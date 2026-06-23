package com.polymind.tenancy;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Static service keys must be re-seeded from config on every startup so they survive restarts. */
class StaticKeySeedingTest {

    private ApiKeyService freshService(TenancyProperties props) {
        ApiKeyService svc = new ApiKeyService(props);
        svc.seed();
        return svc;
    }

    @Test
    void staticKeyAuthenticatesAfterReseed() {
        TenancyProperties props = new TenancyProperties();
        TenancyProperties.StaticKey sk = new TenancyProperties.StaticKey();
        sk.setSecret("pmk-service-fixed");
        sk.setOwner("external-services");
        props.setStaticKeys(java.util.List.of(sk));

        // Simulate two boots of the in-process store: the key must resolve both times.
        for (int boot = 0; boot < 2; boot++) {
            ApiKeyService svc = freshService(props);
            assertThat(svc.authenticate("pmk-service-fixed"))
                    .as("static key resolves on boot %d", boot)
                    .isPresent()
                    .get()
                    .satisfies(k -> {
                        assertThat(k.owner()).isEqualTo("external-services");
                        assertThat(k.admin()).isFalse();
                        assertThat(k.enabled()).isTrue();
                    });
        }
    }

    @Test
    void blankStaticSecretIsIgnored() {
        TenancyProperties props = new TenancyProperties();
        TenancyProperties.StaticKey blank = new TenancyProperties.StaticKey();
        blank.setSecret("   ");
        props.setStaticKeys(java.util.List.of(blank));

        ApiKeyService svc = freshService(props);
        assertThat(svc.authenticate("   ")).isEmpty();
        // Only the bootstrap admin key remains.
        assertThat(svc.list()).hasSize(1);
    }

    @Test
    void staticKeyHonoursAllowedModels() {
        TenancyProperties props = new TenancyProperties();
        TenancyProperties.StaticKey sk = new TenancyProperties.StaticKey();
        sk.setSecret("pmk-scoped");
        sk.setAllowedModels(Set.of("gemma3:4b"));
        props.setStaticKeys(java.util.List.of(sk));

        ApiKey key = freshService(props).authenticate("pmk-scoped").orElseThrow();
        assertThat(key.allowsModel("gemma3:4b")).isTrue();
        assertThat(key.allowsModel("llama3:70b")).isFalse();
    }
}
