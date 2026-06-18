---
name: skill-mcp
stacks: [mcp]
applies_to_roles: [role.software_engineer, role.refactorer, role.bug_hunter, role.architect, role.test_writer]
---

When working with Model Context Protocol (MCP) in this project:

- Adapter pattern: open an MCP session through the daemon's pool —
  `pool.Get(ctx, serverID)` returns a connection; never construct a
  `*mcp.Client` by hand. The pool handles handshake, capability
  negotiation, and re-dial on transport failure.
- After `Get`, always call `Connect(ctx)` before issuing tool calls.
  Connect is idempotent on an already-active session but cheap, and
  callers that skip it hit "method called before initialize" errors
  from spec-compliant servers.
- Tool wire names follow `mcp__<server>__<tool>`. The server segment is
  the operator-facing server ID (from `.agentflow/mcp.json`); the tool
  segment is whatever the server's `tools/list` advertises. Keep the
  double-underscore — single underscores collide with built-in tool
  names.
- Schemas from upstream servers are JSON-Schema with vendor-specific
  extensions. Sanitize before forwarding to the model: drop
  `additionalProperties: false` on top-level objects (most providers
  reject it), coerce unknown formats to `string`, strip
  `$schema` and `definitions` refs the model doesn't follow.
- Trust gate: `.agentflow/mcp.json` is project-scoped — the daemon's
  trust gate decides whether to expose a discovered server based on
  the user's per-project trust list. Don't bypass it.
- Pagination: `tools/list` and `resources/list` may return a `nextCursor`.
  Loop until the cursor is empty; merging partial pages silently drops
  tools.
