package com.polymind.tools;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "polymind.search")
public class SearchProperties {

    /** Active provider name (e.g. "searxng"). */
    private String provider = "searxng";
    private final Searxng searxng = new Searxng();
    private long cacheTtlSeconds = 3600;
    private int dailyQuotaPerKey = 200;
    private int maxResults = 5;

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public Searxng getSearxng() {
        return searxng;
    }

    public long getCacheTtlSeconds() {
        return cacheTtlSeconds;
    }

    public void setCacheTtlSeconds(long cacheTtlSeconds) {
        this.cacheTtlSeconds = cacheTtlSeconds;
    }

    public int getDailyQuotaPerKey() {
        return dailyQuotaPerKey;
    }

    public void setDailyQuotaPerKey(int dailyQuotaPerKey) {
        this.dailyQuotaPerKey = dailyQuotaPerKey;
    }

    public int getMaxResults() {
        return maxResults;
    }

    public void setMaxResults(int maxResults) {
        this.maxResults = maxResults;
    }

    public static class Searxng {
        private String baseUrl = "http://localhost:8888";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }
}
