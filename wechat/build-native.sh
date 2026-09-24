#!/bin/sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../../.." && pwd)
SOURCE="$ROOT/outputs/demo/wechat/native/capture.m"
OUTPUT_DIR="$ROOT/outputs/demo/.runtime"
OUTPUT="$OUTPUT_DIR/wechat-capture-native"

mkdir -p "$OUTPUT_DIR"
xcrun clang \
  -fobjc-arc \
  -Wall \
  -Wextra \
  -Werror \
  -Wno-deprecated-declarations \
  -mmacosx-version-min=14.0 \
  -framework Foundation \
  -framework AppKit \
  -framework CoreGraphics \
  -framework Vision \
  "$SOURCE" \
  -o "$OUTPUT"
chmod 700 "$OUTPUT"
