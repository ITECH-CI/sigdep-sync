package ci.itechciv.sigdep.sync.scheduler;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.sync.buffer.OutboxFlusher;
import ci.itechciv.sigdep.sync.buffer.OutboxFlusher.FlushResult;
import ci.itechciv.sigdep.sync.buffer.OutboxFlusher.PagePushResult;
import ci.itechciv.sigdep.sync.buffer.OutboxRepository;
import ci.itechciv.sigdep.sync.buffer.OutboxRepository.DeadLetterStats;
import ci.itechciv.sigdep.sync.extractor.CanonicalRecord;
import ci.itechciv.sigdep.sync.extractor.DataExtractor;
import ci.itechciv.sigdep.sync.extractor.SyncCursor;
import ci.itechciv.sigdep.sync.state.SyncStateRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Pipeline extraction / push DÉCOUPLÉ pour UN entity_type.
 *
 * Historiquement le cycle était strictement séquentiel par page :
 * extraire → enqueue → push → attendre l'ACK → extraire la page suivante. Le
 * round-trip réseau dominait le temps total. Ici l'extraction du lot N+1 ne
 * bloque plus sur l'ACK du lot N : un producteur extrait/enqueue en avance,
 * un consommateur pousse et n'avance {@code sync_state} que sur ACK.
 *
 * <h3>Invariants garantis</h3>
 * <ul>
 *   <li><b>Profondeur bornée</b> : au plus {@code depth} lots « en vol »
 *       (extraits + enqueue, pas encore acquittés). Un {@link Semaphore} de
 *       {@code depth} permits est acquis par le producteur AVANT d'extraire la
 *       page suivante, relâché par le consommateur après le push. Quand le hub
 *       est lent/injoignable, le producteur bloque sur {@code acquire()} : pas
 *       de boucle d'extraction, pas de saturation disque.</li>
 *   <li><b>Watermark conditionné à l'ACK</b> : c'est {@link OutboxFlusher} qui
 *       avance {@code sync_state}, et seulement sur une page 100 % acceptée.
 *       Le producteur, lui, avance un curseur EN MÉMOIRE (optimiste) pour ne
 *       pas ré-extraire la même page ; ce curseur n'est jamais persisté. Au
 *       redémarrage on repart donc de {@code sync_state} (source de vérité) et
 *       les lots en vol non acquittés sont ré-extraits — sans dommage, l'
 *       ingestion côté hub étant idempotente (upsert).</li>
 *   <li><b>Ordre de push</b> préservé au sein de l'entity_type : file FIFO +
 *       un unique consommateur.</li>
 * </ul>
 *
 * Un pipeline vit le temps d'un cycle de synchronisation de son entité, puis
 * s'arrête (source épuisée, échec réseau, ou plafond de pages).
 */
public class EntityPipeline {

    private static final Logger log = LoggerFactory.getLogger(EntityPipeline.class);

    /** Sentinelle de fin de production placée dans la file. */
    private static final PageRef END = new PageRef(true);

    private final DataExtractor extractor;
    private final EntityType entityType;
    private final int batchSize;
    private final int depth;
    private final int maxPagesPerCycle;

    private final SyncStateRepository syncState;
    private final OutboxFlusher flusher;
    private final OutboxRepository outbox;
    private final OutboxEnqueuerPort enqueuer;
    private final LocalDateTime watermarkInitial;

    // Curseur keyset EN MÉMOIRE (optimiste) : couple (watermark, lastId) du
    // dernier enregistrement extrait, pour lancer la page suivante sans attendre
    // l'ACK et sans sauter/rejouer une ligne au sein d'un groupe de timestamps
    // identiques. JAMAIS persisté (sync_state avance à l'ACK via le flusher).
    private SyncCursor memoryCursor;

    // Bornage de la profondeur du pipeline.
    private final Semaphore inFlight;
    private final BlockingQueue<PageRef> queue;

    // Résultat agrégé du consommateur (ce que renvoyait FlushResult).
    private volatile int totalAccepted;
    private volatile int totalRejected;
    private volatile int totalBatches;
    private volatile boolean networkStopped;

