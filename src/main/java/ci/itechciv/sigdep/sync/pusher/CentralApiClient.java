package ci.itechciv.sigdep.sync.pusher;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.contracts.SyncBatchRequest;
import ci.itechciv.sigdep.contracts.SyncBatchResponse;
import ci.itechciv.sigdep.sync.config.SyncProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Component;

@Component
public class CentralApiClient {

    private static final MediaType JSON = MediaType.parse("application/json");

    private final OkHttpClient http;
    private final ObjectMapper mapper;
    // Base du hub, déjà normalisée et VALIDÉE au démarrage (schéma http/https,
    // pas de slash final) — plus besoin de re-normaliser/reparser à chaque push.
    private final String baseUrl;
    private final String apiKey;

    public CentralApiClient(OkHttpClient http,
                            ObjectMapper mapper,
                            SyncProperties props) {
        this.http = http;
        this.mapper = mapper;
        this.baseUrl = props.centralApiBaseUrl();
        this.apiKey = props.apiKey();
    }

    public SyncBatchResponse push(EntityType entityType, SyncBatchRequest<?> batch) throws IOException {
        String url = baseUrl + "/api/v1/sync/" + entityType.name().toLowerCase();
        RequestBody body = RequestBody.create(mapper.writeValueAsBytes(batch), JSON);

        Request.Builder req = new Request.Builder()
                .url(url)
                .post(body);

        // Auth v2.0 : clé API opaque dans X-API-Key (remplace le bearer OAuth).
        // La validation au démarrage garantit une clé non vide et != 'changeme'.
        if (apiKey != null && !apiKey.isBlank()) {
            req.header("X-API-Key", apiKey);
        }

        try (Response resp = http.newCall(req.build()).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("Central API returned HTTP " + resp.code()
                        + " for " + url);
            }
            return mapper.readValue(resp.body().bytes(), SyncBatchResponse.class);
        }
    }
}
