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
    private final BufferWriteLock writeLock;

    public OutboxRepository(@Qualifier("bufferJdbcTemplate") JdbcTemplate jdbc,
                            BufferWriteLock writeLock) {
        this.jdbc = jdbc;
        this.writeLock = writeLock;
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
     * Sémantique par ligne : UPSERT sur (entity_type, source_uuid), garanti
     * unique par {@code ux_outbox_entity_uuid}. Une ré-extraction écrase donc
     * la ligne existante au lieu d'en créer une nouvelle — y compris quand
     * elle était déjà SENT, cas qui accumulait auparavant une copie du
     * payload (donnée de santé) à chaque passage. Le statut repasse à PENDING
     * avec {@code attempts} remis à zéro : le payload vient d'être ré-extrait
     * de la source, les échecs de l'ancienne version ne le concernent plus.
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
        // Sous verrou d'écriture : la transaction d'enqueue (potentiellement
        // longue sur un gros backfill) ne doit pas chevaucher un markSent du
        // consommateur → sinon SQLITE_BUSY.
        writeLock.runExclusively(() -> jdbc.execute((Connection conn) -> {
            boolean previousAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                upsertAll(conn, rows);
                conn.commit();
                return null;
            } catch (RuntimeException | java.sql.SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(previousAutoCommit);
            }
        }));
    }

    /**
     * UPSERT groupé de toutes les lignes. Le DO UPDATE remet la ligne en
     * PENDING avec attempts=0 et efface last_error : c'est un payload neuf,
     * il doit repartir avec un compteur de tentatives neuf (sinon une ligne
     * anciennement DEAD_LETTER resterait bloquée alors que la donnée source
     * a justement été corrigée).
     *
     * sent_at est remis à NULL pour ne pas laisser une date d'envoi qui ne
     * correspond plus au contenu de la ligne.
     */
    private static void upsertAll(Connection conn, List<EnqueueRow> rows)
            throws java.sql.SQLException {
        if (rows.isEmpty()) {
            return;
        }
        String sql =
                """
                INSERT INTO outbox
                       (entity_type, source_uuid, source_id, watermark, payload_json, status)
                VALUES (?, ?, ?, ?, ?, 'PENDING')
                ON CONFLICT(entity_type, source_uuid) DO UPDATE SET
                       payload_json = excluded.payload_json,
                       watermark    = excluded.watermark,
                       source_id    = excluded.source_id,
                       status       = 'PENDING',
                       attempts     = 0,
                       last_error   = NULL,
                       sent_at      = NULL
                """;
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

    /**
     * Marque les lignes acceptées par le hub et EFFACE leur payload.
     *
     * Le payload est une donnée de santé nominative ; une fois le hub maître
     * de la donnée, la conserver sur le poste du site n'a plus d'utilité —
     * aucun code ne relit le payload_json d'une ligne SENT (le rapport
     * --reconcile n'en fait qu'un COUNT). On garde la ligne elle-même pour ce
     * comptage et pour la traçabilité (source_uuid, sent_at).
     *
     * La colonne est NOT NULL : on écrit la chaîne vide, pas NULL.
     */
    public void markSent(List<Long> ids) {
        if (ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", ids.stream().map(i -> "?").toList());
        writeLock.runExclusively(() -> jdbc.update(
                "UPDATE outbox SET status='SENT', sent_at=datetime('now'), payload_json='' "
                        + "WHERE id IN (" + placeholders + ")",
                ids.toArray()));
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
        writeLock.runExclusively(() -> jdbc.update(
                "UPDATE outbox SET attempts = attempts + 1, last_error = ?, status='PENDING' "
                        + "WHERE id IN (" + placeholders + ")",
                params));
    }

    /**
     * Rejet de VALIDATION (données invalides, ex. UPSERT_FAILED) : la ligne
     * passe en 'REJECTED' et CONSOMME une tentative ; après {@code maxAttempts}
     * elle bascule en 'DEAD_LETTER' (intervention manuelle requise). C'est le
     * comportement historique, désormais réservé aux vrais rejets de données.
     */
    public void markValidationRejected(List<RejectedId> rejects, int maxAttempts) {
        if (rejects.isEmpty()) return;
        writeLock.runExclusively(() -> {
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
        });
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
        writeLock.runExclusively(() -> {
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
        });
    }

    /**
     * Remet en file les lignes 'DEAD_LETTER' pour un nouveau cycle de push :
     * status → 'PENDING' et compteur {@code attempts} remis à zéro (le plafond
     * de tentatives doit repartir de zéro, sinon la ligne rebasculerait en
     * DEAD_LETTER au premier rejet). {@code last_error} est CONSERVÉ comme
     * trace du dernier échec. Opération manuelle (via la commande
     * {@code --requeue-dead-letter}) : à lancer une fois la cause corrigée
     * côté hub (schéma, mapping, migration…).
     *
     * @param entityType entité ciblée, ou {@code null} pour TOUTES les entités.
     * @return nombre de lignes remises en file.
     */
    public int requeueDeadLetter(EntityType entityType) {
        return writeLock.callExclusively(() -> {
            if (entityType == null) {
                return jdbc.update(
                        "UPDATE outbox SET status='PENDING', attempts=0 "
                                + "WHERE status='DEAD_LETTER'");
            }
            return jdbc.update(
                    "UPDATE outbox SET status='PENDING', attempts=0 "
                            + "WHERE status='DEAD_LETTER' AND entity_type=?",
                    entityType.name());
        });
    }

    /** Nombre de lignes en DEAD_LETTER (toutes entités, ou une seule). */
    public int deadLetterCount(EntityType entityType) {
        Integer n = entityType == null
                ? jdbc.queryForObject(
                        "SELECT COUNT(*) FROM outbox WHERE status='DEAD_LETTER'", Integer.class)
                : jdbc.queryForObject(
                        "SELECT COUNT(*) FROM outbox WHERE status='DEAD_LETTER' AND entity_type=?",
                        Integer.class, entityType.name());
        return n == null ? 0 : n;
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

    /**
     * Compteurs outbox par (entity_type, status) en un seul balayage, pour le
     * rapport de réconciliation ({@code --reconcile}). Clé = nom d'entité ;
     * chaque {@link OutboxCounts} porte le détail par statut. Une entité sans
     * aucune ligne en outbox n'apparaît PAS dans la map (au rapport d'ajouter
     * une ligne à zéro à partir de la liste des extracteurs).
     */
    public java.util.Map<String, OutboxCounts> outboxCountsByEntity() {
        java.util.Map<String, OutboxCounts> out = new java.util.HashMap<>();
        jdbc.query(
                """
                SELECT entity_type,
                       SUM(CASE WHEN status='SENT'        THEN 1 ELSE 0 END) AS sent,
                       SUM(CASE WHEN status='PENDING'     THEN 1 ELSE 0 END) AS pending,
                       SUM(CASE WHEN status='REJECTED'    THEN 1 ELSE 0 END) AS rejected,
                       SUM(CASE WHEN status='DEAD_LETTER' THEN 1 ELSE 0 END) AS dead
                FROM outbox
                GROUP BY entity_type
                """,
                (org.springframework.jdbc.core.RowCallbackHandler) rs ->
                        out.put(rs.getString("entity_type"), new OutboxCounts(
                                rs.getLong("sent"), rs.getLong("pending"),
                                rs.getLong("rejected"), rs.getLong("dead"))));
        return out;
    }

    public record OutboxCounts(long sent, long pending, long rejected, long dead) {
        public long total() {
            return sent + pending + rejected + dead;
        }
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
