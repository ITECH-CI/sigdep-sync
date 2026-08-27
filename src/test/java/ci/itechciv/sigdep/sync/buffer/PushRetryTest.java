package ci.itechciv.sigdep.sync.buffer;

import static org.assertj.core.api.Assertions.assertThat;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.contracts.SyncBatchRequest;
import ci.itechciv.sigdep.contracts.SyncBatchResponse;
import ci.itechciv.sigdep.sync.buffer.OutboxRepository.EnqueueRow;
import ci.itechciv.sigdep.sync.config.SyncProperties;
import ci.itechciv.sigdep.sync.pusher.CentralApiClient;
import ci.itechciv.sigdep.sync.pusher.RetryableTransportException;
import ci.itechciv.sigdep.sync.state.SyncStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;

/**
 * Vérifie la résilience du push (SYNC-12) : un échec de TRANSPORT
 * (transitoire) est ré-essayé avec backoff avant de mettre l'entité en pause,
 * alors qu'un rejet APPLICATIF (permanent) ne l'est pas.
 */
class PushRetryTest {

    private static final EntityType ENTITY = EntityType.PATIENTS;

    @TempDir
    Path tmp;

    private JdbcTemplate buffer;
    private OutboxRepository outbox;
    private SyncStateRepository state;

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
        var lock = new BufferWriteLock();
        outbox = new OutboxRepository(buffer, lock);
        state = new SyncStateRepository(buffer, lock);
        // Une ligne à pousser.
        outbox.enqueueBatch(List.of(new EnqueueRow(
                ENTITY, UUID.randomUUID(), LocalDateTime.of(2026, 1, 1, 0, 0), 1L, "{}")));
    }

    /** props avec maxRetries et délais courts pour un test rapide. */
    private static SyncProperties props(int maxRetries) {
        return new SyncProperties(
                "SITE", "https://hub.example.org", 500, 15, LocalDateTime.of(1970, 1, 1, 0, 0),
                java.util.Map.of(), java.util.Map.of(), "api-key-1234", "jdbc:mysql://localhost/openmrs",
                new SyncProperties.Backfill(false, 30, "0 0 22 * * MON-FRI"),
                3, 2,
                new SyncProperties.Http(10, 60, 60, maxRetries, 5L, 20L)); // délais courts
    }

    /** Hub factice : échoue en TRANSPORT les {@code failTransportTimes} premiers appels, puis accepte. */
    private static final class FlakyHub extends CentralApiClient {
        final int failTransportTimes;
        final AtomicInteger calls = new AtomicInteger();
        FlakyHub(int failTransportTimes) {
            super(null, new ObjectMapper(), props(0));
            this.failTransportTimes = failTransportTimes;
        }
        @Override
        public SyncBatchResponse push(EntityType e, SyncBatchRequest<?> batch) throws IOException {
            int n = calls.incrementAndGet();
            if (n <= failTransportTimes) {
                throw new RetryableTransportException("coupure transport simulée #" + n);
            }
            return new SyncBatchResponse(UUID.randomUUID(), batch.records().size(), 0, List.of());
        }
    }

    /** Hub factice : rejette toujours en APPLICATIF (IOException simple, non retryable). */
    private static final class AppRejectHub extends CentralApiClient {
        final AtomicInteger calls = new AtomicInteger();
        AppRejectHub() { super(null, new ObjectMapper(), props(0)); }
        @Override
        public SyncBatchResponse push(EntityType e, SyncBatchRequest<?> batch) throws IOException {
            calls.incrementAndGet();
            throw new IOException("HTTP 400 : requête invalide (applicatif)");
        }
    }

    private OutboxFlusher flusher(CentralApiClient hub, int maxRetries) {
        return new OutboxFlusher(outbox, hub, new ObjectMapper(), state, props(maxRetries));
    }

    @Test
    @DisplayName("Échec de transport transitoire : retry puis succès, pas de pause")
    void transportFailure_retriesThenSucceeds() {
        FlakyHub hub = new FlakyHub(3); // échoue 3 fois, réussit à la 4e
        var result = flusher(hub, 5).flush(ENTITY);

        assertThat(hub.calls.get()).as("3 échecs + 1 succès = 4 appels").isEqualTo(4);
        assertThat(result.stoppedEarly()).as("pas de pause : le retry a réussi").isFalse();
        assertThat(result.rowsAccepted()).isEqualTo(1);
        Integer sent = buffer.queryForObject(
                "SELECT count(*) FROM outbox WHERE status='SENT'", Integer.class);
        assertThat(sent).isEqualTo(1);
    }

    @Test
    @DisplayName("Rejet applicatif : aucun retry (un seul appel), la ligne reste en file")
    void applicativeReject_noRetry() {
        AppRejectHub hub = new AppRejectHub();
        var result = flusher(hub, 5).flush(ENTITY);

        assertThat(hub.calls.get()).as("un seul appel : pas de retry sur rejet applicatif").isEqualTo(1);
        assertThat(result.stoppedEarly()).as("pause après l'échec applicatif").isTrue();
        Integer pending = buffer.queryForObject(
                "SELECT count(*) FROM outbox WHERE status='PENDING'", Integer.class);
        assertThat(pending).isEqualTo(1);
    }

    @Test
    @DisplayName("Transport en échec au-delà de maxRetries : pause après épuisement des tentatives")
    void transportFailure_exhaustsRetriesThenPauses() {
        FlakyHub hub = new FlakyHub(100); // échoue toujours
        var result = flusher(hub, 3).flush(ENTITY); // maxRetries=3

        assertThat(hub.calls.get()).as("1 essai initial + 3 retries = 4 appels").isEqualTo(4);
        assertThat(result.stoppedEarly()).as("pause après épuisement des retries").isTrue();
    }
}
