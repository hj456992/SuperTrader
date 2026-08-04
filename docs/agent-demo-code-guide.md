# Agent Runtime Harness Demo — 代码阅读说明

> 适用范围：`rebuild/` 下的 Agent 交互 Demo（Spring Boot 后端 + React/Vite 前端）。
> 目标读者：第一次接手这套代码、需要在 IDEA 里读懂并修改的开发者。
> 配套文档：设计 `superpowers/specs/2026-08-03-agent-runtime-harness-demo-design.md`；修复报告 `agent-demo-repair-report.md`；修复前基线 `agent-demo-repair-baseline.md`。

本文不讲"做了什么修复"（看修复报告），而是讲**代码长什么样、怎么读、改哪里**。

---

## 1. 一句话架构

> 一个 Spring Boot 单体 + **唯一** `AgentRuntimeHarness`（模型/Agent/Capability 的唯一入口）+ 原子 JSON Store + SSE 实时推送 + React 三栏聊天页。Demo **不接** SimNow/CTP/Gateway/Docker。

两个绝对不能违背的边界：
- **只有一个 Harness**。`agentdemo` 是应用层（HTTP/SSE/Store/UI），不是第二套 Agent 内核。
- **Demo 没有任何交易能力**。`ctp.order.*` / `execution.*` 永远不在 CapabilityRegistry 里。

---

## 2. 分层与目录

```
rebuild/
├── backend/  (Spring Boot 3.3.5, Java 17, AgentScope 2.0)
│   └── src/main/java/com/quant/rebuild/
│       ├── SuperTraderApplication.java   ← main 入口
│       ├── agentscope/                        ← AgentScope/DeepSeek 接入（key 只进环境变量）
│       ├── workspace/                         ← WorkspaceStore（Demo 注册 ws-agent-demo）
│       └── taskcenter/                        ← 【领域内核，Demo 复用】
│           ├── AgentRuntimeHarness.java       ← ★唯一 Harness（815 行，最核心）
│           ├── IntentClassifier.java          ← 确定性意图分类（纯规则，无 LLM）
│           ├── IntentReconciler.java          ← 规则+模型 意图合并（规则优先）
│           ├── IntentResult.java              ← 结构化意图契约
│           ├── CapabilityRegistry.java        ← Capability 白名单（无交易）
│           ├── ToolProxy.java                 ← 工具调用网关（含工作区校验）
│           ├── StrategySpecDraft.java         ← 策略草案领域对象
│           ├── StrategySpecValidator.java     ← 确定性校验
│           ├── SpecFieldHelpers.java          ← 字段逻辑（完整度/下一问/纠正提取）
│           ├── SpecParameters.java            ← SMA_CROSS 等结构化参数
│           └── ...
│       └── agentdemo/                         ← 【Demo 应用层】
│           ├── AgentDemoController.java       ← REST + SSE 路由（/api/v1/agent-demo）
│           ├── AgentDemoService.java          ← 应用编排（guard→T1→submit→202）
│           ├── AgentDemoStore.java            ← 原子 JSON Store（T1/T2/T3 事务语义）
│           ├── AgentDemoEventHub.java         ← SSE fan-out（重放+实时）
│           ├── AgentDemoRunCoordinator.java   ← 异步执行+Stop+Retry
│           ├── AgentDemoConfiguration.java    ← Spring wiring
│           ├── DeepSeekIntentAdapter.java     ← DeepSeek 真实意图适配器
│           └── DemoSensitiveContentGuard.java ← 敏感内容守卫
├── frontend/ (React 18 + Vite + Vitest)
│   └── src/agent-demo/
│       ├── AgentDemoPage.tsx                  ← ★主页面（三栏+状态机）
│       ├── ConversationPane.tsx               ← 对话/输入区
│       ├── HarnessInspector.tsx               ← 右栏 Inspector
│       ├── StrategyArtifactCard.tsx           ← Seed/Draft 卡片
│       ├── api.ts                             ← REST+SSE 客户端
│       ├── runReducer.ts                      ← 纯函数 SSE reducer
│       └── types.ts                           ← 与后端契约对齐的类型
├── e2e/ (Playwright)
│   ├── agent-demo.spec.ts                     ← desktop 1280×800 黑盒
│   └── mobile.spec.ts                         ← mobile 390×844
├── scripts/assert-agent-demo-boundaries.sh    ← 边界扫描
└── run-agent-demo.sh                          ← 唯一启动入口
```

