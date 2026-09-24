#!/bin/zsh
set -euo pipefail
cd "${0:A:h}"
maven_path="${MAVEN_BIN:-/Users/hou/.local/apache-maven-3.9.16/bin/mvn}"
"$maven_path" -q package -DskipTests
npm --prefix web ci --no-audit --no-fund
npm --prefix web run build
