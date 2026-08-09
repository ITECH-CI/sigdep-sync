package ci.itechciv.sigdep.sync.extractor;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Reads OpenMRS 'obs' rows for a given set of encounters and pivots them into
 * a per-encounter map of concept UUID -> typed value.
 *
 * Each obs row carries a value in one of several columns (numeric, datetime,
 * text, coded). For value_coded we resolve the concept_name (locale_preferred,
 * not voided) so the central server stores a human-readable string rather
 * than a meaningless integer that's not stable across instances.
 */
@Component
public class ObsPivot {

    /**
     * PII concepts that must never leave the agent. Identifying free-text
     * (names, phone numbers, precise addresses) is dropped silently — the
     * central hub stores aggregated, non-identifying data only (spec §6.4).
     *
     * Blocking here in ObsPivot rather than in each extractor guarantees
     * we cannot accidentally let one through, and it keeps extra_data
     * JSONB on the hub free of PII as well.
     */
    private static final java.util.Set<String> BLOCKED_CONCEPTS = java.util.Set.of(
            // Names (parents, guardian, support contact)
            "1593AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",   // Nom de la mère
            "1594AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",   // Nom du père
            "163603AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",   // Prénoms du tuteur légal
            "162887AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",   // Prénom personne de soutien
            "164513AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",   // Nom personne de soutien
            // Phone numbers
            "164463AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",   // Téléphone du père
            "164464AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",   // Cellulaire du père
            "164465AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",   // Téléphone de la mère
            "164466AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",   // Cellulaire de la mère
            "164472AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",   // Téléphone du tuteur
            "164473AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",   // Cellulaire du tuteur
            "160642AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",   // Téléphone personne de soutien
            "164500AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",   // Téléphone Fixe patient
            "164501AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",   // Cellulaire patient
            // Free-text "current activity" (occupation as free text — code/type goes through)
            "164459AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",   // Activité actuelle du père
            "164460AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",   // Activité actuelle de la mère
            "164469AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",   // Activité actuelle du tuteur
            // Precise addresses
            "164515AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",   // Adresse personne de soutien
            "164449AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",   // Commune/village (precise)
            "163617AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"    // Quartier
    );

    private final JdbcTemplate localDb;

    public ObsPivot(@Qualifier("localJdbcTemplate") JdbcTemplate localDb) {
        this.localDb = localDb;
    }

    /** Read-only view, exposed so extractors can use it for sanity checks if needed. */
    public static java.util.Set<String> blockedConcepts() {
        return BLOCKED_CONCEPTS;
    }

