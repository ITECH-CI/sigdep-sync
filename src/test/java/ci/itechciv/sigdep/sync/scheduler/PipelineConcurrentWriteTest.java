package ci.itechciv.sigdep.sync.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.contracts.SyncBatchRequest;
import ci.itechciv.sigdep.contracts.SyncBatchResponse;
import ci.itechciv.sigdep.contracts.dto.PatientDto;
import ci.itechciv.sigdep.sync.buffer.BufferSchemaInitializer;
import ci.itechciv.sigdep.sync.buffer.OutboxEnqueuer;
import ci.itechciv.sigdep.sync.buffer.OutboxFlusher;
import ci.itechciv.sigdep.sync.buffer.OutboxRepository;
import ci.itechciv.sigdep.sync.config.SyncProperties;
import ci.itechciv.sigdep.sync.extractor.CanonicalRecord;
import ci.itechciv.sigdep.sync.extractor.DataExtractor;
import ci.itechciv.sigdep.sync.extractor.SyncCursor;
import ci.itechciv.sigdep.sync.pusher.CentralApiClient;
import ci.itechciv.sigdep.sync.state.SyncStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

/**
 * Reproduit le SQLITE_BUSY constaté en prod après l'introduction du pipeline
 * découplé : deux threads écrivent en concurrence sur le buffer SQLite (le
 * producteur enqueue pendant que le consommateur markSent/markRejected).
 * SQLite n'autorise qu'un écrivain à la fois — sans {@code busy_timeout}, la
 * seconde écriture échoue aussitôt (« database is locked »).
 *
 * Le buffer est ouvert EXACTEMENT comme en prod (DataSourcesConfig : WAL +
 * synchronous=NORMAL + busy_timeout via SQLiteConfig), et on force le
 * chevauchement producteur/consommateur avec un hub LENT. Le pipeline doit
 * drainer sans lever d'exception.
 */
class PipelineConcurrentWriteTest {

