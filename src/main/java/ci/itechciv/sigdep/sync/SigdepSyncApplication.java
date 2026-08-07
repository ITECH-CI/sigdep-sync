package ci.itechciv.sigdep.sync;

import ci.itechciv.sigdep.sync.config.SyncProperties;
import ci.itechciv.sigdep.sync.config.SyncPropertiesValidator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.validation.Validator;

@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(SyncProperties.class)
public class SigdepSyncApplication {

    public static void main(String[] args) {
        // Commande one-shot --requeue-dead-letter : on désactive le scheduler
        // AVANT de démarrer le contexte, pour qu'aucun cycle @Scheduled ne parte
        // pendant que DeadLetterRequeueRunner remet les lignes en file puis
        // arrête l'agent. La property est lue par le @ConditionalOnProperty de
        // SyncScheduler (le bean n'est alors pas créé du tout).
        SpringApplication app = new SpringApplication(SigdepSyncApplication.class);
        if (hasRequeueOption(args)) {
            app.setDefaultProperties(java.util.Map.of("sigdep.sync.scheduler.enabled", "false"));
        }
        app.run(args);
    }

    /** Vrai si les arguments contiennent {@code --requeue-dead-letter[=...]}. */
    private static boolean hasRequeueOption(String[] args) {
        for (String a : args) {
            if (a.equals("--requeue-dead-letter") || a.startsWith("--requeue-dead-letter=")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Doit s'appeler {@code configurationPropertiesValidator} : Spring Boot
     * détecte ce nom de bean et l'applique au binding des
     * {@code @ConfigurationProperties} (validation fail-fast des règles à
     * message riche de {@link SyncProperties}).
     */
    @Bean
    public static Validator configurationPropertiesValidator() {
        return new SyncPropertiesValidator();
    }
}
