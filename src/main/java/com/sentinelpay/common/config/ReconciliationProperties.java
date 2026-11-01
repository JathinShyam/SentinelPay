package com.sentinelpay.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "sentinelpay.reconciliation")
public record ReconciliationProperties(
        @DefaultValue("1000") int chunkSize,
        @DefaultValue("50") int maxConcurrency
) {
}
