package ci.itechciv.sigdep.sync.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;

/**
 * Tests d'intégration de la pagination KEYSET composite (date, id).
 *
 * Le risque : si plus de {@code batchSize} lignes partagent le même
 * {@code date_changed} (fréquent sur OpenMRS après une migration de masse), une
 * pagination sur la date seule saute silencieusement les lignes restantes du
 * timestamp ({@code > date} strict) ou boucle. Le tie-breaker {@code id}
 * garantit un ordre total et une progression sûre.
 *
 * L'extracteur {@link KeysetExtractor} reproduit fidèlement la logique keyset
 * des vrais extracteurs (WHERE (date,id) > (?,?) ORDER BY date,id LIMIT n) sur
 * un dataset en mémoire, en s'appuyant sur le {@link SyncCursor} fourni par le
 * pipeline. On l'exécute à travers le vrai {@link EntityPipeline} + outbox +
 * flusher (SQLite temporaire, hub factice qui accepte tout).
 */
class KeysetPaginationTest {

    private static final EntityType ENTITY = EntityType.PATIENTS;
    private static final int BATCH = 100;
    private static final int MAX_PAGES = 10_000;
    private static final LocalDateTime EPOCH = LocalDateTime.of(1970, 1, 1, 0, 0);
    // Timestamp unique partagé par TOUTES les lignes du dataset : le pire cas.
    private static final LocalDateTime SAME_TS = LocalDateTime.of(2026, 1, 1, 12, 0, 0);

    @TempDir
    Path tmp;

    /** Un enregistrement source (id, timestamp). */
    private record Row(long id, LocalDateTime ts) {}

    /**
     * Extracteur keyset sur dataset en mémoire. Applique EXACTEMENT la clause
     * des vrais extracteurs : garde les lignes strictement après (cursor.date,
     * cursor.id), triées (date, id), limitées à batchSize.
     */
    private static final class KeysetExtractor implements DataExtractor {
        private final List<Row> data;

        KeysetExtractor(List<Row> data) {
            // Trié (ts, id) comme le ferait l'ORDER BY.
            this.data = data.stream()
                    .sorted((a, b) -> a.ts().equals(b.ts())
                            ? Long.compare(a.id(), b.id())
                            : a.ts().compareTo(b.ts()))
                    .toList();
        }

        @Override public EntityType getEntityType() { return ENTITY; }
        @Override public String getSourceTable() { return "patient"; }
        @Override public String getWatermarkColumn() { return "date_changed"; }
        @Override public boolean isEnabled() { return true; }

        @Override
        public List<CanonicalRecord> extract(SyncCursor cursor, int batchSize) {
            List<CanonicalRecord> page = new ArrayList<>(batchSize);
            for (Row r : data) {
                boolean after = r.ts().isAfter(cursor.watermark())
                        || (r.ts().isEqual(cursor.watermark()) && r.id() > cursor.lastId());
                if (!after) {
                    continue;
                }
                UUID uuid = new UUID(0L, r.id()); // uuid déterministe par id
                PatientDto dto = new PatientDto(
                        uuid, "M", null, null, null, null, null, null, null, List.of(), false);
                page.add(new CanonicalRecord(ENTITY, uuid, r.ts(), r.id(), dto));
                if (page.size() >= batchSize) {
                    break;
                }
            }
            return page;
        }
    }

    /** Hub factice : accepte tout (option latence non nécessaire ici). */
    private static final class AcceptAllHub extends CentralApiClient {
        AcceptAllHub() { super(null, new ObjectMapper(), props()); }
        @Override
        public SyncBatchResponse push(EntityType e, SyncBatchRequest<?> batch) {
            return new SyncBatchResponse(UUID.randomUUID(), batch.records().size(), 0, List.of());
        }
    }

    private static SyncProperties props() {
        return new SyncProperties(
                "SITE", "https://hub.example.org", BATCH, 15, EPOCH,
                java.util.Map.of(), "api-key-1234", "jdbc:mysql://localhost/openmrs",
                new SyncProperties.Backfill(false, 30, "0 0 22 * * MON-FRI"),
                3, 2, new SyncProperties.Http(10, 60, 60));
    }

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

    private EntityPipeline pipeline(JdbcTemplate buffer, DataExtractor extractor) {
        OutboxRepository outbox = new OutboxRepository(buffer, new ci.itechciv.sigdep.sync.buffer.BufferWriteLock());
        SyncStateRepository state = new SyncStateRepository(buffer, new ci.itechciv.sigdep.sync.buffer.BufferWriteLock());
        OutboxEnqueuer enqueuer = new OutboxEnqueuer(outbox, new ObjectMapper());
        OutboxFlusher flusher = new OutboxFlusher(outbox, new AcceptAllHub(), new ObjectMapper(), state, props());
        return new EntityPipeline(
                extractor, BATCH, props().pipelineDepth(), MAX_PAGES, EPOCH,
                state, flusher, outbox, enqueuer::enqueue);
    }

