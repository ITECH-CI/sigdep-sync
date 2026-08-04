package ci.itechciv.sigdep.sync.buffer;

import ci.itechciv.sigdep.contracts.EntityType;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

    /** Une ligne à mettre en file. */
    public record EnqueueRow(
            EntityType entityType,
            UUID sourceUuid,
            LocalDateTime watermark,
            Long sourceId,
            String payloadJson) {}

    /**
     * Met en file un LOT de lignes en une SEULE transaction, ce qui évite un
     * commit (et donc un fsync) par ligne — l'enqueue de 500 lignes passait de
     * ~1,5 s (autocommit ligne par ligne) à quelques dizaines de ms.
     *
     * Sémantique conservée par ligne : on tente d'abord un UPDATE en place si
     * une ligne (entity_type, source_uuid) est encore PENDING/REJECTED (le hub
     * ne l'a pas encore acceptée), sinon on INSERT. Les UPDATE et les INSERT
     * sont chacun groupés en {@code addBatch()/executeBatch()}.
     *
     * Atomicité : autocommit désactivé le temps du lot ; toute exception
     * déclenche un rollback de l'INTÉGRALITÉ du lot (rien de partiel n'est
     * persisté), puis est relancée. La connexion retrouve son autocommit
     * initial avant d'être rendue au pool.
     */
    public void enqueueBatch(List<EnqueueRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        jdbc.execute((Connection conn) -> {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                List<EnqueueRow> toInsert = updateExisting(conn, rows);
                insertNew(conn, toInsert);
                conn.commit();
                return null;
            } catch (RuntimeException | java.sql.SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        });
    }

    /**
     * Passe UPDATE sur chaque ligne (batch) et retourne celles pour lesquelles
     * aucune ligne PENDING/REJECTED n'existait (updateCount == 0) → à INSERT.
     */
    private static List<EnqueueRow> updateExisting(Connection conn, List<EnqueueRow> rows)
            throws java.sql.SQLException {
        String sql =
                "UPDATE outbox SET payload_json = ?, watermark = ?, source_id = ?"
                + " WHERE entity_type = ? AND source_uuid = ?"
                + "   AND status IN ('PENDING', 'REJECTED')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (EnqueueRow r : rows) {
                ps.setString(1, r.payloadJson());
                ps.setTimestamp(2, Timestamp.valueOf(r.watermark()));
                if (r.sourceId() == null) {
                    ps.setNull(3, Types.INTEGER);
                } else {
                    ps.setLong(3, r.sourceId());
                }
                ps.setString(4, r.entityType().name());
                ps.setString(5, r.sourceUuid().toString());
                ps.addBatch();
            }
            int[] counts = ps.executeBatch();
            List<EnqueueRow> toInsert = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                // 0 = aucune ligne existante mise à jour → à insérer.
                // SUCCESS_NO_INFO (-2) signifie « au moins une », donc pas d'insert.
                if (counts[i] == 0) {
                    toInsert.add(rows.get(i));
                }
            }
            return toInsert;
        }
    }

    /** INSERT groupé des lignes nouvelles. */
    private static void insertNew(Connection conn, List<EnqueueRow> rows)
            throws java.sql.SQLException {
        if (rows.isEmpty()) {
            return;
        }
        String sql =
                "INSERT INTO outbox (entity_type, source_uuid, source_id, watermark, payload_json, status)"
                + " VALUES (?, ?, ?, ?, ?, 'PENDING')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (EnqueueRow r : rows) {
                ps.setString(1, r.entityType().name());
                ps.setString(2, r.sourceUuid().toString());
                if (r.sourceId() == null) {
                    ps.setNull(3, Types.INTEGER);
                } else {
                    ps.setLong(3, r.sourceId());
                }
                ps.setTimestamp(4, Timestamp.valueOf(r.watermark()));
                ps.setString(5, r.payloadJson());
                ps.addBatch();
            }
            ps.executeBatch();
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
     * Rejet de VALIDATION (données invalides, ex. UPSERT_FAILED) : la ligne
     * passe en 'REJECTED' et CONSOMME une tentative ; après {@code maxAttempts}
     * elle bascule en 'DEAD_LETTER' (intervention manuelle requise). C'est le
     * comportement historique, désormais réservé aux vrais rejets de données.
     */
    public void markValidationRejected(List<RejectedId> rejects, int maxAttempts) {
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

    /**
     * Rejet de DÉPENDANCE (parent pas encore ingéré, ex. UNKNOWN_PATIENT) : la
     * ligne passe en 'REJECTED' mais NE CONSOMME PAS de tentative
     * ({@code attempts} inchangé). Un simple décalage d'ordonnancement (le
     * patient parent sera poussé à un cycle ultérieur) ne doit jamais produire
     * un 'stuck' définitif en DEAD_LETTER. La ligne sera rejouée à chaque cycle
     * jusqu'à ce que le parent existe côté hub.
     */
    public void markDependencyPending(List<RejectedId> rejects) {
        if (rejects.isEmpty()) return;
        for (RejectedId r : rejects) {
            jdbc.update(
                    """
                    UPDATE outbox
                       SET last_error = ?,
                           status     = 'REJECTED'
                     WHERE id = ?
                    """,
                    r.errorMessage,
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

    /**
     * Nombre de rejets de DÉPENDANCE EN ATTENTE pour cette entité : lignes en
     * 'REJECTED' dont le dernier rejet est une dépendance non satisfaite
     * (last_error préfixé par le code UNKNOWN_PATIENT). Sert de compteur
     * observable « en attente du parent », distinct des rejets de validation.
     */
    public int pendingDependencyCount(EntityType entityType) {
        Integer n = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM outbox
                 WHERE entity_type = ? AND status = 'REJECTED'
                   AND last_error LIKE 'UNKNOWN_PATIENT%'
                """,
                Integer.class,
                entityType.name());
        return n == null ? 0 : n;
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
