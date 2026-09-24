#!/bin/zsh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
WORKSPACE_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
SOURCE_DIR="$SCRIPT_DIR/native"
RUNTIME_DIR="$SCRIPT_DIR/../.runtime"
APP_BUNDLE="$RUNTIME_DIR/聊天日志.app"
APP_CONTENTS="$APP_BUNDLE/Contents"
APP_MACOS="$APP_CONTENTS/MacOS"
APP_RESOURCES="$APP_CONTENTS/Resources"
APPEX_BUNDLE="$APP_CONTENTS/PlugIns/存入卧底不追高日志.appex"
APPEX_CONTENTS="$APPEX_BUNDLE/Contents"
APPEX_MACOS="$APPEX_CONTENTS/MacOS"
APPEX_RESOURCES="$APPEX_CONTENTS/Resources"
BUILD_DIR="$RUNTIME_DIR/.native-build"
TOKEN_FILE="$WORKSPACE_ROOT/work/chatlog/service.token"
SERVER_FILE="$SCRIPT_DIR/launch.py"
PYTHON_BIN="$(command -v python3)"
SERVER_URL="http://127.0.0.1:48742/"

if [[ ! -f "$TOKEN_FILE" ]]; then
  print -u2 "Missing service token: $TOKEN_FILE"
  exit 1
fi
if [[ ! -f "$SERVER_FILE" ]]; then
  print -u2 "Missing logbook service: $SERVER_FILE"
  exit 1
fi

mkdir -p "$RUNTIME_DIR"
rm -rf "$APP_BUNDLE" "$BUILD_DIR"
mkdir -p "$APP_MACOS" "$APP_RESOURCES" "$APPEX_MACOS" "$APPEX_RESOURCES" "$BUILD_DIR"
chmod 700 "$APP_RESOURCES" "$APPEX_RESOURCES" "$BUILD_DIR"

clang -fobjc-arc -fblocks -mmacosx-version-min=14.0 \
  -framework AppKit -framework WebKit -framework UniformTypeIdentifiers -framework Foundation \
  -I "$SOURCE_DIR/src" \
  "$SOURCE_DIR/src/AppMain.m" "$SOURCE_DIR/src/CLNativeImport.m" \
  -o "$APP_MACOS/聊天日志"

clang -fobjc-arc -fblocks -fapplication-extension -mmacosx-version-min=14.0 \
  -framework AppKit -framework Foundation \
  -I "$SOURCE_DIR/src" \
  "$SOURCE_DIR/src/ShareExtension.m" "$SOURCE_DIR/src/CLNativeImport.m" \
  -o "$APPEX_MACOS/存入卧底不追高日志"

cp "$SOURCE_DIR/App-Info.plist" "$APP_CONTENTS/Info.plist"
cp "$SOURCE_DIR/Share-Info.plist" "$APPEX_CONTENTS/Info.plist"

write_config() {
  local destination="$1"
  /usr/bin/python3 - "$destination" "$PYTHON_BIN" "$SERVER_FILE" "$SERVER_URL" "$TOKEN_FILE" <<'PY'
import json
import os
import pathlib
import sys

destination, python_bin, server_file, server_url, token_file = sys.argv[1:]
token = pathlib.Path(token_file).read_text(encoding="utf-8").strip()
if len(token) < 32:
    raise SystemExit("Service token is invalid")
payload = {
    "python": str(pathlib.Path(python_bin).resolve()),
    "server": str(pathlib.Path(server_file).resolve()),
    "token": token,
    "url": server_url,
}
path = pathlib.Path(destination)
temporary = path.with_suffix(path.suffix + ".tmp")
temporary.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
os.chmod(temporary, 0o600)
temporary.replace(path)
os.chmod(path, 0o600)
PY
}

write_config "$APP_RESOURCES/config.json"
write_config "$APPEX_RESOURCES/config.json"

plutil -lint "$APP_CONTENTS/Info.plist" "$APPEX_CONTENTS/Info.plist" "$SOURCE_DIR/Share.entitlements"

clang -fobjc-arc -fblocks -mmacosx-version-min=14.0 \
  -framework Foundation -I "$SOURCE_DIR/src" \
  "$SOURCE_DIR/tests/NativeImportTests.m" "$SOURCE_DIR/src/CLNativeImport.m" \
  -o "$BUILD_DIR/native-import-tests"
"$BUILD_DIR/native-import-tests"

codesign --force --sign - --entitlements "$SOURCE_DIR/Share.entitlements" "$APPEX_BUNDLE"
codesign --force --sign - "$APP_BUNDLE"
codesign --verify --deep --strict --verbose=2 "$APP_BUNDLE"

rm -rf "$BUILD_DIR"
print "Built: $APP_BUNDLE"