    private static final EntityType ENTITY = EntityType.PATIENTS;
    private static final int BATCH = 200;
    private static final int MAX_PAGES = 10_000;
    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);

    @TempDir
    Path tmp;

    /** Ouvre le buffer AVEC les mêmes PRAGMA que la prod (dont busy_timeout). */
    private JdbcTemplate openBufferLikeProd() throws IOException {
        SQLiteConfig cfg = new SQLiteConfig();
        cfg.setJournalMode(SQLiteConfig.JournalMode.WAL);
        cfg.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        cfg.setBusyTimeout(5_000); // le fix
        SQLiteDataSource ds = new SQLiteDataSource(cfg);
        ds.setUrl("jdbc:sqlite:" + tmp.resolve("buffer.sqlite"));
        return applySchema(ds);
    }

    private JdbcTemplate applySchema(DataSource ds) throws IOException {
        JdbcTemplate t = new JdbcTemplate(ds);
        try (InputStream in = getClass().getResourceAsStream("/db/sqlite/buffer-schema.sql")) {
            String ddl = org.springframework.util.StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            for (String stmt : BufferSchemaInitializer.splitStatements(ddl)) {
                t.execute(stmt);
            }
        }
        return t;
    }

    private static SyncProperties props() {
        return new SyncProperties(
                "SITE", "https://hub.example.org", BATCH, 15, EPOCH,
                java.util.Map.of(), "api-key-1234", "jdbc:mysql://localhost/openmrs",
                new SyncProperties.Backfill(false, 30, "0 0 22 * * MON-FRI"),
                3, 3, new SyncProperties.Http(10, 60, 60)); // pipeline-depth=3 → plus de chevauchement
    }

    /** Extracteur produisant `pages` pages pleines puis vide. */
    private static final class FakeExtractor implements DataExtractor {
        final int pages;
        int produced;
        FakeExtractor(int pages) { this.pages = pages; }
        @Override public EntityType getEntityType() { return ENTITY; }
        @Override public String getSourceTable() { return "patient"; }
        @Override public String getWatermarkColumn() { return "date_changed"; }
        @Override public boolean isEnabled() { return true; }
        @Override public synchronized List<CanonicalRecord> extract(SyncCursor c, int batchSize) {
            if (produced >= pages) return List.of();
            List<CanonicalRecord> page = new ArrayList<>(batchSize);
            for (int i = 0; i < batchSize; i++) {
                long id = (long) produced * batchSize + i;
                UUID uuid = new UUID(0L, id);
                PatientDto dto = new PatientDto(uuid, "M", null, null, null, null, null, null, null, List.of(), false);
                page.add(new CanonicalRecord(ENTITY, uuid, EPOCH.plusSeconds(id), id, dto));
            }
            produced++;
            return page;
        }
    }

    /** Hub LENT : ralentit le consommateur pour maximiser le chevauchement d'écriture. */
    private static final class SlowHub extends CentralApiClient {
        SlowHub() { super(null, new ObjectMapper(), props()); }
        @Override public SyncBatchResponse push(EntityType e, SyncBatchRequest<?> batch) {
            try { Thread.sleep(20); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            return new SyncBatchResponse(UUID.randomUUID(), batch.records().size(), 0, List.of());
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("Écritures concurrentes producteur/consommateur via le pipeline : pas de SQLITE_BUSY")
    void concurrentWrites_viaPipeline_noBusyError() throws Exception {
        JdbcTemplate buffer = openBufferLikeProd();
        // MÊME instance de lock partagée (comme le singleton Spring) : c'est ce
        // qui sérialise producteur et consommateur.
        var lock = new ci.itechciv.sigdep.sync.buffer.BufferWriteLock();
        OutboxRepository outbox = new OutboxRepository(buffer, lock);
        SyncStateRepository state = new SyncStateRepository(buffer, lock);
        OutboxEnqueuer enqueuer = new OutboxEnqueuer(outbox, new ObjectMapper());
        OutboxFlusher flusher = new OutboxFlusher(outbox, new SlowHub(), new ObjectMapper(), state, props());
        EntityPipeline pipeline = new EntityPipeline(
                new FakeExtractor(20), BATCH, props().pipelineDepth(), MAX_PAGES, EPOCH,
                state, flusher, outbox, enqueuer::enqueue);

        assertThatCode(pipeline::runCycle).doesNotThrowAnyException();

        Integer sent = buffer.queryForObject(
                "SELECT count(*) FROM outbox WHERE status='SENT'", Integer.class);
        assertThat(sent).isEqualTo(4000);
        Integer remaining = buffer.queryForObject(
                "SELECT count(*) FROM outbox WHERE status IN ('PENDING','REJECTED')", Integer.class);
        assertThat(remaining).isZero();
    }

    /**
     * Garde de non-régression du fix : {@link ci.itechciv.sigdep.sync.buffer.BufferWriteLock}
     * SÉRIALISE effectivement les écritures. Deux threads tentent d'écrire en
     * boucle via le même lock (comme producteur + consommateur du pipeline) ;
     * on détecte tout CHEVAUCHEMENT via un compteur d'entrées simultanées dans
     * la section critique. Si le verrou était retiré (ou une instance par
     * repository), le max observé serait 2 → c'est exactement la contention qui
     * provoquait le SQLITE_BUSY côté SQLite.
     */
    @Test
    @Timeout(30)
    @DisplayName("BufferWriteLock sérialise les écritures : jamais deux écrivains simultanés")
    void bufferWriteLock_serializesWriters() throws Exception {
        var lock = new ci.itechciv.sigdep.sync.buffer.BufferWriteLock();
        java.util.concurrent.atomic.AtomicInteger inCritical = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger maxObserved = new java.util.concurrent.atomic.AtomicInteger();

        Runnable writer = () -> {
            for (int i = 0; i < 2000; i++) {
                lock.runExclusively(() -> {
                    int now = inCritical.incrementAndGet();
                    maxObserved.accumulateAndGet(now, Math::max);
                    // petite fenêtre pour maximiser la détection d'un chevauchement
                    for (int s = 0; s < 50; s++) { Math.sqrt(s); }
                    inCritical.decrementAndGet();
                });
            }
        };

        Thread t1 = new Thread(writer, "w1");
        Thread t2 = new Thread(writer, "w2");
        t1.start(); t2.start();
        t1.join(); t2.join();

        assertThat(maxObserved.get())
                .as("au plus UN écrivain à la fois dans la section critique")
                .isEqualTo(1);
    }
}
