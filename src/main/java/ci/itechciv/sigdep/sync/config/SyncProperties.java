package ci.itechciv.sigdep.sync.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration de l'agent sync, validée au DÉMARRAGE (fail-fast).
 *
 * Avant cette validation, une configuration invalide (URL du hub sans schéma,
 * clé API laissée à 'changeme', code site absent…) laissait l'agent démarrer
 * puis échouer silencieusement à CHAQUE cycle de synchronisation — panne
 * invisible en production. Désormais le contexte Spring refuse de démarrer et
 * le processus se termine avec un code de sortie non nul, le message nommant
 * la variable d'environnement fautive et montrant la valeur reçue (la clé API
 * étant masquée au-delà des 4 premiers caractères).
 *
 * Les annotations Jakarta couvrent les cas simples (non-vide, bornes). Les
 * règles à message riche (schéma d'URL, clé ≠ 'changeme', préfixe jdbc:) sont
 * vérifiées dans {@link #validate()} pour pouvoir nommer la variable d'env et
 * masquer la clé.
 */
@Validated
@ConfigurationProperties(prefix = "sigdep.sync")
public record SyncProperties(
        @NotBlank(message = "SIGDEP_SITE_CODE est obligatoire (code du site)")
        String siteCode,

        @NotBlank(message = "SIGDEP_CENTRAL_API_URL est obligatoire (URL du hub)")
        String centralApiUrl,

        @Min(value = 1, message = "SIGDEP_BATCH_SIZE doit être >= 1")
        int batchSize,

        @Min(value = 1, message = "SIGDEP_SYNC_INTERVAL_MINUTES doit être >= 1")
        int syncIntervalMinutes,

        LocalDateTime watermarkInitial,
        Map<String, String> identifierMapping,

        // Clé API opaque (UUID) générée côté hub pour ce site, envoyée dans
        // l'en-tête X-API-Key (auth v2.0). Doit être renseignée et différente
        // de la valeur-gabarit 'changeme'.
        @NotBlank(message = "SIGDEP_API_KEY est obligatoire (clé API du site, générée dans la console hub)")
        String apiKey,

        // URL JDBC de la base OpenMRS locale (source). Doit commencer par 'jdbc:'.
        @NotBlank(message = "SIGDEP_LOCAL_DB_URL est obligatoire (URL JDBC de la base OpenMRS locale)")
        String localDbUrl,

        Backfill backfill,
        int maxRejectAttempts,

        // Profondeur du pipeline extraction/push : nombre maximum de lots « en
        // vol » (extraits + mis en file, pas encore acquittés par le hub) par
        // entity_type. Découple l'extraction du round-trip réseau : le lot N+1
        // s'extrait pendant que le lot N attend son ACK. Borne la mémoire/disque
        // et met le pipeline en pause quand le hub est lent/injoignable. 1 =
        // comportement séquentiel historique.
        @Min(value = 1, message = "SIGDEP_PIPELINE_DEPTH doit être >= 1")
        int pipelineDepth,

        Http http
) {
    public record Backfill(boolean enabled, int maxRequestsPerMinute, String cron) {}

    public record Http(
            int connectTimeoutSeconds,
            int readTimeoutSeconds,
            int writeTimeoutSeconds,
            // Résilience du push : sur un échec de TRANSPORT (réseau, 5xx, 429),
            // on ré-essaie le même lot avant de mettre l'entité en pause. Backoff
            // exponentiel borné + jitter. maxRetries=0 restaure le comportement
            // historique (pause immédiate).
            int maxRetries,
            long retryInitialDelayMs,
            long retryMaxDelayMs
    ) {}

    private static final String API_KEY_TEMPLATE = "changeme";

    /**
     * Base normalisée du hub (sans slash final), pré-calculée une fois pour
     * que {@link ci.itechciv.sigdep.sync.pusher.CentralApiClient} n'ait pas à
     * reconcaténer/reparser l'URL à chaque push. Sûr à appeler après
     * validation : l'URL a un schéma http/https.
     */
    public String centralApiBaseUrl() {
        String u = centralApiUrl.strip();
        while (u.endsWith("/")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    /**
     * Validation programmatique des règles à message riche. Appelée par le
     * bean {@link SyncPropertiesValidator} au démarrage (via un Validator
     * Spring), de sorte qu'un échec empêche le contexte de démarrer.
     * Retourne la liste des messages d'erreur (vide si tout est valide).
     */
    public List<String> validationErrors() {
        List<String> errors = new java.util.ArrayList<>();

        // URL du hub : schéma http/https obligatoire.
        if (centralApiUrl != null && !centralApiUrl.isBlank()) {
            String scheme = null;
            try {
                scheme = new URI(centralApiUrl.strip()).getScheme();
            } catch (URISyntaxException ignored) {
                // URL non parsable → scheme reste null → erreur ci-dessous.
            }
            if (scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                errors.add("SIGDEP_CENTRAL_API_URL doit être une URL http(s) valide — reçu : \""
                        + centralApiUrl + "\"");
            }
        }

        // Clé API : différente du gabarit 'changeme' (comparaison insensible
        // à la casse), valeur masquée dans le message.
        if (apiKey != null && API_KEY_TEMPLATE.equalsIgnoreCase(apiKey.strip())) {
            errors.add("SIGDEP_API_KEY ne doit pas rester la valeur-gabarit 'changeme'"
                    + " — reçu : \"" + maskApiKey(apiKey) + "\" (générer une clé dans la console hub)");
        }

        // URL JDBC locale : préfixe 'jdbc:'.
        if (localDbUrl != null && !localDbUrl.isBlank()
                && !localDbUrl.strip().toLowerCase(Locale.ROOT).startsWith("jdbc:")) {
            errors.add("SIGDEP_LOCAL_DB_URL doit commencer par 'jdbc:' — reçu : \""
                    + localDbUrl + "\"");
        }

        return errors;
    }

    /** Masque la clé API au-delà des 4 premiers caractères. */
    static String maskApiKey(String key) {
        if (key == null) return "null";
        String k = key.strip();
        if (k.length() <= 4) return "****";
        return k.substring(0, 4) + "****";
    }
}
