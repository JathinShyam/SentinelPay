package com.sentinelpay.reconciliation.repository;

import com.sentinelpay.reconciliation.domain.DiscrepancyType;
import com.sentinelpay.reconciliation.domain.ReconciliationDiscrepancy;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;
import java.util.UUID;

@Repository
public class DiscrepancyRepository {

    private static final String INSERT_SQL = """
            INSERT INTO reconciliation_discrepancies
                (run_id, type, external_ref, transaction_id,
                 expected_amount_minor, actual_amount_minor, currency, details)
            VALUES
                (:runId, :type, :externalRef, :transactionId,
                 :expectedAmountMinor, :actualAmountMinor, :currency, CAST(:details AS jsonb))
            """;

    private static final RowMapper<ReconciliationDiscrepancy> ROW_MAPPER = (rs, rowNum) ->
            new ReconciliationDiscrepancy(
                    UUID.fromString(rs.getString("discrepancy_id")),
                    UUID.fromString(rs.getString("run_id")),
                    DiscrepancyType.valueOf(rs.getString("type")),
                    rs.getString("external_ref"),
                    rs.getString("transaction_id") == null
                            ? null : UUID.fromString(rs.getString("transaction_id")),
                    rs.getObject("expected_amount_minor", Long.class),
                    rs.getObject("actual_amount_minor", Long.class),
                    rs.getString("currency") == null ? null : rs.getString("currency").trim(),
                    rs.getString("details"),
                    rs.getTimestamp("created_at").toInstant());

    private final NamedParameterJdbcTemplate namedJdbc;
    private final JdbcClient jdbc;

    public DiscrepancyRepository(NamedParameterJdbcTemplate namedJdbc, JdbcClient jdbc) {
        this.namedJdbc = namedJdbc;
        this.jdbc = jdbc;
    }

    public void batchInsert(List<ReconciliationDiscrepancy> discrepancies) {
        if (discrepancies.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = discrepancies.stream()
                .map(this::parameters)
                .toArray(MapSqlParameterSource[]::new);
        namedJdbc.batchUpdate(INSERT_SQL, batch);
    }

    public long count(UUID runId, DiscrepancyType type) {
        String sql = "SELECT COUNT(*) FROM reconciliation_discrepancies WHERE run_id = :runId"
                + (type == null ? "" : " AND type = :type");
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("runId", runId);
        if (type != null) {
            statement = statement.param("type", type.name());
        }
        Long count = statement.query(Long.class).single();
        return count == null ? 0L : count;
    }

    public List<ReconciliationDiscrepancy> findPage(UUID runId,
                                                    DiscrepancyType type,
                                                    int limit,
                                                    int offset) {
        String sql = """
                SELECT discrepancy_id, run_id, type, external_ref, transaction_id,
                       expected_amount_minor, actual_amount_minor, currency, details::text AS details,
                       created_at
                FROM reconciliation_discrepancies
                WHERE run_id = :runId
                """ + (type == null ? "" : " AND type = :type") + """
                 ORDER BY created_at ASC, discrepancy_id ASC
                 LIMIT :limit OFFSET :offset
                """;
        JdbcClient.StatementSpec statement = jdbc.sql(sql)
                .param("runId", runId)
                .param("limit", limit)
                .param("offset", offset);
        if (type != null) {
            statement = statement.param("type", type.name());
        }
        return statement.query(ROW_MAPPER).list();
    }

    private MapSqlParameterSource parameters(ReconciliationDiscrepancy discrepancy) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("runId", discrepancy.runId())
                .addValue("type", discrepancy.type().name())
                .addValue("externalRef", discrepancy.externalRef())
                .addValue("transactionId", discrepancy.transactionId(), Types.OTHER)
                .addValue("expectedAmountMinor", discrepancy.expectedAmountMinor(), Types.BIGINT)
                .addValue("actualAmountMinor", discrepancy.actualAmountMinor(), Types.BIGINT)
                .addValue("currency", discrepancy.currency())
                .addValue("details", discrepancy.details() == null ? "{}" : discrepancy.details());
        return parameters;
    }
}
