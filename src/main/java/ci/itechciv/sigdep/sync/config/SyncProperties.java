package ci.itechciv.sigdep.sync.config;

import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "sigdep.sync")
public record SyncProperties(
        String siteCode,
        String centralApiUrl,
        int batchSize,
        int syncIntervalMinutes,
        LocalDateTime watermarkInitial,
        Map<String, String> identifierMapping,
        Keycloak keycloak,
        Backfill backfill,
        // How many times we re-push a record the hub rejected before parking
        // it in DEAD_LETTER. Tuned so transient FK ordering issues
        // (UNKNOWN_PATIENT during initial backfill) resolve themselves while
        // genuinely bad data stops looping. Default 10.
        int maxRejectAttempts,
        // OkHttp client timeouts to the hub. The defaults (60s read/write)
        // are fine for batches of a few hundred records, but a backfill of
        // tens of thousands of lab results on a slow link needs longer —
        // override via SIGDEP_HTTP_READ_TIMEOUT_SECONDS / WRITE_TIMEOUT
        // rather than dropping batch-size unnecessarily.
        Http http
) {
    public record Keycloak(String issuerUrl, String clientId, String clientSecret) {}

    public record Backfill(boolean enabled, int maxRequestsPerMinute, String cron) {}

    public record Http(
            int connectTimeoutSeconds,
            int readTimeoutSeconds,
            int writeTimeoutSeconds
    ) {}
}
