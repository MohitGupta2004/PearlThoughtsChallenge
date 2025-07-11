package com.pearlthoughts.email.service;

import com.pearlthoughts.email.entity.EmailAttempt;
import com.pearlthoughts.email.model.EmailRequest;
import com.pearlthoughts.email.model.EmailResponse;
import com.pearlthoughts.email.model.EmailStatus;
import com.pearlthoughts.email.repository.EmailAttemptRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class EmailQueueService {

    private static final Logger logger = LoggerFactory.getLogger(EmailQueueService.class);

    @Value("${email.service.queue.max-size:1000}")
    private int maxQueueSize;

    @Value("${email.service.queue.processing-interval:5000}")
    private long processingIntervalMs;

    private BlockingQueue<EmailRequest> emailQueue;
    private final EmailService emailService;
    private final EmailAttemptRepository emailAttemptRepository;
    private final AtomicBoolean isProcessing = new AtomicBoolean(false);
    // Java
    @PostConstruct
    private void initQueue() {
        emailQueue = new LinkedBlockingQueue<>(maxQueueSize);
        logger.info("EmailQueueService initialized with max queue size: {}", maxQueueSize);
    }

    public EmailQueueService(EmailService emailService, EmailAttemptRepository emailAttemptRepository) {
        this.emailService = emailService;
        this.emailAttemptRepository = emailAttemptRepository;
    }

    /**
     * Queue an email for asynchronous processing
     *
     * @param emailRequest the email request to queue
     * @return the email response with pending status
     */
    public EmailResponse queueEmail(EmailRequest emailRequest) {
        if (emailQueue.size() >= maxQueueSize) {
            logger.warn("Email queue is full, rejecting email request");
            return EmailResponse.failure(null, "Email queue is full",
                    List.of("Queue capacity exceeded: " + maxQueueSize));
        }

        try {
            emailQueue.offer(emailRequest);
            logger.info("Email queued successfully. Queue size: {}", emailQueue.size());
            return EmailResponse.pending(null);
        } catch (Exception e) {
            logger.error("Failed to queue email", e);
            return EmailResponse.failure(null, "Failed to queue email", List.of(e.getMessage()));
        }
    }

    /**
     * Process emails from the queue asynchronously
     */
    @Async
    @Scheduled(fixedDelayString = "${email.service.queue.processing-interval:5000}")
    public void processEmailQueue() {
        if (!isProcessing.compareAndSet(false, true)) {
            logger.debug("Email queue processing already in progress, skipping");
            return;
        }

        try {
            logger.debug("Starting email queue processing. Queue size: {}", emailQueue.size());

            int processedCount = 0;
            int maxBatchSize = 10; // Process up to 10 emails per batch

            while (processedCount < maxBatchSize && !emailQueue.isEmpty()) {
                EmailRequest emailRequest = emailQueue.poll();
                if (emailRequest != null) {
                    try {
                        EmailResponse response = emailService.sendEmail(emailRequest);
                        logger.debug("Processed queued email with result: {}", response.getStatus());
                        processedCount++;
                    } catch (Exception e) {
                        logger.error("Error processing queued email", e);
                    }
                }
            }

            if (processedCount > 0) {
                logger.info("Processed {} emails from queue. Remaining queue size: {}",
                        processedCount, emailQueue.size());
            }

        } finally {
            isProcessing.set(false);
        }
    }

    /**
     * Process failed emails for retry
     */
    @Scheduled(fixedDelayString = "${email.service.queue.processing-interval:5000}")
    public void processFailedEmails() {
        try {
            LocalDateTime retryAfter = LocalDateTime.now().minusMinutes(5); // Retry after 5 minutes
            int maxRetryAttempts = 3;

            List<EmailAttempt> failedEmails = emailAttemptRepository.findFailedEmailsForRetry(
                    EmailStatus.FAILED, maxRetryAttempts, retryAfter
            );

            if (!failedEmails.isEmpty()) {
                logger.info("Found {} failed emails to retry", failedEmails.size());

                for (EmailAttempt emailAttempt : failedEmails) {
                    try {
                        EmailRequest emailRequest = convertToEmailRequest(emailAttempt);
                        EmailResponse response = emailService.sendEmail(emailRequest);
                        logger.debug("Retry result for email {}: {}", emailAttempt.getEmailId(), response.getStatus());
                    } catch (Exception e) {
                        logger.error("Error retrying failed email: {}", emailAttempt.getEmailId(), e);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Error processing failed emails", e);
        }
    }

    /**
     * Get queue statistics
     */
    public QueueStats getQueueStats() {
        long pendingCount = emailAttemptRepository.countByStatus(EmailStatus.PENDING);
        long sendingCount = emailAttemptRepository.countByStatus(EmailStatus.SENDING);
        long sentCount = emailAttemptRepository.countByStatus(EmailStatus.SENT);
        long failedCount = emailAttemptRepository.countByStatus(EmailStatus.FAILED);

        return new QueueStats(
                emailQueue.size(),
                maxQueueSize,
                pendingCount,
                sendingCount,
                sentCount,
                failedCount,
                isProcessing.get()
        );
    }

    /**
     * Clear the email queue (for testing)
     */
    public void clearQueue() {
        emailQueue.clear();
        logger.info("Email queue cleared");
    }

    /**
     * Get current queue size
     */
    public int getQueueSize() {
        return emailQueue.size();
    }

    /**
     * Check if queue is full
     */
    public boolean isQueueFull() {
        return emailQueue.size() >= maxQueueSize;
    }

    private EmailRequest convertToEmailRequest(EmailAttempt emailAttempt) {
        EmailRequest request = new EmailRequest();
        request.setFrom(emailAttempt.getFromEmail());
        request.setTo(Arrays.asList(emailAttempt.getToEmails().split(",")));

        if (emailAttempt.getCcEmails() != null && !emailAttempt.getCcEmails().isEmpty()) {
            request.setCc(Arrays.asList(emailAttempt.getCcEmails().split(",")));
        }
        if (emailAttempt.getBccEmails() != null && !emailAttempt.getBccEmails().isEmpty()) {
            request.setBcc(Arrays.asList(emailAttempt.getBccEmails().split(",")));
        }

        request.setSubject(emailAttempt.getSubject());
        request.setBody(emailAttempt.getBody());
        request.setHtml(emailAttempt.isHtml());
        request.setIdempotencyKey(emailAttempt.getIdempotencyKey());

        return request;
    }

    /**
     * Queue statistics holder
     */
    public static class QueueStats {
        private final int currentQueueSize;
        private final int maxQueueSize;
        private final long pendingEmails;
        private final long sendingEmails;
        private final long sentEmails;
        private final long failedEmails;
        private final boolean isProcessing;

        public QueueStats(int currentQueueSize, int maxQueueSize, long pendingEmails,
                          long sendingEmails, long sentEmails, long failedEmails, boolean isProcessing) {
            this.currentQueueSize = currentQueueSize;
            this.maxQueueSize = maxQueueSize;
            this.pendingEmails = pendingEmails;
            this.sendingEmails = sendingEmails;
            this.sentEmails = sentEmails;
            this.failedEmails = failedEmails;
            this.isProcessing = isProcessing;
        }

        // Getters
        public int getCurrentQueueSize() { return currentQueueSize; }
        public int getMaxQueueSize() { return maxQueueSize; }
        public long getPendingEmails() { return pendingEmails; }
        public long getSendingEmails() { return sendingEmails; }
        public long getSentEmails() { return sentEmails; }
        public long getFailedEmails() { return failedEmails; }
        public boolean isProcessing() { return isProcessing; }

        public double getQueueUtilization() {
            return maxQueueSize > 0 ? (double) currentQueueSize / maxQueueSize : 0.0;
        }

        @Override
        public String toString() {
            return String.format("QueueStats{currentQueueSize=%d, maxQueueSize=%d, pendingEmails=%d, " +
                            "sendingEmails=%d, sentEmails=%d, failedEmails=%d, isProcessing=%s, utilization=%.2f%%}",
                    currentQueueSize, maxQueueSize, pendingEmails, sendingEmails,
                    sentEmails, failedEmails, isProcessing, getQueueUtilization() * 100);
        }
    }
}
