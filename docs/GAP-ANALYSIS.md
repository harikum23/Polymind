# Polymind — Technical Gap Analysis

**Date:** 2026-06-18
**Scope:** Full scan of `src/main/java` (93 files), `src/test` (4 files), `models.yaml`, `application.yaml`, `docker-compose.yml`, `Dockerfile`, `build.gradle`, `docs/`.
**Method:** Claims verified against actual source, not docs. Where a documented issue is already fixed in code, it is marked **[FIXED IN CODE]**.

---

## 0. Corrections to prior assumptions (verified against code)

| Prior claim | Reality in code |
|---|---|
| "Is SSE/`stream:true` actually implemented?" | **YES — fully implemented.** `ChatCompletionsController.streaming()` (lines 76-102) returns an `SseEmitter(0L)`, relays NDJSON token-by-token from `OllamaEngine.streamChat` on a virtual thread, emits OpenAI-shaped `choices[].delta` chunks and a terminal `[DONE]`. Not a gap. |
| "`num_ctx` hardcoded to 8192" | **Partially fixed.** It is now a configurable property (`OllamaProperties.numCtx`, default `8192`, sent at `OllamaEngine.java:181-182`). But the **per-model `ctx` from `models.yaml` is still ignored** — qwen2.5-7b declares `ctx: 32768` yet every request is capped at the global 8192. This is now a *config-granularity* gap, not a hardcode. |
| "Resilience4j circuit breaker/retry — is it real?" | **YES — wired programmatically.** `ResilienceConfig` builds CircuitBreaker/Retry/Bulkhead registries per-engine; `ResilientInferenceGateway` wraps inference. Note: it uses **programmatic registries, not `@CircuitBreaker`/`application.yaml` instances** — so the config lives in Java, not externally tunable without a rebuild (Medium gap, see R5). |

---

## 1. Reliability / Correctness

### R1 — Router does not verify model availability before routing — **CRITICAL**
**Evidence:** `OllamaEngine` exposes `isHealthy()` (probes `/api/tags`, line 152-154) but **no `availableModels()`**. `Engine.java:27` only declares `isHealthy()`. `ModelRouter` scores registry entries and never filters by what is actually pulled. `models.yaml` lists `gemma2-9b`, `deepseek-coder-6.7b`, `qwen2.5-math-7b` — none guaranteed pulled. Result: `auto`/category routing selects an unpulled model → `OllamaEngine.java:63` throws `EngineException("Ollama chat failed: HTTP 404")` → `ApiExceptionHandler:19` maps to **502 `engine_error`**. Confirmed in live smoke test (docs/future-pending.md §11) and A/B notes.
**Fix:** Add `Set<String> availableModels()` to `Engine` (Ollama `GET /api/tags`), cache with ~30s TTL. In `ModelRouter`, filter candidates to available models before scoring; fall back to next-best; if none available in-category, fall back across categories or return a clear `503 model_unavailable` instead of a raw 502. Add a registry-id → engine-tag mapping (`engine_model:` field on `ModelSpec`) since ids are sent to Ollama verbatim (`qwen2.5-7b` vs pulled `qwen2.5:7b-instruct-q4_K_M`).

### R2 — No startup validation of registry vs engine — **HIGH**
**Evidence:** No bean validates that `models.yaml` entries exist in Ollama at boot. A misconfigured deploy looks healthy until first `auto` call 502s.
**Fix:** On startup (and on a scheduled probe), reconcile `models.yaml` against `availableModels()`; log a WARN listing declared-but-missing models; optionally expose this in `/v1/health` and a Micrometer gauge `polymind.models.missing`.

### R3 — In-process API key store lost on restart — **HIGH**
**Evidence:** `ApiKeyService` uses `ConcurrentHashMap<String,ApiKey> bySecret`, reseeded only with the bootstrap admin in `@PostConstruct seedAdmin()`. All admin-created keys vanish on restart. Confirmed; matches future-pending.md §8.
**Fix:** JPA `ApiKeyEntity` + Spring Data repo under the `postgres` profile (infra already present via pgvector). Keep in-memory for dev. See S2 for the hashing requirement that should land in the same change.

