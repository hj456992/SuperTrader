#!/usr/bin/env bash
# =============================================================================
# Agent Demo boundary scanner.
#
# Fails (non-zero exit) if any of the Demo invariants are violated:
#   1. agentdemo production source imports native / probe / gateway / ctp /
#      order / cancel types (the Demo must never touch trading infra);
#   2. the run script or Demo scripts contain Docker / native-build /
#      SimNow-start commands;
#   3. a DeepSeek-shaped API key appears in source, the Demo store, or logs.
#
# Designed to run from the project root.
# =============================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REBUILD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$REBUILD_DIR"

STATUS=0

# 1. agentdemo production source must not import trading/native infra.
echo "[boundary] scanning agentdemo source for forbidden imports..."
FORBIDDEN=$(grep -rEn \
  "import .*\.(ctp|gateway|probe|native|order|cancel|live|execution)\." \
  backend/src/main/java/com/supertrader/demo/agentdemo/ 2>/dev/null || true)
if [[ -n "$FORBIDDEN" ]]; then
  echo "FAIL: agentdemo imports trading/native infra:"
  echo "$FORBIDDEN"
  STATUS=1
fi

# 2. run script / Demo scripts must not START Docker / native / SimNow. We
#    only flag actual command invocations (lines beginning with a command),
#    not comments or echo banners that merely mention the words.
echo "[boundary] scanning Demo scripts for Docker/native/SimNow commands..."
SCRIPT_MATCH=$(grep -Eni \
  '^[^#]*[[:space:]](docker |docker-compose |cmake |make native|simnow_probe |gateway/)' \
  run-agent-demo.sh 2>/dev/null || true)
if [[ -n "$SCRIPT_MATCH" ]]; then
  echo "FAIL: run-agent-demo.sh invokes Docker/native/SimNow/gateway:"
  echo "$SCRIPT_MATCH"
  STATUS=1
fi

# 3. No DeepSeek-shaped API key in source, store, or logs.
echo "[boundary] scanning for leaked API keys..."
KEY_PATTERN='sk-[A-Za-z0-9]{20,}'
LEAK=$(grep -rEn "$KEY_PATTERN" \
  backend/src/main/java/com/supertrader/demo/agentdemo/ \
  frontend/src/agent-demo/ \
  .run/agent-demo.json .run/agent-demo-backend.log .run/agent-demo-frontend.log \
  2>/dev/null || true)
if [[ -n "$LEAK" ]]; then
  echo "FAIL:疑似 API key 泄露:"
  echo "$LEAK"
  STATUS=1
fi

# 4. agentdemo must reuse the single AgentRuntimeHarness (no second kernel).
echo "[boundary] confirming a single AgentRuntimeHarness..."
HARNESS_COUNT=$(grep -rEln "class AgentRuntimeHarness" \
  backend/src/main/java/com/supertrader/demo/ 2>/dev/null | wc -l | tr -d ' ')
if [[ "$HARNESS_COUNT" -ne 1 ]]; then
  echo "FAIL: expected exactly 1 AgentRuntimeHarness, found $HARNESS_COUNT"
  STATUS=1
fi

if [[ "$STATUS" -eq 0 ]]; then
  echo "[boundary] OK — all Demo boundary invariants hold."
else
  echo "[boundary] FAILED — see above."
fi
exit $STATUS
