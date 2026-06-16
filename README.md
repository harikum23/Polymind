# Polymind

A standalone, self-hosted **smart LLM-router** built on Java 21 + Spring Boot 3.3 + Spring Modulith.
Speaks the **OpenAI-compatible API** on port **8090**, routes each request to the best local model
(chat / code / math / reasoning), and optionally augments prompts with a **knowledge (RAG) layer**
and **live web search**. The GPU work stays in the inference engine (Ollama); Polymind orchestrates.

Runs alongside the Python `gemma-gateway` (`:8080`) for honest A/B comparison — same wire contract,
just a different base URL. See `../docs/ARCHITECTURE.md` (source of truth).

## Highlights
- **OpenAI edge:** `POST /v1/chat/completions` (SSE streaming + non-streaming), `POST /v1/embeddings`,
  `GET /v1/models`, `GET /v1/health`.
- **Smart switch:** `model` = concrete id (force) | category alias (`chat`/`code`/`math`/`reasoning`)
  | `auto` (classify → best). Heuristic classifier + capability guards (tools/ctx).
- **Tenancy & governance:** API-key auth + per-key model allowlist, admin key endpoints, Bucket4j
  rate-limit/quota.
- **Resilience & admission:** Resilience4j circuit breaker / retry / bulkhead around inference;
  bounded concurrency + backpressure on virtual threads.
- **Knowledge (optional):** pgvector indexer/retriever/injector, activated via
  `metadata.knowledge_pack`. Plain calls pay nothing.
- **Web search:** SearXNG by default (3 access patterns: `metadata.web_search:true`, the agent loop,
  and `POST /v1/tools/web_search`).
- **Agent:** ReAct/tool-use loop with step budget + trace.
- **Self-documenting:** Swagger UI at `/swagger-ui.html`, spec at `/v3/api-docs`.

## Run locally (no Docker)
Prereqs: JDK 21, a running Ollama (`http://localhost:11434`) with the models in `src/main/resources/models.yaml`.

```bash
# Dev mode: auth off, knowledge off, app on :8090
POLYMIND_AUTH_ENABLED=false ./gradlew bootRun
# Swagger UI:   http://localhost:8090/swagger-ui.html
# Health:       http://localhost:8090/v1/health
# Models:       http://localhost:8090/v1/models
```

Example chat (auto routing):
```bash
# (curl shown for reference; this repo's tooling routes HTTP through a sandbox)
POST http://localhost:8090/v1/chat/completions
{ "model": "auto", "messages": [{ "role": "user", "content": "Write a Python quicksort" }] }
```

Build the fat JAR and run it directly:
```bash
./gradlew clean bootJar
java -XX:+UseZGC -XX:+ZGenerational -Dspring.threads.virtual.enabled=true -jar build/libs/polymind.jar
```

Run tests + the modulith boundary verification:
```bash
./gradlew test            # includes ApplicationModules.verify()
```

## Run via docker-compose
Topology: `caddy (TLS) → polymind (:8090) → ollama (host) + searxng`.

```bash
# Always-on stack (polymind + caddy + searxng; ollama on the host).
POLYMIND_ADMIN_KEY=pmk-admin-xyz docker compose up -d --build
# HTTPS via Caddy:  https://localhost:8443/v1/health   (internal CA, self-signed)
# Direct (no TLS):  http://localhost:8090/v1/health

# Enable the knowledge layer (adds Postgres + pgvector). Also set SPRING_PROFILES_ACTIVE=postgres
# on the polymind service (see docker-compose.yml comment).
docker compose --profile knowledge up -d

# Multi-replica scale-out cache/quota (adds Redis — see docs/future-pending.md).
docker compose --profile scale up -d
```

Production HTTPS with real certs:
```bash
POLYMIND_DOMAIN=polymind.example.com docker compose up -d   # Caddy auto-provisions Let's Encrypt
```

## Configuration
- `src/main/resources/application.yaml` — server, security toggle, governance, admission, search.
- `src/main/resources/models.yaml` — the model registry (capability scores per task).
- Key env: `OLLAMA_BASE_URL`, `SEARXNG_BASE_URL`, `POLYMIND_AUTH_ENABLED`, `POLYMIND_ADMIN_KEY`,
  `SPRING_PROFILES_ACTIVE=postgres`, `POSTGRES_URL/USER/PASSWORD`.

## Module map (Spring Modulith)
`web` · `routing` · `knowledge` · `inference` · `agent` · `tools` · `admission` · `resilience` ·
`tenancy` · `governance` · `observability` · `persistence`. Boundaries enforced by
`ApplicationModules.verify()` (`src/test/java/com/polymind/ModularityTests.java`).

## Deferred work
See `../docs/future-pending.md` for items intentionally not built yet (LLM classifier upgrade,
auto pack/web-search detection, Redis-backed shared state, AppCDS/CRaC, native tool-call parsing).
