# wechat-history dsh-java 插件

独立插件将 `wechat-cli history 卧底不追高` 的结构化输出交给本机日志服务；不启动模型、不发送微信、不调用 init、不更改 dsh-java 底座。

入口：`dev.garden.wechat.WechatHistoryPlugin`。Manifest 名：`wechat-history-plugin`。JAR：`target/wechat-history-plugin.jar`。

## 构建

```sh
/Users/hou/.local/apache-maven-3.9.16/bin/mvn -f outputs/demo/plugins/wechat-history/pom.xml clean package
```

需要 Java 17 及本机 Maven 仓库中的 `dev.dsh:kernel-api:0.1.0-SNAPSHOT`。宿主 **必须** 带 `-Dfile.encoding=UTF-8`：macOS Java 17 按默认字符集编码 ProcessBuilder 参数，ASCII 宿主会损坏中文群名，因此插件拒绝非 UTF-8 宿主。子进程环境也明确使用 UTF-8。

## 配置

传给 `PluginContext.config()` 的 Map 示例（路径替换为实际独立安装路径）：

```json
{
  "commandPrefix": ["/absolute/private/venv/bin/python", "-m", "wechat_cli"],
  "cliConfig": "/absolute/private/wechat-cli/config.json",
  "keyFile": "/absolute/private/wechat-cli/keys.json",
  "dataDir": "/absolute/private/wechat-history-plugin",
  "logbookUrl": "http://127.0.0.1:48741",
  "tokenFile": "/absolute/private/logbook-native-token",
  "intervalSeconds": 3,
  "timeoutSeconds": 30
}
```

`commandPrefix` 必须指向已具有 `--structured`、`--cursor-file` 适配的隔离 CLI。插件按数组执行，固定追加 `--config <cliConfig> history 卧底不追高 --format json --structured --cursor-file <dataDir/cursor.json> --limit 200`；无 shell。每轮最多 200 条，stdout 上限 4 MiB，退出状态和超时都必须成功；stderr 丢弃。

`dataDir` 专属于本实例，权限 0700、游标和锁文件 0600。跨进程锁拒绝同目录的第二个实例。插件从日志服务读取已提交检查点，再原子写入 CLI 游标；首轮文件内容为 `null`。CLI 响应的 schema、目标名、账号/群身份、检查点及消息字段必须通过校验，账号和群必须与上次提交的检查点一致。

批次 POST 失败时在内存保留原批次，每轮重试相同批次，成功前不重新 GET 检查点或执行 CLI。进程重启时重新 GET 已提交检查点，由日志服务的事务与幂等写入保证重放安全。`has_more` 不触发无限紧密循环，按配置间隔继续分页。

缺少/空/不可读 `keyFile` 时不执行 CLI，每轮上报 `setup_required`。非空文件只是启动前提；是否能读取由 CLI 的真实结果决定，CLI 失败只报 `error`。验证后的批次被日志服务成功接收后，`has_more=true` 上报 `syncing`，否则才报 `connected`。

HTTP 固定回环 IP、不使用代理、不跟随重定向。每次请求读取 `tokenFile`，设置 `X-Logbook-Token`。端点为 GET `/v1/history-checkpoint`、POST `/v1/history-batch` 和 POST `/v1/history-status`。状态只包含固定 `status`/`detail`，不输出消息正文、密钥、token 或 stderr。

底座 `context.own` 管理关闭：取消轮询、终止本实例 CLI 及后代、断开 HTTP、等待当前轮收尾、释放目录锁。可通过命名服务 `wechat.history.status` 读取只读 `java.util.Map`；包含 `source`、`status`、`detail`、`updatedAt`，日志服务暂不可达时附 `delivery=logbook_unreachable`。该服务用于观测，不声明采集已连接。

## 验证范围

JUnit 使用临时目录、合成 JSON、真实 JDK 子进程和回环 HTTP 测试服务器，覆盖缺密钥、固定命令与 Unicode、成功提交顺序、失败批次重试、错误群、账号变化、进程失败、超时杀进程、stdout 超限、关闭杀进程/释放锁、独占目录和拒绝远程服务。未读取真实微信数据。测试报告位于 `target/surefire-reports/`；首次纯桩失败记录为 `tdd-red.log`。

轮询线程为非 daemon，确保独立 dsh 宿主在 bootstrap 返回后持续运行；仅 stdout 读取线程为 daemon。实际子 JVM 生命周期回归先红后绿，RED 记录为 `tdd-lifecycle-red.log`。状态接口对齐与 has_more 分页回归 RED 记录为 `tdd-status-red.log`。
