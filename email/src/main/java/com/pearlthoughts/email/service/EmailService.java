package com.pearlthoughts.email.service;

import com.pearlthoughts.email.entity.EmailAttempt;
import com.pearlthoughts.email.model.EmailRequest;
import com.pearlthoughts.email.model.EmailResponse;
import com.pearlthoughts.email.model.EmailStatus;
import com.pearlthoughts.email.provider.EmailProvider;
import com.pearlthoughts.email.provider.EmailProviderException;
import com.pearlthoughts.email.repository.EmailAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Main email service with retry logic, fallback mechanism, and idempotency
 */
@Service
@Transactional
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    @Value("${email.service.retry.max-attempts:3}")
    private int maxRetryAttempts;

    @Value("${email.service.retry.initial-delay:1000}")
    private long initialDelayMs;

    @Value("${email.service.retry.max-delay:10000}")
    private long maxDelayMs;

    @Value("${email.service.retry.multiplier:2.0}")
    private double backoffMultiplier;

    private final List<EmailProvider> emailProviders;
    private final EmailAttemptRepository emailAttemptRepository;
    private final RateLimitingService rateLimitingService;
    private final CircuitBreakerService circuitBreakerService;

    public EmailService(List<EmailProvider> emailProviders,
                        EmailAttemptRepository emailAttemptRepository,
                        RateLimitingService rateLimitingService,
                        CircuitBreakerService circuitBreakerService) {
        this.emailProviders = emailProviders.stream()
                .sorted((p1, p2) -> Integer.compare(p1.getPriority(), p2.getPriority()))
                .collect(Collectors.toList());
        this.emailAttemptRepository = emailAttemptRepository;
        this.rateLimitingService = rateLimitingService;
        this.circuitBreakerService = circuitBreakerService;

        logger.info("EmailService initialized with {} providers: {}",
                emailProviders.size(),
                emailProviders.stream().map(EmailProvider::getProviderName).collect(Collectors.toList()));
    }

    /**
     * Send an email with retry logic and fallback mechanism
     *
     * @param emailRequest the email request
     * @return the email response
     */
    public EmailResponse sendEmail(EmailRequest emailRequest) {
        // Generate idempotency key if not provided
        String idempotencyKey = emailRequest.getIdempotencyKey();
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            idempotencyKey = generateIdempotencyKey(emailRequest);
            emailRequest.setIdempotencyKey(idempotencyKey);
        }

        // Check for duplicate (idempotency)
        Optional<EmailAttempt> existingAttempt = emailAttemptRepository.findByIdempotencyKey(idempotencyKey);
        if (existingAttempt.isPresent()) {
            EmailAttempt attempt = existingAttempt.get();
            logger.info("Duplicate email request detected with idempotency key: {}", idempotencyKey);
            return EmailResponse.duplicate(attempt.getEmailId(), idempotencyKey);
        }

        // Check rate limiting
        if (!rateLimitingService.isWithinRateLimit(emailRequest.getFrom())) {
            logger.warn("Rate limit exceeded for sender: {}", emailRequest.getFrom());
            return EmailResponse.rateLimited(UUID.randomUUID().toString());
        }

        // Create email attempt record
        EmailAttempt emailAttempt = createEmailAttempt(emailRequest);
        emailAttempt = emailAttemptRepository.save(emailAttempt);

        // Record rate limit attempt
        rateLimitingService.recordEmailAttempt(emailRequest.getFrom());

        // Attempt to send email with retry and fallback
        return sendEmailWithRetryAndFallback(emailRequest, emailAttempt);
    }

    /**
     * Send email with retry logic and provider fallback
     */
    private EmailResponse sendEmailWithRetryAndFallback(EmailRequest emailRequest, EmailAttempt emailAttempt) {
        List<String> allErrors = new ArrayList<>();

        for (EmailProvider provider : emailProviders) {
            if (!provider.isHealthy() || !circuitBreakerService.isProviderAvailable(provider.getProviderName())) {
                logger.warn("Skipping unhealthy or circuit-broken provider: {}", provider.getProviderName());
                continue;
            }

            logger.info("Attempting to send email using provider: {}", provider.getProviderName());

            // Try sending with this provider (with retry)
            EmailResponse response = sendEmailWithRetry(emailRequest, emailAttempt, provider);

            if (response.getStatus() == EmailStatus.SENT) {
                // Success - update database and circuit breaker
                emailAttempt.setStatus(EmailStatus.SENT);
                emailAttempt.setProviderUsed(provider.getProviderName());
                emailAttempt.setSentAt(LocalDateTime.now());
                emailAttemptRepository.save(emailAttempt);

                circuitBreakerService.recordSuccess(provider.getProviderName());
                logger.info("Email sent successfully using provider: {}", provider.getProviderName());
                return response;
            } else {
                // Failure - record error and try next provider
                allErrors.addAll(response.getErrors() != null ? response.getErrors() : List.of(response.getMessage()));
                circuitBreakerService.recordFailure(provider.getProviderName());
            }
        }

        // All providers failed
        emailAttempt.setStatus(EmailStatus.FAILED);
        emailAttempt.setErrorMessage(String.join("; ", allErrors));
        emailAttemptRepository.save(emailAttempt);

        logger.error("Email sending failed with all providers. Errors: {}", allErrors);
        return EmailResponse.failure(emailAttempt.getEmailId(), "All email providers failed", allErrors);
    }

    /**
     * Send email with exponential backoff retry
     */
    private EmailResponse sendEmailWithRetry(EmailRequest emailRequest, EmailAttempt emailAttempt, EmailProvider provider) {
        var errors = new ArrayList<String>();
        var currentDelay = initialDelayMs;

        for (int attempt = 1; attempt <= maxRetryAttempts; attempt++) {
            try {
                logger.debug("Attempt {} of {} using provider: {}", attempt, maxRetryAttempts, provider.getProviderName());

                // Update attempt count
                emailAttempt.setAttemptCount(attempt);
                emailAttempt.setStatus(EmailStatus.SENDING);
                emailAttemptRepository.save(emailAttempt);

                // Try sending
                var response = provider.sendEmail(emailRequest);
                response.setAttemptCount(attempt);
                return response;

            } catch (EmailProviderException e) {
                var errorMsg = String.format("Attempt %d failed with provider %s: %s",
                        attempt, provider.getProviderName(), e.getMessage());
                errors.add(errorMsg);
                logger.warn(errorMsg, e);

                // If this is the last attempt, don't wait
                if (attempt == maxRetryAttempts) {
                    break;
                }

                // If error is not retryable, don't retry
                if (!e.isRetryable()) {
                    logger.warn("Non-retryable error occurred, skipping remaining attempts");
                    break;
                }

                // Wait before retrying (exponential backoff)
                try {
                    Thread.sleep(currentDelay);
                    currentDelay = Math.min((long) (currentDelay * backoffMultiplier), maxDelayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    errors.add("Retry interrupted");
                    break;
                }
            }
        }

        // All retries failed
        return EmailResponse.failure(emailAttempt.getEmailId(),
                String.format("Provider %s failed after %d attempts",
                        provider.getProviderName(), maxRetryAttempts),
                errors);
    }

    /**
     * Get email status by ID
     */
    public EmailResponse getEmailStatus(String emailId) {
        Optional<EmailAttempt> attempt = emailAttemptRepository.findByEmailId(emailId);
        if (attempt.isEmpty()) {
            return EmailResponse.failure(emailId, "Email not found", List.of("Email ID not found"));
        }

        EmailAttempt emailAttempt = attempt.get();
        EmailResponse response = new EmailResponse();
        response.setId(emailAttempt.getEmailId());
        response.setStatus(emailAttempt.getStatus());
        response.setMessage(emailAttempt.getStatus().getDescription());
        response.setProviderUsed(emailAttempt.getProviderUsed());
        response.setAttemptCount(emailAttempt.getAttemptCount());
        response.setTimestamp(emailAttempt.getUpdatedAt());
        response.setIdempotencyKey(emailAttempt.getIdempotencyKey());

        if (emailAttempt.getErrorMessage() != null) {
            response.setErrors(List.of(emailAttempt.getErrorMessage()));
        }

        return response;
    }

    /**
     * Get all email attempts with pagination
     */
    public List<EmailResponse> getEmailAttempts(EmailStatus status, int page, int size) {
        List<EmailAttempt> attempts;
        if (status != null) {
            attempts = emailAttemptRepository.findByStatus(status);
        } else {
            attempts = emailAttemptRepository.findAll();
        }

        return attempts.stream()
                .skip((long) page * size)
                .limit(size)
                .map(this::convertToResponse)
                .collect(Collectors.toList());
    }

    private EmailAttempt createEmailAttempt(EmailRequest emailRequest) {
        EmailAttempt attempt = new EmailAttempt();
        attempt.setEmailId(UUID.randomUUID().toString());
        attempt.setIdempotencyKey(emailRequest.getIdempotencyKey());
        attempt.setFromEmail(emailRequest.getFrom());
        attempt.setToEmails(String.join(",", emailRequest.getTo()));

        if (emailRequest.getCc() != null) {
            attempt.setCcEmails(String.join(",", emailRequest.getCc()));
        }
        if (emailRequest.getBcc() != null) {
            attempt.setBccEmails(String.join(",", emailRequest.getBcc()));
        }

        attempt.setSubject(emailRequest.getSubject());
        attempt.setBody(emailRequest.getBody());
        attempt.setHtml(emailRequest.isHtml());
        attempt.setStatus(EmailStatus.PENDING);

        return attempt;
    }

    private String generateIdempotencyKey(EmailRequest emailRequest) {
        // Generate idempotency key based on email content
        String content = emailRequest.getFrom() +
                String.join(",", emailRequest.getTo()) +
                emailRequest.getSubject() +
                emailRequest.getBody();
        return UUID.nameUUIDFromBytes(content.getBytes()).toString();
    }

    private EmailResponse convertToResponse(EmailAttempt attempt) {
        EmailResponse response = new EmailResponse();
        response.setId(attempt.getEmailId());
        response.setStatus(attempt.getStatus());
        response.setMessage(attempt.getStatus().getDescription());
        response.setProviderUsed(attempt.getProviderUsed());
        response.setAttemptCount(attempt.getAttemptCount());
        response.setTimestamp(attempt.getUpdatedAt());
        response.setIdempotencyKey(attempt.getIdempotencyKey());

        if (attempt.getErrorMessage() != null) {
            response.setErrors(List.of(attempt.getErrorMessage()));
        }

        return response;
    }
}
