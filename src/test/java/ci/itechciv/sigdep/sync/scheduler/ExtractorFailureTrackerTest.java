package ci.itechciv.sigdep.sync.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ci.itechciv.sigdep.contracts.EntityType;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Vérifie la politique de log de {@link ExtractorFailureTracker} en capturant
 * les événements Logback : niveaux (ERROR/WARN/INFO), présence/absence de stack
 * (throwable attaché), compteur de cycles, réinitialisation au rétablissement,
 * redéclenchement d'une stack au changement de cause et bloc global dédupliqué.
 */
class ExtractorFailureTrackerTest {

    private ExtractorFailureTracker tracker;
    private ListAppender<ILoggingEvent> appender;
    private Logger logbackLogger;

    @BeforeEach
    void setUp() {
        tracker = new ExtractorFailureTracker();
        logbackLogger = (Logger) LoggerFactory.getLogger(ExtractorFailureTracker.class);
        appender = new ListAppender<>();
        appender.start();
        logbackLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logbackLogger.detachAppender(appender);
    }

    private List<ILoggingEvent> events() {
        return appender.list;
    }

    private ILoggingEvent last() {
        return events().get(events().size() - 1);
    }

    private static RuntimeException err(String msg) {
        return new IllegalStateException(msg);
    }

    @Test
    @DisplayName("1er échec : ERROR avec stack (throwable attaché)")
    void firstFailure_isErrorWithStack() {
        tracker.recordFailure(EntityType.PATIENTS, err("boom"));

        assertThat(last().getLevel()).isEqualTo(Level.ERROR);
        assertThat(last().getThrowableProxy()).as("stack attachée").isNotNull();
    }

    @Test
    @DisplayName("Échecs suivants même cause : WARN de résumé sans stack, compteur croissant")
    void subsequentFailures_areWarnSummaryNoStack() {
        tracker.recordFailure(EntityType.PATIENTS, err("boom")); // 1 → ERROR
        tracker.recordFailure(EntityType.PATIENTS, err("boom")); // 2 → WARN
        tracker.recordFailure(EntityType.PATIENTS, err("boom")); // 3 → WARN

        assertThat(last().getLevel()).isEqualTo(Level.WARN);
        assertThat(last().getThrowableProxy()).as("pas de stack en résumé").isNull();
        assertThat(last().getFormattedMessage()).contains("3 cycles");
    }

    @Test
    @DisplayName("Tous les 20 échecs consécutifs : réémission d'une stack ERROR")
    void every20Failures_reemitsStack() {
        for (int i = 1; i <= 19; i++) {
            tracker.recordFailure(EntityType.PATIENTS, err("boom"));
        }
        // le 20e doit être ERROR + stack
        tracker.recordFailure(EntityType.PATIENTS, err("boom"));

        assertThat(last().getLevel()).isEqualTo(Level.ERROR);
        assertThat(last().getThrowableProxy()).isNotNull();
        assertThat(last().getFormattedMessage()).contains("20 cycles");
    }

    @Test
    @DisplayName("Rétablissement : INFO + remise à zéro du compteur")
    void recovery_logsInfoAndResets() {
        tracker.recordFailure(EntityType.PATIENTS, err("boom"));
        tracker.recordFailure(EntityType.PATIENTS, err("boom"));

        tracker.recordSuccess(EntityType.PATIENTS);
        assertThat(last().getLevel()).isEqualTo(Level.INFO);
        assertThat(last().getFormattedMessage()).contains("rétabli");

        // après reset, un nouvel échec repart d'une stack ERROR (compteur = 1)
        int events = events().size();
        tracker.recordFailure(EntityType.PATIENTS, err("boom"));
        assertThat(last().getLevel()).isEqualTo(Level.ERROR);
        assertThat(last().getThrowableProxy()).isNotNull();
        assertThat(events().size()).isEqualTo(events + 1);
    }

    @Test
    @DisplayName("recordSuccess sans échec préalable : aucun log")
    void successWithoutPriorFailure_isSilent() {
        tracker.recordSuccess(EntityType.VISITS);
        assertThat(events()).isEmpty();
    }

    @Test
    @DisplayName("Changement de cause : nouvelle stack ERROR (compteur remis à 1)")
    void causeChange_reemitsStack() {
        tracker.recordFailure(EntityType.PATIENTS, err("cause A")); // ERROR
        tracker.recordFailure(EntityType.PATIENTS, err("cause A")); // WARN
        tracker.recordFailure(EntityType.PATIENTS, err("cause B")); // cause change → ERROR

        assertThat(last().getLevel()).isEqualTo(Level.ERROR);
        assertThat(last().getThrowableProxy()).isNotNull();
    }

    @Test
    @DisplayName("Échec global dédupliqué : un seul bloc ERROR mentionnant le nombre d'extracteurs")
    void globalFailure_singleBlock() {
        tracker.recordGlobalFailure(11, err("config invalide"));

        assertThat(events()).hasSize(1);
        assertThat(last().getLevel()).isEqualTo(Level.ERROR);
        assertThat(last().getThrowableProxy()).isNotNull();
        assertThat(last().getFormattedMessage()).contains("11 extracteurs");
    }

    @Test
    @DisplayName("Échec global répété : WARN de résumé au 2e cycle, puis INFO au rétablissement")
    void globalFailure_thenRecovery() {
        tracker.recordGlobalFailure(11, err("config invalide")); // ERROR
        tracker.recordGlobalFailure(11, err("config invalide")); // WARN résumé
        assertThat(last().getLevel()).isEqualTo(Level.WARN);
        assertThat(last().getThrowableProxy()).isNull();

        tracker.recordGlobalRecovery();
        assertThat(last().getLevel()).isEqualTo(Level.INFO);
        assertThat(last().getFormattedMessage()).contains("global");
    }
}
