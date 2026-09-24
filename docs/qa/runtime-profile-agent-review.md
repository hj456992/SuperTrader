# 真实运行链路独立验收

最终被测后端：`2fb6ba6`（前序 `1c345b2`、`a59b21e`；前置文档 `98e1269`）。最终被测前端：`f6bd81b`（包含 `ed83e4f`、`a00a5a7`、`461b3dc`）。QA 只新增/修改测试与报告，未修改生产代码、底座或正在运行的应用。

## 最新结论

完整离线命令 `MAVEN_OPTS=-Dmaven.repo.local=/Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/ailiao-upgrade/backend/.local/m2 tests/acceptance/run.sh release`：**Java 75/75，Node 60/60，0 fail/error/skip**。包括 13 个独立 RuntimeTest、8 个独立数据回归、2 个独立 gates，以及前端独立用例。静默取消和总时限两项保持原 provider 源取消断言，在 `2fb6ba6` 同时转绿；之前的红测历史保留在下文。

QA 所测 `src/main`、`web/src`、`pom.xml` 与 TL 最终集成 `b5df7ee` 差异为空。QA 另补强了相同人物库内存在但初始未读、也未经工具读到的聊天 ID 必须拒绝，以及真实进度事件有界/递增/无聊天原文断言，包含在上述实跑中。

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
| 无任何 chunk 的模型流取消 | 通过（2fb6ba6） | 3秒内cancelled、实际provider源onCancel触发、零保存；断言前不主动完成源 |
| 六步预算 | 通过 | 恰好6次模型调用、明确错误、零提交且原状态不变 |
| 超时传播 | 通过（2fb6ba6） | 真实已订阅静默源，总时限后error、onCancel触发、零保存 |

QA 第一阶段两项 gates（goal 完整输入隔离、未读知识 ID 拒绝）已在 `1c345b2` 转绿。

## 可复现缺陷与修复验证

### 静默模型源未收到取消（2fb6ba6 已关闭）

- `1c345b2`：原始无 chunk 流在取消后 3 秒仍为 cancelling；独立 RuntimeTest 11 项中 10 过、1 失败。
- `a59b21e`：已能在 3 秒内进入 cancelled，但源订阅取消回调仍未触发。独立 11 项 + 2 gates 运行有 1 失败；单测重复复现。
- 排除测试操作符干扰：从 `Flux.never().doOnCancel(...).takeUntilOther(emergency)` 换为直接 `Flux.create`，在源里先注册 `sink.onCancel` 再发 subscribed latch，不发任何 chunk，结果仍失败。断言后 finally 才调用源 sink.complete 应急释放，不能用该释放冒充系统取消。
- 复现：`mvn -o -Dmaven.repo.local=/Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/ailiao-upgrade/backend/.local/m2 '-Dtest=ProfileAgentAcceptanceRuntimeTest#noChunkHangingModelCancelsWithinBoundWithoutWaitingForProvider' test`。
- 后端确认根因是 Reactor 3.8.4 takeUntilOther 的旁路 onError 只取消旁路，未取消主订阅；应用层 abort 改 sink.success 后走 cancelMainAndComplete。公共底座未修改。`2fb6ba6` 下独立取消和超时原传播断言均通过；未删除或降低 provider 取消门槛。

### 终态提示无法关闭（已修复）

原前端 error/interrupted 的“关闭提示”仍调用 cancel；后端为保留终态将该调用视为 no-op，刷新后提示原样出现。QA 用真实 mount + 对应 HTTP 边界复现 2/2 失败。前端 `461b3dc` 改本地按 runId dismiss 后，同一断言转绿；当时全量 Node **58/58** 通过；合入目标提示f6bd81b后最终60/60。测试按实际按钮 action 操作，未硬编码新实现。

### 测试等待边界调整

首次独立修订用例 5 秒内未到最终模型 latch；单独重跑 2.023 秒通过。将仅测试装配/入口等待上限改为 15 秒后通过；静默取消的产品有界释放门槛仍为 3 秒，未放宽。没有证据将首次入口超时判为产品缺陷。

## 仍不可宣称的验证

真实模型质量、完整 HTTP/浏览器联调、真实 PostgreSQL 进程重启、最终发布装配 smoke 均不由本测试证明。任务提交 HTTP 响应前的 UI 取消已由 Node 控制响应测试覆盖；后端取消三个边界与保存线性化另有独立证据，但不能称同一次网络端到端试验。需 TL 最终集成 SHA 后再跑 `tests/acceptance/run.sh release`（包含 gates），并对照运维隔离 smoke。

历史红测（a59b21e）独立预算追加实跑：2 tests / 1 pass / 1 fail。`deadlineCancelsSilentProviderAndNeverCommits` 已确认源订阅发生，2秒runtime总时限结束后再等2秒，source onCancel仍未触发；`repeatedToolsStopAtSixModelCallsAndNeverCommitPartialResult` 通过。


