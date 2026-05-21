package ci.itechciv.sigdep.sync.extractor;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.contracts.dto.VisitDto;
import ci.itechciv.sigdep.sync.extractor.ObsPivot.ObsValue;
import java.math.BigDecimal;
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
 * Extracts encounters of type "PEC - Suivi patient" (id=2) from OpenMRS.
 * Each encounter becomes one VisitDto. Concept UUIDs come from the
 * SIGDEP-3 "Fiche de Suivi" HTML form (see infra/scripts/scan_htmlforms.py
 * + concepts_inventory.csv for the full inventory).
 *
 * Mapped concepts land in dedicated columns; everything else falls into
 * extra_data, keyed by concept UUID, so we can promote columns later
 * without touching the agent.
 */
@Component
@Order(30)
public class VisitExtractor implements DataExtractor {

    private static final Logger log = LoggerFactory.getLogger(VisitExtractor.class);

    // Encounter type UUID for "PEC - Suivi patient"
    private static final String FOLLOWUP_VISIT_UUID = "8d5b2be0-c2cc-11de-8d13-0010c6dffd0f";

    // --- Mapped concepts (CIEL/OpenMRS standard, UUIDs are stable) -----------
    // Anthropometry & vitals
    private static final String WEIGHT_KG_UUID         = "5089AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";  // Poids (kg)
    private static final String HEIGHT_CM_UUID         = "5090AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";  // Taille (cm)
    private static final String BMI_UUID               = "1342AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";  // IMC
    private static final String TEMPERATURE_C_UUID     = "5088AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";  // Température (C)
    private static final String PULSE_UUID             = "5087AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";  // Pouls
    private static final String RESP_RATE_UUID         = "5242AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";  // Rythme respiratoire
    private static final String BP_SYSTOLIC_UUID       = "5085AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";  // Tension artérielle systolique
    private static final String BP_DIASTOLIC_UUID      = "5086AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";  // Tension artérielle diastolique
    private static final String MUAC_UUID              = "163586AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Périmètre brachial

    // HIV staging & monitoring
    private static final String WHO_STAGE_UUID         = "5356AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";  // Current WHO HIV stage
    private static final String VIRAL_LOAD_UUID        = "856AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";   // VIH charge virale
    private static final String VIRAL_LOAD_DATE_UUID   = "165015AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Date dernière CV
    private static final String CD4_COUNT_UUID         = "159375AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Patient reported CD4
    private static final String CD4_DATE_UUID          = "160103AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Last CD4 count date

    // ARV / cotrim
    private static final String ARV_REGIMEN_UUID       = "162240AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Regime ARV
    private static final String ARV_TREATMENT_DAYS_UUID = "164590AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Nombre de jour de traitement ARV
    private static final String COTRIM_DAYS_UUID       = "164578AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Nombre de jours de traitement au cotrimoxazole

    // Visit cycle & status
    private static final String NEXT_VISIT_DATE_UUID   = "5096AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";  // Return visit date
    private static final String BREASTFEEDING_UUID     = "164764AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Allaitement en cours

    // TPT — captured on the routine PEC - Suivi patient encounters
    private static final String TPT_STATUS_UUID        = "165049AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Traitement TPT (Début/Fin/En cours/Pas)
    private static final String TPT_REGIMEN_UUID       = "165319AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Protocole TPT (3HP, 6H, INH, …)

    // IVSA — captured on the same PEC - Suivi patient encounter. Concept
    // IDs are queried by numeric concept_id rather than UUID because these
    // are SIGDEP-local concepts; the htmlforms use the numeric form too.
    private static final int IVSA_MSD_CONCEPT_ID            = 165063; // Modèle de soins différenciés (1/2/3)
    private static final int IVSA_SUCCESS_DATE_CONCEPT_ID   = 165357; // Date de confirmation succès IVSA
    private static final int IVSA_ALERT_SIGNS_CONCEPT_ID    = 165369; // Signes d'alerte (multi)
    private static final int IVSA_NEURO_SIGNS_CONCEPT_ID    = 165324; // Évaluation neurologique (multi)