### R4 — Retry may double-submit non-idempotent inference — **MEDIUM**
**Evidence:** Resilience4j Retry wraps inference (`ResilientInferenceGateway`). LLM generation is non-idempotent and expensive; blind retries on timeout can fire a second full generation, doubling GPU load and latency under stress.
**Fix:** Restrict retry to connection/5xx-pre-first-token failures only; never retry once streaming has begun (TTFT passed). Make retry `maxAttempts` and the retryable-exception predicate explicit.

### R5 — Resilience config is compiled-in, not externalized — **MEDIUM**
**Evidence:** `ResilienceConfig` builds registries in Java; there are **no `resilience4j.*` keys in `application.yaml`** and no `@CircuitBreaker` annotations. Tuning thresholds requires a rebuild.
**Fix:** Move thresholds to `application.yaml` `resilience4j.circuitbreaker.instances.*` / `retry.instances.*` or bind them to `polymind.resilience.*` properties so ops can tune without recompiling.

### R6 — Embeddings issued one-HTTP-call-per-input — **MEDIUM**
**Evidence:** `OllamaEngine.embed()` loops `request.input()` and does a separate `POST /api/embeddings` per text (lines ~120-143). Indexing a large knowledge pack = N sequential round-trips.
**Fix:** Batch via Ollama `/api/embed` (newer batched endpoint) or parallelize on the virtual-thread executor with a bounded semaphore.

---

## 2. Security

### S1 — Committed default admin key in a public repo — **CRITICAL**
**Evidence:** `docker-compose.yml` sets `POLYMIND_ADMIN_KEY: ${POLYMIND_ADMIN_KEY:-pmk-admin-dev}`; `application.yaml` defaults `admin-key: ${POLYMIND_ADMIN_KEY:pmk-admin-dev}`. Repo is public (`github.com/harikum23/Polymind`). Anyone who reads the repo and reaches an instance that didn't override the env var has full admin (key CRUD, knowledge, metrics).
**Fix:** Remove the default entirely; fail fast at startup if `POLYMIND_ADMIN_KEY` is unset when auth is enabled (no insecure fallback). Generate a random key on first boot and log it once. Document key rotation. Treat the existing `pmk-admin-dev` as compromised.

### S2 — API key secrets stored in plaintext — **HIGH**
**Evidence:** `ApiKeyService` keys the map by the **raw secret** (`bySecret.put(admin.secret(), admin)`) and authenticates by raw lookup (`bySecret::get`). When persistence lands (R3) this would write plaintext to the DB. No hashing anywhere.
**Fix:** Store only a salted hash (e.g. SHA-256 of `secret`, or Argon2/bcrypt if you accept the per-request cost; SHA-256 is acceptable for high-entropy random keys). Show the full key once at creation, persist only the hash, authenticate by hashing the presented secret. Land this together with R3.

### S3 — No request input validation / size limits — **MEDIUM**
**Evidence:** `OpenAiChatRequest` is a plain record with no `@Valid`/`@NotNull`/`@Size`; `temperature`, `maxTokens`, `messages` are unbounded. No max message count, max prompt length, or `maxTokens` ceiling. A huge prompt can OOM or pin a GPU runner.
**Fix:** Add Jakarta Validation (`@NotEmpty messages`, bounded `@Max` on `maxTokens`, range check on `temperature`/`top_p`), enforce a max total prompt-char/token budget pre-routing, and cap Spring's `max-http-request-size`.

### S4 — Timing-safe key comparison — **LOW**
**Evidence:** `ConcurrentHashMap.get(secret)` is an equality lookup; not constant-time. With hashed keys (S2) the hash comparison removes most of the timing surface; otherwise raw `equals` is technically a side channel.
**Fix:** Lookup by hash (resolves it as a side effect of S2).

