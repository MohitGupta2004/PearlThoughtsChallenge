package com.pearlthoughts.email.Controller;

import java.util.Arrays;
import java.util.List;

import com.pearlthoughts.email.controller.EmailController;
import com.pearlthoughts.email.model.EmailRequest;
import com.pearlthoughts.email.model.EmailResponse;
import com.pearlthoughts.email.model.EmailStatus;
import com.pearlthoughts.email.service.CircuitBreakerService;
import com.pearlthoughts.email.service.EmailQueueService;
import com.pearlthoughts.email.service.EmailService;
import com.pearlthoughts.email.service.RateLimitingService;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;

@WebMvcTest(EmailController.class)
class EmailControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Mock
    private EmailService emailService;

    @Mock
    private EmailQueueService emailQueueService;

    @Mock
    private RateLimitingService rateLimitingService;

    @Mock
    private CircuitBreakerService circuitBreakerService;

    @TestConfiguration
    static class MockConfig {
        @Bean
        EmailService emailService(@Mock EmailService mock) { return mock; }
        @Bean
        EmailQueueService emailQueueService(@Mock EmailQueueService mock) { return mock; }
        @Bean
        RateLimitingService rateLimitingService(@Mock RateLimitingService mock) { return mock; }
        @Bean
        CircuitBreakerService circuitBreakerService(@Mock CircuitBreakerService mock) { return mock; }
    }

    @Test
    void testSendEmail_Success() throws Exception {
        // Arrange
        EmailRequest request = createValidEmailRequest();
        EmailResponse response = EmailResponse.success("test-id", "MockProviderA");

        when(emailService.sendEmail(any(EmailRequest.class))).thenReturn(response);

        // Act & Assert
        mockMvc.perform(post("/api/v1/emails/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SENT"))
                .andExpect(jsonPath("$.providerUsed").value("MockProviderA"))
                .andExpect(jsonPath("$.id").value("test-id"));
    }

    @Test
    void testSendEmail_ValidationError() throws Exception {
        // Arrange
        EmailRequest request = new EmailRequest();
        request.setFrom("invalid-email"); // Invalid email format
        request.setTo(Arrays.asList("recipient@example.com"));
        request.setSubject("Test");
        request.setBody("Test body");

        // Act & Assert
        mockMvc.perform(post("/api/v1/emails/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details").isArray());
    }

    @Test
    void testSendEmail_RateLimited() throws Exception {
        // Arrange
        EmailRequest request = createValidEmailRequest();
        EmailResponse response = EmailResponse.rateLimited("test-id");

        when(emailService.sendEmail(any(EmailRequest.class))).thenReturn(response);

        // Act & Assert
        mockMvc.perform(post("/api/v1/emails/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.status").value("RATE_LIMITED"));
    }

    @Test
    void testSendEmail_Duplicate() throws Exception {
        // Arrange
        EmailRequest request = createValidEmailRequest();
        EmailResponse response = EmailResponse.duplicate("test-id", "idempotency-key");

        when(emailService.sendEmail(any(EmailRequest.class))).thenReturn(response);

        // Act & Assert
        mockMvc.perform(post("/api/v1/emails/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value("DUPLICATE"));
    }

    @Test
    void testSendEmail_Failed() throws Exception {
        // Arrange
        EmailRequest request = createValidEmailRequest();
        EmailResponse response = EmailResponse.failure("test-id", "All providers failed",
                Arrays.asList("Error 1", "Error 2"));

        when(emailService.sendEmail(any(EmailRequest.class))).thenReturn(response);

        // Act & Assert
        mockMvc.perform(post("/api/v1/emails/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.status").value("FAILED"));
    }

    @Test
    void testQueueEmail_Success() throws Exception {
        // Arrange
        EmailRequest request = createValidEmailRequest();
        EmailResponse response = EmailResponse.pending("test-id");

        when(emailQueueService.queueEmail(any(EmailRequest.class))).thenReturn(response);

        // Act & Assert
        mockMvc.perform(post("/api/v1/emails/queue")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void testGetEmailStatus_Found() throws Exception {
        // Arrange
        EmailResponse response = EmailResponse.success("test-id", "MockProviderA");
        when(emailService.getEmailStatus("test-id")).thenReturn(response);

        // Act & Assert
        mockMvc.perform(get("/api/v1/emails/test-id/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("test-id"))
                .andExpect(jsonPath("$.status").value("SENT"));
    }

    @Test
    void testGetEmailStatus_NotFound() throws Exception {
        // Arrange
        EmailResponse response = EmailResponse.failure("test-id", "Email not found",
                Arrays.asList("Email ID not found"));
        when(emailService.getEmailStatus("test-id")).thenReturn(response);

        // Act & Assert
        mockMvc.perform(get("/api/v1/emails/test-id/status"))
                .andExpect(status().isNotFound());
    }

    @Test
    void testGetEmailAttempts() throws Exception {
        // Arrange
        List<EmailResponse> responses = Arrays.asList(
                EmailResponse.success("id1", "MockProviderA"),
                EmailResponse.success("id2", "MockProviderB")
        );
        when(emailService.getEmailAttempts(EmailStatus.SENT, 0, 10)).thenReturn(responses);

        // Act & Assert
        mockMvc.perform(get("/api/v1/emails")
                        .param("status", "SENT")
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.emails").isArray())
                .andExpect(jsonPath("$.emails.length()").value(2))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10));
    }

    @Test
    void testGetEmailAttempts_InvalidPageSize() throws Exception {
        // Act & Assert
        mockMvc.perform(get("/api/v1/emails")
                        .param("size", "150")) // Exceeds max size of 100
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Page size cannot exceed 100"));
    }

    @Test
    void testGetQueueStats() throws Exception {
        // Arrange
        EmailQueueService.QueueStats stats = new EmailQueueService.QueueStats(
                10, 100, 5, 2, 100, 3, false
        );
        when(emailQueueService.getQueueStats()).thenReturn(stats);

        // Act & Assert
        mockMvc.perform(get("/api/v1/emails/queue/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentQueueSize").value(10))
                .andExpect(jsonPath("$.maxQueueSize").value(100))
                .andExpect(jsonPath("$.pendingEmails").value(5))
                .andExpect(jsonPath("$.processing").value(false));
    }

    @Test
    void testGetRateLimitInfo() throws Exception {
        // Arrange
        RateLimitingService.RateLimitConfig config = new RateLimitingService.RateLimitConfig(100, 60);
        when(rateLimitingService.getCurrentRequestCount("test@example.com")).thenReturn(25);
        when(rateLimitingService.getRateLimitConfig()).thenReturn(config);
        when(rateLimitingService.isWithinRateLimit("test@example.com")).thenReturn(true);

        // Act & Assert
        mockMvc.perform(get("/api/v1/emails/rate-limit/test@example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromEmail").value("test@example.com"))
                .andExpect(jsonPath("$.currentRequests").value(25))
                .andExpect(jsonPath("$.maxRequests").value(100))
                .andExpect(jsonPath("$.windowSeconds").value(60))
                .andExpect(jsonPath("$.remainingRequests").value(75))
                .andExpect(jsonPath("$.isWithinLimit").value(true));
    }

    @Test
    void testGetCircuitBreakerStatus() throws Exception {
        // Arrange
        when(circuitBreakerService.getCircuitState("MockProviderA"))
                .thenReturn(CircuitBreakerService.CircuitState.CLOSED);
        when(circuitBreakerService.isProviderAvailable("MockProviderA")).thenReturn(true);
        when(circuitBreakerService.getCircuitState("MockProviderB"))
                .thenReturn(CircuitBreakerService.CircuitState.OPEN);
        when(circuitBreakerService.isProviderAvailable("MockProviderB")).thenReturn(false);

        // Act & Assert
        mockMvc.perform(get("/api/v1/emails/circuit-breaker/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.MockProviderA.state").value("CLOSED"))
                .andExpect(jsonPath("$.MockProviderA.available").value(true))
                .andExpect(jsonPath("$.MockProviderB.state").value("OPEN"))
                .andExpect(jsonPath("$.MockProviderB.available").value(false));
    }

    @Test
    void testHealthCheck() throws Exception {
        // Arrange
        EmailQueueService.QueueStats stats = new EmailQueueService.QueueStats(
                10, 100, 5, 2, 100, 3, false
        );
        when(emailQueueService.getQueueStats()).thenReturn(stats);

        // Act & Assert
        mockMvc.perform(get("/api/v1/emails/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.queue.size").value(10))
                .andExpect(jsonPath("$.queue.processing").value(false))
                .andExpect(jsonPath("$.emails.pending").value(5))
                .andExpect(jsonPath("$.emails.sent").value(100));
    }

    @Test
    void testSendEmail_MissingRequiredFields() throws Exception {
        // Arrange
        EmailRequest request = new EmailRequest();
        // Missing required fields

        // Act & Assert
        mockMvc.perform(post("/api/v1/emails/send")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Validation failed"))
                .andExpect(jsonPath("$.details").isArray());
    }

    private EmailRequest createValidEmailRequest() {
        EmailRequest request = new EmailRequest();
        request.setFrom("sender@example.com");
        request.setTo(Arrays.asList("recipient@example.com"));
        request.setSubject("Test Subject");
        request.setBody("Test Body");
        return request;
    }
}
