#!/bin/sh
set -eu
cd "$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"
MAVEN_BIN="${MAVEN_BIN:-/Users/hou/.local/apache-maven-3.9.16/bin/mvn}"
case "${1:-baseline}" in
  baseline)
    "$MAVEN_BIN" test
    node --test web/src/*.test.js tests/acceptance/*.test.mjs
    ;;
  gates)
    "$MAVEN_BIN" -Dtest=ProfileAgentAcceptanceGates test
    ;;
  *) echo 'Usage: tests/acceptance/run.sh [baseline|gates]' >&2; exit 2 ;;
esac
