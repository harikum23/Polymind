package com.polymind.inference;

/** Aggregated non-streaming chat result. */
public record ChatResult(String content, String finishReason, ChatChunk.Usage usage) {}
