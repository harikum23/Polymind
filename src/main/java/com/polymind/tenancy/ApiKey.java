package com.polymind.tenancy;

import java.time.Instant;
import java.util.Set;

/**
 * An API key principal. {@code allowedModels} empty = all models allowed; otherwise the key may
 * only use the listed concrete model ids / category aliases. {@code admin} unlocks {@code /v1/admin/*}.
 */
public record ApiKey(
        String id,
        String secret,
        String owner,
        boolean admin,
        boolean enabled,
        Set<String> allowedModels,
        Instant createdAt) {

    public boolean allowsModel(String model) {
        return allowedModels == null || allowedModels.isEmpty() || allowedModels.contains(model);
    }
}
