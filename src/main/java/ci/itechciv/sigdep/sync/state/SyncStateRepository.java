package ci.itechciv.sigdep.sync.state;

import ci.itechciv.sigdep.contracts.EntityType;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SyncStateRepository {

    private final JdbcTemplate jdbc;

    public SyncStateRepository(@Qualifier("bufferJdbcTemplate") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<LocalDateTime> getWatermark(EntityType entityType) {
        return jdbc.query(
                "SELECT last_watermark FROM sync_state WHERE entity_type = ?",
                rs -> rs.next()
                        ? Optional.ofNullable(rs.getTimestamp("last_watermark")).map(java.sql.Timestamp::toLocalDateTime)
                        : Optional.empty(),
                entityType.name()
        );
    }

    public void updateWatermark(EntityType entityType, LocalDateTime watermark, int recordsSent, String status) {
        jdbc.update(
                """
                INSERT INTO sync_state (entity_type, last_watermark, last_run_at, last_status, records_sent)
                VALUES (?, ?, datetime('now'), ?, ?)
                ON CONFLICT(entity_type) DO UPDATE SET
                  last_watermark = excluded.last_watermark,
                  last_run_at    = excluded.last_run_at,
                  last_status    = excluded.last_status,
                  records_sent   = sync_state.records_sent + excluded.records_sent
                """,
                entityType.name(),
                java.sql.Timestamp.valueOf(watermark),
                status,
                recordsSent
        );
    }

    /**
     * Tie-breaker de keyset (sync_state.last_id), pour les entités dont le
     * watermark temporel n'a qu'une granularité JOUR. Vide si jamais posé.
     */
    public Optional<Long> getLastId(EntityType entityType) {
        return jdbc.query(
                "SELECT last_id FROM sync_state WHERE entity_type = ?",
                rs -> {
                    if (!rs.next()) return Optional.<Long>empty();
                    long v = rs.getLong("last_id");
                    return rs.wasNull() ? Optional.<Long>empty() : Optional.of(v);
                },
                entityType.name()
        );
    }

    /**
     * Avance le curseur keyset (last_watermark, last_id) en une écriture, en
     * cumulant les stats comme {@link #updateWatermark}. Utilisé pour les
     * entités à watermark JOUR (ex. screening) : la date seule ne suffit pas à
     * progresser à l'intérieur d'un même jour, d'où le tie-breaker last_id.
     */
    public void updateKeyset(EntityType entityType, LocalDateTime watermark, long lastId,
                             int recordsSent, String status) {
        jdbc.update(
                """
                INSERT INTO sync_state (entity_type, last_watermark, last_id, last_run_at, last_status, records_sent)
                VALUES (?, ?, ?, datetime('now'), ?, ?)
                ON CONFLICT(entity_type) DO UPDATE SET
                  last_watermark = excluded.last_watermark,
                  last_id        = excluded.last_id,
                  last_run_at    = excluded.last_run_at,
                  last_status    = excluded.last_status,
                  records_sent   = sync_state.records_sent + excluded.records_sent
                """,
                entityType.name(),
                java.sql.Timestamp.valueOf(watermark),
                lastId,
                status,
                recordsSent
        );
    }
}
