package ci.itechciv.sigdep.sync.scheduler;

import ci.itechciv.sigdep.sync.buffer.OutboxEnqueuer;
import ci.itechciv.sigdep.sync.buffer.OutboxFlusher;
import ci.itechciv.sigdep.sync.buffer.OutboxRepository;
import ci.itechciv.sigdep.sync.buffer.OutboxRepository.DeadLetterStats;
import ci.itechciv.sigdep.sync.config.SyncProperties;
import ci.itechciv.sigdep.sync.extractor.CanonicalRecord;
import ci.itechciv.sigdep.sync.extractor.DataExtractor;
import ci.itechciv.sigdep.sync.state.SyncStateRepository;
import java.time.LocalDateTime;
import java.util.List;
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

    public SyncScheduler(List<DataExtractor> extractors,
                         OutboxEnqueuer enqueuer,
                         OutboxFlusher flusher,
                         OutboxRepository outbox,
                         SyncStateRepository syncState,
                         SyncProperties props) {
        this.extractors = extractors;
        this.enqueuer = enqueuer;
        this.flusher = flusher;
        this.outbox = outbox;
        this.syncState = syncState;
        this.props = props;
    }

    @Scheduled(fixedDelayString = "${sigdep.sync.interval-ms:900000}")
    public void runCycle() {
        log.info("Sync cycle started. {} extractor(s) registered.", extractors.size());
        for (DataExtractor x : extractors) {
            if (!x.isEnabled()) {
                log.debug("Extractor {} disabled, skipping", x.getEntityType());
                continue;
            }
            try {
                runOne(x);
            } catch (RuntimeException e) {
                log.error("Extractor {} failed, continuing with next: {}",
                        x.getEntityType(), e.getMessage(), e);
            }
        }
        log.info("Sync cycle completed.");
    }

    /**
     * Nombre maximum de pages drainées pour une même entité au cours d'un seul
     * cycle. Garde-fou anti-boucle-infinie : à batchSize=500, 10 000 pages =
     * 5 M d'enregistrements, largement au-delà d'un backfill réel. Si on
     * atteint ce plafond, on s'arrête proprement et le reste partira au cycle
     * suivant.
     */
    private static final int MAX_PAGES_PER_CYCLE = 10_000;

    private void runOne(DataExtractor x) {
        int batchSize = props.batchSize();
        int pages = 0;

        // Pagination intra-cycle : on draine l'entité jusqu'à épuisement de la
        // source. Indispensable pour le backfill initial — sinon une seule
        // page (batchSize) part par cycle, et les entités dépendantes
        // (visites, labs…) référencent des patients pas encore montés
        // (UNKNOWN_PATIENT → DEAD_LETTER). Comme les extracteurs sont ordonnés
        // (@Order), PATIENTS est intégralement monté avant ses dépendances.
        while (true) {
            // 1. Watermark — relu à chaque itération : le flush précédent l'a
            //    avancé jusqu'au dernier enregistrement accepté.
            LocalDateTime since = syncState.getWatermark(x.getEntityType())
                    .orElse(props.watermarkInitial());

            // 2. Extraire une page depuis la base source.
            List<CanonicalRecord> records = x.extract(since, batchSize);

            // 3. Mettre en file (outbox). Même avec une extraction vide on
            //    passe au flush : l'outbox peut contenir des REJECTED d'un
            //    cycle précédent désormais rejouables.
            if (!records.isEmpty()) {
                int enqueued = enqueuer.enqueue(records);
                log.info("Enqueued {} {} record(s) (since {})", enqueued, x.getEntityType(), since);
            } else {
                log.debug("Nothing new for {} since {}", x.getEntityType(), since);
            }

            // 4. Vider l'outbox pour cette entité. Le watermark n'avance que
            //    sur un batch entièrement accepté ; un REJECTED reste en
            //    outbox jusqu'à acceptation ultérieure ou DEAD_LETTER après
            //    maxRejectAttempts.
            var result = flusher.flush(x.getEntityType());
            DeadLetterStats dlq = outbox.deadLetterStats(x.getEntityType());
            log.info("Flushed {} batch(es) for {} — {} accepted, {} rejected; outbox holds {} retryable + {} stuck{}",
                    result.batches(), x.getEntityType(),
                    result.rowsAccepted(), result.rowsRejected(),
                    dlq.retryable(), dlq.stuck(),
                    result.stoppedEarly() ? " — STOPPED early after a push failure" : "");

            if (dlq.stuck() > 0) {
                log.warn("{} {} record(s) parked in DEAD_LETTER — manual review needed on the hub Rejets page",
                        dlq.stuck(), x.getEntityType());
            }

            // Conditions d'arrêt :
            //  - page non pleine → la source est épuisée ;
            //  - échec réseau (stoppedEarly) → inutile d'insister ce cycle ;
            //  - aucune acceptation sur cette itération → le watermark n'a pas
            //    avancé (flush 100 % rejeté). Re-extraire donnerait la même page
            //    depuis le même watermark : boucle infinie. On s'arrête ; les
            //    rejets restent en outbox et seront rejoués au PROCHAIN cycle,
            //    une fois que les entités amont (PATIENTS…) auront tourné. Sans
            //    ce garde-fou, une entité dont tous les enregistrements sont
            //    rejetés (dépendance pas encore montée) monopolise le thread du
            //    scheduler et les entités suivantes ne tournent jamais.
            //  - plafond de sécurité atteint.
            if (records.size() < batchSize || result.stoppedEarly()) {
                break;
            }
            if (result.rowsAccepted() == 0) {
                log.info("{} : aucune acceptation sur cette page (watermark inchangé) — "
                        + "arrêt du drainage ; rejets rejoués au prochain cycle",
                        x.getEntityType());
                break;
            }
            if (++pages >= MAX_PAGES_PER_CYCLE) {
                log.warn("{} : plafond de {} pages atteint sur ce cycle — le reste partira au prochain cycle",
                        x.getEntityType(), MAX_PAGES_PER_CYCLE);
                break;
            }
        }
    }
}
