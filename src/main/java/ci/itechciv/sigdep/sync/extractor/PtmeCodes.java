package ci.itechciv.sigdep.sync.extractor;

/**
 * Decode dictionaries for PTME integer codes. Labels reverse-engineered
 * from the openmrs ptme module's JSP forms (web/module/*.jsp); raw codes
 * are kept in extraData alongside the decoded labels.
 */
final class PtmeCodes {

    private PtmeCodes() {}

    static String maritalStatus(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 1 -> "Célibataire";
            case 2 -> "Mariée";
            case 3 -> "Divorcée";
            case 4 -> "Veuve";
            case 5 -> "Concubinage";
            default -> null;
        };
    }

    static String spousalScreening(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 0 -> "Non";
            case 1 -> "Oui";
            case 2 -> "Ne sait pas";
            default -> null;
        };
    }

    /** Plain binary HIV result (0=NEG, 1=POS) — used for spousal/PCR/serology. */
    static String hivResult(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 0 -> "NEG";
            case 1 -> "POS";
            default -> null;
        };
    }

    static String arvStatusAtRegistering(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 0 -> "Positif sans ARV";
            case 1 -> "Positif déjà sous ARV";
            case 2 -> "Nouvellement diagnostiquée";
            default -> null;
        };
    }

    static String pregnancyOutcome(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 1 -> "À terme";
            case 2 -> "Prématurité";
            case 3 -> "Avortement";
            case 4 -> "Mort-né frais";
            case 5 -> "Mort-né macéré";
            default -> null;
        };
    }

    static String deliveryType(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 1 -> "Unique";
            case 2 -> "Multiple";
            default -> null;
        };
    }

    /** continuing_arv / continuing_ctx / continuing_inh tri-state. */
    static String yesNoNa(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 0 -> "Non";
            case 1 -> "Oui";
            case 2 -> "N/A";
            default -> null;
        };
    }

    static String followupResult(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 0 -> "Négatif sorti du programme";
            case 1 -> "Perdu de vue";
            case 2 -> "Décédé";
            case 3 -> "Positif adressé pour PEC";
            case 4 -> "Transféré";
            case 5 -> "Référé";
            default -> null;
        };
    }

    static String eatingType(Integer code) {
        if (code == null) return null;
        return switch (code) {
            case 1 -> "Allaitement Exclusif";
            case 2 -> "Alimentation de remplacement";
            case 3 -> "Alimentation de complément";
            case 4 -> "Autre";
            default -> null;
        };
    }
}
