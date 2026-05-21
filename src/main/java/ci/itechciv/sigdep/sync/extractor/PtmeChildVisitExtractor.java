package ci.itechciv.sigdep.sync.extractor;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.contracts.dto.PtmeChildVisitDto;
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
 * PTME child follow-up visit extractor — ptme_child_followup_visit.
 * Linked to its parent ptme_child via child_id (sent up as
 * {@code childSourceUuid}).
 */
@Component
@Order(83)
public class PtmeChildVisitExtractor implements DataExtractor {

    private final JdbcTemplate localDb;

    public PtmeChildVisitExtractor(@Qualifier("localJdbcTemplate") JdbcTemplate localDb) {
        this.localDb = localDb;
    }

    @Override public EntityType getEntityType()  { return EntityType.PTME_CHILD_VISITS; }
    @Override public String getSourceTable()     { return "ptme_child_followup_visit"; }
    @Override public String getWatermarkColumn() { return "date_changed"; }
    @Override public boolean isEnabled()         { return true; }

    @Override
    public List<CanonicalRecord> extract(LocalDateTime since, int batchSize) {
        return localDb.query(
                """
                SELECT v.uuid AS v_uuid,
                       c.uuid AS child_uuid,
                       v.visit_date,
                       v.age_in_day,
                       v.age_in_week,
                       v.age_in_month,
                       v.eating_type,
                       v.modern_contraceptive_method,
                       v.continuing_ctx,
                       v.continuing_inh,
                       v.voided,
                       COALESCE(v.date_changed, v.date_created) AS effective_changed
                FROM ptme_child_followup_visit v
                JOIN ptme_child c ON c.child_id = v.child_id
                WHERE COALESCE(v.date_changed, v.date_created) > ?
                ORDER BY effective_changed
                LIMIT ?
                """,
                (rs, i) -> {
                    UUID uuid = UUID.fromString(rs.getString("v_uuid"));
                    UUID childUuid = UUID.fromString(rs.getString("child_uuid"));

                    Integer eat = (Integer) rs.getObject("eating_type");
                    Integer ctx = (Integer) rs.getObject("continuing_ctx");
                    Integer inh = (Integer) rs.getObject("continuing_inh");
                    Boolean modCon = (Boolean) rs.getObject("modern_contraceptive_method");

                    Map<String, Object> extra = new LinkedHashMap<>();
                    if (eat != null) extra.put("raw_eating_type", eat);
                    if (ctx != null) extra.put("raw_continuing_ctx", ctx);
                    if (inh != null) extra.put("raw_continuing_inh", inh);

                    LocalDate visitDate = rs.getDate("visit_date") == null ? null
                            : rs.getDate("visit_date").toLocalDate();

                    PtmeChildVisitDto dto = new PtmeChildVisitDto(
                            uuid,
                            null,
                            childUuid,
                            visitDate,
                            (Integer) rs.getObject("age_in_day"),
                            (Integer) rs.getObject("age_in_week"),
                            (Integer) rs.getObject("age_in_month"),
                            PtmeCodes.eatingType(eat),
                            modCon,
                            PtmeCodes.yesNoNa(ctx),
                            PtmeCodes.yesNoNa(inh),
                            extra.isEmpty() ? null : extra,
                            rs.getBoolean("voided"));

                    LocalDateTime changed = rs.getTimestamp("effective_changed").toLocalDateTime();
                    return new CanonicalRecord(EntityType.PTME_CHILD_VISITS, uuid, changed, dto);
                },
                Timestamp.valueOf(since), batchSize);
    }
}
