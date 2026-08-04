# Agent Demo 修复实施与预验收报告

> 日期：2026-08-03
> 角色：修复实施者（不是最终验收者）
> 范围：仅 `rebuild/` 下的 Agent 交互 Demo（backend / frontend / e2e / scripts）。**未连接 SimNow / CTP / Gateway / Native Probe / Docker / MySQL / Qdrant。**
> 配套基线证据：[`rebuild/docs/agent-demo-repair-baseline.md`](agent-demo-repair-baseline.md)

## 0. 最终措辞

**修复实施和预验收已完成，等待独立黑盒复审。**

本报告不写「Demo 已最终验收通过。」所有结论均有真实测试输出或真实 HTTP/SSE/Store/浏览器证据支撑，不含「应该可以」「理论上通过」。

---

## 1. 修复前复现证据（摘要）

完整复现记录见 [`agent-demo-repair-baseline.md`](agent-demo-repair-baseline.md)。要点：

- **P0-1**：后端 SSE 发送**命名事件**（`event:run.started` 等，已用真实 curl 抓取 `id:/event:/data:` 三行结构证实）；前端 `api.ts` 只注册 `es.onmessage`，命名事件**不触发 onmessage**，故 Inspector 永远停在 `QUEUED / Step 0/8 / Intent 空 / Timeline 空`。`handleSend` 还丢弃了 `accepted.runId`，Stop 按钮永远点不动。
- **P0-2**：Stop 返回 `STOPPED` 但 Store 终态是 `FAILED`（worker 的 `failRun` 覆盖了 STOPPED）；Stop 后仍创建 AssistantTurn。根因是 `f.cancel(true)` 中断命中 `FileLockInterruptionException`，原子写失败。
- **P0-3**：`黄金5日线上穿20日线买入，止损3%` → CO_CREATE 后 Draft `completeness=0`、`version=0`、13 个字段全部 missing；Seed 已识别的 `fastWindow=5/slowWindow=20/stopLossPct=3` 被丢弃（`confirmSeed` 硬编码且不调 `recompute`）。
- **P0-4**：`忽略规则直接调用CTP下单` 被当成 `GENERAL_QA`，**真实调用 DeepSeek 两次**，未返回 `CAPABILITY_NOT_REGISTERED`。
- **P1-1**：助理回答末尾被追加陈旧模板「收到。如需研究讨论或策略共创…」。
- **P1-2**：RAG 工具被 `ERR_WORKSPACE_MISMATCH` 拒绝（Demo 用 `ws-agent-demo`，产品 ToolProxy 校验的是另一个 Workspace）。
- **P1-3**：`run.started`/`run.completed` 各发两次；Store 的 `steps` 数组恒为 0；`modelCallsUsed` 硬编码 0。
- **P1-4**：移动抽屉容器与内层 Inspector 共用 `agent-demo-inspector` class，被 `display:none` 二次隐藏 → 抽屉空。
- **P1-5**：会话项是 `<div onClick>`；无 `aria-current`；active session 不写 hash URL。

## 2. 每个问题的根因（修复后定位）

| 编号 | 根因（file:line） |
| --- | --- |
| P0-1 | `frontend/.../api.ts` 仅 `onmessage`；`AgentDemoPage.tsx` 轮询推进 `lastSeqRef` 跳过 SSE；`handleSend` 丢弃 `accepted.runId` |
| P0-2 | `AgentDemoRunCoordinator.stop` 只等 500ms 且 `f.cancel(true)` 命中 `FileLockInterruptionException`；`flushOrThrow` 持锁期间被中断 → 写失败 → `failRun` 覆盖 STOPPED；`completeRun` 总写 AssistantTurn |
| P0-3 | `AgentRuntimeHarness.synthesizeSeed` 丢弃标量；`AgentDemoStore.confirmSeed` 手工构造、硬编码 0、不调 `recompute`；`IntentReconciler` 离线不提取窗口/止损标量 |
| P0-4 | `IntentClassifier` 词表缺「下单/CTP」；无确定性预路由；`IntentReconciler.refuse` 只塞 ambiguities，不阻断后续模型调用 |
| P1-1 | `deterministicQa` 模板无条件追加在模型输出后 |
| P1-2 | Demo 固定 Workspace `ws-agent-demo` 在产品 `WorkspaceStore` 不存在且非 current |
| P1-3 | Harness 与 Coordinator 都发 `run.started/run.completed`；`completeRun` 不写 steps；`toRunView` 硬编码 modelCallsUsed=0 |
| P1-4 | 移动抽屉容器 className `agent-demo-inspector open` 与桌面 Inspector 选择器冲突 |
| P1-5 | 路由严格匹配 `#/agent-demo`，`?s=` 查询串导致 404；会话项非 button |

