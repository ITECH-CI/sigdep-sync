package ci.itechciv.sigdep.sync.extractor;

import ci.itechciv.sigdep.contracts.EntityType;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Un enregistrement extrait, prêt à mettre en outbox.
 *
 * {@code sourceId} = clé numérique de la ligne source (ex.
 * hiv_screening_id), utilisée comme tie-breaker de keyset pour les entités
 * dont le watermark temporel n'a qu'une granularité JOUR. {@code null} pour
 * les entités qui n'en ont pas besoin (watermark fin via date_changed).
 */
public record CanonicalRecord(
        EntityType entityType,
        UUID sourceUuid,
        LocalDateTime watermark,
        Long sourceId,
        Object payload
) {
    /** Surcharge de compatibilité : record sans keyset id (sourceId = null). */
    public CanonicalRecord(EntityType entityType, UUID sourceUuid,
                           LocalDateTime watermark, Object payload) {
        this(entityType, sourceUuid, watermark, null, payload);
    }
}
