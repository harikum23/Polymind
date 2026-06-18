package com.polymind.inference;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "polymind.inference.ollama")
public class OllamaProperties {

    private String baseUrl = "http://localhost:11434";
    private long connectTimeoutMs = 5000;
    private long requestTimeoutMs = 600_000;
    /**
     * Ollama context window (num_ctx) sent on every chat request. Kept explicit so Polymind
     * and any co-located gateway share the same loaded model runner instead of forcing Ollama
     * to evict/reload the model when context sizes differ. Set &le; 0 to omit and use Ollama's default.
     */
    private int numCtx = 8192;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public long getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(long requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public int getNumCtx() {
        return numCtx;
    }

    public void setNumCtx(int numCtx) {
        this.numCtx = numCtx;
    }
}