---

## 3. 一次 Turn 的完整请求链路（最重要，先读这个）

用户在聊天框发一条消息后，从浏览器到 Store 再到 SSE 的完整时序：

```
浏览器 AgentDemoPage.handleSend
  │  POST /api/v1/agent-demo/sessions/{id}/turns  (Idempotency-Key)
  ▼
AgentDemoController.postTurn
  ▼
AgentDemoService.acceptTurn
  ├─ 1. DemoSensitiveContentGuard.check(content)     ← 敏感内容拦截（key/密码形态）
  ├─ 2. AgentDemoStore.acceptTurn(...)                ← T1：原子写 UserTurn + QUEUED Run + turn.accepted 事件 + 幂等
  ├─ 3. AgentDemoRunCoordinator.submit(...)           ← 提交到有界线程池（每 session 最多 1 个活动 Run）
  └─ 4. 返回 202 AcceptedTurnResponse（runId/turnId/eventSeq）   ← 浏览器立刻拿到 runId
        │
        │  （异步 worker 线程）
        ▼
AgentDemoRunCoordinator.runTurn
  ├─ 构造 HarnessTurnRequest（服务端 runId/预算/cancel token）
  ├─ store.appendEvent(run.started)                   ← 注意：生命周期事件由 Harness 发，Coordinator 不重发
  ├─ harness.executeTurn(req, observer)               ← ★唯一 Harness 入口
  │     ├─ 确定性分类 IntentClassifier.classify
  │     ├─ (可选) DeepSeekIntentAdapter.infer         ← 真实模型意图（无 key 则 MODEL_UNAVAILABLE）
  │     ├─ IntentReconciler.reconcile                 ← 规则优先合并
  │     ├─ 【能力越界预路由】(P0-4)                    ← 命中则直接 FAILED+CAPABILITY_NOT_REGISTERED，不调模型/Tool
  │     ├─ 【歧义执行短路】(round2 Issue#1)            ← EXECUTION_APPLICATION → 单一澄清，COMPLETED
  │     ├─ executeTurnWithBudget（确定性 planner）
  │     │     ├─ 【字段纠正提取】(round2 Issue#2)       ← 自由文本→DraftPatch
  │     │     ├─ STRATEGY_CANDIDATE → 生成 Seed（不建 Draft）
  │     │     ├─ STRATEGY_REFINEMENT → 解析字段答案
  │     │     ├─ RESEARCH → RAG 工具（gated）
  │     │     └─ (可选) reply 模型调用
  │     └─ observer 事件 → handleHarnessEvent → store.appendEvent（T2，每步原子写）
  ├─ 【候选路径】(round3 IssueA)                       ← 只保留确定性 Seed 说明，丢弃模型自由文本
  ├─ persistSteps                                      ← AgentStep 持久化（重打 runId）
  └─ store.completeRun(...)                            ← T3：AssistantTurn + IntentResult + Seed/Draft patch + 终态 Run + 终态事件
        │  （同时：completeRun 应用 chat-driven patch、STOPPED 不写 AssistantTurn）
        ▼
AgentDemoEventHub.publish                              ← fan-out 给所有 SSE 订阅者
  ▼
浏览器 EventSource                                      ← api.ts 为每个命名事件 addEventListener
  ▼
runReducer.applyEvent → setRun                          ← Inspector 实时更新
```

**三个事务边界（设计 §10.1）：**
- **T1 acceptTurn**：UserTurn + QUEUED Run + turn.accepted + 幂等记录（原子）。
- **T2 每步**：AgentStep + 事件 + 预算用量（原子）。
- **T3 completeRun**：AssistantTurn + IntentResult + Seed/Draft diff + 终态 Run + 终态事件（原子）。
- 任何事务失败都 fail-closed，不向客户端宣告成功。