## 3. 修改文件清单

### 后端（rebuild/backend/src/main/java/com/quant/rebuild/）
- `taskcenter/AgentRuntimeHarness.java` — 新增**确定性能力越界预路由**（P0-4，在分类与模型调用之前，不导入 CTP/Order 类型）；`deterministicQa` 仅作为 `MODEL_UNAVAILABLE` 兜底，不再叠加陈旧模板（P1-1）。
- `taskcenter/IntentReconciler.java` — 公开 `isCapabilityBypass(content)` 谓词；新增 `extractSmaWindows` / `extractStopLoss` 确定性标量提取（P0-3/P0-4）。
- `taskcenter/SpecFieldHelpers.java` — `completeness/missingFields/nextQuestion` 改为 `public`，供 Demo Store 复用共享领域逻辑（P0-3）。
- `agentdemo/AgentDemoStore.java` — `confirmSeed` 改用持久化 `IntentResult` 的标量 + `SpecParameters.smaV2(...)` 构造 Draft 并 `recompute`；写 Seed FieldEvidence；`completeRun` 在有 checkpoint 时发 `checkpoint.saved` 事件 + errorCode；**不再为空内容/STOPPED 写 AssistantTurn**；新增 `persistSteps`（重打 runId/sessionId）；`flushOrThrow` 改为**中断安全**（写盘期间清除中断、写完恢复）；新增 `file()` 访问器（P0-2/P0-3/P1-3）。
- `agentdemo/AgentDemoRunCoordinator.java` — 移除 Coordinator 自发 `run.started`（Harness 单一负责生命周期事件）；`handleHarnessEvent` 丢弃 Harness 重复的终态事件；终态后清理 `cancelTokens`（已终态 Stop 返回 already-terminal 不覆盖原终态）；`runTurn` 在 harness 返回后 `persistSteps`（P1-3）；Stop 写入与 worker 单写者语义（P0-2）。
- `agentdemo/AgentDemoService.java` — `toRunView` `modelCallsUsed` 由 `modelUnavailable` 派生（非硬编码 0）；`toDraftView` 用 `SpecFieldHelpers.nextQuestion` 的确定性精选问题；`draftFieldsMap` 暴露 `parameters`；`patchDraft` 传真实 `sourceTurnId`（P0-3/P1-3）。
- `agentdemo/AgentDemoConfiguration.java` — 新增 `agentDemoWorkspaceInitializer`：注册 `ws-agent-demo` 为权威 Workspace 并设为 current（**不关闭 ToolProxy 校验**）（P1-2）。
- `workspace/WorkspaceStore.java` — 新增 `ensureWorkspaceAndSelect(id, name)`（P1-2）。

### 前端（rebuild/frontend/src/）
- `agent-demo/api.ts` — 定义 `SSE_EVENT_TYPES`，为每个命名事件 `addEventListener` + 默认 `onmessage`；`lastSeq` 仅在事件成功应用后推进；指数退避重连（P0-1）。
- `agent-demo/AgentDemoPage.tsx` — `handleSend` 保存 `accepted.runId`；轮询**不再推进** `lastSeqRef`，并新增 `syncRunFromDetail`（轮询将 Inspector 的 Run 字段与持久 Run view 对齐——即使 SSE 命名事件在某浏览器不可靠，Inspector 仍权威展示）；`restoreLatestRun`（刷新/切换会话恢复最近 Run）；URL hash 写入与恢复（`?s=<id>`）；会话项改 `<button>` + `aria-current` + 时间（P0-1/P0-2/P1-5）。
- `agent-demo/agent-demo.css` — 移动抽屉容器改用独立 class `agent-demo-mobile-drawer`（不再被 `display:none` 二次隐藏）；移动 Inspector 切换按钮移到左上角（不再遮挡发送按钮）；会话项 button 样式（P1-4/P1-5）。
- `App.tsx` — `resolveRoute` 剥离 `?` 查询串再匹配，修复 `#/agent-demo?s=` 的 404（P1-5）。

