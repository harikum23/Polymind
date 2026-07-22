'use strict';
/**
 * Polymind Trade-Digest service.
 *
 * Two jobs, both driven by the Claude CLI on the user's Max subscription ($0 marginal):
 *   1. Nightly digest  — scheduled in-container (satisfies "schedulers in Docker, not the Mac"),
 *      generates a pre-market research digest and reindexes it into Polymind's knowledge pack.
 *   2. Ad-hoc research — POST /ask runs the Claude CLI for a one-off live question and returns the
 *      answer (optionally indexing it). This is the "trigger Claude CLI for any needed questions"
 *      endpoint; TradeEngine reaches it at host.docker.internal:9091.
 *
 * MODEL POLICY (hard-enforced): Haiku by default, Sonnet allowed, Opus (and anything else) rejected.
 */

const http = require('http');
const fs = require('fs');
const path = require('path');
const { spawn } = require('child_process');

const PORT = parseInt(process.env.PORT || '9091', 10);
const POLYMIND_URL = process.env.POLYMIND_URL || 'http://polymind:9090';
const ADMIN_KEY = process.env.POLYMIND_ADMIN_KEY || '';
const PACK = process.env.DIGEST_PACK || 'trade-engine';
const API_KEY = process.env.DIGEST_API_KEY || ''; // if set, /ask & /digest/run require it
const DEFAULT_MODEL = (process.env.DIGEST_MODEL || 'haiku').toLowerCase();
// Comma-separated IST times. Two runs by default so BOTH trading sessions get fresh context:
//   06:30 IST -> pre-NSE-open (post-US-close)   | 18:45 IST -> pre-US-open (post-NSE-close).
// Slots before 12:00 get the "india-open" focus, later slots the "us-open" focus.
const SCHEDULE = (process.env.DIGEST_SCHEDULE
  || `${process.env.DIGEST_SCHEDULE_HOUR || '6'}:${process.env.DIGEST_SCHEDULE_MINUTE || '30'},18:45`)
  .split(',')
  .map(s => s.trim())
  .filter(Boolean)
  .map(s => {
    const [h, m] = s.split(':').map(n => parseInt(n, 10));
    return { h, m: m || 0, focus: h < 12 ? 'india-open' : 'us-open' };
  });
const HAS_TOKEN = !!process.env.CLAUDE_CODE_OAUTH_TOKEN || !!process.env.ANTHROPIC_API_KEY;

// --- model policy -----------------------------------------------------------
const ALLOWED_MODELS = new Set(['haiku', 'sonnet']);
function resolveModel(requested) {
  const m = (requested || DEFAULT_MODEL).toLowerCase();
  if (!ALLOWED_MODELS.has(m)) {
    const err = new Error(
      `model '${m}' is not permitted. Allowed: haiku, sonnet. ` +
      `Opus and other tiers are blocked by policy.`);
    err.statusCode = 400;
    throw err;
  }
  return m;
}

// --- Claude CLI runner ------------------------------------------------------
function runClaude(prompt, model, timeoutMs) {
  return new Promise((resolve, reject) => {
    if (!HAS_TOKEN) {
      const e = new Error(
        'No Claude credentials in container. Run `claude setup-token` on the host and set ' +
        'CLAUDE_CODE_OAUTH_TOKEN in the compose .env, then restart trade-digest.');
      e.statusCode = 503;
      return reject(e);
    }
    const args = ['-p', '--model', model, '--dangerously-skip-permissions'];
    const child = spawn('claude', args, { env: process.env });
    let out = '';
    let err = '';
    const timer = setTimeout(() => {
      child.kill('SIGKILL');
      const e = new Error(`Claude CLI timed out after ${timeoutMs}ms`);
      e.statusCode = 504;
      reject(e);
    }, timeoutMs);
    child.stdout.on('data', (d) => { out += d.toString(); });
    child.stderr.on('data', (d) => { err += d.toString(); });
    child.on('error', (e) => { clearTimeout(timer); reject(e); });
    child.on('close', (code) => {
      clearTimeout(timer);
      if (code === 0) resolve(out.trim());
      else {
        const e = new Error(`Claude CLI exited ${code}: ${err.slice(0, 500)}`);
        e.statusCode = 502;
        reject(e);
      }
    });
    child.stdin.write(prompt);
    child.stdin.end();
  });
}

// --- Polymind knowledge-pack calls -----------------------------------------
async function polymind(path, body) {
  const headers = { 'Content-Type': 'application/json' };
  if (ADMIN_KEY) headers['Authorization'] = 'Bearer ' + ADMIN_KEY;
  const r = await fetch(POLYMIND_URL + path, {
    method: 'POST', headers, body: JSON.stringify(body),
  });
  const text = await r.text();
  if (!r.ok) throw new Error(`Polymind ${path} HTTP ${r.status}: ${text.slice(0, 300)}`);
  return text ? JSON.parse(text) : {};
}

