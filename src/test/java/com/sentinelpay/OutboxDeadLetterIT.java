package com.sentinelpay;

import com.sentinelpay.ledger.domain.AccountView;
import com.sentinelpay.ledger.domain.TransferRequest;
import com.sentinelpay.ledger.domain.TransferResult;
import com.sentinelpay.ledger.service.AccountService;
import com.sentinelpay.ledger.service.LedgerTransferService;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The relay must exhaust a bounded number of attempts against a broker that is
 * unreachable from the start (no cached metadata), not just a paused broker.
 * A real Kafka producer points at a closed local port; Postgres is real.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.kafka.bootstrap-servers=127.0.0.1:1",
                "spring.kafka.admin.auto-create=false",
                "spring.kafka.listener.auto-startup=false",
                "spring.kafka.producer.properties.max.block.ms=500",
                "spring.kafka.producer.properties.delivery.timeout.ms=1000",
                "spring.kafka.producer.properties.request.timeout.ms=500",
                "sentinelpay.outbox.poll-interval-ms=50",
                "sentinelpay.outbox.batch-size=100",
                "sentinelpay.outbox.send-timeout-ms=500",
                "sentinelpay.outbox.max-retries=1"
        })
class OutboxDeadLetterIT {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
                    .withDatabaseName("sentinelpay")
                    .withUsername("sentinelpay")
                    .withPassword("sentinelpay");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "10");
    }

    @Autowired
    AccountService accountService;

    @Autowired
    LedgerTransferService transferService;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void unreachableBrokerMovesEventToDeadLetterAfterOneRetry() {
        String suffix = UUID.randomUUID().toString();
        AccountView source = accountService.createAccount("dead-letter-source-" + suffix, "INR", 10_000);
        AccountView destination = accountService.createAccount("dead-letter-destination-" + suffix, "INR", 0);

        TransferResult result = transferService.executeTransfer(new TransferRequest(
                "dead-letter-" + suffix,
                source.accountId(),
                destination.accountId(),
                1_000,
                "INR",
                "Dead-letter probe"));

        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    Map<String, Object> event = jdbc.queryForMap("""
                            SELECT status, retry_count
                            FROM outbox_events
                            WHERE aggregate_id = ?
                            """, result.transactionId());
                    assertThat(event.get("status")).isEqualTo("DEAD_LETTER");
                    assertThat(((Number) event.get("retry_count")).intValue()).isEqualTo(1);
                });
    }
}
