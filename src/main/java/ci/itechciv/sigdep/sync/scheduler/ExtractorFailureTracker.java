package ci.itechciv.sigdep.sync.scheduler;

import ci.itechciv.sigdep.contracts.EntityType;
import java.util.EnumMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Réduit le bruit de log des échecs d'extraction récurrents.
 *
 * Sans ça, une erreur de configuration globale fait échouer les 11 extracteurs
 * et chacun logue une stack complète à CHAQUE cycle (toutes les 15 min) → 11
 * stacks identiques en boucle, illisible.
 *
 * Politique par extracteur (compteur d'échecs CONSÉCUTIFS pour une même cause) :
 * <ul>
 *   <li>1er échec (ou changement de cause) : stack complète en ERROR ;</li>
 *   <li>échecs suivants même cause : une ligne WARN de résumé (« échoue depuis
 *       N cycles ») ;</li>
 *   <li>tous les {@value #FULL_STACK_EVERY} échecs consécutifs : réémission
 *       d'une stack complète en ERROR (la cause reste visible dans les logs
 *       récents) ;</li>
 *   <li>au rétablissement : une ligne INFO et remise à zéro du compteur.</li>
 * </ul>
 *
 * La « cause » est identifiée par une signature {classe d'exception racine +
 * message}, de sorte qu'un changement de nature d'erreur redéclenche une stack.
 *
 * Non thread-safe : appelé séquentiellement depuis l'unique thread du
 * scheduler ({@code scheduling-1}).
 */
@Component
public class ExtractorFailureTracker {

    private static final Logger log = LoggerFactory.getLogger(ExtractorFailureTracker.class);

    /** Réémission d'une stack complète tous les N échecs consécutifs. */
    static final int FULL_STACK_EVERY = 20;

    /** État par extracteur. */
    private final Map<EntityType, State> perEntity = new EnumMap<>(EntityType.class);

    /** État de l'échec GLOBAL dédupliqué (tous les extracteurs, même cause). */
    private final State global = new State();

    private static final class State {
        String causeSignature;
        int consecutiveFailures;
    }

    /**
     * Signature stable d'une cause d'échec : classe de l'exception racine + son
     * message. Deux échecs de même signature sont « même cause ».
     */
    static String signatureOf(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getName() + ": " + root.getMessage();
    }

    /**
     * Enregistre un échec par extracteur et logue selon la politique.
     * Retourne le nombre d'échecs consécutifs.
     */
    public int recordFailure(EntityType entityType, Throwable error) {
        State s = perEntity.computeIfAbsent(entityType, k -> new State());
        return apply(s, error,
                () -> log.error("Extractor {} failed (continuing with next): {}",
                        entityType, error.getMessage(), error),
                n -> log.error("Extractor {} échoue depuis {} cycles consécutifs (même cause)"
                                + " — rappel de la trace : {}",
                        entityType, n, error.getMessage(), error),
                n -> log.warn("Extractor {} échoue depuis {} cycles consécutifs (même cause) : {}",
                        entityType, n, error.getMessage()));
    }

    /**
     * Signale la réussite d'un extracteur : s'il était en échec, logue le
     * rétablissement en INFO et remet le compteur à zéro. No-op sinon.
     */
    public void recordSuccess(EntityType entityType) {
        State s = perEntity.get(entityType);
        if (s != null && s.consecutiveFailures > 0) {
            log.info("Extractor {} rétabli après {} cycle(s) en échec",
                    entityType, s.consecutiveFailures);
            reset(s);
        }
    }

    /**
     * Échec GLOBAL dédupliqué : tous les extracteurs du cycle ont échoué sur la
     * même cause (probable erreur de configuration). Un SEUL bloc de log au lieu
     * d'un par extracteur.
     *
     * @param failingCount nombre d'extracteurs concernés
     * @param error        l'exception commune
     */
    public void recordGlobalFailure(int failingCount, Throwable error) {
        apply(global, error,
                () -> log.error("Les {} extracteurs échouent tous sur la même cause"
                                + " (probable erreur de configuration globale) : {}",
                        failingCount, error.getMessage(), error),
                n -> log.error("Les {} extracteurs échouent tous sur la même cause depuis {} cycles"
                                + " — rappel de la trace : {}",
                        failingCount, n, error.getMessage(), error),
                n -> log.warn("Les {} extracteurs échouent tous sur la même cause depuis {} cycles : {}",
                        failingCount, n, error.getMessage()));
    }

    /** Rétablissement après un échec global (le cycle courant n'est pas 100 % en échec). */
    public void recordGlobalRecovery() {
        if (global.consecutiveFailures > 0) {
            log.info("Sortie de l'échec global après {} cycle(s)", global.consecutiveFailures);
            reset(global);
        }
    }

    // --- logique commune -----------------------------------------------------

    /**
     * Applique la politique de log à un état donné et retourne le compteur
     * consécutif. {@code onFirst} : 1er échec / changement de cause (ERROR +
     * stack). {@code onPeriodic} : multiple de FULL_STACK_EVERY (ERROR + stack).
     * {@code onSummary} : cas courant (WARN sans stack).
     */
    private int apply(State s, Throwable error,
                      Runnable onFirst,
                      java.util.function.IntConsumer onPeriodic,
                      java.util.function.IntConsumer onSummary) {
        String signature = signatureOf(error);
        if (!signature.equals(s.causeSignature)) {
            s.causeSignature = signature;
            s.consecutiveFailures = 1;
            onFirst.run();
            return 1;
        }
        s.consecutiveFailures++;
        if (s.consecutiveFailures % FULL_STACK_EVERY == 0) {
            onPeriodic.accept(s.consecutiveFailures);
        } else {
            onSummary.accept(s.consecutiveFailures);
        }
        return s.consecutiveFailures;
    }

    private static void reset(State s) {
        s.causeSignature = null;
        s.consecutiveFailures = 0;
    }
}
