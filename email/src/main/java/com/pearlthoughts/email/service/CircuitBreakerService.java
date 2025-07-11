package com.pearlthoughts.email.service;

import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class CircuitBreakerService {

    private static final Logger logger = LoggerFactory.getLogger(CircuitBreakerService.class);

    @Value("${email.service.circuit-breaker.failure-threshold:5}")
    private int failureThreshold;

    @Value("${email.service.circuit-breaker.timeout-seconds:30}")
    private int timeoutSeconds;

    @Value("${email.service.circuit-breaker.recovery-timeout-seconds:60}")
    private int recoveryTimeoutSeconds;

    private final ConcurrentMap<String, CircuitBreakerState> circuitStates = new ConcurrentHashMap<>();

    /**
     * Check if a provider is available (circuit is closed or half-open)
     */
    public boolean isProviderAvailable(String providerName) {
        CircuitBreakerState state = circuitStates.computeIfAbsent(providerName, k -> new CircuitBreakerState());

        synchronized (state) {
            switch (state.getState()) {
                case CLOSED:
                    return true;
                case OPEN:
                    if (isRecoveryTimeElapsed(state)) {
                        state.setState(CircuitState.HALF_OPEN);
                        logger.info("Circuit breaker for {} moved to HALF_OPEN state", providerName);
                        return true;
                    }
                    return false;
                case HALF_OPEN:
                    return true;
                default:
                    return false;
            }
        }
    }

    /**
     * Record a successful call
     */
    public void recordSuccess(String providerName) {
        CircuitBreakerState state = circuitStates.get(providerName);
        if (state != null) {
            synchronized (state) {
                state.reset();
                if (state.getState() == CircuitState.HALF_OPEN) {
                    state.setState(CircuitState.CLOSED);
                    logger.info("Circuit breaker for {} moved to CLOSED state after successful call", providerName);
                }
            }
        }
    }

    /**
     * Record a failed call
     */
    public void recordFailure(String providerName) {
        CircuitBreakerState state = circuitStates.computeIfAbsent(providerName, k -> new CircuitBreakerState());

        synchronized (state) {
            state.incrementFailureCount();

            if (state.getFailureCount() >= failureThreshold) {
                state.setState(CircuitState.OPEN);
                state.setLastFailureTime(LocalDateTime.now());
                logger.warn("Circuit breaker for {} moved to OPEN state after {} failures",
                        providerName, state.getFailureCount());
            }
        }
    }

    /**
     * Get circuit breaker state for a provider
     */
    public CircuitState getCircuitState(String providerName) {
        CircuitBreakerState state = circuitStates.get(providerName);
        return state != null ? state.getState() : CircuitState.CLOSED;
    }

    /**
     * Reset circuit breaker for a provider (for testing)
     */
    public void resetCircuitBreaker(String providerName) {
        CircuitBreakerState state = circuitStates.get(providerName);
        if (state != null) {
            synchronized (state) {
                state.reset();
                state.setState(CircuitState.CLOSED);
                logger.info("Circuit breaker for {} has been reset", providerName);
            }
        }
    }

    private boolean isRecoveryTimeElapsed(CircuitBreakerState state) {
        return state.getLastFailureTime() != null &&
                ChronoUnit.SECONDS.between(state.getLastFailureTime(), LocalDateTime.now()) >= recoveryTimeoutSeconds;
    }

    /**
     * Circuit breaker state enumeration
     */
    public enum CircuitState {
        CLOSED,    // Normal operation
        OPEN,      // Failing fast
        HALF_OPEN  // Testing if service has recovered
    }

    /**
     * Internal state holder for circuit breaker
     */
    @Getter
    private static class CircuitBreakerState {
        private CircuitState state = CircuitState.CLOSED;
        private int failureCount = 0;
        private LocalDateTime lastFailureTime;

        public void setState(CircuitState state) {
            this.state = state;
        }

        public void incrementFailureCount() {
            this.failureCount++;
        }

        public void setLastFailureTime(LocalDateTime lastFailureTime) {
            this.lastFailureTime = lastFailureTime;
        }

        public void reset() {
            this.failureCount = 0;
            this.lastFailureTime = null;
        }
    }
}
