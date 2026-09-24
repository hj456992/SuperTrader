# Provider 数值传输独立验收

本次任务由新 TL 在真实模型 smoke 返回参数类型错误后发起。此前绿色确定性测试使用 ModelRegistry 传输替身，未经过 DeepSeek wire JSON 转换，不能据其通过推断真实网络协议正确。真实 smoke 失败、零保存和 revision 不变为 TL 的运行证据，本报告只记录 QA 自己执行的离线复现。

## 最终结果

后端修复 `d4ed55915bd4a9528ce1f8153811c661777ee31e` 已通过原独立断言：本人/对象工具前后共4次最终 provider JSON 均为整数 `max_tokens=6000`，两个 TransportTest **2/2** 通过。修复只在现有应用 `agent/request` 作用域、prepareCall之前规范化为Integer6000，未省略/放大预算、未改底座或绕开Agent。

完整 `tests/acceptance/run.sh release`（同一专用Maven仓库，离线）**Java77/77、Node60/60，0失败/错误/跳过**。这是合入修复后已启动的一次完整回归，没有另开重复Node验证。QA被测 `src/main`、`web/src`、`pom.xml` 与TL候选 `33193f4` 差异为空。

## 独立复现

新增 `ProfileAgentAcceptanceTransportTest`，通过只读 URLClassLoader 加载现有 DeepSeek 插件，调用实际 `wireRequest(Map)` 纯转换并进行 Jackson JSON 序列化/解析。测试不实例化网络 Adapter、不读取密钥、不调用真实 provider.stream/HTTP，不新增 Maven 或运行依赖。实际目录元数据注册到真实 ModelRegistry，画像仍经过现有 AgentRegistry、AgentLoop、AgentScope 和工具调度器。

被读产物：`${DSH_JAVA_HOME:-/Users/hou/Documents/Codex/projects/dsh-java}/plugins/model-deepseek/target/model-deepseek.jar`。

SHA256：`44d043162eaadedad6c267c3c9669236617e255392c49b8c0bb4d2ef6f9cb020`。

在后端 `2fb6ba6` 运行：

```sh
/Users/hou/.local/apache-maven-3.9.16/bin/mvn -o \
  -Dmaven.repo.local=/Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/ailiao-upgrade/backend/.local/m2 \
  -Dtest=ProfileAgentAcceptanceTransportTest test
```

结果：**2 tests / 1 pass / 1 failure / 0 errors / 0 skip**。业务链路用例明确报 `Provider JSON max_tokens must be an integer; actual=6000.0`；控制用例通过，证明读取的真实 serializer 对 `6000d`、`6000`、目录默认值分别保持浮点、整数、256000整数。

## 修复门槛

- 本人和对象各两次实际模型请求（书籍工具前后）最终 provider JSON 必须都是整数 `max_tokens=6000`。
- 不能仅检查Java数值的 `intValue()`，不能把6000.0强制转换后才断言。
- 不能用省略参数使测试通过：实际目录默认值为 `256000L`，远大于原预算。
- 保留 AgentScope 路径与实际插件 serializer，不修改公共底座。后端已选择当前应用 `agent/request` 作用域把 maxTokens 设为 Integer6000；QA已按d4ed559保持原断言复测通过。
- 缺少现有 DeepSeek 插件产物会明确失败，不将传输门槛跳过；测试环境可通过现有 DSH_JAVA_HOME 指向其验证过的底座目录。

最终真实模型重试仍由 TL/ops 在明确集成 SHA 上安排，离线 serializer 测试不能替代该 smoke。
