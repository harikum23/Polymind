package com.polymind.resilience;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadRegistry;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Programmatic Resilience4j registries protecting inference calls (ARCHITECTURE.md §10):
 * circuit breaker, retry and a semaphore bulkhead. Instances are obtained per engine name so each
 * backend has isolated circuit state.
 */
@Configuration
public class ResilienceConfig {

    @Bean
    public CircuitBreakerRegistry circuitBreakerRegistry() {
        return CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .failureRateThreshold(50)
                .slowCallRateThreshold(80)
                .slowCallDurationThreshold(Duration.ofSeconds(30))
                .waitDurationInOpenState(Duration.ofSeconds(20))
                .permittedNumberOfCallsInHalfOpenState(3)
                .slidingWindowSize(20)
                .minimumNumberOfCalls(10)
                .build());
    }

    @Bean
    public RetryRegistry retryRegistry() {
        return RetryRegistry.of(RetryConfig.custom()
                .maxAttempts(3)
                .waitDuration(Duration.ofMillis(300))
                .build());
    }

    @Bean
    public BulkheadRegistry bulkheadRegistry() {
        return BulkheadRegistry.of(BulkheadConfig.custom()
                .maxConcurrentCalls(256)
                .maxWaitDuration(Duration.ofSeconds(5))
                .build());
    }

    /** Default bulkhead bean (used by the gateway when no engine-specific override exists). */
    @Bean
    public Bulkhead inferenceBulkhead(BulkheadRegistry registry) {
        return registry.bulkhead("inference");
    }
}
