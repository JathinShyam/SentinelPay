package com.sentinelpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.ledger.domain.AccountView;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.FixedHostPortGenericContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.UUID;

/**
 * One real PostgreSQL, Redis and Kafka topology for the whole integration suite.
 * Containers are static singletons so the Spring context and broker state are reused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("sentinelpay")
                    .withUsername("sentinelpay")
                    .withPassword("sentinelpay");

    private static final int KAFKA_HOST_PORT = freePort();

    /**
     * Testcontainers 2.0.3's KafkaContainer advertises the Docker meta-address on
     * some daemon/network combinations. A fixed host-port mapping with an explicit
     * localhost advertised listener is deterministic and still runs the real broker.
     */
    protected static final FixedHostPortGenericContainer<?> KAFKA =
            new FixedHostPortGenericContainer<>("apache/kafka:3.9.0")
                    .withFixedExposedPort(KAFKA_HOST_PORT, 9092)
                    .withEnv("KAFKA_NODE_ID", "1")
                    .withEnv("KAFKA_PROCESS_ROLES", "broker,controller")
                    .withEnv("KAFKA_LISTENERS", "PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093")
                    .withEnv("KAFKA_ADVERTISED_LISTENERS", "PLAINTEXT://localhost:" + KAFKA_HOST_PORT + ",CONTROLLER://localhost:9093")
                    .withEnv("KAFKA_CONTROLLER_LISTENER_NAMES", "CONTROLLER")
                    .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT")
                    .withEnv("KAFKA_CONTROLLER_QUORUM_VOTERS", "1@localhost:9093")
                    .withEnv("KAFKA_INTER_BROKER_LISTENER_NAME", "PLAINTEXT")
                    .withEnv("KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR", "1")
                    .withEnv("KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR", "1")
                    .withEnv("KAFKA_TRANSACTION_STATE_LOG_MIN_ISR", "1")
                    .withEnv("KAFKA_GROUP_INITIAL_REBALANCE_DELAY_MS", "0")
                    .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
                    .withEnv("KAFKA_LOG_DIRS", "/tmp/kraft-combined-logs")
                    .waitingFor(Wait.forLogMessage(".*Kafka Server started.*", 1)
                            .withStartupTimeout(java.time.Duration.ofSeconds(90)));

    protected static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--appendonly", "yes");

    static {
        POSTGRES.start();
        KAFKA.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:" + KAFKA_HOST_PORT);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("sentinelpay.outbox.poll-interval-ms", () -> "100");
        registry.add("sentinelpay.outbox.send-timeout-ms", () -> "1000");
        registry.add("sentinelpay.outbox.retry-base-seconds", () -> "1");
        registry.add("sentinelpay.outbox.metrics-refresh-ms", () -> "500");
        registry.add("spring.kafka.producer.properties.max.block.ms", () -> "2000");
    }

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot allocate a Kafka host port", exception);
        }
    }

    @LocalServerPort
    protected int port;

    @Autowired
    protected TestRestTemplate rest;

    @Autowired
    protected ObjectMapper objectMapper;

    protected AccountView createAccount(String ownerId, String currency, long initialDepositMinor) {
        ResponseEntity<AccountView> response = rest.postForEntity("/api/v1/accounts",
                jsonEntity(Map.of(
                        "ownerId", ownerId,
                        "currency", currency,
                        "initialDepositMinor", initialDepositMinor), null),
                AccountView.class);
        if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
            throw new IllegalStateException("Account creation failed: " + response);
        }
        return response.getBody();
    }

    protected ResponseEntity<JsonNode> transfer(UUID source,
                                                UUID destination,
                                                long amountMinor,
                                                String currency,
                                                String idempotencyKey) {
        return rest.exchange("/api/v1/transfers", HttpMethod.POST,
                jsonEntity(Map.of(
                        "sourceAccountId", source,
                        "destinationAccountId", destination,
                        "amountMinor", amountMinor,
                        "currency", currency), idempotencyKey),
                JsonNode.class);
    }

    protected ResponseEntity<JsonNode> reverse(UUID transactionId, String idempotencyKey) {
        return rest.exchange("/api/v1/transactions/" + transactionId + "/reverse", HttpMethod.POST,
                jsonEntity(Map.of(), idempotencyKey), JsonNode.class);
    }

    protected HttpEntity<Map<String, ?>> jsonEntity(Map<String, ?> body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return new HttpEntity<>(body, headers);
    }

    protected void assertProblem(ResponseEntity<JsonNode> response, int expectedStatus, String expectedCode) {
        org.assertj.core.api.Assertions.assertThat(response.getStatusCode().value())
                .as("HTTP status for %s", response.getBody())
                .isEqualTo(expectedStatus);
        org.assertj.core.api.Assertions.assertThat(response.getBody())
                .isNotNull();
        org.assertj.core.api.Assertions.assertThat(response.getBody().path("code").asText())
                .isEqualTo(expectedCode);
    }
}
