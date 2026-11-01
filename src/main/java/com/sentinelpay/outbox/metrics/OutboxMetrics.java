package com.sentinelpay.outbox.metrics;

import com.sentinelpay.outbox.repository.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class OutboxMetrics {

    private static final Logger log = LoggerFactory.getLogger(OutboxMetrics.class);

    private final OutboxRepository outboxRepository;
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong oldestLagSeconds = new AtomicLong();

    public OutboxMetrics(OutboxRepository outboxRepository, MeterRegistry registry) {
        this.outboxRepository = outboxRepository;
        Gauge.builder("sentinelpay.outbox.pending.gauge", pending, AtomicLong::get)
                .description("Number of outbox events waiting for Kafka delivery")
                .register(registry);
        Gauge.builder("sentinelpay.outbox.lag.seconds", oldestLagSeconds, AtomicLong::get)
                .description("Age of the oldest pending outbox event")
                .register(registry);
    }

    @Scheduled(fixedDelayString = "${sentinelpay.outbox.metrics-refresh-ms:5000}")
    public void refresh() {
        try {
            pending.set(outboxRepository.countPending());
            Long lag = outboxRepository.oldestPendingAgeSeconds();
            oldestLagSeconds.set(lag == null ? 0L : lag);
        } catch (RuntimeException exception) {
            log.debug("Outbox metrics refresh skipped: {}", exception.getMessage());
        }
    }
}
