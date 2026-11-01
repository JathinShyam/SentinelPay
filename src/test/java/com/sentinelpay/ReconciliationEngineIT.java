package com.sentinelpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentinelpay.reconciliation.domain.ReconciliationSummary;
import com.sentinelpay.reconciliation.service.VirtualThreadReconciliationEngine;
import com.sentinelpay.support.SyntheticClearingFileGenerator;
import com.sentinelpay.support.SyntheticClearingFileGenerator.Fixture;
import com.sentinelpay.support.SyntheticClearingFileGenerator.InternalTransaction;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ReconciliationEngineIT extends AbstractIntegrationTest {

    private static final int SEED_BATCH_SIZE = 1_000;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    VirtualThreadReconciliationEngine engine;

    @Test
    void reconcilesOneHundredThousandRecordsInUnderFiveSeconds() throws Exception {
        LocalDate businessDate = LocalDate.of(2026, 1, 15);
        long namespace = businessDate.toEpochDay();

        Path clearingFile = Files.createTempFile("sentinelpay-full-", ".csv");
        Path warmupFile = Files.createTempFile("sentinelpay-warmup-", ".csv");
        try {
            Fixture fixture = SyntheticClearingFileGenerator.generate(
                    clearingFile, businessDate, namespace);
            seedInternal(fixture);

            LocalDate warmupDate = businessDate.minusDays(1);
            SyntheticClearingFileGenerator.writeMissingInternalFile(
                    warmupFile, warmupDate, namespace ^ 0x5EEDL, 1_000);
            ReconciliationSummary warmup = engine.run(
                    warmupFile, warmupDate, "warmup.csv");
            assertThat(warmup.missingInternalCount()).isEqualTo(1_000);

            ResponseEntity<JsonNode> response = postClearingFile(
                    Files.readAllBytes(clearingFile), "clearing.csv", businessDate);
            assertThat(response.getStatusCode().value())
                    .as("reconciliation response: %s", response.getBody())
                    .isEqualTo(200);

            JsonNode body = response.getBody();
            assertThat(body).isNotNull();
            UUID runId = UUID.fromString(body.path("runId").asText());
            long durationMs = body.path("durationMs").asLong();

            System.out.printf("Phase 4 reconciliation: %d records, %d anomalies, %d ms%n",
                    body.path("totalRecords").asLong(),
                    body.path("amountMismatchCount").asLong()
                            + body.path("missingInternalCount").asLong()
                            + body.path("missingExternalCount").asLong(),
                    durationMs);

            assertThat(body.path("status").asText()).isEqualTo("COMPLETED");
            assertThat(body.path("totalRecords").asLong()).isEqualTo(100_000);
            assertThat(body.path("matchedCount").asLong()).isEqualTo(99_925);
            assertThat(body.path("amountMismatchCount").asLong()).isEqualTo(50);
            assertThat(body.path("missingInternalCount").asLong()).isEqualTo(25);
            assertThat(body.path("missingExternalCount").asLong()).isEqualTo(25);
            assertThat(durationMs).as("measured reconciliation duration").isLessThan(5_000);

            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM reconciliation_discrepancies WHERE run_id = ?",
                    Long.class, runId)).isEqualTo(100L);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM reconciliation_discrepancies WHERE run_id = ? AND type = 'MATCHED'",
                    Long.class, runId)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM reconciliation_runs WHERE run_id = ?",
                    String.class, runId)).isEqualTo("COMPLETED");

            ResponseEntity<JsonNode> run = rest.getForEntity(
                    "/api/v1/reconciliation/runs/" + runId, JsonNode.class);
            assertThat(run.getStatusCode().value()).isEqualTo(200);
            assertThat(run.getBody().path("status").asText()).isEqualTo("COMPLETED");
            assertThat(run.getBody().path("businessDate").asText()).isEqualTo("2026-01-15");

            ResponseEntity<JsonNode> all = rest.getForEntity(
                    "/api/v1/reconciliation/runs/" + runId + "/discrepancies?page=0&size=200",
                    JsonNode.class);
            assertThat(all.getStatusCode().value()).isEqualTo(200);
            assertThat(all.getBody().path("total").asLong()).isEqualTo(100);
            assertThat(all.getBody().path("items")).hasSize(100);

            ResponseEntity<JsonNode> mismatches = rest.getForEntity(
                    "/api/v1/reconciliation/runs/" + runId
                            + "/discrepancies?type=AMOUNT_MISMATCH&page=0&size=10",
                    JsonNode.class);
            assertThat(mismatches.getBody().path("total").asLong()).isEqualTo(50);
            assertThat(mismatches.getBody().path("items")).hasSize(10);
            assertThat(mismatches.getBody().path("items").get(0).path("type").asText())
                    .isEqualTo("AMOUNT_MISMATCH");

            ResponseEntity<JsonNode> missingRun = rest.getForEntity(
                    "/api/v1/reconciliation/runs/" + UUID.randomUUID(), JsonNode.class);
            assertProblem(missingRun, 404, "RECONCILIATION_RUN_NOT_FOUND");
        } finally {
            Files.deleteIfExists(clearingFile);
            Files.deleteIfExists(warmupFile);
        }
    }

    @Test
    void malformedClearingFileFailsTheRunWithLineNumber() {
        String malformed = """
                external_ref,transaction_id,amount_minor,currency,status,settled_at
                EXT-1,not-a-uuid,100,INR,SETTLED,2026-01-13T10:00:00Z
                """;

        ResponseEntity<JsonNode> response = postClearingFile(
                malformed.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "bad.csv", LocalDate.of(2026, 1, 13));

        assertProblem(response, 400, "INVALID_REQUEST");
        assertThat(response.getBody().path("lineNumber").asLong()).isEqualTo(2);
    }

    private void seedInternal(Fixture fixture) {
        UUID debitAccount = new UUID(fixture.namespace(), 1L);
        UUID creditAccount = new UUID(fixture.namespace(), 2L);
        String owner = "reconciliation-fixture-" + fixture.namespace();

        jdbc.update("""
                        INSERT INTO accounts
                            (account_id, owner_id, currency, balance_minor, account_type)
                        VALUES (?, ?, 'INR', 0, 'SYSTEM')
                        ON CONFLICT (account_id) DO NOTHING
                        """, debitAccount, owner);
        jdbc.update("""
                        INSERT INTO accounts
                            (account_id, owner_id, currency, balance_minor, account_type)
                        VALUES (?, ?, 'INR', 0, 'SYSTEM')
                        ON CONFLICT (account_id) DO NOTHING
                        """, creditAccount, owner + "-credit");

        List<InternalTransaction> transactions = fixture.internalTransactions();
        for (int start = 0; start < transactions.size(); start += SEED_BATCH_SIZE) {
            int from = start;
            int to = Math.min(start + SEED_BATCH_SIZE, transactions.size());
            List<InternalTransaction> slice = transactions.subList(from, to);

            jdbc.batchUpdate("""
                            INSERT INTO transactions
                                (transaction_id, idempotency_key, tx_type, status,
                                 currency, created_at)
                            VALUES (?, ?, 'TRANSFER', 'POSTED', ?, ?)
                            """, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement statement, int index) throws SQLException {
                    InternalTransaction transaction = slice.get(index);
                    statement.setObject(1, transaction.transactionId());
                    statement.setString(2, "fixture-" + fixture.namespace()
                            + "-" + transaction.transactionId());
                    statement.setString(3, transaction.currency());
                    statement.setTimestamp(4, Timestamp.from(transaction.createdAt()));
                }

                @Override
                public int getBatchSize() {
                    return slice.size();
                }
            });

            jdbc.batchUpdate("""
                            INSERT INTO ledger_entries
                                (transaction_id, account_id, direction,
                                 signed_amount_minor, running_balance_minor, created_at)
                            VALUES (?, ?, ?, ?, 0, ?)
                            """, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement statement, int index) throws SQLException {
                    InternalTransaction transaction = slice.get(index / 2);
                    boolean debit = index % 2 == 0;
                    statement.setObject(1, transaction.transactionId());
                    statement.setObject(2, debit ? debitAccount : creditAccount);
                    statement.setString(3, debit ? "DEBIT" : "CREDIT");
                    statement.setLong(4, debit
                            ? -transaction.amountMinor() : transaction.amountMinor());
                    statement.setTimestamp(5, Timestamp.from(transaction.createdAt()));
                }

                @Override
                public int getBatchSize() {
                    return slice.size() * 2;
                }
            });
        }

        // Keep the shared-database invariant honest: fixture postings must be
        // reflected in the materialized balance like any production transaction.
        jdbc.update("""
                UPDATE accounts a
                SET balance_minor = COALESCE((
                    SELECT SUM(e.signed_amount_minor)
                    FROM ledger_entries e
                    WHERE e.account_id = a.account_id
                ), 0)
                WHERE a.account_id IN (?, ?)
                """, debitAccount, creditAccount);
    }

    private ResponseEntity<JsonNode> postClearingFile(byte[] content,
                                                      String fileName,
                                                      LocalDate businessDate) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        parts.add("businessDate", businessDate.toString());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.postForEntity("/api/v1/reconciliation/run",
                new HttpEntity<>(parts, headers), JsonNode.class);
    }
}
