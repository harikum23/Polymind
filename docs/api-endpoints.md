# API Endpoint Reference — Polymind + Trade-Digest Sidecar

Companion to `docs/nightly-digest.md` (architecture/runbook). This page is the endpoint
contract: schemas, auth, errors, and copy-paste examples.

**Live, always-current sources of truth:**
- Polymind OpenAPI: `http://localhost:9090/swagger-ui.html` (interactive) / `/v3/api-docs` (JSON)
- Sidecar OpenAPI: `http://localhost:9091/openapi.yaml`

**Base URLs**

| Caller | Polymind | Digest sidecar |
|---|---|---|
| Host (Mac) | `http://localhost:9090` | `http://localhost:9091` |
| TradeEngine containers (separate network) | `http://host.docker.internal:9090` | `http://host.docker.internal:9091` |
| Same compose network | `http://polymind:9090` | `http://trade-digest:9091` |

---

## 1. Digest sidecar (`:9091`)

Auth: `Authorization: Bearer <DIGEST_API_KEY>` on `POST` routes **only when** `DIGEST_API_KEY`
is set in the compose env; otherwise open. Model policy on every route that runs Claude:
`haiku` (default) or `sonnet`; anything else → `400`.

### GET /health
No auth. Returns service status:

```json
{
  "status": "ok",
  "hasClaudeToken": true,
  "pack": "trade-engine",
  "allowedModels": ["haiku", "sonnet"],
  "defaultModel": "haiku",
  "schedule": "06:30(india-open), 18:45(us-open) Asia/Kolkata",
  "lastDigest": { "at": "2026-07-22T20:21:27.213Z", "ok": true, "focus": "india-open",
                  "sections": 5, "chunks": 11, "error": null }
}
```

`hasClaudeToken: false` means `/ask` and `/digest/run` will return `503` until
`CLAUDE_CODE_OAUTH_TOKEN` is set (see runbook §5).

### POST /digest/run
Generate a digest now and **replace** the knowledge pack with it. Synchronous (1–5 min).

Request (all fields optional):
```json
{ "model": "haiku", "focus": "us-open" }
```
`focus`: `india-open` (default — post-US-close, pre-NSE-open emphasis) or `us-open`
(pre-US-open: pre-market movers, US earnings/macro due, India wrap).

Response `200`: the `lastDigest` object (above). Errors: `400` disallowed model ·
`401` bad/missing bearer · `502` Claude CLI failed · `503` no credentials · `504` timeout (15 min).

```bash
curl -s -X POST http://localhost:9091/digest/run \
     -H 'Content-Type: application/json' -d '{"focus":"us-open"}' | jq
```

### POST /ask
Ad-hoc Claude-CLI research with live web search. This is what TradeEngine's
`gateway_research()` calls.

Request:
```json
{
  "question": "Did the Fed speaker today move rate-cut odds?",   // required
  "model": "haiku",          // optional: haiku | sonnet
  "index": false,            // optional: persist answer into the pack
  "pack": "trade-engine",    // optional: target pack when index=true
  "source": "fed-check.md"   // optional: source label when index=true
}
```

Response `200`:
```json
{ "model": "haiku", "ms": 42180, "answer": "…", "indexed": null }
```
Errors: same set as `/digest/run` (`/ask` CLI timeout is 5 min).

```bash
curl -s -X POST http://localhost:9091/ask \
     -H 'Content-Type: application/json' \
     -d '{"question":"NVDA pre-market movers and why?"}' | jq -r .answer
```

### GET /openapi.yaml
The machine-readable OpenAPI 3 spec for this service (also checked in at
`docker/digest/openapi.yaml`).

---

## 2. Polymind gateway-compat (`:9090`, the endpoints TradeEngine speaks)

Auth: `Authorization: Bearer <key>` (admin key or a tenancy API key) when
`POLYMIND_AUTH_ENABLED=true`.

### POST /v1/generate
Legacy gemma-gateway shape, non-streaming chat.

```json
{
  "messages": [{ "role": "user", "content": "…" }],
  "max_tokens": 256,
  "model": "qwen2.5-7b",            // optional; omitted = compat default model
  "temperature": 0.2, "top_p": 0.9, // optional
  "metadata": {                      // OPTIONAL — new, additive
    "knowledge_pack": "trade-engine",  // override the server default pack
    "web_search": true,                // inject live SearXNG results
    "task": "chat",                    // routing hint: chat|code|math|reasoning
    "force": false
  }
}
```

**Augmentation semantics (new):** when `polymind.gateway-compat.knowledge-pack` is configured
(it is: `trade-engine`), every call gets that pack **by default — no metadata needed**.
Retrieved chunks are injected only when they clear the relevance gate
(`polymind.knowledge.min-score` — code default 0.35; deployed at **0.55** via compose, verified
to block off-topic calls like JSON-only prompts while keeping research calls augmented), so
validators/formatters receive no context. Per-request `metadata.knowledge_pack` overrides the
default pack.

Response: `{ "content", "text", "model", "tokens_in", "tokens_out", "finish_reason" }`.

### POST /v1/agent
Legacy agent shape — runs a SearXNG web search on the last user message, then answers
grounded in the results. Accepts the same optional `metadata` (digest context stacks on top
of live search results).

Response: `{ "content", "text", "steps": [...], "sources": [...], "tokens_in", "tokens_out" }`.

### Errors worth knowing
- `400 invalid_request_error` — includes the new clear message for a forced-but-unavailable
  model: `"Model 'X' is registered but not currently available on engine 'ollama'"`.
- `502 engine_error` — engine-side failure (e.g. Ollama down).
- `auto`/category routing never selects an unavailable model (availability filter), so the
  old opaque `Ollama chat failed: HTTP 404` path is gone.

---

## 3. Polymind knowledge admin (`:9090`, admin key only)

| Method | Path | Body | Effect |
|---|---|---|---|
| GET | `/v1/admin/knowledge/{pack}` | — | `{ "enabled", "pack", "chunks" }` |
| POST | `/v1/admin/knowledge/{pack}/reindex` | `[{ "source", "text" }, …]` | **replace** the pack (empty array `[]` clears it) |
| POST | `/v1/admin/knowledge/{pack}/index` | `{ "source", "text" }` | **append** one document |

```bash
# pack status
curl -s -H "Authorization: Bearer $POLYMIND_ADMIN_KEY" \
     http://localhost:9090/v1/admin/knowledge/trade-engine | jq
# clear the pack
curl -s -X POST -H "Authorization: Bearer $POLYMIND_ADMIN_KEY" \
     -H 'Content-Type: application/json' -d '[]' \
     http://localhost:9090/v1/admin/knowledge/trade-engine/reindex | jq
```

---

## 4. TradeEngine integration cheat-sheet

| Need | Call | Notes |
|---|---|---|
| Normal research/LLM call with digest context | `gateway_generate()` / `gateway_agent()` — unchanged | Default pack applies server-side; relevance gate keeps off-topic calls clean |
| Force a specific pack per call | pass `metadata={"knowledge_pack": "…"}` in the payload | compat layer forwards it (overrides default) |
| Live question beyond the digest | `gateway_research(question, model="haiku")` (`src/gateway/client.py`) | hits sidecar `/ask`; fail-soft → `None` on any error |
| Health-gate the sidecar | `GET http://host.docker.internal:9091/health` | check `hasClaudeToken` before relying on `/ask` |
