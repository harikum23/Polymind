package com.polymind.routing;

import com.polymind.inference.ChatRequest;
import com.polymind.inference.ChatResult;
import com.polymind.inference.EmbeddingRequest;
import com.polymind.inference.EmbeddingResult;
import com.polymind.inference.Engine;
import com.polymind.inference.EngineRegistry;
import com.polymind.inference.ChatChunk;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelRouterTest {

    private ModelRouter router;

    @BeforeEach
    void setUp() {
        // Stub engine reports empty availability => "unknown, don't filter", so these tests
        // exercise pure capability scoring. Availability filtering is covered separately below.
        router = routerWith(Set.of());
    }

    private ModelRouter routerWith(Set<String> availableModels) {
        ModelRegistry registry = new ModelRegistry(new DefaultResourceLoader(), registryProps());
        registry.reload();
        EngineRegistry engines = new EngineRegistry(List.of(stubOllama(availableModels)));
        return new ModelRouter(registry, new HeuristicTaskClassifier(), engines);
    }

    private Engine stubOllama(Set<String> availableModels) {
        return new Engine() {
            @Override public String name() { return "ollama"; }
            @Override public void streamChat(ChatRequest r, Consumer<ChatChunk> c) { }
            @Override public ChatResult chat(ChatRequest r) { return null; }
            @Override public EmbeddingResult embed(EmbeddingRequest r) { return null; }
            @Override public boolean isHealthy() { return true; }
            @Override public Set<String> availableModels() { return availableModels; }
        };
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

    @Test
    void availabilityFilterSkipsUnavailableTopScorer() {
        // Only qwen2.5-7b is pulled; gemma2-9b (chat score 9) is registered but not available.
        // 'auto' chat must resolve to the best *available* model, not 404 on gemma2-9b.
        ModelRouter r = routerWith(Set.of("qwen2.5-7b:latest", "nomic-embed:latest"));
        RouteDecision d = r.route(new RouteQuery("auto", null,
                List.of("How are you today?"), false, false));
        assertThat(d.category()).isEqualTo(TaskCategory.CHAT);
        assertThat(d.modelId()).isEqualTo("qwen2.5-7b");
        assertThat(d.reason()).contains("avail-filtered");
    }

    @Test
    void forcedUnavailableModelRejectedWithClearError() {
        ModelRouter r = routerWith(Set.of("qwen2.5-7b:latest"));
        assertThatThrownBy(() -> r.route(new RouteQuery("gemma2-9b", null, List.of("hi"), false, false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not currently available");
    }
}