    /** Port minimal vers l'enqueue (facilite les tests). */
    public interface OutboxEnqueuerPort {
        int enqueue(List<CanonicalRecord> records);
    }

    /** Référence légère d'une page produite ; {@code end} marque la fin. */
    private record PageRef(boolean end) {}

    public EntityPipeline(DataExtractor extractor,
                          int batchSize,
                          int depth,
                          int maxPagesPerCycle,
                          LocalDateTime watermarkInitial,
                          SyncStateRepository syncState,
                          OutboxFlusher flusher,
                          OutboxRepository outbox,
                          OutboxEnqueuerPort enqueuer) {
        this.extractor = extractor;
        this.entityType = extractor.getEntityType();
        this.batchSize = batchSize;
        this.depth = Math.max(1, depth);
        this.maxPagesPerCycle = maxPagesPerCycle;
        this.watermarkInitial = watermarkInitial;
        this.syncState = syncState;
        this.flusher = flusher;
        this.outbox = outbox;
        this.enqueuer = enqueuer;
        this.inFlight = new Semaphore(this.depth);
        // File bornée à depth : elle ne peut de toute façon pas contenir plus
        // que le sémaphore ne l'autorise.
        this.queue = new ArrayBlockingQueue<>(this.depth + 1);
    }

    /**
     * Exécute un cycle complet pour cette entité : producteur (extraction) et
     * consommateur (push) tournent en parallèle, bornés à {@code depth} lots en
     * vol. Retourne les stats agrégées (compatibles avec l'ancien FlushResult).
     */
    public FlushResult runCycle() throws InterruptedException {
        // Point de départ = sync_state (source de vérité au démarrage / reprise) :
        // watermark ET last_id, pour reprendre exactement où l'ACK précédent
        // s'était arrêté à l'intérieur d'un groupe de timestamps identiques.
        LocalDateTime startWatermark = syncState.getWatermark(entityType).orElse(watermarkInitial);
        long startLastId = syncState.getLastId(entityType).orElse(0L);
        memoryCursor = new SyncCursor(startWatermark, startLastId);

        Thread consumer = new Thread(this::consumeLoop, "pipeline-" + entityType);
        consumer.start();
        try {
            produceLoop();
        } finally {
            // Signale la fin au consommateur et attend qu'il draine.
            queue.put(END);
            consumer.join();
        }
        return new FlushResult(
                totalAccepted + totalRejected, totalAccepted, totalRejected,
                totalBatches, networkStopped);
    }

    /**
     * Producteur : extrait des pages depuis le curseur mémoire, les enqueue, et
     * signale le consommateur. Acquiert un permit AVANT chaque extraction —
     * bloque donc quand {@code depth} lots sont encore en vol (hub lent /
     * injoignable), ce qui met le pipeline en pause sans sur-extraire.
     */
    private void produceLoop() throws InterruptedException {
        int pages = 0;
        while (true) {
            // Bloque si depth lots sont déjà en vol (non acquittés).
            inFlight.acquire();
            if (networkStopped) {
                // Le consommateur a rencontré une panne réseau : inutile de
                // continuer à extraire, on relâche et on sort.
                inFlight.release();
                break;
            }

            List<CanonicalRecord> records = extractor.extract(memoryCursor, batchSize);
            if (records.isEmpty()) {
                // Source épuisée : on relâche le permit acquis et on sort ;
                // la sentinelle END déclenchée par runCycle terminera le
                // consommateur (qui aura draîné les pages précédentes).
                inFlight.release();
                break;
            }

            enqueuer.enqueue(records);
            // Avance le curseur mémoire sur (max watermark, max sourceId) de la
            // page pour que la suivante ne recouvre NI ne saute une ligne au sein
            // d'un groupe de timestamps identiques (optimiste, non persisté).
            memoryCursor = advanceCursor(memoryCursor, records);
            queue.put(new PageRef(false)); // le permit sera relâché par le consumer

            boolean lastPage = records.size() < batchSize;
            if (lastPage) break;
            if (++pages >= maxPagesPerCycle) {
                log.warn("{} : plafond de {} pages atteint sur ce cycle — le reste partira au prochain cycle",
                        entityType, maxPagesPerCycle);
                break;
            }
        }
    }

