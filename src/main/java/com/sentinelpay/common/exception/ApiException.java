package com.sentinelpay.common.exception;

import java.util.LinkedHashMap;
import java.util.Map;

public class ApiException extends RuntimeException {

    private final ApiErrorCode code;
    private final Map<String, Object> properties;

    public ApiException(ApiErrorCode code, String detail) {
        this(code, detail, Map.of());
    }

    public ApiException(ApiErrorCode code, String detail, Map<String, Object> properties) {
        super(detail);
        this.code = code;
        this.properties = properties == null ? Map.of() : new LinkedHashMap<>(properties);
    }

    public ApiErrorCode code() {
        return code;
    }

    public Map<String, Object> properties() {
        return Map.copyOf(properties);
    }
}
