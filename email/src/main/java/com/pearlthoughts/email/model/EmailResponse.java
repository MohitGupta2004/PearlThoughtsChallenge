package com.pearlthoughts.email.model;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Represents the response of an email sending operation
 */
@Getter
@Setter

public class EmailResponse {

    private String id;
    private EmailStatus status;
    private String message;
    private LocalDateTime timestamp;
    private String providerUsed;
    private int attemptCount;
    private List<String> errors;
    private String idempotencyKey;

    // Constructors
    public EmailResponse() {
        this.timestamp = LocalDateTime.now();
    }

    public EmailResponse(String id, EmailStatus status, String message) {
        this();
        this.id = id;
        this.status = status;
        this.message = message;
    }

    // Static factory methods
    public static EmailResponse success(String id, String providerUsed) {
        EmailResponse response = new EmailResponse(id, EmailStatus.SENT, "Email sent successfully");
        response.setProviderUsed(providerUsed);
        return response;
    }

    public static EmailResponse failure(String id, String message, List<String> errors) {
        EmailResponse response = new EmailResponse(id, EmailStatus.FAILED, message);
        response.setErrors(errors);
        return response;
    }

    public static EmailResponse pending(String id) {
        return new EmailResponse(id, EmailStatus.PENDING, "Email queued for sending");
    }

    public static EmailResponse duplicate(String id, String idempotencyKey) {
        EmailResponse response = new EmailResponse(id, EmailStatus.DUPLICATE, "Email already sent");
        response.setIdempotencyKey(idempotencyKey);
        return response;
    }

    public static EmailResponse rateLimited(String id) {
        return new EmailResponse(id, EmailStatus.RATE_LIMITED, "Email sending rate limited");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        EmailResponse that = (EmailResponse) o;
        return attemptCount == that.attemptCount &&
                Objects.equals(id, that.id) &&
                status == that.status &&
                Objects.equals(message, that.message) &&
                Objects.equals(timestamp, that.timestamp) &&
                Objects.equals(providerUsed, that.providerUsed) &&
                Objects.equals(errors, that.errors) &&
                Objects.equals(idempotencyKey, that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id, status, message, timestamp, providerUsed, attemptCount, errors, idempotencyKey);
    }

    @Override
    public String toString() {
        return "EmailResponse{" +
                "id='" + id + '\'' +
                ", status=" + status +
                ", message='" + message + '\'' +
                ", timestamp=" + timestamp +
                ", providerUsed='" + providerUsed + '\'' +
                ", attemptCount=" + attemptCount +
                ", errors=" + errors +
                ", idempotencyKey='" + idempotencyKey + '\'' +
                '}';
    }
}
