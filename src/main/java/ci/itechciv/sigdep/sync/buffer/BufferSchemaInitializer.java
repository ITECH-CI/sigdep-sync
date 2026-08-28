package ci.itechciv.sigdep.sync.buffer;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
        // Les colonnes du keyset doivent exister avant tout DML de migration.
        // CREATE TABLE IF NOT EXISTS ne modifie pas une table deja creee, donc
        // on applique d'abord le DDL des tables, puis les ALTER, puis le
        // dedoublonnage, et seulement ensuite l'index UNIQUE (qui echouerait
        // sur une base historique porteuse de doublons).
        for (String stmt : splitStatements(ddl)) {
            if (isUniqueOutboxIndex(stmt)) {
                continue;
            }
            buffer.execute(stmt);
        }
        addColumnIfMissing("sync_state", "last_id", "INTEGER");
        addColumnIfMissing("outbox", "source_id", "INTEGER");
        dedupeOutbox();
        for (String stmt : splitStatements(ddl)) {
            if (isUniqueOutboxIndex(stmt)) {
                buffer.execute(stmt);
            }
        }
        log.info("SQLite buffer schema ensured");
    }

    /**
     * Découpe un script DDL en instructions individuelles sur ';', de façon
     * robuste aux commentaires et aux littéraux :
     * <ul>
     *   <li>retire les commentaires ligne ({@code -- ...}) et bloc
     *       ({@code /* ... *&#47;}) avant de découper — un ';' à l'intérieur
     *       d'un commentaire ne coupe donc pas l'instruction ;</li>
     *   <li>un {@code --} ou {@code /*} situé dans un littéral entre quotes
     *       simples n'est PAS traité comme un commentaire ;</li>
     *   <li>un ';' dans un littéral entre quotes simples ne coupe pas ;</li>
     *   <li>les fragments vides (après strip/trim) sont ignorés ;</li>
     *   <li>la dernière instruction n'a pas besoin d'un ';' final.</li>
     * </ul>
     * SQLite ne gère pas les quotes échappées par backslash ; une quote
     * simple se double ({@code ''}) à l'intérieur d'un littéral, ce que le
     * balayage caractère-par-caractère gère naturellement (la quote fermante
     * est immédiatement rouverte).
     */
    public static List<String> splitStatements(String ddl) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inLineComment = false;
        boolean inBlockComment = false;
        boolean inString = false;
        int n = ddl.length();

        for (int i = 0; i < n; i++) {
            char c = ddl.charAt(i);
            char next = i + 1 < n ? ddl.charAt(i + 1) : '\0';

            if (inLineComment) {
                // Le commentaire ligne se termine au saut de ligne, qu'on
                // conserve pour ne pas coller deux tokens.
                if (c == '\n') {
                    inLineComment = false;
                    current.append(c);
                }
                continue;
            }
            if (inBlockComment) {
                if (c == '*' && next == '/') {
                    inBlockComment = false;
                    i++; // consomme le '/'
                }
                continue;
            }
            if (inString) {
                current.append(c);
                if (c == '\'') {
                    inString = false; // une '' rouvre aussitôt au tour suivant
                }
                continue;
            }

            // Hors commentaire, hors littéral : détecter ouverture de l'un d'eux.
            if (c == '-' && next == '-') {
                inLineComment = true;
                i++; // consomme le 2e '-'
                continue;
            }
            if (c == '/' && next == '*') {
                inBlockComment = true;
                i++; // consomme le '*'
                continue;
            }
            if (c == '\'') {
                inString = true;
                current.append(c);
                continue;
            }
            if (c == ';') {
                addIfNotBlank(statements, current);
                current.setLength(0);
                continue;
            }
            current.append(c);
        }
        // Dernière instruction sans ';' final.
        addIfNotBlank(statements, current);
        return statements;
    }

    private static void addIfNotBlank(List<String> statements, StringBuilder sb) {
        String trimmed = sb.toString().trim();
        if (!trimmed.isEmpty()) {
            statements.add(trimmed);
        }
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

    /**
     * Reconnait l'instruction creant l'index UNIQUE sur (entity_type,
     * source_uuid). Elle est mise de cote au premier passage : sur une base
     * historique, des doublons existent et sa creation echouerait.
     */
    private static boolean isUniqueOutboxIndex(String stmt) {
        return stmt.toLowerCase(java.util.Locale.ROOT).contains("ux_outbox_entity_uuid");
    }

    /**
     * Migration des bases historiques : avant l'index UNIQUE, enqueueBatch
     * INSERT une nouvelle ligne chaque fois que la precedente etait deja SENT
     * (l'UPDATE en place ne visait que PENDING/REJECTED). Un meme enregistrement
     * pouvait donc avoir des dizaines de copies de son payload_json sur le
     * poste du site.
     *
     * On ne garde que la ligne la plus recente (MAX(id)) par couple
     * (entity_type, source_uuid). C'est la bonne survivante : elle porte le
     * payload le plus a jour et le statut courant ; les anciennes sont, par
     * construction, des SENT deja acceptes par le hub.
     *
     * Idempotent : sans doublon le DELETE ne touche rien. Le VACUUM n'est
     * declenche que si des lignes ont ete supprimees (SQLite ne rend pas
     * l'espace disque autrement) et jamais dans une transaction.
     */
    private void dedupeOutbox() {
        Integer dupes = buffer.queryForObject(
                """
                SELECT COUNT(*) FROM outbox
                 WHERE id NOT IN (SELECT MAX(id) FROM outbox
                                   GROUP BY entity_type, source_uuid)
                """,
                Integer.class);
        if (dupes == null || dupes == 0) {
            return;
        }
        buffer.update(
                """
                DELETE FROM outbox
                 WHERE id NOT IN (SELECT MAX(id) FROM outbox
                                   GROUP BY entity_type, source_uuid)
                """);
        log.warn("Outbox dedupe: {} ligne(s) dupliquee(s) supprimee(s) "
                + "(une seule ligne conservee par entity_type + source_uuid)", dupes);
        buffer.execute("VACUUM");
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
            restrictBufferDirectoryPermissions(parent);
            log.debug("Buffer directory ensured: {}", parent);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create buffer directory " + parent, e);
        }
    }

    /**
     * Défense en profondeur : restreint le répertoire du buffer à son seul
     * propriétaire ({@code rwx------}), indépendamment de ce que le packaging a
     * (ou n'a pas) fait. Le buffer contient des données de santé nominatives et
     * WAL y crée aussi {@code buffer.sqlite-wal}/{@code -shm} : protéger le
     * RÉPERTOIRE couvre les sidecars.
     *
     * <p>POSIX seulement (Linux/Docker) : sur un système sans permissions POSIX
     * (Windows), on ne fait rien — le contrôle d'accès y est posé par
     * {@code install-service.bat} (icacls). Best-effort : un échec de
     * resserrement est journalisé sans empêcher le démarrage (le packaging
     * reste la première ligne de défense).
     */
    static void restrictBufferDirectoryPermissions(Path dir) {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            return; // Windows : ACL gérées par le packaging (icacls).
        }
        Set<PosixFilePermission> ownerOnly =
                PosixFilePermissions.fromString("rwx------");
        try {
            Files.setPosixFilePermissions(dir, ownerOnly);
        } catch (IOException | UnsupportedOperationException e) {
            log.warn("Impossible de restreindre les permissions du dossier buffer {} "
                    + "({}). Vérifiez que le packaging l'a mis en 700 : il contient "
                    + "des données de santé.", dir, e.toString());
        }
    }
}
