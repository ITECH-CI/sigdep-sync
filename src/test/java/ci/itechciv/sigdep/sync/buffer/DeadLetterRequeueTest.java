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
 * Vérifie la commande d'exploitation de remise en file des DEAD_LETTER
 * ({@link OutboxRepository#requeueDeadLetter}) : status → PENDING, attempts → 0,
 * last_error conservé, filtrage par entité, et comptage.
 */
class DeadLetterRequeueTest {

    @TempDir
    Path tmp;

    private JdbcTemplate buffer;
    private OutboxRepository outbox;

    @BeforeEach
    void setUp() throws IOException {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:" + tmp.resolve("buffer.sqlite"));
        buffer = new JdbcTemplate(ds);
        try (InputStream in = getClass().getResourceAsStream("/db/sqlite/buffer-schema.sql")) {
            String ddl = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            for (String stmt : BufferSchemaInitializer.splitStatements(ddl)) {
                buffer.execute(stmt);
            }
        }
        outbox = new OutboxRepository(buffer, new BufferWriteLock());
    }

    /** Enfile une ligne puis la force en DEAD_LETTER avec attempts>0 et une erreur. */
    private void enqueueDeadLetter(EntityType entity, long sourceId, String error) {
        outbox.enqueueBatch(List.of(new EnqueueRow(
                entity, UUID.randomUUID(), LocalDateTime.of(2026, 1, 1, 0, 0), sourceId, "{}")));
        buffer.update("UPDATE outbox SET status='DEAD_LETTER', attempts=10, last_error=? "
                + "WHERE source_id=?", error, sourceId);
    }

    private int countByStatus(String status) {
        Integer n = buffer.queryForObject(
                "SELECT count(*) FROM outbox WHERE status=?", Integer.class, status);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("Requeue par entité : seule l'entité ciblée repart, attempts=0, last_error conservé")
    void requeueSingleEntity() {
        enqueueDeadLetter(EntityType.LAB_RESULTS, 1L, "UPSERT_FAILED: colonne trop courte");
        enqueueDeadLetter(EntityType.VISITS, 2L, "UPSERT_FAILED: autre");

        assertThat(outbox.deadLetterCount(EntityType.LAB_RESULTS)).isEqualTo(1);
        assertThat(outbox.deadLetterCount(null)).isEqualTo(2);

        int requeued = outbox.requeueDeadLetter(EntityType.LAB_RESULTS);

        assertThat(requeued).isEqualTo(1);
        // LAB_RESULTS remise en file ; VISITS toujours bloquée.
        assertThat(outbox.deadLetterCount(EntityType.LAB_RESULTS)).isZero();
        assertThat(outbox.deadLetterCount(EntityType.VISITS)).isEqualTo(1);
        assertThat(countByStatus("PENDING")).isEqualTo(1);

        // La ligne LAB_RESULTS : PENDING, attempts remis à 0, mais last_error gardé.
        var row = buffer.queryForMap(
                "SELECT status, attempts, last_error FROM outbox WHERE source_id=1");
        assertThat(row.get("status")).isEqualTo("PENDING");
        assertThat(((Number) row.get("attempts")).intValue()).isZero();
        assertThat((String) row.get("last_error")).contains("UPSERT_FAILED");
    }

    @Test
    @DisplayName("Requeue global (entité null) : toutes les entités repartent")
    void requeueAllEntities() {
        enqueueDeadLetter(EntityType.LAB_RESULTS, 1L, "e1");
        enqueueDeadLetter(EntityType.VISITS, 2L, "e2");
        enqueueDeadLetter(EntityType.PATIENTS, 3L, "e3");

        int requeued = outbox.requeueDeadLetter(null);

        assertThat(requeued).isEqualTo(3);
        assertThat(outbox.deadLetterCount(null)).isZero();
        assertThat(countByStatus("PENDING")).isEqualTo(3);
    }

    @Test
    @DisplayName("Aucune ligne DEAD_LETTER : requeue est un no-op (0 ligne)")
    void requeueNoop() {
        outbox.enqueueBatch(List.of(new EnqueueRow(
                EntityType.VISITS, UUID.randomUUID(), LocalDateTime.of(2026, 1, 1, 0, 0), 9L, "{}")));
        // ligne PENDING, pas DEAD_LETTER

        assertThat(outbox.requeueDeadLetter(null)).isZero();
        assertThat(countByStatus("PENDING")).isEqualTo(1);
    }
}
