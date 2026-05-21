package ci.itechciv.sigdep.sync.buffer;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.contracts.dto.ClosureDto;
import ci.itechciv.sigdep.contracts.dto.DispensationDto;
import ci.itechciv.sigdep.contracts.dto.LabResultDto;
import ci.itechciv.sigdep.contracts.dto.PatientDto;
import ci.itechciv.sigdep.contracts.dto.PtmeChildDto;
import ci.itechciv.sigdep.contracts.dto.PtmeChildVisitDto;
import ci.itechciv.sigdep.contracts.dto.PtmeMotherDto;
import ci.itechciv.sigdep.contracts.dto.PtmeMotherVisitDto;
import ci.itechciv.sigdep.contracts.dto.ScreeningDto;
import ci.itechciv.sigdep.contracts.dto.TptRecordDto;
import ci.itechciv.sigdep.contracts.dto.TreatmentInitiationDto;
import ci.itechciv.sigdep.contracts.dto.VisitDto;
import java.util.Map;

/**
 * Maps each EntityType to the DTO class used to (de)serialize its payload.
 * Add new entries here as new extractors / writers come online.
 */
public final class PayloadTypes {

    private static final Map<EntityType, Class<?>> TYPES = Map.ofEntries(
            Map.entry(EntityType.PATIENTS,              PatientDto.class),
            Map.entry(EntityType.VISITS,                VisitDto.class),
            Map.entry(EntityType.DISPENSATIONS,         DispensationDto.class),
            Map.entry(EntityType.TREATMENT_INITIATIONS, TreatmentInitiationDto.class),
            Map.entry(EntityType.CLOSURES,              ClosureDto.class),
            Map.entry(EntityType.LAB_RESULTS,           LabResultDto.class),
            Map.entry(EntityType.TPT_RECORDS,           TptRecordDto.class),
            Map.entry(EntityType.SCREENINGS,            ScreeningDto.class),
            Map.entry(EntityType.PTME_MOTHERS,          PtmeMotherDto.class),
            Map.entry(EntityType.PTME_MOTHER_VISITS,    PtmeMotherVisitDto.class),
            Map.entry(EntityType.PTME_CHILDREN,         PtmeChildDto.class),
            Map.entry(EntityType.PTME_CHILD_VISITS,     PtmeChildVisitDto.class)
    );

    public static Class<?> classFor(EntityType type) {
        Class<?> c = TYPES.get(type);
        if (c == null) {
            throw new IllegalStateException("No DTO class registered for " + type);
        }
        return c;
    }

    private PayloadTypes() {}
}
