package ci.itechciv.sigdep.sync.buffer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Migration d'une base ANTÉRIEURE à l'index unique : elle contient plusieurs
 * lignes pour un même (entity_type, source_uuid), séquelle de l'enqueue qui
 * INSÉRAIT une nouvelle ligne dès que la précédente était SENT. L'initialiseur
 * doit dédoublonner AVANT de créer l'index — sinon le CREATE UNIQUE INDEX
 * échoue et l'agent ne démarre plus.
 */
class OutboxDedupeMigrationTest {

    @TempDir
    Path tmp;

    private JdbcTemplate open(Path file) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:" + file);
        return new JdbcTemplate(ds);
    }

    @Test
    @DisplayName("Base historique avec doublons : dédoublonnée, index créé, agent démarre")
    void legacyDatabaseWithDuplicatesIsMigrated() {
        Path db = tmp.resolve("legacy.sqlite");
        JdbcTemplate jdbc = open(db);

        // Schéma d'avant la migration (sans index unique).
        jdbc.execute("""
                CREATE TABLE outbox (
                  id            INTEGER PRIMARY KEY AUTOINCREMENT,
                  entity_type   TEXT NOT NULL,
                  source_uuid   TEXT NOT NULL,
                  watermark     TIMESTAMP NOT NULL,
                  payload_json  TEXT NOT NULL,
                  status        TEXT NOT NULL DEFAULT 'PENDING',
                  attempts      INTEGER DEFAULT 0,
                  last_error    TEXT,
                  created_at    TEXT DEFAULT (datetime('now')),
                  sent_at       TEXT
                )""");
        jdbc.execute("CREATE TABLE sync_state ("
                + "entity_type TEXT PRIMARY KEY, last_watermark TIMESTAMP,"
                + " last_run_at TIMESTAMP, last_status TEXT, records_sent INTEGER DEFAULT 0)");

        // 3 copies du même enregistrement + 1 autre enregistrement.
        for (int i = 1; i <= 3; i++) {
            jdbc.update("INSERT INTO outbox (entity_type, source_uuid, watermark, payload_json, status)"
                    + " VALUES ('PATIENTS', 'uuid-a', '2026-01-01', ?, 'SENT')", "{\"v\":" + i + "}");
        }
        jdbc.update("INSERT INTO outbox (entity_type, source_uuid, watermark, payload_json, status)"
                + " VALUES ('VISITS', 'uuid-b', '2026-01-01', '{\"v\":9}', 'PENDING')");

        MockEnvironment env = new MockEnvironment();
        env.setProperty("sigdep.sync.buffer-db.jdbc-url", "jdbc:sqlite:" + db);
        new BufferSchemaInitializer(jdbc, env).initSchema();

        // Une seule ligne par enregistrement, et c'est la PLUS RÉCENTE qui survit.
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT payload_json FROM outbox WHERE source_uuid='uuid-a'", String.class))
                .isEqualTo("{\"v\":3}");

        // L'index unique existe et fait son office.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM sqlite_master WHERE type='index'"
                        + " AND name='ux_outbox_entity_uuid'", Long.class)).isEqualTo(1);

        // Idempotence : un second démarrage ne casse rien.
        new BufferSchemaInitializer(jdbc, env).initSchema();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Long.class)).isEqualTo(2);
    }
}
