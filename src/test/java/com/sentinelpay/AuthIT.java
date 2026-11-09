package com.sentinelpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentinelpay.support.TestClient;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 6 verification gate: real session authentication.
 *
 * <p>Registration, login, CSRF, session lifecycle, ownership isolation and the
 * admin-only operational endpoints are exercised against the real filter chain.
 */
class AuthIT extends AbstractIntegrationTest {

    private static final String PASSWORD = "correct-horse-battery";

    @Test
    void registrationLoginAndLogoutLifecycle() {
        String username = uniqueUsername();
        TestClient client = newClient();

        assertThat(client.refreshCsrf()).isNotBlank();
        ResponseEntity<JsonNode> csrf = client.get("/api/v1/auth/csrf");
        assertThat(csrf.getStatusCode().value()).isEqualTo(200);
        assertThat(csrf.getBody().path("headerName").asText()).isEqualTo("X-XSRF-TOKEN");

        ResponseEntity<JsonNode> registration = register(client, username, "Alice Example");
        assertThat(registration.getStatusCode().value()).isEqualTo(201);
        assertThat(registration.getBody().path("username").asText()).isEqualTo(username);
        assertThat(registration.getBody().path("role").asText()).isEqualTo("USER");

        ResponseEntity<JsonNode> login = client.login(username, PASSWORD);
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        assertThat(login.getBody().path("displayName").asText()).isEqualTo("Alice Example");

        TestClient caseInsensitive = newClient();
        assertThat(caseInsensitive.login(username.toUpperCase(java.util.Locale.ROOT), PASSWORD)
                .getStatusCode().value()).isEqualTo(200);

        ResponseEntity<JsonNode> me = client.get("/api/v1/auth/me");
        assertThat(me.getStatusCode().value()).isEqualTo(200);
        assertThat(me.getBody().path("username").asText()).isEqualTo(username);

        ResponseEntity<JsonNode> accounts = client.get("/api/v1/me/accounts");
        assertThat(accounts.getStatusCode().value()).isEqualTo(200);
        assertThat(accounts.getBody().isArray()).isTrue();
        assertThat(accounts.getBody()).isEmpty();

        ResponseEntity<JsonNode> logout = client.logout();
        assertThat(logout.getStatusCode().value()).isEqualTo(204);

        assertProblem(client.get("/api/v1/auth/me"), 401, "UNAUTHENTICATED");
    }

    @Test
    void unauthenticatedAndCsrfLessRequestsAreRejectedWithProblemDetails() {
        TestClient anonymous = newRawClient();

        assertProblem(anonymous.get("/api/v1/me/accounts"), 401, "UNAUTHENTICATED");
        assertProblem(anonymous.get("/api/v1/accounts/" + UUID.randomUUID()), 401,
                "UNAUTHENTICATED");

        // CSRF is enforced on the public mutation endpoints too, otherwise login
        // itself would be forgeable from another origin.
        assertProblem(anonymous.postJson("/api/v1/auth/register", Map.of(
                "username", uniqueUsername(),
                "displayName", "CSRF Probe",
                "password", PASSWORD), null), 403, "FORBIDDEN");
        assertProblem(anonymous.postJson("/api/v1/auth/login", Map.of(
                "username", "admin",
                "password", "admin-password"), null), 403, "FORBIDDEN");

        TestClient client = newClient();
        assertProblem(client.login("admin", "wrong-password"), 401, "INVALID_CREDENTIALS");
    }

    @Test
    void duplicateUsernamesAreRejectedCaseInsensitively() {
        String username = uniqueUsername();
        TestClient first = newClient();
        assertThat(register(first, username, "First Owner").getStatusCode().value())
                .isEqualTo(201);

        TestClient second = newClient();
        assertProblem(register(second, username.toUpperCase(), "Second Owner"),
                409, "USERNAME_TAKEN");
    }

