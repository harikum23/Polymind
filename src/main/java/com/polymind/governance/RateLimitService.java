package com.polymind.governance;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-key rate limiting and daily quota using Bucket4j in-process buckets (ARCHITECTURE.md §11:
 * Caffeine/Bucket4j-local; Redis only for multi-replica). Each key gets a sustained-rate bucket
 * (with burst) and a separate daily-quota bucket.
 */
@Service
@EnableConfigurationProperties(GovernanceProperties.class)
public class RateLimitService {

    private final GovernanceProperties props;
    private final ConcurrentHashMap<String, Bucket> rateBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> quotaBuckets = new ConcurrentHashMap<>();

    public RateLimitService(GovernanceProperties props) {
        this.props = props;
    }

    public boolean isEnabled() {
        return props.isEnabled();
    }

    /** Try to admit one request for the given key id. */
    public Decision tryConsume(String keyId) {
        if (!props.isEnabled()) {
            return Decision.allowed(Long.MAX_VALUE);
        }
        ConsumptionProbe quota = quotaBucket(keyId).tryConsumeAndReturnRemaining(1);
        if (!quota.isConsumed()) {
            return Decision.denied("daily_quota_exceeded",
                    Duration.ofNanos(quota.getNanosToWaitForRefill()).toSeconds());
        }
        ConsumptionProbe rate = rateBucket(keyId).tryConsumeAndReturnRemaining(1);
        if (!rate.isConsumed()) {
            return Decision.denied("rate_limit_exceeded",
                    Duration.ofNanos(rate.getNanosToWaitForRefill()).toSeconds());
        }
        return Decision.allowed(rate.getRemainingTokens());
    }

    private Bucket rateBucket(String keyId) {
        return rateBuckets.computeIfAbsent(keyId, k -> Bucket.builder()
                .addLimit(limit -> limit.capacity(props.getBurst())
                        .refillGreedy(props.getRequestsPerMinute(), Duration.ofMinutes(1)))
                .build());
    }

    private Bucket quotaBucket(String keyId) {
        return quotaBuckets.computeIfAbsent(keyId, k -> Bucket.builder()
                .addLimit(limit -> limit.capacity(props.getDailyQuota())
                        .refillGreedy(props.getDailyQuota(), Duration.ofDays(1)))
                .build());
    }

    public record Decision(boolean allowed, String reason, long retryAfterSeconds, long remaining) {
        static Decision allowed(long remaining) {
            return new Decision(true, null, 0, remaining);
        }

        static Decision denied(String reason, long retryAfter) {
            return new Decision(false, reason, retryAfter, 0);
        }
    }
}
