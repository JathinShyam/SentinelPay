package com.sentinelpay.reconciliation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.common.config.ReconciliationProperties;
import com.sentinelpay.common.metrics.LedgerMetrics;
import com.sentinelpay.reconciliation.domain.ClearingRecord;
import com.sentinelpay.reconciliation.domain.DiscrepancyType;
import com.sentinelpay.reconciliation.domain.ReconciliationDiscrepancy;
import com.sentinelpay.reconciliation.domain.ReconciliationRun;
import com.sentinelpay.reconciliation.domain.ReconciliationSummary;
import com.sentinelpay.reconciliation.parser.ClearingFileParser;
import com.sentinelpay.reconciliation.repository.DiscrepancyRepository;
import com.sentinelpay.reconciliation.repository.ReconciliationRunRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;

/**
 * Reconciles a clearing file against internal POSTED transactions by parsing it
 * in bounded chunks and mapping each chunk to a virtual thread. A semaphore keeps
 * the number of concurrent database queries below the Hikari pool size.
 */
@Service
public class VirtualThreadReconciliationEngine {

    private static final Logger log = LoggerFactory.getLogger(VirtualThreadReconciliationEngine.class);

    private final ClearingFileParser parser;
    private final ReconciliationRunRepository runRepository;
    private final DiscrepancyRepository discrepancyRepository;
    private final ReconciliationProperties properties;
    private final ObjectMapper objectMapper;
    private final JdbcClient jdbc;
    private final LedgerMetrics metrics;

    public VirtualThreadReconciliationEngine(ClearingFileParser parser,
                                             ReconciliationRunRepository runRepository,
                                             DiscrepancyRepository discrepancyRepository,
                                             ReconciliationProperties properties,
                                             ObjectMapper objectMapper,
                                             JdbcClient jdbc,
                                             LedgerMetrics metrics) {
        this.parser = parser;
        this.runRepository = runRepository;
        this.discrepancyRepository = discrepancyRepository;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.jdbc = jdbc;
        this.metrics = metrics;
    }

