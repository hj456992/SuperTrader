#!/bin/sh
set -eu
base=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
python=${WECHAT_PYTHON:-/opt/homebrew/bin/python3.12}
umask 077
export PIP_CACHE_DIR="$base/.pip-cache"
"$python" -m venv "$base/.venv"
"$base/.venv/bin/python" -m pip install -r "$base/requirements.lock"
"$base/.venv/bin/python" -m pip install --no-deps -e "$base"