---

## 4. 关键类怎么读（按推荐顺序）

### 4.1 后端

**第一梯队（理解主链路）：**
1. `AgentDemoController` — 5 分钟扫一遍，看路由表。所有路由前缀 `/api/v1/agent-demo`。
2. `AgentDemoService.acceptTurn` — 看 guard→T1→submit→202 的编排。
3. `AgentRuntimeHarness.executeTurn(HarnessTurnRequest, observer)` — **最核心**。读类级 Javadoc（含中文设计要点），它列出了 6 步执行顺序。三个修复点（越界预路由/歧义短路/候选丢弃模型文本）都在这里有注释。**理解 Harness 的状态机/预算/防循环/Capacity 白名单,先读本文档第 5 节「Harness 设计」。**
4. `AgentDemoRunCoordinator.runTurn` + `stop` — 理解异步执行、Stop 单写者语义。

**第二梯队（理解状态/数据）：**
5. `AgentDemoStore` — 原子 JSON Store。重点读：`acceptTurn`(T1)、`completeRun`(T3，含 patch 应用与 STOPPED 不写回复)、`confirmSeed`(Draft 初始化+recompute)、`flushOrThrow`(中断安全)。
6. `IntentReconciler` — 规则优先合并。读 `reconcileInternal` 的 4 个分支（越界/歧义/候选/完善）。
7. `SpecFieldHelpers` — `completeness/missingFields/nextQuestion/extractCorrection`。

**第三梯队（按需）：**
8. `AgentDemoEventHub` — SSE fan-out（重放+实时+慢消费者断开）。
9. `DeepSeekIntentAdapter` — 真实模型意图（key 只进环境变量，从不写日志/Store）。
10. `CapabilityRegistry` — 白名单（确认没有交易能力）。

### 4.2 前端

1. `AgentDemoPage.tsx` — 主页面。重点读：`handleSend`(保存 runId)、`syncRunFromDetail`(Inspector 权威同步)、`restoreLatestRun`(刷新恢复)、SSE effect。
2. `api.ts` — `SSE_EVENT_TYPES` + `openEventStream`（命名事件监听，P0-1 的关键）。
3. `runReducer.ts` — 纯函数 reducer（seq 去重、终态、intent 字段）。
4. `types.ts` — 与后端契约对齐（注意 `stepsByRun` 不是 `steps`）。

---

## 5. Harness 设计（核心，必读）

> 设计权威：`superpowers/specs/...design.md` §7。实现：`taskcenter/AgentRuntimeHarness.java` + 配套 7 个类。

`AgentRuntimeHarness` 是 Demo 的**唯一 Agent 内核**——所有模型调用、Capability 调用、意图合并、预算执行、防循环都在这里。它被 `agentdemo` 应用层包裹,但应用层不是第二套内核。

### 5.1 状态机（AgentRun）

```
CREATED → QUEUED → RUNNING
                    ├→ WAITING_HUMAN     （歧义/冲突/回测/执行意图，等人工）
                    ├→ CHECKPOINTED      （预算耗尽 / 无进展 / 振荡）
                    ├→ COMPLETED         （正常完成）
                    ├→ STOPPED           （用户停止）
                    ├→ FAILED            （错误，含稳定错误码）
                    └→ BUDGET_EXHAUSTED  （预算用尽）
```

**铁律:终态不能重新进入 RUNNING。** Retry 创建新 Run 并引用旧 Run/Checkpoint。
- 常量定义:`AgentRun.STATUS_*`（`taskcenter/AgentRun.java:34-44`）。
- Demo 实际用到的终态:COMPLETED / FAILED / STOPPED / CHECKPOINTED。

### 5.2 普通回合预算（RunBudget + BudgetPolicy）

服务端固定,客户端不能改（设计 §7.4）:

