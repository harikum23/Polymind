# Plan: Nightly Trade Research Digest (Digest-Only, $0/mo)

> Status: **IMPLEMENTED & DEPLOYED 2026-07-21.** See `docs/nightly-digest.md` for the
> as-built architecture, runbook, and the one remaining manual step (`claude setup-token`).
>
> Deltas from this plan discovered during implementation (all in the runbook):
> - The digest scheduler is an **in-container Node service** (not supercronic) — still Docker-only.
> - Added **4 Polymind fixes** the plan's "zero Java changes" assumption missed: model-availability
>   routing filter (compat calls were 404-ing on `gemma2-9b`), a knowledge **relevance gate**, a
>   **compat-layer default pack** (so TradeEngine needs no code change), and a **pgvector
>   attach-on-read** fix (knowledge went dark after restarts).
> - Added the **`/ask` ad-hoc Claude-CLI endpoint** and a TradeEngine `gateway_research()` helper.

## Ground rules (user-mandated)
1. **All schedulers run in Docker, never on the Mac.** No launchd, no host cron. The
   digest scheduler is a compose service in Polymind's existing `docker-compose.yml`
   stack.
2. **Model policy: Haiku or Sonnet only.** Digest runs default to `--model haiku`;
   escalate to `--model sonnet` only if digest quality proves insufficient. **Never
   Opus (or any higher tier)** unless the user explicitly asks for it. This applies to
   every Claude invocation this plan introduces.

## Why this approach is helpful
1. **$0 marginal cost.** The digest runs headless on the Claude Max subscription
   (`claude -p`), so the expensive research synthesis is prepaid. No Anthropic API key,
   no per-token billing.
2. **One expensive computation, reused all day.** Research is generated once per night
   and served to every TradeEngine call via RAG — instead of paying (money or latency)
   per request.
3. **Raises the local-model quality floor.** Local Gemma/Qwen answering *with* a
   Claude-written digest injected as context is far stronger than answering cold.
4. **Lower latency for ~70% of research calls.** Digest-served queries skip any hosted
   round trip: local pgvector retrieval + local generation.
5. **Zero new code in Polymind.** The RAG layer is already fully implemented
   (`knowledge` module + `PgVectorStore` + admin reindex endpoint); this feature is
   configuration + a sidecar container, and exercises the knowledge layer end-to-end —
   one of Polymind's stated exit criteria (ARCHITECTURE.md §0).
6. **Right freshness semantics.** Nightly `reindex` replaces yesterday's pack wholesale;
   every digest file carries a `digest-date` header so answers can state their as-of time.

## Context
TradeEngine consumes Polymind (OpenAI-compatible router, :8090) for research. Decision:
pre-compute research nightly with Claude Code CLI on the Max subscription, index into
Polymind's `trade-engine` knowledge pack, serve research queries via local Ollama + RAG.
Live-info queries continue to use `metadata.web_search: true` (SearXNG).

The RAG layer is fully implemented and gated by `polymind.knowledge.enabled=true`
(set by the `postgres` profile in `application-postgres.yaml`). Ingestion endpoint:
`POST /v1/admin/knowledge/{pack}/reindex` (`web/admin/AdminKnowledgeController.java`).
Retrieval triggers via `metadata.knowledge_pack`
(`routing/RoutingChatOrchestrator.applyKnowledge`).

## Implementation

### 1. Digest service assets — `docker/digest/` (new, in repo)
- `Dockerfile` — `node:22-slim` base; installs `@anthropic-ai/claude-code` (npm global),
  `jq`, `curl`, `ca-certificates`, and `supercronic` (container-friendly cron). Copies
  the crontab and scripts below.
- `crontab` — `30 6 * * *  /app/run-digest.sh` (container `TZ=Asia/Kolkata` → 06:30 IST,
  post-US-close, pre-NSE-open).
- `run-digest.sh` —
  1. Runs `claude -p "$(cat /app/prompts/nightly-digest.md) ..." --model haiku
     --dangerously-skip-permissions` (headless; **haiku per model policy**).
  2. Deterministic ingestion (script, not the agent): `jq`-builds
     `[{source, text}, ...]` from `/data/out/$(date +%F)/*.md`, POSTs to
     `http://polymind:8090/v1/admin/knowledge/trade-engine/reindex` over the compose
     network (Bearer `POLYMIND_ADMIN_KEY` when auth enabled).
  3. Logs to `/data/logs/`; non-zero exit on failure (no files, or reindex non-2xx).
- `prompts/nightly-digest.md` — digest instructions. Output files per run:
  `us-wrap.md`, `calendar.md`, `sectors.md`, `watchlist-notes.md`, `risks.md`; each
  opens with `<!-- digest-date: YYYY-MM-DD HH:MM IST -->`; short bullets, sources named
  inline, ≤~600 words/file; unattended (never ask questions).
- `watchlist.md` — user-maintained symbol list (US + NSE), mounted into the container.

### 2. Compose service — add to `docker-compose.yml`
```yaml
trade-digest:
  build: ./docker/digest
  environment:
    TZ: Asia/Kolkata
    CLAUDE_CODE_OAUTH_TOKEN: ${CLAUDE_CODE_OAUTH_TOKEN}   # from `claude setup-token` (Max subscription)
    POLYMIND_URL: http://polymind:8090
    POLYMIND_ADMIN_KEY: ${POLYMIND_ADMIN_KEY:-}
  volumes:
    - digest-data:/data                                   # out/ + logs/
    - ./docker/digest/watchlist.md:/app/watchlist.md:ro
  depends_on: [polymind]
  restart: unless-stopped
```
Auth note: `claude setup-token` (run once on the Mac, interactive) mints a long-lived
OAuth token tied to the Max subscription; it goes in the compose `.env` file so the
container needs no mounted home directory. Keep `.env` gitignored.

### 3. Polymind operational prerequisites (no code edits)
- Stack runs with `SPRING_PROFILES_ACTIVE=postgres` (pgvector; enables knowledge layer).
- Ollama has `nomic-embed` pulled (verified present on host Ollama).

### 4. TradeEngine opt-in
`metadata: {knowledge_pack: "trade-engine"}` on `/v1/chat/completions`. Live queries
keep using `metadata.web_search: true`. Document both in `docs/nightly-digest.md`
runbook (created during implementation).

## Reused existing components (no changes)
- `KnowledgeService.reindex` via `AdminKnowledgeController` — ingestion
- `RoutingChatOrchestrator.applyKnowledge` + `metadata.knowledge_pack` — retrieval
- `PgVectorStore` (postgres profile) — storage
- Ollama `nomic-embed` via `EngineRegistry` — embeddings

## Verification
1. One-off run: `docker compose run --rm trade-digest /app/run-digest.sh` → md files in
   the `digest-data` volume, exit 0.
2. `GET /v1/admin/knowledge/trade-engine` (admin key) → `enabled: true`, chunks > 0.
3. End-to-end: `POST /v1/chat/completions` with `model: "auto"`,
   `metadata: {knowledge_pack: "trade-engine"}`, ask "What's on today's earnings
   calendar?" → answer reflects digest content and its date.
4. Scheduler: `docker compose logs trade-digest` next morning shows the 06:30 IST run;
   fresh `out/<date>/` in the volume.

## Cost
$0/month incremental — digest generation on the Max subscription (haiku), embedding/
storage/serving all local. Model policy above keeps any future escalation at Sonnet
pricing at most.
