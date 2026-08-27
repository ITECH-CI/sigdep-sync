package ci.itechciv.sigdep.sync.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.contracts.SyncBatchRequest;
import ci.itechciv.sigdep.contracts.SyncBatchResponse;
import ci.itechciv.sigdep.contracts.dto.PatientDto;
import ci.itechciv.sigdep.sync.buffer.OutboxEnqueuer;
import ci.itechciv.sigdep.sync.buffer.OutboxFlusher;
import ci.itechciv.sigdep.sync.buffer.OutboxFlusher.FlushResult;
import ci.itechciv.sigdep.sync.buffer.OutboxRepository;
import ci.itechciv.sigdep.sync.buffer.BufferSchemaInitializer;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;

/**
 * Vérifie le pipeline extraction/push découplé sur les 3 scénarios demandés :
 * hub lent (le pipeline continue d'extraire pendant le round-trip), hub
 * injoignable (pause à profondeur, pas de sur-extraction ni saturation), et
 * reprise après redémarrage (sync_state = source de vérité, curseur mémoire
 * perdu → rejeu idempotent).
 *
 * On assemble les vrais collaborateurs (outbox + sync_state sur une SQLite
 * temporaire, flusher réel) et on injecte un CentralApiClient factice pour
 * contrôler la latence et la disponibilité du hub.
 */
class EntityPipelineTest {

