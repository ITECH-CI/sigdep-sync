package ci.itechciv.sigdep.sync.extractor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Normalisation des valeurs texte d'obs : OpenMRS stocke parfois des saisies
 * polluées par du padding d'espaces (ex. "ABOBO" + 300 espaces, ou une valeur
 * entièrement blanche). Non trimé, un tel value_text dépasse la largeur des
 * colonnes du hub (value too long for character varying(255)) et fait rejeter
 * le record en boucle (UPSERT_FAILED sur patients.birth_place).
 */
class ObsPivotTrimTest {

    @Test
    @DisplayName("Padding d'espaces retiré : \"  ABOBO  \" (313 car.) → \"ABOBO\"")
    void trimsSurroundingWhitespace() {
        String padded = "                  ABOBO" + " ".repeat(290);
        assertThat(padded.length()).isGreaterThan(255); // reproduit le cas prod
        assertThat(ObsPivot.trimToNull(padded)).isEqualTo("ABOBO");
    }

    @Test
    @DisplayName("Valeur entièrement blanche → null (obs sans valeur utile)")
    void blankBecomesNull() {
        assertThat(ObsPivot.trimToNull(" ".repeat(1988))).isNull();
        assertThat(ObsPivot.trimToNull("")).isNull();
        assertThat(ObsPivot.trimToNull("\t\n  ")).isNull();
    }

    @Test
    @DisplayName("null reste null ; valeur normale inchangée")
    void nullAndNormalUnchanged() {
        assertThat(ObsPivot.trimToNull(null)).isNull();
        assertThat(ObsPivot.trimToNull("Célibataire")).isEqualTo("Célibataire");
    }
}