| 预算 | 默认上限 | 定义 |
|---|---:|---|
| Step | 8 | `BudgetPolicy.DEFAULT_MAX_STEPS` |
| Tool 调用 | 12 | `DEFAULT_MAX_TOOL_CALLS` |
| 最终输出 | 4000 字符 | `DEFAULT_MAX_OUTPUT_CHARS` |
| 单次模型超时 | 30 秒 | `MODEL_CALL_TIMEOUT`（harness 内） |
| 单次 Tool 超时 | 10 秒 | ToolProxy 内 |
| Run 总时长 | 90 秒 | Coordinator 层 |
| 每轮追问 | 1 个 | SpecFieldHelpers.nextQuestion |
| 全 Run 模型重试 | 2 次 | harness 内 |

超限 → `RunCheckpoint.REASON_MAX_STEPS / MAX_TOOL_CALLS / MAX_OUTPUT_CHARS` → `CHECKPOINTED`。
- `RunBudget`（`taskcenter/RunBudget.java`）:record,只含 maxSteps/maxToolCalls/maxOutputChars。
- `BudgetPolicy`（`taskcenter/BudgetPolicy.java`）:`defaultBudget()` 工厂 + `*Remaining` 判定。

### 5.3 防无限循环（LoopGuard）

**设计 §7.5,实现:`taskcenter/LoopGuard.java`。** 四道闸,任一命中即停止:

| 闸 | 条件 | 信号 | LoopGuard 方法 |
|---|---|---|---|
| 预算耗尽 | steps/tools/chars 达上限 | `MAX_STEPS` 等 | `checkBudget(stepsUsed)` |
| 重复动作 | `capability + 规范化参数hash + contextVersion` 与上一次相同 | `CACHED_REPEAT`（复用旧结果）或 `LOOP_DETECTED`（阻断） | `observe(ActionFingerprint)` |
| 无进展 | Draft版本+完整度+证据集+校验问题集+待答问题 连续 3 步不变 | `NO_PROGRESS` | `observeProgress(ProgressFingerprint)` |
| 振荡 | 最近 4 个动作呈 A-B-A-B | `LOOP_DETECTED` | `observe` 内 `isOscillation()` |

- `ActionFingerprint`:`(capability, canonicalArguments, contextVersion)`——成功动作直接复用,连续两次相同阻断。
- `ProgressFingerprint`:`(draftVersion, completeness, evidenceIds, validationIssues, pendingQuestion)`。
- `NO_PROGRESS_WINDOW=3`,`OSCILLATION_WINDOW=4`。
- LoopGuard 是纯 Java、非线程安全;harness 每个 run 持有一个,每步前后检查。

### 5.4 Step 约束（每个 Step 必须产生进展）

每个 Step 至少产生下列之一,否则不算有效（设计 §7.3）:
- 新的用户可见信息 / 新证据 / Draft 字段变化 / Validation Issue 变化 / 唯一下一问题 / Run 终态。

**不接受"继续思考"为进展。** Trace 只保存结构化 `goal/action/observation/hash/status`,**不保存隐藏思维链**。

### 5.5 Run Lease（设计 §7.2,Coordinator 层实现）

Run 使用 `worker_id + lease_version + lease_expires_at`。只有持有当前 Lease Version 的 Worker 可提交下一 Step。Demo 简化为:**每个 session 同时最多一个活动 Run**（`AgentDemoRunCoordinator.activeSessions`）。Stop 通过 cancel token + `Future.cancel(true)` 协作取消。

### 5.6 Capability Registry（白名单,设计 §7.6）

**允许（`CapabilityRegistry.ALLOWED`）:**
```
strategy.read            strategy.draft.update    strategy.validate
rag.search.local         backtest.plan            backtest.result.summarize
simnow.read-only.snapshot
```

**永不注册（fail-closed,设计 §2.3）:**
```
ctp.order.submit   ctp.order.cancel   execution.resume   shell.execute
filesystem.write   network.fetch.arbitrary   capability.register   budget.expand
order / cancel-order / modify-order / trade / execution-live / live-runner
```