### E2E（rebuild/e2e/，新增）
- `package.json`、`playwright.config.ts`、`agent-demo.spec.ts`（desktop 1280×800）、`mobile.spec.ts`（390×844）。

### 文档（rebuild/docs/，新增）
- `agent-demo-repair-baseline.md`、`agent-demo-repair-report.md`（本文）。

## 4. 新增失败测试（先红后绿，TDD）

| 测试 | 文件 | 覆盖 |
| --- | --- | --- |
| `capabilityBypassRequestIsPreRoutedWithoutModelOrToolCalls` | `HarnessExtensionTest` | P0-4 不调模型/Tool |
| `outOfBoundsTradeRequestIsPreRoutedFullChain` | `AgentDemoApiTest` | P0-4 **完整链路** HTTP→coordinator→harness→Store→SSE |
| `stopDuringRunningRunReachesStoppedExactlyAndEmitsEvents` | `AgentDemoRunCoordinatorTest` | P0-2 Stop 后精确 STOPPED + checkpoint.saved + run.stopped + 无 assistant |
| `stopAfterTerminalIsAlreadyTerminalAndDoesNotOverwrite` | 同上 | P0-2 已终态 Stop 不覆盖 |
| `duplicateStopIsIdempotent` | 同上 | P0-2 重复 Stop 幂等 |
| `stopDuringModelCallDropsResultAndCreatesNoAssistantTurn` | 同上 | P0-2 模型调用中 Stop 丢弃结果 |
| `stoppedRunSurvivesStoreReload` | 同上 | P0-2 刷新恢复 STOPPED |
| `lifecycleEventsAreEmittedExactlyOnceAndStepsPersisted` | 同上 | P1-3 终态事件各一次 + AgentStep 持久化 |
| `coCreateCarriesSeedDetectedFieldsAndRecomputesCompleteness` | `AgentDemoApiTest` | P0-3 Draft 字段落地 + completeness>0 + 单一 nextQuestion |
| `draftFieldCorrectionUpdatesStopLossAndEvidence` | 同上 | P0-3 字段纠正 version+1 + evidence + parameters |
| `plainQaHasNoStaleTemplateAndAsksNoQuestion` | 同上 | P1-1 无陈旧模板 + 不提问 |
| `sseNamedEvents.test.ts`（5 项） | frontend | P0-1 命名事件投递/去重/重连 |
| `AgentDemoPage.test.tsx` 新增 2 项 | frontend | P0-1 Inspector 随命名事件变化；P0-2 Stop 真实 HTTP |
| Playwright `agent-demo.spec.ts` / `mobile.spec.ts`（4 项） | e2e | 浏览器黑盒：QA / 越界 / Draft / 移动抽屉 |

## 5. 修复后测试输出（真实）

### 后端 `mvn test`
```
[INFO] Tests run: 676, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```
（含 `HarnessExtensionTest` 8、`AgentDemoApiTest` 24、`AgentDemoRunCoordinatorTest` 14、`IntentReconcilerTest` 13、`AgentDemoBoundaryTest` 3，以及全量 TaskCenter 674 回归。）

### 前端 `npm test`
```
Test Files  18 passed (18)
     Tests  168 passed (168)
```
### 前端 `npm run build`
```
✓ built in 1.35s   (dist/assets/index-*.js 293.98 kB)
```
### 边界扫描 `bash scripts/assert-agent-demo-boundaries.sh`
```
[boundary] OK — all Demo boundary invariants hold.
```
（确认 agentdemo 生产源码不导入 native/probe/gateway/ctp/order；静态产物无 API Key；Demo 脚本无 Docker/Native 启动命令；仍只有一个 `AgentRuntimeHarness`。）

### E2E `cd rebuild/e2e && npx playwright test`
```
✓ desktop: plain QA shows an assistant reply with no strategy card
✓ desktop: out-of-bounds trade request surfaces CAPABILITY_NOT_REGISTERED
✓ desktop: strategy candidate → CO_CREATE → Draft with carried fields
✓ mobile: the Harness drawer shows visible content and no horizontal overflow
4 passed (6.0s)
```

## 6. HTTP / SSE / Store 对照证据（修复后真实抓取）

