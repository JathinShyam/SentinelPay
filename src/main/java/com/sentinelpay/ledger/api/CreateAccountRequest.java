package com.sentinelpay.ledger.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateAccountRequest(
        @NotBlank @Size(max = 64) String ownerId,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "currency must be a three-letter ISO-4217 code")
        String currency,
        @Min(value = 0, message = "initialDepositMinor cannot be negative") Long initialDepositMinor
) {
    public long initialDepositOrZero() {
        return initialDepositMinor == null ? 0L : initialDepositMinor;
    }
}
