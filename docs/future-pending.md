# Polymind — Pending / Deferred Work

Items intentionally **not** implemented in the initial build (steps 1–6 of ARCHITECTURE.md §12 are
done). Each entry gives rationale + a concrete sketch for later. Source of truth remains
`ARCHITECTURE.md`.

Status legend: **DONE** = shipped, **REMAINING** = deferred.

---

## 1. Step 7 — LLM / embedding-based classifier upgrade (§4.3 step 3, §12.7, §13)
- **DONE:** Pluggable `TaskClassifier` interface; `HeuristicTaskClassifier` (code fences/imports →
  code, math symbols/keywords → math, else chat). Routing reads an explicit hint first.
- **REMAINING:** A tiny zero-shot LLM classifier (or embedding-similarity router) used when
  heuristics are unsure, cached by request fingerprint.
- **Why deferred:** §13 says "ship heuristics before any LLM classifier." A learned router needs
  production routing data (which model actually performed best per request) to be worth the latency.
- **How later:** Add `LlmTaskClassifier implements TaskClassifier` calling a small fast model via the
  existing `Engine` port; add a confidence gate in `ModelRouter.resolveCategory` (heuristic →
  fall back to LLM only when ambiguous). Add an embedding-centroid variant per category. Cache by a
  hash of the normalized prompt. Select via `polymind.routing.classifier=heuristic|llm|embedding`.

## 2. Auto knowledge-pack topic detection (§5 "Auto (later)")
- **DONE:** Explicit activation via `metadata.knowledge_pack`; indexer/retriever/injector; the layer
  is fully optional (`NoOpKnowledgeService` when disabled).
- **REMAINING:** Detect the topic of a request and auto-select the relevant pack.
- **Why deferred:** §13 — start with explicit packs + simple top-K; keep plain calls free. Auto
  detection risks injecting irrelevant context and adds an embedding call to every request.
- **How later:** Maintain a per-pack centroid embedding (mean of chunk vectors). On request, embed
  the query once, pick packs whose centroid similarity exceeds a threshold, then retrieve. Reuse the
  query embedding for retrieval to avoid a second embed call.

## 3. Auto web-search topic detection
- **DONE:** Three explicit access patterns — `metadata.web_search:true`, the agent tool-call loop,
  and `POST /v1/tools/web_search`.
- **REMAINING:** Automatically decide a request needs live info and search without an explicit flag.
- **Why deferred:** Same risk/cost profile as auto knowledge detection; explicit control is safer and
  cheaper for the first iteration.
- **How later:** A cheap recency/freshness classifier (keywords like "latest/today/price/news" +
  optional small-model gate) sets `web_search` internally; gate behind
  `polymind.search.auto-detect=true` and the per-key quota already in place.

## 4. Redis-backed shared cache / quota / rate-limit for multi-replica scale (§9, §11 optional)
- **DONE:** In-process Caffeine-style TTL cache (web search), Bucket4j-local rate-limit + daily quota
  (governance) and per-key search quota (tools). A `redis` service exists behind the compose
  `scale` profile.
- **REMAINING:** Shared state across replicas (distributed buckets + cache) and persisting the search
  cache across restarts.
- **Why deferred:** A single instance retains all state in-process (§9). Redis only matters for
  horizontal scale, which is not an exit-criterion for proving the routing/knowledge theory.
- **How later:** Add `bucket4j-redis` (Lettuce) for `ProxyManager`-backed buckets; swap the
  in-process `ConcurrentHashMap` search cache for Redis with TTL; select via
  `polymind.governance.backend=local|redis` and `polymind.search.cache=local|redis`. The
  `RateLimitService` / `WebSearchService` seams already isolate the cache/quota implementation.

## 5. AppCDS / CRaC cold-start mitigation (§13)
- **REMAINING:** Reduce JVM warmup/footprint for fast cold starts.
- **Why deferred:** §13 lists this as a guardrail "if cold start matters." For a long-lived,
  always-on gateway it rarely does; premature optimization.