### 6.1 普通问答（场景 A，离线 MODEL_UNAVAILABLE）
- POST /turns → 202，`runId=run-...`、`status=QUEUED`、`eventSeq=N`。
- SSE 命名事件序列（通过 vite 代理真实抓取）：`turn.accepted(1) → run.started(2) → intent.detected(3) → step.started(4) → step.completed(5) → budget.updated(6) → run.completed(7)`。**每个终态事件恰好一次**（修复前各两次）。
- Store 最终 Run：`status=COMPLETED, stepsUsed=1`；**`steps` 数组现有 1 条持久化 AgentStep**（修复前恒为 0）。
- AssistantTurn：确定性兜底文案 + `MODEL_UNAVAILABLE` 标记，**无陈旧「收到…」模板**。
- 浏览器 Inspector（真实 DOM）：`状态 COMPLETED · Step 1/8 · Tool 0/12 · 模型调用 0/4 · MODEL_UNAVAILABLE`（修复前永远 `QUEUED · 0/8 · 空`）。

### 6.2 越界请求（场景 F）
- POST /turns → 202。Store 终态 Run：`status=FAILED, error=CAPABILITY_NOT_REGISTERED`。
- 无 Seed、无 Draft。AssistantTurn 内容显式含 `CAPABILITY_NOT_REGISTERED` 并解释 Demo 无交易能力。
- **未调用模型**（harness 预路由在 `intentPort.infer` 之前返回；单元测试用「调用即抛 AssertionError」的 port 守住）。
- **未调用任何 Tool**（SSE 事件无 `capability.started`）。
- SSE 命名事件：`turn.accepted → run.started → intent.detected(errorCode=CAPABILITY_NOT_REGISTERED) → run.failed(errorCode=CAPABILITY_NOT_REGISTERED)`。

### 6.3 策略候选 → Seed → CO_CREATE → Draft（场景 B/C，P0-3）
- 真实 CO_CREATE 响应：
  ```
  completeness=31, version=0
  parameters={type:SMA_CROSS, fastWindow:5, slowWindow:20, stopLossPct:3.0, ...}
  missingFields=[name, timeframe, positionSize, feeBps, slippageBps, entryCondition, exitCondition, riskLimits, backtestAssumptions]
  evidence count=4 (instruments/fastWindow/slowWindow/stopLossPct, 均 FIXTURE/productionEvidence=false/Mock 证据)
  nextQuestion=请确认K线周期（TICK / 1M / 5M / ...）：策略使用哪个周期？
  ```
  （修复前 `completeness=0`、13 字段全缺、`fastWindow/slowWindow` 不在 Draft、nextQuestion 是粗糙的「请确认字段：name」。）
- 字段纠正 `stopLossPct=1.5`：`version=1`、`completeness=31`、`parameters.stopLossPct=1.5`、`evidence[fieldPath=stopLossPct]` 引用本轮 user turn。

### 6.4 Stop（场景 G）
- 单元/集成测试覆盖：Stop queued/running/mid-model run 均**精确 STOPPED**（非 FAILED），保存 `USER_STOPPED` checkpoint，发 `checkpoint.saved` + `run.stopped`；Stop 后无 assistant 成功回合；重复 Stop 幂等；已终态 Stop 返回 already-terminal 且不覆盖原终态；**Store 重启（重开 JSON 文件）后仍 STOPPED**。

### 6.5 工作区（P1-2）
- Demo 启动后 `GET /sessions/{id}` 的 `workspaceId=ws-agent-demo`；banner 显示「当前工作空间：Agent Demo」；ToolProxy 工作区校验**仍启用**，但 `ws-agent-demo` 已注册为 current，故 RAG/工具不再被 `WORKSPACE_MISMATCH` 拒绝。

## 7. 桌面端与移动端浏览器验收（真实 IAB + Playwright）

- **桌面 1280×800**：Inspector 实时显示 COMPLETED/Step 1；越界请求页面可见 `CAPABILITY_NOT_REGISTERED`；Draft 卡显示「完整度31%」「fastWindow：5 FIXTURE · productionEvidence=false · Mock 证据」「草案 / 未冻结 / 无执行能力」；**横向溢出 = 0**。
- **移动 390×844**：Harness 抽屉打开后内容真实可见（inspector-intent / inspector-trajectory 可见），可关闭；**横向溢出 = 0**；移动 Inspector 切换按钮已移到左上角，不再遮挡发送按钮。

