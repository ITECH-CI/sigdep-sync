package ci.itechciv.sigdep.sync.buffer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Purge par rétention des lignes {@code DEAD_LETTER}, au démarrage de l'agent.
 *
 * <p>Motivation (protection des données) : une DEAD_LETTER conserve son
 * {@code payload_json} — des données de santé nominatives jamais transmises au
 * hub — <b>sans limite de durée</b>. Ce runner supprime celles qui sont en
 * DEAD_LETTER depuis plus de {@code retention-days} jours, effaçant la donnée
 * au repos du poste de site.
 *
 * <p>Sûreté de la reprise : une DEAD_LETTER est un enregistrement rejeté en
 * validation par le hub (donnée invalide). Il ne sera accepté qu'après
 * correction à la source, qui incrémente {@code date_changed} et le fait
 * ré-extraire (filtre {@code changed > watermark}) — recréant une ligne PENDING
 * neuve. Supprimer la ligne ne retire donc que la copie au repos d'une donnée
 * invalide, pas la capacité de resynchroniser une fois corrigée. Le délai de
 * rétention laisse le temps d'une reprise manuelle
 * ({@code --requeue-dead-letter}) avant l'effacement.
 *
 * <p>Contrairement à {@link DeadLetterRequeueRunner}, ce runner s'exécute
 * <b>automatiquement</b> à chaque démarrage (pas d'option) et NE coupe PAS
 * l'application : la purge est une tâche d'hygiène, l'agent poursuit son
 * démarrage normal. {@code @Order} élevé pour passer APRÈS l'initialisation du
 * schéma buffer (colonne {@code dead_lettered_at} + index). Rétention ≤ 0
 * désactive la purge (sécurité).
 */
@Component
@Order(1002)
public class DeadLetterPurgeRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterPurgeRunner.class);

    private final OutboxRepository outbox;
    private final int retentionDays;

    public DeadLetterPurgeRunner(
            OutboxRepository outbox,
            @Value("${sigdep.sync.dead-letter-retention-days:60}") int retentionDays) {
        this.outbox = outbox;
        this.retentionDays = retentionDays;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Ne pas interférer avec les commandes one-shot (elles arrêtent l'app
        // elles-mêmes ; inutile de purger dans ce cas).
        if (args.containsOption(DeadLetterRequeueRunner.OPTION)) {
            return;
        }
        if (retentionDays <= 0) {
            log.info("Purge DEAD_LETTER désactivée (dead-letter-retention-days={}).", retentionDays);
            return;
        }
        int purged = outbox.purgeExpiredDeadLetters(retentionDays);
        if (purged > 0) {
            log.warn("Purge DEAD_LETTER : {} ligne(s) supprimée(s) (> {} jours en DEAD_LETTER). "
                    + "Payloads (données de santé) effacés du poste. Ces enregistrements "
                    + "resynchroniseront si leur fiche source est corrigée (date_changed).",
                    purged, retentionDays);
        } else {
            log.debug("Purge DEAD_LETTER : aucune ligne au-delà de {} jours.", retentionDays);
        }
    }
}
