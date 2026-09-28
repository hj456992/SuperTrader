#!/bin/zsh
set -euo pipefail
cd "${0:A:h}"
export MAVEN_OPTS="${MAVEN_OPTS:-} -Dfile.encoding=UTF-8"
repo="${EXPERT_MAVEN_REPO:-/Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/ailiao-upgrade/backend/.local/m2}"
"${MAVEN_BIN:-/Users/hou/.local/apache-maven-3.9.16/bin/mvn}" -o -q -Dmaven.repo.local="$repo" package
