package com.sentinelpay.ledger.repository;

import com.sentinelpay.ledger.domain.Direction;
import com.sentinelpay.ledger.domain.LedgerEntry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.Instant;
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

    private static final String ACTIVITY_SELECT = """
            SELECT e.entry_id, e.transaction_id, t.tx_type,
                   CASE WHEN EXISTS (
                       SELECT 1 FROM transactions r WHERE r.reversal_of = t.transaction_id
                   ) THEN 'REVERSED' ELSE t.status END AS effective_status,
                   e.direction, e.signed_amount_minor, e.running_balance_minor,
                   t.currency, t.description, e.created_at,
                   (SELECT other.account_id
                    FROM ledger_entries other
                    WHERE other.transaction_id = e.transaction_id
                      AND other.account_id <> e.account_id
                    ORDER BY other.entry_id
                    LIMIT 1) AS counterparty_account_id
            FROM ledger_entries e
            JOIN transactions t ON t.transaction_id = e.transaction_id
            """;

    private static final RowMapper<AccountActivityRow> ACTIVITY_ROW_MAPPER = (rs, rowNum) ->
            new AccountActivityRow(
                    UUID.fromString(rs.getString("entry_id")),
                    UUID.fromString(rs.getString("transaction_id")),
                    rs.getString("tx_type"),
                    rs.getString("effective_status"),
                    Direction.valueOf(rs.getString("direction")),
                    rs.getLong("signed_amount_minor"),
                    rs.getLong("running_balance_minor"),
                    rs.getString("currency").trim(),
                    rs.getString("description"),
                    rs.getTimestamp("created_at").toInstant(),
                    rs.getString("counterparty_account_id") == null ? null
                            : UUID.fromString(rs.getString("counterparty_account_id")));

    /**
     * Newest-first account statement. The cursor is the (created_at, entry_id)
     * pair of the last row already delivered, which is stable even when two
     * entries share a timestamp.
     */
    public List<AccountActivityRow> findActivity(UUID accountId,
                                                 Instant cursorTime,
                                                 UUID cursorEntryId,
                                                 int limit) {
        if (cursorTime == null || cursorEntryId == null) {
            return jdbc.sql(ACTIVITY_SELECT + """
                            WHERE e.account_id = :accountId
                            ORDER BY e.created_at DESC, e.entry_id DESC
                            LIMIT :limit
                            """)
                    .param("accountId", accountId)
                    .param("limit", limit)
                    .query(ACTIVITY_ROW_MAPPER)
                    .list();
        }
        return jdbc.sql(ACTIVITY_SELECT + """
                        WHERE e.account_id = :accountId
                          AND (e.created_at, e.entry_id) < (:cursorTime, :cursorEntryId)
                        ORDER BY e.created_at DESC, e.entry_id DESC
                        LIMIT :limit
                        """)
                .param("accountId", accountId)
                .param("cursorTime", java.sql.Timestamp.from(cursorTime))
                .param("cursorEntryId", cursorEntryId)
                .param("limit", limit)
                .query(ACTIVITY_ROW_MAPPER)
                .list();
    }

    public List<AccountActivityRow> findRecentActivityForOwner(String ownerId, int limit) {
        return jdbc.sql(ACTIVITY_SELECT + """
                        JOIN accounts a ON a.account_id = e.account_id
                        WHERE a.owner_id = :ownerId
                        ORDER BY e.created_at DESC, e.entry_id DESC
                        LIMIT :limit
                        """)
                .param("ownerId", ownerId)
                .param("limit", limit)
                .query(ACTIVITY_ROW_MAPPER)
                .list();
    }

    /**
     * One scan over the owner's accounts proving that every materialised
     * balance still equals the sum of its journal entries.
     */
    public OwnerLedgerSummary summarizeOwner(String ownerId) {
        return jdbc.sql("""
                        WITH account_balances AS (
                            SELECT a.account_id, a.balance_minor,
                                   COALESCE(SUM(e.signed_amount_minor), 0) AS ledger_balance,
                                   COUNT(e.entry_id) AS entry_count
                            FROM accounts a
                            LEFT JOIN ledger_entries e ON e.account_id = a.account_id
                            WHERE a.owner_id = :ownerId
                            GROUP BY a.account_id, a.balance_minor
                        )
                        SELECT COUNT(*) AS accounts_checked,
                               COALESCE(SUM(entry_count), 0) AS entry_count,
                               COALESCE(SUM(ABS(balance_minor - ledger_balance)), 0) AS drift_minor
                        FROM account_balances
                        """)
                .param("ownerId", ownerId)
                .query((resultSet, rowNum) -> new OwnerLedgerSummary(
                        resultSet.getInt("accounts_checked"),
                        resultSet.getLong("entry_count"),
                        resultSet.getLong("drift_minor")))
                .single();
    }

    public record AccountActivityRow(
            UUID entryId,
            UUID transactionId,
            String type,
            String status,
            Direction direction,
            long signedAmountMinor,
            long runningBalanceMinor,
            String currency,
            String description,
            Instant createdAt,
            UUID counterpartyAccountId
    ) {
    }

    public record OwnerLedgerSummary(int accountsChecked, long entryCount, long driftMinor) {
    }

}
