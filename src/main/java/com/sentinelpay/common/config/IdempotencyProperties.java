package com.sentinelpay.common.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

@ConfigurationProperties(prefix = "sentinelpay.idempotency")
public record IdempotencyProperties(
        @DefaultValue("Idempotency-Key") String headerName,
        @DefaultValue("30s") Duration inFlightTtl,
        @DefaultValue("24h") Duration completedTtl,
        @DefaultValue("1048576") int maxBodyBytes
) {
}
