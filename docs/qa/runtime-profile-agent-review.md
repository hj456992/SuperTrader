# 真实运行链路独立验收

被测后端：`1c345b2`，后续修复 `a59b21e`；前置文档 `98e1269`。被测前端：`ed83e4f` + `a00a5a7` + `461b3dc`。QA 只新增/修改测试与报告，未修改生产代码、底座或正在运行的应用。

## 方法和真实程度

`ProfileAgentAcceptanceRuntimeTest` 复用后端 Harness 的真实 PluginManager 装配：DSH AgentRegistry、AgentLoop、AgentScopeRuntime、会话、上下文、工具调度器。QA 独立提供虚构 fixture、Repository、模型传输输出及断言。所有画像场景经过实际 RanchAnalyzer 到 Repository.update，不把直接调用校验器当作完整保存验证。没有连接真实模型、PostgreSQL、私有材料或真实用户配置。

本人/对象各做有观察与无观察两次运行：材料预算使指定旧材料不进入初始输入（断言初始消息不存在该原文），第一次模型请求 book_search，第二次从真实 tool-result 读到原文后请求 chat_search，第三次只依据实际工具观察决定输出，最终 Repository 保存的 summary 在两个场景必须不同。另断言 3 次模型调用、书籍引用、runId、业务 revision 与 basedOnRevision、一次提交。这是观察改变后续输出及正式保存的证据，不是仅检查工具定义存在。

## 已观察结果

| 场景 | 当前独立结果 | 范围 |
|---|---|---|
| 本人/对象两轮工具后观察改变保存内容 | 通过 | 每条路径均包含有/无观察对照 |
| 空库/全停用/有书无命中 | 通过 | 正式保存且状态分开，空引用、有局限提示 |
| 跨人物/背景反证/库内存在但未读书摘 | 通过 | 最终状态 error、零提交、revision 不变 |
| 首次模型响应前、book 工具执行中、最终模型响应返回前取消 | 通过 | provider 可忽略中断迟到返回；旧画像与 revision 不变；工具取消后不触发下一模型调用 |
| 旧 runId、重复取消、cancelling 禁止重启 | 通过 | 实际 Analyzer 调用，不仅 UI |
| 正式 update 已取得保存屏障时取消 | 通过 | cancel 线程必须被监视器阻塞；释放 commit 后一次保存、done；重复取消不能谎报 cancelled |
| 进度不改业务 revision，真实材料变更拒绝旧结果 | 通过 | 受控最终模型返回前修改业务；保留新数据，不提交旧画像 |
| running / cancelling 重新构造 Analyzer | 通过 | interrupted 持久记录、旧画像和 revision 保持；这是内存 Repository 重建，非真实数据库进程重启 |
| 删除书籍撤销本人/对象当前与历史书摘 | 通过 | 实际 RanchKnowledge.command + Repository 删除，检查两类主体历史 |
| 模型失败/畸形 JSON | 通过 | 不覆盖已有画像；错误不给用户暴露内部测试异常标记 |
| 既有策略路径 | 通过 | 新目标与双方材料进入模型；保存策略，不改人物画像 |
| 无任何 chunk 的模型流取消 | **仍失败** | a59b21e 可到 cancelled，但实际 provider 源 onCancel 没触发，见下文 |
| 六步预算 | 通过 | 恰好6次模型调用、明确错误、零提交且原状态不变 |
| 超时传播 | **仍失败** | 时限结束能成为error且零保存，但provider源onCancel未触发；与取消缺口相同 |

QA 第一阶段两项 gates（goal 完整输入隔离、未读知识 ID 拒绝）已在 `1c345b2` 转绿。

## 可复现缺陷与修复验证

### 静默模型源未收到取消（未关闭）

- `1c345b2`：原始无 chunk 流在取消后 3 秒仍为 cancelling；独立 RuntimeTest 11 项中 10 过、1 失败。
- `a59b21e`：已能在 3 秒内进入 cancelled，但源订阅取消回调仍未触发。独立 11 项 + 2 gates 运行有 1 失败；单测重复复现。
- 排除测试操作符干扰：从 `Flux.never().doOnCancel(...).takeUntilOther(emergency)` 换为直接 `Flux.create`，在源里先注册 `sink.onCancel` 再发 subscribed latch，不发任何 chunk，结果仍失败。断言后 finally 才调用源 sink.complete 应急释放，不能用该释放冒充系统取消。
- 复现：`mvn -o -Dmaven.repo.local=/Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/ailiao-upgrade/backend/.local/m2 '-Dtest=ProfileAgentAcceptanceRuntimeTest#noChunkHangingModelCancelsWithinBoundWithoutWaitingForProvider' test`。
- 已报新 TL 和后端。不得只保留 cancelled 终态断言而删除 provider 取消传播断言。

### 终态提示无法关闭（已修复）

原前端 error/interrupted 的“关闭提示”仍调用 cancel；后端为保留终态将该调用视为 no-op，刷新后提示原样出现。QA 用真实 mount + 对应 HTTP 边界复现 2/2 失败。前端 `461b3dc` 改本地按 runId dismiss 后，同一断言转绿；全量 Node **58/58** 通过。测试按实际按钮 action 操作，未硬编码新实现。

### 测试等待边界调整

首次独立修订用例 5 秒内未到最终模型 latch；单独重跑 2.023 秒通过。将仅测试装配/入口等待上限改为 15 秒后通过；静默取消的产品有界释放门槛仍为 3 秒，未放宽。没有证据将首次入口超时判为产品缺陷。

## 仍不可宣称的验证

真实模型质量、完整 HTTP/浏览器联调、真实 PostgreSQL 进程重启、最终发布装配 smoke 均不由本测试证明。任务提交 HTTP 响应前的 UI 取消已由 Node 控制响应测试覆盖；后端取消三个边界与保存线性化另有独立证据，但不能称同一次网络端到端试验。需 TL 最终集成 SHA 后再跑 `tests/acceptance/run.sh release`（包含 gates），并对照运维隔离 smoke。

独立预算追加实跑：2 tests / 1 pass / 1 fail。`deadlineCancelsSilentProviderAndNeverCommits` 已确认源订阅发生，2秒runtime总时限结束后再等2秒，source onCancel仍未触发；`repeatedToolsStopAtSixModelCallsAndNeverCommitPartialResult` 通过。
