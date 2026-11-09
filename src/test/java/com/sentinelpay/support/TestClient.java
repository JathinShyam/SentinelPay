package com.sentinelpay.support;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.client.TestRestTemplate.HttpClientOption;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Test-only HTTP client that keeps the JSESSIONID cookie and mirrors the
 * browser's CSRF contract: fetch a token, send it on every mutating request,
 * and refresh it after login because Spring Security rotates it.
 */
public final class TestClient {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    private final TestRestTemplate rest;
    private final AtomicReference<String> csrfToken = new AtomicReference<>();

    public TestClient(int port, boolean sendCsrf) {
        this.rest = new TestRestTemplate(HttpClientOption.ENABLE_COOKIES);
        this.rest.setUriTemplateHandler(
                new org.springframework.web.util.DefaultUriBuilderFactory(
                        "http://localhost:" + port));
        if (sendCsrf) {
            rest.getRestTemplate().getInterceptors().add((request, body, execution) -> {
                String token = csrfToken.get();
                if (token != null && !SAFE_METHODS.contains(request.getMethod().name())) {
                    request.getHeaders().set("X-XSRF-TOKEN", token);
                }
                return execution.execute(request, body);
            });
        }
    }

    public TestRestTemplate rest() {
        return rest;
    }

    public String refreshCsrf() {
        ResponseEntity<JsonNode> response = rest.getForEntity("/api/v1/auth/csrf", JsonNode.class);
        if (response.getStatusCode().value() != 200 || response.getBody() == null) {
            throw new IllegalStateException("Could not obtain a CSRF token: " + response);
        }
        String token = response.getBody().path("token").asText();
        csrfToken.set(token);
        return token;
    }

    public void clearCsrf() {
        csrfToken.set(null);
    }

    public ResponseEntity<JsonNode> login(String username, String password) {
        refreshCsrf();
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("username", username);
        form.add("password", password);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/v1/auth/login",
                new HttpEntity<>(form, headers), JsonNode.class);
        if (response.getStatusCode().value() == 200) {
            refreshCsrf();
        }
        return response;
    }

    public ResponseEntity<JsonNode> logout() {
        ResponseEntity<JsonNode> response = rest.postForEntity("/api/v1/auth/logout",
                HttpEntity.EMPTY, JsonNode.class);
        csrfToken.set(null);
        return response;
    }

    public ResponseEntity<JsonNode> get(String path) {
        return rest.getForEntity(path, JsonNode.class);
    }

    public ResponseEntity<JsonNode> postJson(String path, Object body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return rest.postForEntity(path, new HttpEntity<>(body, headers), JsonNode.class);
    }

    public ResponseEntity<JsonNode> postMultipart(String path,
                                                  byte[] content,
                                                  String fileName,
                                                  String businessDate) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new ByteArrayResource(content) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        parts.add("businessDate", businessDate);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return rest.postForEntity(path, new HttpEntity<>(parts, headers), JsonNode.class);
    }
}
