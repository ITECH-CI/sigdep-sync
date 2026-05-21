package ci.itechciv.sigdep.sync.extractor;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.contracts.dto.TptRecordDto;
import ci.itechciv.sigdep.sync.extractor.ObsPivot.ObsValue;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * TB Preventive Therapy extractor — covers both encounter types:
 *   - PEC - Suivi TPT  (id=17) -> recordType = FOLLOWUP
 *   - PEC - Issue TPT  (id=18) -> recordType = OUTCOME
 *
 * The form has 218 concepts but we only promote 7 to columns; everything
 * else (signs/symptoms of hepato-/neurotoxicity, …) lands in extra_data.
 */
@Component
@Order(60)
public class TptExtractor implements DataExtractor {

    private static final Logger log = LoggerFactory.getLogger(TptExtractor.class);

    // Encounter type UUIDs
    private static final String TPT_FOLLOWUP_UUID = "aac089d5-c842-11eb-a01e-00090faa0001"; // PEC - Suivi TPT
    private static final String TPT_OUTCOME_UUID  = "aac582f9-c842-11eb-a01e-00090faa0001"; // PEC - Issue TPT
    private static final String FOLLOWUP_VISIT_UUID = "8d5b2be0-c2cc-11de-8d13-0010c6dffd0f"; // PEC - Suivi patient

    // Mapped concepts
    private static final String TPT_FOLLOWUP_DATE_UUID = "165234AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Date de visite TPT
    private static final String TPT_END_DATE_UUID      = "165202AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Date de fin du TPT
    private static final String TPT_OUTCOME_CONCEPT    = "165243AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Issue TPT
    private static final String TPT_ORDER_NUMBER_UUID  = "165244AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Numero d'ordre TPT
    private static final String TPT_STATUS_UUID        = "165049AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Traitement TPT (Début/Fin/En cours/Pas)
    private static final String TPT_REGIMEN_UUID       = "165319AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Protocole TPT (3HP, 6H, INH, …)
    private static final String ADHERENCE_UUID         = "165200AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Observation traitement préventif
    private static final String WEIGHT_UUID            = "5089AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";   // Poids
    private static final String NEXT_VISIT_UUID        = "5096AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";   // Date prochaine visite

    private static final java.util.Set<String> MAPPED = java.util.Set.of(
            TPT_FOLLOWUP_DATE_UUID, TPT_END_DATE_UUID, TPT_OUTCOME_CONCEPT,
            TPT_ORDER_NUMBER_UUID, TPT_STATUS_UUID, TPT_REGIMEN_UUID,
            ADHERENCE_UUID, WEIGHT_UUID, NEXT_VISIT_UUID);

    private final JdbcTemplate localDb;
    private final ObsPivot obsPivot;

    public TptExtractor(@Qualifier("localJdbcTemplate") JdbcTemplate localDb,
                        ObsPivot obsPivot) {
        this.localDb = localDb;
        this.obsPivot = obsPivot;
    }

    @Override public EntityType getEntityType()  { return EntityType.TPT_RECORDS; }
    @Override public String getSourceTable()     { return "encounter"; }
    @Override public String getWatermarkColumn() { return "date_changed"; }
    @Override public boolean isEnabled()         { return true; }

