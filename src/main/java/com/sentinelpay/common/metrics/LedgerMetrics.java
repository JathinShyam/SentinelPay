package com.sentinelpay.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

@Component
public class LedgerMetrics {

    private final MeterRegistry registry;
    private final Timer transferTimer;
    private final Timer lockTimer;
    private final Timer commitTimer;
    private final AtomicInteger activeVirtualThreads = new AtomicInteger();

    public LedgerMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.transferTimer = Timer.builder("sentinelpay.ledger.transfer.duration")
                .description("End-to-end ledger transfer service latency")
                .publishPercentileHistogram()
                .register(registry);
        this.lockTimer = Timer.builder("sentinelpay.ledger.lock.acquisition.duration")
                .description("Time spent in SELECT ... FOR UPDATE account locks")
                .publishPercentileHistogram()
                .register(registry);
        this.commitTimer = Timer.builder("sentinelpay.ledger.commit.duration")
                .description("Time from the last statement to transaction commit acknowledgement")
                .publishPercentileHistogram()
                .register(registry);
        Gauge.builder("sentinelpay.virtual.threads.active", activeVirtualThreads,
                        AtomicInteger::get)
                .description("Virtual threads currently executing reconciliation chunks")
                .register(registry);
    }

    public void transfer(String status, String currency) {
        Counter.builder("sentinelpay.ledger.transfers.total")
                .description("Ledger transfer outcomes")
                .tags("status", status, "currency", currency)
                .register(registry)
                .increment();
    }

    public <T> T recordTransferDuration(Supplier<T> call) {
        return transferTimer.record(call);
    }

    public <T> T recordLock(Supplier<T> lockCall) {
        return lockTimer.record(lockCall);
    }

    public void recordCommitDuration(long nanos) {
        commitTimer.record(java.time.Duration.ofNanos(nanos));
    }

    public void reconciliationDiscrepancy(String type) {
        Counter.builder("sentinelpay.reconciliation.discrepancies.total")
                .description("Persisted reconciliation anomalies by type")
                .tag("type", type)
                .register(registry)
                .increment();
    }

    public void virtualThreadStarted() {
        activeVirtualThreads.incrementAndGet();
    }

    public void virtualThreadFinished() {
        activeVirtualThreads.decrementAndGet();
    }
}
