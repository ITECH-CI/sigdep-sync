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
 * Watermark column: GREATEST over person/patient date_changed ET date_voided
 * (chacun retombant sur date_created via COALESCE) — on capte ainsi les
 * modifications ET les suppressions logiques (void), qui autrement ne feraient
 * pas avancer le watermark et resteraient invisibles côté hub (SYNC-10). The
 * reference OpenMRS schema is used here; site-specific tweaks (custom
 * person_attribute_type UUIDs, etc.) are wired through configuration.
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

    // Types d'identifiant non mappés déjà signalés (WARN), pour ne pas répéter
    // l'alerte à chaque page/cycle : on ne réalerte que sur un type NOUVEAU.
    // ConcurrentHashMap.newKeySet : l'extraction peut tourner sur des threads
    // distincts d'un cycle à l'autre.
    private final java.util.Set<String> reportedUnmappedTypes =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

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
    public List<CanonicalRecord> extract(SyncCursor cursor, int batchSize) {
        LocalDateTime sinceDate = cursor.watermark();
        long sinceId = cursor.lastId();

        // 1. Pick a page of patients ordered by their effective change watermark.
        List<PatientRow> rows = localDb.query(
                """
                SELECT
                  per.person_id                    AS person_id,
                  per.uuid                         AS patient_uuid,
                  per.gender                       AS gender,
                  per.birthdate                    AS birthdate,
                  per.birthdate_estimated          AS birthdate_estimated,
                  per.voided                       AS person_voided,
                  pat.voided                       AS patient_voided,
                  GREATEST(
                    COALESCE(per.date_changed, per.date_created),
                    COALESCE(pat.date_changed, pat.date_created),
                    COALESCE(per.date_voided, per.date_created),
                    COALESCE(pat.date_voided, pat.date_created)
                  )                                AS effective_changed
                FROM patient pat
                JOIN person  per ON per.person_id = pat.patient_id
                WHERE GREATEST(
                        COALESCE(per.date_changed, per.date_created),
                        COALESCE(pat.date_changed, pat.date_created),
                        COALESCE(per.date_voided, per.date_created),
                        COALESCE(pat.date_voided, pat.date_created)
                      ) > ?
                   OR (GREATEST(
                        COALESCE(per.date_changed, per.date_created),
                        COALESCE(pat.date_changed, pat.date_created),
                        COALESCE(per.date_voided, per.date_created),
                        COALESCE(pat.date_voided, pat.date_created)
                      ) = ? AND per.person_id > ?)
                ORDER BY effective_changed, per.person_id
                LIMIT ?
                """,
                (rs, i) -> new PatientRow(
                        rs.getLong("person_id"),
                        UUID.fromString(rs.getString("patient_uuid")),
                        rs.getString("gender"),
                        rs.getDate("birthdate"),
                        rs.getObject("birthdate_estimated") != null && rs.getBoolean("birthdate_estimated"),
                        rs.getBoolean("person_voided") || rs.getBoolean("patient_voided"),
                        rs.getTimestamp("effective_changed").toLocalDateTime()),
                java.sql.Timestamp.valueOf(sinceDate),
                java.sql.Timestamp.valueOf(sinceDate),
                sinceId,
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
            out.add(new CanonicalRecord(EntityType.PATIENTS, r.uuid, r.changed, r.personId, dto));
        }
        log.debug("Extracted {} patient(s) since {}", out.size(), sinceDate);
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
        // Types d'identifiant rencontrés mais absents du mapping, avec le
        // nombre de lignes concernées sur CETTE page. Un type non mappé est
        // exclu du push (le code cible est inconnu) ; on le remonte pour que
        // l'exclusion ne soit pas silencieuse (SYNC-11).
        Map<String, Integer> unmappedThisPage = new HashMap<>();
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
                    if (mappedCode == null) {
                        unmappedThisPage.merge(typeName, 1, Integer::sum);
                        return;
                    }
                    UUID u = UUID.fromString(rs.getString("patient_uuid"));
                    out.computeIfAbsent(u, k -> new ArrayList<>()).add(new IdentifierDto(
                            mappedCode,
                            rs.getString("identifier_value"),
                            rs.getBoolean("is_preferred"),
                            null,
                            null));
                },
                uuids);
        reportUnmappedIdentifierTypes(unmappedThisPage);
        return out;
    }

    /**
     * Rend visibles les types d'identifiant exclus faute de mapping (SYNC-11).
     * Sans cela, un site nommant son type ARV autrement que les clés de
     * {@code identifier-mapping} verrait TOUS ses codes ARV disparaître en
     * silence. On loggue un WARN la PREMIÈRE fois qu'un type non mappé apparaît
     * (dédup via {@link #reportedUnmappedTypes}), avec le nombre de lignes
     * concernées sur la page — assez pour diagnostiquer sans spammer le journal.
     */
    private void reportUnmappedIdentifierTypes(Map<String, Integer> unmappedThisPage) {
        for (Map.Entry<String, Integer> e : unmappedThisPage.entrySet()) {
            if (reportedUnmappedTypes.add(e.getKey())) {
                log.warn("Type d'identifiant OpenMRS \"{}\" absent de identifier-mapping : "
                        + "{} identifiant(s) exclus de la synchro sur cette page (et les "
                        + "suivantes tant que le mapping n'est pas complété). Ajouter une "
                        + "entrée sigdep.sync.identifier-mapping[\"{}\"] = <code cible> si "
                        + "ces identifiants doivent remonter.",
                        e.getKey(), e.getValue(), e.getKey());
            }
        }
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
            long personId,
            UUID uuid,
            String gender,
            Date birthdate,
            boolean birthdateEstimated,
            boolean voided,
            LocalDateTime changed
    ) {}
}
