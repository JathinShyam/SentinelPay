package com.sentinelpay;

import com.sentinelpay.settlement.consumer.SettlementSideEffects;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaConsumerIT extends AbstractIntegrationTest {

    private static final String TOPIC = "ledger.transactions.v1";

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    SettlementSideEffects sideEffects;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    KafkaProperties kafkaProperties;

    @Test
    void duplicateEventIdsProduceOneDedupRowAndOneSideEffect() throws Exception {
        UUID transactionId = UUID.randomUUID();
        UUID firstEventId = UUID.randomUUID();
        UUID secondEventId = UUID.randomUUID();

        String firstPayload = payload(firstEventId, transactionId, "TransferPostedEvent");
        kafkaTemplate.send(TOPIC, transactionId.toString(), firstPayload)
                .get(10, TimeUnit.SECONDS);

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(processedRows(firstEventId)).isEqualTo(1L);
            assertThat(sideEffects.countFor(firstEventId)).isEqualTo(1L);
        });

        // Same key/partition: if the sentinel is processed, the duplicate was consumed too.
        kafkaTemplate.send(TOPIC, transactionId.toString(), firstPayload)
                .get(10, TimeUnit.SECONDS);
        kafkaTemplate.send(TOPIC, transactionId.toString(),
                        payload(secondEventId, transactionId, "TransferPostedEvent"))
                .get(10, TimeUnit.SECONDS);

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertThat(sideEffects.countFor(secondEventId)).isEqualTo(1L);
            assertThat(processedRows(firstEventId)).isEqualTo(1L);
            assertThat(sideEffects.countFor(firstEventId)).isEqualTo(1L);
            assertThat(processedRows(secondEventId)).isEqualTo(1L);
        });
    }

    @Test
    void malformedPayloadIsRetriedThenPublishedToTheDeadLetterTopic() throws Exception {
        UUID transactionId = UUID.randomUUID();
        String broken = objectMapper.writeValueAsString(Map.of(
                "eventType", "TransferPostedEvent",
                "transactionId", transactionId.toString()));

        long deadLettersBefore = deadLetterCount();
        kafkaTemplate.send(TOPIC, transactionId.toString(), broken).get(10, TimeUnit.SECONDS);

        Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(deadLetterCount()).isGreaterThan(deadLettersBefore));
    }

    private String payload(UUID eventId, UUID transactionId, String eventType) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "eventId", eventId.toString(),
                "eventType", eventType,
                "transactionId", transactionId.toString(),
                "amountMinor", 1_000,
                "currency", "INR",
                "occurredAt", "2026-10-31T00:00:00Z"));
    }

    private long processedRows(UUID eventId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_kafka_events WHERE event_id = ?", Long.class, eventId);
        return count == null ? 0L : count;
    }

    private long deadLetterCount() throws Exception {
        String bootstrap = String.join(",", kafkaProperties.getBootstrapServers());
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap))) {
            var topic = admin.describeTopics(List.of("ledger.transactions.v1.DLT"))
                    .allTopicNames().get(10, TimeUnit.SECONDS)
                    .get("ledger.transactions.v1.DLT");
            Map<TopicPartition, OffsetSpec> request = new HashMap<>();
            topic.partitions().forEach(partition -> request.put(
                    new TopicPartition("ledger.transactions.v1.DLT", partition.partition()),
                    OffsetSpec.latest()));
            ListOffsetsResult offsets = admin.listOffsets(request);
            long total = 0L;
            for (var partitionOffset : offsets.all().get(10, TimeUnit.SECONDS).values()) {
                total += partitionOffset.offset();
            }
            return total;
        }
    }
}
