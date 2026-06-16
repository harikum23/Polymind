package com.polymind.routing;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRouterTest {

    private ModelRouter router;

    @BeforeEach
    void setUp() {
        ModelRegistry registry = new ModelRegistry(new DefaultResourceLoader(), registryProps());
        registry.reload();
        router = new ModelRouter(registry, new HeuristicTaskClassifier());
    }

    private RegistryProperties registryProps() {
        RegistryProperties p = new RegistryProperties();
        p.setLocation("classpath:models.yaml");
        return p;
    }

    @Test
    void forcedExplicitModelBypassesClassification() {
        RouteDecision d = router.route(new RouteQuery("gemma2-9b", null, List.of("solve x^2=4"), false, false));
        assertThat(d.modelId()).isEqualTo("gemma2-9b");
        assertThat(d.reason()).isEqualTo("forced-explicit-id");
    }

    @Test
    void unknownExplicitModelRejected() {
        assertThatThrownBy(() -> router.route(new RouteQuery("nope-1b", null, List.of("hi"), false, false)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void forcedCategoryPicksBestScoringModel() {
        RouteDecision d = router.route(new RouteQuery("code", null, List.of("just chatting"), false, false));
        assertThat(d.modelId()).isEqualTo("deepseek-coder-6.7b"); // code score 9
    }

    @Test
    void autoClassifiesCodeFromFence() {
        RouteDecision d = router.route(new RouteQuery("auto", null,
                List.of("```java\nclass A {}\n```"), false, false));
        assertThat(d.category()).isEqualTo(TaskCategory.CODE);
        assertThat(d.modelId()).isEqualTo("deepseek-coder-6.7b");
    }

    @Test
    void autoClassifiesMathFromKeyword() {
        RouteDecision d = router.route(new RouteQuery("auto", null,
                List.of("Find the derivative of sin(x)"), false, false));
        assertThat(d.category()).isEqualTo(TaskCategory.MATH);
        assertThat(d.modelId()).isEqualTo("qwen2.5-math-7b"); // math score 9
    }

    @Test
    void autoDefaultsToChat() {
        RouteDecision d = router.route(new RouteQuery("auto", null,
                List.of("How are you today?"), false, false));
        assertThat(d.category()).isEqualTo(TaskCategory.CHAT);
        assertThat(d.modelId()).isEqualTo("gemma2-9b"); // chat score 9
    }

    @Test
    void toolsGuardSelectsOnlyToolCapableModel() {
        RouteDecision d = router.route(new RouteQuery("auto", null,
                List.of("call a tool please"), true, false));
        assertThat(d.modelId()).isEqualTo("qwen2.5-7b"); // only supports_tools=true
    }

    @Test
    void explicitTaskHintWins() {
        RouteDecision d = router.route(new RouteQuery("auto", "math",
                List.of("hello there"), false, false));
        assertThat(d.category()).isEqualTo(TaskCategory.MATH);
    }
}
