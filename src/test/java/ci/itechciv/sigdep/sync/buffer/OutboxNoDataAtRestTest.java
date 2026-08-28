package ci.itechciv.sigdep.sync.buffer;

import static org.assertj.core.api.Assertions.assertThat;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.sync.buffer.OutboxRepository.EnqueueRow;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;

/**
 * Le buffer local ne doit PAS devenir une seconde base de données de santé sur
 * le poste du site. Deux garanties sont vérifiées ici :
 *
 * <ol>
 *   <li>UNICITÉ — une ré-extraction du même enregistrement met à jour la ligne
 *       existante au lieu d'en créer une nouvelle, y compris quand elle était
 *       déjà SENT (le comportement historique accumulait une copie du payload
 *       à chaque passage) ;</li>
 *   <li>EFFACEMENT — dès que le hub a accepté la ligne, son payload_json est
 *       vidé : plus aucune donnée nominative au repos pour un enregistrement
 *       déjà transmis.</li>
 * </ol>
 */
class OutboxNoDataAtRestTest {

    private static final UUID UUID_A = UUID.fromString("3ad556cd-7d44-45c5-b3ff-0afc61c84ca9");
    private static final LocalDateTime WM = LocalDateTime.of(2026, 8, 27, 15, 26);

    @TempDir
    Path tmp;

    private JdbcTemplate jdbc;
    private OutboxRepository repo;

    @BeforeEach
    void setUp() throws IOException {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:" + tmp.resolve("buffer.sqlite"));
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("PRAGMA journal_mode=WAL");
        String ddl;
        try (InputStream in = getClass().getResourceAsStream("/db/sqlite/buffer-schema.sql")) {
            ddl = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
        }
        for (String stmt : BufferSchemaInitializer.splitStatements(ddl)) {
            jdbc.execute(stmt);
        }
        repo = new OutboxRepository(jdbc, new BufferWriteLock());
    }

    private EnqueueRow row(String payload) {
        return new EnqueueRow(EntityType.TREATMENT_INITIATIONS, UUID_A, WM, 42L, payload);
    }

    private Long rowCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Long.class);
    }

    @Test
    @DisplayName("markSent efface le payload : pas de donnée de santé au repos")
    void markSentClearsPayload() {
        repo.enqueueBatch(List.of(row("{\"weight\":72.07}")));
        long id = jdbc.queryForObject("SELECT id FROM outbox", Long.class);

        repo.markSent(List.of(id));

        String payload = jdbc.queryForObject(
                "SELECT payload_json FROM outbox WHERE id = ?", String.class, id);
        assertThat(payload).isEmpty();
        // La ligne survit : --reconcile en fait un COUNT et la traçabilité
        // (source_uuid, sent_at) reste exploitable.
        assertThat(rowCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM outbox WHERE id = ?", String.class, id)).isEqualTo("SENT");
        assertThat(jdbc.queryForObject(
                "SELECT sent_at FROM outbox WHERE id = ?", String.class, id)).isNotNull();
    }

    @Test
    @DisplayName("Ré-extraction après SENT : une seule ligne, pas de copie accumulée")
    void reExtractAfterSentDoesNotDuplicate() {
        repo.enqueueBatch(List.of(row("{\"weight\":7207}")));
        long id = jdbc.queryForObject("SELECT id FROM outbox", Long.class);
        repo.markSent(List.of(id));

        // Le site corrige la donnée à la source → l'encounter est ré-extrait.
        repo.enqueueBatch(List.of(row("{\"weight\":72.07}")));

        assertThat(rowCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT payload_json FROM outbox", String.class)).contains("72.07");
        // Le payload est neuf : il doit repartir avec un compteur neuf.
        assertThat(jdbc.queryForObject("SELECT status FROM outbox", String.class))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox", Integer.class))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT sent_at FROM outbox", String.class))
                .isNull();
    }

    @Test
    @DisplayName("Ré-extraction d'une DEAD_LETTER : la ligne est débloquée par le payload corrigé")
    void reExtractRevivesDeadLetter() {
        repo.enqueueBatch(List.of(row("{\"weight\":7207}")));
        long id = jdbc.queryForObject("SELECT id FROM outbox", Long.class);
        // 10 rejets de validation → DEAD_LETTER (cas du poids saisi en grammes).
        for (int i = 0; i < 10; i++) {
            repo.markValidationRejected(
                    List.of(new OutboxRepository.RejectedId(id, "numeric field overflow")), 10);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM outbox", String.class))
                .isEqualTo("DEAD_LETTER");

        repo.enqueueBatch(List.of(row("{\"weight\":72.07}")));

        assertThat(rowCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox", String.class))
                .isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT last_error FROM outbox", String.class)).isNull();
    }

    @Test
    @DisplayName("Le flusher ne relit jamais le payload vidé d'une ligne SENT")
    void sentRowsAreNeverRetried() {
        repo.enqueueBatch(List.of(row("{\"weight\":72.07}")));
        long id = jdbc.queryForObject("SELECT id FROM outbox", Long.class);
        repo.markSent(List.of(id));

        assertThat(repo.findRetryable(EntityType.TREATMENT_INITIATIONS, 100, 10)).isEmpty();
    }
}
