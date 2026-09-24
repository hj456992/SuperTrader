# feishu-history-plugin

独立 dsh-java 插件，按调用读取飞书客户端当前可访问会话。插件不轮询、不保存聊天、不发送消息、不调用模型。辅助功能权限与客户端选择由外部采集器负责。

## 构建

需 JDK 17，以及本机 Maven 仓库中的 `dev.dsh:kernel-api:0.1.0-SNAPSHOT`。

```sh
/Users/hou/.local/apache-maven-3.9.16/bin/mvn -f pom.xml clean package
```

产物：`target/feishu-history-plugin.jar`（包含 Jackson，kernel-api 由宿主提供）。清单入口为 `dev.garden.feishu.FeishuHistoryPlugin`。

## 配置与调用

```json
{
  "commandPrefix": ["/usr/bin/python3", "/absolute/path/outputs/demo/feishu/collector.py"],
  "timeoutSeconds": 20
}
```

`commandPrefix` 是不经过 shell 的完整参数数组；可执行文件必须为绝对路径。`timeoutSeconds` 缺省 20 秒，允许整数 1–30。

服务键为 `new ServiceKey<Supplier>("feishu.capture", Supplier.class)`。`Supplier.get()` 同步执行一次采集并返回 `Map<String,Object>`；宿主应在工作线程调用，避免阻塞 UI。服务接口和全部返回值仅用 JDK 类型，跨插件类加载器无需共享插件实现类。

浏览服务键为 `new ServiceKey<Function>("feishu.browse", Function.class)`，与 capture 使用同一对象、互斥锁和关闭生命周期。`Function.apply(Map<String,Object>)` 接受以下请求：

- `{ "action": "list" }`：以 `commandPrefix + ["list"]` 执行采集器，返回 `{ "status": "ok", "platform": "feishu", "scope": "loaded_chats", "chats": [{ "id": "chat_1", "title": "测试群" }], "detail": "当前已加载会话" }`。最多 200 项，ID 唯一，标题非空。
- `{ "action": "select", "chatId": "chat_1", "chatTitle": "测试群" }`：以 `commandPrefix + ["select", chatId, chatTitle]` 执行；ID 必须匹配 `[A-Za-z0-9_-]{1,160}`，标题非空且最多 100 个 UTF-16 单元。结果必须符合下述 current_view 格式，且群名与请求完全一致。

未知操作、多余字段和无效请求不会启动进程。原有 `Supplier.get()` 继续不追加任何参数。列表不扫描未加载会话；选择可能切换飞书当前会话。浏览还支持 `not_found` 与 `ambiguous` 结构化失败状态。

成功格式：

```json
{
  "status": "ok",
  "platform": "feishu",
  "chatTitle": "测试群",
  "text": "对方：消息",
  "capturedAt": "2026-09-23T01:00:00Z",
  "messages": [{"sender": "张三", "text": "消息", "role": "them"}],
  "scope": "current_view",
  "truncated": false,
  "detail": "已读取飞书当前加载的消息，不代表全部历史。"
}
```

读取范围是当前可访问页面，不代表全量历史。成功结果必须有非空群名、正文、ISO 时间和 1–60 条消息对象；正文最多 16000 字符，群名最多 500 字符。`truncated` 必须为布尔值，省略时默认 `false`；可选成功 `detail` 必须为非空且不超过 500 字符的安全说明。这些来源字段保留给界面和持久化使用。插件不要求固定的消息作者字段，允许采集器保留其消息来源字段。

采集器可返回 `{ "status": "permission_required|not_running|no_chat|empty|error", "detail": "安全的中文说明" }`。这些状态将透传给调用方，说明长度上限 500 字符。采集器应保证说明不含敏感日志。重复并发调用返回 `busy`。超时、子进程非零退出、无效 JSON、超出输出上限、关闭后调用均返回安全的 `error`；stderr 丢弃，不进入返回值。

进程 stdout 上限 1 MiB，超限即终止进程。宿主关闭插件时释放采集器，终止正在运行的采集进程及可发现的子孙进程。没有读取调用时不启动子进程。

## 验证

JUnit 测试使用临时 Python 脚本，不访问真实客户端。覆盖新快照、结构化状态、超时与子孙进程终止、关闭生命周期、并发 busy、1 MiB 输出限制、非零退出、无效协议与参数。浏览测试还覆盖精确命令参数、非法请求零进程、重复 ID、列表边界、目标群名核对以及跨服务互斥。`tdd-red.log` 与 `tdd-browse-red.log` 保留实现前的失败记录。
