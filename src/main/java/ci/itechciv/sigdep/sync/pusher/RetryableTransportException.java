package ci.itechciv.sigdep.sync.pusher;

import java.io.IOException;

/**
 * Échec de TRANSPORT vers le hub, TRANSITOIRE et donc retentable : coupure
 * réseau, reset de flux HTTP/2 (GOAWAY après {@code keepalive_requests}),
 * handshake TLS interrompu, timeout, HTTP 5xx ou 429.
 *
 * À distinguer d'une {@link IOException} simple, qui signale un rejet
 * applicatif permanent (HTTP 4xx : requête ou authentification invalide) pour
 * lequel re-tenter à l'identique est inutile.
 */
public class RetryableTransportException extends IOException {

    public RetryableTransportException(String message) {
        super(message);
    }

    public RetryableTransportException(String message, Throwable cause) {
        super(message, cause);
    }
}
