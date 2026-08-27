package ci.itechciv.sigdep.sync.buffer;

import static org.assertj.core.api.Assertions.assertThat;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.sync.buffer.OutboxRepository.EnqueueRow;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;

/**
 * Vérifie le batch-enqueue de l'outbox : performance (500 lignes bien en deçà
 * du seuil), atomicité, et surtout ABSENCE DE RÉGRESSION sur la reprise après
 * redémarrage (les lignes commitées survivent à la fermeture/réouverture de la
 * base — ce que WAL + synchronous=NORMAL ne remettent pas en cause).
 */
class OutboxRepositoryBatchTest {

    private static final Logger log = LoggerFactory.getLogger(OutboxRepositoryBatchTest.class);

    // Seuil cible : 100 ms. On LOGUE la mesure, mais on n'échoue que très
    // au-dessus (500 ms) pour ne pas rendre la CI flaky sur un runner lent —
    // l'objectif du test est de garder trace de la perf, pas de la garantir au
    // milliseconde près.
    private static final long TARGET_MS = 100;
    private static final long HARD_CEILING_MS = 500;

    @TempDir
    Path tmp;

    private Path dbFile;
    private JdbcTemplate jdbc;
    private OutboxRepository repo;

    @BeforeEach
    void setUp() throws IOException {
        dbFile = tmp.resolve("buffer.sqlite");
        jdbc = openBuffer(dbFile);
        applySchema(jdbc);
        repo = new OutboxRepository(jdbc, new ci.itechciv.sigdep.sync.buffer.BufferWriteLock());
    }

    @AfterEach
    void tearDown() {
        // rien de spécial : DriverManagerDataSource ne poole pas.
    }

    private static JdbcTemplate openBuffer(Path file) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:" + file);
        JdbcTemplate t = new JdbcTemplate(ds);
        // Reproduit les PRAGMA posés en prod (connection-init-sql).
        t.execute("PRAGMA journal_mode=WAL");
        t.execute("PRAGMA synchronous=NORMAL");
        return t;
    }

    private static void applySchema(JdbcTemplate t) throws IOException {
        String ddl;
        try (InputStream in = OutboxRepositoryBatchTest.class.getResourceAsStream(
                "/db/sqlite/buffer-schema.sql")) {
            ddl = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }
        for (String stmt : BufferSchemaInitializer.splitStatements(ddl)) {
            t.execute(stmt);
        }
    }

    private static List<EnqueueRow> makeRows(int n) {
        List<EnqueueRow> rows = new ArrayList<>(n);
        LocalDateTime wm = LocalDateTime.of(2026, 1, 1, 0, 0);
        for (int i = 0; i < n; i++) {
            rows.add(new EnqueueRow(
                    EntityType.PATIENTS,
                    UUID.randomUUID(),
                    wm.plusSeconds(i),
                    (long) i,
                    "{\"i\":" + i + "}"));
        }
        return rows;
    }

    @Test
    @DisplayName("Enqueue de 500 lignes : mesuré et loggué, non bloquant sauf dérive majeure")
    void enqueue500_isFast() {
        List<EnqueueRow> rows = makeRows(500);

        long start = System.nanoTime();
        repo.enqueueBatch(rows);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        log.info("Enqueue de 500 lignes en {} ms (cible < {} ms)", elapsedMs, TARGET_MS);

        assertThat(count()).isEqualTo(500);
        // Non bloquant à 100 ms près ; échoue seulement en cas de dérive nette
        // (retour à un comportement autocommit ligne par ligne ≈ 1,5 s).
        assertThat(elapsedMs)
                .as("enqueue de 500 lignes = %d ms — dérive majeure au-delà de %d ms",
                        elapsedMs, HARD_CEILING_MS)
                .isLessThan(HARD_CEILING_MS);
    }

    @Test
    @DisplayName("Reprise après redémarrage : les lignes commitées survivent à la réouverture")
    void surviveRestart_noRegression() {
        repo.enqueueBatch(makeRows(500));
        assertThat(count()).isEqualTo(500);

        // Simule un redémarrage de l'agent : nouvelle connexion sur le MÊME
        // fichier. Avec WAL, un checkpoint implicite/à la fermeture garantit la
        // visibilité ; les lignes doivent toutes être là.
        JdbcTemplate reopened = openBuffer(dbFile);
        Integer after = reopened.queryForObject("SELECT count(*) FROM outbox", Integer.class);
        assertThat(after).isEqualTo(500);
    }

    @Test
    @DisplayName("Upsert conservé : ré-enqueue d'un même (entity_type, source_uuid) PENDING met à jour, n'ajoute pas")
    void reEnqueueSameUuid_updatesInPlace() {
        UUID uuid = UUID.randomUUID();
        LocalDateTime wm = LocalDateTime.of(2026, 1, 1, 0, 0);
        repo.enqueueBatch(List.of(new EnqueueRow(EntityType.PATIENTS, uuid, wm, 1L, "{\"v\":1}")));
        assertThat(count()).isEqualTo(1);

        // Même clé, nouveau payload : doit rester UNE ligne, payload mis à jour.
        repo.enqueueBatch(List.of(new EnqueueRow(EntityType.PATIENTS, uuid, wm, 2L, "{\"v\":2}")));
        assertThat(count()).isEqualTo(1);
        String payload = jdbc.queryForObject(
                "SELECT payload_json FROM outbox WHERE source_uuid = ?", String.class, uuid.toString());
        assertThat(payload).isEqualTo("{\"v\":2}");
    }

    @Test
    @DisplayName("Atomicité : un lot contenant une ligne invalide est annulé en intégralité")
    void batchFailure_rollsBackEverything() {
        // On insère une 1re fournée valide, puis une fournée dont une ligne
        // viole NOT NULL (payload_json null) → tout le lot doit être annulé.
        repo.enqueueBatch(makeRows(10));
        assertThat(count()).isEqualTo(10);

        List<EnqueueRow> bad = new ArrayList<>(makeRows(5));
        bad.add(new EnqueueRow(EntityType.PATIENTS, UUID.randomUUID(),
                LocalDateTime.of(2026, 1, 1, 0, 0), 99L, null)); // payload null → NOT NULL viol
        try {
            repo.enqueueBatch(bad);
        } catch (RuntimeException expected) {
            // attendu
        }
        // Aucune des 6 lignes du lot fautif ne doit avoir été persistée.
        assertThat(count()).isEqualTo(10);
    }

    private int count() {
        Integer c = jdbc.queryForObject("SELECT count(*) FROM outbox", Integer.class);
        return c == null ? -1 : c;
    }
}
