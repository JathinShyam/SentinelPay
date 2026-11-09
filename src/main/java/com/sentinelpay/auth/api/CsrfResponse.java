package com.sentinelpay.auth.api;

public record CsrfResponse(String token, String headerName) {
}
