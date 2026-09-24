# 独立画像验收

所有夹具由 QA 人工虚构，不读取真实聊天、用户配置、外部模型或数据库。Java 测试直接调用生产数据/检索方法；Node 测试直接调用生产渲染方法。`fixtureNotice` 仅说明夹具来源，不发送给模型。

```sh
tests/acceptance/run.sh baseline
tests/acceptance/run.sh gates
```

`baseline` 运行已有测试及新增可在旧实现执行的回归。`gates` 单独运行升级门槛，在 88cdbac 预计有两个失败；该命令必须在最终发布验收执行。分开运行是为了保留干净的历史回归证据，不能把 baseline 成功等同升级完成。可通过 `MAVEN_BIN` 指向已有 Maven，不安装依赖或服务。

## 夹具用法与观察预言

`fixtures/profile-agent.json` 包含本人、两个不同人物、旁人背景、用户转述、相互冲突的本人发言、虚构书籍和旧画像。`M-lan-1` 只支持一般意愿；`M-lan-counter` 明确拒绝本周末安排。`M-yu-1` 是另一人物的材料，即使 speaker=them 也不得串用。

后端入口到位后，用**真实生产 AgentLoop 和工具调度器**执行以下流程；仅模型传输和存储使用可控替身：

1. 模型第一次输出书籍检索 tool call，实际调度器执行并返回虚构书摘。
2. 模型第二次必须在收到的消息中找到实际书摘文本和对应 tool-call ID，然后要求检索冲突聊天/展开上下文。
3. 实际观察包含 `M-lan-counter` 后，模型第三次返回 `loopOracle.requiredSummary`，引用实际读过的书摘及冲突证据。
4. 对照场景移除冲突观察，模型输出必须不同。测试断言最终**保存结果**不同、调用次数、真实工具结果关联及来源，不能只检查配置里有工具定义。
5. 本人路径用同样方法读取 `M-self-direct`/`M-me-in-lan` 的观察并生成自己的结果，拒绝 `M-lan-1`。

## 取消预言

使用 latch 控制真正模型/工具/保存边界，禁止固定 sleep 猜竞态。记录 store commit 次数以及保存前 state 快照：

- 模型响应前取消；替身忽略中断并迟到返回，仍不得保存。
- 工具执行中取消；迟到观察不得引发下一模型调用或提交。
- validating/saving 前取消；解除 latch 后 commit 次数仍为 0，原画像和 revision 不变。
- 重复取消安全；旧 runId 不能取消新任务；运行进度不增加业务 revision。

这些流程目前是待接入的验收规格，不是已实现/已通过的循环或取消测试。不可用自建假循环来填充通过数。

## 后端集成后统一门槛

后端提供已有 DSH 产物的任务专用 Maven 仓库后，完整验收使用：

```sh
MAVEN_OPTS='-Dmaven.repo.local=/Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/ailiao-upgrade/backend/.local/m2' tests/acceptance/run.sh release
```

`release` 离线运行全部 Java 测试 **包括独立 gates**，再运行全部前端与 QA Node 测试，不会因默认 Surefire 命名规则漏掉 gates。不要用过期共享 `~/.m2` 的合同替代后端核验的产物。

`ProfileAgentAcceptanceRuntimeTest` 调用真实 RanchAnalyzer/ProfileRuntime/DSH AgentScope/工具调度器；仅复用后端测试 Harness 的插件装配，模型传输和内存 Repository 是测试边界。self/person 各做实际观察有/无配对：用材料预算先隐藏观察，经 book_search→chat_search→模型读取 tool-result 后返回不同输出，断言最终保存内容不同。取消与修订使用 latch 控制边界，保存优先场景断言并发取消不能把已保存结果改称 cancelled。静默流取消专门断言无任何 chunk 也必须有界释放，失败后才释放应急测试信号以免挂住套件。

## Provider 传输数值门槛

`ProfileAgentAcceptanceTransportTest` 只读 `${DSH_JAVA_HOME:-/Users/hou/Documents/Codex/projects/dsh-java}/plugins/model-deepseek/target/model-deepseek.jar`，反射调用真实插件的纯 `wireRequest` 转换并做真实 JSON 序列化。不会实例化网络 Adapter、读取密钥、调用 provider stream 或发 HTTP。

它加载同一 JAR 的实际模型目录，将其元数据接入现有 ModelRegistry，再通过真实 AgentScope 的本人/对象两步链路，要求**每一次最终 provider JSON 的 max_tokens 是整数且恰为6000**。省略不接受：已核实目录默认是256000L，会扩大预算。另有控制用例证明实际 serializer 把6000d写成浮点6000.0、整数6000写成整数。构建环境必须准备这个现有插件产物；缺失时明确失败，不跳过此门槛。

## 材料时间来源门槛

`ProfileAgentAcceptanceTimeTest` 使用虚构材料验证本人/对象初始消息和实际 chat_search/chat_context 工具结果：录入/采集/导入时间及仅有 sourceKey 的旧记录不得作为发言日期，只有有效的原始消息时间可传为 spokenAt。同时验证原 at 的最新材料排序、输入/state 不变，以及 Live 微信/飞书、Log、legacy 的时间来源映射。5项在修复前全红、df23b58后全绿；详见 `docs/qa/time-provenance-review.md`。模型传输使用既有 Harness 替身，不访问真实来源或网络。
