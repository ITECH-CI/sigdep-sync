package ci.itechciv.sigdep.sync.extractor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Normalisation des valeurs texte d'obs : OpenMRS stocke des saisies polluées
 * par des espaces en bordure ET à l'intérieur (ex. "<290 espaces>ABOBO<espaces>
 * ABOBO" = 295 caractères pour "ABOBO ABOBO"). Non normalisé, ce value_text
 * dépasse character varying(255) sur patients.birth_place côté hub et fait
 * rejeter le record en boucle (UPSERT_FAILED). Un simple strip() des bords ne
 * suffit pas — d'où le collapse des blancs internes.
 */
class ObsPivotTrimTest {

    @Test
    @DisplayName("Padding en bordure retiré : \"  ABOBO  \" → \"ABOBO\"")
    void trimsSurroundingWhitespace() {
        String padded = " ".repeat(18) + "ABOBO" + " ".repeat(290);
        assertThat(padded.length()).isGreaterThan(255);
        assertThat(ObsPivot.normalizeText(padded)).isEqualTo("ABOBO");
    }

    @Test
    @DisplayName("Bruit d'espaces INTERNE collapsé : \"…ABOBO…ABOBO\" (295 car.) → \"ABOBO ABOBO\"")
    void collapsesInnerWhitespace() {
        // Cas prod exact : ABOBO, un gros paquet d'espaces INTERNES, ABOBO.
        // Après strip() des bords il reste 5 + 285 + 5 = 295 car. > 255 → le
        // simple trim échouait ; le collapse des blancs internes règle le cas.
        String noisy = "ABOBO" + " ".repeat(285) + "ABOBO";
        assertThat(noisy.strip().length()).isEqualTo(295);
        assertThat(noisy.strip().length()).isGreaterThan(255); // strip() seul échoue
        assertThat(ObsPivot.normalizeText(noisy)).isEqualTo("ABOBO ABOBO");
    }

    @Test
    @DisplayName("Tabs et sauts de ligne aussi collapsés")
    void collapsesTabsAndNewlines() {
        assertThat(ObsPivot.normalizeText("A\t\tB\n\nC")).isEqualTo("A B C");
    }

    @Test
    @DisplayName("Valeur trop longue tronquée à 255")
    void truncatesToMax() {
        String longText = "X".repeat(400);
        assertThat(ObsPivot.normalizeText(longText)).hasSize(255);
    }

    @Test
    @DisplayName("Valeur entièrement blanche → null")
    void blankBecomesNull() {
        assertThat(ObsPivot.normalizeText(" ".repeat(1988))).isNull();
        assertThat(ObsPivot.normalizeText("")).isNull();
        assertThat(ObsPivot.normalizeText("\t\n  ")).isNull();
    }

    @Test
    @DisplayName("null reste null ; valeur normale inchangée")
    void nullAndNormalUnchanged() {
        assertThat(ObsPivot.normalizeText(null)).isNull();
        assertThat(ObsPivot.normalizeText("Célibataire")).isEqualTo("Célibataire");
    }
}