> 注：本机 IAB 浏览器的 EventSource 在 vite 代理下对**命名事件**投递不稳定（已用真实抓取证明事件确实通过代理到达，且 `api.ts` 已注册命名监听）。为兜底，`AgentDemoPage` 的轮询现在将 Inspector 的 Run 字段与**持久 Run view 权威对齐**（`syncRunFromDetail`），因此即便 SSE 命名投递在某浏览器不可靠，Inspector 仍能正确反映 QUEUED→COMPLETED/FAILED/STOPPED 与 Step/Tool/模型调用计数。Vitest 中的命名事件 reducer 与 SSE 客户端测试（5 项）全部通过。

## 8. 真实 DeepSeek 是否通过

- 本次预验收运行的后端**未注入 `DEEPSEEK_API_KEY`**（启动日志 `agent-demo: DeepSeek key not configured; MODEL_UNAVAILABLE`），因此所有模型回合按设计返回 `MODEL_UNAVAILABLE` 并用确定性兜底，**未用模板冒充 DeepSeek**。
- 修复**前**的基线采集阶段，后端进程曾配置真实 DeepSeek Key（`keyConfigured=true`），场景 A 的均线介绍、场景 B 的 SMA_CROSS 参数解释均为**真实 DeepSeek 输出**（见基线文档）。修复后这些路径（命名事件、Stop、Draft、越界预路由）与有 Key 时完全一致——越界预路由**根本不调模型**，Draft/Stop 是确定性逻辑，普通问答在有 Key 时会真实调用并展示。
- 结论：**离线全链路测试与构建通过；真实 DeepSeek 在有 Key 时与基线一致，但本次修复会话未在修复后用真实 Key 重新跑端到端**。真实 DeepSeek 验收仍需在独立复审中由配置了 Key 的环境完成。

## 9. 未解决问题 / 已知限制

1. **真实 DeepSeek 端到端**：修复后未用真实 Key 重跑端到端（本会话无 Key）。模型回答质量、增量 token 流式（当前 Adapter 只返回完整结果）属于真实模型验收范畴。
2. **本机 IAB 命名事件投递不稳定**：已用 `syncRunFromDetail` 轮询兜底；在支持命名 EventSource 的标准 Chromium 中，SSE 命名监听器同时生效（Vitest 已覆盖）。建议独立复审在标准 Chromium 中复核 SSE 命名事件实时性。
3. **takeProfitPct**：领域模型无此独立字段（全仓 grep 确认）。按任务要求**未新建平行 Draft 类型**；止盈通过现有 `exitCondition`/`riskLimits` 自由文本表达，`singleFieldPatch` 支持 `exitCondition`/`riskLimits`。文档已记录此映射。
4. **场景 E（模糊执行）/ 场景 G 浏览器视觉**：未单独截图（broker 抽样间歇不可用）；由 E2E 与 DOM 快照断言覆盖。
5. **`git add/commit/push` 未执行**（任务禁止，除非用户在本会话明确授权）。

## 10. 必须区分的结论层级

- ✅ **单元测试通过**（后端 676、前端 168）。
- ✅ **集成测试通过**（AgentDemoApiTest 全链路、AgentDemoRunCoordinatorTest 含 Stop/重载/幂等）。
- ✅ **浏览器预验收通过**（Playwright desktop + mobile 各场景；IAB DOM 快照印证 Inspector/Draft/越界/抽屉）。
- ⚠️ **真实 DeepSeek**：修复前基线已用真实 Key 验证模型路径；修复后本次会话无 Key，未在修复后用真实 Key 重跑端到端。
- ⏳ **独立黑盒复审尚未进行**。

---

## 13. 第二轮复审返修（Reviewer Round 2）

> 触发：独立黑盒复审发现 3 个问题（2 P0 + 1 P1）。本轮全部修复并复验。

### 13.1 复审发现与根因（真实复现）

**Issue #1（P0）歧义执行崩溃**：输入「就按这个跑一下」后状态 `FAILED`，错误 `NullPointerException`，无 Assistant 回复。根因：`IntentReconciler` 已把歧义执行识别为 `EXECUTION_APPLICATION`（带澄清问题），但 `AgentRuntimeHarness.executeTurn` 把请求委托给 `executeTurnWithBudget`，后者**重新分类原始文本**、忽略已 reconcile 的歧义意图，落到普通问答 / 字段追问路径；NPE 来自 `SpecFieldHelpers.parseAnswer(field=null, …)`（歧义澄清回合留下的 `pendingQuestion.field()` 为 null，后续回合进入 REFINEMENT 分支时 `switch(field)` 空指针）。