// --- digest generation ------------------------------------------------------
const DIGEST_PROMPT = `You are producing a nightly pre-market research digest for an automated trading system.
US markets have closed; India's NSE opens at 09:15 IST. Use web search for CURRENT data
(prices, news, calendars) — do not answer time-sensitive items from memory.

Watchlist (produce one entry per symbol under the watchlist section):
US: NVDA (NASDAQ), SPY (NYSE)
India: RELIANCE (NSE), HDFCBANK (NSE), NIFTY50 (NSE)

Produce EXACTLY these five sections. Emit each section preceded by its delimiter line on its own
line, in this exact order, and NOTHING outside the sections:

===FILE: us-wrap.md===
US session wrap (index closes with levels, notable movers + why, after-hours earnings) and overnight
global (GIFT Nifty, US futures, dollar index, USDINR, crude, gold, key Asian markets).

===FILE: calendar.md===
Today's calendar for both markets: earnings due (US + India) and macro releases with times in IST,
central-bank speakers, F&O expiry if applicable.

===FILE: sectors.md===
Sector performance and rotation; cross-market read-through (IT<->Nasdaq, metals<->China, energy<->crude,
banks<->rates).

===FILE: watchlist-notes.md===
One subsection per watchlist symbol: latest price action, fresh news since yesterday, upcoming
catalysts, widely-cited technical levels. Mark "No new developments" where none.

===FILE: risks.md===
Known upcoming catalysts / risk events over the next ~5 trading days (Fed/RBI dates, CPI, expiry,
geopolitics, scheduled data).

Rules for every section:
- Start the body with an HTML comment: <!-- digest-date: YYYY-MM-DD HH:MM IST --> using the real
  current IST date/time, then a # heading.
- Short factual bullets with concrete numbers. Name the source inline in parentheses, e.g. (Reuters).
- Spell out abbreviations on first use. Keep each section under ~600 words. Mark unverifiable data
  "unverified" rather than guessing.
Work unattended: batch your searches, then output all five sections. Do not ask questions.
CRITICAL: do NOT create or write any files and do not describe what you did — PRINT the five
delimited sections themselves to stdout as your entire response.`;

function parseSections(text) {
  // Split on "===FILE: name===" delimiters into {source, text} docs.
  const re = /^===FILE:\s*(.+?)\s*===\s*$/gm;
  const docs = [];
  let m; const marks = [];
  while ((m = re.exec(text)) !== null) marks.push({ name: m[1].trim(), start: m.index, end: re.lastIndex });
  for (let i = 0; i < marks.length; i++) {
    const bodyStart = marks[i].end;
    const bodyEnd = i + 1 < marks.length ? marks[i + 1].start : text.length;
    const body = text.slice(bodyStart, bodyEnd).trim();
    if (body) docs.push({ source: marks[i].name, text: body });
  }
  return docs;
}

let lastDigest = { at: null, ok: null, focus: null, sections: 0, chunks: 0, error: null };

const FOCUS_NOTES = {
  'india-open':
    'SESSION FOCUS: this is the pre-NSE-open run (US markets just closed). Emphasize the completed ' +
    'US session wrap, overnight globals, and today\'s Indian session setup.',
  'us-open':
    'SESSION FOCUS: this is the pre-US-open run (Indian markets just closed). Emphasize US pre-market ' +
    '(futures, pre-market movers, US earnings and macro releases due in the coming US session) and a ' +
    'wrap of the just-closed Indian session. US calendar times may use ET with IST in parentheses.',
};

async function runDigest(model, focus) {
  const chosen = resolveModel(model);
  const slot = FOCUS_NOTES[focus] ? focus : 'india-open';
  log(`digest: generating via Claude CLI (model=${chosen}, focus=${slot})`);
  // Inject the authoritative timestamp — models mis-stamp dates when left to guess.
  const nowIst = new Date().toLocaleString('en-IN', {
    timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit',
    hour: '2-digit', minute: '2-digit', hour12: false,
  });
  const prompt = `Current IST date/time (authoritative — use EXACTLY this in every digest-date stamp): ${nowIst} IST\n\n`
    + FOCUS_NOTES[slot] + '\n\n' + DIGEST_PROMPT;
  const output = await runClaude(prompt, chosen, 15 * 60 * 1000);
  const docs = parseSections(output);
  if (docs.length === 0) throw new Error('digest produced no parseable sections');
  const res = await polymind(`/v1/admin/knowledge/${PACK}/reindex`, docs);
  lastDigest = { at: new Date().toISOString(), ok: true, focus: slot, sections: docs.length, chunks: res.chunks ?? null, error: null };
  log(`digest: reindexed ${docs.length} sections -> pack '${PACK}' (${res.chunks ?? '?'} chunks, focus=${slot})`);
  return lastDigest;
}

