package com.sentinelpay;

import com.fasterxml.jackson.databind.JsonNode;
import com.sentinelpay.ledger.domain.AccountView;
import com.sentinelpay.settlement.consumer.SettlementSideEffects;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxResilienceIT extends AbstractIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    SettlementSideEffects sideEffects;

    @Test
    void brokerOutageBuffersEventsAndRecoversWithinFiveSeconds() throws Exception {
        AccountView source = createAccount("resilience-owner-" + UUID.randomUUID(), "INR", 100_000);
        AccountView destination = createAccount("resilience-owner-" + UUID.randomUUID(), "INR", 0);

        // Drain the genesis/deposit backlog so the outage window contains only this test.
        Awaitility.await().atMost(Duration.ofSeconds(20))
                .until(() -> countPending() == 0);

        List<UUID> transactionIds = new ArrayList<>();
        boolean paused = false;
        try {
            pauseKafka();
            paused = true;

            for (int i = 0; i < 20; i++) {
                ResponseEntity<JsonNode> response = transfer(source.accountId(), destination.accountId(),
                        1_000, "INR", "resilience-" + UUID.randomUUID());
                assertThat(response.getStatusCode().value()).isEqualTo(201);
                transactionIds.add(UUID.fromString(response.getBody().path("transactionId").asText()));
            }

            Awaitility.await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(100))
                    .until(() -> countByAggregateStatus(transactionIds, "PENDING") == 20
                            && countWithRetries(transactionIds) > 0);

            assertThat(countByAggregateStatus(transactionIds, "PENDING")).isEqualTo(20L);
            assertThat(countWithRetries(transactionIds)).isPositive();
        } finally {
            if (paused) {
                unpauseKafka();
            }
        }

        List<UUID> eventIds = eventIdsFor(transactionIds);
        assertThat(eventIds).hasSize(20);

        Awaitility.await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> {
                    assertThat(countByAggregateStatus(transactionIds, "PROCESSED")).isEqualTo(20L);
                    assertThat(processedRows(eventIds)).isEqualTo(20L);
                });

        for (UUID eventId : eventIds) {
            assertThat(sideEffects.countFor(eventId)).isEqualTo(1L);
        }
        assertThat(countByAggregateStatus(transactionIds, "DEAD_LETTER")).isZero();
        assertThat(countWithRetries(transactionIds)).isPositive();
    }

    private void pauseKafka() {
        KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    private void unpauseKafka() {
        KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    private long countPending() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox_events WHERE status = 'PENDING'", Long.class);
        return count == null ? 0L : count;
    }

    private long countByAggregateStatus(List<UUID> aggregateIds, String status) {
        Object[] args = new Object[aggregateIds.size() + 1];
        for (int i = 0; i < aggregateIds.size(); i++) {
            args[i] = aggregateIds.get(i);
        }
        args[args.length - 1] = status;
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id IN ("
                        + placeholders(aggregateIds.size()) + ") AND status = ?",
                Long.class, args);
        return count == null ? 0L : count;
    }

    private long countWithRetries(List<UUID> aggregateIds) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE aggregate_id IN ("
                        + placeholders(aggregateIds.size()) + ") AND retry_count > 0",
                Long.class, aggregateIds.toArray());
        return count == null ? 0L : count;
    }

    private List<UUID> eventIdsFor(List<UUID> aggregateIds) {
        return jdbc.query("SELECT event_id::text FROM outbox_events WHERE aggregate_id IN ("
                                + placeholders(aggregateIds.size()) + ") ORDER BY created_at",
                (rs, rowNum) -> UUID.fromString(rs.getString(1)),
                aggregateIds.toArray());
    }

    private long processedRows(List<UUID> eventIds) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM processed_kafka_events WHERE event_id IN ("
                        + placeholders(eventIds.size()) + ")",
                Long.class, eventIds.toArray());
        return count == null ? 0L : count;
    }

    private String placeholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }
}
