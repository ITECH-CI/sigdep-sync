package ci.itechciv.sigdep.sync.extractor;

import ci.itechciv.sigdep.contracts.EntityType;
import ci.itechciv.sigdep.contracts.dto.PtmeMotherDto;
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
 * PTME mother extractor — joins ptme_pregnant_patient with its latest
 * ptme_mother_followup row. Both tables come from the openmrs "ptme"
 * module's dedicated MySQL tables.
 *
 * Demographics (family_name, given_name) are dropped at this layer:
 * the hub stores only opaque local identifiers.
 *
 * Watermark = the most recent change between ptme_pregnant_patient and
 * its follow-up row.
 */
@Component
@Order(80)
public class PtmeMotherExtractor implements DataExtractor {

    private final JdbcTemplate localDb;

    public PtmeMotherExtractor(@Qualifier("localJdbcTemplate") JdbcTemplate localDb) {
        this.localDb = localDb;
    }

    @Override public EntityType getEntityType()  { return EntityType.PTME_MOTHERS; }
    @Override public String getSourceTable()     { return "ptme_pregnant_patient"; }
    @Override public String getWatermarkColumn() { return "date_changed"; }
    @Override public boolean isEnabled()         { return true; }

    @Override
    public List<CanonicalRecord> extract(LocalDateTime since, int batchSize) {
        return localDb.query(
                """
                SELECT p.uuid                   AS p_uuid,
                       p.pregnant_number,
                       p.hiv_care_number,
                       p.screening_number,
                       p.age,
                       p.marital_status,
                       p.spousal_screening,
                       p.spousal_screening_result AS p_spousal_result,
                       p.voided                 AS p_voided,
                       f.start_date,
                       f.end_date,
                       f.arv_status_at_registering,
                       f.estimated_delivery_date,
                       f.pregnancy_outcome,
                       f.spousal_screening_result AS f_spousal_result,
                       f.spousal_screening_date,
                       f.delivery_type,
                       GREATEST(
                         COALESCE(p.date_changed, p.date_created),
                         COALESCE(f.date_changed, f.date_created, p.date_created)
                       ) AS effective_changed
                FROM ptme_pregnant_patient p
                LEFT JOIN ptme_mother_followup f
                       ON f.pregnant_patient_id = p.pregnant_patient_id
                      AND f.voided = 0
                WHERE GREATEST(
                        COALESCE(p.date_changed, p.date_created),
                        COALESCE(f.date_changed, f.date_created, p.date_created)
                      ) > ?
                ORDER BY effective_changed
                LIMIT ?
                """,
                (rs, i) -> {
                    UUID uuid = UUID.fromString(rs.getString("p_uuid"));
                    Integer marital = (Integer) rs.getObject("marital_status");
                    Integer spScreen = (Integer) rs.getObject("spousal_screening");
                    Integer pSpResult = (Integer) rs.getObject("p_spousal_result");
                    Integer fSpResult = (Integer) rs.getObject("f_spousal_result");
                    Integer arvStatus = (Integer) rs.getObject("arv_status_at_registering");
                    Integer outcome = (Integer) rs.getObject("pregnancy_outcome");
                    Integer delivery = (Integer) rs.getObject("delivery_type");

                    // The followup's spousal result takes priority — it's the
                    // value entered during ongoing follow-up; the pregnant
                    // patient's column is only filled if it was set at intake.
                    Integer effectiveSpResult = fSpResult != null ? fSpResult : pSpResult;

                    Map<String, Object> extra = new LinkedHashMap<>();
                    put(extra, "raw_marital_status", marital);
                    put(extra, "raw_spousal_screening", spScreen);
                    put(extra, "raw_spousal_screening_result", effectiveSpResult);
                    put(extra, "raw_arv_status_at_registering", arvStatus);
                    put(extra, "raw_pregnancy_outcome", outcome);
                    put(extra, "raw_delivery_type", delivery);

                    LocalDate startDate = toLocalDate(rs.getDate("start_date"));
                    LocalDate endDate = toLocalDate(rs.getDate("end_date"));
                    LocalDate edd = toLocalDate(rs.getDate("estimated_delivery_date"));
                    LocalDate spDate = toLocalDate(rs.getDate("spousal_screening_date"));

                    PtmeMotherDto dto = new PtmeMotherDto(
                            uuid,
                            null,
                            rs.getString("pregnant_number"),
                            rs.getString("hiv_care_number"),
                            rs.getString("screening_number"),
                            (Integer) rs.getObject("age"),
                            PtmeCodes.maritalStatus(marital),
                            PtmeCodes.spousalScreening(spScreen),
                            PtmeCodes.hivResult(effectiveSpResult),
                            startDate, endDate,
                            PtmeCodes.arvStatusAtRegistering(arvStatus),
                            edd,
                            PtmeCodes.pregnancyOutcome(outcome),
                            spDate,
                            PtmeCodes.deliveryType(delivery),
                            extra.isEmpty() ? null : extra,
                            rs.getBoolean("p_voided"));

                    LocalDateTime changed = rs.getTimestamp("effective_changed").toLocalDateTime();
                    return new CanonicalRecord(EntityType.PTME_MOTHERS, uuid, changed, dto);
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