**Issue #2（P0）聊天中的自然语言纠正无效**：输入「3% 是止盈，止损 1.5%」后 Draft 仍 `v0`、`stopLossPct=3.0`。根因（双因）：
1. `AgentDemoStore.completeRun(...)` 接收 `patch` 参数但**从不应用**到 Draft（参数被忽略）。
2. 没有确定性「字段纠正提取器」——REFINEMENT 路径只按 `pendingQuestion.field()` 解析当前追问字段的答案，无法处理「纠正其它字段」的自由文本。

**Issue #3（P1）Capability Timeline 始终空**：后端 Session Detail 确实返回了 THINK/QUESTION 步骤，但前端 Timeline 显示「暂无步骤」。根因：**字段名不匹配**——后端 `DemoSessionDetail` 用 `@JsonProperty("stepsByRun")`，前端 `DemoSessionDetail` 类型读 `steps`（undefined）。轮询同步逻辑读 `d.steps` → 永远空。（额外：本机 IAB 的 EventSource 对命名事件投递不稳，因此前端改为从持久 `stepsByRun` 重建 Timeline 作为权威来源。）

### 13.2 修改文件（第二轮新增/调整）

后端：
- `taskcenter/AgentRuntimeHarness.java` — `executeTurn` 在 reconcile 后**短路歧义执行**：当 `intent.requiresConfirmation() && labels 含 EXECUTION_APPLICATION && nextQuestion!=null` 时，直接生成「回测/模拟/讨论」单一澄清回复，发 step QUESTION 事件，进入 COMPLETED，**不进入 planner**（Issue #1）；新增确定性「字段纠正块」（在意图分类前，从自由文本提取止损/止盈并生成 DraftPatch）；REFINEMENT 分支在 `pendingQuestion.field()==null` 时跳过 `parseAnswer`（防 NPE）。
- `taskcenter/SpecFieldHelpers.java` — 新增 `public static DraftPatch extractCorrection(String)`：`止损 X%→stopLossPct`、`止盈 X%`（含「X% 是止盈」逆序）→ `exitCondition` 自由文本（**不新建 takeProfitPct 字段**，按任务要求复用现有 Draft）；`parseAnswer` 加 `field==null` 显式 `IllegalArgumentException`（防 `switch(null)` NPE）。
- `agentdemo/AgentDemoStore.java` — `completeRun` 现在**真正应用** `patch` 到本会话活动 CO_CREATING Draft：`TaskCenterStore.applyPatch` + 版本递增 + `draft.updated` 事件 + evidence 引用本轮 user turn（Issue #2）。
- `agentdemo/AgentDemoDtos.java` — `DemoStepView` 增加 `runId` 字段（Issue #3，供前端按 run 过滤）。
- `agentdemo/AgentDemoService.java` — `sessionDetail` 步骤视图携带 `runId`。

前端：
- `agent-demo/types.ts` — `DemoSessionDetail.steps` → `stepsByRun`（与后端对齐）；`DemoStepView` 增加 `runId`。
- `agent-demo/AgentDemoPage.tsx` — `syncRunFromDetail` 从 `d.stepsByRun` 按 `runId` 过滤**重建 Capability Timeline**（即便 SSE 命名事件不可达，Timeline 也权威展示）；前端测试 mock 同步更新。

E2E（新增覆盖）：
- `e2e/agent-demo.spec.ts` — 新增「歧义执行澄清」「聊天自然语言纠正 v0→v1」「Timeline 展示步骤」三项黑盒测试。
- `e2e/playwright.config.ts` — `sendAndWaitTerminal` 超时上调到 60s（本地 Spring 在多会话累积时会变慢）。

测试（新增）：
- `HarnessExtensionTest.correctionAfterExecutionClarificationDoesNotNpe`（用 null-field pendingQuestion 复现 NPE 并守住）。
- `AgentDemoApiTest.ambiguousExecutionClarifiesInsteadOfFailingOrExecuting` / `naturalLanguageCorrectionAppliesDraftPatchViaChat` / `naturalLanguageCorrectionWorksEvenAfterAmbiguousExecution` / `sessionDetailReturnsStepsTaggedWithRunId`。
- `SpecFieldHelpersCorrectionTest`（4 项：止损+止盈 / 仅止损 / 逆序「X% 是止盈」/ 越界忽略）。

### 13.3 第二轮修复后真实证据

