package com.sentinelpay.auth.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Authentication primitives that do not depend on a servlet container.
 * Non-web contexts (for example the outbox dead-letter test) still get method
 * security and a password encoder.
 */
@Configuration
@EnableMethodSecurity
public class AuthCoreConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}
