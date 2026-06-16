# Polymind — Architecture (Source of Truth)

> Single authoritative design for **Polymind**, a new standalone smart LLM-router
> service. Supersedes all earlier scattered planning notes. If another doc
> disagrees with this one, this one wins.

---

## 0. Status & relationship to gemma-gateway

**Polymind is a brand-new, separate project — it does NOT replace the existing
Python `gemma-gateway`.** The Python gateway stays the production service.
Polymind is a parallel experiment to **prove the theory** (smart per-task model
routing + a knowledge-injection layer on a Java/Modulith foundation) before any
migration is ever considered.

- **Coexistence:** Polymind runs on its **own port** (e.g. `:8090`), alongside the
  Python gemma-gateway (`:8080`). Both can run at the same time.
- **Same wire contract:** both expose the OpenAI-compatible API, so a client
  (AgenticFlow, SDKs) can point at either by changing only the base URL — making
  honest **A/B comparison** trivial.
- **Shared backends:** both talk to the *same* local inference engines
  (Ollama/llama.cpp/vLLM). Polymind adds nothing on the GPU side.
- **Exit criteria (when is the theory "proven"?):** routing picks the right model
  for chat/math/code reliably; the knowledge layer measurably improves answers on
  a domain (e.g. a Java project); concurrency/stability hold under load; ops are
  simpler. Only after that is replacing gemma-gateway even discussed.

This document is a **plan only** — nothing here is implemented yet.

---

## 1. Vision

One **central, self-hosted LLM gateway** that every project points at. It:

- Speaks the **OpenAI-compatible API**, so any tool (AgenticFlow, SDKs, IDEs)
  connects with zero custom code.
- **Smartly picks the best local model for each request** (chat vs math vs code),
  with a **forceful override** when the caller names a model.
- **Augments prompts with a knowledge layer** (RAG) so smaller local models punch
  above their weight on domain-specific work (e.g. a Java project).
- Includes **web search** as a first-class tool.
- Is built on **Java + Spring Boot + Spring Modulith** for high concurrency,
  retained in-process state, and long-term maintainability.

### Goals
- Efficient, manageable, single-deployable server.
- High concurrency (thousands of simultaneous streaming requests) without losing
  shared state.
- Pluggable models, knowledge packs, and tools.
- **Self-documenting API** — live, browsable API docs served by the app itself
  (Spring Boot + springdoc-openapi → Swagger UI), so every endpoint, schema, and
  extension is discoverable without external docs.

### Non-goals
- **Not** an inference engine. The GPU work stays in Ollama / llama.cpp / vLLM.
  This service orchestrates them; it never does token generation itself.
- **Not** a faster *generator*. Token throughput is a backend concern (batching,
  parallel slots, quantization). This service maximizes utilization, compatibility,
  concurrency, and intelligence of routing — not raw tokens/sec.

---

## 2. Why Java + Spring Modulith

- **Virtual Threads (Loom, JDK 21+):** handle thousands of long-lived streaming
  (SSE) connections with simple blocking code and no Python-style GIL limit. This
  is the core reason for the JVM here.
- **Retained in-process state:** model registry, admission queue, circuit state,
  routing stats, conversation/session cache, and the knowledge index all live in
  **one deployable**, shared across all cores — no multi-process state-sync dance.
  Redis is optional (for multi-instance later), not mandatory.
- **Spring Modulith:** microservice-grade module boundaries inside one app.
  Enforced at build time; split a module into its own service only if it ever
  needs independent scaling.
- **ZGC** for sub-millisecond GC pauses → predictable streaming tail latency.

---

## 3. Module structure (Spring Modulith)

Each module is a top-level package with an explicit published API. Cross-module
communication uses published service interfaces or async `ApplicationEvent`s.
Boundaries verified by `ApplicationModules.verify()` in a test.

