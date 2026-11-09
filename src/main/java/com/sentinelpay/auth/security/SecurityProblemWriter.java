package com.sentinelpay.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.common.exception.ApiErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;

@Component
public class SecurityProblemWriter {

    private final ObjectMapper objectMapper;

    public SecurityProblemWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletRequest request,
                      HttpServletResponse response,
                      ApiErrorCode code,
                      String detail) throws IOException {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(),
                detail == null ? code.title() : detail);
        problem.setTitle(code.title());
        problem.setType(URI.create("https://sentinelpay.dev/errors/"
                + code.name().toLowerCase().replace('_', '-')));
        problem.setProperty("code", code.name());
        problem.setInstance(URI.create(request.getRequestURI()));
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
