package ci.itechciv.sigdep.sync.extractor;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.contracts.dto.PatientDto;
import ci.itechciv.sigdep.contracts.dto.PatientDto.IdentifierDto;
import ci.itechciv.sigdep.sync.config.SyncProperties;
import java.sql.Date;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Extracts modified patients from a local OpenMRS MySQL database and turns
 * them into canonical PatientDto records ready to be queued in the outbox.
 *
 * Watermark column: GREATEST(person.date_changed, patient.date_changed) so we
 * pick up changes coming from either side. The reference OpenMRS schema is
 * used here; site-specific tweaks (custom person_attribute_type UUIDs, etc.)
 * are wired through configuration.
 */
/**
 * Order matters: patients are the FK target for every other entity, so they
 * must reach the hub first. The hub rejects child records (visits, lab
 * results, ...) with UNKNOWN_PATIENT when the patient row isn't there yet.
 */
@Component
@Order(10)
public class PatientExtractor implements DataExtractor {

    private static final Logger log = LoggerFactory.getLogger(PatientExtractor.class);

    private final JdbcTemplate localDb;
    private final SyncProperties props;

    public PatientExtractor(@Qualifier("localJdbcTemplate") JdbcTemplate localDb,
                            SyncProperties props) {
        this.localDb = localDb;
        this.props = props;
    }

    @Override public EntityType getEntityType()      { return EntityType.PATIENTS; }
    @Override public String getSourceTable()         { return "patient"; }
    @Override public String getWatermarkColumn()     { return "date_changed"; }
    @Override public boolean isEnabled()             { return true; }

    @Override
    public List<CanonicalRecord> extract(LocalDateTime since, int batchSize) {
        // 1. Pick a page of patients ordered by their effective change watermark.
        List<PatientRow> rows = localDb.query(
                """
                SELECT
                  per.uuid                         AS patient_uuid,
                  per.gender                       AS gender,
                  per.birthdate                    AS birthdate,
                  per.birthdate_estimated          AS birthdate_estimated,
                  per.voided                       AS person_voided,
                  pat.voided                       AS patient_voided,
                  GREATEST(
                    COALESCE(per.date_changed, per.date_created),
                    COALESCE(pat.date_changed, pat.date_created)
                  )                                AS effective_changed
                FROM patient pat
                JOIN person  per ON per.person_id = pat.patient_id
                WHERE GREATEST(
                        COALESCE(per.date_changed, per.date_created),
                        COALESCE(pat.date_changed, pat.date_created)
                      ) > ?
                ORDER BY effective_changed
                LIMIT ?
                """,
                (rs, i) -> new PatientRow(
                        UUID.fromString(rs.getString("patient_uuid")),
                        rs.getString("gender"),
                        rs.getDate("birthdate"),
                        rs.getObject("birthdate_estimated") != null && rs.getBoolean("birthdate_estimated"),
                        rs.getBoolean("person_voided") || rs.getBoolean("patient_voided"),
                        rs.getTimestamp("effective_changed").toLocalDateTime()),
                java.sql.Timestamp.valueOf(since),
                batchSize);

        if (rows.isEmpty()) {
            return List.of();
        }

        // 2. Fetch person attributes (Birthplace, Civil Status) for these patients in one shot.
        Map<UUID, Map<String, String>> attrs = fetchPersonAttributes(rows);

        // 3. Fetch patient_identifier rows for these patients in one shot.
        Map<UUID, List<IdentifierDto>> ids = fetchIdentifiers(rows);

        // 4. Build the DTOs.
        List<CanonicalRecord> out = new ArrayList<>(rows.size());
        for (PatientRow r : rows) {
            Map<String, String> a = attrs.getOrDefault(r.uuid, Map.of());
            PatientDto dto = new PatientDto(
                    r.uuid,
                    normalizeSex(r.gender),
                    r.birthdate == null ? null : r.birthdate.toLocalDate(),
                    r.birthdateEstimated,
                    a.get("Birthplace"),
                    null,                       // profession — not yet mapped
                    null,                       // education_level — not yet mapped
                    a.get("Civil Status"),
                    null,                       // religion — not yet mapped
                    ids.getOrDefault(r.uuid, List.of()),
                    r.voided);
            out.add(new CanonicalRecord(EntityType.PATIENTS, r.uuid, r.changed, dto));
        }
        log.debug("Extracted {} patient(s) since {}", out.size(), since);
        return out;
    }