```
                       ┌────────────────────────────────────────────┐
   any project ───────▶│ web — OpenAI-compatible REST + SSE edge      │
   (OpenAI client)     └───────┬─────────────────────────────────────┘
                               │
        ┌──────────┬───────────┼───────────┬────────────┬───────────┐
        ▼          ▼           ▼            ▼            ▼           ▼
     tenancy   governance   routing     knowledge      agent     observability
     (keys)    (limits)   (SMART SWITCH) (RAG layer)  (tool loop)  (metrics)
                               │            │            │
                               ▼            ▼            ▼
                          inference      persistence    tools
                        (engine ports)  (db+vectors)  (web_search,…)
                               │
                ┌──────────────┼───────────────┬──────────────┐
                ▼              ▼                ▼              ▼
            OllamaEngine   VllmEngine     LlamaCppEngine  RemoteOpenAIEngine
```

| Module | Responsibility |
|--------|----------------|
| `web` | OpenAI-compatible controllers (`/v1/chat/completions`, `/v1/embeddings`, `/v1/models`), SSE streaming, request validation |
| `routing` | **The smart switch** — task classification, model registry, capability scoring, forced-model override |
| `knowledge` | **RAG layer** — knowledge packs, vector store, retriever, prompt-context injector |
| `inference` | `Engine` port + adapters (Ollama/vLLM/llama.cpp/remote); streaming token relay |
| `agent` | ReAct/tool-use loop, step budgets, trace |
| `tools` | web search + builtin tools (fetch_url, summarize, calculator, …) |
| `admission` | bounded priority queue, concurrency control, backpressure (Loom) |
| `resilience` | circuit breaker, retry, bulkhead, timeout (Resilience4j) |
| `tenancy` | API keys, per-key model allowlist, admin key management |
| `governance` | rate limit + quota (Bucket4j, optional Redis) |
| `observability` | Micrometer + OpenTelemetry, `/admin/metrics`, per-module tracing |
| `persistence` | keys, metrics, trace store, **vector index** (Postgres+pgvector / Redis) |

---

## 4. The smart switch (`routing`) — heart of the service

### 4.1 Model registry
Declarative catalog of locally-hosted models with **capability scores** per task
category. Example (`models.yaml`):

```yaml
models:
  qwen2.5-7b:   { engine: ollama, ctx: 32768, supports_tools: true,
                  scores: { chat: 8, code: 8, math: 7, reasoning: 7 } }
  deepseek-coder-6.7b: { engine: ollama, ctx: 16384, supports_tools: false,
                  scores: { chat: 5, code: 9, math: 6, reasoning: 6 } }
  qwen2.5-math-7b: { engine: ollama, ctx: 8192,
                  scores: { chat: 4, code: 5, math: 9, reasoning: 8 } }
  gemma2-9b:    { engine: ollama, ctx: 8192,
                  scores: { chat: 9, code: 6, math: 5, reasoning: 7 } }
  nomic-embed:  { engine: ollama, role: embed }
```

