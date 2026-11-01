package com.sentinelpay.support;

import com.sentinelpay.reconciliation.parser.ClearingFileParser;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic clearing-file fixture for the Phase 4 gate.
 *
 * <p>100,000 clearing records: 99,925 matched, 50 amount mismatches and
 * 25 transactions absent internally. The internal side additionally holds
 * 25 POSTED transactions that never appear in the file.</p>
 */
public final class SyntheticClearingFileGenerator {

    public static final int TOTAL_RECORDS = 100_000;
    public static final int MATCHED_RECORDS = 99_925;
    public static final int AMOUNT_MISMATCHES = 50;
    public static final int MISSING_INTERNAL_RECORDS = 25;
    public static final int MISSING_EXTERNAL_RECORDS = 25;

    private static final int FIRST_MISMATCH_INDEX = MATCHED_RECORDS;
    private static final int FIRST_MISSING_INTERNAL_INDEX =
            MATCHED_RECORDS + AMOUNT_MISMATCHES;
    private static final long GHOST_ID_BASE = 1_000_000L;
    private static final String CURRENCY = "INR";

    private SyntheticClearingFileGenerator() {
    }

    public record InternalTransaction(UUID transactionId,
                                      long amountMinor,
                                      String currency,
                                      Instant createdAt) {
    }

    public record Fixture(Path clearingFile,
                          long namespace,
                          List<InternalTransaction> internalTransactions) {
    }

    /**
     * Writes the 100,000-line CSV and returns every internal transaction that
     * must exist before reconciliation (99,975 file-backed + 25 extras).
     */
    public static Fixture generate(Path target, LocalDate businessDate, long namespace)
            throws IOException {
        Instant dayStart = businessDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        List<InternalTransaction> internal = new ArrayList<>(TOTAL_RECORDS);

        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            writer.write(String.join(",", ClearingFileParser.CSV_HEADER));
            writer.newLine();

            for (int index = 0; index < TOTAL_RECORDS; index++) {
                long externalAmount = amountFor(index);
                UUID transactionId;
                if (index < FIRST_MISSING_INTERNAL_INDEX) {
                    transactionId = transactionId(namespace, index);
                    long internalAmount = index < FIRST_MISMATCH_INDEX
                            ? externalAmount : externalAmount + 1;
                    internal.add(new InternalTransaction(transactionId, internalAmount,
                            CURRENCY, settledAt(dayStart, index)));
                } else {
                    transactionId = ghostTransactionId(namespace,
                            index - FIRST_MISSING_INTERNAL_INDEX);
                }
                writeCsvLine(writer, index, transactionId, externalAmount, dayStart);
            }
        }

        // Internal transactions the clearinghouse never reported.
        for (int index = FIRST_MISSING_INTERNAL_INDEX; index < TOTAL_RECORDS; index++) {
            internal.add(new InternalTransaction(transactionId(namespace, index),
                    amountFor(index), CURRENCY, settledAt(dayStart, index)));
        }
        return new Fixture(target, namespace, List.copyOf(internal));
    }

    /**
     * Writes a file whose records all reference non-existent internal
     * transactions. Used for the unmeasured warm-up run.
     */
    public static Path writeMissingInternalFile(Path target,
                                                LocalDate businessDate,
                                                long namespace,
                                                int records) throws IOException {
        Instant dayStart = businessDate.atStartOfDay(ZoneOffset.UTC).toInstant();
        try (BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
            writer.write(String.join(",", ClearingFileParser.CSV_HEADER));
            writer.newLine();
            for (int index = 0; index < records; index++) {
                writeCsvLine(writer, index, ghostTransactionId(namespace, index),
                        amountFor(index), dayStart);
            }
        }
        return target;
    }

    public static UUID transactionId(long namespace, long index) {
        return new UUID(namespace, index);
    }

    private static UUID ghostTransactionId(long namespace, long ordinal) {
        return new UUID(namespace ^ 0x5EED_5EED_5EED_5EEDL, GHOST_ID_BASE + ordinal);
    }

    private static long amountFor(int index) {
        return 10_000L + (index * 37L) % 90_000L;
    }

    private static Instant settledAt(Instant dayStart, int index) {
        return dayStart.plusSeconds(3_600L + (index * 7L) % 80_000L);
    }

    private static void writeCsvLine(BufferedWriter writer,
                                     int index,
                                     UUID transactionId,
                                     long amountMinor,
                                     Instant dayStart) throws IOException {
        writer.write("EXT-%08d,%s,%d,%s,SETTLED,%s".formatted(
                index, transactionId, amountMinor, CURRENCY,
                settledAt(dayStart, index)));
        writer.newLine();
    }
}