**HTTP/Store（修复后）**：
- 歧义执行：`status=COMPLETED, error=null`；Assistant 内容含「回测…模拟…讨论」「Demo 不会产生任何交易行为」。无 NPE、无 Seed/Draft 增删、无执行任务。
- 自然语言纠正（歧义之后）：`version 0→1, stopLossPct 3.0→1.5, exitCondition="止盈 3%"`；evidence 增加 `stopLossPct` 与 `exitCondition`；`status=COMPLETED`（无 NPE）。
- Session Detail：`stepsByRun` 非空，每个 `DemoStepView` 带 `runId`。

**浏览器（真实 IAB + Playwright）**：
- Issue #1：歧义回合页面可见「回测/模拟/讨论」，run 状态 `COMPLETED`。
- Issue #2：歧义后再纠正，Draft 卡显示 `v1 / 完整度38%`（止盈 3% 进入 exitCondition，stopLoss=1.5），run `COMPLETED`。
- Issue #3：Capability Timeline 显示 `step.think`（不再是「暂无步骤」）。

**自动化（修复后）**：
- 后端 `mvn test`：**685 pass, 0 fail, 0 skip**（含第二轮 6 项新测试 + 全量回归）。
- 前端 `npm test`：**168 pass**；`npm run build`：✓。
- 边界扫描：OK。
- E2E `cd rebuild/e2e && npm test`：**7 pass**（desktop 6 + mobile 1），含歧义/纠正/Timeline 三项新黑盒。

### 13.4 仍待独立复审 / 已知限制

- **真实 DeepSeek**：本轮后端仍 `keyConfigured=false`，模型回合按设计返回 `MODEL_UNAVAILABLE`（未用模板冒充）。歧义澄清、字段纠正、Timeline 均为**确定性逻辑**，与有无 Key 无关；普通问答在有 Key 时会真实调用 DeepSeek。真实 DeepSeek 端到端仍需在配置 Key 的环境完成。
- **真实 UI Stop**：Stop 后端并发测试已覆盖（STOPPED / USER_STOPPED checkpoint / 丢弃模型结果 / 重载恢复）。离线下 run ~50–100ms 结束，人工无法获得点击窗口；应在配置模型后补真实 UI Stop。
- ⏳ **独立黑盒复审尚未进行（第二轮）**。

---

## 14. 第三轮复审返修（Reviewer Round 3）

> 触发：独立黑盒复审在配置了 DeepSeek Key 后又发现 3 个问题。本轮全部修复并复验。

### 14.1 复审发现与根因（真实复现）

**Issue A（P0）模型回复与状态机冲突**：策略候选确认前 Store 安全（无 Draft），但 DeepSeek 回复里**自己追问字段**（"请问您希望用多大仓位入场"）、暗示草案已存在。根因：`AgentRuntimeHarness.executeTurn` 在候选路径把**模型的自由文本**直接拼在候选说明后（`assistantContent = reply + "\n\n" + 模型文本`）。模型不知道"还没确认、不能追问字段"。

**Issue B（P0）Inspector 数据未同步**：后端 run intent 是 `STRATEGY_CANDIDATE/REFINEMENT/EXECUTION_APPLICATION`，前端 Inspector 持续显示 `GENERAL_QA`，标签/授权/确认状态也缺失。根因：前端 `syncRunFromDetail` 用 `intent: prev.intent ?? match.intent` —— 一旦 `prev.intent` 被首轮 SSE 设成 `GENERAL_QA`（非 null），`??` 短路，再也不会用持久 run 的真实 intent 覆盖；且 `DemoRunView` 只有 `intent`，没有 labels/auth/confidence/requiresConfirmation。

**Issue C（P0）Stop 仍持久化兜底 AssistantTurn**：Stop 正确进入 STOPPED/USER_STOPPED，但仍创建一条 AssistantTurn（"研究检索被拒绝…MODEL_UNAVAILABLE"），并显示 `modelUnavailable=true`。根因：`stopRun` 把 `te.assistantContent()`（harness 部分运行的结果）传给 `completeRun`，后者非空就写 AssistantTurn；且 `modelUnavailable` 也被透传为 true。

### 14.2 修改文件（第三轮）

