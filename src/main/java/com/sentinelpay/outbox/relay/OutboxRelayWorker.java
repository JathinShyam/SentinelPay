package com.sentinelpay.outbox.relay;

import com.sentinelpay.common.config.OutboxProperties;
import com.sentinelpay.outbox.domain.OutboxEvent;
import com.sentinelpay.outbox.repository.OutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Transactional outbox relay. The claim, Kafka sends and status updates share one
 * database transaction, so row locks make a second relay instance skip the batch.
 * Batches are bounded and abort on the first delivery failure (D-5).
 */
@Component
public class OutboxRelayWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayWorker.class);
    private static final long MAX_BACKOFF_SECONDS = 3_600;

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final OutboxProperties properties;
    private final TransactionTemplate transactionTemplate;

    public OutboxRelayWorker(OutboxRepository outboxRepository,
                             KafkaTemplate<String, String> kafkaTemplate,
                             OutboxProperties properties,
                             TransactionTemplate transactionTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelayString = "${sentinelpay.outbox.poll-interval-ms:200}")
    public void relay() {
        try {
            Integer delivered = transactionTemplate.execute(status -> relayBatch());
            if (delivered != null && delivered > 0) {
                log.debug("Outbox relay delivered {} event(s)", delivered);
            }
        } catch (RuntimeException exception) {
            // The next poll retries; rows remain PENDING and locked only for this attempt.
            log.warn("Outbox relay iteration failed: {}", exception.getMessage());
        }
    }

    private int relayBatch() {
        List<OutboxEvent> batch = outboxRepository.claimPending(properties.batchSize());
        if (batch.isEmpty()) {
            return 0;
        }

        // Dispatch the whole batch before awaiting acknowledgements so the Kafka
        // producer can coalesce requests; one bulk UPDATE records the batch.
        List<CompletableFuture<?>> deliveries = new ArrayList<>(batch.size());
        for (int index = 0; index < batch.size(); index++) {
            OutboxEvent event = batch.get(index);
            try {
                deliveries.add(kafkaTemplate.send(properties.topic(),
                        event.aggregateId().toString(), event.payload()));
            } catch (RuntimeException exception) {
                // KafkaProducer can fail synchronously while waiting for broker
                // metadata (no cached metadata, unreachable broker). Treat this
                // like an asynchronous send failure so retries remain bounded.
                markFailed(batch.subList(index, batch.size()), exception.getMessage());
                return index;
            }
        }

        int delivered = 0;
        for (int index = 0; index < batch.size(); index++) {
            try {
                deliveries.get(index).get(properties.sendTimeoutMs(), TimeUnit.MILLISECONDS);
                delivered++;
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                markFailed(batch.subList(index, batch.size()), "relay interrupted");
                return delivered;
            } catch (Exception exception) {
                markFailed(batch.subList(index, batch.size()), exception.getMessage());
                return delivered;
            }
        }
        outboxRepository.markProcessed(batch.stream().map(OutboxEvent::eventId).toList());
        return delivered;
    }

    private void markFailed(List<OutboxEvent> events, String reason) {
        log.warn("Kafka delivery failed for {} outbox event(s); aborting batch: {}",
                events.size(), reason);
        for (OutboxEvent event : events) {
            int retryCount = event.retryCount() + 1;
            if (retryCount >= properties.maxRetries()) {
                outboxRepository.markDeadLetter(event.eventId(), retryCount);
                log.error("Outbox event {} moved to DEAD_LETTER after {} attempts",
                        event.eventId(), retryCount);
            } else {
                outboxRepository.markForRetry(event.eventId(), retryCount,
                        backoffSeconds(retryCount));
            }
        }
    }

    private long backoffSeconds(int retryCount) {
        int exponent = Math.min(Math.max(retryCount - 1, 0), 20);
        long delay = properties.retryBaseSeconds() * (1L << exponent);
        return Math.max(1L, Math.min(delay, MAX_BACKOFF_SECONDS));
    }
}
