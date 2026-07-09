package ci.itechciv.sigdep.sync.extractor;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.contracts.dto.ScreeningDto;
import ci.itechciv.sigdep.sync.state.SyncStateRepository;
import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * HIV screening extractor — reads the OpenMRS "hivscreening" module's
 * dedicated tables (hiv_screening_hiv_screening + screening_register_info),
 * not encounter/obs. Records are anonymous (no patient FK upstream),
 * so we don't resolve a patient_uuid — the hub stores them as-is.
 *
 * The upstream table has no date_changed column, so we use screening_date
 * as the temporal watermark. A small overlap is tolerated since the hub
 * upserts on (site_id, source_uuid).
 *
 * Categorical integer codes are decoded here so the hub stores
 * human-readable labels; raw codes also land in extraData for reference.
 */
@Component
@Order(70)
public class ScreeningExtractor implements DataExtractor {

    private final JdbcTemplate localDb;
    private final SyncStateRepository syncState;

    public ScreeningExtractor(@Qualifier("localJdbcTemplate") JdbcTemplate localDb,
                              SyncStateRepository syncState) {
        this.localDb = localDb;
        this.syncState = syncState;
    }

    @Override public EntityType getEntityType()  { return EntityType.SCREENINGS; }
    @Override public String getSourceTable()     { return "hiv_screening_hiv_screening"; }
    @Override public String getWatermarkColumn() { return "screening_date"; }
    @Override public boolean isEnabled()         { return true; }

