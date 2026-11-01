package com.sentinelpay.ledger.repository;

import com.sentinelpay.ledger.domain.Direction;
import com.sentinelpay.ledger.domain.LedgerEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public class LedgerEntryRepository {

    private static final RowMapper<LedgerEntry> ROW_MAPPER = (rs, rowNum) -> new LedgerEntry(
            UUID.fromString(rs.getString("entry_id")),
            UUID.fromString(rs.getString("transaction_id")),
            UUID.fromString(rs.getString("account_id")),
            Direction.valueOf(rs.getString("direction")),
            rs.getLong("signed_amount_minor"),
            rs.getLong("running_balance_minor"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;
    private final JdbcTemplate jdbcTemplate;

    public LedgerEntryRepository(JdbcClient jdbc, JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbc;
        this.jdbcTemplate = jdbcTemplate;
    }

    public void batchInsert(List<LedgerEntry> entries) {
        jdbcTemplate.batchUpdate("""
                INSERT INTO ledger_entries
                    (entry_id, transaction_id, account_id, direction,
                     signed_amount_minor, running_balance_minor, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, entries, 100, (statement, entry) -> {
            statement.setObject(1, entry.entryId());
            statement.setObject(2, entry.transactionId());
            statement.setObject(3, entry.accountId());
            statement.setString(4, entry.direction().name());
            statement.setLong(5, entry.signedAmountMinor());
            statement.setLong(6, entry.runningBalanceMinor());
            statement.setObject(7, java.sql.Timestamp.from(entry.createdAt()));
        });
    }

    public List<LedgerEntry> findByTransactionId(UUID transactionId) {
        return jdbc.sql("""
                        SELECT entry_id, transaction_id, account_id, direction,
                               signed_amount_minor, running_balance_minor, created_at
                        FROM ledger_entries
                        WHERE transaction_id = :transactionId
                        ORDER BY created_at ASC, entry_id ASC
                        """)
                .param("transactionId", transactionId)
                .query(ROW_MAPPER)
                .list();
    }

    /**
     * Produces the audit certificate inputs in one scan: the account's signed
     * balance and posting count, plus whether every transaction linked to the
     * account nets to zero across <em>all</em> of its entries.
     */
    public AccountLedgerSummary summarizeAccount(UUID accountId) {
        return jdbc.sql("""
                        WITH linked_transactions AS (
                            SELECT DISTINCT transaction_id
                            FROM ledger_entries
                            WHERE account_id = :accountId
                        ),
                        account_totals AS (
                            SELECT COALESCE(SUM(signed_amount_minor), 0) AS balance_minor,
                                   COUNT(*) AS entry_count
                            FROM ledger_entries
                            WHERE account_id = :accountId
                        ),
                        transaction_totals AS (
                            SELECT e.transaction_id,
                                   SUM(e.signed_amount_minor) AS transaction_sum
                            FROM ledger_entries e
                            JOIN linked_transactions lt ON lt.transaction_id = e.transaction_id
                            GROUP BY e.transaction_id
                        )
                        SELECT a.balance_minor,
                               a.entry_count,
                               NOT EXISTS (
                                   SELECT 1
                                   FROM transaction_totals t
                                   WHERE t.transaction_sum <> 0
                               ) AS balanced
                        FROM account_totals a
                        """)
                .param("accountId", accountId)
                .query((resultSet, rowNum) -> new AccountLedgerSummary(
                        resultSet.getLong("balance_minor"),
                        resultSet.getLong("entry_count"),
                        resultSet.getBoolean("balanced")))
                .single();
    }

    public record AccountLedgerSummary(long balanceMinor, long entryCount, boolean balanced) {
    }

    public long sumSignedAmountByTransactionId(UUID transactionId) {
        Long sum = jdbc.sql("""
                        SELECT COALESCE(SUM(signed_amount_minor), 0)
                        FROM ledger_entries
                        WHERE transaction_id = :transactionId
                        """)
                .param("transactionId", transactionId)
                .query(Long.class)
                .single();
        return sum == null ? 0L : sum;
    }

    public long findRunningBalanceForTransaction(UUID transactionId, UUID accountId) {
        Long balance = jdbc.sql("""
                        SELECT running_balance_minor
                        FROM ledger_entries
                        WHERE transaction_id = :transactionId AND account_id = :accountId
                        ORDER BY created_at DESC, entry_id DESC
                        LIMIT 1
                        """)
                .param("transactionId", transactionId)
                .param("accountId", accountId)
                .query(Long.class)
                .optional()
                .orElse(0L);
        return balance;
    }

}
