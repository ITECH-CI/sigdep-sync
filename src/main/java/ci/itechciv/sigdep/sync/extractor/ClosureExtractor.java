package ci.itechciv.sigdep.sync.extractor;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.contracts.dto.ClosureDto;
import ci.itechciv.sigdep.sync.extractor.ObsPivot.ObsValue;
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
 * Extracts encounters of type "PEC - Cloture de dossier" (id=5) from
 * OpenMRS. Concept set comes from form 05_CLOTURE DE DOSSIER. The four
 * mutually-exclusive booleans/codeds (transferred, auto-transferred,
 * deceased, voluntary stop, HIV-negative) are folded into a single
 * closure_type categorical column.
 */
@Component
@Order(40)
public class ClosureExtractor implements DataExtractor {

    private static final Logger log = LoggerFactory.getLogger(ClosureExtractor.class);

    // Encounter type UUID for "PEC - Cloture de dossier"
    private static final String CLOSURE_ENCOUNTER_UUID = "abe5b173-0a3b-42eb-865b-f95b645864c7";

    // Closure category drivers
    private static final String PATIENT_TRANSFERRED_UUID = "160036AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // BIT
    private static final String AUTO_TRANSFERRED_UUID    = "165235AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // CWE
    private static final String DECEASED_UUID            = "159AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";    // CWE
    private static final String VOLUNTARY_STOP_UUID      = "165067AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"; // BIT

    // Dates
    private static final String DEATH_DATE_UUID          = "1543AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String ACTUAL_DEATH_DATE_UUID   = "165233AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String HIV_NEGATIVE_DATE_UUID   = "163511AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String VOLUNTARY_STOP_DATE_UUID = "165068AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String TRANSFER_DATE_UUID       = "164595AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    // Free-text / coded
    private static final String DEATH_CAUSE_TEXT_UUID    = "162580AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String DEATH_CAUSE_CODE_UUID    = "165225AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String TRANSFER_REASON_UUID     = "165216AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
    private static final String TRANSFER_DESTINATION_UUID = "164665AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";

    private static final java.util.Set<String> MAPPED = java.util.Set.of(
            PATIENT_TRANSFERRED_UUID, AUTO_TRANSFERRED_UUID, DECEASED_UUID, VOLUNTARY_STOP_UUID,
            DEATH_DATE_UUID, ACTUAL_DEATH_DATE_UUID, HIV_NEGATIVE_DATE_UUID,
            VOLUNTARY_STOP_DATE_UUID, TRANSFER_DATE_UUID,
            DEATH_CAUSE_TEXT_UUID, DEATH_CAUSE_CODE_UUID,
            TRANSFER_REASON_UUID, TRANSFER_DESTINATION_UUID);

    private final JdbcTemplate localDb;
    private final ObsPivot obsPivot;

    public ClosureExtractor(@Qualifier("localJdbcTemplate") JdbcTemplate localDb,
                            ObsPivot obsPivot) {
        this.localDb = localDb;
        this.obsPivot = obsPivot;
    }

    @Override public EntityType getEntityType()  { return EntityType.CLOSURES; }
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
                CLOSURE_ENCOUNTER_UUID,
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

            // Derive closure_type from the available signals.
            String closureType = deriveClosureType(obs);

            // Dates
            LocalDate deathDate = ObsPivot.asDate(obs.get(DEATH_DATE_UUID));
            LocalDate actualDeathDate = ObsPivot.asDate(obs.get(ACTUAL_DEATH_DATE_UUID));
            LocalDate hivNegativeDate = ObsPivot.asDate(obs.get(HIV_NEGATIVE_DATE_UUID));
            LocalDate voluntaryStopDate = ObsPivot.asDate(obs.get(VOLUNTARY_STOP_DATE_UUID));
            LocalDate transferDate = ObsPivot.asDate(obs.get(TRANSFER_DATE_UUID));

            // Free-text / coded
            String deathCauseText = ObsPivot.asString(obs.get(DEATH_CAUSE_TEXT_UUID));
            String deathCauseCode = ObsPivot.asString(obs.get(DEATH_CAUSE_CODE_UUID));
            String transferReason = ObsPivot.asString(obs.get(TRANSFER_REASON_UUID));
            String transferDestination = ObsPivot.asString(obs.get(TRANSFER_DESTINATION_UUID));

            // Catch-all (PII already filtered by ObsPivot)
            Map<String, Object> extra = new LinkedHashMap<>();
            for (Map.Entry<String, ObsValue> e : obs.entrySet()) {
                if (MAPPED.contains(e.getKey())) continue;
                extra.put(e.getKey(), ObsPivot.asString(e.getValue()));
            }

            ClosureDto dto = new ClosureDto(
                    r.encounterUuid,
                    r.patientUuid,
                    closureType,
                    r.encounterDatetime == null ? null : r.encounterDatetime.toLocalDateTime().toLocalDate(),
                    transferDate,
                    transferDestination,
                    transferReason,
                    deathDate,
                    actualDeathDate,
                    deathCauseCode,
                    deathCauseText,
                    voluntaryStopDate,
                    hivNegativeDate,
                    extra.isEmpty() ? null : extra,
                    r.voided);

            out.add(new CanonicalRecord(EntityType.CLOSURES, r.encounterUuid, r.changed, dto));
        }
        log.debug("Extracted {} closure(s) since {}", out.size(), since);
        return out;
    }

    /**
     * Closure type priority: a record can carry several signals (e.g. a
     * deceased patient also has a death date). We pick the most specific
     * one in this order: DEATH > TRANSFER > AUTO_TRANSFER > VOLUNTARY_STOP
     * > HIV_NEGATIVE > OTHER.
     */
    private static String deriveClosureType(Map<String, ObsValue> obs) {
        ObsValue deceased = obs.get(DECEASED_UUID);
        if (deceased != null || obs.get(DEATH_DATE_UUID) != null
                || obs.get(ACTUAL_DEATH_DATE_UUID) != null) {
            return "DEATH";
        }
        ObsValue transferred = obs.get(PATIENT_TRANSFERRED_UUID);
        if (transferred != null && transferred.numeric != null
                && transferred.numeric.signum() != 0) {
            return "TRANSFER";
        }
        if (obs.get(TRANSFER_DATE_UUID) != null) {
            return "TRANSFER";
        }
        if (obs.get(AUTO_TRANSFERRED_UUID) != null) {
            return "AUTO_TRANSFER";
        }
        ObsValue stop = obs.get(VOLUNTARY_STOP_UUID);
        if (stop != null && stop.numeric != null && stop.numeric.signum() != 0) {
            return "VOLUNTARY_STOP";
        }
        if (obs.get(VOLUNTARY_STOP_DATE_UUID) != null) {
            return "VOLUNTARY_STOP";
        }
        if (obs.get(HIV_NEGATIVE_DATE_UUID) != null) {
            return "HIV_NEGATIVE";
        }
        return "OTHER";
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
