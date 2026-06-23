package com.polymind.tenancy;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process API-key store (ARCHITECTURE.md §9 retained in-process state). Seeded with a bootstrap
 * admin key from config. JPA persistence is a documented refinement (docs/future-pending.md).
 *
 * <p>Published service: governance reads keys for quota; the auth filter authenticates by secret.
 */
@Service
@EnableConfigurationProperties(TenancyProperties.class)
public class ApiKeyService {

    private final TenancyProperties props;
    private final ConcurrentHashMap<String, ApiKey> bySecret = new ConcurrentHashMap<>();

    public ApiKeyService(TenancyProperties props) {
        this.props = props;
    }

    @PostConstruct
    void seed() {
        ApiKey admin = new ApiKey(UUID.randomUUID().toString(), props.getAdminKey(), "bootstrap-admin",
                true, true, Set.of(), Instant.now());
        bySecret.put(admin.secret(), admin);

        // Static service keys re-seeded from config on every startup, so external integrations
        // (e.g. TradeEngine) keep a stable key across restarts of the in-process store.
        for (TenancyProperties.StaticKey sk : props.getStaticKeys()) {
            String secret = sk.getSecret();
            if (secret == null || secret.isBlank()) {
                continue;
            }
            ApiKey key = new ApiKey(UUID.randomUUID().toString(), secret.trim(), sk.getOwner(),
                    sk.isAdmin(), true,
                    sk.getAllowedModels() == null ? Set.of() : Set.copyOf(sk.getAllowedModels()),
                    Instant.now());
            bySecret.put(key.secret(), key);
        }
    }

    public Optional<ApiKey> authenticate(String secret) {
        return Optional.ofNullable(secret)
                .map(bySecret::get)
                .filter(ApiKey::enabled);
    }

    public ApiKey create(String owner, boolean admin, Set<String> allowedModels) {
        String secret = "pmk-" + UUID.randomUUID().toString().replace("-", "");
        ApiKey key = new ApiKey(UUID.randomUUID().toString(), secret, owner, admin, true,
                allowedModels == null ? Set.of() : Set.copyOf(allowedModels), Instant.now());
        bySecret.put(secret, key);
        return key;
    }

    public boolean revoke(String secret) {
        ApiKey existing = bySecret.get(secret);
        if (existing == null) {
            return false;
        }
        bySecret.put(secret, new ApiKey(existing.id(), existing.secret(), existing.owner(),
                existing.admin(), false, existing.allowedModels(), existing.createdAt()));
        return true;
    }

    public List<ApiKey> list() {
        return List.copyOf(bySecret.values());
    }

    public boolean isAuthEnabled() {
        return props.isAuthEnabled();
    }
}
