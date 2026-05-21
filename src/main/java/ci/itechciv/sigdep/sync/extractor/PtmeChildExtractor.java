package ci.itechciv.sigdep.sync.extractor;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.contracts.dto.PtmeChildDto;
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
 * PTME child extractor — ptme_child JOIN ptme_child_followup (1:1 on
 * child_id). family/given names are stripped at this layer.
 *
 * The mother cross-link, when present, goes up as {@code motherSourceUuid}
 * (= ptme_pregnant_patient.uuid). If the child has no mother record, it
 * stays null and the hub keeps the row as orphan.
 */
@Component
@Order(82)
public class PtmeChildExtractor implements DataExtractor {

    private final JdbcTemplate localDb;

    public PtmeChildExtractor(@Qualifier("localJdbcTemplate") JdbcTemplate localDb) {
        this.localDb = localDb;
    }

    @Override public EntityType getEntityType()  { return EntityType.PTME_CHILDREN; }
    @Override public String getSourceTable()     { return "ptme_child"; }
    @Override public String getWatermarkColumn() { return "date_changed"; }
    @Override public boolean isEnabled()         { return true; }

    @Override
    public List<CanonicalRecord> extract(LocalDateTime since, int batchSize) {
        return localDb.query(
                """
                SELECT c.uuid                          AS c_uuid,
                       m.uuid                          AS mother_uuid,
                       c.child_followup_number,
                       c.birth_date,
                       c.gender,
                       c.voided                        AS c_voided,
                       f.arv_prophylaxis_given, f.arv_prophylaxis_given_date,
                       f.followup_end_date,
                       f.pcr1_sampling_date, f.age_in_week_on_pcr1_sampling, f.age_in_month_on_pcr1_sampling, f.pcr1_result,
                       f.pcr2_sampling_date, f.age_in_week_on_pcr2_sampling, f.age_in_month_on_pcr2_sampling, f.pcr2_result,
                       f.pcr3_sampling_date, f.age_in_week_on_pcr3_sampling, f.age_in_month_on_pcr3_sampling, f.pcr3_result,
                       f.ctx_initiation_date, f.age_in_week_on_ctx_initiation, f.age_in_month_on_ctx_initiation,
                       f.inh_initiation_date, f.age_in_week_on_inh_initiation, f.age_in_month_on_inh_initiation,
                       f.hiv_serology1_date, f.age_in_week_on_hiv_serology1, f.age_in_month_on_hiv_serology1, f.hiv_serology1_result,
                       f.hiv_serology2_date, f.age_in_week_on_hiv_serology2, f.age_in_month_on_hiv_serology2, f.hiv_serology2_result,
                       f.followup_result, f.followup_result_date, f.reference_location,
                       GREATEST(
                         COALESCE(c.date_changed, c.date_created),
                         COALESCE(f.date_changed, f.date_created, c.date_created)
                       ) AS effective_changed
                FROM ptme_child c
                LEFT JOIN ptme_child_followup f ON f.child_followup_id = c.child_id AND f.voided = 0
                LEFT JOIN ptme_pregnant_patient m ON m.pregnant_patient_id = c.mother
                WHERE GREATEST(
                        COALESCE(c.date_changed, c.date_created),
                        COALESCE(f.date_changed, f.date_created, c.date_created)
                      ) > ?
                ORDER BY effective_changed
                LIMIT ?
                """,
                (rs, i) -> {
                    UUID uuid = UUID.fromString(rs.getString("c_uuid"));
                    String motherUuidStr = rs.getString("mother_uuid");
                    UUID motherUuid = motherUuidStr == null ? null : UUID.fromString(motherUuidStr);

                    Integer prophylaxis = (Integer) rs.getObject("arv_prophylaxis_given");
                    Integer pcr1 = (Integer) rs.getObject("pcr1_result");
                    Integer pcr2 = (Integer) rs.getObject("pcr2_result");
                    Integer pcr3 = (Integer) rs.getObject("pcr3_result");
                    Integer ser1 = (Integer) rs.getObject("hiv_serology1_result");
                    Integer ser2 = (Integer) rs.getObject("hiv_serology2_result");
                    Integer followupResult = (Integer) rs.getObject("followup_result");

                    Map<String, Object> extra = new LinkedHashMap<>();
                    put(extra, "raw_arv_prophylaxis_given", prophylaxis);
                    put(extra, "raw_pcr1_result", pcr1);
                    put(extra, "raw_pcr2_result", pcr2);
                    put(extra, "raw_pcr3_result", pcr3);
                    put(extra, "raw_hiv_serology1_result", ser1);
                    put(extra, "raw_hiv_serology2_result", ser2);
                    put(extra, "raw_followup_result", followupResult);

                    PtmeChildDto dto = new PtmeChildDto(
                            uuid, null, motherUuid,
                            rs.getString("child_followup_number"),
                            toLocalDate(rs.getDate("birth_date")),
                            rs.getString("gender"),
                            PtmeCodes.yesNoNa(prophylaxis),
                            toLocalDate(rs.getDate("arv_prophylaxis_given_date")),
                            toLocalDate(rs.getDate("followup_end_date")),
                            toLocalDate(rs.getDate("pcr1_sampling_date")),
                            (Integer) rs.getObject("age_in_week_on_pcr1_sampling"),
                            (Integer) rs.getObject("age_in_month_on_pcr1_sampling"),
                            PtmeCodes.hivResult(pcr1),
                            toLocalDate(rs.getDate("pcr2_sampling_date")),
                            (Integer) rs.getObject("age_in_week_on_pcr2_sampling"),
                            (Integer) rs.getObject("age_in_month_on_pcr2_sampling"),
                            PtmeCodes.hivResult(pcr2),
                            toLocalDate(rs.getDate("pcr3_sampling_date")),
                            (Integer) rs.getObject("age_in_week_on_pcr3_sampling"),
                            (Integer) rs.getObject("age_in_month_on_pcr3_sampling"),
                            PtmeCodes.hivResult(pcr3),
                            toLocalDate(rs.getDate("ctx_initiation_date")),
                            (Integer) rs.getObject("age_in_week_on_ctx_initiation"),
                            (Integer) rs.getObject("age_in_month_on_ctx_initiation"),
                            toLocalDate(rs.getDate("inh_initiation_date")),
                            (Integer) rs.getObject("age_in_week_on_inh_initiation"),
                            (Integer) rs.getObject("age_in_month_on_inh_initiation"),
                            toLocalDate(rs.getDate("hiv_serology1_date")),
                            (Integer) rs.getObject("age_in_week_on_hiv_serology1"),
                            (Integer) rs.getObject("age_in_month_on_hiv_serology1"),
                            PtmeCodes.hivResult(ser1),
                            toLocalDate(rs.getDate("hiv_serology2_date")),
                            (Integer) rs.getObject("age_in_week_on_hiv_serology2"),
                            (Integer) rs.getObject("age_in_month_on_hiv_serology2"),
                            PtmeCodes.hivResult(ser2),
                            PtmeCodes.followupResult(followupResult),
                            toLocalDate(rs.getDate("followup_result_date")),
                            rs.getString("reference_location"),
                            extra.isEmpty() ? null : extra,
                            rs.getBoolean("c_voided"));

                    LocalDateTime changed = rs.getTimestamp("effective_changed").toLocalDateTime();
                    return new CanonicalRecord(EntityType.PTME_CHILDREN, uuid, changed, dto);
                },
                Timestamp.valueOf(since), batchSize);
    }

    private static void put(Map<String, Object> m, String k, Object v) {
        if (v != null) m.put(k, v);
    }

    private static LocalDate toLocalDate(java.sql.Date d) {
        return d == null ? null : d.toLocalDate();
    }
}
