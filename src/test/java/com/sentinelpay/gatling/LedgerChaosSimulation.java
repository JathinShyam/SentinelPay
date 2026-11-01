package com.sentinelpay.gatling;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.gatling.javaapi.core.FeederBuilder;
import io.gatling.javaapi.core.PopulationBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import io.gatling.javaapi.http.HttpRequestActionBuilder;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.constantUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.csv;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.percent;
import static io.gatling.javaapi.core.CoreDsl.rampUsersPerSec;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.status;

/**
 * Phase 5 chaos load profile:
 * <ul>
 *   <li>60 s ramp from 1 to 2,500 users/s.</li>
 *   <li>120 s sustained at 2,500 users/s, 90% transfers / 10% audit.</li>
 *   <li>One error-path user exercising duplicate-key replay and insufficient funds.</li>
 * </ul>
 * The sustained phase is measured separately so the ramp does not dilute
 * achieved throughput.
 */
public class LedgerChaosSimulation extends Simulation {

    private static final int ACCOUNT_COUNT =
            integerSetting("sentinelpay.gatling.accounts", "SENTINELPAY_GATLING_ACCOUNTS", 10_000);
    private static final double TARGET_RPS = Double.parseDouble(
            setting("sentinelpay.gatling.targetRps", "SENTINELPAY_GATLING_TARGET_RPS", "2500"));
    private static final int RAMP_SECONDS =
            integerSetting("sentinelpay.gatling.rampSeconds", "SENTINELPAY_GATLING_RAMP_SECONDS", 60);
    private static final int SUSTAIN_SECONDS =
            integerSetting("sentinelpay.gatling.sustainSeconds", "SENTINELPAY_GATLING_SUSTAIN_SECONDS", 120);
    private static final String BASE_URL =
            setting("sentinelpay.baseUrl", "SENTINELPAY_BASE_URL", "http://127.0.0.1:8080");
    private static final long INITIAL_DEPOSIT_MINOR = 100_000_000L;
    private static final long TRANSFER_AMOUNT_MINOR = 100L;
    private static final boolean REUSE_FEEDER =
            booleanSetting("sentinelpay.gatling.reuseFeeder", "SENTINELPAY_GATLING_REUSE_FEEDER", true);
    private static final Path FEEDER_FILE =
            Path.of("target", "gatling", "sentinelpay-accounts.csv");

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpProtocolBuilder httpProtocol = io.gatling.javaapi.http.HttpDsl.http
            .baseUrl(baseUrl())
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .shareConnections();

    {
        try {
            if (!REUSE_FEEDER || !hasUsableFeeder()) {
                seedAccounts();
            }
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not seed Gatling accounts; is SentinelPay running at " + baseUrl() + "?",
                    exception);
        }

        FeederBuilder<String> accounts = csv(FEEDER_FILE.toAbsolutePath().toString()).circular();

        ScenarioBuilder ramp = scenario("Warm-up ramp")
                .feed(accounts)
                .exec((Session session) -> session.set("idem", UUID.randomUUID().toString()))
                .randomSwitch().on(
                        percent(90).then(exec(transfer("Ramp request", TRANSFER_AMOUNT_MINOR, "#{idem}"))),
                        percent(10).then(exec(audit("Ramp request"))));

        ScenarioBuilder sustained = scenario("Sustained load")
                .feed(accounts)
                .exec((Session session) -> session.set("idem", UUID.randomUUID().toString()))
                .randomSwitch().on(
                        percent(90).then(exec(transfer("Sustained request", TRANSFER_AMOUNT_MINOR, "#{idem}"))),
                        percent(10).then(exec(audit("Sustained request"))));

        ScenarioBuilder errorPaths = scenario("Error paths")
                .feed(accounts)
                .exec((Session session) -> session.set("idem", UUID.randomUUID().toString()))
                .exec((Session session) -> session.set("dupKey", "gatling-dup-" + UUID.randomUUID()))
                .exec(transfer("Error path duplicate create", 100L, "#{dupKey}", 201))
                .exec(transfer("Error path duplicate replay", 100L, "#{dupKey}", 200))
                .exec(transfer("Error path insufficient funds", 200_000_000L, "#{idem}", 422));

        PopulationBuilder rampPopulation = ramp
                .injectOpen(rampUsersPerSec(1).to(TARGET_RPS).during(RAMP_SECONDS))
                .protocols(httpProtocol);
        PopulationBuilder sustainedPopulation = sustained
                .injectOpen(constantUsersPerSec(TARGET_RPS).during(SUSTAIN_SECONDS))
                .protocols(httpProtocol);
        PopulationBuilder errorPopulation = errorPaths
                .injectOpen(atOnceUsers(1))
                .protocols(httpProtocol);

