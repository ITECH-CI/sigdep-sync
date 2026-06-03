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
    private final SyncProperties props;

    public CentralApiClient(OkHttpClient http,
                            ObjectMapper mapper,
                            SyncProperties props) {
        this.http = http;
        this.mapper = mapper;
        this.props = props;
    }

    public SyncBatchResponse push(EntityType entityType, SyncBatchRequest<?> batch) throws IOException {
        String url = props.centralApiUrl() + "/api/v1/sync/" + entityType.name().toLowerCase();
        RequestBody body = RequestBody.create(mapper.writeValueAsBytes(batch), JSON);

        Request.Builder req = new Request.Builder()
                .url(url)
                .post(body);

        // Auth v2.0 : clé API opaque dans X-API-Key (remplace le bearer OAuth).
        // Vide en dev (le hub en profil dev accepte les requêtes non authentifiées).
        String apiKey = props.apiKey();
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
