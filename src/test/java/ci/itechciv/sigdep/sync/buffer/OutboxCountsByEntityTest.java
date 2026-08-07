package ci.itechciv.sigdep.sync.buffer;

import static org.assertj.core.api.Assertions.assertThat;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.sync.buffer.OutboxRepository.EnqueueRow;
import ci.itechciv.sigdep.sync.buffer.OutboxRepository.OutboxCounts;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;

/**
 * Vérifie les compteurs outbox par (entity_type, status) qui alimentent le
 * rapport de réconciliation ({@code --reconcile}).
 */
class OutboxCountsByEntityTest {

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

    private void enqueue(EntityType entity, long sourceId, String status) {
        outbox.enqueueBatch(List.of(new EnqueueRow(
                entity, UUID.randomUUID(), LocalDateTime.of(2026, 1, 1, 0, 0), sourceId, "{}")));
        if (!"PENDING".equals(status)) {
            buffer.update("UPDATE outbox SET status=? WHERE source_id=?", status, sourceId);
        }
    }

    @Test
    @DisplayName("Compteurs par entité et statut : agrégat correct, entité sans ligne absente")
    void countsAggregateByEntityAndStatus() {
        // LAB_RESULTS : 2 SENT, 1 PENDING, 1 REJECTED, 1 DEAD_LETTER
        enqueue(EntityType.LAB_RESULTS, 1L, "SENT");
        enqueue(EntityType.LAB_RESULTS, 2L, "SENT");
        enqueue(EntityType.LAB_RESULTS, 3L, "PENDING");
        enqueue(EntityType.LAB_RESULTS, 4L, "REJECTED");
        enqueue(EntityType.LAB_RESULTS, 5L, "DEAD_LETTER");
        // VISITS : 1 SENT
        enqueue(EntityType.VISITS, 6L, "SENT");

        Map<String, OutboxCounts> counts = outbox.outboxCountsByEntity();

        OutboxCounts lab = counts.get("LAB_RESULTS");
        assertThat(lab).isNotNull();
        assertThat(lab.sent()).isEqualTo(2);
        assertThat(lab.pending()).isEqualTo(1);
        assertThat(lab.rejected()).isEqualTo(1);
        assertThat(lab.dead()).isEqualTo(1);
        assertThat(lab.total()).isEqualTo(5);

        assertThat(counts.get("VISITS").sent()).isEqualTo(1);

        // PATIENTS : aucune ligne → absente de la map.
        assertThat(counts).doesNotContainKey("PATIENTS");
    }

    @Test
    @DisplayName("Outbox vide : map vide")
    void emptyOutbox() {
        assertThat(outbox.outboxCountsByEntity()).isEmpty();
    }
}
