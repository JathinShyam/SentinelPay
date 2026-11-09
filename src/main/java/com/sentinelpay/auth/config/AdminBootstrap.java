package com.sentinelpay.auth.config;

import com.sentinelpay.auth.domain.AppUser;
import com.sentinelpay.auth.domain.Role;
import com.sentinelpay.auth.domain.UserStatus;
import com.sentinelpay.auth.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

/**
 * Creates a single administrator when the deployment supplies bootstrap
 * credentials. No default credentials are baked into the application.
 */
@Component
public class AdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final String username;
    private final String password;
    private final String displayName;

    public AdminBootstrap(AppUserRepository users,
                          PasswordEncoder passwordEncoder,
                          @Value("${sentinelpay.security.bootstrap-admin.username:}") String username,
                          @Value("${sentinelpay.security.bootstrap-admin.password:}") String password,
                          @Value("${sentinelpay.security.bootstrap-admin.display-name:Administrator}")
                          String displayName) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.username = username;
        this.password = password;
        this.displayName = displayName;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (username == null || username.isBlank()
                || password == null || password.isBlank()) {
            return;
        }
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        if (users.findByUsername(normalized).isPresent()) {
            return;
        }
        if (password.length() < 10 || password.length() > 72) {
            log.warn("Bootstrap admin was not created: password must be 10-72 characters");
            return;
        }

        Instant now = Instant.now();
        AppUser admin = new AppUser(UUID.randomUUID(), normalized,
                displayName == null || displayName.isBlank() ? "Administrator" : displayName.trim(),
                passwordEncoder.encode(password), Role.ADMIN, UserStatus.ACTIVE, now, now);
        try {
            users.insert(admin);
            log.info("Bootstrap administrator '{}' created", normalized);
        } catch (DuplicateKeyException exception) {
            log.info("Bootstrap administrator '{}' already exists", normalized);
        }
    }
}
