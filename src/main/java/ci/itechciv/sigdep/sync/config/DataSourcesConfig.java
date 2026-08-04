package ci.itechciv.sigdep.sync.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

@Configuration
public class DataSourcesConfig {

    @Bean
    @Primary
    @ConfigurationProperties("sigdep.sync.local-db")
    public DataSource localDataSource() {
        return DataSourceBuilder.create().build();
    }

    /**
     * DataSource du buffer SQLite, avec PRAGMA appliqués de façon FIABLE via
     * {@link SQLiteConfig} (et non via Hikari connection-init-sql : le driver
     * xerial n'exécute que la 1re instruction d'une string multi-PRAGMA, ce qui
     * laisserait synchronous à FULL).
     *
     * Compromis de durabilité ASSUMÉ :
     * <ul>
     *   <li><b>journal_mode=WAL</b> : écritures plus rapides, journal séparé,
     *       lectures concurrentes non bloquées.</li>
     *   <li><b>synchronous=NORMAL</b> : plus de fsync à chaque commit (vs FULL
     *       par défaut). Sur crash OS / coupure de courant, les dernières
     *       transactions WAL non checkpointées peuvent être perdues —
     *       ACCEPTABLE : le buffer (outbox + watermarks) est RECONSTRUCTIBLE
     *       depuis la base source (ré-extraction à partir du dernier watermark
     *       confirmé par le hub). En échange, l'enqueue d'un lot de 500 lignes
     *       passe de ~1,5 s à quelques dizaines de ms.</li>
     * </ul>
     */
    @Bean
    public DataSource bufferDataSource(
            @Value("${sigdep.sync.buffer-db.jdbc-url}") String jdbcUrl) {
        SQLiteConfig config = new SQLiteConfig();
        config.setJournalMode(SQLiteConfig.JournalMode.WAL);
        config.setSynchronous(SQLiteConfig.SynchronousMode.NORMAL);
        SQLiteDataSource ds = new SQLiteDataSource(config);
        ds.setUrl(jdbcUrl);
        return ds;
    }

    @Bean
    public JdbcTemplate localJdbcTemplate(@Qualifier("localDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean
    public JdbcTemplate bufferJdbcTemplate(@Qualifier("bufferDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
