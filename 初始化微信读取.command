#!/bin/zsh
set -eu
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
print '需要一次管理员授权；密码输入时不会显示字符，请勿把密码发给任何人。'
if ! /usr/bin/sudo /usr/bin/python3 "$SCRIPT_DIR/logbook/initialize_wechat.py"; then
  print '初始化未完成；若已进入脚本，错误原因已保存在本地，助手可直接检查。'
fi
print '按回车关闭此窗口。'
read -r