    @Test
    void authenticatedMutationsRequireACsrfToken() {
        TestClient client = loggedInUser();

        client.clearCsrf();
        assertProblem(client.postJson("/api/v1/accounts",
                Map.of("currency", "INR", "initialDepositMinor", 0), null),
                403, "FORBIDDEN");

        client.refreshCsrf();
        ResponseEntity<JsonNode> created = client.postJson("/api/v1/accounts",
                Map.of("currency", "INR", "initialDepositMinor", 0), null);
        assertThat(created.getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void customersOnlySeeAndSpendFromTheirOwnAccounts() {
        TestClient alice = loggedInUser();
        TestClient bob = loggedInUser();

        ResponseEntity<JsonNode> aliceMe = alice.get("/api/v1/auth/me");
        String aliceId = aliceMe.getBody().path("userId").asText();

        JsonNode aliceAccount = createAccount(alice, "INR", 100_000);
        UUID aliceAccountId = UUID.fromString(aliceAccount.path("accountId").asText());
        assertThat(aliceAccount.path("ownerId").asText()).isEqualTo(aliceId);

        assertThat(alice.get("/api/v1/accounts/" + aliceAccountId).getStatusCode().value())
                .isEqualTo(200);
        assertThat(alice.get("/api/v1/me/accounts").getBody()).hasSize(1);

        // A foreign account is indistinguishable from a missing one.
        assertProblem(bob.get("/api/v1/accounts/" + aliceAccountId), 404, "ACCOUNT_NOT_FOUND");
        assertProblem(bob.get("/api/v1/accounts/" + aliceAccountId + "/audit"),
                404, "ACCOUNT_NOT_FOUND");
        assertProblem(bob.postJson("/api/v1/transfers", Map.of(
                "sourceAccountId", aliceAccountId,
                "destinationAccountId", UUID.randomUUID(),
                "amountMinor", 1_000,
                "currency", "INR"), "bob-steals-" + UUID.randomUUID()),
                404, "ACCOUNT_NOT_FOUND");

        JsonNode bobAccount = createAccount(bob, "INR", 5_000);
        assertThat(bob.get("/api/v1/me/accounts").getBody()).hasSize(1);
        assertThat(bob.get("/api/v1/me/accounts").getBody().get(0).path("accountId").asText())
                .isEqualTo(bobAccount.path("accountId").asText());

        // Operators can inspect any account.
        assertThat(rest.getForEntity("/api/v1/accounts/" + aliceAccountId, JsonNode.class)
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void reversalAndReconciliationAreAdminOnly() {
        TestClient alice = loggedInUser();
        TestClient bob = loggedInUser();

        UUID source = UUID.fromString(createAccount(alice, "INR", 50_000)
                .path("accountId").asText());
        UUID destination = UUID.fromString(createAccount(alice, "INR", 0)
                .path("accountId").asText());

        ResponseEntity<JsonNode> transfer = alice.postJson("/api/v1/transfers", Map.of(
                "sourceAccountId", source,
                "destinationAccountId", destination,
                "amountMinor", 1_000,
                "currency", "INR"), "auth-reverse-" + UUID.randomUUID());
        assertThat(transfer.getStatusCode().value()).isEqualTo(201);
        String transactionId = transfer.getBody().path("transactionId").asText();

        assertProblem(bob.postJson("/api/v1/transactions/" + transactionId + "/reverse",
                Map.of(), "bob-reverse-" + UUID.randomUUID()), 403, "FORBIDDEN");
        assertProblem(alice.postJson("/api/v1/transactions/" + transactionId + "/reverse",
                Map.of(), "alice-reverse-" + UUID.randomUUID()), 403, "FORBIDDEN");

        ResponseEntity<JsonNode> reversal = rest.postForEntity(
                "/api/v1/transactions/" + transactionId + "/reverse",
                jsonEntity(Map.of("description", "operator correction"),
                        "admin-reverse-" + UUID.randomUUID()),
                JsonNode.class);
        assertThat(reversal.getStatusCode().value()).isEqualTo(201);

        assertProblem(alice.postMultipart("/api/v1/reconciliation/run",
                "externalId,amountMinor,currency,businessDate\n".getBytes(),
                "clearing.csv", "2027-01-01"), 403, "FORBIDDEN");

        assertThat(rest.getForEntity("/api/v1/reconciliation/runs/" + UUID.randomUUID(),
                JsonNode.class).getStatusCode().value()).isEqualTo(404);
    }

    private TestClient loggedInUser() {
        String username = uniqueUsername();
        TestClient client = newClient();
        assertThat(register(client, username, "Wallet Customer").getStatusCode().value())
                .isEqualTo(201);
        ResponseEntity<JsonNode> login = client.login(username, PASSWORD);
        assertThat(login.getStatusCode().value()).isEqualTo(200);
        return client;
    }

    private ResponseEntity<JsonNode> register(TestClient client, String username,
                                              String displayName) {
        client.refreshCsrf();
        return client.postJson("/api/v1/auth/register", Map.of(
                "username", username,
                "displayName", displayName,
                "password", PASSWORD), null);
    }

    private JsonNode createAccount(TestClient client, String currency, long deposit) {
        ResponseEntity<JsonNode> response = client.postJson("/api/v1/accounts",
                Map.of("currency", currency, "initialDepositMinor", deposit), null);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return response.getBody();
    }

    private static String uniqueUsername() {
        return "user-" + UUID.randomUUID().toString().substring(0, 12);
    }
}