    private static final java.util.Set<String> MAPPED = java.util.Set.of(
            WEIGHT_KG_UUID, HEIGHT_CM_UUID, BMI_UUID,
            TEMPERATURE_C_UUID, PULSE_UUID, RESP_RATE_UUID,
            BP_SYSTOLIC_UUID, BP_DIASTOLIC_UUID, MUAC_UUID,
            WHO_STAGE_UUID, VIRAL_LOAD_UUID, VIRAL_LOAD_DATE_UUID,
            CD4_COUNT_UUID, CD4_DATE_UUID,
            ARV_REGIMEN_UUID, ARV_TREATMENT_DAYS_UUID, COTRIM_DAYS_UUID,
            NEXT_VISIT_DATE_UUID, BREASTFEEDING_UUID,
            TPT_STATUS_UUID, TPT_REGIMEN_UUID);

    private final JdbcTemplate localDb;
    private final ObsPivot obsPivot;

    public VisitExtractor(@Qualifier("localJdbcTemplate") JdbcTemplate localDb,
                          ObsPivot obsPivot) {
        this.localDb = localDb;
        this.obsPivot = obsPivot;
    }

    @Override public EntityType getEntityType()  { return EntityType.VISITS; }
    @Override public String getSourceTable()     { return "encounter"; }
    @Override public String getWatermarkColumn() { return "date_changed"; }
    @Override public boolean isEnabled()         { return true; }

