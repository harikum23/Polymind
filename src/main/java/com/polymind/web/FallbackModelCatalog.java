package com.polymind.web;

import com.polymind.web.dto.ModelsResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** Minimal catalog used until the routing registry (step 3) supplies the real one. */
@Configuration
public class FallbackModelCatalog {

    @Bean
    @ConditionalOnMissingBean(ModelCatalog.class)
    public ModelCatalog defaultModelCatalog() {
        return () -> List.of(new ModelsResponse.ModelEntry(
                "auto", "model", "polymind:alias", "polymind", null));
    }
}
