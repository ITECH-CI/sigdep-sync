package ci.itechciv.sigdep.sync.buffer;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Applies db/sqlite/buffer-schema.sql to the local SQLite buffer, creating the
 * buffer's parent directory if it does not yet exist (so a fresh checkout /
 * fresh site install works without 'mkdir -p' first).
 * Idempotent — the DDL uses CREATE TABLE IF NOT EXISTS.
 *
 * Runs as a {@code @PostConstruct} (not an {@code ApplicationRunner}) so the
 * schema is guaranteed to exist during context initialisation, BEFORE
 * {@code @EnableScheduling} starts the scheduler in the lifecycle phase. With
 * an ApplicationRunner the first scheduled cycle could race the DDL and fail
 * with "no such table: sync_state". {@link ci.itechciv.sigdep.sync.scheduler.SyncScheduler}
 * also declares {@code @DependsOn("bufferSchemaInitializer")} to make the
 * ordering explicit.
 */
@Component
public class BufferSchemaInitializer {

    private static final Logger log = LoggerFactory.getLogger(BufferSchemaInitializer.class);

    private final JdbcTemplate buffer;
    private final Environment env;

    public BufferSchemaInitializer(@Qualifier("bufferJdbcTemplate") JdbcTemplate buffer,
                                   Environment env) {
        this.buffer = buffer;
        this.env = env;
    }

    @PostConstruct
    public void initSchema() {
        ensureBufferDirectory();

        String ddl;
        try {
            ddl = StreamUtils.copyToString(
                    new ClassPathResource("db/sqlite/buffer-schema.sql").getInputStream(),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read buffer-schema.sql", e);
        }
        for (String stmt : ddl.split(";")) {
            String trimmed = stmt.trim();
            if (trimmed.isEmpty()) continue;
            buffer.execute(trimmed);
        }
        addColumnIfMissing("sync_state", "last_id", "INTEGER");
        addColumnIfMissing("outbox", "source_id", "INTEGER");
        log.info("SQLite buffer schema ensured");
    }

    /**
     * Ajoute une colonne sur une base déjà créée avant son introduction (le
     * CREATE TABLE IF NOT EXISTS ne modifie pas une table existante). SQLite
     * n'a pas d'ADD COLUMN IF NOT EXISTS → on interroge PRAGMA table_info
     * d'abord. Idempotent (colonnes du keyset : sync_state.last_id,
     * outbox.source_id).
     */
    private void addColumnIfMissing(String table, String column, String type) {
        boolean present = Boolean.TRUE.equals(buffer.query(
                "PRAGMA table_info(" + table + ")",
                rs -> {
                    while (rs.next()) {
                        if (column.equalsIgnoreCase(rs.getString("name"))) {
                            return Boolean.TRUE;
                        }
                    }
                    return Boolean.FALSE;
                }));
        if (!present) {
            buffer.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + type);
            log.info("{}.{} column added (keyset migration)", table, column);
        }
    }

    private void ensureBufferDirectory() {
        // Re-derive the path the same way DataSourcesConfig does, by reading
        // sigdep.sync.buffer-db.jdbc-url from the environment.
        String jdbcUrl = env.getProperty("sigdep.sync.buffer-db.jdbc-url");
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:sqlite:")) {
            return;
        }
        String filePath = jdbcUrl.substring("jdbc:sqlite:".length());
        Path parent = Paths.get(filePath).getParent();
        if (parent == null) {
            return;
        }
        try {
            Files.createDirectories(parent);
            log.debug("Buffer directory ensured: {}", parent);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create buffer directory " + parent, e);
        }
    }
}