    @Override
    public List<CanonicalRecord> extract(LocalDateTime since, int batchSize) {
        List<EncounterRow> rows = localDb.query(
                """
                SELECT e.encounter_id              AS encounter_id,
                       e.uuid                      AS encounter_uuid,
                       per.uuid                    AS patient_uuid,
                       e.encounter_datetime        AS encounter_datetime,
                       e.voided                    AS voided,
                       et.name                     AS encounter_type_name,
                       COALESCE(e.date_changed, e.date_created) AS effective_changed
                FROM encounter e
                JOIN encounter_type et ON et.encounter_type_id = e.encounter_type
                JOIN patient pat       ON pat.patient_id = e.patient_id
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
                        rs.getBoolean("voided"),
                        rs.getString("encounter_type_name"),
                        rs.getTimestamp("effective_changed").toLocalDateTime()),
                FOLLOWUP_VISIT_UUID,
                Timestamp.valueOf(since),
                batchSize);

        if (rows.isEmpty()) {
            return List.of();
        }

        List<Long> encounterIds = rows.stream().map(r -> r.encounterId).toList();
        Map<Long, Map<String, ObsValue>> byEncounter = obsPivot.pivot(encounterIds);
        Map<Long, IvsaData> ivsaByEncounter = fetchIvsaData(encounterIds);

        List<CanonicalRecord> out = new ArrayList<>(rows.size());
        for (EncounterRow r : rows) {
            Map<String, ObsValue> obs = byEncounter.getOrDefault(r.encounterId, Map.of());
            IvsaData ivsa = ivsaByEncounter.getOrDefault(r.encounterId, IvsaData.EMPTY);

            // Anthropometry
            BigDecimal weight = ObsPivot.asDecimal(obs.get(WEIGHT_KG_UUID));
            BigDecimal height = ObsPivot.asDecimal(obs.get(HEIGHT_CM_UUID));
            BigDecimal bmi = ObsPivot.asDecimal(obs.get(BMI_UUID));
            BigDecimal muac = ObsPivot.asDecimal(obs.get(MUAC_UUID));

            // Vitals
            BigDecimal temperature = ObsPivot.asDecimal(obs.get(TEMPERATURE_C_UUID));
            Short pulse = ObsPivot.asShort(obs.get(PULSE_UUID));
            Short respRate = ObsPivot.asShort(obs.get(RESP_RATE_UUID));
            Short bpSys = ObsPivot.asShort(obs.get(BP_SYSTOLIC_UUID));
            Short bpDia = ObsPivot.asShort(obs.get(BP_DIASTOLIC_UUID));

            // HIV staging & monitoring
            Short whoStage = ObsPivot.asShort(obs.get(WHO_STAGE_UUID));
            BigDecimal viralLoad = ObsPivot.asDecimal(obs.get(VIRAL_LOAD_UUID));
            LocalDate viralLoadDate = ObsPivot.asDate(obs.get(VIRAL_LOAD_DATE_UUID));
            Integer cd4Count = ObsPivot.asInt(obs.get(CD4_COUNT_UUID));
            LocalDate cd4Date = ObsPivot.asDate(obs.get(CD4_DATE_UUID));

            // ARV / cotrim
            String arvRegimen = ObsPivot.asString(obs.get(ARV_REGIMEN_UUID));
            Short arvDays = ObsPivot.asShort(obs.get(ARV_TREATMENT_DAYS_UUID));
            Short cotrimDays = ObsPivot.asShort(obs.get(COTRIM_DAYS_UUID));

            // Visit cycle & status
            LocalDate nextVisit = ObsPivot.asDate(obs.get(NEXT_VISIT_DATE_UUID));
            String breastfeeding = ObsPivot.asString(obs.get(BREASTFEEDING_UUID));
            String tptStatus = ObsPivot.asString(obs.get(TPT_STATUS_UUID));
            String tptRegimen = ObsPivot.asString(obs.get(TPT_REGIMEN_UUID));

            // Everything that wasn't mapped goes to extra_data, keyed by concept UUID.
            Map<String, Object> extra = new LinkedHashMap<>();
            for (Map.Entry<String, ObsValue> e : obs.entrySet()) {
                if (MAPPED.contains(e.getKey())) continue;
                extra.put(e.getKey(), ObsPivot.asString(e.getValue()));
            }

            VisitDto dto = new VisitDto(
                    r.encounterUuid,
                    r.patientUuid,
                    r.encounterTypeName,
                    r.encounterDatetime == null ? null : r.encounterDatetime.toLocalDateTime().toLocalDate(),
                    nextVisit,
                    null,                   // tbScreeningResult — not yet in the form mapping
                    null,                   // tbDiagnosed
                    null,                   // tbTreatmentStatus
                    null,                   // tbTreatmentStartDate
                    whoStage,
                    null,                   // cdcStage — only on the initial form
                    null,                   // ctxPrescribed — derived later
                    null,                   // ctxStartDate
                    ivsa.successDate,
                    null,                   // isPregnant
                    null,                   // isBreastfeeding (legacy boolean) — not extracted; use breastfeedingStatus
                    weight,
                    height,
                    bmi,
                    muac,
                    arvRegimen,
                    arvDays,
                    cotrimDays,
                    temperature,
                    pulse,
                    respRate,
                    bpSys,
                    bpDia,
                    viralLoad,
                    viralLoadDate,
                    cd4Count,
                    cd4Date,
                    breastfeeding,
                    tptStatus,
                    tptRegimen,
                    ivsa.msdLabel,
                    ivsa.alertSignsCount,
                    ivsa.neuroSignsCount,
                    extra.isEmpty() ? null : extra,
                    r.voided);

            out.add(new CanonicalRecord(EntityType.VISITS, r.encounterUuid, r.changed, dto));
        }
        log.debug("Extracted {} visit(s) since {}", out.size(), since);
        return out;
    }

    private record EncounterRow(
            long encounterId,
            UUID encounterUuid,
            UUID patientUuid,
            Timestamp encounterDatetime,
            boolean voided,
            String encounterTypeName,
            LocalDateTime changed
    ) {}

    /**
     * Side query for IVSA — we read 4 facts per encounter directly from the
     * obs table by numeric concept_id, because:
     *   (a) the IVSA concepts are SIGDEP-local and don't have stable UUIDs,
     *   (b) ObsPivot returns one ObsValue per concept, but the alert/neuro
     *       panels are multi-coded (one row per ticked checkbox), so we need
     *       to count rows, not pivot them.
     */
    private Map<Long, IvsaData> fetchIvsaData(List<Long> encounterIds) {
        if (encounterIds.isEmpty()) return Map.of();

        String inList = encounterIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
        Map<Long, IvsaData.Builder> builders = new java.util.HashMap<>();
        for (Long id : encounterIds) builders.put(id, new IvsaData.Builder());

        // MSD code (single value, value_coded) — decode to label.
        localDb.query(
                "SELECT o.encounter_id, o.value_coded"
                        + " FROM obs o"
                        + " WHERE o.voided = 0 AND o.concept_id = ?"
                        + "   AND o.encounter_id IN (" + inList + ")",
                rs -> {
                    long encId = rs.getLong("encounter_id");
                    long valueCoded = rs.getLong("value_coded");
                    if (rs.wasNull()) return;
                    builders.get(encId).msdConceptId = (int) valueCoded;
                },
                IVSA_MSD_CONCEPT_ID);

        // Success confirmation date (single date)
        localDb.query(
                "SELECT o.encounter_id, o.value_datetime"
                        + " FROM obs o"
                        + " WHERE o.voided = 0 AND o.concept_id = ?"
                        + "   AND o.encounter_id IN (" + inList + ")",
                rs -> {
                    long encId = rs.getLong("encounter_id");
                    java.sql.Timestamp ts = rs.getTimestamp("value_datetime");
                    if (ts == null) return;
                    builders.get(encId).successDate = ts.toLocalDateTime().toLocalDate();
                },
                IVSA_SUCCESS_DATE_CONCEPT_ID);

        // Multi-coded panels: count one row per ticked checkbox.
        localDb.query(
                "SELECT o.encounter_id, count(*) AS n"
                        + " FROM obs o"
                        + " WHERE o.voided = 0 AND o.concept_id = ?"
                        + "   AND o.encounter_id IN (" + inList + ")"
                        + " GROUP BY o.encounter_id",
                rs -> { builders.get(rs.getLong("encounter_id")).alertSignsCount = (short) rs.getInt("n"); },
                IVSA_ALERT_SIGNS_CONCEPT_ID);

        localDb.query(
                "SELECT o.encounter_id, count(*) AS n"
                        + " FROM obs o"
                        + " WHERE o.voided = 0 AND o.concept_id = ?"
                        + "   AND o.encounter_id IN (" + inList + ")"
                        + " GROUP BY o.encounter_id",
                rs -> { builders.get(rs.getLong("encounter_id")).neuroSignsCount = (short) rs.getInt("n"); },
                IVSA_NEURO_SIGNS_CONCEPT_ID);

        Map<Long, IvsaData> out = new java.util.HashMap<>();
        for (var e : builders.entrySet()) out.put(e.getKey(), e.getValue().build());
        return out;
    }

    /** Decoded IVSA facts for one encounter. */
    private record IvsaData(
            String msdLabel,
            LocalDate successDate,
            Short alertSignsCount,
            Short neuroSignsCount
    ) {
        static final IvsaData EMPTY = new IvsaData(null, null, null, null);

        static String decodeMsd(Integer code) {
            if (code == null) return null;
            return switch (code) {
                case 1 -> "Standard";
                case 2 -> "IVSA";
                case 3 -> "Échec thérapeutique";
                default -> null;
            };
        }

        static final class Builder {
            Integer msdConceptId;
            LocalDate successDate;
            Short alertSignsCount;
            Short neuroSignsCount;

            IvsaData build() {
                return new IvsaData(decodeMsd(msdConceptId), successDate,
                        alertSignsCount, neuroSignsCount);
            }
        }
    }
}
