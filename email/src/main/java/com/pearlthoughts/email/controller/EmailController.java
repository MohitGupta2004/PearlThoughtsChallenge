package com.pearlthoughts.email.controller;

import com.pearlthoughts.email.model.EmailRequest;
import com.pearlthoughts.email.model.EmailResponse;
import com.pearlthoughts.email.model.EmailStatus;
import com.pearlthoughts.email.service.CircuitBreakerService;
import com.pearlthoughts.email.service.EmailQueueService;
import com.pearlthoughts.email.service.EmailService;
import com.pearlthoughts.email.service.RateLimitingService;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/emails")
public class EmailController {

    private static final Logger logger = LoggerFactory.getLogger(EmailController.class);

    private final EmailService emailService;
    private final EmailQueueService emailQueueService;
    private final RateLimitingService rateLimitingService;
    private final CircuitBreakerService circuitBreakerService;

    public EmailController(EmailService emailService,
                           EmailQueueService emailQueueService,
                           RateLimitingService rateLimitingService,
                           CircuitBreakerService circuitBreakerService) {
        this.emailService = emailService;
        this.emailQueueService = emailQueueService;
        this.rateLimitingService = rateLimitingService;
        this.circuitBreakerService = circuitBreakerService;
    }

    /**
     * Send an email synchronously
     */
    @PostMapping("/send")
    public ResponseEntity<?> sendEmail(@Valid @RequestBody EmailRequest emailRequest,
                                       BindingResult bindingResult) {

        if (bindingResult.hasErrors()) {
            List<String> errors = bindingResult.getFieldErrors().stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .collect(Collectors.toList());

            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Validation failed",
                    "details", errors
            ));
        }

        try {
            logger.info("Received email send request from: {} to: {}",
                    emailRequest.getFrom(), emailRequest.getTo());

            EmailResponse response = emailService.sendEmail(emailRequest);

            HttpStatus status = switch (response.getStatus()) {
                case SENT -> HttpStatus.OK;
                case DUPLICATE -> HttpStatus.CONFLICT;
                case RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
                case FAILED -> HttpStatus.INTERNAL_SERVER_ERROR;
                case PENDING, SENDING -> HttpStatus.ACCEPTED;
            };

            return ResponseEntity.status(status).body(response);

        } catch (Exception e) {
            logger.error("Unexpected error sending email", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Internal server error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Queue an email for asynchronous processing
     */
    @PostMapping("/queue")
    public ResponseEntity<?> queueEmail(@Valid @RequestBody EmailRequest emailRequest,
                                        BindingResult bindingResult) {

        if (bindingResult.hasErrors()) {
            List<String> errors = bindingResult.getFieldErrors().stream()
                    .map(error -> error.getField() + ": " + error.getDefaultMessage())
                    .collect(Collectors.toList());

            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Validation failed",
                    "details", errors
            ));
        }

        try {
            logger.info("Received email queue request from: {} to: {}",
                    emailRequest.getFrom(), emailRequest.getTo());

            EmailResponse response = emailQueueService.queueEmail(emailRequest);

            HttpStatus status = response.getStatus() == EmailStatus.PENDING
                    ? HttpStatus.ACCEPTED
                    : HttpStatus.INTERNAL_SERVER_ERROR;

            return ResponseEntity.status(status).body(response);

        } catch (Exception e) {
            logger.error("Unexpected error queueing email", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Internal server error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Get email status by ID
     */
    @GetMapping("/{emailId}/status")
    public ResponseEntity<?> getEmailStatus(@PathVariable String emailId) {
        try {
            EmailResponse response = emailService.getEmailStatus(emailId);

            if (response.getStatus() == EmailStatus.FAILED &&
                    response.getErrors() != null &&
                    response.getErrors().contains("Email ID not found")) {
                return ResponseEntity.notFound().build();
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Error getting email status for ID: {}", emailId, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Internal server error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Get email attempts with optional filtering
     */
    @GetMapping
    public ResponseEntity<?> getEmailAttempts(
            @RequestParam(required = false) EmailStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        try {
            if (size > 100) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Page size cannot exceed 100"
                ));
            }

            List<EmailResponse> responses = emailService.getEmailAttempts(status, page, size);

            return ResponseEntity.ok(Map.of(
                    "emails", responses,
                    "page", page,
                    "size", size,
                    "totalElements", responses.size()
            ));

        } catch (Exception e) {
            logger.error("Error getting email attempts", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Internal server error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Get queue statistics
     */
    @GetMapping("/queue/stats")
    public ResponseEntity<?> getQueueStats() {
        try {
            EmailQueueService.QueueStats stats = emailQueueService.getQueueStats();
            return ResponseEntity.ok(stats);
        } catch (Exception e) {
            logger.error("Error getting queue stats", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Internal server error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Get rate limiting information for a sender
     */
    @GetMapping("/rate-limit/{fromEmail}")
    public ResponseEntity<?> getRateLimitInfo(@PathVariable String fromEmail) {
        try {
            int currentCount = rateLimitingService.getCurrentRequestCount(fromEmail);
            RateLimitingService.RateLimitConfig config = rateLimitingService.getRateLimitConfig();

            return ResponseEntity.ok(Map.of(
                    "fromEmail", fromEmail,
                    "currentRequests", currentCount,
                    "maxRequests", config.getMaxRequests(),
                    "windowSeconds", config.getWindowSeconds(),
                    "remainingRequests", Math.max(0, config.getMaxRequests() - currentCount),
                    "isWithinLimit", rateLimitingService.isWithinRateLimit(fromEmail)
            ));
        } catch (Exception e) {
            logger.error("Error getting rate limit info for: {}", fromEmail, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Internal server error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Get circuit breaker status for all providers
     */
    @GetMapping("/circuit-breaker/status")
    public ResponseEntity<?> getCircuitBreakerStatus() {
        try {
            // This is a simple implementation - in a real system you'd want to track all providers
            Map<String, Object> status = Map.of(
                    "MockProviderA", Map.of(
                            "state", circuitBreakerService.getCircuitState("MockProviderA"),
                            "available", circuitBreakerService.isProviderAvailable("MockProviderA")
                    ),
                    "MockProviderB", Map.of(
                            "state", circuitBreakerService.getCircuitState("MockProviderB"),
                            "available", circuitBreakerService.isProviderAvailable("MockProviderB")
                    )
            );

            return ResponseEntity.ok(status);
        } catch (Exception e) {
            logger.error("Error getting circuit breaker status", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of(
                    "error", "Internal server error",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Health check endpoint
     */
    @GetMapping("/health")
    public ResponseEntity<?> healthCheck() {
        try {
            EmailQueueService.QueueStats stats = emailQueueService.getQueueStats();

            return ResponseEntity.ok(Map.of(
                    "status", "UP",
                    "timestamp", System.currentTimeMillis(),
                    "queue", Map.of(
                            "size", stats.getCurrentQueueSize(),
                            "utilization", String.format("%.2f%%", stats.getQueueUtilization() * 100),
                            "processing", stats.isProcessing()
                    ),
                    "emails", Map.of(
                            "pending", stats.getPendingEmails(),
                            "sending", stats.getSendingEmails(),
                            "sent", stats.getSentEmails(),
                            "failed", stats.getFailedEmails()
                    )
            ));
        } catch (Exception e) {
            logger.error("Error performing health check", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                    "status", "DOWN",
                    "error", e.getMessage()
            ));
        }
    }
}
//controller done