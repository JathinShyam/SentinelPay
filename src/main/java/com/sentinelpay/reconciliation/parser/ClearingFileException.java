package com.sentinelpay.reconciliation.parser;

/**
 * Raised when a clearing file cannot be parsed. Carries the 1-based file line
 * when the failure is attributable to a specific record.
 */
public class ClearingFileException extends RuntimeException {

    private final Long lineNumber;

    public ClearingFileException(String message, Long lineNumber) {
        super(lineNumber == null ? message : "Line " + lineNumber + ": " + message);
        this.lineNumber = lineNumber;
    }

    public ClearingFileException(String message, Long lineNumber, Throwable cause) {
        super(lineNumber == null ? message : "Line " + lineNumber + ": " + message, cause);
        this.lineNumber = lineNumber;
    }

    public Long lineNumber() {
        return lineNumber;
    }
}
