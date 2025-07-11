package com.pearlthoughts.email.service;

import com.pearlthoughts.email.model.EmailStatus;
import com.pearlthoughts.email.repository.EmailAttemptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate limiting service to prevent email spam and abuse
 * Implements sliding window rate limiting
 */
@Service
public class RateLimitingService {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitingService.class);

    @Value("${email.service.rate-limit.max-requests:100}")
    private int maxRequests;

    @Value("${email.service.rate-limit.window-seconds:60}")
    private int windowSeconds;

    private final EmailAttemptRepository emailAttemptRepository;
    private final ConcurrentMap<String, SlidingWindow> rateLimitWindows = new ConcurrentHashMap<>();

    public RateLimitingService(EmailAttemptRepository emailAttemptRepository) {
        this.emailAttemptRepository = emailAttemptRepository;
    }

    /**
     * Check if an email request is within rate limits
     *
     * @param fromEmail the sender email address
     * @return true if within rate limits, false otherwise
     */
    public boolean isWithinRateLimit(String fromEmail) {
        // Check in-memory sliding window first (for immediate rate limiting)
        SlidingWindow window = rateLimitWindows.computeIfAbsent(fromEmail, k -> new SlidingWindow());

        synchronized (window) {
            LocalDateTime now = LocalDateTime.now();
            window.cleanupOldEntries(now);

            if (window.getCurrentCount() >= maxRequests) {
                logger.warn("Rate limit exceeded for email: {} (in-memory check)", fromEmail);
                return false;
            }

            // Also check database for accuracy (in case of restarts)
            LocalDateTime windowStart = now.minusSeconds(windowSeconds);
            List<EmailStatus> countableStatuses = Arrays.asList(
                    EmailStatus.PENDING, EmailStatus.SENDING, EmailStatus.SENT
            );

            long dbCount = emailAttemptRepository.countByFromEmailAndCreatedAtAfterAndStatusIn(
                    fromEmail, windowStart, countableStatuses
            );

            if (dbCount >= maxRequests) {
                logger.warn("Rate limit exceeded for email: {} (database check: {} requests)", fromEmail, dbCount);
                return false;
            }

            // Add to sliding window
            window.addRequest(now);
            logger.debug("Rate limit check passed for email: {} (current count: {})", fromEmail, window.getCurrentCount());
            return true;
        }
    }

    /**
     * Record an email attempt for rate limiting
     * This is called after an email is queued/sent
     */
    public void recordEmailAttempt(String fromEmail) {
        SlidingWindow window = rateLimitWindows.computeIfAbsent(fromEmail, k -> new SlidingWindow());
        synchronized (window) {
            window.addRequest(LocalDateTime.now());
        }
    }

    /**
     * Get current request count for an email address
     */
    public int getCurrentRequestCount(String fromEmail) {
        SlidingWindow window = rateLimitWindows.get(fromEmail);
        if (window == null) {
            return 0;
        }

        synchronized (window) {
            window.cleanupOldEntries(LocalDateTime.now());
            return window.getCurrentCount();
        }
    }

    /**
     * Reset rate limit for an email address (for testing)
     */
    public void resetRateLimit(String fromEmail) {
        rateLimitWindows.remove(fromEmail);
        logger.info("Rate limit reset for email: {}", fromEmail);
    }

    /**
     * Get rate limit configuration
     */
    public RateLimitConfig getRateLimitConfig() {
        return new RateLimitConfig(maxRequests, windowSeconds);
    }

    /**
     * Sliding window implementation for rate limiting
     */
    private class SlidingWindow {
        private final ConcurrentMap<LocalDateTime, AtomicInteger> timeSlots = new ConcurrentHashMap<>();

        public void addRequest(LocalDateTime timestamp) {
            // Round to nearest second for grouping
            LocalDateTime roundedTime = timestamp.withNano(0);
            timeSlots.computeIfAbsent(roundedTime, k -> new AtomicInteger(0)).incrementAndGet();
        }

        public int getCurrentCount() {
            return timeSlots.values().stream()
                    .mapToInt(AtomicInteger::get)
                    .sum();
        }

        public void cleanupOldEntries(LocalDateTime now) {
            LocalDateTime cutoff = now.minusSeconds(windowSeconds);
            timeSlots.entrySet().removeIf(entry -> entry.getKey().isBefore(cutoff));
        }
    }

    /**
     * Rate limit configuration holder
     */
    public static class RateLimitConfig {
        private final int maxRequests;
        private final int windowSeconds;

        public RateLimitConfig(int maxRequests, int windowSeconds) {
            this.maxRequests = maxRequests;
            this.windowSeconds = windowSeconds;
        }

        public int getMaxRequests() {
            return maxRequests;
        }

        public int getWindowSeconds() {
            return windowSeconds;
        }

        @Override
        public String toString() {
            return String.format("RateLimitConfig{maxRequests=%d, windowSeconds=%d}", maxRequests, windowSeconds);
        }
    }
}
