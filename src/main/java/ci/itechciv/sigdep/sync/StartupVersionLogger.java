package ci.itechciv.sigdep.sync;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Logue en INFO, sur une seule ligne au démarrage, l'identité du build de
 * l'agent : version, SHA git court, date de build. Objectif : pouvoir
 * identifier sans ambiguïté ce qui tourne sur un site donné (le tag Docker et
 * la version du JAR sont désormais alignés — cf. -Drevision + build-info).
 *
 * Les données viennent de {@link BuildProperties} (META-INF/build-info.properties
 * généré par spring-boot-maven-plugin:build-info). Injecté via
 * {@link ObjectProvider} pour rester silencieux (et ne pas planter) si le
 * build-info est absent — cas d'un lancement depuis l'IDE / les tests sans
 * packaging Maven complet.
 */
@Component
public class StartupVersionLogger {

    private static final Logger log = LoggerFactory.getLogger(StartupVersionLogger.class);

    private final ObjectProvider<BuildProperties> buildProperties;

    public StartupVersionLogger(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logVersion() {
        BuildProperties bp = buildProperties.getIfAvailable();
        if (bp == null) {
            log.info("sigdep-sync démarré — build-info indisponible (lancement hors packaging Maven)");
            return;
        }
        String sha = bp.get("git.sha");
        String built = bp.getTime() == null ? "unknown"
                : DateTimeFormatter.ISO_INSTANT.format(bp.getTime().atZone(ZoneOffset.UTC));
        log.info("sigdep-sync démarré — version={} sha={} build={}",
                bp.getVersion(),
                sha == null ? "unknown" : sha,
                built);
    }
}
