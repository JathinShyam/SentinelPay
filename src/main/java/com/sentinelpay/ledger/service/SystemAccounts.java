package com.sentinelpay.ledger.service;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Deterministic identifiers for the system clearing accounts. Using name-based UUIDs
 * keeps the seed idempotent across restarts without a lookup table.
 */
public final class SystemAccounts {

    public static final long GENESIS_AMOUNT_MINOR = 1_000_000_000_000L;

    private SystemAccounts() {
    }

    public static UUID equity(String currency) {
        return UUID.nameUUIDFromBytes(("SYSTEM_EQUITY:" + currency).getBytes(StandardCharsets.UTF_8));
    }

    public static UUID reserve(String currency) {
        return UUID.nameUUIDFromBytes(("SYSTEM_RESERVE:" + currency).getBytes(StandardCharsets.UTF_8));
    }

    public static String genesisIdempotencyKey(String currency) {
        return "genesis-v1:" + currency;
    }
}
