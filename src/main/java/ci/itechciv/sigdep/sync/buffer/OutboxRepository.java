package ci.itechciv.sigdep.sync.buffer;

import ci.itechciv.sigdep.contracts.EntityType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OutboxRepository {

    private final JdbcTemplate jdbc;

    public OutboxRepository(@Qualifier("bufferJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Enqueue a record into the outbox. If a row with the same
     * (entity_type, source_uuid) is still PENDING or REJECTED — i.e. the hub
     * hasn't yet accepted it — we replace its payload + watermark in place
     * instead of inserting a duplicate. Avoids two-way amplification when
     * the extractor's watermark is held back by an unresolved reject.
     */
    public void enqueue(EntityType entityType, UUID sourceUuid, LocalDateTime watermark,
                        Long sourceId, String payloadJson) {
        int updated = jdbc.update(
                """
                UPDATE outbox
                   SET payload_json = ?, watermark = ?, source_id = ?
                 WHERE entity_type  = ?
                   AND source_uuid  = ?
                   AND status IN ('PENDING', 'REJECTED')
                """,
                payloadJson,
                java.sql.Timestamp.valueOf(watermark),
                sourceId,
                entityType.name(),
                sourceUuid.toString());
        if (updated == 0) {
            jdbc.update(
                    """
                    INSERT INTO outbox (entity_type, source_uuid, source_id, watermark, payload_json, status)
                    VALUES (?, ?, ?, ?, ?, 'PENDING')
                    """,
                    entityType.name(),
                    sourceUuid.toString(),
                    sourceId,
                    java.sql.Timestamp.valueOf(watermark),
                    payloadJson);
        }
    }

    /**
     * Drainage queue: rows the hub hasn't accepted yet. Includes:
     *  - PENDING rows (new extracts)
     *  - REJECTED rows still under the max-attempts cap (retried each cycle,
     *    giving UNKNOWN_PATIENT a chance once the patient is finally ingested)
     *
     * REJECTED rows come first (ORDER BY status='REJECTED' DESC, id) so the
     * retries get processed before fresh extracts of the same entity, keeping
     * batches FK-coherent.
     *
     * DEAD_LETTER rows are NOT included — they require manual action.
     */
    public List<OutboxEntry> findRetryable(EntityType entityType, int limit, int maxAttempts) {
        return jdbc.query(
                """
                SELECT id, entity_type, source_uuid, source_id, watermark, payload_json,
                       status, attempts, last_error
                FROM outbox
                WHERE entity_type = ?
                  AND ( status = 'PENDING'
                     OR (status = 'REJECTED' AND attempts < ?) )
                ORDER BY CASE WHEN status = 'REJECTED' THEN 0 ELSE 1 END, id
                LIMIT ?
                """,
                (rs, i) -> new OutboxEntry(
                        rs.getLong("id"),
                        EntityType.valueOf(rs.getString("entity_type")),
                        UUID.fromString(rs.getString("source_uuid")),
                        rs.getTimestamp("watermark").toLocalDateTime(),
                        rs.getString("payload_json"),
                        rs.getInt("attempts"),
                        readNullableLong(rs, "source_id")),
                entityType.name(),
                maxAttempts,
                limit);
    }

    /**
     * Backwards-compatible alias — only returns PENDING rows. Kept for any
     * caller that does not yet know about REJECTED retries.
     */
    public List<OutboxEntry> findPending(EntityType entityType, int limit) {
        return jdbc.query(
                """
                SELECT id, entity_type, source_uuid, watermark, payload_json, attempts
                FROM outbox
                WHERE status = 'PENDING' AND entity_type = ?
                ORDER BY id
                LIMIT ?
                """,
                (rs, i) -> new OutboxEntry(
                        rs.getLong("id"),
                        EntityType.valueOf(rs.getString("entity_type")),
                        UUID.fromString(rs.getString("source_uuid")),
                        rs.getTimestamp("watermark").toLocalDateTime(),
                        rs.getString("payload_json"),
                        rs.getInt("attempts"),
                        null),
                entityType.name(),
                limit);
    }

    /** Lit une colonne INTEGER SQLite nullable en Long (null si SQL NULL). */
    private static Long readNullableLong(java.sql.ResultSet rs, String col) throws java.sql.SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    public void markSent(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        jdbc.update(
                "UPDATE outbox SET status='SENT', sent_at=datetime('now') WHERE id IN (" + placeholders + ")",
                ids.toArray());
    }

    public void markFailed(List<Long> ids, String error) {
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        Object[] params = new Object[ids.size() + 1];
        params[0] = error;
        for (int i = 0; i < ids.size(); i++) {
            params[i + 1] = ids.get(i);
        }
        jdbc.update(
                "UPDATE outbox SET attempts = attempts + 1, last_error = ?, status='PENDING' "
                        + "WHERE id IN (" + placeholders + ")",
                params);
    }

    /**
     * Mark rows the hub explicitly rejected (with sourceUuid + code/message).
     * They land in status='REJECTED' with the error message; on the next
     * cycle they're picked back up by {@link #findRetryable}. After
     * {@code maxAttempts} the row is moved to status='DEAD_LETTER' and won't
     * be retried until an operator intervenes.
     */
    public void markRejected(List<RejectedId> rejects, int maxAttempts) {
        if (rejects.isEmpty()) return;
        for (RejectedId r : rejects) {
            jdbc.update(
                    """
                    UPDATE outbox
                       SET attempts    = attempts + 1,
                           last_error  = ?,
                           status      = CASE WHEN attempts + 1 >= ?
                                              THEN 'DEAD_LETTER'
                                              ELSE 'REJECTED'
                                         END
                     WHERE id = ?
                    """,
                    r.errorMessage,
                    maxAttempts,
                    r.id);
        }
    }

    /** Counters for the per-cycle log line. */
    public DeadLetterStats deadLetterStats(EntityType entityType) {
        return jdbc.queryForObject(
                """
                SELECT
                  SUM(CASE WHEN status='REJECTED'    THEN 1 ELSE 0 END) AS retryable,
                  SUM(CASE WHEN status='DEAD_LETTER' THEN 1 ELSE 0 END) AS stuck
                FROM outbox WHERE entity_type = ?
                """,
                (rs, i) -> new DeadLetterStats(rs.getInt("retryable"), rs.getInt("stuck")),
                entityType.name());
    }

    public record RejectedId(long id, String errorMessage) {}

    public record DeadLetterStats(int retryable, int stuck) {}

    public record OutboxEntry(
            long id,
            EntityType entityType,
            UUID sourceUuid,
            LocalDateTime watermark,
            String payloadJson,
            int attempts,
            Long sourceId
    ) {}
}
