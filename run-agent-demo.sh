#!/usr/bin/env bash
# =============================================================================
# Agent Runtime Harness Demo — SINGLE local launch entrypoint.
#
# Brings up ONLY the minimal Agent interaction Demo:
#   - Spring Boot backend  (http://127.0.0.1:8080) with the Demo service,
#     Store, Event Hub, Run Coordinator and the unique Agent Runtime Harness;
#   - Vite + React frontend (http://127.0.0.1:5174/#/agent-demo).
#
# Guarantees (Demo boundary):
#   * checks ONLY Java / Node / npm / curl exist (NO CMake / Docker / native SDK);
#   * starts backend + frontend (NO native probe, NO SimNow Gateway, NO MySQL,
#     NO Qdrant, NO order/cancel/recovery);
#   * uses an isolated Demo store at rebuild/.run/agent-demo.json;
#   * waits for GET /api/v1/health BEFORE announcing ready;
#   * prints the ONE browser URL;
#   * NEVER prints/reads/writes any credential value; only WARNs if the
#     DeepSeek key is absent (the UI still starts; MODEL_UNAVAILABLE).
#
# Designed for macOS 14+ arm64. Bash 3.2 compatible.
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

BACKEND_DIR="$SCRIPT_DIR/backend"
FRONTEND_DIR="$SCRIPT_DIR/frontend"
RUN_DIR="$SCRIPT_DIR/.run"
DEMO_STORE="$RUN_DIR/agent-demo.json"
BACKEND_LOG="$RUN_DIR/agent-demo-backend.log"
FRONTEND_LOG="$RUN_DIR/agent-demo-frontend.log"
BACKEND_PID=""
FRONTEND_PID=""

mkdir -p "$RUN_DIR"

# ---- tool checks (Demo subset only) ----------------------------------------
need() {
  command -v "$1" >/dev/null 2>&1 || { echo "missing required tool: $1" >&2; exit 1; }
}
need java
need mvn
need node
need npm
need curl

JAVA_MAJOR=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]\{1,\}\).*/\1/p' | head -1)
if [[ -z "$JAVA_MAJOR" || "$JAVA_MAJOR" -lt 17 ]]; then
  echo "Java 17+ required (found '${JAVA_MAJOR:-unknown}')" >&2
  exit 1
fi

# ---- DeepSeek key (warn only; never print the value) -----------------------
if [[ -z "${DEEPSEEK_API_KEY:-}" ]]; then
  echo "WARN: DEEPSEEK_API_KEY not set — the UI starts, but real model calls"
  echo "      return MODEL_UNAVAILABLE. Set it with: export DEEPSEEK_API_KEY=..."
  echo "      (the value is NEVER written to a file, log, HTTP body or the page.)"
else
  echo "DEEPSEEK_API_KEY is set — real DeepSeek calls are enabled."
fi

cleanup() {
  [[ -n "$FRONTEND_PID" ]] && kill "$FRONTEND_PID" 2>/dev/null || true
  [[ -n "$BACKEND_PID" ]] && kill "$BACKEND_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# ---- start backend ----------------------------------------------------------
echo "starting Spring backend (Demo)..."
( cd "$BACKEND_DIR" && mvn -q -DskipTests spring-boot:run \
    >"$BACKEND_LOG" 2>&1 ) &
BACKEND_PID=$!

# wait for health (poll; no `timeout` on macOS bash 3.2)
echo "waiting for backend health..."
DEADLINE=$(( $(date +%s) + 120 ))
READY=""
while [[ $(date +%s) -lt $DEADLINE ]]; do
  if curl -fsS http://127.0.0.1:8080/api/v1/health >/dev/null 2>&1; then
    READY=1
    break
  fi
  if ! kill -0 "$BACKEND_PID" 2>/dev/null; then
    echo "backend exited early — see $BACKEND_LOG" >&2
    exit 1
  fi
  sleep 2
done
if [[ -z "$READY" ]]; then
  echo "backend did not become healthy in 120s — see $BACKEND_LOG" >&2
  exit 1
fi
echo "backend ready."

# ---- start frontend ---------------------------------------------------------
echo "starting Vite frontend (Demo)..."
( cd "$FRONTEND_DIR" && npm run dev >"$FRONTEND_LOG" 2>&1 ) &
FRONTEND_PID=$!

# wait for vite
DEADLINE=$(( $(date +%s) + 60 ))
READY=""
while [[ $(date +%s) -lt $DEADLINE ]]; do
  if curl -fsS http://127.0.0.1:5174/ >/dev/null 2>&1; then
    READY=1
    break
  fi
  if ! kill -0 "$FRONTEND_PID" 2>/dev/null; then
    echo "frontend exited early — see $FRONTEND_LOG" >&2
    exit 1
  fi
  sleep 2
done
if [[ -z "$READY" ]]; then
  echo "frontend did not become ready in 60s — see $FRONTEND_LOG" >&2
  exit 1
fi

echo ""
echo "=========================================================================="
echo " Agent Runtime Demo is ready."
echo ""
echo "   Browser:   http://127.0.0.1:5174/#/agent-demo"
echo ""
echo "   Backend log:  $BACKEND_LOG"
echo "   Frontend log: $FRONTEND_LOG"
echo "   Demo store:   $DEMO_STORE"
echo ""
echo "   Demo 不连接 SimNow / CTP / Gateway；不产生任何交易行为。"
echo "   Ctrl-C to stop both processes."
echo "=========================================================================="

# keep the script alive; wait for either child
wait