    /**
     * For the given encounters, return: encounterId -> (conceptUuid -> ObsValue).
     * Empty map if encounters is empty.
     */
    public Map<Long, Map<String, ObsValue>> pivot(Collection<Long> encounterIds) {
        if (encounterIds == null || encounterIds.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(",", encounterIds.stream().map(i -> "?").toList());
        Object[] params = encounterIds.toArray();

        Map<Long, Map<String, ObsValue>> result = new HashMap<>();
        // For value_coded, resolve the concept_name using a French-first locale
        // ranking so we don't end up with Swahili / Spanish / Creole leaking
        // through (locale_preferred=1 is set on too many rows in some exports).
        localDb.query(
                """
                SELECT o.encounter_id        AS encounter_id,
                       c.uuid                AS concept_uuid,
                       o.value_numeric       AS value_numeric,
                       o.value_datetime      AS value_datetime,
                       o.value_text          AS value_text,
                       o.value_coded         AS value_coded,
                       (
                         SELECT cn.name FROM concept_name cn
                         WHERE cn.concept_id = o.value_coded
                           AND cn.voided = 0
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
                       ) AS coded_name
                FROM obs o
                JOIN concept c ON c.concept_id = o.concept_id
                WHERE o.voided = 0 AND o.encounter_id IN (%s)
                """.formatted(placeholders),
                rs -> {
                    long eid = rs.getLong("encounter_id");
                    String conceptUuid = rs.getString("concept_uuid");

                    // PII filter: PII concepts never leave the agent.
                    if (BLOCKED_CONCEPTS.contains(conceptUuid)) return;

                    // Read value_coded first so its wasNull() is captured before
                    // any subsequent getXxx() overwrites it.
                    long coded = rs.getLong("value_coded");
                    boolean codedNull = rs.wasNull();

                    BigDecimal num = rs.getBigDecimal("value_numeric");
                    Timestamp dt = rs.getTimestamp("value_datetime");
                    // Normaliser les valeurs texte : OpenMRS stocke parfois des
                    // saisies polluées par du padding d'espaces (ex. "ABOBO" suivi
                    // de 300+ espaces, ou une valeur entièrement blanche). Non
                    // trimé, un tel value_text dépasse la largeur des colonnes du
                    // hub (value too long for character varying(255) sur
                    // patients.birth_place) et fait rejeter le record en boucle.
                    // On trime, et un texte devenu vide est traité comme absent.
                    String text = trimToNull(rs.getString("value_text"));
                    String codedName = trimToNull(rs.getString("coded_name"));

                    ObsValue v;
                    if (num != null) {
                        v = ObsValue.numeric(num);
                    } else if (dt != null) {
                        v = ObsValue.datetime(dt);
                    } else if (!codedNull) {
                        v = ObsValue.coded(coded, codedName);
                    } else if (text != null) {
                        v = ObsValue.text(text);
                    } else {
                        return;
                    }
                    result.computeIfAbsent(eid, k -> new HashMap<>()).put(conceptUuid, v);
                },
                params);
        return result;
    }

    /** Convenience extractor wrappers. */
    public static java.time.LocalDate asDate(ObsValue v) {
        if (v == null) return null;
        if (v.datetime != null) return v.datetime.toLocalDateTime().toLocalDate();
        if (v.text != null) {
            try { return java.time.LocalDate.parse(v.text); } catch (RuntimeException ignored) { return null; }
        }
        return null;
    }

    public static BigDecimal asDecimal(ObsValue v) {
        return v == null ? null : v.numeric;
    }

    public static Short asShort(ObsValue v) {
        if (v == null) return null;
        if (v.numeric != null) {
            try { return v.numeric.shortValueExact(); }
            catch (ArithmeticException e) { return null; }
        }
        // Some sites store vital signs (BP, etc.) in value_text rather than
        // value_numeric. Try to parse if it looks like a number.
        if (v.text != null && !v.text.isBlank()) {
            try { return Short.parseShort(v.text.trim()); }
            catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    public static Integer asInt(ObsValue v) {
        if (v == null) return null;
        if (v.numeric != null) {
            try { return v.numeric.intValueExact(); }
            catch (ArithmeticException e) { return null; }
        }
        if (v.text != null && !v.text.isBlank()) {
            try { return Integer.parseInt(v.text.trim()); }
            catch (NumberFormatException e) { return null; }
        }
        return null;
    }

    /** Trime une chaîne ; renvoie null si elle est nulle ou vide après trim. */
    static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    public static String asString(ObsValue v) {
        if (v == null) return null;
        if (v.codedName != null) return v.codedName;
        if (v.text != null) return v.text;
        if (v.numeric != null) return v.numeric.toPlainString();
        if (v.datetime != null) return v.datetime.toString();
        return null;
    }

    /** A typed obs value. Exactly one of the fields is non-null. */
    public static final class ObsValue {
        public final BigDecimal numeric;
        public final Timestamp datetime;
        public final String text;
        public final Long codedId;
        public final String codedName;

        private ObsValue(BigDecimal n, Timestamp d, String t, Long ci, String cn) {
            this.numeric = n; this.datetime = d; this.text = t; this.codedId = ci; this.codedName = cn;
        }
        static ObsValue numeric(BigDecimal n)              { return new ObsValue(n, null, null, null, null); }
        static ObsValue datetime(Timestamp d)              { return new ObsValue(null, d, null, null, null); }
        static ObsValue text(String s)                     { return new ObsValue(null, null, s, null, null); }
        static ObsValue coded(long id, String name)        { return new ObsValue(null, null, null, id, name); }
    }
}
