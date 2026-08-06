package ci.itechciv.sigdep.sync.buffer;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.contracts.SyncBatchRequest;
import ci.itechciv.sigdep.contracts.SyncBatchResponse;
import ci.itechciv.sigdep.contracts.SyncBatchResponse.RecordError;
import ci.itechciv.sigdep.sync.buffer.OutboxRepository.OutboxEntry;
import ci.itechciv.sigdep.sync.buffer.OutboxRepository.RejectedId;
import ci.itechciv.sigdep.sync.config.SyncProperties;
import ci.itechciv.sigdep.sync.pusher.CentralApiClient;
import ci.itechciv.sigdep.sync.pusher.RetryableTransportException;
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

    /**
     * Code de rejet signalant une DÉPENDANCE non satisfaite (le patient parent
     * n'est pas encore ingéré) — transitoire, dû à l'ordonnancement, par
     * opposition à un rejet de VALIDATION (données invalides, permanent). Émis
     * par les writers du hub (VisitWriter, InitiationWriter, …).
     */
    private static final String DEPENDENCY_REJECT_CODE = "UNKNOWN_PATIENT";

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
            PagePushResult r = pushOnePage(entityType);
            if (r.empty()) break;
            total += r.accepted() + r.rejected();
            accepted += r.accepted();
            rejected += r.rejected();
            batches++;
            if (r.networkFailure()) {
                return new FlushResult(total, accepted, rejected, batches, true);
            }
        }
        return new FlushResult(total, accepted, rejected, batches, false);
    }

    /**
     * Pousse EXACTEMENT une page de l'outbox pour cette entité (la plus
     * prioritaire, cf. {@link OutboxRepository#findRetryable}) et renvoie son
     * résultat. Brique unitaire du pipeline extraction/push : un appel = un
     * round-trip réseau = une unité de profondeur.
     *
     * <ul>
     *   <li>{@code empty=true} : plus rien à pousser (outbox vide pour cette
     *       entité) — pas de round-trip effectué.</li>
     *   <li>{@code networkFailure=true} : le hub était injoignable ; les lignes
     *       restent PENDING (rejouées plus tard). Le watermark n'a pas bougé.</li>
     *   <li>sinon : la page a été acquittée (accepted/rejected renseignés) et le
     *       watermark a avancé si et seulement si la page était 100 % acceptée
     *       (logique inchangée de {@link #pushPage}).</li>
     * </ul>
     */
    public PagePushResult pushOnePage(EntityType entityType) {
        List<OutboxEntry> page = outbox.findRetryable(
                entityType, props.batchSize(), props.maxRejectAttempts());
        if (page.isEmpty()) {
            return new PagePushResult(true, false, 0, 0, 0);
        }
        try {
            PushResult r = pushPage(entityType, page);
            return new PagePushResult(false, false, r.accepted, r.rejected, r.dependencyRejected);
        } catch (IOException e) {
            List<Long> ids = page.stream().map(OutboxEntry::id).toList();
            outbox.markFailed(ids, e.getMessage());
            log.warn("Push failed for {} rows ({}), they remain PENDING and will retry: {}",
                    page.size(), entityType, e.getMessage());
            return new PagePushResult(false, true, 0, 0, 0);
        }
    }

    /**
     * Résultat d'un push d'UNE page. {@code dependencyRejected} = sous-ensemble
     * de {@code rejected} dû à une dépendance non satisfaite (UNKNOWN_PATIENT),
     * transitoire — à distinguer des rejets de validation dans les compteurs.
     */
    public record PagePushResult(boolean empty, boolean networkFailure,
                                 int accepted, int rejected, int dependencyRejected) {}

    private PushResult pushPage(EntityType entityType, List<OutboxEntry> page) throws IOException {
        Class<?> dtoClass = PayloadTypes.classFor(entityType);
        List<Object> records = new ArrayList<>(page.size());
        LocalDateTime maxWatermark = page.get(0).watermark();
        Long maxSourceId = null; // plus grand source_id de la page (keyset)
        Map<UUID, Long> idBySourceUuid = new HashMap<>(page.size());
        for (OutboxEntry e : page) {
            records.add(mapper.readValue(e.payloadJson(), dtoClass));
            idBySourceUuid.put(e.sourceUuid(), e.id());
            if (e.watermark().isAfter(maxWatermark)) {
                maxWatermark = e.watermark();
            }
            if (e.sourceId() != null && (maxSourceId == null || e.sourceId() > maxSourceId)) {
                maxSourceId = e.sourceId();
            }
        }

        SyncBatchRequest<Object> batch = new SyncBatchRequest<>(
                props.siteCode(),
                UUID.randomUUID(),
                entityType,
                records);

        SyncBatchResponse resp = pushWithRetry(entityType, batch);
        log.info("ACK {} entityType={} accepted={} rejected={}",
                resp.batchId(), entityType, resp.accepted(), resp.rejected());

        // Per-row split : on sépare les rejets de DÉPENDANCE (UNKNOWN_PATIENT,
        // transitoires) des rejets de VALIDATION (UPSERT_FAILED…, permanents).
        // Le message stocké en last_error commence par le code, ce qui permet
        // au compteur pendingDependencyCount de reconnaître les dépendances.
        List<RecordError> errors = resp.errors() == null ? List.of() : resp.errors();
        Set<Long> rejectedIds = new HashSet<>(errors.size());
        List<RejectedId> dependencyRows = new ArrayList<>();
        List<RejectedId> validationRows = new ArrayList<>();
        for (RecordError err : errors) {
            Long rowId = idBySourceUuid.get(err.sourceUuid());
            if (rowId == null) continue; // hub returned an unknown uuid — skip
            rejectedIds.add(rowId);
            String message = (err.code() == null ? "?" : err.code())
                    + (err.message() == null ? "" : ": " + err.message());
            boolean isDependency = DEPENDENCY_REJECT_CODE.equals(err.code());
            if (isDependency) {
                dependencyRows.add(new RejectedId(rowId, message));

                // DIAGNOSTIC : un rejet de DÉPENDANCE signifie que le patient
                // parent n'a pas encore été ingéré — probable décalage
                // d'ordonnancement entre extracteurs (chacun avance sur son
                // propre watermark). On trace l'uuid du parent manquant, le type
                // d'entité rejetée, et l'état du watermark de PATIENTS.
                if (log.isDebugEnabled()) {
                    String parentUuid = err.message() == null ? "?" : err.message();
                    LocalDateTime patientsWatermark =
                            syncState.getWatermark(EntityType.PATIENTS).orElse(null);
                    log.debug("Rejet de DÉPENDANCE : entité={} sourceUuid={} — {} ;"
                                    + " watermark PATIENTS au moment du rejet = {}",
                            entityType, err.sourceUuid(), parentUuid, patientsWatermark);
                }
            } else {
                validationRows.add(new RejectedId(rowId, message));
            }
        }
        List<Long> acceptedIds = new ArrayList<>(page.size() - rejectedIds.size());
        for (OutboxEntry e : page) {
            if (!rejectedIds.contains(e.id())) acceptedIds.add(e.id());
        }

        outbox.markSent(acceptedIds);
        // Dépendances : PENDING sans consommer de tentative (un décalage
        // d'ordonnancement ne doit jamais produire un DEAD_LETTER définitif).
        outbox.markDependencyPending(dependencyRows);
        // Validation : consomme une tentative, DEAD_LETTER après maxAttempts.
        outbox.markValidationRejected(validationRows, props.maxRejectAttempts());

        boolean allAccepted = dependencyRows.isEmpty() && validationRows.isEmpty();

        // Watermark advances only if the whole page succeeded. Otherwise we
        // keep the previous watermark so an extractor restart doesn't skip
        // anything that the hub didn't accept.
        //
        // Keyset : quand la page porte un source_id (entités à watermark JOUR,
        // ex. screening), on avance aussi sync_state.last_id — mais UNIQUEMENT
        // sur une page 100 % acceptée, dans la même condition que le watermark.
        // Sur un rejet (dépendance OU validation), ni le watermark ni le last_id
        // ne bougent, donc rien n'est sauté au cycle suivant.
        String status = allAccepted ? "OK" : "PARTIAL";
        if (allAccepted) {
            if (maxSourceId != null) {
                syncState.updateKeyset(entityType, maxWatermark, maxSourceId, resp.accepted(), status);
            } else {
                syncState.updateWatermark(entityType, maxWatermark, resp.accepted(), status);
            }
        } else {
            syncState.updateWatermark(entityType,
                    syncState.getWatermark(entityType).orElse(props.watermarkInitial()),
                    resp.accepted(), status);
        }
        return new PushResult(resp.accepted(), resp.rejected(),
                dependencyRows.size(), validationRows.size());
    }

    /**
     * Pousse un batch au hub en ré-essayant sur un échec de TRANSPORT
     * ({@link RetryableTransportException} : réseau, 5xx, 429), avec backoff
     * exponentiel borné + jitter. Un rejet APPLICATIF ({@link IOException}
     * simple) est propagé immédiatement (retry inutile). Après épuisement des
     * tentatives, l'exception de transport est propagée → l'appelant met
     * l'entité en pause (comme avant), mais seulement en dernier recours.
     *
     * Corrige SYNC-12 : une coupure de transport transitoire (reset de flux
     * HTTP/2 après {@code keepalive_requests}) coûtait un cycle entier ; elle
     * est désormais absorbée par quelques retries de quelques secondes.
     */
    private SyncBatchResponse pushWithRetry(EntityType entityType, SyncBatchRequest<Object> batch)
            throws IOException {
        int maxRetries = props.http().maxRetries();
        long delay = props.http().retryInitialDelayMs();
        long maxDelay = props.http().retryMaxDelayMs();
        int attempt = 0;
        while (true) {
            try {
                return api.push(entityType, batch);
            } catch (RetryableTransportException e) {
                attempt++;
                if (attempt > maxRetries) {
                    log.warn("Push {} : échec de transport après {} tentative(s), mise en pause : {}",
                            entityType, maxRetries, e.getMessage());
                    throw e;
                }
                long sleep = jitter(Math.min(delay, maxDelay));
                log.info("Push {} : échec de transport (tentative {}/{}), retry dans {} ms : {}",
                        entityType, attempt, maxRetries, sleep, e.getMessage());
                sleepQuietly(sleep);
                delay = Math.min(delay * 2, maxDelay); // backoff exponentiel borné
            }
        }
    }

    /** Ajoute un jitter [50%, 100%] au délai pour désynchroniser les retries. */
    private static long jitter(long delayMs) {
        long half = delayMs / 2;
        return half + (long) (Math.random() * half);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private record PushResult(int accepted, int rejected,
                              int dependencyRejected, int validationRejected) {}

    public record FlushResult(
            int rowsAcked,       // accepted + rejected — i.e. everything the hub touched
            int rowsAccepted,
            int rowsRejected,
            int batches,
            boolean stoppedEarly
    ) {}
}
