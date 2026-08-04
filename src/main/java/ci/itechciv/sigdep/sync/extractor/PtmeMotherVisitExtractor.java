package ci.itechciv.sigdep.sync.extractor;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.contracts.dto.PtmeMotherVisitDto;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * PTME mother follow-up visit extractor — ptme_mother_followup_visit.
 * Visits link to their parent ptme_mother_followup, which itself links
 * to ptme_pregnant_patient.uuid (sent up to the hub as motherSourceUuid).
 */
@Component
@Order(81)
public class PtmeMotherVisitExtractor implements DataExtractor {

    private final JdbcTemplate localDb;

    public PtmeMotherVisitExtractor(@Qualifier("localJdbcTemplate") JdbcTemplate localDb) {
        this.localDb = localDb;
    }

    @Override public EntityType getEntityType()  { return EntityType.PTME_MOTHER_VISITS; }
    @Override public String getSourceTable()     { return "ptme_mother_followup_visit"; }
    @Override public String getWatermarkColumn() { return "date_changed"; }
    @Override public boolean isEnabled()         { return true; }

    @Override
    public List<CanonicalRecord> extract(SyncCursor cursor, int batchSize) {
        LocalDateTime sinceDate = cursor.watermark();
        long sinceId = cursor.lastId();

        return localDb.query(
                """
                SELECT v.mother_followup_visit_id,
                       v.uuid              AS v_uuid,
                       p.uuid              AS mother_uuid,
                       v.visit_date,
                       v.gestational_age,
                       v.continuing_arv,
                       v.continuing_ctx,
                       v.voided,
                       COALESCE(v.date_changed, v.date_created) AS effective_changed
                FROM ptme_mother_followup_visit v
                JOIN ptme_mother_followup f ON f.mother_followup_id = v.mother_followup_id
                JOIN ptme_pregnant_patient p ON p.pregnant_patient_id = f.pregnant_patient_id
                WHERE COALESCE(v.date_changed, v.date_created) > ?
                   OR (COALESCE(v.date_changed, v.date_created) = ?
                       AND v.mother_followup_visit_id > ?)
                ORDER BY effective_changed, v.mother_followup_visit_id
                LIMIT ?
                """,
                (rs, i) -> {
                    long visitId = rs.getLong("mother_followup_visit_id");
                    UUID uuid = UUID.fromString(rs.getString("v_uuid"));
                    UUID motherUuid = UUID.fromString(rs.getString("mother_uuid"));
                    Integer arv = (Integer) rs.getObject("continuing_arv");
                    Integer ctx = (Integer) rs.getObject("continuing_ctx");

                    Map<String, Object> extra = new LinkedHashMap<>();
                    if (arv != null) extra.put("raw_continuing_arv", arv);
                    if (ctx != null) extra.put("raw_continuing_ctx", ctx);

                    LocalDate visitDate = rs.getDate("visit_date") == null ? null
                            : rs.getDate("visit_date").toLocalDate();

                    PtmeMotherVisitDto dto = new PtmeMotherVisitDto(
                            uuid,
                            null,
                            motherUuid,
                            visitDate,
                            (Integer) rs.getObject("gestational_age"),
                            PtmeCodes.yesNoNa(arv),
                            PtmeCodes.yesNoNa(ctx),
                            extra.isEmpty() ? null : extra,
                            rs.getBoolean("voided"));

                    LocalDateTime changed = rs.getTimestamp("effective_changed").toLocalDateTime();
                    return new CanonicalRecord(
                            EntityType.PTME_MOTHER_VISITS, uuid, changed, visitId, dto);
                },
                Timestamp.valueOf(sinceDate), Timestamp.valueOf(sinceDate), sinceId, batchSize);
    }
}
