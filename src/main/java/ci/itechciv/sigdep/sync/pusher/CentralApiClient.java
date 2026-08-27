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

    /**
     * Pousse un batch au hub. Distingue deux familles d'échec :
     * <ul>
     *   <li><b>transport / transitoire</b> (coupure réseau, timeout, HTTP 5xx,
     *       429) → {@link RetryableTransportException} : le hub est là mais le
     *       transport a lâché, un retry a des chances d'aboutir ;</li>
     *   <li><b>applicatif / permanent</b> (HTTP 4xx hors 429 : requête ou auth
     *       invalide) → {@link IOException} simple : re-tenter à l'identique ne
     *       sert à rien.</li>
     * </ul>
     * La stratégie de retry (backoff+jitter) est appliquée par l'appelant
     * ({@code OutboxFlusher}) qui sait combien de fois ré-essayer.
     */
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
            if (resp.isSuccessful() && resp.body() != null) {
                return mapper.readValue(resp.body().bytes(), SyncBatchResponse.class);
            }
            int code = resp.code();
            String msg = "Central API returned HTTP " + code + " for " + url;
            if (isTransient(code)) {
                throw new RetryableTransportException(msg);
            }
            throw new IOException(msg);
        } catch (java.io.InterruptedIOException e) {
            // Timeout de lecture/écriture (SocketTimeoutException en est une
            // sous-classe) : transitoire.
            throw new RetryableTransportException("Timeout vers " + url + " : " + e.getMessage(), e);
        } catch (RetryableTransportException e) {
            throw e; // déjà catégorisée
        } catch (IOException e) {
            throw e; // rejet applicatif (5xx/429 déjà convertis plus haut)
        } catch (Exception e) {
            // Toute autre panne de transport OkHttp (reset de flux HTTP/2,
            // handshake TLS interrompu, connexion refusée…) est transitoire.
            throw new RetryableTransportException("Échec de transport vers " + url
                    + " : " + e.getClass().getSimpleName() + " " + e.getMessage(), e);
        }
    }

    /** 5xx (hub en erreur) et 429 (rate limit) sont retentables ; 4xx non. */
    private static boolean isTransient(int httpCode) {
        return httpCode >= 500 || httpCode == 429;
    }
}
