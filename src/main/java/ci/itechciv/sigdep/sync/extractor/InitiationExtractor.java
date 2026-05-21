package ci.itechciv.sigdep.sync.extractor;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.contracts.dto.PediatricInitiationDto;
import ci.itechciv.sigdep.contracts.dto.TreatmentInitiationDto;
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
 * Extracts encounters of type "PEC - Ouverture de dossier" (id=1) from OpenMRS.
 * One DTO per enrolment. Mapped concepts come from the SIGDEP-3 "Fiche initiale
 * adulte" HTML form (see infra/scripts/scan_htmlforms.py).
 *
 * The DTO also carries four profile fields (marital_status, birth_place,
 * education_level, religion) that the form fills in but ultimately belong to
 * core.patients — the hub propagates them on upsert (see InitiationWriter).
 */
@Component
@Order(20)
public class InitiationExtractor implements DataExtractor {

    private static final Logger log = LoggerFactory.getLogger(InitiationExtractor.class);

    // Encounter type UUID for "PEC - Ouverture de dossier"
    private static final String OPENING_FILE_UUID = "8d5b27bc-c2cc-11de-8d13-0010c6dffd0f";

    // --- Mapped concepts ------------------------------------------------------

    // Dates
    private static final String ARV_START_DATE_UUID  = "159599AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // DATE DE DEBUT ARV
    private static final String HIV_TEST_DATE_UUID   = "160554AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Date diagnostique VIH

    // HIV identity
    private static final String HIV_TYPE_UUID        = "163623AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Resultat Type VIH
    private static final String ENTRY_POINT_UUID     = "164523AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Point d'entrée

    // Clinical snapshot at enrolment
    private static final String WHO_STAGE_INIT_UUID  = "164487AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Stade clinique OMS
    private static final String CDC_STAGE_INIT_UUID  = "1209AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Stadification CDC
    private static final String ARV_REGIMEN_INIT_UUID = "162240AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Regime ARV
    private static final String WEIGHT_INIT_UUID     = "5089AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";   // POIDS (KG)
    private static final String CD4_INIT_UUID        = "5497AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";   // Numération CD4
    private static final String CD4_PCT_INIT_UUID    = "730AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";    // CD4%
    private static final String KARNOFSKY_UUID       = "5283AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";   // Score de Karnofsky

    // Patient pathway
    private static final String REFERRED_UUID        = "1648AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // PATIENT REFERE
    private static final String REFERRED_ORIGIN_UUID = "164562AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Référé origine
    private static final String TREATMENT_MOTIVE_UUID = "162225AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Motif mise sous TARV

    // Partner / counselling
    private static final String PARTNER_HIV_STATUS_UUID = "1436AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Statut sero partenaire

    // Antecedents
    private static final String TB_HISTORY_UUID      = "1389AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Antécédent Tuberculose
    private static final String ARV_HISTORY_UUID     = "164540AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Antécédent ARV
    private static final String TRANSFUSION_HISTORY_UUID = "1871AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Antécédent transfusion

    // PTME history
    private static final String PTME_HISTORY_UUID    = "163450AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Antécédent PTME
    private static final String PTME_REGIMEN_HIST_UUID = "1400AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Régime antécédent PTME
    private static final String PTME_HISTORY_DATE_UUID = "164588AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Date PTME

    // --- Profile concepts (propagated to core.patients) ---------------------
    private static final String MARITAL_STATUS_UUID  = "1054AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Etat civil
    private static final String BIRTH_PLACE_UUID     = "164444AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Lieu de naissance
    private static final String EDUCATION_LEVEL_UUID = "1712AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Niveau d'éducation
    private static final String RELIGION_UUID        = "162894AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Religion
    private static final String PROFESSION_UUID      = "162904AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // Profession (texte libre)

