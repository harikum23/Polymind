package com.polymind.governance;

import com.polymind.tenancy.ApiKey;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Enforces per-key rate limit + daily quota on generation endpoints. Runs after authentication so
 * the key id is available; falls back to a per-IP key when unauthenticated (dev mode). Registered
 * via {@link GovernanceConfig} with an order after the Spring Security filter chain.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private final RateLimitService rateLimit;

    public RateLimitFilter(RateLimitService rateLimit) {
        this.rateLimit = rateLimit;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String keyId = resolveKeyId(request);
        RateLimitService.Decision decision = rateLimit.tryConsume(keyId);
        if (!decision.allowed()) {
            response.setStatus(429);
            response.setContentType("application/json");
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            response.getWriter().write("{\"error\":{\"type\":\"" + decision.reason()
                    + "\",\"message\":\"Too many requests\"}}");
            return;
        }
        response.setHeader("X-RateLimit-Remaining", String.valueOf(decision.remaining()));
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!rateLimit.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        return !(path.equals("/v1/chat/completions")
                || path.equals("/v1/embeddings")
                || path.equals("/v1/tools/web_search"));
    }

    private String resolveKeyId(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof ApiKey key) {
            return key.id();
        }
        return "ip:" + request.getRemoteAddr();
    }
}
