package com.pearlthoughts.email.provider;

import com.pearlthoughts.email.model.EmailRequest;
import com.pearlthoughts.email.model.EmailResponse;

/**
 * Interface for email providers
 */
public interface EmailProvider {

    /**
     * Send an email using this provider
     *
     * @param emailRequest the email request to send
     * @return the email response
     * @throws EmailProviderException if sending fails
     */
    EmailResponse sendEmail(EmailRequest emailRequest) throws EmailProviderException;

    /**
     * Get the name of this provider
     *
     * @return provider name
     */
    String getProviderName();

    /**
     * Check if this provider is currently healthy/available
     *
     * @return true if provider is available
     */
    boolean isHealthy();

    /**
     * Get the priority of this provider (lower number = higher priority)
     *
     * @return provider priority
     */
    int getPriority();
}
