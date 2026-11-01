package com.sentinelpay.reconciliation.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sentinelpay.reconciliation.domain.ClearingRecord;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Streaming clearing-file parser for CSV and NDJSON. The whole file is never
 * materialised; records are handed to the caller in bounded chunks.
 */
@Component
public class ClearingFileParser {

    public static final List<String> CSV_HEADER = List.of(
            "external_ref", "transaction_id", "amount_minor",
            "currency", "status", "settled_at");

    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final List<String> ACCEPTED_STATUSES = List.of("SETTLED", "POSTED");

    private final ObjectMapper objectMapper;

    public ClearingFileParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void parse(Path file, int chunkSize, Consumer<List<ClearingRecord>> chunkConsumer) {
        if (chunkSize <= 0) {
            throw new IllegalArgumentException("chunkSize must be positive");
        }
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".csv")) {
            parseCsv(file, chunkSize, chunkConsumer);
        } else if (name.endsWith(".ndjson") || name.endsWith(".jsonl")) {
            parseNdjson(file, chunkSize, chunkConsumer);
        } else if (looksLikeJson(file)) {
            parseNdjson(file, chunkSize, chunkConsumer);
        } else {
            parseCsv(file, chunkSize, chunkConsumer);
        }
    }

    private void parseCsv(Path file, int chunkSize, Consumer<List<ClearingRecord>> chunkConsumer) {
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .setIgnoreEmptyLines(true)
                .build();

        try (Reader fileReader = Files.newBufferedReader(file, StandardCharsets.UTF_8);
             Reader reader = skipBom(fileReader);
             CSVParser parser = CSVParser.parse(reader, format)) {
            requireHeader(parser.getHeaderNames());
            List<ClearingRecord> chunk = new ArrayList<>(chunkSize);
            for (CSVRecord csvRecord : parser) {
                long line = csvRecord.getRecordNumber() + 1;
                chunk.add(toRecord(csvRecord, line));
                if (chunk.size() == chunkSize) {
                    chunkConsumer.accept(List.copyOf(chunk));
                    chunk.clear();
                }
            }
            if (!chunk.isEmpty()) {
                chunkConsumer.accept(List.copyOf(chunk));
            }
        } catch (ClearingFileException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ClearingFileException("Cannot parse CSV clearing file", null, exception);
        }
    }

    private void parseNdjson(Path file, int chunkSize, Consumer<List<ClearingRecord>> chunkConsumer) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            List<ClearingRecord> chunk = new ArrayList<>(chunkSize);
            String line;
            long lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) {
                    continue;
                }
                chunk.add(toRecord(line, lineNumber));
                if (chunk.size() == chunkSize) {
                    chunkConsumer.accept(List.copyOf(chunk));
                    chunk.clear();
                }
            }
            if (!chunk.isEmpty()) {
                chunkConsumer.accept(List.copyOf(chunk));
            }
        } catch (ClearingFileException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ClearingFileException("Cannot parse NDJSON clearing file", null, exception);
        }
    }

    private ClearingRecord toRecord(CSVRecord record, long line) {
        if (record.size() != CSV_HEADER.size()) {
            throw new ClearingFileException(
                    "expected %d columns but found %d".formatted(CSV_HEADER.size(), record.size()), line);
        }
        String externalRef = record.get("external_ref");
        String transactionId = record.get("transaction_id");
        String amount = record.get("amount_minor");
        String currency = record.get("currency");
        String status = record.get("status");
        String settledAt = record.get("settled_at");
        return build(externalRef, transactionId, amount, currency, status, settledAt, line);
    }

    private ClearingRecord toRecord(String jsonLine, long line) {
        try {
            JsonNode node = objectMapper.readTree(jsonLine);
            if (node == null || !node.isObject()) {
                throw new ClearingFileException("NDJSON record must be an object", line);
            }
            return build(
                    text(node, "external_ref", "externalRef"),
                    text(node, "transaction_id", "transactionId"),
                    text(node, "amount_minor", "amountMinor"),
                    text(node, "currency", "currency"),
                    text(node, "status", "status"),
                    text(node, "settled_at", "settledAt"),
                    line);
        } catch (ClearingFileException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ClearingFileException("Malformed NDJSON record", line, exception);
        }
    }

    private ClearingRecord build(String externalRef,
                                 String transactionId,
                                 String amount,
                                 String currency,
                                 String status,
                                 String settledAt,
                                 long line) {
        if (externalRef == null || externalRef.isBlank() || externalRef.length() > 128) {
            throw new ClearingFileException("external_ref must be 1-128 characters", line);
        }
        UUID parsedTransactionId;
        try {
            parsedTransactionId = UUID.fromString(require(transactionId, "transaction_id", line));
        } catch (IllegalArgumentException exception) {
            throw new ClearingFileException("transaction_id is not a UUID", line);
        }
        long parsedAmount;
        try {
            parsedAmount = Long.parseLong(require(amount, "amount_minor", line));
        } catch (NumberFormatException exception) {
            throw new ClearingFileException("amount_minor is not an integer", line);
        }
        if (parsedAmount <= 0) {
            throw new ClearingFileException("amount_minor must be positive", line);
        }
        String parsedCurrency = require(currency, "currency", line).toUpperCase(Locale.ROOT);
        if (!CURRENCY.matcher(parsedCurrency).matches()) {
            throw new ClearingFileException("currency must be an ISO-4217 code", line);
        }
        String parsedStatus = require(status, "status", line).toUpperCase(Locale.ROOT);
        if (!ACCEPTED_STATUSES.contains(parsedStatus)) {
            throw new ClearingFileException("status must be SETTLED or POSTED", line);
        }
        Instant parsedSettledAt = parseInstant(require(settledAt, "settled_at", line), line);
        return new ClearingRecord(externalRef.trim(), parsedTransactionId, parsedAmount,
                parsedCurrency, parsedStatus, parsedSettledAt);
    }

    private Instant parseInstant(String value, long line) {
        try {
            return Instant.parse(value);
        } catch (Exception exception) {
            try {
                return OffsetDateTime.parse(value).toInstant();
            } catch (Exception fallback) {
                throw new ClearingFileException("settled_at must be an ISO-8601 instant", line);
            }
        }
    }

    private String require(String value, String field, long line) {
        if (value == null || value.isBlank()) {
            throw new ClearingFileException(field + " is required", line);
        }
        return value.trim();
    }

    private String text(JsonNode node, String primary, String alternative) {
        JsonNode value = node.hasNonNull(primary) ? node.get(primary) : node.get(alternative);
        return value == null || value.isNull() ? null : value.asText();
    }

    private void requireHeader(List<String> actual) {
        if (actual == null || !actual.equals(CSV_HEADER)) {
            throw new ClearingFileException(
                    "CSV header must be: " + String.join(",", CSV_HEADER), null);
        }
    }

    private Reader skipBom(Reader reader) throws IOException {
        BufferedReader buffered;
        if (reader instanceof BufferedReader existing) {
            buffered = existing;
        } else {
            buffered = new BufferedReader(reader);
        }
        buffered.mark(1);
        int first = buffered.read();
        if (first != 0xFEFF) {
            buffered.reset();
        }
        return buffered;
    }

    private boolean looksLikeJson(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            int next;
            while ((next = reader.read()) != -1) {
                if (!Character.isWhitespace(next)) {
                    return next == '{';
                }
            }
            return false;
        } catch (IOException exception) {
            throw new ClearingFileException("Cannot read clearing file", null, exception);
        }
    }
}
