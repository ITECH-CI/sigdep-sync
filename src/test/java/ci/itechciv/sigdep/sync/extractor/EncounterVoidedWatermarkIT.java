package ci.itechciv.sigdep.sync.extractor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * SYNC-10 : une suppression LOGIQUE (void) doit faire avancer le watermark pour
 * que la ligne repasse dans la fenêtre d'extraction et que le flag
 * {@code voided=true} remonte au hub.
 *
 * Piège OpenMRS : quand une ligne est voidée APRÈS coup, {@code date_voided}
 * est renseigné mais {@code date_changed} peut rester à sa valeur d'origine
 * (l'ordre de void ne repasse pas toujours par un update « métier »). Une
 * pagination bornée sur {@code COALESCE(date_changed, date_created)} SEUL
 * raterait alors la suppression : le watermark ne bougerait pas, la ligne
 * voidée ne serait jamais renvoyée, et le hub garderait une donnée fantôme.
 *
 * On vérifie contre un VRAI MySQL (testcontainers) que la borne réelle des
 * extracteurs encounter — {@code GREATEST(COALESCE(date_changed,date_created),
 * COALESCE(date_voided,date_created))} — ramène bien l'encounter voidé, et que
 * l'ancienne borne l'aurait raté.
 */
@Testcontainers
class EncounterVoidedWatermarkIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("openmrs");

    private static JdbcTemplate jdbc;

    // Watermark du dernier cycle : APRÈS la création (date_created) mais AVANT
    // le void. L'encounter voidé n'a donc bougé que par date_voided.
    private static final String LAST_WATERMARK = "2026-02-01 00:00:00";

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(MYSQL.getJdbcUrl());
        ds.setUsername(MYSQL.getUsername());
        ds.setPassword(MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("""
                CREATE TABLE encounter (
                  encounter_id INT PRIMARY KEY,
                  uuid CHAR(38) NOT NULL,
                  voided TINYINT NOT NULL DEFAULT 0,
                  date_created DATETIME NOT NULL,
                  date_changed DATETIME NULL,
                  date_voided  DATETIME NULL
                )""");

        // Encounter 1 : créé le 10/01 (avant le watermark), JAMAIS re-modifié,
        // puis VOIDÉ le 05/02 (après le watermark). date_changed reste NULL.
        jdbc.update("INSERT INTO encounter (encounter_id, uuid, voided, date_created,"
                + " date_changed, date_voided) VALUES (1, ?, 1,"
                + " '2026-01-10 08:00:00', NULL, '2026-02-05 09:00:00')",
                "11111111-1111-1111-1111-111111111111");

        // Encounter 2 : créé et modifié avant le watermark, jamais voidé →
        // NE doit PAS repasser (témoin : rien n'a bougé après le watermark).
        jdbc.update("INSERT INTO encounter (encounter_id, uuid, voided, date_created,"
                + " date_changed, date_voided) VALUES (2, ?, 0,"
                + " '2026-01-05 08:00:00', '2026-01-20 09:00:00', NULL)",
                "22222222-2222-2222-2222-222222222222");
    }

    /** Borne RÉELLE des extracteurs encounter (SYNC-10). */
    private static final String NEW_BOUND =
            "GREATEST(COALESCE(date_changed, date_created), COALESCE(date_voided, date_created))";

    /** Ancienne borne (avant SYNC-10) : date_changed/created seuls. */
    private static final String OLD_BOUND = "COALESCE(date_changed, date_created)";

    private List<Long> idsSince(String bound, String watermark) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT encounter_id FROM encounter WHERE " + bound + " > ?"
                + " OR (" + bound + " = ? AND encounter_id > ?) ORDER BY encounter_id",
                watermark, watermark, 0);
        return rows.stream().map(r -> ((Number) r.get("encounter_id")).longValue()).toList();
    }

    @Test
    @DisplayName("Encounter voidé après coup : la nouvelle borne le ramène (flag voided remonte)")
    void voidedAfterWatermark_isPickedUpByNewBound() {
        List<Long> ids = idsSince(NEW_BOUND, LAST_WATERMARK);
        assertThat(ids)
                .as("l'encounter voidé après le watermark repasse grâce à date_voided")
                .contains(1L)
                .doesNotContain(2L); // rien n'a bougé pour le 2 après le watermark
    }

    @Test
    @DisplayName("Contre-épreuve : l'ancienne borne (date_changed seul) raterait le void")
    void voidedAfterWatermark_missedByOldBound() {
        List<Long> ids = idsSince(OLD_BOUND, LAST_WATERMARK);
        assertThat(ids)
                .as("sans date_voided dans la borne, la suppression logique reste invisible")
                .doesNotContain(1L);
    }
}
