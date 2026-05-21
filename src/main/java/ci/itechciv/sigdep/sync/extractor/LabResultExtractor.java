package ci.itechciv.sigdep.sync.extractor;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.contracts.dto.LabResultDto;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Extracts encounters of type "Biologie-Bilan" (id=8) from OpenMRS.
 *
 * Different shape from the other extractors: each *obs* of a bilan
 * produces its own CanonicalRecord with the obs UUID as source_uuid.
 * A single bilan with CD4 + viral load + hemoglobin therefore generates
 * three DTOs. The hub stores them in core.lab_results, one row per
 * (test, exam_date), keyed by (site_id, source_uuid).
 *
 * The watermark for paging is on the *encounter* though, not the obs:
 * we page through bilans in encounter.date_changed order so a partial
 * cycle never strands obs of the same encounter across cycles.
 */
@Component
@Order(50)
public class LabResultExtractor implements DataExtractor {

    private static final Logger log = LoggerFactory.getLogger(LabResultExtractor.class);

    // Encounter type UUID for "Biologie-Bilan"
    private static final String LAB_ENCOUNTER_UUID = "b2750363-7c00-4ece-bceb-47ab09b8d21b";

    private final JdbcTemplate localDb;

    public LabResultExtractor(@Qualifier("localJdbcTemplate") JdbcTemplate localDb) {
        this.localDb = localDb;
    }

    @Override public EntityType getEntityType()  { return EntityType.LAB_RESULTS; }
    @Override public String getSourceTable()     { return "encounter"; }
    @Override public String getWatermarkColumn() { return "date_changed"; }
    @Override public boolean isEnabled()         { return true; }