    /** Les uuid déterministes (UUID(0, id)) permettent de vérifier l'unicité. */
    private Set<UUID> pushedUuids(JdbcTemplate buffer) {
        return new HashSet<>(buffer.queryForList(
                "SELECT source_uuid FROM outbox WHERE status = 'SENT'", String.class)
                .stream().map(UUID::fromString).toList());
    }

    @Test
    @Timeout(30)
    @DisplayName("batchSize+50 lignes au MÊME date_changed : toutes extraites exactement une fois, sans boucle")
    void sameTimestampGroup_allExtractedExactlyOnce() throws Exception {
        JdbcTemplate buffer = openBuffer();
        int total = BATCH + 50;                       // 150 lignes, > batchSize
        List<Row> data = new ArrayList<>();
        for (long id = 1; id <= total; id++) {
            data.add(new Row(id, SAME_TS));           // TOUTES au même timestamp
        }

        pipeline(buffer, new KeysetExtractor(data)).runCycle();

        // Chaque ligne poussée exactement une fois (uuid unique par id).
        Integer sent = buffer.queryForObject(
                "SELECT count(*) FROM outbox WHERE status = 'SENT'", Integer.class);
        assertThat(sent).as("les %d lignes du groupe de timestamps identiques sont toutes passées", total)
                .isEqualTo(total);
        assertThat(pushedUuids(buffer)).as("aucun doublon").hasSize(total);
        // Le curseur keyset a avancé jusqu'au dernier id du groupe.
        SyncStateRepository state = new SyncStateRepository(buffer, new ci.itechciv.sigdep.sync.buffer.BufferWriteLock());
        assertThat(state.getLastId(ENTITY)).contains((long) total);
    }

    @Test
    @Timeout(30)
    @DisplayName("Redémarrage au milieu d'un groupe de timestamps identiques : ni doublon ni perte")
    void restartMidGroup_noDuplicateNoLoss() throws Exception {
        JdbcTemplate buffer = openBuffer();
        int total = BATCH + 50;
        List<Row> data = new ArrayList<>();
        for (long id = 1; id <= total; id++) {
            data.add(new Row(id, SAME_TS));
        }

        // 1er run : pipeline profondeur 2, mais on ARRÊTE tôt en simulant un
        // "redémarrage" — on ne joue qu'un cycle partiel via un plafond de
        // pages à 1 (une seule page de batchSize part, puis on coupe).
        OutboxRepository outbox = new OutboxRepository(buffer, new ci.itechciv.sigdep.sync.buffer.BufferWriteLock());
        SyncStateRepository state = new SyncStateRepository(buffer, new ci.itechciv.sigdep.sync.buffer.BufferWriteLock());
        OutboxEnqueuer enqueuer = new OutboxEnqueuer(outbox, new ObjectMapper());
        OutboxFlusher flusher = new OutboxFlusher(outbox, new AcceptAllHub(), new ObjectMapper(), state, props());
        new EntityPipeline(new KeysetExtractor(data), BATCH, 1, /*maxPages*/ 1, EPOCH,
                state, flusher, outbox, enqueuer::enqueue).runCycle();

        int afterFirst = buffer.queryForObject(
                "SELECT count(*) FROM outbox WHERE status='SENT'", Integer.class);
        // Le 1er run n'a traité qu'une partie du groupe (plafond de pages = 1).
        assertThat(afterFirst).isEqualTo(BATCH);
        // Le curseur persistant a mémorisé où on s'est arrêté DANS le groupe.
        long lastIdAfterFirst = state.getLastId(ENTITY).orElseThrow();
        assertThat(lastIdAfterFirst).isEqualTo(BATCH);

        // 2e run (redémarrage) : nouveau pipeline, repart de sync_state
        // (SAME_TS, lastId=100). Doit reprendre EXACTEMENT à la ligne 101.
        pipeline(buffer, new KeysetExtractor(data)).runCycle();

        Integer sentTotal = buffer.queryForObject(
                "SELECT count(*) FROM outbox WHERE status='SENT'", Integer.class);
        assertThat(sentTotal).as("toutes les lignes finissent poussées après reprise").isEqualTo(total);
        // Unicité : aucun doublon (les uuid sont déterministes par id).
        assertThat(pushedUuids(buffer)).as("ni doublon ni perte au redémarrage").hasSize(total);
        assertThat(state.getLastId(ENTITY)).contains((long) total);
    }
}
