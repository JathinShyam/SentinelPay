package com.sentinelpay.auth.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{2,63}",
                message = "username must be 3-64 characters and contain only letters, digits, dot, underscore or hyphen")
        String username,
        @NotBlank
        @Size(max = 128, message = "displayName must be at most 128 characters")
        String displayName,
        @NotBlank
        @Size(min = 10, max = 72, message = "password must be between 10 and 72 characters")
        String password
) {
}
