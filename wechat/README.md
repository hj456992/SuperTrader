> 旧OCR实验已停用。当前启动器和48741页面读取独立聊天日志服务，不再启动本目录原生采集器。请查看 [新日志应用说明](../logbook/README.md)。旧代码/数据库保留供回溯，以下为历史说明。

# 爱聊微信采集

## 使用

1. 双击上一级的 `启动微信采集.command`，或在 Demo 目录运行 `python3 wechat/launch.py`。
2. 打开 [微信采集](http://127.0.0.1:48741/wechat.html)，微信中打开《卧底不追高》。页面上可开始或暂停采集。
3. 在微信直接滚动，停稳约 3–5 秒；尽量保留相邻页的两三条重叠消息。爱聊自动保存看到的消息，无需让 Codex 截图。
4. 爱聊显示采集状态、成员和消息数量、分段记录。切到其他群会停止入库；返回目标群后继续。暂停后数据保留。

若已经运行，重复启动不会再创建同端口服务。在启动终端按 Ctrl+C 停止扩展。下次启动保留数据库中的消息；原有 Java 服务不受影响。

## 本地处理与覆盖

采集程序只选择 `com.tencent.xinWeChat` 的聊天窗口，先检查标题，再识别指定群消息区。不会识别左侧会话列表的消息。程序不控制微信、不发送消息、不读聊天数据库，也不使用外部模型。

CoreGraphics 获取单窗口图像，Vision 在本地执行文字识别，原始图像仅在内存使用。昵称和文字保存到工作区 `work/wechat-capture/capture.sqlite3`；目录0700、数据库0600，日志不输出原文。数据库与原有人物 PostgreSQL 数据分开，避免把多个成员当成一个对方。

连续重复页面幂等，重启后保留最近页面的去重状态；唯一且足够明确的连续重叠序列可以把上/下滚动页面连接成一个片段。真实重复发言保留。没有锚点、对齐有歧义或同一页面经过其他内容后再次出现时，保守分开保存并显示缺口，不声称全量、连续或精确一次同步。完全相同的页面无法可靠区分复读与后来再次出现的同样内容，可能保留需要核对的重复片段。

当前识别适配本机微信聊天窗口布局。显示昵称是临时成员身份，重名可能需要后续校正；没有源消息 ID。只保留可见时间标签和采集时间，不编造精确发送时间。媒体/无法转写的表情只记录占位，引用结构尚未单独解析；文字识别可能有误。已滚过却没有停留采样的页面不会自动补回。

## 工程

- `native/capture.m`：本机单窗口采集和 Vision OCR，先验证目标标题再识别正文。
- `collector.py`：持续采样、稳定页检测、说话人和正文组合、暂停恢复。
- `store.py`：SQLite 持久化、成员标识、序列对齐和覆盖片段。
- `server.py`：127.0.0.1:48741 本地服务；保留并同源代理48740原有人物API。
- `../web/public/wechat.*`：采集控制和群消息页面。

构建与测试：

```sh
zsh outputs/demo/wechat/build-native.sh
python3 -m unittest discover -s outputs/demo/wechat/tests -v
node --test outputs/demo/wechat/tests/test_ui.cjs
zsh outputs/demo/wechat/native/test-native.sh
npm --prefix outputs/demo/web run build
```

原生系统API参考：[Apple Vision文字识别](https://developer.apple.com/documentation/vision/vnrecognizetextrequest)、[单窗口截取](https://developer.apple.com/documentation/coregraphics/cgwindowlistcreateimage(_:_:_:_:))。当前使用系统公开屏幕录制授权，不修改权限数据库。若系统拒绝，界面显示需要权限，不以其他通道绕过。
