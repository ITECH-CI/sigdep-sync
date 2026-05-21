package ci.itechciv.sigdep.sync.buffer;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.contracts.SyncBatchRequest;
import ci.itechciv.sigdep.contracts.SyncBatchResponse;
import ci.itechciv.sigdep.contracts.SyncBatchResponse.RecordError;
import ci.itechciv.sigdep.sync.buffer.OutboxRepository.OutboxEntry;
import ci.itechciv.sigdep.sync.buffer.OutboxRepository.RejectedId;
import ci.itechciv.sigdep.sync.config.SyncProperties;
import ci.itechciv.sigdep.sync.pusher.CentralApiClient;
import ci.itechciv.sigdep.sync.state.SyncStateRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Drains the SQLite outbox in pages and pushes each page as a SyncBatchRequest
 * to the central API.
 *
 * Per-row reconciliation: when the hub returns a partial ACK (accepted=N,
 * rejected=M with a list of {@link RecordError}), the accepted rows go to
 * status='SENT' and the rejected ones go to status='REJECTED'. Rejected
 * rows are re-tried on the next cycle (FK-coherent retries first, see
 * {@link OutboxRepository#findRetryable}); once a row has been retried
 * {@code maxRejectAttempts} times it sticks in status='DEAD_LETTER' and
 * waits for manual intervention via the console.
 *
 * Watermark advancement: only advanced when no reject is left in this
 * batch. If anything rejected, we keep the watermark where it was so the
 * extractor doesn't move past the rejected window — those rows will get
 * another try via the outbox.
 */
@Component
public class OutboxFlusher {

    private static final Logger log = LoggerFactory.getLogger(OutboxFlusher.class);

    private final OutboxRepository outbox;
    private final CentralApiClient api;
    private final ObjectMapper mapper;
    private final SyncStateRepository syncState;
    private final SyncProperties props;

    public OutboxFlusher(OutboxRepository outbox,
                         CentralApiClient api,
                         ObjectMapper mapper,
                         SyncStateRepository syncState,
                         SyncProperties props) {
        this.outbox = outbox;
        this.api = api;
        this.mapper = mapper;
        this.syncState = syncState;
        this.props = props;
    }

    /**
     * Drains all retryable rows for the given entity (PENDING + REJECTED
     * under the cap), pushing them in pages of {@link SyncProperties#batchSize()}.
     * Returns aggregate stats for the cycle.
     */
    public FlushResult flush(EntityType entityType) {
        int total = 0;
        int accepted = 0;
        int rejected = 0;
        int batches = 0;
        while (true) {
            List<OutboxEntry> page = outbox.findRetryable(
                    entityType, props.batchSize(), props.maxRejectAttempts());
            if (page.isEmpty()) break;

            try {
                PushResult r = pushPage(entityType, page);
                total += page.size();
                accepted += r.accepted;
                rejected += r.rejected;
                batches++;
            } catch (IOException e) {
                List<Long> ids = page.stream().map(OutboxEntry::id).toList();
                outbox.markFailed(ids, e.getMessage());
                log.warn("Push failed for {} rows ({}), they remain PENDING and will retry: {}",
                        page.size(), entityType, e.getMessage());
                return new FlushResult(total, accepted, rejected, batches, true);
            }
        }
        return new FlushResult(total, accepted, rejected, batches, false);
    }

    private PushResult pushPage(EntityType entityType, List<OutboxEntry> page) throws IOException {
        Class<?> dtoClass = PayloadTypes.classFor(entityType);
        List<Object> records = new ArrayList<>(page.size());
        LocalDateTime maxWatermark = page.get(0).watermark();
        Map<UUID, Long> idBySourceUuid = new HashMap<>(page.size());
        for (OutboxEntry e : page) {
            records.add(mapper.readValue(e.payloadJson(), dtoClass));
            idBySourceUuid.put(e.sourceUuid(), e.id());
            if (e.watermark().isAfter(maxWatermark)) {
                maxWatermark = e.watermark();
            }
        }

        SyncBatchRequest<Object> batch = new SyncBatchRequest<>(
                props.siteCode(),
                UUID.randomUUID(),
                entityType,
                records);

        SyncBatchResponse resp = api.push(entityType, batch);
        log.info("ACK {} entityType={} accepted={} rejected={}",
                resp.batchId(), entityType, resp.accepted(), resp.rejected());

        // Build the per-row split: every reject the hub returned (matched by
        // sourceUuid) gets markRejected; everything else in the batch is SENT.
        List<RecordError> errors = resp.errors() == null ? List.of() : resp.errors();
        Set<Long> rejectedIds = new HashSet<>(errors.size());
        List<RejectedId> rejectedRows = new ArrayList<>(errors.size());
        for (RecordError err : errors) {
            Long rowId = idBySourceUuid.get(err.sourceUuid());
            if (rowId == null) continue; // hub returned an unknown uuid — skip
            rejectedIds.add(rowId);
            String message = (err.code() == null ? "?" : err.code())
                    + (err.message() == null ? "" : ": " + err.message());
            rejectedRows.add(new RejectedId(rowId, message));
        }
        List<Long> acceptedIds = new ArrayList<>(page.size() - rejectedIds.size());
        for (OutboxEntry e : page) {
            if (!rejectedIds.contains(e.id())) acceptedIds.add(e.id());
        }

        outbox.markSent(acceptedIds);
        outbox.markRejected(rejectedRows, props.maxRejectAttempts());

        // Watermark advances only if the whole page succeeded. Otherwise we
        // keep the previous watermark so an extractor restart doesn't skip
        // anything that the hub didn't accept.
        String status = rejectedRows.isEmpty() ? "OK" : "PARTIAL";
        if (rejectedRows.isEmpty()) {
            syncState.updateWatermark(entityType, maxWatermark, resp.accepted(), status);
        } else {
            syncState.updateWatermark(entityType,
                    syncState.getWatermark(entityType).orElse(props.watermarkInitial()),
                    resp.accepted(), status);
        }
        return new PushResult(resp.accepted(), resp.rejected());
    }

    private record PushResult(int accepted, int rejected) {}

    public record FlushResult(
            int rowsAcked,       // accepted + rejected — i.e. everything the hub touched
            int rowsAccepted,
            int rowsRejected,
            int batches,
            boolean stoppedEarly
    ) {}
}
