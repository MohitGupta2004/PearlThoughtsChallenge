package com.pearlthoughts.email.model;

/**
 * Represents the status of an email sending attempt
 */

public enum EmailStatus {
    PENDING("Email is queued for sending"),
    SENDING("Email is currently being sent"),
    SENT("Email was successfully sent"),
    FAILED("Email sending failed after all retries"),
    RATE_LIMITED("Email sending was rate limited"),
    DUPLICATE("Email was not sent due to duplicate detection");

    private final String description;

    EmailStatus(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public boolean isTerminal() {
        return this == SENT || this == FAILED || this == DUPLICATE;
    }

    public boolean isSuccessful() {
        return this == SENT;
    }
}