- **How later:** Generate an AppCDS archive in the Docker build (`-XX:ArchiveClassesAtExit`) and load
  it at runtime (`-XX:SharedArchiveFile`); or adopt CRaC (checkpoint/restore) with a CRaC-enabled JDK
  base image and a warmup checkpoint. Both are Dockerfile/JVM-flag changes only.

---

## Partial slices (working minimal version shipped; remainder deferred)

### 6. Inference engine adapters (§3 diagram)
- **DONE:** `Engine` port + `OllamaEngine` (streaming `/api/chat`, `/api/embeddings`, HTTP/2,
  OpenAI-shaped deltas + finish_reason/usage). `EngineRegistry` resolves by name.
- **REMAINING:** `VllmEngine`, `LlamaCppEngine`, `RemoteOpenAIEngine` adapters.
- **How later:** Implement `Engine` for each backend; they auto-register in `EngineRegistry`. The
  registry/router already dispatch by `ModelSpec.engine`, so no routing changes are needed.

### 7. Native tool-call parsing in the agent loop (§6, §8.2 #2)
- **DONE:** Model-agnostic JSON-action ReAct loop (`{"action":"web_search"|"final"}`) with a step
  budget + full trace; triggered by `metadata.agent:true` or presence of `tools`.
- **REMAINING:** Parse native OpenAI/Ollama `tool_calls` (`message.tool_calls[]`) and support
  multiple builtin tools (fetch_url, summarize, calculator).
- **How later:** Extend `ChatResult`/`OllamaEngine` to surface `tool_calls`; add a `Tool` interface +
  registry in `tools`; have `AgentLoop` dispatch by tool name. The trace + budget plumbing is reused.

### 8. API-key persistence (§3 persistence, §10)
- **DONE:** In-process `ApiKeyService` (thread-safe, seeded admin key) + admin CRUD endpoints.
- **REMAINING:** JPA/Postgres-backed key store so keys survive restarts.
- **How later:** Add an `ApiKeyEntity` + Spring Data repository in `persistence`; back
  `ApiKeyService` with it when the postgres profile is active (keep in-memory for dev). Hash secrets
  at rest.

### 9. OpenTelemetry export & Spring Modulith observability wiring (§10)
- **DONE:** Micrometer metrics, Prometheus actuator endpoint, `/v1/admin/metrics` snapshot,
  routing-decision counters, `spring-modulith-observability` on the classpath.
- **REMAINING:** Configure an OTLP exporter/collector endpoint and per-module spans in deployment.
- **How later:** Add `micrometer-tracing` + OTLP exporter config (`management.otlp.tracing.endpoint`)
  and point at a collector in `docker-compose.yml`.

### 10. Hot-reload of `models.yaml` at runtime (§9 "hot-reloadable")
- **DONE:** `ModelRegistry.reload()` exists and is idempotent (atomic swap).
- **REMAINING:** Trigger reload without restart (file-watch or an admin endpoint).
- **How later:** Add `POST /v1/admin/registry/reload` (admin-only) or a `WatchService` on the
  registry file that calls `reload()`.

### 11. Live engine model-availability filtering in routing (§4.2 "best-scoring **available** model")
- **DONE:** Score-based selection, capability guards (tools/ctx), forced-id ▸ category ▸ auto precedence.
- **REMAINING:** The router does **not** check whether a chosen model is actually loaded in the engine.
  `auto`/category routing picks the top-scored registry entry (e.g. `gemma2-9b`) even if that model
  isn't pulled in Ollama, producing a 502 (`Ollama chat failed: HTTP 404`). Forced-id works because
  the caller names a model that exists. **Surfaced during the live docker smoke test (2026-06-16).**
- **How later:** Add an `Engine.availableModels()` probe (Ollama `GET /api/tags`), cache it with a
  short TTL, and have `ModelRouter` filter candidates to available models before scoring (falling
  back to next-best). Also map registry ids → engine model tags (today the registry id is sent to
  Ollama verbatim, so ids must match the pulled tag or have an `ollama cp` alias — e.g. registry
  `qwen2.5-7b` vs pulled `qwen2.5:7b-instruct-q4_K_M`). Consider an optional `engine_model:` field
  on `ModelSpec` for an explicit id→tag mapping.