    @Override
    public List<CanonicalRecord> extract(LocalDateTime since, int batchSize) {
        // Both encounter types in one query, paged by effective_changed.
        List<EncounterRow> rows = localDb.query(
                """
                SELECT e.encounter_id              AS encounter_id,
                       e.uuid                      AS encounter_uuid,
                       per.uuid                    AS patient_uuid,
                       e.encounter_datetime        AS encounter_datetime,
                       e.voided                    AS voided,
                       et.uuid                     AS encounter_type_uuid,
                       COALESCE(e.date_changed, e.date_created) AS effective_changed
                FROM encounter e
                JOIN encounter_type et ON et.encounter_type_id = e.encounter_type
                JOIN person  per       ON per.person_id  = e.patient_id
                WHERE et.uuid IN (?, ?)
                  AND COALESCE(e.date_changed, e.date_created) > ?
                ORDER BY effective_changed
                LIMIT ?
                """,
                (rs, i) -> new EncounterRow(
                        rs.getLong("encounter_id"),
                        UUID.fromString(rs.getString("encounter_uuid")),
                        UUID.fromString(rs.getString("patient_uuid")),
                        rs.getTimestamp("encounter_datetime"),
                        rs.getBoolean("voided"),
                        rs.getString("encounter_type_uuid"),
                        rs.getTimestamp("effective_changed").toLocalDateTime()),
                TPT_FOLLOWUP_UUID, TPT_OUTCOME_UUID,
                Timestamp.valueOf(since),
                batchSize);

        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> ids = rows.stream().map(r -> r.encounterId).toList();
        Map<Long, Map<String, ObsValue>> byEncounter = obsPivot.pivot(ids);

        List<CanonicalRecord> out = new ArrayList<>(rows.size());
        for (EncounterRow r : rows) {
            Map<String, ObsValue> obs = byEncounter.getOrDefault(r.encounterId, Map.of());

            String recordType = TPT_OUTCOME_UUID.equals(r.encounterTypeUuid) ? "OUTCOME" : "FOLLOWUP";

            LocalDate followupDate = ObsPivot.asDate(obs.get(TPT_FOLLOWUP_DATE_UUID));
            LocalDate endDate = ObsPivot.asDate(obs.get(TPT_END_DATE_UUID));
            String outcome = ObsPivot.asString(obs.get(TPT_OUTCOME_CONCEPT));
            String orderNumber = ObsPivot.asString(obs.get(TPT_ORDER_NUMBER_UUID));
            String tptStatus = ObsPivot.asString(obs.get(TPT_STATUS_UUID));
            String tptRegimen = ObsPivot.asString(obs.get(TPT_REGIMEN_UUID));
            // The TPT obs are usually captured on the routine "PEC - Suivi
            // patient" encounter, not on the dedicated TPT one — fall back
            // to the most recent suivi-patient encounter for this patient
            // up to and including the TPT encounter's date.
            LocalDate recordDate = r.encounterDatetime == null ? null
                    : r.encounterDatetime.toLocalDateTime().toLocalDate();
            if ((tptStatus == null || tptRegimen == null) && recordDate != null) {
                Map<String, ObsValue> latest = latestSuiviPatientObs(r.patientUuid, recordDate);
                if (tptStatus == null)  tptStatus  = ObsPivot.asString(latest.get(TPT_STATUS_UUID));
                if (tptRegimen == null) tptRegimen = ObsPivot.asString(latest.get(TPT_REGIMEN_UUID));
            }
            String adherence = ObsPivot.asString(obs.get(ADHERENCE_UUID));
            BigDecimal weight = ObsPivot.asDecimal(obs.get(WEIGHT_UUID));
            LocalDate nextVisit = ObsPivot.asDate(obs.get(NEXT_VISIT_UUID));

            // Catch-all (PII already filtered in ObsPivot)
            Map<String, Object> extra = new LinkedHashMap<>();
            for (Map.Entry<String, ObsValue> e : obs.entrySet()) {
                if (MAPPED.contains(e.getKey())) continue;
                extra.put(e.getKey(), ObsPivot.asString(e.getValue()));
            }

            TptRecordDto dto = new TptRecordDto(
                    r.encounterUuid,
                    r.patientUuid,
                    recordType,
                    r.encounterDatetime == null ? null : r.encounterDatetime.toLocalDateTime().toLocalDate(),
                    followupDate,
                    endDate,
                    outcome,
                    orderNumber,
                    tptStatus,
                    tptRegimen,
                    adherence,
                    weight,
                    nextVisit,
                    extra.isEmpty() ? null : extra,
                    r.voided);

            out.add(new CanonicalRecord(EntityType.TPT_RECORDS, r.encounterUuid, r.changed, dto));
        }
        log.debug("Extracted {} TPT record(s) since {}", out.size(), since);
        return out;
    }

    /**
     * Pivot the obs of the most recent "PEC - Suivi patient" encounter for
     * this patient on or before {@code asOf}. Used as a fallback to fill
     * tpt_status / tpt_regimen on TPT records, since those obs are saved on
     * the routine visit, not on the dedicated PEC - Suivi TPT encounter.
     */
    private Map<String, ObsValue> latestSuiviPatientObs(UUID patientUuid, LocalDate asOf) {
        List<Long> ids = localDb.queryForList(
                """
                SELECT e.encounter_id
                FROM encounter e
                JOIN encounter_type et ON et.encounter_type_id = e.encounter_type
                JOIN person  per       ON per.person_id  = e.patient_id
                WHERE et.uuid = ?
                  AND per.uuid = ?
                  AND e.voided = 0
                  AND DATE(e.encounter_datetime) <= ?
                ORDER BY e.encounter_datetime DESC
                LIMIT 1
                """,
                Long.class,
                FOLLOWUP_VISIT_UUID, patientUuid.toString(), Date.valueOf(asOf));
        if (ids.isEmpty()) return Map.of();
        return obsPivot.pivot(ids).getOrDefault(ids.get(0), Map.of());
    }

    private record EncounterRow(
            long encounterId,
            UUID encounterUuid,
            UUID patientUuid,
            Timestamp encounterDatetime,
            boolean voided,
            String encounterTypeUuid,
            LocalDateTime changed
    ) {}
}
