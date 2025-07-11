package com.pearlthoughts.email.provider;

/**
 * Exception thrown when an email provider fails to send an email
 */
public class EmailProviderException extends Exception {

    private final String providerName;
    private final boolean isRetryable;

    public EmailProviderException(String message, String providerName) {
        super(message);
        this.providerName = providerName;
        this.isRetryable = true;
    }

    public EmailProviderException(String message, String providerName, boolean isRetryable) {
        super(message);
        this.providerName = providerName;
        this.isRetryable = isRetryable;
    }

    public EmailProviderException(String message, Throwable cause, String providerName) {
        super(message, cause);
        this.providerName = providerName;
        this.isRetryable = true;
    }

    public EmailProviderException(String message, Throwable cause, String providerName, boolean isRetryable) {
        super(message, cause);
        this.providerName = providerName;
        this.isRetryable = isRetryable;
    }

    public String getProviderName() {
        return providerName;
    }

    public boolean isRetryable() {
        return isRetryable;
    }
}