    /**
     * Consommateur : draine la file, pousse chaque « unité » via le flusher (qui
     * n'avance sync_state que sur ACK), et relâche un permit par page traitée.
     * S'arrête à la sentinelle END, ou plus tôt si le réseau lâche.
     */
    private void consumeLoop() {
        try {
            while (true) {
                PageRef ref = queue.take();
                if (ref.end()) {
                    break;
                }
                try {
                    // pushOnePage() pousse EXACTEMENT la page que le producteur
                    // vient de signaler (mapping strict 1 signal = 1 page = 1
                    // permit) et avance sync_state UNIQUEMENT sur une page 100 %
                    // acceptée. On agrège ses stats.
                    pushAndAggregate();
                } finally {
                    // Un permit relâché par page consommée : rouvre exactement
                    // une place d'extraction (profondeur bornée respectée).
                    inFlight.release();
                }
                logDeadLetters();
            }

            // Drainage du RELIQUAT : l'outbox peut contenir plus de pages
            // PENDING/REJECTED que ce que le producteur a signalé ce cycle
            // (lignes laissées par un cycle précédent interrompu, ou rejets
            // rejouables). On vide jusqu'à outbox vide ou panne réseau, sans
            // toucher au sémaphore (le producteur a terminé). Garantit qu'un
            // cycle avec hub disponible ne laisse pas de reliquat orphelin.
            while (!networkStopped) {
                PagePushResult r = pushAndAggregate();
                if (r.empty() || r.networkFailure()) {
                    break;
                }
                logDeadLetters();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Pousse une page via le flusher, agrège les stats et positionne
     * {@code networkStopped} en cas de panne réseau. sync_state n'avance que
     * sur une page 100 % acceptée (logique du flusher, inchangée).
     */
    private PagePushResult pushAndAggregate() {
        PagePushResult r = flusher.pushOnePage(entityType);
        totalAccepted += r.accepted();
        totalRejected += r.rejected();
        if (!r.empty()) {
            totalBatches++;
        }
        if (r.networkFailure()) {
            networkStopped = true;
        }
        return r;
    }

    private void logDeadLetters() {
        DeadLetterStats dlq = outbox.deadLetterStats(entityType);
        if (dlq.stuck() > 0) {
            log.warn("{} {} record(s) parked in DEAD_LETTER — manual review needed on the hub Rejets page",
                    dlq.stuck(), entityType);
        }
    }

    /**
     * Avance le curseur keyset sur la borne max {@code (watermark, sourceId)}
     * de la page. La page étant extraite {@code ORDER BY date, id}, le plus
     * grand couple est celui du DERNIER enregistrement — mais on balaie par
     * sécurité (indépendance vis-à-vis de l'ordre effectif renvoyé).
     *
     * Si les enregistrements n'ont pas de sourceId (entité non encore migrée en
     * keyset, sourceId == null), on n'avance que le watermark et on garde le
     * lastId du curseur courant — dégénère proprement vers l'ancien comportement.
     */
    private static SyncCursor advanceCursor(SyncCursor current, List<CanonicalRecord> records) {
        LocalDateTime maxWatermark = current.watermark();
        long maxId = current.lastId();
        boolean anySourceId = false;
        for (CanonicalRecord r : records) {
            LocalDateTime w = r.watermark();
            long id = r.sourceId() == null ? 0L : r.sourceId();
            if (r.sourceId() != null) {
                anySourceId = true;
            }
            // Ordre lexicographique (date, id).
            if (w.isAfter(maxWatermark) || (w.isEqual(maxWatermark) && id > maxId)) {
                maxWatermark = w;
                maxId = id;
            }
        }
        return anySourceId ? new SyncCursor(maxWatermark, maxId)
                           : new SyncCursor(maxWatermark, current.lastId());
    }
}
