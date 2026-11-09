package com.sentinelpay.auth.service;

import com.sentinelpay.auth.api.RegisterRequest;
import com.sentinelpay.auth.api.UserView;
import com.sentinelpay.auth.domain.AppUser;
import com.sentinelpay.auth.domain.Role;
import com.sentinelpay.auth.domain.UserStatus;
import com.sentinelpay.auth.repository.AppUserRepository;
import com.sentinelpay.common.exception.ApiErrorCode;
import com.sentinelpay.common.exception.ApiException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuthService {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;

    public AuthService(AppUserRepository users, PasswordEncoder passwordEncoder) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public UserView register(RegisterRequest request) {
        String username = request.username().trim().toLowerCase(Locale.ROOT);
        String displayName = request.displayName().trim();
        if (displayName.isEmpty()) {
            throw new ApiException(ApiErrorCode.INVALID_REQUEST,
                    "displayName must be non-blank");
        }

        Instant now = Instant.now();
        AppUser user = new AppUser(UUID.randomUUID(), username, displayName,
                passwordEncoder.encode(request.password()), Role.USER, UserStatus.ACTIVE,
                now, now);
        try {
            users.insert(user);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(ApiErrorCode.USERNAME_TAKEN,
                    "The username is already registered");
        }
        return UserView.from(user);
    }
}
