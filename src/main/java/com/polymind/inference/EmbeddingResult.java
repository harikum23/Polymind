package com.polymind.inference;

import java.util.List;

public record EmbeddingResult(String model, List<float[]> vectors, ChatChunk.Usage usage) {}
