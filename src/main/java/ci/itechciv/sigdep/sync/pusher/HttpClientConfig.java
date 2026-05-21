package ci.itechciv.sigdep.sync.pusher;

import ci.itechciv.sigdep.sync.config.SyncProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Duration;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class HttpClientConfig {

    /**
     * OkHttp client used to push batches to the hub. The three timeouts
     * come from sigdep.sync.http.* (env vars SIGDEP_HTTP_*_TIMEOUT_SECONDS).
     * Defaults match the historical hard-coded values (10s/60s/60s) so
     * existing deployments keep behaving the same — override on sites
     * where a big backfill exceeds 60s of read or write time.
     */
    @Bean
    public OkHttpClient okHttpClient(SyncProperties props) {
        SyncProperties.Http http = props.http();
        return new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(http.connectTimeoutSeconds()))
                .readTimeout(Duration.ofSeconds(http.readTimeoutSeconds()))
                .writeTimeout(Duration.ofSeconds(http.writeTimeoutSeconds()))
                .retryOnConnectionFailure(true)
                .build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.findAndRegisterModules();
        return mapper;
    }
}
