package com.pearlthoughts.email.Service;

import com.pearlthoughts.email.entity.EmailAttempt;
import com.pearlthoughts.email.model.EmailRequest;
import com.pearlthoughts.email.model.EmailResponse;
import com.pearlthoughts.email.model.EmailStatus;
import com.pearlthoughts.email.provider.EmailProvider;
import com.pearlthoughts.email.provider.EmailProviderException;
import com.pearlthoughts.email.repository.EmailAttemptRepository;
import com.pearlthoughts.email.service.CircuitBreakerService;
import com.pearlthoughts.email.service.EmailService;
import com.pearlthoughts.email.service.RateLimitingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmailServiceTest {

    @Mock
    private EmailAttemptRepository emailAttemptRepository;

    @Mock
    private RateLimitingService rateLimitingService;

    @Mock
    private CircuitBreakerService circuitBreakerService;

    @Mock
    private EmailProvider mockProviderA;

    @Mock
    private EmailProvider mockProviderB;

    private EmailService emailService;

    @BeforeEach
    void setUp() {
        when(mockProviderA.getProviderName()).thenReturn("MockProviderA");
        when(mockProviderA.getPriority()).thenReturn(1);
        when(mockProviderA.isHealthy()).thenReturn(true);

        when(mockProviderB.getProviderName()).thenReturn("MockProviderB");
        when(mockProviderB.getPriority()).thenReturn(2);
        when(mockProviderB.isHealthy()).thenReturn(true);

        List<EmailProvider> providers = Arrays.asList(mockProviderA, mockProviderB);

        emailService = new EmailService(providers, emailAttemptRepository,
                rateLimitingService, circuitBreakerService);

        // Set retry configuration
        ReflectionTestUtils.setField(emailService, "maxRetryAttempts", 3);
        ReflectionTestUtils.setField(emailService, "initialDelayMs", 100L);
        ReflectionTestUtils.setField(emailService, "maxDelayMs", 1000L);
        ReflectionTestUtils.setField(emailService, "backoffMultiplier", 2.0);
    }

    @Test
    void testSendEmail_Success() throws EmailProviderException {
        // Arrange
        EmailRequest request = createEmailRequest();
        EmailAttempt savedAttempt = createEmailAttempt();

        when(emailAttemptRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(rateLimitingService.isWithinRateLimit(anyString())).thenReturn(true);
        when(emailAttemptRepository.save(any(EmailAttempt.class))).thenReturn(savedAttempt);
        when(circuitBreakerService.isProviderAvailable("MockProviderA")).thenReturn(true);

        EmailResponse mockResponse = EmailResponse.success("test-id", "MockProviderA");
        when(mockProviderA.sendEmail(any(EmailRequest.class))).thenReturn(mockResponse);

        // Act
        EmailResponse response = emailService.sendEmail(request);

        // Assert
        assertEquals(EmailStatus.SENT, response.getStatus());
        assertEquals("MockProviderA", response.getProviderUsed());
        verify(rateLimitingService).recordEmailAttempt(request.getFrom());
        verify(circuitBreakerService).recordSuccess("MockProviderA");
        verify(emailAttemptRepository, times(2)).save(any(EmailAttempt.class));
    }

    @Test
    void testSendEmail_Duplicate() {
        // Arrange
        EmailRequest request = createEmailRequest();
        EmailAttempt existingAttempt = createEmailAttempt();

        when(emailAttemptRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.of(existingAttempt));

        // Act
        EmailResponse response = emailService.sendEmail(request);

        // Assert
        assertEquals(EmailStatus.DUPLICATE, response.getStatus());
        verify(rateLimitingService, never()).isWithinRateLimit(anyString());
        verify(emailAttemptRepository, never()).save(any(EmailAttempt.class));
    }

    @Test
    void testSendEmail_RateLimited() {
        // Arrange
        EmailRequest request = createEmailRequest();

        when(emailAttemptRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(rateLimitingService.isWithinRateLimit(anyString())).thenReturn(false);

        // Act
        EmailResponse response = emailService.sendEmail(request);

        // Assert
        assertEquals(EmailStatus.RATE_LIMITED, response.getStatus());
        verify(emailAttemptRepository, never()).save(any(EmailAttempt.class));
    }

    @Test
    void testSendEmail_FallbackToSecondProvider() throws EmailProviderException {
        // Arrange
        EmailRequest request = createEmailRequest();
        EmailAttempt savedAttempt = createEmailAttempt();

        when(emailAttemptRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(rateLimitingService.isWithinRateLimit(anyString())).thenReturn(true);
        when(emailAttemptRepository.save(any(EmailAttempt.class))).thenReturn(savedAttempt);
        when(circuitBreakerService.isProviderAvailable("MockProviderA")).thenReturn(true);
        when(circuitBreakerService.isProviderAvailable("MockProviderB")).thenReturn(true);

        // First provider fails
        when(mockProviderA.sendEmail(any(EmailRequest.class)))
                .thenThrow(new EmailProviderException("Provider A failed", "MockProviderA"));

        // Second provider succeeds
        EmailResponse mockResponse = EmailResponse.success("test-id", "MockProviderB");
        when(mockProviderB.sendEmail(any(EmailRequest.class))).thenReturn(mockResponse);

        // Act
        EmailResponse response = emailService.sendEmail(request);

        // Assert
        assertEquals(EmailStatus.SENT, response.getStatus());
        assertEquals("MockProviderB", response.getProviderUsed());
        verify(circuitBreakerService).recordFailure("MockProviderA");
        verify(circuitBreakerService).recordSuccess("MockProviderB");
    }

    @Test
    void testSendEmail_AllProvidersFail() throws EmailProviderException {
        // Arrange
        EmailRequest request = createEmailRequest();
        EmailAttempt savedAttempt = createEmailAttempt();

        when(emailAttemptRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(rateLimitingService.isWithinRateLimit(anyString())).thenReturn(true);
        when(emailAttemptRepository.save(any(EmailAttempt.class))).thenReturn(savedAttempt);
        when(circuitBreakerService.isProviderAvailable(anyString())).thenReturn(true);

        // Both providers fail
        when(mockProviderA.sendEmail(any(EmailRequest.class)))
                .thenThrow(new EmailProviderException("Provider A failed", "MockProviderA"));
        when(mockProviderB.sendEmail(any(EmailRequest.class)))
                .thenThrow(new EmailProviderException("Provider B failed", "MockProviderB"));

        // Act
        EmailResponse response = emailService.sendEmail(request);

        // Assert
        assertEquals(EmailStatus.FAILED, response.getStatus());
        assertEquals("All email providers failed", response.getMessage());
        verify(circuitBreakerService).recordFailure("MockProviderA");
        verify(circuitBreakerService).recordFailure("MockProviderB");
    }

    @Test
    void testSendEmail_RetryLogic() throws EmailProviderException {
        // Arrange
        EmailRequest request = createEmailRequest();
        EmailAttempt savedAttempt = createEmailAttempt();

        when(emailAttemptRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(rateLimitingService.isWithinRateLimit(anyString())).thenReturn(true);
        when(emailAttemptRepository.save(any(EmailAttempt.class))).thenReturn(savedAttempt);
        when(circuitBreakerService.isProviderAvailable("MockProviderA")).thenReturn(true);

        // First two attempts fail, third succeeds
        when(mockProviderA.sendEmail(any(EmailRequest.class)))
                .thenThrow(new EmailProviderException("Temporary failure", "MockProviderA"))
                .thenThrow(new EmailProviderException("Temporary failure", "MockProviderA"))
                .thenReturn(EmailResponse.success("test-id", "MockProviderA"));

        // Act
        EmailResponse response = emailService.sendEmail(request);

        // Assert
        assertEquals(EmailStatus.SENT, response.getStatus());
        assertEquals("MockProviderA", response.getProviderUsed());
        assertEquals(3, response.getAttemptCount());
        verify(mockProviderA, times(3)).sendEmail(any(EmailRequest.class));
    }

    @Test
    void testSendEmail_NonRetryableError() throws EmailProviderException {
        // Arrange
        EmailRequest request = createEmailRequest();
        EmailAttempt savedAttempt = createEmailAttempt();

        when(emailAttemptRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(rateLimitingService.isWithinRateLimit(anyString())).thenReturn(true);
        when(emailAttemptRepository.save(any(EmailAttempt.class))).thenReturn(savedAttempt);
        when(circuitBreakerService.isProviderAvailable("MockProviderA")).thenReturn(true);
        when(circuitBreakerService.isProviderAvailable("MockProviderB")).thenReturn(true);

        // Non-retryable error
        when(mockProviderA.sendEmail(any(EmailRequest.class)))
                .thenThrow(new EmailProviderException("Invalid email format", "MockProviderA", false));

        // Second provider succeeds
        EmailResponse mockResponse = EmailResponse.success("test-id", "MockProviderB");
        when(mockProviderB.sendEmail(any(EmailRequest.class))).thenReturn(mockResponse);

        // Act
        EmailResponse response = emailService.sendEmail(request);

        // Assert
        assertEquals(EmailStatus.SENT, response.getStatus());
        assertEquals("MockProviderB", response.getProviderUsed());
        verify(mockProviderA, times(1)).sendEmail(any(EmailRequest.class)); // Only one attempt
    }

    @Test
    void testSendEmail_CircuitBreakerOpen() throws EmailProviderException {
        // Arrange
        EmailRequest request = createEmailRequest();
        EmailAttempt savedAttempt = createEmailAttempt();

        when(emailAttemptRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
        when(rateLimitingService.isWithinRateLimit(anyString())).thenReturn(true);
        when(emailAttemptRepository.save(any(EmailAttempt.class))).thenReturn(savedAttempt);
        when(circuitBreakerService.isProviderAvailable("MockProviderA")).thenReturn(false);
        when(circuitBreakerService.isProviderAvailable("MockProviderB")).thenReturn(true);

        // Second provider succeeds
        EmailResponse mockResponse = EmailResponse.success("test-id", "MockProviderB");
        when(mockProviderB.sendEmail(any(EmailRequest.class))).thenReturn(mockResponse);

        // Act
        EmailResponse response = emailService.sendEmail(request);

        // Assert
        assertEquals(EmailStatus.SENT, response.getStatus());
        assertEquals("MockProviderB", response.getProviderUsed());
        verify(mockProviderA, never()).sendEmail(any(EmailRequest.class));
        verify(mockProviderB, times(1)).sendEmail(any(EmailRequest.class));
    }

    @Test
    void testGetEmailStatus_Found() {
        // Arrange
        EmailAttempt attempt = createEmailAttempt();
        attempt.setStatus(EmailStatus.SENT);
        attempt.setProviderUsed("MockProviderA");

        when(emailAttemptRepository.findByEmailId("test-id")).thenReturn(Optional.of(attempt));

        // Act
        EmailResponse response = emailService.getEmailStatus("test-id");

        // Assert
        assertEquals(EmailStatus.SENT, response.getStatus());
        assertEquals("MockProviderA", response.getProviderUsed());
        assertEquals("test-id", response.getId());
    }

    @Test
    void testGetEmailStatus_NotFound() {
        // Arrange
        when(emailAttemptRepository.findByEmailId("nonexistent-id")).thenReturn(Optional.empty());

        // Act
        EmailResponse response = emailService.getEmailStatus("nonexistent-id");

        // Assert
        assertEquals(EmailStatus.FAILED, response.getStatus());
        assertTrue(response.getErrors().contains("Email ID not found"));
    }

    @Test
    void testGetEmailAttempts() {
        // Arrange
        List<EmailAttempt> attempts = Arrays.asList(createEmailAttempt(), createEmailAttempt());
        when(emailAttemptRepository.findByStatus(EmailStatus.SENT)).thenReturn(attempts);

        // Act
        List<EmailResponse> responses = emailService.getEmailAttempts(EmailStatus.SENT, 0, 10);

        // Assert
        assertEquals(2, responses.size());
        verify(emailAttemptRepository).findByStatus(EmailStatus.SENT);
    }

    private EmailRequest createEmailRequest() {
        EmailRequest request = new EmailRequest();
        request.setFrom("sender@example.com");
        request.setTo(Arrays.asList("recipient@example.com"));
        request.setSubject("Test Subject");
        request.setBody("Test Body");
        return request;
    }

    private EmailAttempt createEmailAttempt() {
        EmailAttempt attempt = new EmailAttempt();
        attempt.setEmailId("test-id");
        attempt.setFromEmail("sender@example.com");
        attempt.setToEmails("recipient@example.com");
        attempt.setSubject("Test Subject");
        attempt.setBody("Test Body");
        attempt.setStatus(EmailStatus.PENDING);
        return attempt;
    }
}
