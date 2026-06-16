package com.polymind.tenancy;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/** Authenticated principal carrying the resolved {@link ApiKey}. */
public class ApiKeyAuthentication extends AbstractAuthenticationToken {

    private final ApiKey apiKey;

    public ApiKeyAuthentication(ApiKey apiKey) {
        super(apiKey.admin()
                ? List.of(new SimpleGrantedAuthority("ROLE_USER"), new SimpleGrantedAuthority("ROLE_ADMIN"))
                : List.of(new SimpleGrantedAuthority("ROLE_USER")));
        this.apiKey = apiKey;
        setAuthenticated(true);
    }

    public ApiKey apiKey() {
        return apiKey;
    }

    @Override
    public Object getCredentials() {
        return apiKey.secret();
    }

    @Override
    public Object getPrincipal() {
        return apiKey;
    }

    @Override
    public String getName() {
        return apiKey.owner();
    }
}
