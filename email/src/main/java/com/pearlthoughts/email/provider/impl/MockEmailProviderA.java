package com.pearlthoughts.email.provider.impl;

import com.pearlthoughts.email.model.EmailRequest;
import com.pearlthoughts.email.model.EmailResponse;
import com.pearlthoughts.email.provider.EmailProvider;
import com.pearlthoughts.email.provider.EmailProviderException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Random;
import java.util.UUID;

/**
 * Mock Email Provider A - Simulates a primary email service
 * This provider has a 80% success rate and higher priority
 */
@Component
public class MockEmailProviderA implements EmailProvider {

    private static final Logger logger = LoggerFactory.getLogger(MockEmailProviderA.class);
    private static final String PROVIDER_NAME = "MockProviderA";
    private static final int PRIORITY = 1; // Higher priority
    private static final double SUCCESS_RATE = 0.8; // 80% success rate

    private final Random random = new Random();
    private volatile boolean healthy = true;

    @Override
    public EmailResponse sendEmail(EmailRequest emailRequest) throws EmailProviderException {
        logger.info("MockProviderA attempting to send email to: {}", emailRequest.getTo());

        // Simulate network latency
        try {
            Thread.sleep(100 + random.nextInt(200)); // 100-300ms delay
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmailProviderException("Interrupted while sending email", e, PROVIDER_NAME);
        }

        // Simulate random failures
        if (random.nextDouble() > SUCCESS_RATE) {
            String errorMessage = "MockProviderA failed to send email - simulated failure";
            logger.warn(errorMessage);
            throw new EmailProviderException(errorMessage, PROVIDER_NAME, true);
        }

        // Simulate success
        String emailId = UUID.randomUUID().toString();
        logger.info("MockProviderA successfully sent email with ID: {}", emailId);

        return EmailResponse.success(emailId, PROVIDER_NAME);
    }

    @Override
    public String getProviderName() {
        return PROVIDER_NAME;
    }

    @Override
    public boolean isHealthy() {
        return healthy;
    }

    @Override
    public int getPriority() {
        return PRIORITY;
    }

    /**
     * Simulate provider health changes for testing
     */
    public void setHealthy(boolean healthy) {
        this.healthy = healthy;
        logger.info("MockProviderA health status changed to: {}", healthy);
    }
}
