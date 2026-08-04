#!/usr/bin/env bash
# =============================================================================
# Module 2 — SINGLE local stop entrypoint.
#
# Stops ONLY the processes this module started (backend + frontend), identified
# by the PID files written by run-local.sh. It does NOT kill by port, by binary
# name or by `pkill -f java/node` — so it cannot touch the user's unrelated
# Java, Node or Docker processes. It deletes NO data, SDK, credential or result.
#
# Safety: before signalling a pid, it confirms via `ps` that the process command
# line belongs to THIS module (our jar / our vite). A recycled pid that now
# points at an unrelated process is left untouched.
#
# Usage:
#   ./stop-local.sh           stop backend + frontend (logs kept)
#   ./stop-local.sh --purge   remove only this launcher's logs/pids; business data stays
# =============================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

RUN_DIR="$SCRIPT_DIR/.run"
BACKEND_PID_FILE="$RUN_DIR/backend.pid"
FRONTEND_PID_FILE="$RUN_DIR/frontend.pid"
FRONTEND_DIR="$SCRIPT_DIR/frontend"

PURGE=0
[[ "${1:-}" == "--purge" ]] && PURGE=1

log()  { printf '\033[1;34m==> %s\033[0m\n' "$*"; }
ok()   { printf '\033[1;32m    ✓ %s\033[0m\n' "$*"; }
note() { printf '\033[1;33m    ! %s\033[0m\n' "$*"; }

alive() { [[ -n "${1:-}" ]] && kill -0 "$1" 2>/dev/null; }

# Is this pid one WE launched? Verify the command line matches our module so a
# recycled pid pointing at an unrelated process is never signalled.
pid_is_ours() {
  local pid="$1" kind="$2" cmd
  alive "$pid" || return 1
  cmd="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  [[ -z "$cmd" ]] && return 1
  case "$kind" in
    backend)
      [[ "$cmd" == *"supertrader-demo-backend"* ]] || return 1 ;;
    frontend)
      [[ "$cmd" == *"$FRONTEND_DIR"* ]] || [[ "$cmd" == *"vite"* ]] || return 1 ;;
    *) return 1 ;;
  esac
  return 0
}

# stop_pidfile <pidfile> <kind> <label>: graceful TERM, then KILL if needed.
stop_pidfile() {
  local pidfile="$1" kind="$2" label="$3"
  if [[ ! -f "$pidfile" ]]; then
    note "无 $label pid 文件（未启动或已停止）"
    return 0
  fi
  local pid
  pid="$(cat "$pidfile" 2>/dev/null || true)"
  if [[ -z "$pid" ]]; then
    note "$label pid 文件为空"
    rm -f "$pidfile"
    return 0
  fi
  if ! alive "$pid"; then
    ok "$label 已不在运行 (pid $pid)"
    rm -f "$pidfile"
    return 0
  fi
  if ! pid_is_ours "$pid" "$kind"; then
    note "pid $pid 不属于本模块的 $label（命令行不匹配），跳过，避免误杀无关进程"
    rm -f "$pidfile"
    return 0
  fi
  log "停止 $label (pid $pid)：SIGTERM"
  kill -TERM "$pid" 2>/dev/null || true
  # Poll up to 15s for graceful exit (bash 3.2: no `timeout`, use a loop).
  local waited=0
  while alive "$pid"; do
    sleep 1
    waited=$((waited + 1))
    [[ "$waited" -ge 15 ]] && break
  done
  if alive "$pid"; then
    note "$label 未在 15s 内退出，发送 SIGKILL"
    kill -KILL "$pid" 2>/dev/null || true
    # reap
    waited=0
    while alive "$pid"; do
      sleep 1
      waited=$((waited + 1))
      [[ "$waited" -ge 5 ]] && break
    done
  fi
  if alive "$pid"; then
    note "$label 进程 pid $pid 仍未退出，请手动检查"
  else
    ok "$label 已停止 (pid $pid)"
  fi
  rm -f "$pidfile"
}

log "停止本地整链（仅本模块启动的进程，不触碰无关 Java/Node/Docker）"
[[ -d "$RUN_DIR" ]] || { note "无 .run/ 目录，无内容可停止"; exit 0; }

stop_pidfile "$BACKEND_PID_FILE"  backend  "后端"
stop_pidfile "$FRONTEND_PID_FILE" frontend "前端"

if [[ "$PURGE" -eq 1 ]]; then
  log "--purge：清理运行目录 ${RUN_DIR}（仅日志与 pid，不删任何数据/SDK/凭证/结果）"
  rm -f "${RUN_DIR}"/backend.log "${RUN_DIR}"/frontend.log "${RUN_DIR}"/backend.pid "${RUN_DIR}"/frontend.pid
  ok "已清理日志与 pid；保留 ${RUN_DIR} 及其中业务数据"
else
  note "日志保留于 $RUN_DIR/*.log 供排查（如需清理：./stop-local.sh --purge）"
fi

echo ""
ok "停止完成。未删除任何数据、SDK、凭证或诊断结果。"
