package ci.itechciv.sigdep.sync.scheduler;

import ci.itechciv.sigdep.sync.buffer.OutboxEnqueuer;
import ci.itechciv.sigdep.sync.buffer.OutboxFlusher;
import ci.itechciv.sigdep.sync.buffer.OutboxRepository;
import ci.itechciv.sigdep.sync.buffer.OutboxRepository.DeadLetterStats;
import ci.itechciv.sigdep.sync.config.SyncProperties;
import ci.itechciv.sigdep.sync.extractor.DataExtractor;
import ci.itechciv.sigdep.sync.state.SyncStateRepository;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@DependsOn("bufferSchemaInitializer")
public class SyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

    private final List<DataExtractor> extractors;
    private final OutboxEnqueuer enqueuer;
    private final OutboxFlusher flusher;
    private final OutboxRepository outbox;
    private final SyncStateRepository syncState;
    private final SyncProperties props;
    private final ExtractorFailureTracker failureTracker;

    public SyncScheduler(List<DataExtractor> extractors,
                         OutboxEnqueuer enqueuer,
                         OutboxFlusher flusher,
                         OutboxRepository outbox,
                         SyncStateRepository syncState,
                         SyncProperties props,
                         ExtractorFailureTracker failureTracker) {
        this.extractors = extractors;
        this.enqueuer = enqueuer;
        this.flusher = flusher;
        this.outbox = outbox;
        this.syncState = syncState;
        this.props = props;
        this.failureTracker = failureTracker;
    }

    @Scheduled(fixedDelayString = "${sigdep.sync.interval-ms:900000}")
    public void runCycle() {
        log.info("Sync cycle started. {} extractor(s) registered.", extractors.size());

        // On mémorise, pour ce cycle, les extracteurs actifs, ceux qui ont
        // réussi, et l'exception de ceux qui ont échoué — sans loguer tout de
        // suite : la décision de log (par extracteur vs bloc global dédupliqué)
        // se prend APRÈS la boucle, une fois qu'on sait si tout le monde a
        // échoué sur la même cause.
        List<DataExtractor> active = new ArrayList<>();
        List<DataExtractor> succeeded = new ArrayList<>();
        Map<DataExtractor, RuntimeException> failures = new LinkedHashMap<>();

        for (DataExtractor x : extractors) {
            if (!x.isEnabled()) {
                log.debug("Extractor {} disabled, skipping", x.getEntityType());
                continue;
            }
            active.add(x);
            try {
                runOne(x);
                succeeded.add(x);
            } catch (RuntimeException e) {
                failures.put(x, e);
            }
        }

        reportCycle(active, succeeded, failures);
        log.info("Sync cycle completed.");
    }

    /**
     * Décide comment loguer les échecs du cycle, via {@link ExtractorFailureTracker} :
     * <ul>
     *   <li>si TOUS les extracteurs actifs ont échoué sur la MÊME cause, un seul
     *       bloc global (dédup) ;</li>
     *   <li>sinon, un log par extracteur en échec + un log de rétablissement
     *       pour ceux qui viennent de repasser au vert.</li>
     * </ul>
     */
    private void reportCycle(List<DataExtractor> active,
                             List<DataExtractor> succeeded,
                             Map<DataExtractor, RuntimeException> failures) {
        boolean allFailedSameCause = !active.isEmpty()
                && failures.size() == active.size()
                && sameCause(failures.values());

        if (allFailedSameCause) {
            // Un seul bloc pour la panne globale ; pas de spam par entité.
            RuntimeException any = failures.values().iterator().next();
            failureTracker.recordGlobalFailure(failures.size(), any);
            return;
        }

        // Cas normal : on sort d'un éventuel échec global, puis on traite
        // chaque extracteur individuellement.
        failureTracker.recordGlobalRecovery();
        for (DataExtractor x : succeeded) {
            failureTracker.recordSuccess(x.getEntityType());
        }
        for (Map.Entry<DataExtractor, RuntimeException> e : failures.entrySet()) {
            failureTracker.recordFailure(e.getKey().getEntityType(), e.getValue());
        }
    }

    /** Vrai si toutes les exceptions partagent la même signature de cause. */
    private static boolean sameCause(Collection<RuntimeException> errors) {
        String first = null;
        for (RuntimeException e : errors) {
            String sig = ExtractorFailureTracker.signatureOf(e);
            if (first == null) {
                first = sig;
            } else if (!first.equals(sig)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Nombre maximum de pages drainées pour une même entité au cours d'un seul
     * cycle. Garde-fou anti-boucle-infinie : à batchSize=500, 10 000 pages =
     * 5 M d'enregistrements, largement au-delà d'un backfill réel. Si on
     * atteint ce plafond, on s'arrête proprement et le reste partira au cycle
     * suivant.
     */
    private static final int MAX_PAGES_PER_CYCLE = 10_000;

    /**
     * Exécute le cycle d'une entité via un {@link EntityPipeline} : extraction
     * et push DÉCOUPLÉS, bornés à {@code pipelineDepth} lots en vol. L'ordre de
     * push est préservé au sein de l'entity_type ; le watermark persistant
     * (sync_state) n'avance que sur ACK ; si le hub est injoignable, le pipeline
     * se met en pause une fois la profondeur atteinte (pas de sur-extraction).
     *
     * Les entités restent traitées séquentiellement ENTRE elles (l'ordre @Order
     * PATIENTS → dépendances est conservé : un pipeline se termine avant que le
     * suivant démarre), ce qui garde la cohérence FK du backfill initial.
     */
    private void runOne(DataExtractor x) {
        EntityPipeline pipeline = new EntityPipeline(
                x,
                props.batchSize(),
                props.pipelineDepth(),
                MAX_PAGES_PER_CYCLE,
                props.watermarkInitial(),
                syncState,
                flusher,
                outbox,
                enqueuer::enqueue);
        try {
            var result = pipeline.runCycle();
            DeadLetterStats dlq = outbox.deadLetterStats(x.getEntityType());
            int pendingDeps = outbox.pendingDependencyCount(x.getEntityType());
            log.info("Pipeline {} — {} accepted, {} rejected across {} batch(es); "
                            + "outbox holds {} retryable ({} en attente de parent) + {} stuck{}",
                    x.getEntityType(),
                    result.rowsAccepted(), result.rowsRejected(), result.batches(),
                    dlq.retryable(), pendingDeps, dlq.stuck(),
                    result.stoppedEarly() ? " — PAUSED after a push failure" : "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Pipeline " + x.getEntityType() + " interrupted", e);
        }
    }
}
