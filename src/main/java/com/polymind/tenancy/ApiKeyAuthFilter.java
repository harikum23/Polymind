package com.polymind.tenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Authenticates requests by {@code Authorization: Bearer <api-key>}. On a valid key it populates the
 * Spring Security context with an {@link ApiKeyAuthentication}; invalid keys are left unauthenticated
 * and rejected downstream by the authorization rules. When auth is disabled (dev) it is a no-op.
 */
@Component
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    private final ApiKeyService keys;

    public ApiKeyAuthFilter(ApiKeyService keys) {
        this.keys = keys;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        extractKey(request)
                .flatMap(keys::authenticate)
                .ifPresent(key -> SecurityContextHolder.getContext()
                        .setAuthentication(new ApiKeyAuthentication(key)));
        chain.doFilter(request, response);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !keys.isAuthEnabled();
    }

    private Optional<String> extractKey(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.of(header.substring(7).trim());
        }
        String apiKeyHeader = request.getHeader("X-Api-Key");
        return Optional.ofNullable(apiKeyHeader);
    }
}
