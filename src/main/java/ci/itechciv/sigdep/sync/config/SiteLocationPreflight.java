package ci.itechciv.sigdep.sync.config;

import jakarta.annotation.PostConstruct;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Préflight de cohérence du SITE, au démarrage de l'agent.
 *
 * <p>Motivation (incident réel) : un administrateur a saisi un mauvais
 * {@code SIGDEP_SITE_CODE} dans la configuration de l'agent ; comme le code
 * était syntaxiquement valide et que le hub ne recoupait rien, les données
 * d'un établissement ont été attribuées à un autre. {@link SiteCodeValidator}
 * ne vérifie que la présence/forme du code, jamais qu'il correspond à la base
 * OpenMRS réellement branchée.
 *
 * <p>Ce préflight établit le « vrai » site de l'instance à partir de la base
 * OpenMRS locale, en recoupant TROIS sources indépendantes, toutes résolues
 * vers l'{@code uuid} de la {@code location} :
 * <ol>
 *   <li>la {@code global_property 'default_location'} (site que l'installation
 *       se déclare — un nom) et/ou
 *       {@code 'generateurid.defaultLocation'} (un id de location) ;</li>
 *   <li>le {@code location_id} distinct porté par {@code patient_identifier}
 *       (lieu d'émission des identifiants) ;</li>
 *   <li>le {@code location_id} distinct porté par {@code encounter}
 *       (lieu des actes de soin).</li>
 * </ol>
 *
 * <p>Règles :
 * <ul>
 *   <li>si les sources <b>présentes</b> désignent <b>plusieurs</b> uuids
 *       distincts → {@link IllegalStateException} : l'agent refuse de démarrer
 *       (base multi-sites ou mal configurée) ;</li>
 *   <li>si <b>aucune</b> source n'est disponible (base vide, pas de
 *       global_property) → avertissement, l'agent démarre quand même : rien à
 *       mal attribuer tant qu'aucune donnée n'est poussée, et le contrôle côté
 *       hub prendra le relais dès le premier lot ;</li>
 *   <li>si exactement <b>un</b> uuid → il est mémorisé et joint à chaque lot
 *       ({@link ci.itechciv.sigdep.contracts.SyncBatchRequest#locationUuid()}),
 *       ce qui permet au hub de rejeter tout lot dont le site déclaré ne
 *       correspond pas à ce site réel.</li>
 * </ul>
 *
 * <p>Une source de global_property <b>absente</b> n'est pas une divergence :
 * elle est simplement ignorée ; seules les sources effectivement présentes
 * doivent concorder. Le préflight n'essaie PAS de traduire l'uuid en code
 * (l'uuid n'est pas toujours dérivable du code : padding {@code S…}, séquence
 * {@code L…} ou vrai uuid selon les sites) — la traduction uuid → site est du
 * ressort du hub, seul détenteur du référentiel {@code core.sites.source_uuid}.
 */
@Component
public class SiteLocationPreflight {

    private static final Logger log = LoggerFactory.getLogger(SiteLocationPreflight.class);

    /**
     * Recoupe les trois sources et résout chacune vers {@code location.uuid}.
     * {@code src} nomme la source pour le message d'erreur ; l'agrégat écarte
     * naturellement les sources absentes (aucune ligne renvoyée).
     */
    private static final String SQL_DISTINCT_LOCATIONS = """
            SELECT src, l.uuid
            FROM (
                SELECT 'default_location'          AS src, l.location_id
                FROM location l
                JOIN global_property gp
                  ON gp.property = 'default_location'
                 AND gp.property_value = l.name
                UNION
                SELECT 'generateurid.defaultLocation' AS src, l.location_id
                FROM location l
                JOIN global_property gp
                  ON gp.property = 'generateurid.defaultLocation'
                 AND gp.property_value REGEXP '^[0-9]+$'
                 AND CAST(gp.property_value AS UNSIGNED) = l.location_id
                UNION
                SELECT 'patient_identifier' AS src, location_id
                FROM patient_identifier
                WHERE voided = 0 AND location_id IS NOT NULL
                UNION
                SELECT 'encounter' AS src, location_id
                FROM encounter
                WHERE voided = 0 AND location_id IS NOT NULL
            ) s
            JOIN location l ON l.location_id = s.location_id
            GROUP BY src, l.uuid
            """;

    private final JdbcTemplate localDb;

    /** uuid de la location réelle de l'instance, ou {@code null} si base vide. */
    private volatile String resolvedLocationUuid;

    public SiteLocationPreflight(@Qualifier("localJdbcTemplate") JdbcTemplate localDb) {
        this.localDb = localDb;
    }

    /**
     * Fabrique un préflight déjà résolu, sans accès base — pour les tests qui
     * n'exercent pas la résolution (le préflight n'est pas le sujet du test).
     * {@code uuid} {@code null} reproduit un agent sans location connue.
     */
    public static SiteLocationPreflight resolvedTo(String uuid) {
        SiteLocationPreflight p = new SiteLocationPreflight((JdbcTemplate) null);
        p.resolvedLocationUuid = uuid;
        return p;
    }

    @PostConstruct
    public void validate() {
        // src → uuid pour chaque source présente (peut contenir plusieurs
        // lignes par source si une source portait plusieurs locations).
        List<String[]> rows = localDb.query(
                SQL_DISTINCT_LOCATIONS,
                (rs, i) -> new String[] {rs.getString("src"), rs.getString("uuid")});

        Set<String> distinctUuids = rows.stream()
                .map(r -> r[1])
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));

        if (distinctUuids.isEmpty()) {
            log.warn("Préflight site : la base OpenMRS locale ne porte aucune location "
                    + "(base vide ou pas encore alimentée). L'agent démarre ; le site "
                    + "réel ne pourra être joint aux lots qu'une fois des données présentes. "
                    + "Le hub vérifiera l'attribution à la réception.");
            return;
        }

        if (distinctUuids.size() > 1) {
            // Regroupe par uuid les sources qui le désignent, pour un message lisible.
            Map<String, String> sourcesByUuid = new LinkedHashMap<>();
            for (String[] r : rows) {
                sourcesByUuid.merge(r[1], r[0], (a, b) -> a + ", " + b);
            }
            String detail = sourcesByUuid.entrySet().stream()
                    .map(e -> "  - " + e.getKey() + "  (vu par : " + e.getValue() + ")")
                    .collect(Collectors.joining("\n"));
            throw new IllegalStateException(
                    "Préflight site : la base OpenMRS locale contient PLUSIEURS sites distincts "
                            + "(" + distinctUuids.size() + " locations), ce qui indique une instance "
                            + "mal configurée ou multi-établissements. Une instance SIGDEP-3 doit "
                            + "correspondre à UN seul site. Locations trouvées :\n" + detail
                            + "\nCorrigez la base source avant de démarrer l'agent.");
        }

        this.resolvedLocationUuid = distinctUuids.iterator().next();
        log.info("Préflight site OK : location unique '{}' (recoupée sur {} source(s)). "
                        + "Cet uuid sera joint à chaque lot pour vérification côté hub.",
                resolvedLocationUuid, rows.size());
    }

    /**
     * uuid de la location réelle de l'instance, à joindre à chaque
     * {@link ci.itechciv.sigdep.contracts.SyncBatchRequest}. {@code null}
     * uniquement si la base était vide au démarrage (préflight non bloquant).
     */
    public String resolvedLocationUuid() {
        return resolvedLocationUuid;
    }
}
