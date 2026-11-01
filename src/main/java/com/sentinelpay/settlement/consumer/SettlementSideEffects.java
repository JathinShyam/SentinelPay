package com.sentinelpay.settlement.consumer;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Observable settlement side effect. In production this is where downstream
 * settlement state would be advanced; the counter makes effect-once testable.
 */
@Component
public class SettlementSideEffects {

    private final Map<UUID, AtomicLong> countsByEvent = new ConcurrentHashMap<>();
    private final AtomicLong total = new AtomicLong();

    public void record(UUID eventId) {
        countsByEvent.computeIfAbsent(eventId, ignored -> new AtomicLong()).incrementAndGet();
        total.incrementAndGet();
    }

    public long countFor(UUID eventId) {
        AtomicLong count = countsByEvent.get(eventId);
        return count == null ? 0L : count.get();
    }

    public long total() {
        return total.get();
    }
}