### 4.2 Selection algorithm (precedence — first match wins)
1. **Forced explicit model.** If `model` is a concrete registered id
   (e.g. `qwen2.5-7b`) → use it, *bypassing classification*. Honors the caller's
   override. (Rejected only if the API key's allowlist forbids it.)
2. **Forced category.** If `model` is a category alias (`chat` / `code` / `math`
   / `reasoning`) → pick the highest-scoring available model in that category.
3. **Auto.** If `model` is `auto` (or omitted) → **classify the request**, then
   pick the best-scoring available model for the detected category.

So the **same OpenAI `model` string** is the control knob — fully compatible:
`"qwen2.5-7b"` (force), `"code"` (force category), `"auto"` (smart).

### 4.3 Task classification pipeline (cheap → expensive; stop at first confident)
1. **Explicit hint** — caller-supplied `metadata.task` or `X-Task` header.
2. **Heuristics (fast, free)** — code fences/imports → `code`; math symbols,
   "solve/derivative/integral/equation" → `math`; otherwise `chat`.
3. **Classifier model (fallback)** — a tiny fast model does zero-shot intent
   classification when heuristics are unsure. Cached by request fingerprint.

Pluggable `TaskClassifier` interface so the strategy can evolve (later: an
embedding-similarity router).

### 4.4 Capability guards
Selected model must satisfy hard requirements: if the request has `tools`, only
`supports_tools` models are eligible; if it needs a big context, filter by `ctx`.
Falls back to the next-best model that qualifies.

---

## 5. Knowledge layer (`knowledge`) — RAG augmentation

Purpose: inject domain/project knowledge into the prompt so a small local model
"understands" a topic it wasn't trained on (e.g. *your* Java project).

### Concepts
- **Knowledge pack** — a named, indexed corpus (project docs, code, API references,
  runbooks). e.g. `java-springboot`, `my-trade-engine`.
- **Vector store** — embeddings of pack chunks (pgvector / Redis vectors), built by
  an offline/background **indexer**.
- **Retriever** — on request, embed the query, fetch top-K relevant chunks from the
  selected pack(s).
- **Injector** — prepend retrieved context to the system/user prompt under a token
  budget (reserve room for the answer; trim by relevance).

### Activation
- **Explicit:** caller passes `metadata.knowledge_pack: "java-springboot"`.
- **Auto (later):** detect topic from the request and choose a pack.
- **None:** packs are optional; absence = plain generation.

### Flow
`query → embed → vector search in pack → top-K chunks → budget-trim → inject into
prompt → model generates grounded answer.` Keeps the door open to per-project
knowledge that improves over time without retraining any model.

---

## 6. Web search (`tools` + `agent`)
First-class tool available to the agent loop. When enabled (or when the model
emits a `web_search` tool call), the `tools` module queries a search provider
(e.g. SearXNG/Gemini), fetches + summarizes results, and feeds them back into the
generation loop. Cached and quota-limited. Complements the knowledge layer:
knowledge = *your* curated corpus; web search = *live* external info.

---

## 7. Request lifecycle (end to end)

```
POST /v1/chat/completions  (OpenAI shape; model = "auto" | "code" | "qwen2.5-7b")
  │
  1. web         validate, parse (+ optional metadata.task / knowledge_pack)
  2. tenancy     authenticate API key; check model allowlist
  3. governance  rate-limit + quota (per key)
  4. routing     resolve model:  forced id ▸ forced category ▸ classify→best
  5. knowledge   if pack set/detected → retrieve + inject context
  6. agent/tools if tools enabled (web_search,…) → run tool loop
  7. admission   enqueue under concurrency limit + backpressure
  8. inference   pick engine for chosen model → stream tokens
        (wrapped by resilience: circuit breaker / retry / timeout)
  9. observability  record latency, TTFT, tokens, chosen model, queue wait
  ▼
SSE stream back, OpenAI-shaped (choices[].delta …)
```

---

## 8. API surface

OpenAI-compatible, so existing clients work unchanged:
- `POST /v1/chat/completions` — streaming + non-streaming; `tools` supported.
- `POST /v1/embeddings`
- `GET  /v1/models` — lists registered models **and** category aliases, with
  capability metadata.
- Admin/health: `/v1/health`, `/v1/admin/keys`, `/v1/admin/metrics`.

**Compatible extensions** (OpenAI clients ignore unknown fields):
- `model`: concrete id (force) | category alias (`chat`/`code`/`math`) | `auto`.
- `metadata.task`: optional explicit task hint.
- `metadata.knowledge_pack`: optional RAG pack name.
- `metadata.force`: `true` to forbid any routing fallback.

### 8.1 Live API documentation (Spring Boot, required)
The service **documents itself** — no separate API docs to maintain.

- **springdoc-openapi** auto-generates an OpenAPI 3 spec from the Spring
  controllers and DTOs. Add `springdoc-openapi-starter-webmvc-ui`.
- **Swagger UI** served by the app at `/swagger-ui.html` (interactive "try it
  out" console), with the raw spec at `/v3/api-docs` (JSON) and
  `/v3/api-docs.yaml`.
- **Annotate** controllers/DTOs with `@Tag`, `@Operation`, `@Schema`,
  `@ApiResponse` so each endpoint, field, and the Polymind-specific extensions
  (`model` aliases, `metadata.task`, `metadata.knowledge_pack`, `metadata.force`)
  are described inline — including examples for `auto` / category / forced-model
  calls.
- **Grouped docs** via `GroupedOpenApi`: e.g. a `public` group (the OpenAI-compatible
  `/v1/*` surface) and an `admin` group (`/v1/admin/*`), so consumers see only what
  they need.
- **Security shown in UI:** declare the Bearer API-key scheme so Swagger UI lets you
  authorize and call live endpoints.
- **Toggle per environment:** enable in dev/staging; gate or disable
  `/swagger-ui`/`/v3/api-docs` in production via config (`springdoc.swagger-ui.enabled`).
- The generated `/v3/api-docs` JSON is *also* the artifact other tools (AgenticFlow,
  client codegen) can consume to verify OpenAI compatibility.

### 8.2 Web search — how it is used
Provider-agnostic (mirrors today's gemma-gateway: SearXNG self-hosted **or** an
API like Gemini/Brave/Tavily, with fallback), plus result caching and per-key
daily quota. Three access patterns:

1. **Per-request flag (simplest).** Caller sets `metadata.web_search: true` on
   `/v1/chat/completions`. Polymind runs a search, injects the results
   (text + `sources[]`) into the prompt context, then generates a grounded answer.
   Good for "answer using live info."
2. **Tool in the agent loop.** When `tools` are enabled and the model emits a
   `web_search` tool call, the `tools` module executes it and feeds results back
   for the next step. Good for multi-step research.
3. **Direct endpoint (optional).** `POST /v1/tools/web_search { query, days? }` →
   `{ text, sources[], cache_hit }` with no generation. Useful for debugging,
   direct use, and it shows up in Swagger UI.

Knowledge layer vs web search: **knowledge packs = your curated corpus**
(`§5`); **web search = live external info**. Both feed context; either, both, or
neither can apply to a request.

Web search is a **first-class, required capability** — client apps such as
**TradeEngine** depend on it, not just interactive chat. Backend defaults to
**SearXNG** (self-hosted, no API key, private); an API provider is a drop-in
alternative via config (`search.provider`). Caching/quota use the in-process
cache by default, or Redis when configured (`§11`).

---

## 9. Concurrency & state model
- **Spring MVC + Virtual Threads** (`spring.threads.virtual.enabled=true`) — simple
  imperative streaming code at massive concurrency. (WebFlux only if end-to-end
  reactive backpressure is later required; isolate it behind the `inference` port.)
- **In-process shared state**, lock-light: model registry (hot-reloadable), admission
  queue, circuit state, routing stats, knowledge index handle, optional session
  cache. One deployable owns it all — no cross-process sync needed for a single
  instance. Redis is an optional add-on for horizontal scale.
- Backend calls via JDK `HttpClient` (HTTP/2, virtual-thread friendly), pooled
  per engine.

---

## 10. Tech stack
| Concern | Choice |
|---------|--------|
| Runtime | Java 21+, Spring Boot 3.3+, Spring Modulith |
| Concurrency | Virtual Threads (Loom); ZGC |
| Resilience | Resilience4j (circuit/bulkhead/retry/timeout) |
| Rate limit / quota | Bucket4j (+ Redis optional) |
| Observability | Micrometer + OpenTelemetry; Modulith observability |
| Persistence | Postgres + **pgvector** (keys, metrics, trace, knowledge vectors); Redis optional |
| HTTP client | JDK 21 HttpClient (streaming, HTTP/2) |
| Engine adapters | custom `Engine` ports (Spring AI optional) |
| Auth | Spring Security API-key filter |
| **API docs** | **springdoc-openapi** (OpenAPI 3 + Swagger UI at `/swagger-ui.html`) |

---

## 11. Build / run / deploy

### 11.1 Artifact: one executable JAR (not WAR)
- Ship a **Spring Boot executable fat JAR** with an embedded server. WAR is
  avoided — it requires an external servlet container and is legacy for
  containerized services. `java -jar polymind.jar` *is* the whole app.
- **Multi-stage Dockerfile:** stage 1 builds with Maven/Gradle (JDK 21); stage 2
  runs the JAR on a slim JRE 21 base. One image, one container.
- Runs on its **own port** (default `:8090`) so it coexists with the Python
  gemma-gateway (`:8080`); point any client at whichever base URL to compare.
- Config via `application.yaml` + `models.yaml` (registry) + knowledge-pack defs;
  secrets via env. Health/readiness endpoints; metrics scraped by Prometheus.
- **Browsable API docs** at `/swagger-ui.html` (spec at `/v3/api-docs`).

### 11.2 Dependencies — what's required vs optional
| Component | Needed? | Why / when |
|-----------|---------|------------|
| **Polymind JAR** | **Required** | The service itself |
| **Inference engine** (Ollama/vLLM/llama.cpp) | **Required** | Does the actual generation; reached over HTTP (reuse the one gemma-gateway already uses) |
| **Web search backend** (SearXNG, or a search API) | **Required** | Web search is a first-class capability — consumed by real client apps (e.g. **TradeEngine**). Default to **SearXNG** (self-hosted, private, no key); a search API is a config-swap alternative |
| **Caddy** | **Required** | TLS/HTTPS reverse proxy + single public entrypoint — Polymind will be **exposed over HTTPS**, so Caddy is part of the deployment. Terminates TLS (auto-certs) in front of Polymind on `:8090` |
| **Redis** | **Optional** | Single instance keeps cache/quota/rate-limit **in-process** (Caffeine + Bucket4j-local). Add Redis only for **multiple replicas** (shared limits/cache) or to **persist** the search cache across restarts |

### 11.3 Compose topology
Standard stack (one `docker-compose.yml`, mirroring gemma-gateway's shape):

```
  Internet ──HTTPS──▶ caddy (TLS, :443/:8443) ──▶ polymind (:8090)
                                                     │  ├─▶ ollama  (host engine)
                                                     │  └─▶ searxng (web search)
                                                     └─(optional)─▶ redis
```

- **Always on:** `polymind` + `caddy` + `searxng` (+ host `ollama` via
  `host.docker.internal`).
- **Optional add-on:** `redis` — enable via a compose **profile** only when you
  scale to multiple replicas or want the search cache to survive restarts.
- For pure local iteration you can still run `polymind` alone (hit `:8090`
  directly, skip Caddy) — but the deployable target includes Caddy for HTTPS.

State note: Polymind retains cache/quota/limits **in-process** (`§9`), so Redis
stays optional even with Caddy + web search in the picture — those two are about
**exposure** and **capability**, not shared state.

---

## 12. Build order (incremental, each shippable)
1. **Skeleton:** new `polymind/` Spring Boot project (own port `:8090`) — modulith
   with the 12 modules + boundary-verification test + **springdoc-openapi wired so
   `/swagger-ui.html` is live from day one** (docs grow as endpoints are added).
2. **Edge + inference:** `web` (OpenAI endpoints, SSE via Loom) + `inference`
   (Ollama adapter, pointing at the same engines gemma-gateway uses). Reach parity
   with today's generate/embed — OpenAI-shaped. Annotate endpoints for Swagger.
3. **Smart switch:** `routing` (registry + forced override + heuristic classifier).
   Add category aliases + `auto`.
4. **Tenancy + governance + resilience + observability.**
5. **Knowledge layer:** `knowledge` indexer + retriever + injector (pgvector).
6. **Web search + agent loop.**
7. **Classifier model upgrade** (LLM/embedding router) once data exists.

Always in parallel (separate track): tune the **inference backend** (continuous
batching, parallel slots, quantization, prompt cache) — the only lever that raises
actual tokens/sec.

---

## 13. Risks & guardrails
- **Routing wrong model:** always allow forced override; log chosen model + reason;
  make scores tunable; ship heuristics before any LLM classifier.
- **Knowledge layer complexity:** start with explicit packs + simple top-K; auto
  topic detection is a later phase. Keep it optional so plain calls never pay for it.
- **JVM footprint/warmup:** mitigate with AppCDS / CRaC if cold start matters.
- **Don't over-modularize / don't rewrite engines.** One deployable; engines stay
  native.
- **Set expectations:** smarter routing + knowledge + concurrency ≠ faster token
  generation. That remains a backend concern.
