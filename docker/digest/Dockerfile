# Polymind trade-digest sidecar: runs the nightly research digest (Claude CLI on the user's Max
# subscription) and serves the ad-hoc /ask endpoint. Scheduler runs in-container per project policy.
FROM node:22-slim

# CLI deps: curl/ca-certificates for HTTPS, plus the Claude Code CLI (provides `claude`).
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && npm install -g @anthropic-ai/claude-code \
    && npm cache clean --force

WORKDIR /app
COPY server.js ./server.js
COPY openapi.yaml ./openapi.yaml

# Persist Claude CLI state and digest data on a volume; run as the non-root `node` user so the
# CLI's --dangerously-skip-permissions is accepted (it refuses to run as root).
ENV TZ=Asia/Kolkata \
    NODE_ENV=production \
    CLAUDE_CONFIG_DIR=/data/claude \
    PORT=9091
RUN mkdir -p /data/claude && chown -R node:node /app /data
USER node

EXPOSE 9091
CMD ["node", "server.js"]
