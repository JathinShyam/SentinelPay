package com.sentinelpay.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "sentinelpay.outbox")
public record OutboxProperties(
        @DefaultValue("ledger.transactions.v1") String topic,
        @DefaultValue("200") long pollIntervalMs,
        @DefaultValue("100") int batchSize,
        @DefaultValue("3000") long sendTimeoutMs,
        @DefaultValue("5") int maxRetries,
        @DefaultValue("2") long retryBaseSeconds
) {
}
