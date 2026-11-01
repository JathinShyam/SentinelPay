package com.sentinelpay.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.common.config.IdempotencyProperties;
import com.sentinelpay.common.exception.ApiErrorCode;
import com.sentinelpay.common.metrics.LedgerMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Tier 1 idempotency: an atomic Redis SET NX EX lock before any database work,
 * followed by a cached response replay for the configured completion window.
 * The database UNIQUE(idempotency_key) remains the durable backstop.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class RedisIdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyFilter.class);
    private static final String KEY_PREFIX = "sentinelpay:idempotency:";
    private static final Set<String> MUTATING_METHODS = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final Pattern REVERSAL_PATH = Pattern.compile("^/api/v1/transactions/[^/]+/reverse$");

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final IdempotencyProperties properties;
    private final LedgerMetrics metrics;

    public RedisIdempotencyFilter(StringRedisTemplate redis,
                                  ObjectMapper objectMapper,
                                  IdempotencyProperties properties,
                                  LedgerMetrics metrics) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.metrics = metrics;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/v1/")) {
            return true;
        }
        if (path.startsWith("/api/v1/reconciliation")) {
            return true;
        }
        return !MUTATING_METHODS.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String idempotencyKey = request.getHeader(properties.headerName());
        boolean required = requiresIdempotencyKey(request.getRequestURI());

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            if (required) {
                writeProblem(response, request, ApiErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                        "Idempotency-Key header is required", 400);
                return;
            }
            filterChain.doFilter(request, response);
            return;
        }

        if (idempotencyKey.length() < 16 || idempotencyKey.length() > 128) {
            writeProblem(response, request, ApiErrorCode.IDEMPOTENCY_KEY_REQUIRED,
                    "Idempotency-Key must be between 16 and 128 characters", 400);
            return;
        }

        CachedBodyHttpServletRequest cachedRequest;
        try {
            cachedRequest = new CachedBodyHttpServletRequest(request, properties.maxBodyBytes());
        } catch (CachedBodyHttpServletRequest.RequestBodyTooLargeException exception) {
            writeProblem(response, request, ApiErrorCode.INVALID_REQUEST, exception.getMessage(), 413);
            return;
        }

        String fingerprint = fingerprint(cachedRequest, cachedRequest.body());
        String redisKey = KEY_PREFIX + idempotencyKey;
        StoredIdempotency inFlight = new StoredIdempotency("IN_PROGRESS", fingerprint, null, null, null);

        Boolean acquired;
        try {
            acquired = redis.opsForValue().setIfAbsent(redisKey, serialize(inFlight),
                    properties.inFlightTtl());
        } catch (RuntimeException exception) {
            log.warn("Redis idempotency unavailable; falling back to the database unique key: {}",
                    exception.getMessage());
            filterChain.doFilter(cachedRequest, response);
            return;
        }

        if (Boolean.TRUE.equals(acquired)) {
            executeAsWinner(cachedRequest, response, filterChain, redisKey, fingerprint);
            return;
        }

        StoredIdempotency stored = readStored(redisKey);
        if (stored == null) {
            // The in-flight record expired between SET NX and GET; retry once as the winner.
            Boolean retried = redis.opsForValue().setIfAbsent(redisKey, serialize(inFlight),
                    properties.inFlightTtl());
            if (Boolean.TRUE.equals(retried)) {
                executeAsWinner(cachedRequest, response, filterChain, redisKey, fingerprint);
                return;
            }
            writeInFlight(response, request);
            return;
        }

        if ("IN_PROGRESS".equals(stored.state())) {
            writeInFlight(response, request);
            return;
        }

        if (!fingerprint.equals(stored.fingerprint())) {
            writeProblem(response, request, ApiErrorCode.IDEMPOTENCY_CONFLICT,
                    "Idempotency key was already used with a different payload", 409);
            return;
        }

        int replayStatus = stored.httpStatus() == null ? 200 : stored.httpStatus();
        // FR-02: a replay of a successful create reports 200, not 201.
        if (replayStatus == 201) {
            replayStatus = 200;
        }
        if ("/api/v1/transfers".equals(request.getRequestURI())) {
            metrics.transfer("IDEMPOTENT_REPLAY", requestCurrency(cachedRequest.body()));
        }
        response.setStatus(replayStatus);
        if (stored.contentType() != null) {
            response.setContentType(stored.contentType());
        }
        response.setHeader("Idempotency-Replayed", "true");
        if (stored.body() != null) {
            response.getOutputStream().write(stored.body().getBytes(StandardCharsets.UTF_8));
        }
        response.flushBuffer();
    }

    private void executeAsWinner(CachedBodyHttpServletRequest request,
                                 HttpServletResponse response,
                                 FilterChain filterChain,
                                 String redisKey,
                                 String fingerprint) throws ServletException, IOException {
        ContentCachingResponseWrapper responseWrapper = new ContentCachingResponseWrapper(response);
        try {
            filterChain.doFilter(request, responseWrapper);
        } catch (RuntimeException | ServletException | IOException exception) {
            safeDelete(redisKey);
            throw exception;
        }

        int status = responseWrapper.getStatus();
        try {
            if (status >= 500) {
                safeDelete(redisKey);
            } else {
                byte[] body = responseWrapper.getContentAsByteArray();
                StoredIdempotency completed = new StoredIdempotency(
                        "COMPLETED",
                        fingerprint,
                        status,
                        responseWrapper.getContentType(),
                        body.length == 0 ? null : new String(body, StandardCharsets.UTF_8));
                redis.opsForValue().set(redisKey, serialize(completed), properties.completedTtl());
            }
        } catch (RuntimeException exception) {
            log.warn("Could not cache idempotent response for {}: {}", redisKey, exception.getMessage());
        } finally {
            responseWrapper.copyBodyToResponse();
        }
    }

    private StoredIdempotency readStored(String redisKey) {
        try {
            String json = redis.opsForValue().get(redisKey);
            return json == null ? null : objectMapper.readValue(json, StoredIdempotency.class);
        } catch (Exception exception) {
            log.warn("Discarding malformed idempotency record {}: {}", redisKey, exception.getMessage());
            safeDelete(redisKey);
            return null;
        }
    }

    private void safeDelete(String redisKey) {
        try {
            redis.delete(redisKey);
        } catch (RuntimeException exception) {
            log.warn("Could not delete idempotency key {}: {}", redisKey, exception.getMessage());
        }
    }

    private boolean requiresIdempotencyKey(String path) {
        return "/api/v1/transfers".equals(path) || REVERSAL_PATH.matcher(path).matches();
    }

    private String fingerprint(HttpServletRequest request, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(request.getMethod().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(request.getRequestURI().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(request.getQueryString() == null
                    ? new byte[0] : request.getQueryString().getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String requestCurrency(byte[] body) {
        try {
            String currency = objectMapper.readTree(body).path("currency").asText("");
            return currency.length() == 3 ? currency : "UNKNOWN";
        } catch (Exception exception) {
            return "UNKNOWN";
        }
    }

    private String serialize(StoredIdempotency value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot serialize idempotency record", exception);
        }
    }

    private void writeInFlight(HttpServletResponse response, HttpServletRequest request) throws IOException {
        response.setHeader("Retry-After", "1");
        writeProblem(response, request, ApiErrorCode.IDEMPOTENCY_IN_FLIGHT,
                "An identical idempotent request is already in flight", 409);
    }

    private void writeProblem(HttpServletResponse response,
                              HttpServletRequest request,
                              ApiErrorCode code,
                              String detail,
                              int status) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setTitle(code.title());
        problem.setType(URI.create("https://sentinelpay.dev/errors/"
                + code.name().toLowerCase().replace('_', '-')));
        problem.setProperty("code", code.name());
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getOutputStream().write(objectMapper.writeValueAsBytes(problem));
        response.flushBuffer();
    }

    public record StoredIdempotency(
            String state,
            String fingerprint,
            Integer httpStatus,
            String contentType,
            String body
    ) {
    }
}