    // --- Pediatric concepts (Fiche initiale enfant) -------------------------
    // Birth / clinical baby
    private static final String BIRTH_WEIGHT_UUID       = "1406AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String BIRTH_LENGTH_UUID       = "1835AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String HEAD_CIRC_UUID          = "163587AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String APGAR_UUID              = "1504AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String DELIVERY_MODE_UUID      = "163568AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String DELIVERED_FACILITY_UUID = "164738AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    // Mother PTME
    private static final String MOTHER_RECEIVED_PTME_UUID = "164475AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String MOTHER_HIV_STATUS_UUID    = "1396AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String MOTHER_VITAL_STATUS_UUID  = "163646AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String MOTHER_PTME_REGIMEN_UUID  = "165213AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String INFANT_PROPHY_GIVEN_UUID  = "163605AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String INFANT_PROTOCOL_UUID      = "163638AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    // Nutrition / suivi
    private static final String FEEDING_MODE_UUID         = "163584AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String WEANING_DATE_UUID         = "163510AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String VACCINATIONS_UUID         = "1197AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    // Family / context (coded only)
    private static final String FATHER_VITAL_UUID         = "163647AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String FATHER_EDUCATION_UUID     = "164457AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String FATHER_ACTIVITY_UUID      = "164462AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String MOTHER_EDUCATION_UUID     = "164458AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String MOTHER_ACTIVITY_UUID      = "164461AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String GUARDIAN_VITAL_UUID       = "164467AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String GUARDIAN_EDUCATION_UUID   = "164468AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String GUARDIAN_ACTIVITY_UUID    = "164470AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String GUARDIAN_HIV_STATUS_UUID  = "164471AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    // Other
    private static final String ADMISSION_DATE_UUID       = "164488AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String SCHOOLING_STATUS_UUID     = "164746AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String SCREENING_CODE_UUID       = "164072AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private static final java.util.Set<String> MAPPED = java.util.Set.of(
            ARV_START_DATE_UUID, HIV_TEST_DATE_UUID,
            HIV_TYPE_UUID, ENTRY_POINT_UUID,
            WHO_STAGE_INIT_UUID, CDC_STAGE_INIT_UUID, ARV_REGIMEN_INIT_UUID,
            WEIGHT_INIT_UUID, CD4_INIT_UUID, CD4_PCT_INIT_UUID, KARNOFSKY_UUID,
            REFERRED_UUID, REFERRED_ORIGIN_UUID, TREATMENT_MOTIVE_UUID,
            PARTNER_HIV_STATUS_UUID,
            TB_HISTORY_UUID, ARV_HISTORY_UUID, TRANSFUSION_HISTORY_UUID,
            PTME_HISTORY_UUID, PTME_REGIMEN_HIST_UUID, PTME_HISTORY_DATE_UUID,
            MARITAL_STATUS_UUID, BIRTH_PLACE_UUID, EDUCATION_LEVEL_UUID, RELIGION_UUID,
            PROFESSION_UUID);

    private static final java.util.Set<String> MAPPED_PEDIATRIC = java.util.Set.of(
            BIRTH_WEIGHT_UUID, BIRTH_LENGTH_UUID, HEAD_CIRC_UUID, APGAR_UUID,
            DELIVERY_MODE_UUID, DELIVERED_FACILITY_UUID,
            MOTHER_RECEIVED_PTME_UUID, MOTHER_HIV_STATUS_UUID, MOTHER_VITAL_STATUS_UUID,
            MOTHER_PTME_REGIMEN_UUID, INFANT_PROPHY_GIVEN_UUID, INFANT_PROTOCOL_UUID,
            FEEDING_MODE_UUID, WEANING_DATE_UUID, VACCINATIONS_UUID,
            FATHER_VITAL_UUID, FATHER_EDUCATION_UUID, FATHER_ACTIVITY_UUID,
            MOTHER_EDUCATION_UUID, MOTHER_ACTIVITY_UUID,
            GUARDIAN_VITAL_UUID, GUARDIAN_EDUCATION_UUID, GUARDIAN_ACTIVITY_UUID,
            GUARDIAN_HIV_STATUS_UUID,
            ADMISSION_DATE_UUID, SCHOOLING_STATUS_UUID, SCREENING_CODE_UUID);

    private final JdbcTemplate localDb;
    private final ObsPivot obsPivot;

    public InitiationExtractor(@Qualifier("localJdbcTemplate") JdbcTemplate localDb,
                               ObsPivot obsPivot) {
        this.localDb = localDb;
        this.obsPivot = obsPivot;
    }

    @Override public EntityType getEntityType()  { return EntityType.TREATMENT_INITIATIONS; }
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
                        rs.getBoolean("voided"),
                        rs.getTimestamp("effective_changed").toLocalDateTime()),
                OPENING_FILE_UUID,
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

            // Dates
            LocalDate arvInit = ObsPivot.asDate(obs.get(ARV_START_DATE_UUID));
            LocalDate hivTestDate = ObsPivot.asDate(obs.get(HIV_TEST_DATE_UUID));

            // HIV identity
            String hivType = ObsPivot.asString(obs.get(HIV_TYPE_UUID));
            String entryPoint = ObsPivot.asString(obs.get(ENTRY_POINT_UUID));

            // Clinical snapshot
            String whoStage = ObsPivot.asString(obs.get(WHO_STAGE_INIT_UUID));
            String cdcStage = ObsPivot.asString(obs.get(CDC_STAGE_INIT_UUID));
            String arvRegimen = ObsPivot.asString(obs.get(ARV_REGIMEN_INIT_UUID));
            BigDecimal weight = ObsPivot.asDecimal(obs.get(WEIGHT_INIT_UUID));
            Integer cd4 = ObsPivot.asInt(obs.get(CD4_INIT_UUID));
            BigDecimal cd4Pct = ObsPivot.asDecimal(obs.get(CD4_PCT_INIT_UUID));
            Short karnofsky = ObsPivot.asShort(obs.get(KARNOFSKY_UUID));

            // Pathway
            String referred = ObsPivot.asString(obs.get(REFERRED_UUID));
            String referredOrigin = ObsPivot.asString(obs.get(REFERRED_ORIGIN_UUID));
            String treatmentMotive = ObsPivot.asString(obs.get(TREATMENT_MOTIVE_UUID));

            // Partner
            String partnerStatus = ObsPivot.asString(obs.get(PARTNER_HIV_STATUS_UUID));

            // Antecedents
            String tbHistory = ObsPivot.asString(obs.get(TB_HISTORY_UUID));
            String arvHistory = ObsPivot.asString(obs.get(ARV_HISTORY_UUID));
            String transfusionHistory = ObsPivot.asString(obs.get(TRANSFUSION_HISTORY_UUID));

            // PTME
            String ptmeHistory = ObsPivot.asString(obs.get(PTME_HISTORY_UUID));
            String ptmeRegimenHistory = ObsPivot.asString(obs.get(PTME_REGIMEN_HIST_UUID));
            LocalDate ptmeHistoryDate = ObsPivot.asDate(obs.get(PTME_HISTORY_DATE_UUID));

            // Profile fields (will be propagated to core.patients on the hub)
            String maritalStatus = ObsPivot.asString(obs.get(MARITAL_STATUS_UUID));
            String birthPlace = ObsPivot.asString(obs.get(BIRTH_PLACE_UUID));
            String educationLevel = ObsPivot.asString(obs.get(EDUCATION_LEVEL_UUID));
            String religion = ObsPivot.asString(obs.get(RELIGION_UUID));
            String profession = ObsPivot.asString(obs.get(PROFESSION_UUID));

            // Pediatric extension — emit only if at least one pediatric obs is present.
            PediatricInitiationDto pediatric = buildPediatric(obs);

            // Catch-all (excludes both adult-mapped AND pediatric-mapped concepts;
            // PII concepts are already filtered out by ObsPivot itself).
            Map<String, Object> extra = new LinkedHashMap<>();
            for (Map.Entry<String, ObsValue> e : obs.entrySet()) {
                if (MAPPED.contains(e.getKey())) continue;
                if (MAPPED_PEDIATRIC.contains(e.getKey())) continue;
                extra.put(e.getKey(), ObsPivot.asString(e.getValue()));
            }

            TreatmentInitiationDto dto = new TreatmentInitiationDto(
                    r.encounterUuid,
                    r.patientUuid,
                    r.encounterDatetime == null ? null : r.encounterDatetime.toLocalDateTime().toLocalDate(),
                    arvInit,
                    hivTestDate,
                    hivType,
                    entryPoint,
                    whoStage,
                    cdcStage,
                    arvRegimen,
                    weight,
                    cd4,
                    cd4Pct,
                    karnofsky,
                    referred,
                    referredOrigin,
                    treatmentMotive,
                    partnerStatus,
                    tbHistory,
                    arvHistory,
                    transfusionHistory,
                    ptmeHistory,
                    ptmeRegimenHistory,
                    ptmeHistoryDate,
                    maritalStatus,
                    birthPlace,
                    educationLevel,
                    religion,
                    profession,
                    pediatric,
                    extra.isEmpty() ? null : extra,
                    r.voided);

            out.add(new CanonicalRecord(EntityType.TREATMENT_INITIATIONS, r.encounterUuid, r.changed, dto));
        }
        log.debug("Extracted {} initiation(s) since {}", out.size(), since);
        return out;
    }

    /**
     * Build the pediatric extension DTO from the obs map. Returns null when
     * none of the pediatric concepts have a value — adult enrolments thus
     * carry no pediatric extension at all.
     */
    private static PediatricInitiationDto buildPediatric(Map<String, ObsValue> obs) {
        BigDecimal birthWeight = normaliseBirthWeightKg(
                ObsPivot.asDecimal(obs.get(BIRTH_WEIGHT_UUID)));
        BigDecimal birthLength = ObsPivot.asDecimal(obs.get(BIRTH_LENGTH_UUID));
        BigDecimal headCirc = ObsPivot.asDecimal(obs.get(HEAD_CIRC_UUID));
        Short apgar = ObsPivot.asShort(obs.get(APGAR_UUID));
        String deliveryMode = ObsPivot.asString(obs.get(DELIVERY_MODE_UUID));
        String deliveredFacility = ObsPivot.asString(obs.get(DELIVERED_FACILITY_UUID));

        String motherReceivedPtme = ObsPivot.asString(obs.get(MOTHER_RECEIVED_PTME_UUID));
        String motherHivStatus = ObsPivot.asString(obs.get(MOTHER_HIV_STATUS_UUID));
        String motherVitalStatus = ObsPivot.asString(obs.get(MOTHER_VITAL_STATUS_UUID));
        String motherPtmeRegimen = ObsPivot.asString(obs.get(MOTHER_PTME_REGIMEN_UUID));
        String infantProphyGiven = ObsPivot.asString(obs.get(INFANT_PROPHY_GIVEN_UUID));
        String infantProtocol = ObsPivot.asString(obs.get(INFANT_PROTOCOL_UUID));

        String feedingMode = ObsPivot.asString(obs.get(FEEDING_MODE_UUID));
        LocalDate weaningDate = ObsPivot.asDate(obs.get(WEANING_DATE_UUID));
        String vaccinations = ObsPivot.asString(obs.get(VACCINATIONS_UUID));

        String fatherVital = ObsPivot.asString(obs.get(FATHER_VITAL_UUID));
        String fatherEducation = ObsPivot.asString(obs.get(FATHER_EDUCATION_UUID));
        String fatherActivity = ObsPivot.asString(obs.get(FATHER_ACTIVITY_UUID));
        String motherEducation = ObsPivot.asString(obs.get(MOTHER_EDUCATION_UUID));
        String motherActivity = ObsPivot.asString(obs.get(MOTHER_ACTIVITY_UUID));
        String guardianVital = ObsPivot.asString(obs.get(GUARDIAN_VITAL_UUID));
        String guardianEducation = ObsPivot.asString(obs.get(GUARDIAN_EDUCATION_UUID));
        String guardianActivity = ObsPivot.asString(obs.get(GUARDIAN_ACTIVITY_UUID));
        String guardianHivStatus = ObsPivot.asString(obs.get(GUARDIAN_HIV_STATUS_UUID));

        LocalDate admissionDate = ObsPivot.asDate(obs.get(ADMISSION_DATE_UUID));
        String schoolingStatus = ObsPivot.asString(obs.get(SCHOOLING_STATUS_UUID));
        String screeningCode = ObsPivot.asString(obs.get(SCREENING_CODE_UUID));

        // Did any pediatric concept produce a non-null value?
        Object[] all = {
                birthWeight, birthLength, headCirc, apgar, deliveryMode, deliveredFacility,
                motherReceivedPtme, motherHivStatus, motherVitalStatus, motherPtmeRegimen,
                infantProphyGiven, infantProtocol,
                feedingMode, weaningDate, vaccinations,
                fatherVital, fatherEducation, fatherActivity,
                motherEducation, motherActivity,
                guardianVital, guardianEducation, guardianActivity, guardianHivStatus,
                admissionDate, schoolingStatus, screeningCode
        };
        boolean anyPresent = false;
        for (Object o : all) {
            if (o != null) { anyPresent = true; break; }
        }
        if (!anyPresent) return null;

        return new PediatricInitiationDto(
                birthWeight, birthLength, headCirc, apgar, deliveryMode, deliveredFacility,
                motherReceivedPtme, motherHivStatus, motherVitalStatus, motherPtmeRegimen,
                infantProphyGiven, infantProtocol,
                feedingMode, weaningDate, vaccinations,
                fatherVital, fatherEducation, fatherActivity,
                motherEducation, motherActivity,
                guardianVital, guardianEducation, guardianActivity, guardianHivStatus,
                admissionDate, schoolingStatus, screeningCode);
    }

    /**
     * The "Fiche initiale enfant" form captures birth weight in grams
     * (the htmlform has {@code <obs conceptId="1406" /> g}). The hub
     * stores it in core.treatment_initiations_pediatric.birth_weight_kg
     * as DECIMAL(5,2) (max 999.99), so a 3200 g value would overflow.
     *
     * Convert to kg by dividing by 1000 when the raw value clearly
     * isn't in kg (a newborn never weighs > 15 kg). Below the threshold
     * we trust the source — protects sites that may already enter the
     * value in kg.
     */
    private static BigDecimal normaliseBirthWeightKg(BigDecimal raw) {
        if (raw == null) return null;
        if (raw.compareTo(BigDecimal.valueOf(15)) > 0) {
            return raw.divide(BigDecimal.valueOf(1000), 2, java.math.RoundingMode.HALF_UP);
        }
        return raw;
    }

    private record EncounterRow(
            long encounterId,
            UUID encounterUuid,
            UUID patientUuid,
            Timestamp encounterDatetime,
            boolean voided,
            LocalDateTime changed
    ) {}
}
