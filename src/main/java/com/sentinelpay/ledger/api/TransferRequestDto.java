package com.sentinelpay.ledger.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import java.util.UUID;

public record TransferRequestDto(
        @NotNull UUID sourceAccountId,
        @NotNull UUID destinationAccountId,
        @Positive(message = "amountMinor must be greater than zero") long amountMinor,
        @NotNull @Pattern(regexp = "[A-Z]{3}", message = "currency must be a three-letter ISO-4217 code")
        String currency,
        String description
) {
}
