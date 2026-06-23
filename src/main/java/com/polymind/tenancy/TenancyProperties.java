package com.polymind.tenancy;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@ConfigurationProperties(prefix = "polymind.tenancy")
public class TenancyProperties {

    /** Whether API-key authentication is enforced. When false (dev), requests pass through. */
    private boolean authEnabled = true;

    /** Bootstrap admin key secret. Override via POLYMIND_ADMIN_KEY env in production. */
    private String adminKey = "pmk-admin-dev";

    /**
     * Statically configured, non-admin API keys for external services (e.g. TradeEngine). Unlike
     * keys minted via {@code /v1/admin/keys}, these are re-seeded from config on every startup, so
     * they survive restarts of the in-process key store. Configure via env, e.g.
     * {@code POLYMIND_TENANCY_STATIC_KEYS_0_SECRET}. Entries with a blank secret are ignored.
     */
    private List<StaticKey> staticKeys = new ArrayList<>();

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

    public List<StaticKey> getStaticKeys() {
        return staticKeys;
    }

    public void setStaticKeys(List<StaticKey> staticKeys) {
        this.staticKeys = staticKeys;
    }

    /** A pre-shared key seeded from configuration. */
    public static class StaticKey {
        private String secret;
        private String owner = "static";
        private boolean admin = false;
        private Set<String> allowedModels = Set.of();

        public String getSecret() {
            return secret;
        }

        public void setSecret(String secret) {
            this.secret = secret;
        }

        public String getOwner() {
            return owner;
        }

        public void setOwner(String owner) {
            this.owner = owner;
        }

        public boolean isAdmin() {
            return admin;
        }

        public void setAdmin(boolean admin) {
            this.admin = admin;
        }

        public Set<String> getAllowedModels() {
            return allowedModels;
        }

        public void setAllowedModels(Set<String> allowedModels) {
            this.allowedModels = allowedModels;
        }
    }
}
