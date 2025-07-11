package com.pearlthoughts.email.Service;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pearlthoughts.email.model.EmailStatus;
import com.pearlthoughts.email.repository.EmailAttemptRepository;
import com.pearlthoughts.email.service.RateLimitingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.Mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class RateLimitingServiceTest {

    @Mock
    private EmailAttemptRepository emailAttemptRepository;

    private RateLimitingService rateLimitingService;
    private static final String TEST_EMAIL = "test@example.com";

    @BeforeEach
    void setUp() {
        rateLimitingService = new RateLimitingService(emailAttemptRepository);
        ReflectionTestUtils.setField(rateLimitingService, "maxRequests", 5);
        ReflectionTestUtils.setField(rateLimitingService, "windowSeconds", 60);
    }

    @Test
    void testIsWithinRateLimit_InitialRequest_ShouldReturnTrue() {
        // Arrange
        when(emailAttemptRepository.countByFromEmailAndCreatedAtAfterAndStatusIn(
                eq(TEST_EMAIL), any(LocalDateTime.class), anyList())).thenReturn(0L);

        // Act
        boolean result = rateLimitingService.isWithinRateLimit(TEST_EMAIL);

        // Assert
        assertTrue(result);
        verify(emailAttemptRepository).countByFromEmailAndCreatedAtAfterAndStatusIn(
                eq(TEST_EMAIL), any(LocalDateTime.class), anyList());
    }

    @Test
    void testIsWithinRateLimit_WithinLimit_ShouldReturnTrue() {
        // Arrange
        when(emailAttemptRepository.countByFromEmailAndCreatedAtAfterAndStatusIn(
                eq(TEST_EMAIL), any(LocalDateTime.class), anyList())).thenReturn(3L);

        // Act
        boolean result = rateLimitingService.isWithinRateLimit(TEST_EMAIL);

        // Assert
        assertTrue(result);
    }

    @Test
    void testIsWithinRateLimit_AtLimit_ShouldReturnFalse() {
        // Arrange
        when(emailAttemptRepository.countByFromEmailAndCreatedAtAfterAndStatusIn(
                eq(TEST_EMAIL), any(LocalDateTime.class), anyList())).thenReturn(5L);

        // Act
        boolean result = rateLimitingService.isWithinRateLimit(TEST_EMAIL);

        // Assert
        assertFalse(result);
    }

    @Test
    void testIsWithinRateLimit_ExceedsLimit_ShouldReturnFalse() {
        // Arrange
        when(emailAttemptRepository.countByFromEmailAndCreatedAtAfterAndStatusIn(
                eq(TEST_EMAIL), any(LocalDateTime.class), anyList())).thenReturn(10L);

        // Act
        boolean result = rateLimitingService.isWithinRateLimit(TEST_EMAIL);

        // Assert
        assertFalse(result);
    }

    @Test
    void testIsWithinRateLimit_InMemoryLimit_ShouldReturnFalse() {
        // Arrange
        when(emailAttemptRepository.countByFromEmailAndCreatedAtAfterAndStatusIn(
                eq(TEST_EMAIL), any(LocalDateTime.class), anyList())).thenReturn(0L);

        // Act - add requests up to limit
        for (int i = 0; i < 5; i++) {
            rateLimitingService.isWithinRateLimit(TEST_EMAIL);
        }

        // Additional request should be rejected
        boolean result = rateLimitingService.isWithinRateLimit(TEST_EMAIL);

        // Assert
        assertFalse(result);
    }

    @Test
    void testRecordEmailAttempt() {
        // Act
        rateLimitingService.recordEmailAttempt(TEST_EMAIL);

        // Assert
        assertEquals(1, rateLimitingService.getCurrentRequestCount(TEST_EMAIL));
    }

    @Test
    void testGetCurrentRequestCount_NoRequests_ShouldReturnZero() {
        // Act
        int count = rateLimitingService.getCurrentRequestCount(TEST_EMAIL);

        // Assert
        assertEquals(0, count);
    }

    @Test
    void testGetCurrentRequestCount_WithRequests_ShouldReturnCorrectCount() {
        // Arrange
        rateLimitingService.recordEmailAttempt(TEST_EMAIL);
        rateLimitingService.recordEmailAttempt(TEST_EMAIL);

        // Act
        int count = rateLimitingService.getCurrentRequestCount(TEST_EMAIL);

        // Assert
        assertEquals(2, count);
    }

    @Test
    void testResetRateLimit() {
        // Arrange
        rateLimitingService.recordEmailAttempt(TEST_EMAIL);
        assertEquals(1, rateLimitingService.getCurrentRequestCount(TEST_EMAIL));

        // Act
        rateLimitingService.resetRateLimit(TEST_EMAIL);

        // Assert
        assertEquals(0, rateLimitingService.getCurrentRequestCount(TEST_EMAIL));
    }

    @Test
    void testGetRateLimitConfig() {
        // Act
        RateLimitingService.RateLimitConfig config = rateLimitingService.getRateLimitConfig();

        // Assert
        assertEquals(5, config.getMaxRequests());
        assertEquals(60, config.getWindowSeconds());
    }

    @Test
    void testRateLimitingWithDifferentEmails_ShouldBeIndependent() {
        // Arrange
        String email1 = "user1@example.com";
        String email2 = "user2@example.com";

        when(emailAttemptRepository.countByFromEmailAndCreatedAtAfterAndStatusIn(
                anyString(), any(LocalDateTime.class), anyList())).thenReturn(0L);

        // Act
        for (int i = 0; i < 3; i++) {
            rateLimitingService.isWithinRateLimit(email1);
        }
        for (int i = 0; i < 2; i++) {
            rateLimitingService.isWithinRateLimit(email2);
        }

        // Assert
        assertEquals(3, rateLimitingService.getCurrentRequestCount(email1));
        assertEquals(2, rateLimitingService.getCurrentRequestCount(email2));
    }

    @Test
    void testSlidingWindowCleanup() throws InterruptedException {
        // Arrange
        ReflectionTestUtils.setField(rateLimitingService, "windowSeconds", 1); // 1 second window
        when(emailAttemptRepository.countByFromEmailAndCreatedAtAfterAndStatusIn(
                eq(TEST_EMAIL), any(LocalDateTime.class), anyList())).thenReturn(0L);

        // Act
        rateLimitingService.isWithinRateLimit(TEST_EMAIL);
        assertEquals(1, rateLimitingService.getCurrentRequestCount(TEST_EMAIL));

        // Wait for window to expire
        Thread.sleep(1100);

        // Check count again (should trigger cleanup)
        int count = rateLimitingService.getCurrentRequestCount(TEST_EMAIL);

        // Assert
        assertEquals(0, count);
    }

    @Test
    void testIsWithinRateLimit_ChecksCorrectStatuses() {
        // Arrange
        when(emailAttemptRepository.countByFromEmailAndCreatedAtAfterAndStatusIn(
                eq(TEST_EMAIL), any(LocalDateTime.class), anyList())).thenReturn(0L);

        // Act
        rateLimitingService.isWithinRateLimit(TEST_EMAIL);

        // Assert
        verify(emailAttemptRepository).countByFromEmailAndCreatedAtAfterAndStatusIn(
                eq(TEST_EMAIL),
                any(LocalDateTime.class),
                eq(Arrays.asList(EmailStatus.PENDING, EmailStatus.SENDING, EmailStatus.SENT))
        );
    }
}
