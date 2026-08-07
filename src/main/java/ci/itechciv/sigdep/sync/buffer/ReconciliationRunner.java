package ci.itechciv.sigdep.sync.buffer;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.sync.buffer.OutboxRepository.OutboxCounts;
import ci.itechciv.sigdep.sync.extractor.DataExtractor;
import ci.itechciv.sigdep.sync.state.SyncStateRepository;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Commande d'exploitation ONE-SHOT : produit un RAPPORT DE RÉCONCILIATION
 * local, entité par entité, puis arrête l'agent sans démarrer de cycle.
 *
 * <p>Pour chaque {@link DataExtractor} enregistré, le rapport donne :</p>
 * <ul>
 *   <li>le nombre de lignes dans la TABLE SOURCE OpenMRS ({@code getSourceTable()},
 *       COUNT(*) brut — voir l'avertissement ci-dessous) ;</li>
 *   <li>les compteurs de l'OUTBOX par statut : SENT / PENDING / REJECTED /
 *       DEAD_LETTER ;</li>
 *   <li>le WATERMARK persistant ({@code sync_state.last_watermark}).</li>
 * </ul>
 *
 * <p><b>Le compteur source est APPROXIMATIF</b> : c'est un COUNT(*) brut de la
 * table, sans les filtres propres à chaque extracteur (type d'encounter,
 * exclusion des lignes non pertinentes, jointures PTME…) et SANS exclure les
 * lignes {@code voided}. Il donne un ORDRE DE GRANDEUR pour repérer une entité
 * anormalement en retard ou jamais synchronisée, pas un rapprochement ligne à
 * ligne. Un écart source ≫ SENT sur une entité qui devrait être à jour est le
 * signal à investiguer.</p>
 *
 * <p>Usage : {@code java -jar sigdep-sync.jar --reconcile}. Comme
 * {@code --requeue-dead-letter}, le scheduler est désactivé pour ce lancement
 * (aucun cycle en concurrence) et l'agent s'arrête à la fin du rapport.</p>
 */
@Component
@Order(1001)
public class ReconciliationRunner implements ApplicationRunner {

    static final String OPTION = "reconcile";

    private static final Logger log = LoggerFactory.getLogger(ReconciliationRunner.class);

    private final List<DataExtractor> extractors;
    private final OutboxRepository outbox;
    private final SyncStateRepository syncState;
    private final JdbcTemplate localDb;
    private final ConfigurableApplicationContext context;

    public ReconciliationRunner(List<DataExtractor> extractors,
                                OutboxRepository outbox,
                                SyncStateRepository syncState,
                                @Qualifier("localJdbcTemplate") JdbcTemplate localDb,
                                ConfigurableApplicationContext context) {
        this.extractors = extractors;
        this.outbox = outbox;
        this.syncState = syncState;
        this.localDb = localDb;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption(OPTION)) {
            return;
        }

        Map<String, OutboxCounts> counts = outbox.outboxCountsByEntity();

        StringBuilder sb = new StringBuilder();
        sb.append("Rapport de réconciliation (compteurs LOCAUX — voir l'avertissement sur "
                + "le compteur source approximatif) :\n");
        sb.append(String.format("%-22s %12s %10s %9s %9s %11s  %-19s%n",
                "ENTITY_TYPE", "SOURCE(~)", "SENT", "PENDING", "REJECTED", "DEAD_LETTER", "WATERMARK"));
        sb.append("-".repeat(110)).append('\n');

        for (DataExtractor x : extractors) {
            EntityType type = x.getEntityType();
            OutboxCounts c = counts.getOrDefault(type.name(),
                    new OutboxCounts(0, 0, 0, 0));
            String source = countSource(x);
            String watermark = syncState.getWatermark(type)
                    .map(Object::toString).orElse("—");

            sb.append(String.format("%-22s %12s %10d %9d %9d %11d  %-19s%n",
                    type.name(), source, c.sent(), c.pending(), c.rejected(), c.dead(), watermark));
        }

        log.info("{}", sb);
        shutdown();
    }

    /**
     * COUNT(*) brut de la table source. Renvoie "n/a" (plutôt que de planter le
     * rapport) si la table est absente ou le compte échoue — le rapport reste
     * utile pour les autres entités.
     */
    private String countSource(DataExtractor x) {
        String table = x.getSourceTable();
        if (table == null || table.isBlank()) {
            return "n/a";
        }
        try {
            // getSourceTable() est une constante interne à l'extracteur (jamais
            // une entrée utilisateur) → pas d'injection possible.
            Long n = localDb.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
            return n == null ? "n/a" : Long.toString(n);
        } catch (RuntimeException e) {
            log.warn("Comptage source impossible pour {} (table {}) : {}",
                    x.getEntityType(), table, e.getMessage());
            return "n/a";
        }
    }

    private void shutdown() {
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
