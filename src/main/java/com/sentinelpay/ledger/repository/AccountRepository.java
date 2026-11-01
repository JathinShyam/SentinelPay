package com.sentinelpay.ledger.repository;

import com.sentinelpay.ledger.domain.Account;
import com.sentinelpay.ledger.domain.AccountStatus;
import com.sentinelpay.ledger.domain.AccountType;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AccountRepository {

    private static final RowMapper<Account> ROW_MAPPER = (rs, rowNum) -> new Account(
            UUID.fromString(rs.getString("account_id")),
            rs.getString("owner_id"),
            rs.getString("currency").trim(),
            rs.getLong("balance_minor"),
            AccountType.valueOf(rs.getString("account_type")),
            AccountStatus.valueOf(rs.getString("status")),
            rs.getLong("version"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant());

    private final JdbcClient jdbc;

    public AccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Account> findById(UUID accountId) {
        return jdbc.sql("""
                        SELECT account_id, owner_id, currency, balance_minor, account_type,
                               status, version, created_at, updated_at
                        FROM accounts
                        WHERE account_id = :accountId
                        """)
                .param("accountId", accountId)
                .query(ROW_MAPPER)
                .optional();
    }

    public Optional<Account> findByIdForUpdate(UUID accountId) {
        return jdbc.sql("""
                        SELECT account_id, owner_id, currency, balance_minor, account_type,
                               status, version, created_at, updated_at
                        FROM accounts
                        WHERE account_id = :accountId
                        FOR UPDATE
                        """)
                .param("accountId", accountId)
                .query(ROW_MAPPER)
                .optional();
    }

    public List<Account> findByOwner(String ownerId) {
        return jdbc.sql("""
                        SELECT account_id, owner_id, currency, balance_minor, account_type,
                               status, version, created_at, updated_at
                        FROM accounts
                        WHERE owner_id = :ownerId
                        ORDER BY created_at ASC
                        """)
                .param("ownerId", ownerId)
                .query(ROW_MAPPER)
                .list();
    }

    public void insert(Account account) {
        jdbc.sql("""
                        INSERT INTO accounts
                            (account_id, owner_id, currency, balance_minor, account_type,
                             status, version, created_at, updated_at)
                        VALUES
                            (:accountId, :ownerId, :currency, :balanceMinor, :accountType,
                             :status, :version, :createdAt, :updatedAt)
                        """)
                .param("accountId", account.accountId())
                .param("ownerId", account.ownerId())
                .param("currency", account.currency())
                .param("balanceMinor", account.balanceMinor())
                .param("accountType", account.accountType().name())
                .param("status", account.status().name())
                .param("version", account.version())
                .param("createdAt", java.sql.Timestamp.from(account.createdAt()))
                .param("updatedAt", java.sql.Timestamp.from(account.updatedAt()))
                .update();
    }

    public int updateBalance(UUID accountId, long newBalanceMinor, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE accounts
                        SET balance_minor = :balanceMinor,
                            version = version + 1,
                            updated_at = NOW()
                        WHERE account_id = :accountId
                          AND version = :expectedVersion
                        """)
                .param("balanceMinor", newBalanceMinor)
                .param("accountId", accountId)
                .param("expectedVersion", expectedVersion)
                .update();
    }
}
