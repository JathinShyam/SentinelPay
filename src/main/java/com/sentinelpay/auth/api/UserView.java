package com.sentinelpay.auth.api;

import com.sentinelpay.auth.domain.AppUser;
import com.sentinelpay.auth.security.AuthenticatedUser;

import java.util.UUID;

public record UserView(
        UUID userId,
        String username,
        String displayName,
        String role
) {
    public static UserView from(AuthenticatedUser user) {
        return new UserView(user.userId(), user.username(), user.displayName(),
                user.role().name());
    }

    public static UserView from(AppUser user) {
        return new UserView(user.userId(), user.username(), user.displayName(),
                user.role().name());
    }
}
