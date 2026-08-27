package ci.itechciv.sigdep.sync.buffer;

import ci.itechciv.sigdep.contracts.EntityType;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Commande d'exploitation ONE-SHOT : remet en file les lignes bloquées en
 * {@code DEAD_LETTER} (rejets de validation ayant épuisé leurs tentatives),
 * puis ARRÊTE l'application sans démarrer de cycle de synchronisation.
 *
 * <p>Remplace la manipulation manuelle risquée qui consistait à monter le
 * volume du buffer dans un conteneur {@code alpine + sqlite3} et à lancer un
 * {@code UPDATE outbox SET status='PENDING'…} à la main. Ici l'action est
 * explicite, tracée dans les logs, et passe par le même verrou d'écriture que
 * le reste de l'agent (pas de course avec un cycle en vol).</p>
 *
 * <p>Usage (le conteneur lance le jar avec l'argument) :</p>
 * <pre>
 *   # toutes les entités
 *   java -jar sigdep-sync.jar --requeue-dead-letter
 *   # une seule entité
 *   java -jar sigdep-sync.jar --requeue-dead-letter=LAB_RESULTS
 * </pre>
 *
 * <p>À lancer une fois la cause du rejet corrigée côté hub (schéma élargi,
 * mapping complété, migration passée…). Les lignes repartent avec
 * {@code attempts=0} ; {@code last_error} est conservé comme trace.</p>
 *
 * <p>Sans l'argument, ce runner ne fait rien et l'agent démarre normalement.
 * {@code @Order} élevé pour passer APRÈS l'initialisation du schéma buffer.</p>
 */
@Component
@Order(1000)
public class DeadLetterRequeueRunner implements ApplicationRunner {

    /** Argument (préfixe {@code --}) qui déclenche la commande. */
    static final String OPTION = "requeue-dead-letter";
    /** Valeur signifiant « toutes les entités ». */
    static final String ALL = "ALL";

    private static final Logger log = LoggerFactory.getLogger(DeadLetterRequeueRunner.class);

    private final OutboxRepository outbox;
    private final ConfigurableApplicationContext context;

    public DeadLetterRequeueRunner(OutboxRepository outbox,
                                   ConfigurableApplicationContext context) {
        this.outbox = outbox;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return; // démarrage normal de l'agent
        }

        EntityType target = parseTarget(args.getOptionValues(OPTION));
        String scope = target == null ? "TOUTES les entités" : target.name();

        int before = outbox.deadLetterCount(target);
        if (before == 0) {
            log.info("--{} : aucune ligne DEAD_LETTER à remettre en file ({}).", OPTION, scope);
            shutdown(0);
            return;
        }

        int requeued = outbox.requeueDeadLetter(target);
        log.info("--{} : {} ligne(s) DEAD_LETTER remise(s) en file (status=PENDING, attempts=0) "
                + "pour {}. Elles repartiront au prochain cycle. Vérifier que la cause du rejet "
                + "a bien été corrigée côté hub.", OPTION, requeued, scope);
        shutdown(0);
    }

    /**
     * Résout l'entité cible depuis les valeurs de l'option. Absente / vide /
     * {@code ALL} → {@code null} (toutes). Une valeur non reconnue arrête la
     * commande avec un code non nul plutôt que d'agir sur un périmètre erroné.
     */
    private EntityType parseTarget(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        String raw = values.get(0);
        if (raw == null || raw.isBlank() || ALL.equalsIgnoreCase(raw.strip())) {
            return null;
        }
        try {
            return EntityType.valueOf(raw.strip().toUpperCase());
        } catch (IllegalArgumentException e) {
            log.error("--{}={} : entité inconnue. Valeurs possibles : {} (ou ALL / vide pour tout).",
                    OPTION, raw, java.util.Arrays.toString(EntityType.values()));
            shutdown(2);
            throw e; // atteint seulement si shutdown ne coupe pas le flux
        }
    }

    /** Ferme le contexte et termine le processus avec le code donné. */
    private void shutdown(int code) {
        int exit = SpringApplication.exit(context, () -> code);
        System.exit(exit);
    }
}