    public ReconciliationSummary run(Path file, LocalDate businessDate, String fileName) {
        long startedNanos = System.nanoTime();
        UUID runId = runRepository.insertRunning(fileName, sha256(file), businessDate);
        try {
            Set<UUID> seenInternalIds = ConcurrentHashMap.newKeySet();
            Semaphore permits = new Semaphore(properties.maxConcurrency());

            long totalRecords = 0;
            long matched = 0;
            long amountMismatch = 0;
            long missingInternal = 0;
            List<ReconciliationDiscrepancy> anomalies = new ArrayList<>();

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                List<Future<ChunkResult>> futures = new ArrayList<>();
                parser.parse(file, properties.chunkSize(), chunk -> futures.add(executor.submit(() -> {
                    permits.acquireUninterruptibly();
                    metrics.virtualThreadStarted();
                    try {
                        return reconcileChunk(runId, chunk, seenInternalIds);
                    } finally {
                        metrics.virtualThreadFinished();
                        permits.release();
                    }
                })));

                for (Future<ChunkResult> future : futures) {
                    ChunkResult result = await(future);
                    totalRecords += result.totalRecords();
                    matched += result.matched();
                    amountMismatch += result.amountMismatch();
                    missingInternal += result.missingInternal();
                    anomalies.addAll(result.anomalies());
                }
            }

            MissingExternalResult missingExternal =
                    findMissingExternal(runId, businessDate, seenInternalIds);
            anomalies.addAll(missingExternal.anomalies());

            discrepancyRepository.batchInsert(anomalies);
            anomalies.forEach(discrepancy ->
                    metrics.reconciliationDiscrepancy(discrepancy.type().name()));

            runRepository.markCompleted(runId, totalRecords, matched, amountMismatch,
                    missingInternal, missingExternal.count());
            long durationMs = (System.nanoTime() - startedNanos) / 1_000_000;
            ReconciliationRun run = runRepository.findById(runId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Reconciliation run disappeared after completion"));
            log.info("Reconciliation {} completed: total={} matched={} amountMismatch={} "
                            + "missingInternal={} missingExternal={} durationMs={}",
                    runId, totalRecords, matched, amountMismatch, missingInternal,
                    missingExternal.count(), durationMs);
            return ReconciliationSummary.from(run, durationMs);
        } catch (Exception exception) {
            String message = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
            runRepository.markFailed(runId, message);
            log.error("Reconciliation {} failed: {}", runId, message);
            if (exception instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Reconciliation failed", exception);
        }
    }

    private ChunkResult reconcileChunk(UUID runId, List<ClearingRecord> chunk, Set<UUID> seen) {
        List<UUID> ids = chunk.stream().map(ClearingRecord::transactionId).toList();
        Map<UUID, InternalTransaction> internalById = new HashMap<>();
        jdbc.sql("""
                        SELECT t.transaction_id,
                               t.currency,
                               CASE WHEN EXISTS (
                                   SELECT 1 FROM transactions r WHERE r.reversal_of = t.transaction_id
                               ) THEN 'REVERSED' ELSE t.status END AS effective_status,
                               COALESCE(SUM(CASE WHEN e.direction = 'CREDIT'
                                                 THEN e.signed_amount_minor ELSE 0 END), 0) AS amount_minor
                        FROM transactions t
                        LEFT JOIN ledger_entries e ON e.transaction_id = t.transaction_id
                        WHERE t.transaction_id IN (:ids)
                        GROUP BY t.transaction_id, t.currency, t.status
                        """)
                .param("ids", ids)
                .query((rs, rowNum) -> new InternalTransaction(
                        UUID.fromString(rs.getString("transaction_id")),
                        rs.getString("currency").trim(),
                        rs.getString("effective_status"),
                        rs.getLong("amount_minor")))
                .list()
                .forEach(internal -> internalById.put(internal.transactionId(), internal));

        List<ReconciliationDiscrepancy> anomalies = new ArrayList<>();
        int matched = 0;
        int amountMismatches = 0;
        int missingInternal = 0;

        for (ClearingRecord record : chunk) {
            InternalTransaction internal = internalById.get(record.transactionId());
            if (internal == null) {
                missingInternal++;
                anomalies.add(discrepancy(runId, DiscrepancyType.MISSING_INTERNAL,
                        record.externalRef(), null, record.amountMinor(), record.amountMinor(),
                        record.currency(), details(
                                "reason", "transaction not found internally",
                                "externalTransactionId", record.transactionId().toString())));
                continue;
            }
            seen.add(internal.transactionId());
            if (!"POSTED".equals(internal.status())) {
                missingInternal++;
                anomalies.add(discrepancy(runId, DiscrepancyType.MISSING_INTERNAL,
                        record.externalRef(), internal.transactionId(), record.amountMinor(),
                        internal.amountMinor(), record.currency(), details(
                                "reason", "internal transaction is not POSTED",
                                "internalStatus", internal.status())));
                continue;
            }
            if (internal.amountMinor() != record.amountMinor()) {
                amountMismatches++;
                anomalies.add(discrepancy(runId, DiscrepancyType.AMOUNT_MISMATCH,
                        record.externalRef(), internal.transactionId(), record.amountMinor(),
                        internal.amountMinor(), record.currency(), details(
                                "reason", "amount differs",
                                "externalAmountMinor", record.amountMinor(),
                                "internalAmountMinor", internal.amountMinor())));
                continue;
            }
            if (!internal.currency().equals(record.currency())) {
                amountMismatches++;
                anomalies.add(discrepancy(runId, DiscrepancyType.AMOUNT_MISMATCH,
                        record.externalRef(), internal.transactionId(), record.amountMinor(),
                        internal.amountMinor(), record.currency(), details(
                                "reason", "currency differs",
                                "externalCurrency", record.currency(),
                                "internalCurrency", internal.currency())));
                continue;
            }
            matched++;
        }
        return new ChunkResult(chunk.size(), matched, amountMismatches, missingInternal, anomalies);
    }

    private MissingExternalResult findMissingExternal(UUID runId,
                                                      LocalDate businessDate,
                                                      Set<UUID> seenInternalIds) {
        Instant start = businessDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant end = businessDate.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        List<InternalTransaction> candidates = jdbc.sql("""
                        SELECT t.transaction_id,
                               t.currency,
                               COALESCE(SUM(CASE WHEN e.direction = 'CREDIT'
                                                 THEN e.signed_amount_minor ELSE 0 END), 0) AS amount_minor
                        FROM transactions t
                        LEFT JOIN ledger_entries e ON e.transaction_id = t.transaction_id
                        WHERE t.status = 'POSTED'
                          AND t.created_at >= :start
                          AND t.created_at < :end
                          AND NOT EXISTS (
                              SELECT 1 FROM transactions r WHERE r.reversal_of = t.transaction_id
                          )
                        GROUP BY t.transaction_id, t.currency
                        """)
                .param("start", Timestamp.from(start))
                .param("end", Timestamp.from(end))
                .query((rs, rowNum) -> new InternalTransaction(
                        UUID.fromString(rs.getString("transaction_id")),
                        rs.getString("currency").trim(),
                        "POSTED",
                        rs.getLong("amount_minor")))
                .list();

        List<ReconciliationDiscrepancy> anomalies = new ArrayList<>();
        for (InternalTransaction candidate : candidates) {
            if (seenInternalIds.contains(candidate.transactionId())) {
                continue;
            }
            anomalies.add(discrepancy(runId, DiscrepancyType.MISSING_EXTERNAL,
                    null, candidate.transactionId(), null, candidate.amountMinor(),
                    candidate.currency(), details(
                            "reason", "internal transaction missing from clearing file")));
        }
        return new MissingExternalResult(anomalies.size(), anomalies);
    }

    private ReconciliationDiscrepancy discrepancy(UUID runId,
                                                  DiscrepancyType type,
                                                  String externalRef,
                                                  UUID transactionId,
                                                  Long expectedAmountMinor,
                                                  Long actualAmountMinor,
                                                  String currency,
                                                  String details) {
        return new ReconciliationDiscrepancy(UUID.randomUUID(), runId, type, externalRef,
                transactionId, expectedAmountMinor, actualAmountMinor, currency, details,
                Instant.now());
    }

    private String details(Object... values) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            map.put(String.valueOf(values[i]), values[i + 1]);
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot serialize discrepancy details", exception);
        }
    }

    private ChunkResult await(Future<ChunkResult> future) {
        try {
            return future.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Reconciliation interrupted", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Reconciliation chunk failed", cause);
        }
    }

    private String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(file);
                 DigestInputStream digestInput = new DigestInputStream(input, digest)) {
                digestInput.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot hash clearing file", exception);
        }
    }

    private record InternalTransaction(UUID transactionId,
                                       String currency,
                                       String status,
                                       long amountMinor) {
    }

    private record ChunkResult(int totalRecords,
                               int matched,
                               int amountMismatch,
                               int missingInternal,
                               List<ReconciliationDiscrepancy> anomalies) {
    }

    private record MissingExternalResult(long count,
                                         List<ReconciliationDiscrepancy> anomalies) {
    }
}
