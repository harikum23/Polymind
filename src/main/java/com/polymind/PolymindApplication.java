package com.polymind;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

/**
 * Polymind — a standalone smart LLM-router service built on Spring Boot + Spring Modulith.
 *
 * <p>Speaks the OpenAI-compatible API on port 8090, routes each request to the best local
 * model (chat/code/math/reasoning) and optionally augments prompts with a knowledge layer
 * and live web search. The GPU work stays in the inference engines (Ollama, etc.).
 */
@Modulithic(systemName = "Polymind")
@SpringBootApplication
public class PolymindApplication {

    public static void main(String[] args) {
        SpringApplication.run(PolymindApplication.class, args);
    }
}