// --- scheduler (in-container; TZ=Asia/Kolkata so local hours are IST) -------
function nextRun() {
  const now = new Date();
  let best = null;
  for (const slot of SCHEDULE) {
    const next = new Date(now);
    next.setHours(slot.h, slot.m, 0, 0);
    if (next <= now) next.setDate(next.getDate() + 1);
    if (!best || next < best.when) best = { when: next, focus: slot.focus };
  }
  return best;
}
function scheduleNext() {
  const { when, focus } = nextRun();
  const delay = when - Date.now();
  log(`scheduler: next digest at ${when.toString()} (focus=${focus}, in ${Math.round(delay / 60000)} min)`);
  setTimeout(async () => {
    try { await runDigest(DEFAULT_MODEL, focus); }
    catch (e) { lastDigest = { at: new Date().toISOString(), ok: false, focus, sections: 0, chunks: 0, error: e.message }; log(`scheduler: digest FAILED: ${e.message}`); }
    scheduleNext();
  }, delay);
}

// --- HTTP helpers -----------------------------------------------------------
function log(msg) { console.log(`[${new Date().toISOString()}] ${msg}`); }
function send(res, code, obj) {
  const body = JSON.stringify(obj);
  res.writeHead(code, { 'Content-Type': 'application/json' });
  res.end(body);
}
function readBody(req) {
  return new Promise((resolve) => {
    let d = '';
    req.on('data', (c) => { d += c; });
    req.on('end', () => { try { resolve(d ? JSON.parse(d) : {}); } catch { resolve({}); } });
  });
}
function authorized(req) {
  if (!API_KEY) return true; // no key configured -> open (dev)
  const h = req.headers['authorization'] || '';
  return h === 'Bearer ' + API_KEY;
}

const ASK_PREAMBLE =
  'You are a markets research assistant for an automated trading system. Use web search for current ' +
  'data; be concise and factual, cite sources inline, and do not invent numbers. Question:\n\n';

// --- server -----------------------------------------------------------------
const server = http.createServer(async (req, res) => {
  try {
    if (req.method === 'GET' && req.url === '/health') {
      return send(res, 200, {
        status: 'ok', hasClaudeToken: HAS_TOKEN, pack: PACK,
        allowedModels: [...ALLOWED_MODELS], defaultModel: DEFAULT_MODEL,
        schedule: SCHEDULE.map(s =>
          `${String(s.h).padStart(2, '0')}:${String(s.m).padStart(2, '0')}(${s.focus})`).join(', ')
          + ` ${process.env.TZ || 'local'}`,
        lastDigest,
      });
    }

    if (req.method === 'POST' && req.url === '/digest/run') {
      if (!authorized(req)) return send(res, 401, { error: 'unauthorized' });
      const body = await readBody(req);
      const result = await runDigest(body.model, body.focus); // focus: "india-open" | "us-open"
      return send(res, 200, result);
    }

    if (req.method === 'POST' && req.url === '/ask') {
      if (!authorized(req)) return send(res, 401, { error: 'unauthorized' });
      const body = await readBody(req);
      if (!body.question || !String(body.question).trim()) {
        return send(res, 400, { error: 'missing "question"' });
      }
      const model = resolveModel(body.model);
      const t0 = Date.now();
      const answer = await runClaude(ASK_PREAMBLE + body.question, model, 5 * 60 * 1000);
      const ms = Date.now() - t0;
      // Optionally persist the answer into a knowledge pack for reuse.
      let indexed = null;
      if (body.index) {
        const pack = body.pack || PACK;
        const source = body.source || `ask-${Date.now()}.md`;
        try { indexed = await polymind(`/v1/admin/knowledge/${pack}/index`, { source, text: answer }); }
        catch (e) { indexed = { error: e.message }; }
      }
      return send(res, 200, { model, ms, answer, indexed });
    }

    if (req.method === 'GET' && req.url === '/openapi.yaml') {
      // Self-documenting: the machine-readable contract ships with the service.
      res.writeHead(200, { 'Content-Type': 'text/yaml' });
      return res.end(fs.readFileSync(path.join(__dirname, 'openapi.yaml')));
    }

    return send(res, 404, {
      error: 'not found',
      routes: ['GET /health', 'GET /openapi.yaml', 'POST /digest/run', 'POST /ask'],
    });
  } catch (e) {
    log(`request error: ${e.message}`);
    return send(res, e.statusCode || 500, { error: e.message });
  }
});

server.listen(PORT, () => {
  log(`trade-digest listening on :${PORT} | pack=${PACK} | model policy: ${[...ALLOWED_MODELS].join(', ')} (default ${DEFAULT_MODEL})`);
  log(`Claude credentials present: ${HAS_TOKEN}`);
  scheduleNext();
});
