package com.polymind.admission;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Bounded concurrency control with backpressure (ARCHITECTURE.md §9, step 4). A fair semaphore caps
 * simultaneous in-flight inference tasks; a separate counter bounds the waiting queue so excess load
 * is rejected fast (HTTP 429) rather than piling up unbounded. Each admitted task runs on the
 * caller's virtual thread — Loom makes thousands of concurrent waiters cheap.
 *
 * <p>Priority: callers pass a {@link Priority}; higher priority requests get a longer acquire budget
 * (a pragmatic, lock-light approximation of a priority queue suitable for a single instance).
 */
@Service
@EnableConfigurationProperties(AdmissionProperties.class)
public class AdmissionControl {

    private static final Logger log = LoggerFactory.getLogger(AdmissionControl.class);

    private final AdmissionProperties props;
    private final Semaphore permits;
    private final AtomicInteger waiting = new AtomicInteger();
    private final AtomicInteger inFlight = new AtomicInteger();

    public AdmissionControl(AdmissionProperties props) {
        this.props = props;
        this.permits = new Semaphore(props.getMaxConcurrency(), true);
    }

    public enum Priority {
        HIGH, NORMAL, LOW
    }

    /** Run {@code task} under a concurrency permit, or throw {@link BackpressureException} if saturated. */
    public <T> T submit(Priority priority, Supplier<T> task) {
        if (!props.isEnabled()) {
            return task.get();
        }
        if (waiting.get() >= props.getQueueCapacity()) {
            throw new BackpressureException("Admission queue full (" + props.getQueueCapacity() + ")");
        }
        long budget = acquireBudgetMs(priority);
        waiting.incrementAndGet();
        boolean acquired = false;
        try {
            acquired = permits.tryAcquire(budget, TimeUnit.MILLISECONDS);
            if (!acquired) {
                throw new BackpressureException("Timed out acquiring admission permit after " + budget + "ms");
            }
            waiting.decrementAndGet();
            inFlight.incrementAndGet();
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackpressureException("Interrupted while waiting for admission");
        } finally {
            if (acquired) {
                inFlight.decrementAndGet();
                permits.release();
            } else {
                waiting.decrementAndGet();
            }
        }
    }

    public void runStreaming(Priority priority, Runnable task) {
        submit(priority, () -> {
            task.run();
            return null;
        });
    }

    private long acquireBudgetMs(Priority priority) {
        long base = props.getAcquireTimeoutMs();
        return switch (priority) {
            case HIGH -> base * 2;
            case NORMAL -> base;
            case LOW -> base / 2;
        };
    }

    public int inFlight() {
        return inFlight.get();
    }

    public int waiting() {
        return waiting.get();
    }

    public int availablePermits() {
        return permits.availablePermits();
    }

    @PreDestroy
    void shutdown() {
        log.info("AdmissionControl shutting down: inFlight={}, waiting={}", inFlight.get(), waiting.get());
    }
}
