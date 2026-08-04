package ci.itechciv.sigdep.sync.extractor;

import ci.itechciv.sigdep.contracts.EntityType;
import java.util.List;

public interface DataExtractor {

    EntityType getEntityType();

    String getSourceTable();

    String getWatermarkColumn();

    boolean isEnabled();

    /**
     * Extrait au plus {@code batchSize} enregistrements strictement APRÈS le
     * curseur keyset {@code cursor} — pagination composite {@code (date, id)}
     * pour ne jamais sauter ni rejouer une ligne au sein d'un groupe de
     * timestamps identiques. Les enregistrements retournés portent leur
     * {@code sourceId} (PK source) afin que le curseur puisse avancer sur
     * {@code (max date, max id)}.
     */
    List<CanonicalRecord> extract(SyncCursor cursor, int batchSize);
}
