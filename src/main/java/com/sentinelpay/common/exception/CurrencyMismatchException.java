package com.sentinelpay.common.exception;

import java.util.Map;

public class CurrencyMismatchException extends ApiException {

    public CurrencyMismatchException(String sourceCurrency, String destinationCurrency, String requestCurrency) {
        super(ApiErrorCode.CURRENCY_MISMATCH,
                "Currencies do not match: source=%s destination=%s request=%s"
                        .formatted(sourceCurrency, destinationCurrency, requestCurrency),
                Map.of(
                        "sourceCurrency", sourceCurrency,
                        "destinationCurrency", destinationCurrency,
                        "requestCurrency", requestCurrency));
    }
}
