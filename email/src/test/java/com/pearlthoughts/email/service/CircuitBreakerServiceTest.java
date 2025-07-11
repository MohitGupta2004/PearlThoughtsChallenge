package com.pearlthoughts.email.Service;

import com.pearlthoughts.email.service.CircuitBreakerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;

class CircuitBreakerServiceTest {

    private CircuitBreakerService circuitBreakerService;
    private static final String PROVIDER_NAME = "TestProvider";

    @BeforeEach
    void setUp() {
        circuitBreakerService = new CircuitBreakerService();
        ReflectionTestUtils.setField(circuitBreakerService, "failureThreshold", 3);
        ReflectionTestUtils.setField(circuitBreakerService, "timeoutSeconds", 30);
        ReflectionTestUtils.setField(circuitBreakerService, "recoveryTimeoutSeconds", 60);
    }

    @Test
    void testInitialState_ShouldBeClosed() {
        assertTrue(circuitBreakerService.isProviderAvailable(PROVIDER_NAME));
        assertEquals(CircuitBreakerService.CircuitState.CLOSED,
                circuitBreakerService.getCircuitState(PROVIDER_NAME));
    }

    @Test
    void testRecordSuccess_ShouldKeepCircuitClosed() {
        circuitBreakerService.recordSuccess(PROVIDER_NAME);
        assertTrue(circuitBreakerService.isProviderAvailable(PROVIDER_NAME));
        assertEquals(CircuitBreakerService.CircuitState.CLOSED,
                circuitBreakerService.getCircuitState(PROVIDER_NAME));
    }

    @Test
    void testRecordFailure_ShouldOpenCircuitAfterThreshold() {
        // Record failures up to threshold
        circuitBreakerService.recordFailure(PROVIDER_NAME);
        circuitBreakerService.recordFailure(PROVIDER_NAME);
        assertTrue(circuitBreakerService.isProviderAvailable(PROVIDER_NAME));
        assertEquals(CircuitBreakerService.CircuitState.CLOSED,
                circuitBreakerService.getCircuitState(PROVIDER_NAME));

        // One more failure should open the circuit
        circuitBreakerService.recordFailure(PROVIDER_NAME);
        assertFalse(circuitBreakerService.isProviderAvailable(PROVIDER_NAME));
        assertEquals(CircuitBreakerService.CircuitState.OPEN,
                circuitBreakerService.getCircuitState(PROVIDER_NAME));
    }

    @Test
    void testCircuitBreaker_ShouldTransitionToHalfOpenAfterRecoveryTimeout() throws InterruptedException {
        // Open the circuit
        for (int i = 0; i < 3; i++) {
            circuitBreakerService.recordFailure(PROVIDER_NAME);
        }
        assertFalse(circuitBreakerService.isProviderAvailable(PROVIDER_NAME));

        // Wait for recovery timeout (simulate by setting a shorter timeout)
        ReflectionTestUtils.setField(circuitBreakerService, "recoveryTimeoutSeconds", 0);

        // Should transition to half-open
        assertTrue(circuitBreakerService.isProviderAvailable(PROVIDER_NAME));
        assertEquals(CircuitBreakerService.CircuitState.HALF_OPEN,
                circuitBreakerService.getCircuitState(PROVIDER_NAME));
    }

    @Test
    void testHalfOpenToClosedTransition() {
        // Open the circuit
        for (int i = 0; i < 3; i++) {
            circuitBreakerService.recordFailure(PROVIDER_NAME);
        }

        // Force half-open state
        ReflectionTestUtils.setField(circuitBreakerService, "recoveryTimeoutSeconds", 0);
        circuitBreakerService.isProviderAvailable(PROVIDER_NAME);

        // Record success should close the circuit
        circuitBreakerService.recordSuccess(PROVIDER_NAME);
        assertTrue(circuitBreakerService.isProviderAvailable(PROVIDER_NAME));
        assertEquals(CircuitBreakerService.CircuitState.CLOSED,
                circuitBreakerService.getCircuitState(PROVIDER_NAME));
    }

    @Test
    void testResetCircuitBreaker() {
        // Open the circuit
        for (int i = 0; i < 3; i++) {
            circuitBreakerService.recordFailure(PROVIDER_NAME);
        }
        assertFalse(circuitBreakerService.isProviderAvailable(PROVIDER_NAME));

        // Reset should close the circuit
        circuitBreakerService.resetCircuitBreaker(PROVIDER_NAME);
        assertTrue(circuitBreakerService.isProviderAvailable(PROVIDER_NAME));
        assertEquals(CircuitBreakerService.CircuitState.CLOSED,
                circuitBreakerService.getCircuitState(PROVIDER_NAME));
    }

    @Test
    void testMultipleProviders_ShouldBeIndependent() {
        String provider1 = "Provider1";
        String provider2 = "Provider2";

        // Open circuit for provider1
        for (int i = 0; i < 3; i++) {
            circuitBreakerService.recordFailure(provider1);
        }

        // Provider1 should be unavailable, Provider2 should be available
        assertFalse(circuitBreakerService.isProviderAvailable(provider1));
        assertTrue(circuitBreakerService.isProviderAvailable(provider2));

        assertEquals(CircuitBreakerService.CircuitState.OPEN,
                circuitBreakerService.getCircuitState(provider1));
        assertEquals(CircuitBreakerService.CircuitState.CLOSED,
                circuitBreakerService.getCircuitState(provider2));
    }
}
