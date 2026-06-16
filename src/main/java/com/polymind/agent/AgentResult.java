package com.polymind.agent;

import com.polymind.inference.ChatChunk;

/** Outcome of an agent run: final answer, the model used, the trace, and aggregated usage. */
public record AgentResult(String content, String model, AgentTrace trace, ChatChunk.Usage usage) {}
