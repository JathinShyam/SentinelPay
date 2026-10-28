package com.sentinelpay;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 verification gate.
 *
 * <p>Runs Flyway against a real PostgreSQL 17 container and proves the schema's
 * non-negotiable invariants: both migrations apply, the outbox partial index exists,
 * journal lines balance, and PostgreSQL itself rejects UPDATE/DELETE on the ledger.
 */
@Testcontainers
class SchemaMigrationIT {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("sentinelpay")
            .withUsername("sentinelpay")
            .withPassword("sentinelpay");

    static JdbcTemplate jdbc;

    @BeforeAll
    static void migrateSchema() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);

        MigrateResult result = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(result.migrationsExecuted)
                .as("V1 and V2 must apply on an empty database")
                .isEqualTo(2);
    }

    @Test
    void flywayHistoryContainsBothVersionedMigrations() {
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank",
                String.class);

        assertThat(versions).containsExactly("1", "2");
    }

    @Test
    void schemaContainsEveryPlaneAndThePartialOutboxIndex() {
        List<String> tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public'",
                String.class);

        assertThat(tables).contains(
                "accounts",
                "transactions",
                "ledger_entries",
                "outbox_events",
                "processed_kafka_events",
                "reconciliation_runs",
                "reconciliation_discrepancies");

        Boolean isPartial = jdbc.queryForObject("""
                SELECT i.indpred IS NOT NULL
                FROM pg_index i
                JOIN pg_class c ON c.oid = i.indexrelid
                WHERE c.relname = 'idx_outbox_pending'
                """, Boolean.class);

        assertThat(isPartial)
                .as("idx_outbox_pending must be a partial index so polling never scans history")
                .isTrue();

        String predicate = jdbc.queryForObject(
                "SELECT pg_get_expr(i.indpred, i.indrelid) FROM pg_index i " +
                        "JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 'idx_outbox_pending'",
                String.class);

        assertThat(predicate).contains("PENDING");
    }

    @Test
    void ledgerTriggerIsInstalledOnTheJournalTable() {
        List<String> triggers = jdbc.queryForList("""
                SELECT tgname
                FROM pg_trigger
                WHERE tgrelid = 'ledger_entries'::regclass
                  AND NOT tgisinternal
                """, String.class);

        assertThat(triggers).contains("trg_immutable_ledger");
    }

    @Test
    void postedTransferProducesBalancedSignedEntries() {
        SeededTransfer transfer = seedPostedTransfer();

        Long journalSum = jdbc.queryForObject(
                "SELECT COALESCE(SUM(signed_amount_minor), 0) FROM ledger_entries WHERE transaction_id = ?",
                Long.class,
                transfer.transactionId());

        assertThat(journalSum)
                .as("every transaction must satisfy sum(signed_amount_minor) == 0")
                .isZero();

        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO ledger_entries
                            (entry_id, transaction_id, account_id, direction, signed_amount_minor, running_balance_minor)
                        VALUES (?, ?, ?, 'DEBIT', 1000, 1000)
                        """,
                        UUID.randomUUID(), transfer.transactionId(), transfer.sourceAccountId()))
                .as("a DEBIT must carry a negative signed amount")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("chk_direction_sign");
    }

    @Test
    void ledgerEntriesCannotBeUpdatedOrDeleted() {
        SeededTransfer transfer = seedPostedTransfer();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ledger_entries SET running_balance_minor = running_balance_minor + 1 " +
                        "WHERE transaction_id = ?",
                transfer.transactionId()))
                .as("the append-only trigger must reject UPDATE")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM ledger_entries WHERE transaction_id = ?",
                transfer.transactionId()))
                .as("the append-only trigger must reject DELETE")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");

        Integer remaining = jdbc.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE transaction_id = ?",
                Integer.class,
                transfer.transactionId());

        assertThat(remaining).isEqualTo(2);
    }

    @Test
    void processedKafkaEventPrimaryKeyRejectsDuplicateDelivery() {
        UUID eventId = UUID.randomUUID();

        jdbc.update("INSERT INTO processed_kafka_events (event_id) VALUES (?)", eventId);

        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO processed_kafka_events (event_id) VALUES (?)", eventId))
                .as("at-least-once delivery must collapse to one consumer side effect")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("processed_kafka_events_pkey");
    }

    @Test
    void reconciliationRunAcceptsAllFourResultStatesAndRejectsUnknownOnes() {
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reconciliation_runs
                    (run_id, source_file_name, status, total_records)
                VALUES (?, 'clearing-2026-10-20.ndjson', 'RUNNING', 4)
                """, runId);

        for (String result : List.of("MATCHED", "AMOUNT_MISMATCH", "MISSING_INTERNAL", "MISSING_EXTERNAL")) {
            jdbc.update("""
                    INSERT INTO reconciliation_discrepancies
                        (run_id, external_reference, status, external_amount_minor, currency)
                    VALUES (?, ?, ?, 1234, 'INR')
                    """, runId, "EXT-" + result, result);
        }

        assertThatThrownBy(() -> jdbc.update("""
                        INSERT INTO reconciliation_discrepancies
                            (run_id, external_reference, status)
                        VALUES (?, 'EXT-BOGUS', 'BOGUS')
                        """, runId))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("reconciliation_discrepancies_status_check");
    }

    private SeededTransfer seedPostedTransfer() {
        UUID sourceAccountId = UUID.randomUUID();
        UUID destinationAccountId = UUID.randomUUID();
        UUID transactionId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO accounts (account_id, owner_id, currency, balance_minor)
                VALUES (?, ?, 'INR', 10000)
                """, sourceAccountId, "owner-" + sourceAccountId);
        jdbc.update("""
                INSERT INTO accounts (account_id, owner_id, currency, balance_minor)
                VALUES (?, ?, 'INR', 0)
                """, destinationAccountId, "owner-" + destinationAccountId);

        jdbc.update("""
                INSERT INTO transactions
                    (transaction_id, idempotency_key, tx_type, status, currency)
                VALUES (?, ?, 'TRANSFER', 'POSTED', 'INR')
                """, transactionId, "idem-" + transactionId);

        jdbc.update("""
                INSERT INTO ledger_entries
                    (entry_id, transaction_id, account_id, direction, signed_amount_minor, running_balance_minor)
                VALUES (?, ?, ?, 'DEBIT', -1000, 9000)
                """, UUID.randomUUID(), transactionId, sourceAccountId);
        jdbc.update("""
                INSERT INTO ledger_entries
                    (entry_id, transaction_id, account_id, direction, signed_amount_minor, running_balance_minor)
                VALUES (?, ?, ?, 'CREDIT', 1000, 1000)
                """, UUID.randomUUID(), transactionId, destinationAccountId);

        return new SeededTransfer(transactionId, sourceAccountId, destinationAccountId);
    }

    private record SeededTransfer(UUID transactionId, UUID sourceAccountId, UUID destinationAccountId) {
    }
}
