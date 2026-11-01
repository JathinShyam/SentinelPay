package com.sentinelpay.reconciliation.repository;

import com.sentinelpay.reconciliation.domain.ReconciliationRun;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ReconciliationRunRepository {

    private static final RowMapper<ReconciliationRun> ROW_MAPPER = (rs, rowNum) -> new ReconciliationRun(
            UUID.fromString(rs.getString("run_id")),
            rs.getString("file_name"),
            rs.getString("file_sha256"),
            rs.getDate("business_date").toLocalDate(),
            rs.getString("status"),
            rs.getLong("total_records"),
            rs.getLong("matched_count"),
            rs.getLong("amount_mismatch_count"),
            rs.getLong("missing_internal_count"),
            rs.getLong("missing_external_count"),
            rs.getString("error_message"),
            rs.getTimestamp("started_at").toInstant(),
            rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant());

    private final JdbcClient jdbc;

    public ReconciliationRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID insertRunning(String fileName, String fileSha256, LocalDate businessDate) {
        UUID runId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO reconciliation_runs
                            (run_id, file_name, file_sha256, business_date, status)
                        VALUES
                            (:runId, :fileName, :fileSha256, :businessDate, 'RUNNING')
                        """)
                .param("runId", runId)
                .param("fileName", fileName)
                .param("fileSha256", fileSha256)
                .param("businessDate", Date.valueOf(businessDate))
                .update();
        return runId;
    }

    public void markCompleted(UUID runId,
                              long totalRecords,
                              long matchedCount,
                              long amountMismatchCount,
                              long missingInternalCount,
                              long missingExternalCount) {
        jdbc.sql("""
                        UPDATE reconciliation_runs
                        SET status = 'COMPLETED',
                            total_records = :totalRecords,
                            matched_count = :matchedCount,
                            amount_mismatch_count = :amountMismatchCount,
                            missing_internal_count = :missingInternalCount,
                            missing_external_count = :missingExternalCount,
                            completed_at = NOW()
                        WHERE run_id = :runId
                        """)
                .param("runId", runId)
                .param("totalRecords", totalRecords)
                .param("matchedCount", matchedCount)
                .param("amountMismatchCount", amountMismatchCount)
                .param("missingInternalCount", missingInternalCount)
                .param("missingExternalCount", missingExternalCount)
                .update();
    }

    public void markFailed(UUID runId, String errorMessage) {
        jdbc.sql("""
                        UPDATE reconciliation_runs
                        SET status = 'FAILED', error_message = :errorMessage, completed_at = NOW()
                        WHERE run_id = :runId
                        """)
                .param("runId", runId)
                .param("errorMessage", errorMessage)
                .update();
    }

    public Optional<ReconciliationRun> findById(UUID runId) {
        return jdbc.sql("""
                        SELECT run_id, file_name, file_sha256, business_date, status,
                               total_records, matched_count, amount_mismatch_count,
                               missing_internal_count, missing_external_count,
                               error_message, started_at, completed_at
                        FROM reconciliation_runs
                        WHERE run_id = :runId
                        """)
                .param("runId", runId)
                .query(ROW_MAPPER)
                .optional();
    }
}
