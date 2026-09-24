#!/bin/zsh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
APP_SOURCE="$SCRIPT_DIR/../.runtime/聊天日志.app"
APP_DESTINATION="$HOME/Applications/聊天日志.app"
if [[ ! -d "$APP_SOURCE" ]]; then
  print -u2 '请先运行 logbook/build-native.sh。'
  exit 1
fi
if [[ -e "$APP_DESTINATION" ]]; then
  existing_id=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$APP_DESTINATION/Contents/Info.plist" 2>/dev/null || true)
  if [[ "$existing_id" != 'local.aichat.chatlog' ]]; then
    print -u2 '目标位置已有另一应用，未覆盖。'
    exit 1
  fi
  if pgrep -x '聊天日志' >/dev/null; then
    print -u2 '聊天日志正在运行，请退出后再安装新构建。'
    exit 1
  fi
  backup_path="$SCRIPT_DIR/../.runtime/聊天日志.previous.$(date +%Y%m%d%H%M%S).app"
  mv "$APP_DESTINATION" "$backup_path"
fi
mkdir -p "$HOME/Applications"
ditto "$APP_SOURCE" "$APP_DESTINATION"
codesign --verify --deep --strict "$APP_DESTINATION"
/System/Library/Frameworks/CoreServices.framework/Frameworks/LaunchServices.framework/Support/lsregister -f "$APP_DESTINATION"
pluginkit -a "$APP_DESTINATION/Contents/PlugIns/存入卧底不追高日志.appex"
print "已安装：$APP_DESTINATION"
