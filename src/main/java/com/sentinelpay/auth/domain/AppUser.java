package com.sentinelpay.auth.domain;

import java.time.Instant;
import java.util.UUID;

public record AppUser(
        UUID userId,
        String username,
        String displayName,
        String passwordHash,
        Role role,
        UserStatus status,
        Instant createdAt,
        Instant updatedAt
) {
}
