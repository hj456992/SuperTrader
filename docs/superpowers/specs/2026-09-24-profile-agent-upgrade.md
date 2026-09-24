# 爱聊画像 Agent 改造：范围与接口基线

本设计由用户认可的业务构想与架构方案收敛而来。用户已授权 TL 组织产品、前端、后端、测试、运维五个任务执行；2026-09-24 新增约束：不得意外膨胀，出现范围或规模扩张必须由用户决定。

## 本轮目标

1. 用户聊天中的本人证据生成自己的画像。
2. 用户选择人物并设置交往目标后，基于该人物聊天生成初步画像。
3. 本人和对方画像均可使用书籍 RAG 与受控 ReAct：检索方法、补查聊天、观察结果后继续调用模型。
4. 保留人物事实、用户目标、书籍方法的边界。目标变化不能重写人物事实。生成策略的既有能力不得回归。
5. 用户能够查看真实执行阶段、书籍和聊天依据、资料不足提示并停止任务；关闭页面后已保存画像仍可读取。

## 范围控制（所有角色必须遵守）

允许：已有应用内的适配、必要的小类拆分、已有 DSH/AgentScope 接入、已有 PostgreSQL 文档存储中的兼容字段、针对本次链路的测试及运行脚本调整。

本轮不加入：独立关系档案产品、向量数据库、重排服务、多 Agent、联网搜索、新 MCP 服务、完整多轮聊天产品、摘要记忆系统、自动发消息、自动订阅平台全历史、全量身份系统、云部署、监控平台、UI 重设计。

保留已有词项检索，先验证 Agent 能正确使用证据；不以额外模型/基础设施换取未经评测的收益。保留现有轮询传输，增加真实步骤反馈；不把它称为端到端 Token 流式。真实 Token 流/SSE 如成为必要新增工作，先报 TL 并由用户决定。

需报用户的膨胀触发：新增服务/数据库/付费依赖；修改外部 dsh-java 公共合同或大量底座模块；重写前端框架或存储；扩大业务范围；发现需要明显额外工作才能实现本轮承诺。先报告原因、受影响范围、最小替代方案，暂停相关扩展。不能暗自降低目标或写假循环冒充集成。

## 工作空间和职责

真实仓库 `/Users/hou/Documents/Codex/2026-09-22/garden-product-design/outputs/demo`，起点 `b410f0f`。原任务目录不是 Git 根，原生工作树工具已失败，TL 从真实仓库创建隔离工作树。

TL 集成分支 `ailiao/tl-agent-upgrade`。每个角色只在分配的工作树提交；不得推送远端，不修改其他角色工作树或正在运行的原应用，不读取/输出真实聊天与凭据用于测试。TL 审核后集成。

- 产品：`docs/product/`；业务验收和文案，严禁增项。
- 后端：`src/main/`、本次相关 `src/test/`、`pom.xml`；不改 `run.py` 或 `web/`。在 `docs/backend/` 交付确切运行插件/依赖需求。
- 前端：`web/src/` 与必要样式及对应测试；不改 Java/启动器/依赖版本。
- 测试：`src/test/` 中新建 `ProfileAgentAcceptance*`、`tests/acceptance/`、`docs/qa/`；不修改产品代码，发现问题报 TL。
- 运维：`run.py`、`build.sh`、`scripts/`、`tests/ops/`、`docs/ops/`、必要 README 更新；不修改业务 Java 或底座代码。

## 当前事实

`RanchAnalyzer` 直接调用 ModelRegistry，没有 tools；策略才执行知识检索；collectList 收齐后解析，前端轮询。`RanchStore` 使用 PostgreSQL JSONB，人物与画像已有持久化；job 在内存中。dsh-java 本地路径 `/Users/hou/Documents/Codex/projects/dsh-java`，已有 AgentScopeRuntime 和 DshResultMiddleware/ToolScheduler 路径。本轮必须验证可复用路径，不能把官方 TS 能力当作本地已实现。

## 兼容接口 v1（TL 管理，变化先协调前后端）

