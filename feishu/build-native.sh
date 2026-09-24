#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p bin
clang -fobjc-arc -O2 -framework AppKit -framework ApplicationServices native/read-current.m -o bin/feishu-read-current
