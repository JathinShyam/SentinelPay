package com.sentinelpay.common.exception;

import com.sentinelpay.reconciliation.parser.ClearingFileException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final long DEPENDENCY_WARNING_INTERVAL_MS = 10_000;

    private final AtomicLong lastDependencyWarning = new AtomicLong();

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ProblemDetail> handleApiException(ApiException exception, HttpServletRequest request) {
        ProblemDetail problem = build(exception.code(), exception.getMessage(), request);
        exception.properties().forEach(problem::setProperty);
        return ResponseEntity.status(exception.code().status()).body(problem);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception,
                                                          HttpServletRequest request) {
        List<Map<String, String>> errors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::fieldError)
                .toList();
        boolean amountError = exception.getBindingResult().getFieldErrors().stream()
                .anyMatch(error -> error.getField().equals("amountMinor"));
        ApiErrorCode code = amountError ? ApiErrorCode.INVALID_AMOUNT : ApiErrorCode.INVALID_REQUEST;

        String detail = errors.isEmpty() ? "Request validation failed" : errors.getFirst().get("message");
        ProblemDetail problem = build(code, detail, request);
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException exception,
                                                             HttpServletRequest request) {
        ApiErrorCode code = "Idempotency-Key".equalsIgnoreCase(exception.getHeaderName())
                ? ApiErrorCode.IDEMPOTENCY_KEY_REQUIRED
                : ApiErrorCode.INVALID_REQUEST;
        ProblemDetail problem = build(code, exception.getMessage(), request);
        problem.setProperty("header", exception.getHeaderName());
        return ResponseEntity.status(code.status()).body(problem);
    }

    @ExceptionHandler({
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MissingServletRequestPartException.class
    })
    public ResponseEntity<ProblemDetail> handleBadRequest(Exception exception, HttpServletRequest request) {
        ProblemDetail problem = build(ApiErrorCode.INVALID_REQUEST,
                "Malformed or incomplete request", request);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(ClearingFileException.class)
    public ResponseEntity<ProblemDetail> handleClearingFile(ClearingFileException exception,
                                                            HttpServletRequest request) {
        ProblemDetail problem = build(ApiErrorCode.INVALID_REQUEST, exception.getMessage(), request);
        if (exception.lineNumber() != null) {
            problem.setProperty("lineNumber", exception.lineNumber());
        }
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ProblemDetail> handleUploadTooLarge(MaxUploadSizeExceededException exception,
                                                              HttpServletRequest request) {
        ProblemDetail problem = build(ApiErrorCode.INVALID_REQUEST,
                "Uploaded clearing file exceeds the configured limit", request);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleDataIntegrity(DataIntegrityViolationException exception,
                                                             HttpServletRequest request) {
        String message = exception.getMostSpecificCause().getMessage();
        if (message != null && message.contains("idempotency_key")) {
            ProblemDetail problem = build(ApiErrorCode.IDEMPOTENCY_CONFLICT,
                    "The idempotency key was already used by a different request", request);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
        }
        log.error("Unhandled data integrity failure", exception);
        ProblemDetail problem = build(ApiErrorCode.INTERNAL_ERROR,
                "The request could not be completed", request);
        return ResponseEntity.internalServerError().body(problem);
    }

    @ExceptionHandler({CannotCreateTransactionException.class, DataAccessResourceFailureException.class})
    public ResponseEntity<ProblemDetail> handleDependencyUnavailable(Exception exception,
                                                                     HttpServletRequest request) {
        long now = System.currentTimeMillis();
        long previous = lastDependencyWarning.get();
        if (now - previous >= DEPENDENCY_WARNING_INTERVAL_MS
                && lastDependencyWarning.compareAndSet(previous, now)) {
            log.warn("Dependency unavailable on {} {}: {}",
                    request.getMethod(), request.getRequestURI(), exception.getMessage());
        }
        ProblemDetail problem = build(ApiErrorCode.DEPENDENCY_UNAVAILABLE,
                "The service is temporarily unable to process the request", request);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled request failure on {} {}", request.getMethod(), request.getRequestURI(), exception);
        ProblemDetail problem = build(ApiErrorCode.INTERNAL_ERROR,
                "The request could not be completed", request);
        return ResponseEntity.internalServerError().body(problem);
    }

    private Map<String, String> fieldError(FieldError error) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("field", error.getField());
        result.put("message", error.getDefaultMessage() == null ? "invalid value" : error.getDefaultMessage());
        return result;
    }

    private ProblemDetail build(ApiErrorCode code, String detail, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail == null ? code.title() : detail);
        problem.setTitle(code.title());
        problem.setType(URI.create("https://sentinelpay.dev/errors/"
                + code.name().toLowerCase().replace('_', '-')));
        problem.setProperty("code", code.name());
        problem.setInstance(URI.create(request.getRequestURI()));
        return problem;
    }
}
