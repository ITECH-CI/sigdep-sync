package ci.itechciv.sigdep.sync.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.Validator;

/**
 * Vérifie le fail-fast de la validation de {@link SyncProperties} au démarrage
 * du contexte : une configuration valide démarre, une configuration invalide
 * empêche le contexte de se lever et le message nomme la variable
 * d'environnement fautive.
 *
 * On utilise {@link ApplicationContextRunner} (pas @SpringBootTest) pour
 * n'assembler que le binding + la validation des propriétés, sans démarrer les
 * DataSources, le scheduler ni le client HTTP.
 */
class SyncPropertiesValidationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TestConfig.class)
            // Valeurs de base valides ; chaque test surcharge ce qu'il teste.
            .withPropertyValues(
                    "sigdep.sync.site-code=CI001",
                    "sigdep.sync.central-api-url=https://sigdephub.itech-civ.org",
                    "sigdep.sync.api-key=abcd1234-real-key",
                    "sigdep.sync.local-db-url=jdbc:mysql://localhost:3306/openmrs",
                    "sigdep.sync.batch-size=500",
                    "sigdep.sync.sync-interval-minutes=15",
                    "sigdep.sync.max-reject-attempts=10",
                    "sigdep.sync.pipeline-depth=2");

    @Test
    @DisplayName("Configuration valide : le contexte démarre")
    void validConfig_startsContext() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SyncProperties.class).centralApiBaseUrl())
                    .isEqualTo("https://sigdephub.itech-civ.org");
        });
    }

    @Test
    @DisplayName("URL du hub sans schéma : échec, message nommant SIGDEP_CENTRAL_API_URL")
    void urlWithoutScheme_failsWithNamedVariable() {
        runner.withPropertyValues("sigdep.sync.central-api-url=sigdephub.itech-civ.org")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCauseMessage(context)).contains("SIGDEP_CENTRAL_API_URL");
                });
    }

    @Test
    @DisplayName("Clé API 'changeme' : échec du démarrage")
    void apiKeyChangeme_fails() {
        runner.withPropertyValues("sigdep.sync.api-key=changeme")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCauseMessage(context)).contains("SIGDEP_API_KEY");
                });
    }

    @Test
    @DisplayName("URL JDBC locale sans préfixe jdbc: : échec, message nommant SIGDEP_LOCAL_DB_URL")
    void localDbUrlWithoutJdbcPrefix_fails() {
        runner.withPropertyValues("sigdep.sync.local-db-url=mysql://localhost:3306/openmrs")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(rootCauseMessage(context)).contains("SIGDEP_LOCAL_DB_URL");
                });
    }

    @Test
    @DisplayName("batch-size = 0 : échec")
    void batchSizeZero_fails() {
        runner.withPropertyValues("sigdep.sync.batch-size=0")
                .run(context -> assertThat(context).hasFailed());
    }

    private static String rootCauseMessage(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
        Throwable failure = context.getStartupFailure();
        assertThat(failure).isNotNull();
        StringBuilder sb = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append('\n');
            }
        }
        return sb.toString();
    }

    @Configuration
    @EnableConfigurationProperties(SyncProperties.class)
    static class TestConfig {
        @Bean
        public static Validator configurationPropertiesValidator() {
            return new SyncPropertiesValidator();
        }
    }
}
