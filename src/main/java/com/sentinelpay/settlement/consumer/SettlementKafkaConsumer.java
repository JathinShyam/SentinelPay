package com.sentinelpay.settlement.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.BatchListenerFailedException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * At-least-once Kafka delivery becomes effectively-once by inserting event ids
 * into processed_kafka_events in the same transaction as the side effects.
 * Records are consumed in batches so offset commits and dedup inserts are
 * amortised instead of paying one database transaction per event.
 */
@Component
public class SettlementKafkaConsumer {

    private static final Logger log = LoggerFactory.getLogger(SettlementKafkaConsumer.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final SettlementSideEffects sideEffects;

    public SettlementKafkaConsumer(JdbcTemplate jdbc,
                                   ObjectMapper objectMapper,
                                   SettlementSideEffects sideEffects) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.sideEffects = sideEffects;
    }

    @KafkaListener(
            topics = "${sentinelpay.outbox.topic:ledger.transactions.v1}",
            groupId = "${spring.kafka.consumer.group-id:sentinelpay-settlement}",
            batch = "true")
    @Transactional
    public void onMessages(List<ConsumerRecord<String, String>> records) {
        List<UUID> eventIds = new ArrayList<>(records.size());
        for (ConsumerRecord<String, String> record : records) {
            try {
                JsonNode event = parse(record.value());
                eventIds.add(eventId(event));
            } catch (RuntimeException exception) {
                throw new BatchListenerFailedException(
                        "Invalid settlement payload", exception, record);
            }
        }

        List<UUID> inserted = insertNewEventIds(eventIds);
        inserted.forEach(sideEffects::record);
        if (log.isDebugEnabled()) {
            log.debug("Settlement batch processed records={} newEvents={}",
                    records.size(), inserted.size());
        }
    }

    private List<UUID> insertNewEventIds(List<UUID> eventIds) {
        if (eventIds.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",",
                Collections.nCopies(eventIds.size(), "(CAST(? AS uuid))"));
        return jdbc.query("""
                        INSERT INTO processed_kafka_events (event_id)
                        VALUES %s
                        ON CONFLICT (event_id) DO NOTHING
                        RETURNING event_id
                        """.formatted(placeholders),
                (resultSet, rowNum) -> resultSet.getObject("event_id", UUID.class),
                eventIds.toArray());
    }

    private JsonNode parse(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("Settlement payload must be a JSON object");
            }
            return node;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Cannot parse settlement payload", exception);
        }
    }

    private UUID eventId(JsonNode event) {
        String value = event.path("eventId").asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Settlement payload is missing eventId");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Settlement payload has an invalid eventId", exception);
        }
    }
}
