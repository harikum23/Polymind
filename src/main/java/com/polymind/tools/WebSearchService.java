package com.polymind.tools;

import io.github.bucket4j.Bucket;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Web-search facade (ARCHITECTURE.md §8.2): selects the configured provider, caches results in
 * process with TTL, and enforces a per-key daily quota (Bucket4j-local; Redis is the multi-replica
 * option noted in docs/future-pending.md). The single seam used by all three access patterns
 * (per-request flag, agent tool-call, direct endpoint).
 */
@Service
@EnableConfigurationProperties(SearchProperties.class)
public class WebSearchService {

    private static final Logger log = LoggerFactory.getLogger(WebSearchService.class);

    private final SearchProperties props;
    private final Map<String, WebSearchProvider> providers;
    private final ConcurrentHashMap<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> quotas = new ConcurrentHashMap<>();
    private WebSearchProvider active;

    public WebSearchService(SearchProperties props, List<WebSearchProvider> providerBeans) {
        this.props = props;
        this.providers = providerBeans.stream()
                .collect(Collectors.toMap(WebSearchProvider::name, Function.identity()));
    }

    @PostConstruct
    void selectProvider() {
        active = providers.get(props.getProvider());
        if (active == null) {
            throw new IllegalStateException("Unknown search provider '" + props.getProvider()
                    + "'. Available: " + providers.keySet());
        }
        log.info("Web search provider: {}", active.name());
    }

    /** Search on behalf of an API key (for quota); use {@link #search(String, Integer)} for system calls. */
    public SearchResult searchForKey(String keyId, String query, Integer days) {
        if (!consumeQuota(keyId)) {
            throw new WebSearchException("Daily web-search quota exceeded for key");
        }
        return search(query, days);
    }

    public SearchResult search(String query, Integer days) {
        String cacheKey = query + "|" + (days == null ? "" : days);
        CacheEntry cached = cache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && cached.expiresAt > now) {
            SearchResult r = cached.result;
            return new SearchResult(r.text(), r.sources(), true);
        }
        SearchResult result = active.search(query, days);
        cache.put(cacheKey, new CacheEntry(result, now + props.getCacheTtlSeconds() * 1000));
        return result;
    }

    private boolean consumeQuota(String keyId) {
        Bucket bucket = quotas.computeIfAbsent(keyId, k -> Bucket.builder()
                .addLimit(limit -> limit.capacity(props.getDailyQuotaPerKey())
                        .refillGreedy(props.getDailyQuotaPerKey(), Duration.ofDays(1)))
                .build());
        return bucket.tryConsume(1);
    }

    private record CacheEntry(SearchResult result, long expiresAt) {}
}
