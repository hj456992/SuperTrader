#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../../.." && pwd)
BIN="$ROOT/outputs/demo/.runtime/wechat-capture-native"

"$ROOT/outputs/demo/wechat/build-native.sh"

permission_json=$($BIN --permission)
PERMISSION_JSON="$permission_json" /usr/bin/python3 - <<'PY'
import json
import os

value = json.loads(os.environ["PERMISSION_JSON"])
assert set(value) == {"status", "granted"}, value
assert value["status"] == "ok", value
assert isinstance(value["granted"], bool), value
PY

self_test_json=$($BIN --self-test)
SELF_TEST_JSON="$self_test_json" /usr/bin/python3 - <<'PY'
import json
import os

value = json.loads(os.environ["SELF_TEST_JSON"])
assert value == {"status": "ok", "tests": 28, "failures": 0}, value
PY

set +e
missing_target_json=$($BIN --once)
missing_target_status=$?
set -e
MISSING_TARGET_JSON="$missing_target_json" MISSING_TARGET_STATUS="$missing_target_status" /usr/bin/python3 - <<'PY'
import json
import os

value = json.loads(os.environ["MISSING_TARGET_JSON"])
required = {
    "status", "windowId", "title", "width", "height",
    "chatLeft", "chatTop", "chatBottom", "lines",
}
assert os.environ["MISSING_TARGET_STATUS"] == "2"
assert set(value) == required, value
assert value["status"] == "capture_error", value
PY
