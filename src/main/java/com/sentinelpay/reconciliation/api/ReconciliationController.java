package com.sentinelpay.reconciliation.api;

import com.sentinelpay.common.exception.ApiErrorCode;
import com.sentinelpay.common.exception.ApiException;
import com.sentinelpay.common.exception.ReconciliationRunNotFoundException;
import com.sentinelpay.reconciliation.domain.DiscrepancyPage;
import com.sentinelpay.reconciliation.domain.DiscrepancyType;
import com.sentinelpay.reconciliation.domain.ReconciliationRun;
import com.sentinelpay.reconciliation.domain.ReconciliationSummary;
import com.sentinelpay.reconciliation.repository.DiscrepancyRepository;
import com.sentinelpay.reconciliation.repository.ReconciliationRunRepository;
import com.sentinelpay.reconciliation.service.VirtualThreadReconciliationEngine;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reconciliation")
public class ReconciliationController {

    private static final int MAX_PAGE_SIZE = 500;

    private final VirtualThreadReconciliationEngine engine;
    private final ReconciliationRunRepository runRepository;
    private final DiscrepancyRepository discrepancyRepository;

    public ReconciliationController(VirtualThreadReconciliationEngine engine,
                                    ReconciliationRunRepository runRepository,
                                    DiscrepancyRepository discrepancyRepository) {
        this.engine = engine;
        this.runRepository = runRepository;
        this.discrepancyRepository = discrepancyRepository;
    }

    @PostMapping(value = "/run", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ReconciliationSummary run(
            @RequestParam("file") MultipartFile file,
            @RequestParam("businessDate")
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate businessDate) {
        if (file.isEmpty()) {
            throw new ApiException(ApiErrorCode.INVALID_REQUEST, "Clearing file must not be empty");
        }
        String fileName = file.getOriginalFilename() == null || file.getOriginalFilename().isBlank()
                ? "clearing.csv" : file.getOriginalFilename();
        Path tempFile = null;
        try {
            tempFile = Files.createTempFile("sentinelpay-clearing-", suffix(fileName));
            Files.copy(file.getInputStream(), tempFile, StandardCopyOption.REPLACE_EXISTING);
            return engine.run(tempFile, businessDate, fileName);
        } catch (IOException exception) {
            throw new ApiException(ApiErrorCode.INVALID_REQUEST,
                    "Clearing file could not be stored for reconciliation");
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (IOException ignored) {
                    // The temp file is best-effort cleanup; the run itself already completed.
                }
            }
        }
    }

    @GetMapping("/runs/{runId}")
    public ReconciliationRun run(@PathVariable UUID runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new ReconciliationRunNotFoundException(runId));
    }

    @GetMapping("/runs/{runId}/discrepancies")
    public DiscrepancyPage discrepancies(
            @PathVariable UUID runId,
            @RequestParam(required = false) DiscrepancyType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new ApiException(ApiErrorCode.INVALID_REQUEST,
                    "page must be >= 0 and size must be between 1 and " + MAX_PAGE_SIZE);
        }
        runRepository.findById(runId)
                .orElseThrow(() -> new ReconciliationRunNotFoundException(runId));
        long total = discrepancyRepository.count(runId, type);
        return new DiscrepancyPage(runId, page, size, total,
                discrepancyRepository.findPage(runId, type, size, page * size));
    }

    private String suffix(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".ndjson")) {
            return ".ndjson";
        }
        if (lower.endsWith(".jsonl")) {
            return ".jsonl";
        }
        return ".csv";
    }
}
