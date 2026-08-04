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
        SpringApplication.run(SigdepSyncApplication.class, args);
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