- `isRegistered(cap)`:`ALLOWED.contains(cap)`。对 `ctp.order.submit` 返回 false。
- `AGENT_CALLABLE`:比 ALLOWED 更窄——Agent 可直接调的子集（不含 simnow.read-only.snapshot）。
- **Demo 用到的 capability 概念**:`model.intent_classify / model.general_answer / model.extract_strategy_fields / research.mock_evidence_search / strategy.compile_draft / strategy.validate_draft / conversation.ask_one_question`。

### 5.7 HarnessObserver（安全事件端口）

Harness 通过 `HarnessObserver`（`taskcenter/HarnessObserver.java`）发布**结构化安全事件**:
- `record Event(String type, Map<String,Object> payload)`。
- `NO_OP`:无观察者的默认实现。
- **只发安全摘要**:`runId / status / step seq / capability / durationMs / budget / errorCode`。
- **永不发**:Prompt 原文、模型请求体、隐藏推理、API Key、用户敏感内容。

Demo 的 `AgentDemoRunCoordinator.handleHarnessEvent` 把这些事件透传到 Store + SSE。

### 5.8 三层意图识别（设计 §6）

```
确定性预判（IntentClassifier，纯规则）
        ↓
DeepSeek 结构化识别（IntentInferencePort / DeepSeekIntentAdapter）
        ↓
确定性合并（IntentReconciler，规则优先于模型）
```

- **规则永远优先**:模型不能授权执行、注册 Capability、扩大预算。
- 高影响动作（执行/回测）**不靠置信度**,始终进入显式确认。
- 四层意图结构:`SpeechAct + DomainObject + DesiredMutation + AuthorizationState`。

### 5.9 稳定错误码（设计 §10.2）

```
MODEL_UNAVAILABLE   MODEL_TIMEOUT   MODEL_OUTPUT_INVALID
CAPABILITY_NOT_REGISTERED   TOOL_TIMEOUT   EVIDENCE_UNAVAILABLE
NO_PROGRESS   LOOP_DETECTED   BUDGET_EXHAUSTED
SENSITIVE_CONTENT_REJECTED   IDEMPOTENCY_CONFLICT
OPTIMISTIC_LOCK_CONFLICT   PERSISTENCE_UNAVAILABLE
INVALID_STATE   FORBIDDEN   NOT_FOUND
```

Demo 用到的:`CAPABILITY_NOT_REGISTERED`（越界）、`MODEL_UNAVAILABLE`（无 Key）、`OPTIMISTIC_LOCK_CONFLICT`（Draft patch）、`USER_STOPPED`（Stop checkpoint reason）。

### 5.10 Prompt 与敏感信息边界（设计 §8）

上下文信任顺序（上覆下）:
```
SYSTEM_POLICY > SERVER_CONTEXT > CAPABILITY_SCHEMA > USER_MESSAGE > EXTERNAL_EVIDENCE
```
- User Message 和 Evidence 永远作为**数据**,不能覆盖上层策略。
- 能力检查在**模型外**执行 → Prompt Injection 无法扩大实际能力。
- 用户消息入库前做凭证形态扫描（`DemoSensitiveContentGuard`）,命中则脱敏 + 拒绝模型调用。

### 5.11 关键类速查表

| 类 | 职责 | 行数 |
|---|---|---:|
| `AgentRuntimeHarness` | 唯一内核入口,编排分类/模型/reconcile/planner/预算/事件 | ~815 |
| `HarnessTurnRequest` | 应用层指定的 runId/预算/cancel token/activeDraft/pendingQuestion 输入 | ~80 |
| `HarnessObserver` | 安全事件端口（NO_OP 默认） | ~50 |
| `LoopGuard` | 防无限循环（预算/重复/无进展/振荡） | 149 |
| `RunBudget` | 预算 record（steps/tools/outputChars） | ~20 |
| `BudgetPolicy` | 默认预算工厂 + 剩余判定 | ~40 |
| `RunCheckpoint` | Checkpoint（context/draft version/budget/reason） | ~40 |
| `AgentRun` | Run 状态机（状态常量 + lease 字段） | ~60 |
| `AgentStep` | 单步 Trace（goal/action/observation/hash,无隐藏链） | ~40 |
| `CapabilityRegistry` | Capability 白名单（无交易） | ~85 |
| `IntentClassifier` | 确定性意图分类（纯规则） | ~90 |
| `IntentReconciler` | 规则+模型合并（规则优先） | ~470 |
| `IntentResult` | 结构化意图契约 | ~80 |

