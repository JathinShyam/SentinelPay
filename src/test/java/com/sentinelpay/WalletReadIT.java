package com.sentinelpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentinelpay.support.TestClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Read-only customer views: the overview total, the per-account statement,
 * cursor pagination and transaction detail. All amounts are asserted in
 * integer minor units.
 */
class WalletReadIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "wallet-read-password";

    @Test
    void overviewSumsCurrenciesAndProvesTheJournal() {
        TestClient customer = customer();
        UUID inr = account(customer, "INR", 100_000);
        UUID savings = account(customer, "INR", 0);
        account(customer, "USD", 5_000);

        ResponseEntity<JsonNode> overview = customer.get("/api/v1/me/overview");
        assertThat(overview.getStatusCode().value()).isEqualTo(200);

        JsonNode body = overview.getBody();
        assertThat(body.path("totals")).hasSize(2);
        assertThat(currencyTotal(body, "INR")).isEqualTo(100_000L);
        assertThat(currencyTotal(body, "USD")).isEqualTo(5_000L);
        assertThat(body.path("accounts")).hasSize(3);
        assertThat(body.path("proof").path("balanced").asBoolean()).isTrue();
        assertThat(body.path("proof").path("accountsChecked").asInt()).isEqualTo(3);
        assertThat(body.path("proof").path("driftMinor").asLong()).isZero();
        assertThat(body.path("proof").path("entryCount").asLong()).isGreaterThanOrEqualTo(2);

        transfer(customer, inr, savings, 25_000);

        // A user-to-user transfer moves value between the customer's own
        // accounts, so the per-currency total must not change.
        assertThat(currencyTotal(customer.get("/api/v1/me/overview").getBody(), "INR"))
                .isEqualTo(100_000L);
    }

    @Test
    void accountStatementIsNewestFirstAndCursorPaginated() {
        TestClient customer = customer();
        UUID source = account(customer, "INR", 100_000);
        UUID destination = account(customer, "INR", 0);
        UUID transactionId = transfer(customer, source, destination, 25_000);

        ResponseEntity<JsonNode> firstPage = customer.get(
                "/api/v1/accounts/" + source + "/transactions?limit=1");
        assertThat(firstPage.getStatusCode().value()).isEqualTo(200);
        JsonNode firstItem = firstPage.getBody().path("items").get(0);
        assertThat(firstItem.path("transactionId").asText())
                .isEqualTo(transactionId.toString());
        assertThat(firstItem.path("direction").asText()).isEqualTo("DEBIT");
        assertThat(firstItem.path("amountMinor").asLong()).isEqualTo(25_000L);
        assertThat(firstItem.path("balanceAfterMinor").asLong()).isEqualTo(75_000L);
        assertThat(firstItem.path("counterpartyAccountId").asText())
                .isEqualTo(destination.toString());
        assertThat(firstPage.getBody().path("nextCursor").isTextual()).isTrue();

        String cursor = firstPage.getBody().path("nextCursor").asText();
        ResponseEntity<JsonNode> secondPage = customer.get(
                "/api/v1/accounts/" + source + "/transactions?limit=1&cursor=" + cursor);
        assertThat(secondPage.getStatusCode().value()).isEqualTo(200);
        JsonNode deposit = secondPage.getBody().path("items").get(0);
        assertThat(deposit.path("type").asText()).isEqualTo("DEPOSIT");
        assertThat(deposit.path("direction").asText()).isEqualTo("CREDIT");
        assertThat(deposit.path("balanceAfterMinor").asLong()).isEqualTo(100_000L);
        assertThat(secondPage.getBody().path("nextCursor").isMissingNode()
                || secondPage.getBody().path("nextCursor").isNull()).isTrue();

        assertProblem(customer.get("/api/v1/accounts/" + source
                + "/transactions?cursor=not-a-cursor"), 400, "INVALID_REQUEST");
        assertProblem(customer.get("/api/v1/accounts/" + source
                + "/transactions?limit=101"), 400, "INVALID_REQUEST");
    }

    @Test
    void transactionDetailExposesBalancedJournalEntries() {
        TestClient customer = customer();
        UUID source = account(customer, "INR", 50_000);
        UUID destination = account(customer, "INR", 0);
        UUID transactionId = transfer(customer, source, destination, 12_500);

        ResponseEntity<JsonNode> detail = customer.get(
                "/api/v1/transactions/" + transactionId);
        assertThat(detail.getStatusCode().value()).isEqualTo(200);
        JsonNode body = detail.getBody();
        assertThat(body.path("type").asText()).isEqualTo("TRANSFER");
        assertThat(body.path("status").asText()).isEqualTo("POSTED");
        assertThat(body.path("entries")).hasSize(2);

        long sum = 0;
        for (JsonNode entry : body.path("entries")) {
            long amount = entry.path("amountMinor").asLong();
            sum += entry.path("direction").asText().equals("DEBIT") ? -amount : amount;
        }
        assertThat(sum).as("journal entries must net to zero").isZero();
    }

    @Test
    void statementsAndDetailsAreInvisibleToOtherCustomers() {
        TestClient alice = customer();
        TestClient bob = customer();
        UUID source = account(alice, "INR", 10_000);
        UUID destination = account(alice, "INR", 0);
        UUID transactionId = transfer(alice, source, destination, 1_000);
        UUID bobAccount = account(bob, "INR", 0);
        UUID externalTransactionId = transfer(alice, source, bobAccount, 500);

        assertProblem(bob.get("/api/v1/accounts/" + source + "/transactions"),
                404, "ACCOUNT_NOT_FOUND");
        assertProblem(bob.get("/api/v1/transactions/" + transactionId),
                404, "TRANSACTION_NOT_FOUND");
        assertProblem(bob.get("/api/v1/transactions/" + UUID.randomUUID()),
                404, "TRANSACTION_NOT_FOUND");

        // The counterparty's running balance is masked from Alice; only the
        // account she can inspect keeps its post-entry balance.
        JsonNode aliceDetail = alice.get("/api/v1/transactions/" + externalTransactionId).getBody();
        for (JsonNode entry : aliceDetail.path("entries")) {
            boolean visible = entry.path("accountId").asText().equals(source.toString());
            assertThat(entry.hasNonNull("runningBalanceMinor"))
                    .as("counterparty balance visibility for %s", entry)
                    .isEqualTo(visible);
        }

        // The administrator can inspect every statement and every balance.
        assertThat(rest.getForEntity("/api/v1/accounts/" + source + "/transactions",
                JsonNode.class).getStatusCode().value()).isEqualTo(200);
        assertThat(rest.getForEntity("/api/v1/transactions/" + transactionId,
                JsonNode.class).getStatusCode().value()).isEqualTo(200);
        JsonNode adminDetail = rest.getForEntity(
                "/api/v1/transactions/" + externalTransactionId, JsonNode.class).getBody();
        for (JsonNode entry : adminDetail.path("entries")) {
            assertThat(entry.hasNonNull("runningBalanceMinor")).isTrue();
        }
    }

    private TestClient customer() {
        String username = "wallet-" + UUID.randomUUID().toString().substring(0, 12);
        TestClient client = newClient();
        client.refreshCsrf();
        ResponseEntity<JsonNode> registration = client.postJson("/api/v1/auth/register",
                Map.of("username", username, "displayName", "Wallet Customer",
                        "password", PASSWORD), null);
        assertThat(registration.getStatusCode().value()).isEqualTo(201);
        assertThat(client.login(username, PASSWORD).getStatusCode().value()).isEqualTo(200);
        return client;
    }

    private UUID account(TestClient client, String currency, long deposit) {
        ResponseEntity<JsonNode> response = client.postJson("/api/v1/accounts",
                Map.of("currency", currency, "initialDepositMinor", deposit), null);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return UUID.fromString(response.getBody().path("accountId").asText());
    }

    private UUID transfer(TestClient client, UUID source, UUID destination, long amount) {
        ResponseEntity<JsonNode> response = client.postJson("/api/v1/transfers", Map.of(
                "sourceAccountId", source,
                "destinationAccountId", destination,
                "amountMinor", amount,
                "currency", "INR"), "wallet-read-" + UUID.randomUUID());
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return UUID.fromString(response.getBody().path("transactionId").asText());
    }

    private static long currencyTotal(JsonNode overview, String currency) {
        for (JsonNode total : overview.path("totals")) {
            if (total.path("currency").asText().equals(currency)) {
                return total.path("balanceMinor").asLong();
            }
        }
        throw new AssertionError("No total for " + currency + ": " + overview);
    }
}
