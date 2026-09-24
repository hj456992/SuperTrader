#!/bin/zsh
set -euo pipefail
cd "${0:A:h}"
maven_path="${MAVEN_BIN:-/Users/hou/.local/apache-maven-3.9.16/bin/mvn}"
"$maven_path" -q package
"$maven_path" -q -f plugins/feishu-history/pom.xml package
npm --prefix web ci --no-audit --no-fund
mkdir -p web/dist/vendor
npm --prefix web run build
