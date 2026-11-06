package com.experterp.backend.integration.xero;

/**
 * Xero said no, or could not be reached. The kind decides what the sync queue does: VALIDATION is not retried (it needs
 * a person), RATE_LIMIT waits retryAfterSeconds, UNAUTHORIZED refreshes the token, UNAVAILABLE retries later.
 */
public class XeroException extends RuntimeException {

    public enum Kind { VALIDATION, RATE_LIMIT, UNAUTHORIZED, UNAVAILABLE, NOT_FOUND }

    private final Kind kind;
    private final int status;
    private final long retryAfterSeconds;

    public XeroException(Kind kind, int status, String message, long retryAfterSeconds) {
        super(message);
        this.kind = kind;
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public XeroException(Kind kind, int status, String message) {
        this(kind, status, message, 0);
    }

    public Kind kind() { return kind; }
    public int status() { return status; }
    public long retryAfterSeconds() { return retryAfterSeconds; }

    public boolean retryable() {
        return kind == Kind.RATE_LIMIT || kind == Kind.UNAVAILABLE || kind == Kind.UNAUTHORIZED;
    }
}
