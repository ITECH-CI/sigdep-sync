package ci.itechciv.sigdep.sync.config;

import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

/**
 * Validateur Spring des règles à message riche de {@link SyncProperties}
 * (schéma d'URL, clé API ≠ 'changeme', préfixe jdbc:) — celles qui doivent
 * nommer la variable d'environnement fautive et masquer la clé API, ce que les
 * annotations Jakarta ne permettent pas d'exprimer.
 *
 * Déclaré sous le nom de bean {@code configurationPropertiesValidator} pour
 * que Spring Boot l'applique pendant le binding de {@code @ConfigurationProperties}.
 * Un échec empêche le contexte de démarrer → l'agent se termine avec un code de
 * sortie non nul plutôt que d'échouer silencieusement à chaque cycle.
 */
public class SyncPropertiesValidator implements Validator {

    @Override
    public boolean supports(Class<?> clazz) {
        return SyncProperties.class.isAssignableFrom(clazz);
    }

    @Override
    public void validate(Object target, Errors errors) {
        if (!(target instanceof SyncProperties props)) {
            return;
        }
        for (String message : props.validationErrors()) {
            errors.reject("sigdep.sync.invalid", message);
        }
    }
}
