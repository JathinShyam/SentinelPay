package com.sentinelpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentinelpay.ledger.domain.AccountView;
import com.sentinelpay.reconciliation.service.VirtualThreadReconciliationEngine;
import com.sentinelpay.support.SyntheticClearingFileGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    VirtualThreadReconciliationEngine engine;

    @Test
    void prometheusExposesRequiredMeterFamiliesAndTags() throws Exception {
        AccountView source = createAccount("metrics-" + UUID.randomUUID(), "INR", 100_000);
        AccountView destination = createAccount("metrics-" + UUID.randomUUID(), "INR", 0);
        String key = "metrics-replay-" + UUID.randomUUID();

        assertThat(transfer(source.accountId(), destination.accountId(), 1_000, "INR", key)
                .getStatusCode().value()).isEqualTo(201);
        assertThat(transfer(source.accountId(), destination.accountId(), 1_000, "INR", key)
                .getStatusCode().value()).isEqualTo(200);
        assertProblem(transfer(source.accountId(), UUID.randomUUID(), 100, "INR",
                "metrics-missing-" + UUID.randomUUID()), 404, "ACCOUNT_NOT_FOUND");

        AccountView usd = createAccount("metrics-" + UUID.randomUUID(), "USD", 0);
        assertProblem(transfer(source.accountId(), usd.accountId(), 100, "INR",
                "metrics-currency-" + UUID.randomUUID()), 422, "CURRENCY_MISMATCH");
        assertProblem(transfer(source.accountId(), destination.accountId(), 1_000_000, "INR",
                "metrics-funds-" + UUID.randomUUID()), 422, "INSUFFICIENT_FUNDS");

        jdbc.update("UPDATE accounts SET status = 'FROZEN' WHERE account_id = ?", source.accountId());
        assertProblem(transfer(source.accountId(), destination.accountId(), 100, "INR",
                "metrics-frozen-" + UUID.randomUUID()), 422, "ACCOUNT_NOT_ACTIVE");

        LocalDate reconciliationDate = LocalDate.of(2027, 3, 3);
        Path clearingFile = Files.createTempFile("sentinelpay-metrics-", ".csv");
        try {
            SyntheticClearingFileGenerator.writeMissingInternalFile(
                    clearingFile, reconciliationDate, reconciliationDate.toEpochDay(), 1);
            engine.run(clearingFile, reconciliationDate, "metrics.csv");
        } finally {
            Files.deleteIfExists(clearingFile);
        }

        ResponseEntity<String> scrape = rest.getForEntity("/actuator/prometheus", String.class);
        assertThat(scrape.getStatusCode().value()).isEqualTo(200);
        String metrics = scrape.getBody();
        assertThat(metrics).isNotNull();

        assertThat(metrics).contains("sentinelpay_ledger_transfers_total");
        assertStatus(metrics, "POSTED");
        assertStatus(metrics, "IDEMPOTENT_REPLAY");
        assertStatus(metrics, "INSUFFICIENT_FUNDS");
        assertStatus(metrics, "CURRENCY_MISMATCH");
        assertStatus(metrics, "ACCOUNT_NOT_FOUND");
        assertStatus(metrics, "ACCOUNT_NOT_ACTIVE");

        assertThat(metrics).contains("sentinelpay_ledger_lock_acquisition_duration_seconds");
        assertThat(metrics).contains("sentinelpay_ledger_transfer_duration_seconds");
        assertThat(metrics).contains("sentinelpay_outbox_pending_gauge");
        assertThat(metrics).contains("sentinelpay_outbox_lag_seconds");
        assertThat(metrics).contains("sentinelpay_virtual_threads_active");

        assertThat(metrics.lines().anyMatch(line ->
                line.startsWith("sentinelpay_reconciliation_discrepancies_total")
                        && line.contains("type=\"MISSING_INTERNAL\"")))
                .as("reconciliation discrepancy counter with type tag")
                .isTrue();
    }

    private void assertStatus(String metrics, String status) {
        assertThat(metrics.lines().anyMatch(line ->
                line.startsWith("sentinelpay_ledger_transfers_total")
                        && line.contains("status=\"" + status + "\"")
                        && line.contains("currency=\"INR\"")))
                .as("transfer counter line for status %s", status)
                .isTrue();
    }
}