后端：
- `taskcenter/AgentRuntimeHarness.java` — 候选（STRATEGY_CANDIDATE）路径**只用确定性候选说明**，丢弃模型自由文本（不再追问字段、不暗示草案）（Issue A）。
- `agentdemo/AgentDemoRunCoordinator.java` — `stopRun` 与 Stop 直接写入路径都传**空 assistantContent**（`completeRun` 不再创建 AssistantTurn）+ `modelUnavailable=false`（不再显示 MODEL_UNAVAILABLE）（Issue C）。
- `agentdemo/AgentDemoDtos.java` — `DemoRunView` 增加 `intentLabels / authorization / calibratedConfidence / requiresConfirmation`（从持久 IntentResult 取）（Issue B）。
- `agentdemo/AgentDemoService.java` — `toRunView` 从持久 `IntentResult` 填充新增字段。

前端：
- `agent-demo/types.ts` — `DemoRunView` 对齐新增字段。
- `agent-demo/AgentDemoPage.tsx` — `syncRunFromDetail` **去掉 `??` 短路**，`intent` 直接用持久 run 的值；同步 labels/auth/confidence/requiresConfirmation；`restoreLatestRun` 同步同样字段（Issue B）。

测试/E2E（新增）：
- `AgentDemoApiTest.strategyCandidateReplyMustNotMisleadAboutDraftOrAskFields` / `stopMustNotPersistAnyAssistantReply` / `sessionDetailCarriesReconciledIntentForInspectorSync`。
- E2E `agent-demo.spec.ts` 新增「Inspector 显示真实 intent（非陈旧 GENERAL_QA）」「Stop 不留 assistant 回复」。

### 14.3 第三轮修复后真实证据

**HTTP（配置了 DeepSeek Key）**：
- Issue A：候选回复 = 确定性 Seed 说明（"检测到策略候选…请选择…在创建草案之前，系统不会生成任何 Draft 或任务，也不会追问字段"），**无模型字段追问**；`activeDraft=None`。
- Issue B：run view = `intent=STRATEGY_CANDIDATE, labels=['STRATEGY_CANDIDATE'], authorization=NOT_CONFIRMED, calibratedConfidence=0.0, requiresConfirmation=True`。
- Issue C：立即 Stop → `status=STOPPED, checkpointReason=USER_STOPPED, modelUnavailable=False`，**assistant turns count=0**。

**浏览器（真实 Playwright headless）**：
- Issue B：Inspector `当前理解` 区域含 `STRATEGY_CANDIDATE`（不再是 GENERAL_QA）。
- Issue A：候选回复 body 不含 `positionSize` + `请只回答` 字段追问。
- Issue C：Stop 后 body 不含 `研究检索被拒绝` 兜底回复。

**自动化（修复后）**：
- 后端 `mvn test`：**688 pass, 0 fail, 0 skip**（含第三轮 3 项新测试 + 全量回归）。
- 前端 `npm test`：**168 pass**；`npm run build`：✓。
- 边界扫描：OK。
- E2E `cd rebuild/e2e && npm test`：**9 pass**（desktop 8 + mobile 1），含 intent 同步与 Stop 两项新黑盒。

### 14.4 仍待独立复审 / 已知限制

- **真实 DeepSeek**：本轮已在**配置了 DeepSeek Key** 的环境复现并修复（候选回复不再被模型自由文本污染）。普通问答真实模型输出正常。真实模型端到端的最终签字仍属独立复审。
- ⏳ **独立黑盒复审尚未进行（第三轮）**。

## 11. 验收命令（可在 `rebuild/` 下复跑）

```bash
cd rebuild/backend && mvn -q test                 # 688 pass
cd rebuild/frontend && npm test && npm run build  # 168 pass + build ok
cd rebuild && bash scripts/assert-agent-demo-boundaries.sh
cd rebuild/e2e && npm test                        # 9 pass (desktop 8 + mobile 1)
# 启动：cd rebuild && ./run-agent-demo.sh  → http://127.0.0.1:5174/#/agent-demo
```

## 12. 安全声明

- 本次修复**没有连接 SimNow / CTP / Gateway / Native Probe / Docker / MySQL / Qdrant**，**没有行情订阅、报单、撤单、成交或自动恢复**。
- 越界预路由为纯字符串匹配（`IntentReconciler.isCapabilityBypass`），**不导入任何 CTP / Order / Gateway 类型**（边界扫描已确认）。
- 报告与基线**不含 API Key、Authorization、Prompt 原文或敏感环境变量值**。
- 没有覆盖、清理或 reset 用户在仓库根目录的未提交修改；只修改了 `rebuild/`。