## 最终集成只读审阅：b410f0f → b5df7ee

按规格 Task 2/3/5 审阅生产变更、启动器、插件清单和隔离探针，未发现新的生产阻断缺陷：

- cancel 与 store.update 在 Analyzer 同一监视器线性化；取消先接受则不提交，保存已占据屏障则取消等待，保存成功保持done。静默流释放由上述红绿用例证明。
- 画像去除goal/goalType；聊天工具仅持有任务主体资料，实际返回片段追加读取集合；保存前分别校验人物、反证与实际读取书籍ID。书籍删除清理双方当前和历史引用。
- 任务进度使用现有文档表的profile-job，不增加业务revision；启动恢复只标interrupted，不声称继续模型。旧两参校验入口等价空知识引用，旧数据新增字段仍可选。
- Garden通过现有AgentRegistry/AgentLoop/AgentScope、tools/systemPrompt连接；新增Maven依赖为已有DSH合同provided或实现test scope。run.py只装配既有插件，未增加服务/向量库/新业务/新数据库，变更集中未出现公共底座源码改动。
- 启动器预检不连接DB/模型、不创建数据库、不打印凭据；正式bootstrap只写插件公开配置。隔离smoke固定48749与授权schema，来源接口禁用。当前只审查探针代码，不冒称已执行实际装配/HTTP/模型smoke。
- 文档遗留已报TL：runbook隔离步骤7的“正常Ctrl+C后应interrupted”与新的real-smoke-plan步骤9不一致；应明确正常关闭可为cancelled，用本任务持有的测试进程异常中断验证interrupted。由ops修改其负责文件，QA未越权修改。

最终运行环境、真实模型、真实DB重启与浏览器操作结果仍由TL/ops在明确集成SHA上执行并单列，不属于本报告的绿色确定性测试结论。

## A01–A23 最新映射（覆盖初期报告中的待测状态）

| 产品 ID | 当前证据状态 |
|---|---|
| A01 | 确定性通过：本人真实AgentScope工具循环、读取范围、正式保存；Node本人引用通过；真实模型待smoke |
| A02 | 确定性通过：对象实际观察对照与保存、主体隔离；Node对象引用通过；真实模型待smoke |
| A03 | 确定性通过：书籍/人物引用域分开、混入ID拒绝、界面方法与事实分区 |
| A04 | 通过：画像完整输入目标隔离gate，编辑不改现存画像；策略可收到新目标 |
| A05 | 部分验证：原有无适格证据拒绝测试通过；少量信息下真实模型结论质量待smoke |
| A06 | 确定性通过：隐藏冲突由工具读到后改变最终保存，反证引用校验与渲染 |
| A07 | 通过：空库及全停用生成有限结果、empty_library、无伪书摘 |
| A08 | 通过：存在库但无命中生成有限结果、no_match，与空库区分 |
| A09 | 确定性真实组件通过：3次模型传输、两次真实工具、观察影响保存；完整宿主装配smoke待TL |
| A10 | 通过：主体归属、转述inferred约束、self/对象历史引用界面隔离 |
| A11 | 通过：跨人、背景反证、同人存在但未读聊天、库内存在但未读书摘均不提交 |
| A12 | 通过确定性命令/数据层：删书双方当前及历史清理、聊天撤销；真实HTTP待smoke |
| A13 | 通过：真实阶段、最大6步、最多32事件、seq递增、无原文；界面最多8阶段且转义 |
| A14 | 通过确定性：首次模型/工具中/最终返回前取消零提交；保存先占屏障保持done；提交HTTP响应未回的UI路径另有Node证据 |
| A15 | 通过：错runId不取消、重复安全、cancelling禁止启动，旧UI按钮不误伤新run |
| A16 | 部分验证：Node卸载不取消/不重新生成、旧记录重读可显示；真实新会话待smoke |
| A17 | 部分验证：同Repository重建Analyzer，running/cancelling转interrupted且保留画像；实际DB和进程重启待smoke |
| A18 | 通过确定性：进度不改revision，业务变更使旧完成结果CAS失败且不覆盖 |
| A19 | 通过确定性：6次调用封顶、第7次不发；静默deadline释放provider且零提交；12000字工具边界另经代码审阅 |
| A20 | 通过确定性：模型报错、畸形JSON/非法引用不覆盖旧画像，内部错误未放入用户状态 |
| A21 | 通过Node：任务/方法/本人及对象引用隔离，取消期间切换与迟到轮询组合场景 |
| A22 | 部分验证：旧画像/旧job/省略runId Node与Java回归，终态关闭修复红绿；完整HTTP兼容待smoke |
| A23 | 通过确定性/Node：源文本转义、真实既有策略模型路径保存、双方材料与目标输入兼容；真实模型策略待smoke |
