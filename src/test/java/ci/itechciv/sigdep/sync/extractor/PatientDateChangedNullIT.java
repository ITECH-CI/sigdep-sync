package ci.itechciv.sigdep.sync.extractor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Test d'intégration : un patient dont {@code date_changed} est NULL (ligne
 * jamais modifiée depuis sa création — cas courant sur OpenMRS) DOIT être
 * extrait par la pagination.
 *
 * Le risque documenté : sur OpenMRS, {@code date_changed} reste NULL tant que
 * la ligne n'a jamais été mise à jour. Une pagination sur {@code date_changed}
 * seul (sans COALESCE) sauterait silencieusement toutes ces lignes. On vérifie
 * ici, contre un VRAI MySQL (testcontainers), que la requête réelle des
 * extracteurs — bâtie sur {@code COALESCE(date_changed, date_created)} — ramène
 * bien la ligne grâce au fallback {@code date_created} (NOT NULL).
 *
 * Requête reproduite à l'identique depuis {@code PatientExtractor} (page 1).
 * Ne démarre pas Spring : un JdbcTemplate direct sur le conteneur suffit.
 */
@Testcontainers
class PatientDateChangedNullIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("openmrs");

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(MYSQL.getJdbcUrl());
        ds.setUsername(MYSQL.getUsername());
        ds.setPassword(MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);

        // Schéma minimal person + patient (colonnes utilisées par l'extracteur).
        jdbc.execute("""
                CREATE TABLE person (
                  person_id INT PRIMARY KEY,
                  uuid CHAR(38) NOT NULL,
                  gender VARCHAR(50),
                  birthdate DATE,
                  birthdate_estimated TINYINT,
                  voided TINYINT NOT NULL DEFAULT 0,
                  date_created DATETIME NOT NULL,
                  date_changed DATETIME NULL
                )""");
        jdbc.execute("""
                CREATE TABLE patient (
                  patient_id INT PRIMARY KEY,
                  voided TINYINT NOT NULL DEFAULT 0,
                  date_created DATETIME NOT NULL,
                  date_changed DATETIME NULL
                )""");

        // Patient A : JAMAIS modifié → date_changed NULL des deux côtés,
        // seul date_created est renseigné. C'est le cas à ne pas rater.
        jdbc.update("INSERT INTO person (person_id, uuid, gender, birthdate, birthdate_estimated,"
                + " voided, date_created, date_changed) VALUES (1, ?, 'M', '1990-01-01', 0, 0,"
                + " '2026-01-10 08:00:00', NULL)",
                "11111111-1111-1111-1111-111111111111");
        jdbc.update("INSERT INTO patient (patient_id, voided, date_created, date_changed)"
                + " VALUES (1, 0, '2026-01-10 08:00:00', NULL)");

        // Patient B : modifié une fois → date_changed renseigné (témoin).
        jdbc.update("INSERT INTO person (person_id, uuid, gender, birthdate, birthdate_estimated,"
                + " voided, date_created, date_changed) VALUES (2, ?, 'F', '1985-05-05', 0, 0,"
                + " '2026-01-05 08:00:00', '2026-01-12 09:00:00')",
                "22222222-2222-2222-2222-222222222222");
        jdbc.update("INSERT INTO patient (patient_id, voided, date_created, date_changed)"
                + " VALUES (2, 0, '2026-01-05 08:00:00', NULL)");
    }

    @AfterAll
    static void tearDown() {
        // Le conteneur est arrêté automatiquement par @Testcontainers.
    }

    /** Requête de pagination de PatientExtractor (page 1, curseur au tout début). */
    private static final String PAGE_SQL = """
            SELECT
              per.person_id                    AS person_id,
              per.uuid                         AS patient_uuid,
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
               OR (GREATEST(
                    COALESCE(per.date_changed, per.date_created),
                    COALESCE(pat.date_changed, pat.date_created)
                  ) = ? AND per.person_id > ?)
            ORDER BY effective_changed, per.person_id
            LIMIT ?
            """;

    @Test
    @DisplayName("Patient à date_changed NULL : extrait via COALESCE(date_changed, date_created)")
    void patientWithNullDateChanged_isExtracted() {
        // Curseur au tout début (epoch, id 0) → doit ramener TOUS les patients.
        List<Map<String, Object>> rows = jdbc.queryForList(
                PAGE_SQL, "1970-01-01 00:00:00", "1970-01-01 00:00:00", 0, 100);

        List<Long> ids = rows.stream().map(r -> ((Number) r.get("person_id")).longValue()).toList();

        // Le patient A (date_changed NULL) DOIT être présent — c'est le cœur du test.
        assertThat(ids)
                .as("le patient jamais modifié (date_changed NULL) est extrait grâce au fallback date_created")
                .contains(1L);
        // Les deux patients sont ramenés.
        assertThat(ids).containsExactlyInAnyOrder(1L, 2L);

        // Son effective_changed vaut bien date_created (pas NULL).
        Map<String, Object> a = rows.stream()
                .filter(r -> ((Number) r.get("person_id")).longValue() == 1L)
                .findFirst().orElseThrow();
        assertThat(a.get("effective_changed"))
                .as("effective_changed retombe sur date_created quand date_changed est NULL")
                .isNotNull();
    }

    @Test
    @DisplayName("Contre-épreuve : une pagination sur date_changed SEUL raterait le patient NULL")
    void bareDateChanged_wouldMissNullPatient() {
        // Démonstration du piège : sans COALESCE, la borne WHERE date_changed > ?
        // exclut la ligne à date_changed NULL (NULL > x est inconnu → exclu).
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT per.person_id AS person_id FROM patient pat"
                + " JOIN person per ON per.person_id = pat.patient_id"
                + " WHERE per.date_changed > ? ORDER BY per.person_id",
                "1970-01-01 00:00:00");
        List<Long> ids = rows.stream().map(r -> ((Number) r.get("person_id")).longValue()).toList();

        // Le patient A (NULL) est ABSENT → prouve que le COALESCE est nécessaire.
        assertThat(ids)
                .as("la pagination sur date_changed seul saute le patient jamais modifié")
                .doesNotContain(1L)
                .containsExactly(2L);
    }
}