---

## 3. AI / ML-Specific (priority focus)

### A1 — Task classification is regex-only; the documented LLM/embedding fallback does not exist — **HIGH**
**Evidence:** `HeuristicTaskClassifier` is the only `TaskClassifier`: code via ```` ``` ````/import regex → CODE; `[∫∑√π…]` / "solve/derivative/integral" → MATH; else CHAT (lines 16-39). ARCHITECTURE.md §4.3 step 3 promises a "tiny fast classifier model… zero-shot intent classification when heuristics are unsure" — **not implemented**. The interface is pluggable but unused. Consequence: "explain the time complexity of quicksort" (no fence) routes to CHAT not CODE; reasoning category is essentially never auto-detected (only CHAT/CODE/MATH branches exist — no REASONING heuristic).
**Fix:** Add a `ConfidenceTaskClassifier` chain: heuristic → on low confidence, an embedding-similarity router (embed the query, compare to per-category centroid prototypes using the existing `nomic-embed`) → optional tiny LLM zero-shot. Cache by request fingerprint. Add a REASONING signal (multi-step/planning keywords, long analytical asks).

### A2 — No evaluation / regression harness for routing or RAG — **HIGH**
**Evidence:** Tests cover only `ModelRouterTest` (a handful of asserts). There is **no labeled eval set**, no routing-accuracy metric, no RAG retrieval-quality (recall@k / nDCG) harness, no golden-answer regression. Routing decisions and RAG quality can silently regress.
**Fix:** Build an offline eval module: (1) a labeled prompt→expected-category set to measure classifier accuracy + a confusion matrix in CI; (2) a RAG eval (recall@k, MRR, faithfulness via an LLM-judge) over a fixed knowledge pack; (3) wire both into Gradle as a `:eval` task so PRs report deltas. This is the highest-leverage AI-engineering addition.

### A3 — RAG pipeline is minimal: no reranking, fixed-char chunking, no query expansion — **HIGH**
**Evidence:** `Chunker` is a fixed 1200-char sliding window with 150-char overlap (`KnowledgeProperties`), splitting mid-sentence/mid-token. `KnowledgeRetriever` does a single `nomic-embed` query embed → `PgVectorStore` cosine top-K (`1 - (embedding <=> ?)`, HNSW) → `ContextInjector` concatenates by stored order. **No reranker, no MMR/diversity, no query rewriting, no metadata filtering, no dedupe.** `ContextInjector` budget-trims but the comment says "trim by relevance" while it iterates chunks in arrival order.
**Fix:** (1) Structure-aware chunking (sentence/markdown-heading boundaries, code-aware splitting). (2) Add a reranking stage (cross-encoder or a small LLM-judge rerank of top-N→top-K). (3) MMR for diversity; dedupe near-identical chunks. (4) Confirm `ContextInjector` actually sorts by `VectorChunk.score` before budget-trimming. (5) Optional query expansion/HyDE.

### A4 — Embedding model management is single-model and unvalidated — **MEDIUM**
**Evidence:** Embedding model is a single config string `nomic-embed` (`KnowledgeProperties.embedModel`). No dimension check, no guard that the indexer and retriever used the **same** model/dimension (`PgVectorStore` infers dimension from the first stored vector). Switching embed models silently corrupts a pack.
**Fix:** Persist the embed model + dimension per pack; reject retrieval if the query embed model/dimension differs; version packs by embedding model.

### A5 — Token accounting trusts Ollama counts; no client-side estimate or budget enforcement — **MEDIUM**
**Evidence:** Usage comes from Ollama's `prompt_eval_count` / `eval_count` (`OllamaEngine` 84-85, 141). No independent tokenizer, no pre-flight token estimate, no enforcement that prompt+`num_ctx`+`max_tokens` fit the model's real context window. RAG injection (A3) can silently overflow `num_ctx` (8192) and get truncated by Ollama with no warning.
**Fix:** Add a tokenizer-based pre-flight estimate; reserve answer budget; reject or trim when prompt+reserve exceeds the model's context; expose estimated vs actual token deltas as a metric to catch overflow.

### A6 — No model warmup / `keep_alive` control — **MEDIUM**
**Evidence:** `buildChatBody` sets `num_ctx`, `temperature`, `top_p`, `num_predict` but **never `keep_alive`**, and there is no startup warmup call. A/B notes attribute "~2x slower than gemma-gateway" partly to model thrash/cold runners.
**Fix:** Add `polymind.inference.ollama.keep_alive` (e.g. `-1` to pin, or a TTL) into the options; add an optional startup warmup that issues a 1-token generation per declared model; document `OLLAMA_MAX_LOADED_MODELS` on the host (see O2).

### A7 — Agent loop uses text-protocol ReAct, not native OpenAI tool-calling — **MEDIUM**
**Evidence:** `AgentLoop` instructs the model to emit a textual action and parses `web_search:` from text (line 77); only `web_search` is wired. ARCHITECTURE.md §8 advertises `tools` support on `/v1/chat/completions`, but there is no native OpenAI `tools`/`tool_calls`/`function` parsing (future-pending.md §7 confirms "native tool-call parsing" is deferred). Clients sending OpenAI `tools` get heuristic text parsing, not spec-compliant `tool_calls`.
**Fix:** Implement OpenAI function/tool-calling: accept `tools`/`tool_choice`, emit `tool_calls` in responses, support multi-tool and a pluggable tool registry beyond web_search.

---

## 4. Scalability / Ops

### O1 — Single Ollama backend; no multi-provider abstraction realized — **HIGH**
**Evidence:** `EngineRegistry.require("ollama")` is hardcoded throughout (retriever, indexer). `Engine` port exists but only `OllamaEngine` implements it (future-pending.md §6 lists `LlamaCppEngine`/`RemoteOpenAIEngine` as deferred). One Ollama host = single point of failure and the throughput ceiling.
**Fix:** Add a `RemoteOpenAIEngine` (route to OpenAI/Together/vLLM) and load-balance across multiple Ollama hosts. Make the embed call engine-agnostic instead of `require("ollama")`.

### O2 — No response/semantic caching — **MEDIUM**
**Evidence:** Only the web-search result is cached (`SearchProperties.cacheTtlSeconds`). No chat/embedding response cache. Identical prompts re-run full inference.
**Fix:** Add an exact-match response cache (hash of normalized request) and, higher-value, a **semantic cache** (embed prompt, return cached answer above a similarity threshold). Embedding cache for RAG queries is near-free given pgvector is already present.

### O3 — All governance/quota/cache state is in-process — **MEDIUM**
**Evidence:** Bucket4j buckets and the search cache are in-process (`RateLimitService`, confirmed local). Horizontal scaling beyond one replica breaks rate-limit/quota correctness. Redis exists only behind the `scale` compose profile and is **not wired** in code yet.
**Fix:** Implement the documented `bucket4j-redis` (Lettuce `ProxyManager`) and a Redis-backed cache, selected by `polymind.governance.backend=local|redis`.

### O4 — Observability depth: no TTFT/queue-wait/routing metrics surfaced — **MEDIUM**
**Evidence:** Actuator + Prometheus + `MetricsSnapshotService` exist (CPU/JVM gauges). ARCHITECTURE.md §7 step 9 promises "latency, TTFT, tokens, chosen model, queue wait" but there's no evidence these custom timers/counters are emitted; OpenTelemetry export is deferred (future-pending.md §9).
**Fix:** Emit Micrometer metrics: `polymind.route.decision{category,model}`, `polymind.inference.ttft`, `polymind.admission.queue.wait`, `polymind.tokens{type}`, `polymind.model.unavailable`. Add a Grafana dashboard.

---

## 5. Testing

### T1 — Severe test coverage gap — **HIGH**
**Evidence:** Only **4 test files for 93 main files**: `ModelRouterTest`, `SecurityIntegrationTest`, `ModularityTests`, `PolymindApplicationTests`. **Zero tests** for: streaming/SSE controller, `OllamaEngine` (request body, NDJSON parse, error→502 mapping), the entire knowledge/RAG layer, governance/rate-limit, admission/backpressure, resilience gateway, gemma-compat controller, embeddings, agent loop.
**Fix:** Add (1) `OllamaEngine` tests with a WireMock/MockWebServer Ollama (chat, stream, embeddings, 404→EngineException); (2) `ChatCompletionsController` slice tests for both streaming and non-streaming + the 502/429/400 paths; (3) RAG tests (chunker boundaries, retriever ordering by score, injector budget-trim); (4) governance/admission concurrency tests. Target the request lifecycle end-to-end.

---

## 6. Prioritized Roadmap (highest value first)

1. **(CRITICAL) Model-availability-aware routing + graceful fallback** (R1/R2) — makes `auto` actually work; the headline correctness bug.
2. **(CRITICAL) Remove committed admin key; fail-fast on unset** (S1).
3. **(HIGH) Persistent + hashed API keys** (R3 + S2) — one change on the `postgres` profile.
4. **(HIGH) Routing/RAG evaluation harness** (A2) — your AI-engineering leverage point; prevents silent regressions for everything below.
5. **(HIGH) RAG quality: structure-aware chunking + reranking + MMR** (A3).
6. **(HIGH) Confidence-based classifier (embedding-similarity fallback + REASONING)** (A1).
7. **(MEDIUM) Semantic + exact response caching** (O2) — biggest latency/cost win.
8. **(MEDIUM) Native OpenAI tool/function calling** (A7) — true client compatibility.
9. **(MEDIUM) Per-model `ctx`/`keep_alive` + warmup + token-budget enforcement** (A5/A6, num_ctx granularity).
10. **(MEDIUM) Multi-provider engines + Redis-backed shared state** (O1/O3) — the horizontal-scale story.

---

## Appendix — Evidence index (file:line)

- Routing/availability: `inference/Engine.java:27`, `inference/OllamaEngine.java:152` (only `isHealthy`), `routing/ModelRouter.java`, `routing/HeuristicTaskClassifier.java:16-39`.
- Streaming (works): `web/ChatCompletionsController.java:76-121`, `inference/OllamaEngine.java:56-98`.
- num_ctx: `inference/OllamaEngine.java:181-182`, `inference/OllamaProperties.java:16`.
- Keys: `tenancy/ApiKeyService.java` (`bySecret` ConcurrentHashMap, `seedAdmin`).
- Secrets: `docker-compose.yml` (`pmk-admin-dev`), `application.yaml` `tenancy.admin-key`.
- Resilience: `resilience/ResilienceConfig.java`, `resilience/ResilientInferenceGateway.java`; no `resilience4j.*` in `application.yaml`.
- Admission: `admission/AdmissionControl.java`, `admission/BackpressureException.java`.
- Errors: `web/ApiExceptionHandler.java:18-34` (EngineException→502).
- RAG: `knowledge/Chunker.java:12`, `knowledge/KnowledgeRetriever.java:26-33`, `knowledge/ContextInjector.java:24-33`, `persistence/PgVectorStore.java:46,82-85`, `knowledge/KnowledgeProperties.java:9-15`.
- Embeddings: `inference/OllamaEngine.java:120-147` (per-input loop).
- Agent: `agent/AgentLoop.java:21,77`.
- Tests: `src/test/java/com/polymind/{ModularityTests,PolymindApplicationTests,routing/ModelRouterTest,tenancy/SecurityIntegrationTest}.java` (4 total).