保留 `POST /api/ranch-analyze` body `{revision,id}`、`POST /api/ranch-strategy` body `{revision,id,situation}`，现有返回 job 继续可用。`GET /api/ranch-state` 仍返回原业务 state 并包含 job。`POST /api/ranch-cancel` 支持可选 `{runId}`；旧客户端省略时仍可取消当前任务；有 runId 不匹配时不能取消新任务。

job 兼容字段：`id,status,kind,targetId,error`。status：`idle|running|cancelling|done|cancelled|error|interrupted`。扩展字段全部对旧记录可选：

```json
{"id":"run-1","status":"running","kind":"profile","targetId":"person-1","phase":"knowledge","step":1,"maxSteps":6,"message":"正在检索参考方法","events":[{"seq":1,"type":"phase","phase":"knowledge","message":"正在检索参考方法","at":"2026-09-24T00:00:00Z"}]}
```

phase：`preparing|knowledge|evidence|reasoning|validating|saving|finished`；事件仅包含面向用户的真实进度，不含内部推理、凭据、整段聊天或未校验模型输出。保留有限事件以避免状态无限增长。刷新不重启任务；关闭页面不等于取消任务。

画像保留 `summary,facets,uncertainties,coverage,createdAt,stale,model`。增量字段：

```json
{"runId":"run-1","version":1,"basedOnRevision":10,"knowledgeStatus":"used","knowledge":[{"id":"K-book-0","title":"书籍名","location":"章节","text":"实际读取的书摘"}],"facets":[{"category":"沟通","text":"有边界的认识","kind":"inferred","evidenceIds":["M1"],"knowledgeIds":["K-book-0"],"counterEvidenceIds":[],"scope":"当前已读取材料","confidenceReason":"来自多次明确表达"}]}
```

新增 facet 字段可选。knowledgeStatus：`used|no_match|empty_library`。没有书籍/命中时允许有限画像，必须明确提示，不可伪造方法。书籍 ID 不能进入人物 evidenceIds；其他人发言不能成为目标自己的画像证据；用户转述必须保留来源与推断标签。引用只能来自本任务实际读取的材料。对旧画像不做破坏性迁移。

## 后端执行要求

通过已有 DSH AgentLoop/AgentScopeRuntime，工具执行走已有调度器。工具初期仅需要聊天检索、上下文展开、书籍检索；直接事实无需强制挂书摘。基础材料先做主体过滤与覆盖说明，画像输入不携带用来改变人格结论的交往目标。重要推断可用补查结果修正，未知应保留。

先查清合同再实现。最多 6 个模型步骤、总时限建议沿用 110 秒并以实际可行性配置、工具输出设上限；达到预算要明确失败/有限结果，不能提交半截 JSON。结构与引用校验、版本校验、取消屏障负责正式保存。取消传播到模型和工具；取消后迟到结果不提交。已有策略路径需要继续兼容，不在本轮扩展新策略业务。

任务恢复采用最小持久记录：重启时未完成任务标记 interrupted，不声称自动恢复模型。业务修订号与运行进度写入要分离，避免进度使自己的输入失效。可复用现有文档表的新文档，不新增数据库或服务。

## 验收门槛

- 自己/对方画像均有包含书籍检索及模型再次调用的确定性链路测试。
- 至少一个两步工具循环用工具观察改变后续输出，不能只校验工具定义存在。
- 目标变化不改变画像事实；本人/对方/背景证据不串用。
- 无书、无命中、冲突证据、超预算、模型失败均有明确状态。
- 书籍/材料引用校验拒绝伪造 ID；删除依据不保留可用的过期引用。
- 停止前响应/执行中/保存前取消，均不能迟到提交；重复取消安全。
- 页面重开能读取已保存画像；运行状态反馈不伪装为 Token 流。
- 现有 Java 与前端测试通过；构建运行装配真实加载 AgentScope 路径。
- 使用虚构数据测试；真实模型验证与确定性替身验证分开记录。

## 交付

每个角色提交修改、测试命令及结果、未完成项/风险。产品和测试独立核对，TL 集成后统一验收与发布；不能仅凭角色自述宣告完成。
