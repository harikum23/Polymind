package com.polymind;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Verifies the Spring Modulith boundaries: no module reaches into another module's internals;
 * cross-module access goes through published interfaces / named interfaces / application events.
 * This test must stay green after every build step.
 */
class ModularityTests {

    static final ApplicationModules MODULES = ApplicationModules.of(PolymindApplication.class);

    @Test
    void verifiesModularStructure() {
        MODULES.verify();
    }

    @Test
    void writesDocumentationSnippets() {
        new Documenter(MODULES)
                .writeModulesAsPlantUml()
                .writeIndividualModulesAsPlantUml();
    }
}
