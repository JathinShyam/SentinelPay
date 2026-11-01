package com.sentinelpay.outbox.domain;

import java.time.Instant;
import java.util.UUID;

public record OutboxEvent(
        UUID eventId,
        String aggregateType,
        UUID aggregateId,
        String eventType,
        String payload,
        int retryCount,
        Instant createdAt
) {
}