    private static final EntityType ENTITY = EntityType.PATIENTS;
    private static final int BATCH = 100;
    private static final int MAX_PAGES = 10_000;
    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);

    @TempDir
    Path tmp;

    // --- collaborateurs réels sur SQLite temp -------------------------------

    private JdbcTemplate openBuffer() throws IOException {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:" + tmp.resolve("buffer.sqlite"));
        JdbcTemplate t = new JdbcTemplate(ds);
        try (InputStream in = getClass().getResourceAsStream("/db/sqlite/buffer-schema.sql")) {
            String ddl = StreamUtils.copyToString(in, StandardCharsets.UTF_8);
            for (String stmt : BufferSchemaInitializer.splitStatements(ddl)) {
                t.execute(stmt);
            }
        }
        return t;
    }

    private static SyncProperties props() {
        return new SyncProperties(
                "SITE", "https://hub.example.org", BATCH, 15, EPOCH,
                java.util.Map.of(), java.util.Map.of(), "api-key-1234",
                "jdbc:mysql://localhost/openmrs",
                new SyncProperties.Backfill(false, 30, "0 0 22 * * MON-FRI"),
                3, 2,
                new SyncProperties.Http(10, 60, 60, 0, 500L, 30000L));
    }

    /** Extracteur factice : produit `pageCount` pages pleines puis une vide. */
    private static final class FakeExtractor implements DataExtractor {
        final int pageCount;
        final AtomicInteger extractCalls = new AtomicInteger();
        int produced;

        FakeExtractor(int pageCount) { this.pageCount = pageCount; }

        @Override public EntityType getEntityType() { return ENTITY; }
        @Override public String getSourceTable() { return "patients"; }
        @Override public String getWatermarkColumn() { return "date_changed"; }
        @Override public boolean isEnabled() { return true; }

        @Override
        public synchronized List<CanonicalRecord> extract(SyncCursor cursor, int batchSize) {
            extractCalls.incrementAndGet();
            if (produced >= pageCount) {
                return List.of();
            }
            List<CanonicalRecord> page = new ArrayList<>(batchSize);
            for (int i = 0; i < batchSize; i++) {
                LocalDateTime wm = EPOCH.plusMinutes((long) produced * batchSize + i);
                UUID uuid = UUID.randomUUID();
                // Payload = vrai PatientDto : il doit se re-désérialiser côté
                // flusher avant le push (le contenu importe peu, seule la
                // validité JSON compte pour ce test de pipeline).
                PatientDto dto = new PatientDto(
                        uuid, "M", null, null, null, null, null, null, null, List.of(), false);
                page.add(new CanonicalRecord(ENTITY, uuid, wm, (long) (produced * batchSize + i), dto));
            }
            produced++;
            return page;
        }
    }

    /** Hub factice : latence contrôlée + option "toujours injoignable". */
    private static final class FakeHub extends CentralApiClient {
        final long latencyMs;
        final boolean unreachable;
        final AtomicInteger pushCalls = new AtomicInteger();

        FakeHub(long latencyMs, boolean unreachable) {
            super(null, new ObjectMapper(), props());
            this.latencyMs = latencyMs;
            this.unreachable = unreachable;
        }

        @Override
        public SyncBatchResponse push(EntityType entityType, SyncBatchRequest<?> batch)
                throws IOException {
            pushCalls.incrementAndGet();
            if (latencyMs > 0) {
                try { Thread.sleep(latencyMs); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
            if (unreachable) {
                throw new IOException("hub injoignable (simulé)");
            }
            // Tout accepté.
            int n = batch.records().size();
            return new SyncBatchResponse(UUID.randomUUID(), n, 0, List.of());
        }
    }

    private EntityPipeline pipeline(JdbcTemplate buffer, DataExtractor extractor, CentralApiClient hub) {
        OutboxRepository outbox = new OutboxRepository(buffer, new ci.itechciv.sigdep.sync.buffer.BufferWriteLock());
        SyncStateRepository state = new SyncStateRepository(buffer, new ci.itechciv.sigdep.sync.buffer.BufferWriteLock());
        OutboxEnqueuer enqueuer = new OutboxEnqueuer(outbox, new ObjectMapper());
        OutboxFlusher flusher = new OutboxFlusher(outbox, hub, new ObjectMapper(), state, props());
        return new EntityPipeline(
                extractor, BATCH, props().pipelineDepth(), MAX_PAGES, EPOCH,
                state, flusher, outbox, enqueuer::enqueue);
    }

    // --- scénarios -----------------------------------------------------------

    @Test
    @Timeout(30)
    @DisplayName("Hub lent : toutes les pages finissent acquittées, sync_state avance")
    void slowHub_allAcknowledged() throws Exception {
        JdbcTemplate buffer = openBuffer();
        FakeExtractor extractor = new FakeExtractor(5);      // 5 pages de 100
        FakeHub hub = new FakeHub(50, false);                // 50 ms par push

        FlushResult r = pipeline(buffer, extractor, hub).runCycle();

        assertThat(r.rowsAccepted()).isEqualTo(500);
        assertThat(r.stoppedEarly()).isFalse();
        // 5 pages poussées, l'outbox est vidée.
        assertThat(hub.pushCalls.get()).isGreaterThanOrEqualTo(5);
        Integer remaining = buffer.queryForObject(
                "SELECT count(*) FROM outbox WHERE status IN ('PENDING','REJECTED')", Integer.class);
        assertThat(remaining).isZero();
        // sync_state a avancé (watermark non nul).
        SyncStateRepository state = new SyncStateRepository(buffer, new ci.itechciv.sigdep.sync.buffer.BufferWriteLock());
        assertThat(state.getWatermark(ENTITY)).isPresent();
    }

    @Test
    @Timeout(30)
    @DisplayName("Hub injoignable : pause à la profondeur, pas de sur-extraction ni saturation, sync_state inchangé")
    void unreachableHub_pausesBounded() throws Exception {
        JdbcTemplate buffer = openBuffer();
        FakeExtractor extractor = new FakeExtractor(100);    // "infini" à l'échelle du test
        FakeHub hub = new FakeHub(0, true);                  // toujours injoignable

        FlushResult r = pipeline(buffer, extractor, hub).runCycle();

        assertThat(r.stoppedEarly()).as("le pipeline signale la pause réseau").isTrue();
        // BORNAGE : l'extraction ne doit pas s'être emballée. Avec depth=2, on
        // tolère au plus depth+1 pages extraites avant l'arrêt (marge de course).
        assertThat(extractor.produced)
                .as("pas de sur-extraction : extraction bornée par la profondeur")
                .isLessThanOrEqualTo(props().pipelineDepth() + 1);
        // Aucun watermark ne doit avoir avancé (aucun ACK).
        SyncStateRepository state = new SyncStateRepository(buffer, new ci.itechciv.sigdep.sync.buffer.BufferWriteLock());
        assertThat(state.getWatermark(ENTITY))
                .as("sync_state ne bouge pas sur un lot non acquitté")
                .isEmpty();
        // Les lignes extraites restent en outbox (PENDING), reconstructibles.
        Integer pending = buffer.queryForObject(
                "SELECT count(*) FROM outbox WHERE status = 'PENDING'", Integer.class);
        assertThat(pending).isGreaterThan(0);
    }

    @Test
    @Timeout(30)
    @DisplayName("Redémarrage en cours de pipeline : on repart de sync_state, rejeu idempotent, pas de perte")
    void restartMidPipeline_replaysFromSyncState() throws Exception {
        JdbcTemplate buffer = openBuffer();

        // 1er "run" : hub injoignable → rien n'est acquitté, sync_state vide,
        // des lignes restent en outbox (simule un crash avant l'ACK).
        FakeExtractor first = new FakeExtractor(100);
        pipeline(buffer, first, new FakeHub(0, true)).runCycle();
        SyncStateRepository state = new SyncStateRepository(buffer, new ci.itechciv.sigdep.sync.buffer.BufferWriteLock());
        assertThat(state.getWatermark(ENTITY)).as("aucun ACK au 1er run").isEmpty();
        int pendingBefore = buffer.queryForObject(
                "SELECT count(*) FROM outbox WHERE status='PENDING'", Integer.class);
        assertThat(pendingBefore).isGreaterThan(0);

        // 2e "run" (redémarrage) : hub OK. Le pipeline repart de sync_state
        // (vide → EPOCH), draine d'abord l'outbox en attente, et acquitte.
        FakeExtractor second = new FakeExtractor(2);
        FlushResult r2 = pipeline(buffer, second, new FakeHub(0, false)).runCycle();

        assertThat(r2.rowsAccepted()).isGreaterThan(0);
        // L'outbox finit vidée (les PENDING du 1er run + les nouveaux sont partis).
        Integer remaining = buffer.queryForObject(
                "SELECT count(*) FROM outbox WHERE status IN ('PENDING','REJECTED')", Integer.class);
        assertThat(remaining).as("tout est acquitté après reprise").isZero();
        // sync_state a désormais avancé.
        assertThat(state.getWatermark(ENTITY)).isPresent();
    }
}
