package ci.itechciv.sigdep.sync.extractor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import ci.itechciv.sigdep.contracts.dto.PatientDto.IdentifierDto;
import ci.itechciv.sigdep.sync.config.SyncProperties;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * SYNC-11 : un type d'identifiant OpenMRS absent de {@code identifier-mapping}
 * ne doit plus être exclu SILENCIEUSEMENT. On vérifie que
 * {@link PatientExtractor} loggue un WARN nommant le type et le nombre de
 * lignes concernées, une seule fois (dédup) même sur plusieurs pages.
 */
class PatientUnmappedIdentifierTest {

    private static final UUID PATIENT = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private JdbcTemplate localDb;
    private PatientExtractor extractor;
    private ListAppender<ILoggingEvent> logs;
    private Logger extractorLogger;

    @BeforeEach
    void setUp() {
        localDb = mock(JdbcTemplate.class);
        // mapping ne connaît QUE "CODE ARV officiel" ; "Ancien code local" est non mappé.
        SyncProperties props = props(Map.of("CODE ARV officiel", "CODE_ARV"));
        extractor = new PatientExtractor(localDb, props);

        extractorLogger = (Logger) LoggerFactory.getLogger(PatientExtractor.class);
        logs = new ListAppender<>();
        logs.start();
        extractorLogger.addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        extractorLogger.detachAppender(logs);
    }

    @Test
    @DisplayName("Type d'identifiant non mappé : WARN une fois, identifiant exclu du push")
    void unmappedIdentifierType_warnsOnceAndIsExcluded() throws Exception {
        // Le mock exécute le RowCallbackHandler sur deux lignes : une mappée,
        // une non mappée pour le MÊME patient.
        stubIdentifierQuery();

        // fetchIdentifiers est privé : on l'invoque via ReflectionTestUtils avec
        // une liste d'une ligne patient (seul le uuid compte pour la requête).
        Object patientRow = newPatientRow();
        @SuppressWarnings("unchecked")
        Map<UUID, List<IdentifierDto>> out1 = (Map<UUID, List<IdentifierDto>>)
                ReflectionTestUtils.invokeMethod(extractor, "fetchIdentifiers", List.of(patientRow));

        // Seul l'identifiant mappé remonte ; le non mappé est exclu.
        assertThat(out1).containsKey(PATIENT);
        assertThat(out1.get(PATIENT)).hasSize(1);
        assertThat(out1.get(PATIENT).get(0).typeCode()).isEqualTo("CODE_ARV");

        // Un WARN nommant le type non mappé et le compte de lignes.
        List<ILoggingEvent> warns = logs.list.stream()
                .filter(e -> e.getLevel() == Level.WARN).toList();
        assertThat(warns).hasSize(1);
        assertThat(warns.get(0).getFormattedMessage())
                .contains("Ancien code local")
                .contains("identifier-mapping");

        // Deuxième page : même type non mappé → PAS de nouveau WARN (dédup).
        ReflectionTestUtils.invokeMethod(extractor, "fetchIdentifiers", List.of(patientRow));
        long warnCount = logs.list.stream().filter(e -> e.getLevel() == Level.WARN).count();
        assertThat(warnCount).as("le type non mappé n'est signalé qu'une seule fois").isEqualTo(1);
    }

    /** Programme le mock pour rejouer le callback sur 2 lignes (1 mappée, 1 non mappée). */
    private void stubIdentifierQuery() {
        doAnswer(inv -> {
            RowCallbackHandler h = inv.getArgument(1);
            h.processRow(identifierRow("CODE ARV officiel", "ARV-001", true));
            h.processRow(identifierRow("Ancien code local", "OLD-999", false));
            return null;
        }).when(localDb).query(anyString(), any(RowCallbackHandler.class), (Object[]) any());
    }

    private static ResultSet identifierRow(String typeName, String value, boolean preferred)
            throws Exception {
        ResultSet rs = mock(ResultSet.class);
        Mockito.when(rs.getString("type_name")).thenReturn(typeName);
        Mockito.when(rs.getString("patient_uuid")).thenReturn(PATIENT.toString());
        Mockito.when(rs.getString("identifier_value")).thenReturn(value);
        Mockito.when(rs.getBoolean("is_preferred")).thenReturn(preferred);
        return rs;
    }

    /** Construit un PatientRow (record privé) via réflexion — seul uuid est lu ici. */
    private static Object newPatientRow() {
        Class<?> rowClass;
        try {
            rowClass = Class.forName(
                    "ci.itechciv.sigdep.sync.extractor.PatientExtractor$PatientRow");
            var ctor = rowClass.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            // (personId, uuid, gender, birthdate, birthdateEstimated, voided, changed)
            return ctor.newInstance(1L, PATIENT, "M", null, false, false,
                    LocalDateTime.of(2026, 1, 1, 0, 0));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SyncProperties props(Map<String, String> identifierMapping) {
        return new SyncProperties(
                "SITE", "https://hub.example.org", 500, 15,
                LocalDateTime.of(1970, 1, 1, 0, 0),
                identifierMapping, java.util.Map.of(), "api-key-1234",
                "jdbc:mysql://localhost/openmrs",
                new SyncProperties.Backfill(false, 30, "0 0 22 * * MON-FRI"),
                3, 2, new SyncProperties.Http(10, 60, 60, 0, 500L, 30000L));
    }
}
