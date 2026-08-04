package ci.itechciv.sigdep.sync.extractor;

import java.time.LocalDateTime;

/**
 * Curseur de pagination keyset passé à {@link DataExtractor#extract}.
 *
 * Porte le couple {@code (watermark, lastId)} — la borne temporelle ET le
 * tie-breaker d'identité — pour une pagination keyset composite stable :
 * {@code WHERE (date, id) > (watermark, lastId)}, {@code ORDER BY date, id}.
 *
 * <p>Pourquoi le couple et pas la seule date : si plus de {@code batchSize}
 * lignes partagent le même {@code date_changed} (fréquent sur OpenMRS après une
 * migration de masse), une pagination sur la date seule saute silencieusement
 * les lignes restantes de ce timestamp (filtre strict {@code > date}) ou boucle
 * (filtre inclusif). Le tie-breaker {@code id} garantit un ordre TOTAL et une
 * progression sûre à l'intérieur d'un groupe de timestamps identiques.</p>
 *
 * <p>Le curseur est maintenu EN MÉMOIRE par le pipeline (avancé optimistement à
 * chaque page extraite) et rejoué depuis {@code sync_state} (watermark +
 * last_id) au démarrage / après un redémarrage. {@code lastId = 0} = début
 * (aucun id encore franchi), cohérent avec des PK strictement positives.</p>
 */
public record SyncCursor(LocalDateTime watermark, long lastId) {

    /** Curseur initial pour un watermark donné (aucun id encore franchi). */
    public static SyncCursor startingAt(LocalDateTime watermark) {
        return new SyncCursor(watermark, 0L);
    }
}