        setUp(rampPopulation.andThen(sustainedPopulation).andThen(errorPopulation))
                .assertions(
                        details("Sustained request").requestsPerSec().gte(TARGET_RPS),
                        details("Sustained request").responseTime().percentile4().lt(25),
                        global().failedRequests().percent().lt(0.1));
    }

    private HttpRequestActionBuilder transfer(String name,
                                              long amountMinor,
                                              String idempotencyKey,
                                              int... expectedStatuses) {
        Integer[] statuses = expectedStatuses.length == 0
                ? new Integer[]{200, 201}
                : java.util.Arrays.stream(expectedStatuses).boxed().toArray(Integer[]::new);
        return http(name)
                .post("/api/v1/transfers")
                .header("Idempotency-Key", idempotencyKey)
                .body(StringBody(session -> """
                        {"sourceAccountId":"%s","destinationAccountId":"%s",\
                        "amountMinor":%d,"currency":"INR"}
                        """.formatted(
                        session.getString("sourceAccountId"),
                        session.getString("destinationAccountId"),
                        amountMinor)))
                .check(status().in(statuses));
    }

    private HttpRequestActionBuilder audit(String name) {
        return http(name)
                .get("/api/v1/accounts/#{sourceAccountId}/audit")
                .check(status().is(200));
    }

    /**
     * Reuses an existing feeder when its first account still exists in the
     * target database. This keeps repeated benchmark runs from paying a
     * 10,000-account setup cost and from adding seed traffic to the measured
     * stack. A clean checkout falls through to seeding.
     */
    private boolean hasUsableFeeder() {
        if (!Files.isRegularFile(FEEDER_FILE)) {
            return false;
        }
        try {
            List<String> lines = Files.readAllLines(FEEDER_FILE, StandardCharsets.UTF_8);
            if (lines.size() - 1 < ACCOUNT_COUNT) {
                return false;
            }
            String[] firstRow = lines.get(1).split(",");
            if (firstRow.length != 2) {
                return false;
            }
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(baseUrl() + "/api/v1/accounts/" + firstRow[0]))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                System.out.printf("Reusing %d existing Gatling accounts from %s%n",
                        lines.size() - 1, FEEDER_FILE.toAbsolutePath());
                return true;
            }
        } catch (Exception exception) {
            System.out.println("Existing Gatling feeder is not usable ("
                    + exception.getMessage() + "); seeding fresh accounts");
        }
        return false;
    }

    private void seedAccounts() throws Exception {
        Files.createDirectories(FEEDER_FILE.getParent());
        String[] accountIds = new String[ACCOUNT_COUNT];
        HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        Semaphore permits = new Semaphore(32);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>(ACCOUNT_COUNT);
            for (int index = 0; index < ACCOUNT_COUNT; index++) {
                int slot = index;
                futures.add(executor.submit(() -> {
                    permits.acquireUninterruptibly();
                    try {
                        accountIds[slot] = createAccount(client);
                    } catch (Exception exception) {
                        throw new IllegalStateException("Seeding account " + slot + " failed",
                                exception);
                    } finally {
                        permits.release();
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get();
            }
        }

        StringBuilder csv = new StringBuilder("sourceAccountId,destinationAccountId\n");
        for (int index = 0; index < ACCOUNT_COUNT; index++) {
            csv.append(accountIds[index]).append(',')
                    .append(accountIds[(index + 1) % ACCOUNT_COUNT]).append('\n');
        }
        Files.writeString(FEEDER_FILE, csv.toString(), StandardCharsets.UTF_8);
        System.out.printf("Seeded %d funded accounts for Gatling -> %s%n",
                ACCOUNT_COUNT, FEEDER_FILE.toAbsolutePath());
    }

    private String createAccount(HttpClient client) throws Exception {
        String body = """
                {"ownerId":"gatling-%s","currency":"INR","initialDepositMinor":%d}
                """.formatted(UUID.randomUUID(), INITIAL_DEPOSIT_MINOR);
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/accounts"))
                .timeout(Duration.ofSeconds(60))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        IOException lastFailure = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpResponse<String> response = client.send(request,
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 201) {
                    JsonNode json = objectMapper.readTree(response.body());
                    return json.path("accountId").asText();
                }
                if (response.statusCode() < 500) {
                    throw new IllegalStateException("Account creation failed with HTTP "
                            + response.statusCode() + ": " + response.body());
                }
                lastFailure = new IOException("Account creation failed with HTTP "
                        + response.statusCode() + ": " + response.body());
            } catch (IOException exception) {
                lastFailure = exception;
            }
            if (attempt < 3) {
                Thread.sleep(attempt * 1_000L);
            }
        }
        throw new IOException("Account creation failed after 3 attempts", lastFailure);
    }

    private String baseUrl() {
        return BASE_URL;
    }

    private static int integerSetting(String property, String environment, int fallback) {
        return Integer.parseInt(setting(property, environment, Integer.toString(fallback)));
    }

    private static boolean booleanSetting(String property, String environment, boolean fallback) {
        return Boolean.parseBoolean(setting(property, environment, Boolean.toString(fallback)));
    }

    private static String setting(String property, String environment, String fallback) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(environment);
        }
        return value == null || value.isBlank() ? fallback : value;
    }
}
