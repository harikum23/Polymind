package com.polymind.tenancy;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "polymind.tenancy")
public class TenancyProperties {

    /** Whether API-key authentication is enforced. When false (dev), requests pass through. */
    private boolean authEnabled = true;

    /** Bootstrap admin key secret. Override via POLYMIND_ADMIN_KEY env in production. */
    private String adminKey = "pmk-admin-dev";

    public boolean isAuthEnabled() {
        return authEnabled;
    }

    public void setAuthEnabled(boolean authEnabled) {
        this.authEnabled = authEnabled;
    }

    public String getAdminKey() {
        return adminKey;
    }

    public void setAdminKey(String adminKey) {
        this.adminKey = adminKey;
    }
}