    private Map<UUID, Map<String, String>> fetchPersonAttributes(List<PatientRow> rows) {
        if (rows.isEmpty()) return Map.of();
        String placeholders = String.join(",", rows.stream().map(r -> "?").toList());
        Object[] uuids = rows.stream().map(r -> r.uuid.toString()).toArray();

        Map<UUID, Map<String, String>> result = new HashMap<>();
        localDb.query(
                """
                SELECT per.uuid              AS patient_uuid,
                       pat_t.name            AS attr_type,
                       pa.value              AS raw_value,
                       cn.name               AS concept_name
                FROM person_attribute pa
                JOIN person_attribute_type pat_t ON pat_t.person_attribute_type_id = pa.person_attribute_type_id
                JOIN person per                  ON per.person_id = pa.person_id
                LEFT JOIN concept_name cn        ON cn.concept_id = NULLIF(pa.value, '')
                                                 AND cn.locale_preferred = 1
                                                 AND cn.voided = 0
                WHERE pa.voided = 0
                  AND pat_t.name IN ('Birthplace', 'Civil Status')
                  AND per.uuid IN (%s)
                """.formatted(placeholders),
                rs -> {
                    UUID u = UUID.fromString(rs.getString("patient_uuid"));
                    String type = rs.getString("attr_type");
                    String value = rs.getString("concept_name");
                    if (value == null || value.isBlank()) {
                        value = rs.getString("raw_value");
                    }
                    result.computeIfAbsent(u, k -> new HashMap<>()).put(type, value);
                },
                uuids);
        return result;
    }

    private Map<UUID, List<IdentifierDto>> fetchIdentifiers(List<PatientRow> rows) {
        if (rows.isEmpty()) return Map.of();
        Map<String, String> mapping = props.identifierMapping() == null
                ? Map.of()
                : props.identifierMapping();
        if (mapping.isEmpty()) {
            log.debug("No identifier mapping configured; skipping identifier extraction");
            return Map.of();
        }
        String placeholders = String.join(",", rows.stream().map(r -> "?").toList());
        Object[] uuids = rows.stream().map(r -> r.uuid.toString()).toArray();

        Map<UUID, List<IdentifierDto>> out = new HashMap<>();
        localDb.query(
                """
                SELECT per.uuid                    AS patient_uuid,
                       pi.identifier               AS identifier_value,
                       pit.name                    AS type_name,
                       pi.preferred                AS is_preferred
                FROM patient_identifier pi
                JOIN patient_identifier_type pit ON pit.patient_identifier_type_id = pi.identifier_type
                JOIN person per                  ON per.person_id = pi.patient_id
                WHERE pi.voided = 0 AND per.uuid IN (%s)
                """.formatted(placeholders),
                rs -> {
                    String typeName = rs.getString("type_name");
                    String mappedCode = mapping.get(typeName);
                    if (mappedCode == null) return;
                    UUID u = UUID.fromString(rs.getString("patient_uuid"));
                    out.computeIfAbsent(u, k -> new ArrayList<>()).add(new IdentifierDto(
                            mappedCode,
                            rs.getString("identifier_value"),
                            rs.getBoolean("is_preferred"),
                            null,
                            null));
                },
                uuids);
        return out;
    }

    private static String normalizeSex(String openmrsGender) {
        if (openmrsGender == null || openmrsGender.isBlank()) return "U";
        String g = openmrsGender.trim().toUpperCase();
        return switch (g) {
            case "M", "F" -> g;
            default       -> "U";
        };
    }

    private record PatientRow(
            UUID uuid,
            String gender,
            Date birthdate,
            boolean birthdateEstimated,
            boolean voided,
            LocalDateTime changed
    ) {}
}
