#!/bin/sh
# Requires explicit isolated integration environment; never source or print credentials here.
set -eu
cd "$(dirname "$0")/.."
: "${EXPERT_DB_URL:?Set the dedicated test PostgreSQL URL}"
: "${EXPERT_DB_USER:?Set the dedicated test PostgreSQL user}"
test_filter="${PRODUCTION_ACCEPTANCE_TEST:-ProductionAcceptanceTest}"
exec "${MAVEN_BIN:-/Users/hou/.local/apache-maven-3.9.16/bin/mvn}" -o \
  -Dmaven.repo.local="${EXPERT_MAVEN_REPO:-/Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/ailiao-upgrade/backend/.local/m2}" \
  -Dtest="$test_filter" test
