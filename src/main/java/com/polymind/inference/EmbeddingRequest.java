package com.polymind.inference;

import java.util.List;

public record EmbeddingRequest(String model, List<String> input) {}
