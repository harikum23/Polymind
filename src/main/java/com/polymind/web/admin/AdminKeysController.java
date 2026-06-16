package com.polymind.web.admin;

import com.polymind.tenancy.ApiKey;
import com.polymind.tenancy.ApiKeyService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Admin key management ({@code /v1/admin/keys}). Requires an admin API key. */
@RestController
@RequestMapping("/v1/admin/keys")
@Tag(name = "Admin: Keys", description = "API key management (admin only)")
public class AdminKeysController {

    private final ApiKeyService keys;

    public AdminKeysController(ApiKeyService keys) {
        this.keys = keys;
    }

    @GetMapping
    @Operation(summary = "List API keys (secrets are masked)")
    public List<Map<String, Object>> list() {
        return keys.list().stream().map(this::mask).toList();
    }

    @PostMapping
    @Operation(summary = "Create a new API key (full secret returned once)")
    public Map<String, Object> create(@RequestBody CreateKeyRequest req) {
        ApiKey key = keys.create(req.owner(), req.admin(),
                req.allowedModels() == null ? Set.of() : req.allowedModels());
        return Map.of(
                "id", key.id(),
                "secret", key.secret(),
                "owner", key.owner(),
                "admin", key.admin(),
                "allowedModels", key.allowedModels());
    }

    @DeleteMapping("/{secret}")
    @Operation(summary = "Revoke (disable) an API key")
    public Map<String, Object> revoke(@PathVariable String secret) {
        return Map.of("revoked", keys.revoke(secret));
    }

    private Map<String, Object> mask(ApiKey key) {
        String s = key.secret();
        String masked = s.length() <= 8 ? "****" : s.substring(0, 8) + "****";
        return Map.of(
                "id", key.id(),
                "secret", masked,
                "owner", key.owner(),
                "admin", key.admin(),
                "enabled", key.enabled(),
                "allowedModels", key.allowedModels());
    }

    public record CreateKeyRequest(String owner, boolean admin, Set<String> allowedModels) {}
}
