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
 * Un encounter dont {@code patient_id} pointe vers une {@code person} qui
 * n'est PAS un {@code patient} (relation, prestataire, contact — cas réel
 * OpenMRS) ne doit PLUS être extrait par les extracteurs d'encounters.
 *
 * <p>Sans le {@code JOIN patient}, ces encounters remontaient avec un UUID que
 * {@link PatientExtractor} (qui exige {@code FROM patient JOIN person}) n'extrait
 * jamais → rejet {@code UNKNOWN_PATIENT} « not yet ingested » ÉTERNEL côté hub
 * (observé en prod : ~79k rejets sur des closures d'un même person_id orphelin).
 * On vérifie contre un vrai MySQL que la borne réelle (avec {@code JOIN patient})
 * exclut l'orphelin et garde le patient valide ; contre-épreuve avec l'ancienne
 * jointure ({@code person} seule) qui, elle, le laissait passer.
 */
@Testcontainers
class EncounterOrphanPersonIT {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("openmrs");

    private static JdbcTemplate jdbc;

    private static final String CLOSURE_TYPE_UUID = "abe5b173-0a3b-42eb-865b-f95b645864c7";

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(MYSQL.getJdbcUrl());
        ds.setUsername(MYSQL.getUsername());
        ds.setPassword(MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);

        jdbc.execute("""
                CREATE TABLE person (
                  person_id INT PRIMARY KEY, uuid CHAR(38) NOT NULL,
                  date_created DATETIME NOT NULL, date_changed DATETIME NULL,
                  date_voided DATETIME NULL
                )""");
        jdbc.execute("""
                CREATE TABLE patient (
                  patient_id INT PRIMARY KEY,
                  date_created DATETIME NOT NULL, date_changed DATETIME NULL
                )""");
        jdbc.execute("""
                CREATE TABLE encounter_type (
                  encounter_type_id INT PRIMARY KEY, uuid CHAR(38) NOT NULL, name VARCHAR(100)
                )""");
        jdbc.execute("""
                CREATE TABLE encounter (
                  encounter_id INT PRIMARY KEY, uuid CHAR(38) NOT NULL,
                  encounter_type INT NOT NULL, patient_id INT NOT NULL,
                  encounter_datetime DATETIME, voided TINYINT NOT NULL DEFAULT 0,
                  date_created DATETIME NOT NULL, date_changed DATETIME NULL, date_voided DATETIME NULL
                )""");

        jdbc.update("INSERT INTO encounter_type VALUES (1, ?, 'CLOSURE')", CLOSURE_TYPE_UUID);

        // person 10 = vrai patient ; person 20 = person SANS patient (orphelin).
        jdbc.update("INSERT INTO person VALUES (10, ?, '2026-01-01', NULL, NULL)",
                "10000000-0000-0000-0000-000000000010");
        jdbc.update("INSERT INTO patient VALUES (10, '2026-01-01', NULL)");
        jdbc.update("INSERT INTO person VALUES (20, ?, '2026-01-01', NULL, NULL)",
                "20000000-0000-0000-0000-000000000020");
        // pas de ligne patient pour 20

        // Une closure pour chacun.
        jdbc.update("INSERT INTO encounter VALUES (1, ?, 1, 10, '2026-02-01', 0, '2026-02-01', NULL, NULL)",
                "e0000000-0000-0000-0000-000000000001");
        jdbc.update("INSERT INTO encounter VALUES (2, ?, 1, 20, '2026-02-01', 0, '2026-02-01', NULL, NULL)",
                "e0000000-0000-0000-0000-000000000002");
    }

    private List<Long> extract(String join) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT e.encounter_id AS id FROM encounter e "
                + "JOIN encounter_type et ON et.encounter_type_id = e.encounter_type "
                + join + " "
                + "JOIN person per ON per.person_id = e.patient_id "
                + "WHERE et.uuid = ? ORDER BY e.encounter_id",
                CLOSURE_TYPE_UUID);
        return rows.stream().map(r -> ((Number) r.get("id")).longValue()).toList();
    }

    @Test
    @DisplayName("Avec JOIN patient : l'encounter orphelin (person sans patient) est exclu")
    void withPatientJoin_excludesOrphan() {
        List<Long> ids = extract("JOIN patient pat ON pat.patient_id = e.patient_id");
        assertThat(ids)
                .as("seul l'encounter du vrai patient remonte")
                .containsExactly(1L)
                .doesNotContain(2L);
    }

    @Test
    @DisplayName("Contre-épreuve : JOIN person seul laissait passer l'orphelin (bug UNKNOWN_PATIENT)")
    void withoutPatientJoin_leaksOrphan() {
        List<Long> ids = extract(""); // ancienne jointure : person seule
        assertThat(ids)
                .as("l'orphelin remontait et provoquait un rejet éternel")
                .containsExactly(1L, 2L);
    }
}