    /**
     * Extraction en KEYSET (screening_date, hiv_screening_id).
     *
     * La table amont n'a pas de date_changed : le watermark temporel n'a
     * qu'une granularité JOUR. Avec un simple {@code screening_date >= ?} le
     * curseur ne franchissait jamais la frontière du jour courant et
     * ré-extrayait toute la journée à chaque cycle (rejeu absorbé en upsert
     * côté hub, mais transactions d'audit qui s'accumulent). On ajoute donc
     * l'id comme tie-breaker et on filtre en keyset strict :
     * {@code date > d OR (date = d AND id > lastId)}.
     *
     * Le curseur (last_watermark, last_id) est relu ici et avancé après
     * extraction sur le dernier enregistrement de la page. Comme les
     * screenings sont anonymes (aucune dépendance FK), ils ne sont jamais
     * rejetés par le hub : avancer le keyset dès l'extraction est sûr (pas de
     * cas PARTIAL à couvrir, contrairement aux entités à FK).
     */
    @Override
    public List<CanonicalRecord> extract(LocalDateTime since, int batchSize) {
        LocalDate sinceDate = since.toLocalDate();
        long sinceId = syncState.getLastId(EntityType.SCREENINGS).orElse(0L);

        return localDb.query(
                """
                SELECT h.hiv_screening_id,
                       h.uuid                    AS screening_uuid,
                       h.screening_date,
                       h.screening_code,
                       h.profession,
                       h.gender,
                       h.age,
                       h.residence,
                       h.marital_status,
                       h.other_marital_status,
                       h.population_type,
                       h.other_population_type,
                       h.screening_reason,
                       h.other_screening_reason,
                       h.invalidated_test1,
                       h.test1_reaction,
                       h.invalidated_test2,
                       h.test2_reaction,
                       h.invalidated_test3,
                       h.test3_reaction,
                       h.final_result,
                       h.result_announcing_date,
                       h.retesting,
                       h.comment,
                       h.sampling,
                       ri.screening_site_type,
                       ri.screening_post
                FROM hiv_screening_hiv_screening h
                LEFT JOIN hiv_screening_screening_register_info ri
                       ON ri.screening_info_id = h.register_info
                WHERE h.screening_date > ?
                   OR (h.screening_date = ? AND h.hiv_screening_id > ?)
                ORDER BY h.screening_date, h.hiv_screening_id
                LIMIT ?
                """,
                (rs, i) -> {
                    long screeningId = rs.getLong("hiv_screening_id");
                    UUID uuid = UUID.fromString(rs.getString("screening_uuid"));
                    LocalDate screeningDate = rs.getDate("screening_date") == null ? null
                            : rs.getDate("screening_date").toLocalDate();
                    LocalDate announce = rs.getDate("result_announcing_date") == null ? null
                            : rs.getDate("result_announcing_date").toLocalDate();

                    Integer ageInt = (Integer) rs.getObject("age");
                    Integer maritalCode = (Integer) rs.getObject("marital_status");
                    Integer populationCode = (Integer) rs.getObject("population_type");
                    Integer reasonCode = (Integer) rs.getObject("screening_reason");
                    Integer test1Code = (Integer) rs.getObject("test1_reaction");
                    Integer test2Code = (Integer) rs.getObject("test2_reaction");
                    Integer test3Code = (Integer) rs.getObject("test3_reaction");
                    Integer finalCode = (Integer) rs.getObject("final_result");
                    Boolean inv1 = (Boolean) rs.getObject("invalidated_test1");
                    Boolean inv2 = (Boolean) rs.getObject("invalidated_test2");
                    Boolean inv3 = (Boolean) rs.getObject("invalidated_test3");
                    Boolean retest = (Boolean) rs.getObject("retesting");

                    Map<String, Object> extra = new LinkedHashMap<>();
                    putIfNotNull(extra, "raw_marital_status", maritalCode);
                    putIfNotNull(extra, "raw_population_type", populationCode);
                    putIfNotNull(extra, "raw_screening_reason", reasonCode);
                    putIfNotNull(extra, "raw_test1_reaction", test1Code);
                    putIfNotNull(extra, "raw_test2_reaction", test2Code);
                    putIfNotNull(extra, "raw_test3_reaction", test3Code);
                    putIfNotNull(extra, "raw_final_result", finalCode);
                    putIfNotNull(extra, "other_population_type", rs.getString("other_population_type"));
                    putIfNotNull(extra, "sampling", rs.getString("sampling"));

                    ScreeningDto dto = new ScreeningDto(
                            uuid,
                            null, // siteCode: filled by the batch envelope
                            rs.getString("screening_code"),
                            screeningDate,
                            announce,
                            rs.getString("gender"),
                            ageInt,
                            rs.getString("profession"),
                            rs.getString("residence"),
                            decodeMaritalStatus(maritalCode),
                            rs.getString("other_marital_status"),
                            decodePopulationType(populationCode),
                            decodeScreeningReason(reasonCode),
                            rs.getString("other_screening_reason"),
                            decodeReaction(test1Code),
                            decodeReaction(test2Code),
                            decodeReaction(test3Code),
                            inv1, inv2, inv3,
                            decodeFinalResult(finalCode),
                            retest,
                            rs.getString("comment"),
                            rs.getString("screening_site_type"),
                            rs.getString("screening_post"),
                            extra.isEmpty() ? null : extra,
                            Boolean.FALSE);

                    LocalDateTime changed = screeningDate == null
                            ? LocalDateTime.now()
                            : LocalDateTime.of(screeningDate, LocalTime.MIN);
                    // sourceId = hiv_screening_id : porté jusqu'à l'outbox pour
                    // que le flusher avance sync_state.last_id sur les seules
                    // lignes confirmées par le hub (keyset robuste aux rejets).
                    return new CanonicalRecord(
                            EntityType.SCREENINGS, uuid, changed, screeningId, dto);
                },
                Date.valueOf(sinceDate), Date.valueOf(sinceDate), sinceId, batchSize);
    }

    private static void putIfNotNull(Map<String, Object> map, String k, Object v) {
        if (v != null) map.put(k, v);
    }

    private static String decodeMaritalStatus(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 0 -> "Célibataire";
            case 1 -> "Couple";
            case 2 -> "Autre";
            default -> null;
        };
    }

    private static String decodePopulationType(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 0 -> "Population Générale";
            case 1 -> "UD";
            case 2 -> "TS";
            case 3 -> "HSH";
            case 4 -> "PC";
            case 5 -> "Autres";
            default -> null;
        };
    }

    private static String decodeScreeningReason(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 1 -> "IST";
            case 2 -> "Confirmation après auto-test";
            case 3 -> "Contact-index";
            case 4 -> "Femme enceinte";
            case 5 -> "Femme allaitante";
            case 6 -> "Dépistage en couple";
            case 7 -> "AES";
            case 8 -> "PrEP";
            case 9 -> "Autres";
            default -> null;
        };
    }

    private static String decodeReaction(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 1 -> "R";
            case 0 -> "NR";
            default -> null;
        };
    }

    private static String decodeFinalResult(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 0 -> "NEG";
            case 1 -> "POS";
            case 2 -> "IND";
            default -> null;
        };
    }
}