---

## 6. 三轮修复的代码定位（改某个问题时去哪）

| 问题 | 文件 : 关键方法 | 一句话根因 |
|---|---|---|
| **P0-1 SSE/Inspector 不工作** | `api.ts:SSE_EVENT_TYPES` / `AgentDemoPage.handleSend` | 前端只用 onmessage 收不到命名事件；handleSend 丢弃 runId |
| **P0-2 Stop 不真实执行** | `AgentDemoStore.flushOrThrow` / `AgentDemoRunCoordinator.stop+stopRun` | cancel(true) 中断命中 FileLock → 写失败 → failRun 覆盖 STOPPED |
| **P0-3 Draft 初始化/纠正** | `AgentDemoStore.confirmSeed` / `completeRun(patch)` / `SpecFieldHelpers.extractCorrection` | confirmSeed 硬编码 completeness=0 不调 recompute；completeRun 接收 patch 却不应用 |
| **P0-4 越界交易 NPE** | `AgentRuntimeHarness.executeTurn`(越界预路由) / `IntentReconciler.isCapabilityBypass` | 无确定性预路由，落到普通问答调模型 |
| **R2-Issue1 歧义执行崩溃** | `AgentRuntimeHarness.executeTurn`(歧义短路) | reconcile 后委托 planner 重新分类；parseAnswer(null) NPE |
| **R2-Issue2 聊天纠正无效** | `AgentRuntimeHarness`(字段纠正块) / `AgentDemoStore.completeRun`(应用 patch) | 无确定性提取器；completeRun 不应用 patch |
| **R2-Issue3 Timeline 空** | `types.ts:DemoSessionDetail` / `AgentDemoPage.syncRunFromDetail` | 后端发 stepsByRun，前端读 steps（undefined） |
| **R3-IssueA 候选误导** | `AgentRuntimeHarness.executeTurn`(候选路径) | 把模型自由文本拼进候选回复 |
| **R3-IssueB Inspector 不同步** | `AgentDemoService.toRunView` / `AgentDemoPage.syncRunFromDetail` | prev.intent ?? match.intent 短路；DemoRunView 缺 intent 详情 |
| **R3-IssueC Stop 兜底回复** | `AgentDemoRunCoordinator.stopRun` / `AgentDemoStore.completeRun` | stopRun 透传 te.assistantContent() → 写了 AssistantTurn |

每个修复点在代码里都有**中文注释**（搜「中文要点」或问题编号如「P0-2」「第三轮 Issue C」可定位）。

---

## 7. SSE 契约（前后端必须对齐）

后端 `AgentDemoEventHub.SseEmitterSink.send` 用 `SseEmitter.event().name(type)` 发**命名事件**。
前端 `api.ts` 必须为每个命名类型 `addEventListener`（onmessage 收不到命名事件）。

事件类型（`SSE_EVENT_TYPES`）：
```
turn.accepted, run.started, run.completed, run.failed, run.stopped,
run.checkpointed, intent.detected, step.started, step.completed,
budget.updated, assistant.delta, citation.added, seed.detected,
draft.updated, validation.updated, checkpoint.saved,
capability.started, capability.completed, capability.failed,
turn.completed, turn.stopped, heartbeat
```

字段名陷阱：`DemoSessionDetail` 的步骤字段叫 **`stepsByRun`**（不是 `steps`），前端类型必须对齐。

---

## 8. 本地启动与调试

### 启动
```bash
cd .
zsh -lic './run-agent-demo.sh'   # 登录 shell，继承 ~/.zshrc 里的 DEEPSEEK_API_KEY
```
启动后：
- 后端 `http://127.0.0.1:8080`，前端 `http://127.0.0.1:5174/#/agent-demo`
- `GET /api/v1/health` 看 `keyConfigured`（true=真实 DeepSeek 可用）

