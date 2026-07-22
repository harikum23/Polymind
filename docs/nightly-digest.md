# Nightly Trade Research Digest — Architecture & Runbook

**Status: ACTIVE with real data since 2026-07-22** — the first digest was generated via the
host Claude CLI (Haiku) and indexed (10 chunks, stamped 22:15 IST); TradeEngine calls are being
auto-augmented from it. **Remaining gap:** the in-container nightly schedule (06:30 IST) still
needs `CLAUDE_CODE_OAUTH_TOKEN` (see [§5](#5-activation--the-one-manual-step)) — until set,
scheduled runs fail with a clear 503 and the pack simply keeps serving the last ingested digest.

**Host-run fallback (used for the first digest):** pipe the digest prompt to the local CLI
(`claude -p --model haiku --dangerously-skip-permissions < prompt`), then POST the parsed
sections to `/v1/admin/knowledge/trade-engine/reindex`. Note the prompt must forbid file
writing — the CLI once wrote the sections as files instead of printing them; the sidecar's
prompt now pins this ("PRINT the five delimited sections to stdout").

## 1. What this is & why

A way to give TradeEngine Claude-quality market research **without per-call API cost**.
Instead of TradeEngine paying a hosted model on every research call, we:

1. Run **one** research pass each night with the **Claude CLI on your Max subscription**
   ($0 marginal — subscription-billed, not API-billed).
2. Index that digest into Polymind's `trade-engine` **knowledge pack** (pgvector).
3. Serve every daytime research call from **local Ollama + RAG retrieval** of the digest.

So the expensive step happens once and is reused all day. A local model answering *with*
a Claude-written digest in context is far stronger than answering cold — and it costs nothing
per call and skips the hosted round-trip.

For live questions the morning digest doesn't cover, an **`/ask` endpoint** triggers the Claude
CLI on demand (also subscription-billed).

## 2. Architecture

```
  06:30 IST (in-container scheduler, TZ=Asia/Kolkata)
        │
        ▼
  ┌─────────────────────────┐   claude -p --model haiku      ┌──────────────┐
  │ trade-digest sidecar     │──────(web search, $0)─────────▶│  Claude CLI   │
  │ (node, :9091)            │◀──────5 markdown sections──────│ (Max sub)     │
  │  • nightly scheduler      │                                └──────────────┘
  │  • POST /digest/run       │   POST /v1/admin/knowledge/
  │  • POST /ask (ad-hoc)     │        trade-engine/reindex
  └───────────┬──────────────┘──────────────┐
              │                              ▼
              │                    ┌──────────────────────┐   embed + store
              │                    │  Polymind (:9090)     │──────────────▶ pgvector
              │                    │  knowledge layer      │◀──────────────  (postgres)
              │                    └──────────┬───────────┘   top-K + relevance gate
              │                               │ auto-augments
              │                               ▼
  TradeEngine (separate docker net) ──/v1/generate, /v1/agent──▶ grounded answer
     reaches both via host.docker.internal (9090 = Polymind, 9091 = /ask)
```

**Key design choice — server-side default pack.** TradeEngine speaks the legacy
`/v1/generate` and `/v1/agent` endpoints. Polymind's gemma-compat layer now injects a
**configured default knowledge pack** (`trade-engine`) into every such call, so *all existing
TradeEngine research calls leverage the digest with no TradeEngine code change and no restart*.
A **relevance gate** (cosine-similarity threshold) ensures off-topic calls (e.g. a JSON
validator) get *nothing* injected — only pertinent calls receive context.

## 3. What was built

### Polymind (Java — rebuilt image `polymind:0.1.0`)
| Change | File | Why |
|---|---|---|
| **Live model-availability filter** | `routing/ModelRouter.java`, `inference/Engine.java`, `inference/OllamaEngine.java` | `auto`/category routing now skips registered-but-not-pulled models (e.g. `gemma2-9b`) instead of failing with an opaque Ollama 404. Closes future-pending #11. |
| **Knowledge relevance gate** | `knowledge/KnowledgeProperties.java`, `knowledge/KnowledgeRetriever.java` | `min-score` (default 0.35) drops weakly-matching chunks so a default pack can apply to all traffic without polluting off-topic calls. |
| **Compat-layer metadata + default pack** | `web/GatewayCompatController.java`, `application-postgres.yaml` | `/v1/generate` & `/v1/agent` now forward `metadata` and inject `polymind.gateway-compat.knowledge-pack`. This is how TradeEngine picks up augmentation. |
| **pgvector attach-on-read** | `persistence/PgVectorStore.java` | Fixes a latent bug where the knowledge layer went dark after any Polymind restart until the next reindex. Now it re-attaches to the existing table on first read. |

### Digest sidecar (new — `docker/digest/`)
- `server.js` — in-container scheduler (06:30 IST) + `POST /digest/run` + `POST /ask` + `GET /health`.
- `Dockerfile` — `node:22-slim` + Claude CLI, runs as non-root.
- Added to `docker-compose.yml` as service `trade-digest`, published on host `:9091`.

### TradeEngine (Python — additive, activates on next `te_app` rebuild)
- `config.py` — `DIGEST_URL` / `DIGEST_API_KEY` / `DIGEST_TIMEOUT`.
- `src/gateway/client.py` — new `gateway_research()` fail-soft helper for the `/ask` endpoint.
- **Existing calls need no change** — they already leverage the digest via the server-side default.

## 4. Model policy (enforced)

Haiku by default, Sonnet allowed, **Opus and higher rejected**. Enforced in the sidecar
(`resolveModel`, returns HTTP 400 for anything other than `haiku`/`sonnet`) and in the digest
config (`DIGEST_MODEL: haiku`). Verified: `/ask` with `model=opus` → `400 not permitted`.

## 5. Activation — the one manual step

The digest is deployed but needs Claude credentials inside the container. Do this once:

```bash
# 1. On the host (interactive) — mints a long-lived token from your Max subscription:
claude setup-token
# copy the printed token, then add it to the compose env file:
echo 'CLAUDE_CODE_OAUTH_TOKEN=<paste-token-here>' >> /Users/harishkumargundameedi/Projects/services/polymind/.env
# (optional) protect the endpoints:
echo 'DIGEST_API_KEY=<choose-a-secret>' >> /Users/harishkumargundameedi/Projects/services/polymind/.env

# 2. Recreate the sidecar so it picks up the token:
cd /Users/harishkumargundameedi/Projects/services/polymind
docker compose up -d trade-digest

# 3. Trigger the first real digest now (don't wait for 06:30):
curl -s -X POST http://localhost:9091/digest/run | jq
# then confirm the pack populated:
curl -s -H "Authorization: Bearer pmk-admin-dev" \
     http://localhost:9090/v1/admin/knowledge/trade-engine | jq
```

Until then, `/health` shows `hasClaudeToken: false`, and `/ask` / `/digest/run` return `503`
with a clear message. **The pack is intentionally empty** — TradeEngine behaves exactly as
before (no augmentation) until the first real digest runs. It will never serve stale/fake data.

## 6. Endpoint reference

### Digest sidecar (`http://localhost:9091`, or `host.docker.internal:9091` from TradeEngine)
| Method | Path | Body | Purpose |
|---|---|---|---|
| GET | `/health` | — | status, token presence, model policy, schedule, last digest result |
| POST | `/digest/run` | `{ "model": "haiku" }` (optional) | generate the digest now + reindex |
| POST | `/ask` | `{ "question": "...", "model": "haiku\|sonnet", "index": false, "pack": "..." }` | ad-hoc Claude-CLI research; returns `{ model, ms, answer }` |

`Authorization: Bearer <DIGEST_API_KEY>` required on `/ask` and `/digest/run` when `DIGEST_API_KEY` is set.

### Polymind knowledge admin (`http://localhost:9090`, admin key required)
| Method | Path | Purpose |
|---|---|---|
| GET | `/v1/admin/knowledge/trade-engine` | pack status (`enabled`, `chunks`) |
| POST | `/v1/admin/knowledge/trade-engine/reindex` | replace pack contents (`[{source,text}]`; `[]` clears) |
| POST | `/v1/admin/knowledge/trade-engine/index` | append one document `{source,text}` |

## 7. How TradeEngine leverages it

Two mechanisms:

1. **Automatic (live now, no TradeEngine change):** every `gateway_generate` / `gateway_agent`
   call flows through Polymind's compat layer, which attaches the `trade-engine` pack. When the
   query is relevant to the digest, digest context is injected; otherwise the relevance gate
   injects nothing. This is why the running `te_app` already benefits.
2. **On-demand (activates on next `te_app` rebuild):** `gateway_research(question, model="haiku")`
   in `src/gateway/client.py` calls the `/ask` endpoint for live questions beyond the digest.
   Fail-soft — returns `None` on any error, so callers fall back exactly as they do today.

> To activate mechanism 2, rebuild `te_app` when convenient
> (`cd /Users/harishkumargundameedi/Projects/TradeEngine && docker compose up -d --build app`).
> Not done automatically here to avoid disrupting the live trading process. Mechanism 1 needs no rebuild.

## 8. Configuration reference

| Env (compose `.env`) | Default | Meaning |
|---|---|---|
| `CLAUDE_CODE_OAUTH_TOKEN` | — | Claude Max token from `claude setup-token`. Required for digest/`ask`. |
| `POLYMIND_ADMIN_KEY` | `pmk-admin-dev` | admin key for reindex |
| `DIGEST_API_KEY` | — | if set, protects `/ask` & `/digest/run` |
| `DIGEST_MODEL` | `haiku` | digest model (`haiku`\|`sonnet`) |
| `DIGEST_SCHEDULE` | `06:30,18:45` | comma-separated IST run times. Slots before 12:00 run with `india-open` focus (post-US-close, pre-NSE-open); later slots with `us-open` focus (post-NSE-close, pre-US-open: pre-market movers, US earnings/macro due, India wrap). Both sessions get fresh context. |
| `POLYMIND_COMPAT_KNOWLEDGE_PACK` | `trade-engine` | default pack for compat calls (`application-postgres.yaml`) |
| `POLYMIND_KNOWLEDGE_MIN_SCORE` | `0.35` | relevance gate threshold |

## 9. Verified during deployment

- Routing: `model=auto` → `qwen2.5-7b` (was 404 on `gemma2-9b`); forced unavailable model → clear 400.
- Model policy: `/ask model=opus` → 400 rejected; `haiku`/`sonnet` accepted.
- Augmentation: `/v1/generate` with no metadata returned a digest-grounded answer (specific
  earnings/macro items) while the pack held data; an off-topic (code) call stayed clean.
- Restart safety: pgvector attach-on-read makes an existing pack visible after a Polymind restart
  with no reindex.
- Full Java test suite green (`./gradlew test`).
- Mock demo data **cleared** — pack is empty pending the first real digest.

## 10. Tuning

- **Relevance gate too strict/loose?** Adjust `POLYMIND_KNOWLEDGE_MIN_SCORE`. Lower → more calls
  get context (risk: marginal matches); higher → only strong matches. Re-run the A/B in
  `docs/plans/nightly-research-digest.md` §Verification after changing.
- **Digest not thorough enough?** Bump `DIGEST_MODEL` to `sonnet` (still subscription-billed).
- **Watchlist:** edit the symbol list in `docker/digest/server.js` (`DIGEST_PROMPT`) and rebuild
  the sidecar (`docker compose up -d --build trade-digest`).
