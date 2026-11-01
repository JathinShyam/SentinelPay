package com.sentinelpay.ledger.repository;

import com.sentinelpay.ledger.domain.TransactionEntity;
import com.sentinelpay.ledger.domain.TxStatus;
import com.sentinelpay.ledger.domain.TxType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class TransactionRepository {

    private static final RowMapper<TransactionEntity> ROW_MAPPER = (rs, rowNum) -> new TransactionEntity(
            UUID.fromString(rs.getString("transaction_id")),
            rs.getString("idempotency_key"),
            TxType.valueOf(rs.getString("tx_type")),
            TxStatus.valueOf(rs.getString("effective_status")),
            rs.getString("currency").trim(),
            rs.getString("description"),
            rs.getString("metadata"),
            rs.getString("reversal_of") == null ? null : UUID.fromString(rs.getString("reversal_of")),
            rs.getTimestamp("created_at").toInstant());

    private static final String SELECT_WITH_DERIVED_STATUS = """
            SELECT t.transaction_id, t.idempotency_key, t.tx_type,
                   CASE WHEN EXISTS (
                       SELECT 1 FROM transactions r WHERE r.reversal_of = t.transaction_id
                   ) THEN 'REVERSED' ELSE t.status END AS effective_status,
                   t.currency, t.description, t.metadata::text AS metadata, t.reversal_of, t.created_at
            FROM transactions t
            """;

    private final JdbcClient jdbc;

    public TransactionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Inserts an envelope unless the idempotency key or reversal_of already exists.
     *
     * @return true when this call created the row, false when a concurrent request won.
     */
    public boolean insertIfAbsent(TransactionEntity transaction) {
        int inserted = jdbc.sql("""
                        INSERT INTO transactions
                            (transaction_id, idempotency_key, tx_type, status, currency,
                             description, metadata, reversal_of, created_at)
                        VALUES
                            (:transactionId, :idempotencyKey, :txType, :status, :currency,
                             :description, CAST(:metadata AS jsonb), :reversalOf, :createdAt)
                        ON CONFLICT DO NOTHING
                        """)
                .param("transactionId", transaction.transactionId())
                .param("idempotencyKey", transaction.idempotencyKey())
                .param("txType", transaction.txType().name())
                .param("status", transaction.status().name())
                .param("currency", transaction.currency())
                .param("description", transaction.description())
                .param("metadata", transaction.metadata() == null ? "{}" : transaction.metadata())
                .param("reversalOf", transaction.reversalOf())
                .param("createdAt", java.sql.Timestamp.from(transaction.createdAt()))
                .update();
        return inserted == 1;
    }

    public Optional<TransactionEntity> findById(UUID transactionId) {
        return jdbc.sql(SELECT_WITH_DERIVED_STATUS + " WHERE t.transaction_id = :transactionId")
                .param("transactionId", transactionId)
                .query(ROW_MAPPER)
                .optional();
    }

    public Optional<TransactionEntity> findByIdForUpdate(UUID transactionId) {
        return jdbc.sql(SELECT_WITH_DERIVED_STATUS
                        + " WHERE t.transaction_id = :transactionId FOR UPDATE OF t")
                .param("transactionId", transactionId)
                .query(ROW_MAPPER)
                .optional();
    }

    public Optional<TransactionEntity> findByIdempotencyKey(String idempotencyKey) {
        return jdbc.sql(SELECT_WITH_DERIVED_STATUS + " WHERE t.idempotency_key = :idempotencyKey")
                .param("idempotencyKey", idempotencyKey)
                .query(ROW_MAPPER)
                .optional();
    }

    public Optional<TransactionEntity> findReversalOf(UUID originalTransactionId) {
        return jdbc.sql(SELECT_WITH_DERIVED_STATUS + " WHERE t.reversal_of = :originalTransactionId")
                .param("originalTransactionId", originalTransactionId)
                .query(ROW_MAPPER)
                .optional();
    }

    public List<TransactionEntity> findByIds(Collection<UUID> transactionIds) {
        if (transactionIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(SELECT_WITH_DERIVED_STATUS + " WHERE t.transaction_id IN (:transactionIds)")
                .param("transactionIds", transactionIds)
                .query(ROW_MAPPER)
                .list();
    }
}