> **为什么用 `zsh -lic`**：`.zshrc` 只在交互式登录 shell 加载。非交互 shell（默认 `bash run-agent-demo.sh`）不 source 它，拿不到 `DEEPSEEK_API_KEY`，会变成 `MODEL_UNAVAILABLE`。

### 停止
```bash
pkill -f "SuperTraderApplication"; pkill -f vite
```

### 跑测试
```bash
cd rebuild/backend && mvn test                    # 后端单元+集成（~688）
cd rebuild/frontend && npm test && npm run build  # 前端（168）+ 构建
cd rebuild && bash scripts/assert-agent-demo-boundaries.sh
cd rebuild/e2e && npm test                        # Playwright 黑盒（9）
```

### IDEA 里调试
- 用 `idea rebuild/backend` 打开（Maven 工程）。
- 主类：`SuperTraderApplication`。
- 想跑单测：右键 `AgentDemoApiTest` → Run（注意会占 8080，先停 Demo）。
- 改了后端代码后，重启 Demo 才生效（`spring-boot:run` 每次重启会重编译）。

### 常见坑
- **`keyConfigured=false`**：后端进程的环境没有 `DEEPSEEK_API_KEY`。用 `zsh -lic` 启动，或在你的终端 `export` 后启动。
- **端口被占**：`lsof -nP -iTCP:8080 -sTCP:LISTEN`，`pkill` 旧进程。
- **改了代码不生效**：后端是 `spring-boot:run`，重启才重编译；前端 Vite 有 HMR，改前端通常自动生效。
- **Inspector 卡在 QUEUED**：SSE 命名事件没到。现在有轮询 `syncRunFromDetail` 兜底，但仍建议在标准 Chromium 里验证 SSE 实时性。

---

## 9. 安全边界（改代码时必须守住）

- **Key 只进环境变量**：`DEEPSEEK_API_KEY` 由 `System.getenv` 读，从不写日志/Store/HTTP body/页面。
- **不导入交易类型**：`agentdemo` 生产源码不得 import CTP/Order/Gateway（边界扫描会拦）。
- **不返回隐藏思维链**：Trace 只存结构化 goal/action/observation，不存模型内部推理。
- **客户端不能提交** Workspace/Role/Model/Prompt/Budget/Capability/Key（严格 DTO 拒绝未知字段）。
- **Prompt 不能扩大能力**：能力检查在模型外执行（越界预路由），Prompt Injection 无法扩容。

---

## 10. 想深入读的话

- **设计权威**：`superpowers/specs/2026-08-03-agent-runtime-harness-demo-design.md`
  - §5 单次 Agent Turn 链路（本文第 3 节的依据）
  - §6 意图识别与四层意图结构（本文 §5.8）
  - §7 Agent Runtime Harness（状态机 §7.1 / Run Lease §7.2 / Step 约束 §7.3 / 预算 §7.4 / 防循环 §7.5 / Capability §7.6）→ 本文第 5 节
  - §8 Prompt 与敏感信息边界（本文 §5.10）
  - §9 核心数据模型（会话/Run/Intent/Step/Seed/Draft）
  - §10 API 与 SSE 契约 + 稳定错误码（本文 §7、§5.9）
  - §11 最小交互 Demo（真实与 Mock 边界）
  - §12 Demo 验收标准
- **实施计划**：`superpowers/plans/2026-08-03-agent-runtime-demo-implementation.md`（Task 1-12，每个 Task 列了文件+测试命令）。
- **修复全过程**：`agent-demo-repair-report.md`（三轮，含根因/证据/测试）。
- **修复前基线**：`agent-demo-repair-baseline.md`（每个问题的真实复现）。
- **Harness 设计源码**（本文第 5 节的依据）：`taskcenter/` 下的 `AgentRuntimeHarness / LoopGuard / RunBudget / BudgetPolicy / RunCheckpoint / AgentRun / AgentStep / CapabilityRegistry / HarnessObserver / IntentReconciler / IntentClassifier`。