    @Override
    public List<CanonicalRecord> extract(LocalDateTime since, int batchSize) {
        // Page on encounters; results below come from a join on obs of those encounters.
        // Use COALESCE(date_changed, date_created) like the other extractors.
        List<EncounterRow> encounters = localDb.query(
                """
                SELECT e.encounter_id        AS encounter_id,
                       e.uuid                AS encounter_uuid,
                       per.uuid              AS patient_uuid,
                       e.encounter_datetime  AS encounter_datetime,
                       COALESCE(e.date_changed, e.date_created) AS effective_changed
                FROM encounter e
                JOIN encounter_type et ON et.encounter_type_id = e.encounter_type
                JOIN person  per       ON per.person_id  = e.patient_id
                WHERE et.uuid = ?
                  AND COALESCE(e.date_changed, e.date_created) > ?
                ORDER BY effective_changed
                LIMIT ?
                """,
                (rs, i) -> new EncounterRow(
                        rs.getLong("encounter_id"),
                        UUID.fromString(rs.getString("encounter_uuid")),
                        UUID.fromString(rs.getString("patient_uuid")),
                        rs.getTimestamp("encounter_datetime"),
                        rs.getTimestamp("effective_changed").toLocalDateTime()),
                LAB_ENCOUNTER_UUID,
                Timestamp.valueOf(since),
                batchSize);

        if (encounters.isEmpty()) {
            return List.of();
        }

        // Build the "?,?,..." for the IN clause and load all obs at once.
        String placeholders = String.join(",", encounters.stream().map(e -> "?").toList());
        Object[] ids = encounters.stream().map(e -> e.encounterId).toArray();

        List<ObsRow> obsRows = localDb.query(
                """
                SELECT o.obs_id              AS obs_id,
                       o.uuid                AS obs_uuid,
                       o.encounter_id        AS encounter_id,
                       c.uuid                AS concept_uuid,
                       o.value_numeric       AS value_numeric,
                       o.value_text          AS value_text,
                       o.value_coded         AS value_coded,
                       cn_num.units          AS unit,
                       o.voided              AS voided,
                       (
                         SELECT cn.name FROM concept_name cn
                         WHERE cn.concept_id = o.concept_id AND cn.voided = 0
                         ORDER BY
                           CASE
                             WHEN cn.locale = 'fr' AND cn.concept_name_type = 'FULLY_SPECIFIED' THEN 1
                             WHEN cn.locale = 'fr' THEN 2
                             WHEN cn.locale = 'en' AND cn.concept_name_type = 'FULLY_SPECIFIED' THEN 3
                             WHEN cn.locale = 'en' THEN 4
                             ELSE 5
                           END,
                           CASE WHEN cn.locale_preferred = 1 THEN 0 ELSE 1 END,
                           cn.concept_name_id
                         LIMIT 1
                       ) AS test_name,
                       (
                         SELECT cn.name FROM concept_name cn
                         WHERE cn.concept_id = o.value_coded AND cn.voided = 0
                         ORDER BY
                           CASE
                             WHEN cn.locale = 'fr' AND cn.concept_name_type = 'FULLY_SPECIFIED' THEN 1
                             WHEN cn.locale = 'fr' THEN 2
                             WHEN cn.locale = 'en' AND cn.concept_name_type = 'FULLY_SPECIFIED' THEN 3
                             WHEN cn.locale = 'en' THEN 4
                             ELSE 5
                           END,
                           cn.concept_name_id
                         LIMIT 1
                       ) AS coded_name
                FROM obs o
                JOIN concept c           ON c.concept_id      = o.concept_id
                LEFT JOIN concept_numeric cn_num ON cn_num.concept_id = o.concept_id
                WHERE o.encounter_id IN (%s)
                """.formatted(placeholders),
                (rs, i) -> new ObsRow(
                        UUID.fromString(rs.getString("obs_uuid")),
                        rs.getLong("encounter_id"),
                        rs.getString("concept_uuid"),
                        rs.getString("test_name"),
                        rs.getBigDecimal("value_numeric"),
                        rs.getString("value_text"),
                        rs.getString("coded_name"),
                        rs.getString("unit"),
                        rs.getBoolean("voided")),
                ids);

        // Index encounters by id so we can stamp each obs with its bilan info.
        java.util.Map<Long, EncounterRow> byId = new java.util.HashMap<>();
        for (EncounterRow e : encounters) byId.put(e.encounterId, e);

        List<CanonicalRecord> out = new ArrayList<>(obsRows.size());
        for (ObsRow o : obsRows) {
            // Skip obs with no value at all.
            if (o.valueNumeric == null && (o.valueText == null || o.valueText.isBlank())
                    && (o.codedName == null || o.codedName.isBlank())) {
                continue;
            }
            EncounterRow enc = byId.get(o.encounterId);
            if (enc == null) continue;

            LocalDate examDate = enc.encounterDatetime == null
                    ? null
                    : enc.encounterDatetime.toLocalDateTime().toLocalDate();

            LabResultDto dto = new LabResultDto(
                    o.obsUuid,
                    enc.patientUuid,
                    enc.encounterUuid,
                    examDate,
                    o.conceptUuid,
                    o.testName,
                    o.valueNumeric,
                    nullIfBlank(o.valueText),
                    nullIfBlank(o.codedName),
                    nullIfBlank(o.unit),
                    o.voided);

            // Watermark for the per-record outbox flush — use the encounter's
            // effective_changed so resync of a bilan stays atomic.
            out.add(new CanonicalRecord(EntityType.LAB_RESULTS, o.obsUuid, enc.changed, dto));
        }
        log.debug("Extracted {} lab line(s) from {} bilan(s) since {}",
                out.size(), encounters.size(), since);
        return out;
    }

    private static String nullIfBlank(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }

    private record EncounterRow(
            long encounterId,
            UUID encounterUuid,
            UUID patientUuid,
            Timestamp encounterDatetime,
            LocalDateTime changed
    ) {}

    private record ObsRow(
            UUID obsUuid,
            long encounterId,
            String conceptUuid,
            String testName,
            BigDecimal valueNumeric,
            String valueText,
            String codedName,
            String unit,
            boolean voided
    ) {}
}
