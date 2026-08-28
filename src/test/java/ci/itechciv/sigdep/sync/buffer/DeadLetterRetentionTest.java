package ci.itechciv.sigdep.sync.buffer;

import static org.assertj.core.api.Assertions.assertThat;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.sync.buffer.OutboxRepository.EnqueueRow;
import ci.itechciv.sigdep.sync.buffer.OutboxRepository.RejectedId;
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
 * Purge par rétention des DEAD_LETTER : une ligne rejetée en validation garde
 * son payload (données de santé) indéfiniment. On la supprime après N jours en
 * DEAD_LETTER, sans toucher aux lignes récentes ni aux autres statuts, et une
 * ré-extraction efface l'horodatage de bascule.
 */
class DeadLetterRetentionTest {

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

    /** Enfile une ligne et la pousse jusqu'à DEAD_LETTER (maxAttempts=1). */
    private long enqueueThenDeadLetter(String uuid) {
        repo.enqueueBatch(List.of(new EnqueueRow(
                EntityType.PATIENTS, UUID.fromString(uuid), WM, null, "{\"code_arv\":\"X\"}")));
        long id = jdbc.queryForObject(
                "SELECT id FROM outbox WHERE source_uuid = ?", Long.class, uuid);
        // maxAttempts=1 → bascule immédiate en DEAD_LETTER, pose dead_lettered_at.
        repo.markValidationRejected(List.of(new RejectedId(id, "invalide")), 1);
        return id;
    }

    private void backdateDeadLetter(long id, int daysAgo) {
        jdbc.update("UPDATE outbox SET dead_lettered_at = datetime('now', ?) WHERE id = ?",
                "-" + daysAgo + " days", id);
    }

    private String status(long id) {
        return jdbc.queryForObject("SELECT status FROM outbox WHERE id = ?", String.class, id);
    }

    private boolean exists(long id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE id = ?", Integer.class, id) > 0;
    }

    @Test
    @DisplayName("Bascule en DEAD_LETTER : dead_lettered_at est posé")
    void deadLetter_setsTimestamp() {
        long id = enqueueThenDeadLetter("11111111-1111-1111-1111-111111111111");
        assertThat(status(id)).isEqualTo("DEAD_LETTER");
        assertThat(jdbc.queryForObject(
                "SELECT dead_lettered_at FROM outbox WHERE id = ?", String.class, id)).isNotNull();
    }

    @Test
    @DisplayName("Purge : supprime une DEAD_LETTER au-delà de la rétention")
    void purge_deletesExpired() {
        long id = enqueueThenDeadLetter("22222222-2222-2222-2222-222222222222");
        backdateDeadLetter(id, 90); // 90 jours en DEAD_LETTER

        int purged = repo.purgeExpiredDeadLetters(60);

        assertThat(purged).isEqualTo(1);
        assertThat(exists(id)).isFalse(); // payload (donnée de santé) effacé du disque
    }

    @Test
    @DisplayName("Purge : épargne une DEAD_LETTER encore dans la fenêtre de rétention")
    void purge_keepsRecent() {
        long id = enqueueThenDeadLetter("33333333-3333-3333-3333-333333333333");
        backdateDeadLetter(id, 10); // seulement 10 jours

        int purged = repo.purgeExpiredDeadLetters(60);

        assertThat(purged).isZero();
        assertThat(exists(id)).isTrue(); // reprise --requeue-dead-letter encore possible
    }

    @Test
    @DisplayName("Purge : n'affecte pas les lignes REJECTED/PENDING (pas DEAD_LETTER)")
    void purge_ignoresNonDeadLetter() {
        // REJECTED : maxAttempts=5, un seul rejet → reste REJECTED, dead_lettered_at NULL.
        repo.enqueueBatch(List.of(new EnqueueRow(
                EntityType.PATIENTS, UUID.fromString("44444444-4444-4444-4444-444444444444"),
                WM, null, "{}")));
        long rejectedId = jdbc.queryForObject(
                "SELECT id FROM outbox WHERE source_uuid = ?", Long.class,
                "44444444-4444-4444-4444-444444444444");
        repo.markValidationRejected(List.of(new RejectedId(rejectedId, "e")), 5);

        int purged = repo.purgeExpiredDeadLetters(0 + 1); // rétention 1 jour, agressive

        assertThat(purged).isZero();
        assertThat(status(rejectedId)).isEqualTo("REJECTED");
        assertThat(exists(rejectedId)).isTrue();
    }

    @Test
    @DisplayName("Ré-extraction d'une DEAD_LETTER : dead_lettered_at repasse à NULL")
    void reExtract_clearsTimestamp() {
        String uuid = "55555555-5555-5555-5555-555555555555";
        long id = enqueueThenDeadLetter(uuid);
        backdateDeadLetter(id, 90);

        // Le site corrige la fiche → ré-extraction (UPSERT) : PENDING, horodatage effacé.
        repo.enqueueBatch(List.of(new EnqueueRow(
                EntityType.PATIENTS, UUID.fromString(uuid), WM, null, "{\"code_arv\":\"Y\"}")));

        assertThat(status(id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT dead_lettered_at FROM outbox WHERE id = ?", String.class, id)).isNull();
        // Et donc la purge ne la supprime plus.
        assertThat(repo.purgeExpiredDeadLetters(60)).isZero();
    }

    @Test
    @DisplayName("requeueDeadLetter efface aussi dead_lettered_at")
    void requeue_clearsTimestamp() {
        long id = enqueueThenDeadLetter("66666666-6666-6666-6666-666666666666");
        backdateDeadLetter(id, 90);

        repo.requeueDeadLetter(EntityType.PATIENTS);

        assertThat(status(id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject(
                "SELECT dead_lettered_at FROM outbox WHERE id = ?", String.class, id)).isNull();
    }
}
