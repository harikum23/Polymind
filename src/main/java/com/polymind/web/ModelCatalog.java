package com.polymind.web;

import com.polymind.web.dto.ModelsResponse;

import java.util.List;

/**
 * Supplies the {@code GET /v1/models} listing. Implemented by the routing module's registry in
 * step 3 (concrete models + category aliases with capability metadata). A minimal fallback is
 * provided until then.
 */
public interface ModelCatalog {

    List<ModelsResponse.ModelEntry> list();
}
