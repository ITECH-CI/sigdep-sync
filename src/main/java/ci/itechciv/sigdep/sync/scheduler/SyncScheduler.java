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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
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

    private void runOne(DataExtractor x) {
        // 1. Watermark
        LocalDateTime since = syncState.getWatermark(x.getEntityType())
                .orElse(props.watermarkInitial());

        // 2. Extract a page from the source DB
        List<CanonicalRecord> records = x.extract(since, props.batchSize());

        // 3. Enqueue into outbox. Note: even with an empty extract we still
        //    fall through to the flush step, because the outbox may still
        //    hold rejected rows from previous cycles that are now retryable.
        if (!records.isEmpty()) {
            int enqueued = enqueuer.enqueue(records);
            log.info("Enqueued {} {} record(s) (since {})", enqueued, x.getEntityType(), since);
        } else {
            log.debug("Nothing new for {} since {}", x.getEntityType(), since);
        }

        // 4. Drain the outbox for this entity. The flusher pushes PENDING +
        //    retryable REJECTED rows; the watermark only moves when the
        //    page was fully accepted. A REJECTED row sticks in the outbox
        //    until either it gets accepted on a later cycle or attempts
        //    reaches maxRejectAttempts (status = DEAD_LETTER, manual action).
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
    }
}
