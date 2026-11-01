package com.sentinelpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentinelpay.ledger.domain.AccountView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class LedgerTransferIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void happyPathPostsExactlyTwoBalancedEntriesWithRunningBalances() {
        AccountView source = createAccount("owner-" + UUID.randomUUID(), "INR", 50_000);
        AccountView destination = createAccount("owner-" + UUID.randomUUID(), "INR", 0);
        String key = "transfer-" + UUID.randomUUID();

        ResponseEntity<JsonNode> response = transfer(source.accountId(), destination.accountId(),
                12_500, "INR", key);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        UUID transactionId = UUID.fromString(response.getBody().path("transactionId").asText());
        assertThat(response.getBody().path("idempotencyKey").asText()).isEqualTo(key);
        assertThat(response.getBody().path("status").asText()).isEqualTo("POSTED");
        assertThat(response.getBody().path("sourceBalanceMinorAfter").asLong()).isEqualTo(37_500);
        assertThat(response.getBody().path("destinationBalanceMinorAfter").asLong()).isEqualTo(12_500);

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE transaction_id = ?", Long.class, transactionId))
                .isEqualTo(2L);
        assertThat(jdbc.queryForObject(
                "SELECT SUM(signed_amount_minor) FROM ledger_entries WHERE transaction_id = ?",
                Long.class, transactionId))
                .isZero();

        assertThat(jdbc.queryForObject(
                "SELECT balance_minor FROM accounts WHERE account_id = ?", Long.class, source.accountId()))
                .isEqualTo(37_500L);
        assertThat(jdbc.queryForObject(
                "SELECT balance_minor FROM accounts WHERE account_id = ?", Long.class,
                destination.accountId()))
                .isEqualTo(12_500L);
    }

    @Test
    void idempotentReplayReturnsTheSameTransactionAndDoesNotDoubleDebit() {
        AccountView source = createAccount("owner-" + UUID.randomUUID(), "INR", 20_000);
        AccountView destination = createAccount("owner-" + UUID.randomUUID(), "INR", 0);
        String key = "replay-" + UUID.randomUUID();

        ResponseEntity<JsonNode> first = transfer(source.accountId(), destination.accountId(),
                5_000, "INR", key);
        ResponseEntity<JsonNode> second = transfer(source.accountId(), destination.accountId(),
                5_000, "INR", key);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(second.getBody().path("transactionId").asText())
                .isEqualTo(first.getBody().path("transactionId").asText());

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE idempotency_key = ?", Long.class, key))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT balance_minor FROM accounts WHERE account_id = ?", Long.class, source.accountId()))
                .isEqualTo(15_000L);
    }

    @Test
    void unknownAccountReturns404ProblemDetail() {
        AccountView source = createAccount("owner-" + UUID.randomUUID(), "INR", 10_000);

        ResponseEntity<JsonNode> response = transfer(source.accountId(), UUID.randomUUID(),
                100, "INR", "missing-" + UUID.randomUUID());

        assertProblem(response, 404, "ACCOUNT_NOT_FOUND");
    }

    @Test
    void frozenAndClosedAccountsReturn422() {
        AccountView source = createAccount("owner-" + UUID.randomUUID(), "INR", 10_000);
        AccountView destination = createAccount("owner-" + UUID.randomUUID(), "INR", 0);
        jdbc.update("UPDATE accounts SET status = 'FROZEN' WHERE account_id = ?", source.accountId());

        ResponseEntity<JsonNode> frozen = transfer(source.accountId(), destination.accountId(),
                100, "INR", "frozen-" + UUID.randomUUID());
        assertProblem(frozen, 422, "ACCOUNT_NOT_ACTIVE");

        jdbc.update("UPDATE accounts SET status = 'CLOSED' WHERE account_id = ?", destination.accountId());
        jdbc.update("UPDATE accounts SET status = 'ACTIVE' WHERE account_id = ?", source.accountId());
        ResponseEntity<JsonNode> closed = transfer(source.accountId(), destination.accountId(),
                100, "INR", "closed-" + UUID.randomUUID());
        assertProblem(closed, 422, "ACCOUNT_NOT_ACTIVE");
    }

    @Test
    void insufficientFundsAndCurrencyMismatchReturn422() {
        AccountView source = createAccount("owner-" + UUID.randomUUID(), "INR", 1_000);
        AccountView destination = createAccount("owner-" + UUID.randomUUID(), "INR", 0);
        AccountView usd = createAccount("owner-" + UUID.randomUUID(), "USD", 0);

        assertProblem(transfer(source.accountId(), destination.accountId(), 1_001, "INR",
                "funds-" + UUID.randomUUID()), 422, "INSUFFICIENT_FUNDS");

        assertProblem(transfer(source.accountId(), usd.accountId(), 100, "INR",
                "currency-" + UUID.randomUUID()), 422, "CURRENCY_MISMATCH");
    }

    @Test
    void invalidAmountAndIdenticalAccountsReturn400() {
        AccountView source = createAccount("owner-" + UUID.randomUUID(), "INR", 10_000);
        AccountView destination = createAccount("owner-" + UUID.randomUUID(), "INR", 0);

        assertProblem(transfer(source.accountId(), destination.accountId(), 0, "INR",
                "zero-" + UUID.randomUUID()), 400, "INVALID_AMOUNT");

        assertProblem(transfer(source.accountId(), source.accountId(), 100, "INR",
                "same-" + UUID.randomUUID()), 400, "INVALID_AMOUNT");
    }

    @Test
    void missingIdempotencyKeyReturns400() {
        AccountView source = createAccount("owner-" + UUID.randomUUID(), "INR", 10_000);
        AccountView destination = createAccount("owner-" + UUID.randomUUID(), "INR", 0);

        ResponseEntity<JsonNode> response = transfer(source.accountId(), destination.accountId(),
                100, "INR", null);

        assertProblem(response, 400, "IDEMPOTENCY_KEY_REQUIRED");
    }
}
