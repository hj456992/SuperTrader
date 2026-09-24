# 画像 Agent 独立 QA 报告

日期：2026-09-24。基线：`88cdbac`。工作树：`work/ailiao-upgrade/qa`，分支：`ailiao/qa-profile-agent`。本报告只声称已执行部分的结论。

**最新结果（后端2fb6ba6、前端f6bd81b，与TL b5df7ee生产代码一致）：完整release Java75/75、Node60/60，0失败/跳过。原goal/知识引用、静默流取消与超时、终态提示关闭缺陷均有红绿证据并已通过。以下保留分阶段历史；最终范围与限制以 `runtime-profile-agent-review.md` 为准。实际模型/数据库/浏览器smoke仍由TL单列。**

## 已执行的原始基线

| 命令 | 结果 | 范围 |
|---|---|---|
| `/Users/hou/.local/apache-maven-3.9.16/bin/mvn test` | 40 tests，0 failures/errors/skips | 原有 Java 单元测试 |
| `node --test web/src/*.test.js` | 24 tests，24 pass | 原有 Node 单元测试 |

没有访问真实聊天/模型/数据库；没有改生产代码、外部底座或运行实例。QA 工作树初始空 index 已恢复至 HEAD，未纳入任何原文件删除。

## 独立用例与状态

| 编号 | 断言 | 本阶段证据/限制 |
|---|---|---|
| QA-01 | 对象仅引用自身发言；混合其他人物/本人/背景/书籍 ID 拒绝 | `ProfileAgentAcceptanceTest`，直接调用生产校验 |
| QA-02 | 本人汇总跨人物空间中的 me 发言，不取 them/context | 同上，直接调用生产输入/校验 |
| QA-03 | 用户转述只能推断 | 同上；测试结果文本明确“据用户描述” |
| QA-04 | 冲突发言不在输入阶段丢失 | 同上；尚不证明最终模型会修正结论 |
| QA-05 | 检索返回实际书摘；无命中/空库不制造片段 | 同上；尚不证明最终 job/knowledgeStatus |
| QA-06 | 改目标不改现存事实 | 数据层回归；模型输入独立另有失败门槛 |
| QA-07 | 旧画像字段可选、来源丢失明示、文本转义 | Java + `legacy-profile.test.mjs`；序列化重读，不等同数据库重开 |
| QA-08 | 删除依据撤销现存及历史结论 | 数据层 revoke 回归；后续需真实 command 路径 |
| QA-09 | 目标完全不进入画像模型输入 | `ProfileAgentAcceptanceGates`，当前基线已知缺口 |
| QA-10 | 从未读到的书籍 ID 拒绝 | `ProfileAgentAcceptanceGates`，当前基线已知缺口 |
| QA-11 | 本人/对象真实工具循环，观察改变后续输出 | 待后端实际 AgentLoop/model seam；预言在 tests/acceptance/README.md |
| QA-12 | 响应前、工具中、保存前取消后迟到结果均零提交 | 待后端可控 model/tool/store seam，不以字段测试替代 |
| QA-13 | 无书/无命中最终有明确状态；预算、模型失败不保存半截 | 待后端集成 |
| QA-14 | 重启 running/cancelling → interrupted；已存画像可读 | 待隔离持久化集成，禁止访问原数据库 |
| QA-15 | 旧 runId 不取消新任务；进度不递增业务 revision | 待后端集成 |
| QA-16 | 书籍删除同时撤销画像历史引用；真实策略无回归 | 待后端集成 |

## 风险与接口阻塞

1. **P1 QA-09：画像输入泄漏交往目标。** 当前 `RanchData.profileInput` 深复制输入并保留 `goal/goalType`。同一份发言仅改目标，输入 JSON 就变化，可影响事实结论。复现：`tests/acceptance/run.sh gates` 的 `profileModelInputMustBeIdenticalWhenOnlyRelationshipGoalChanges`。修复归后端，QA 不改生产代码。
2. **P1 QA-10：画像书籍引用未校验。** 给有效人物证据的 facet 加 `knowledgeIds:["K-never-read"]`，当前 `validateProfile` 接受。复现同命令的 `profileMustRejectKnowledgeIdsThatWereNeverRead`。后端如采用独立的新校验入口，QA 将改接那个真实最终入口，门槛不降低。
3. **验收 seam：** 当前 `RanchStore` 构造器直连环境数据库，`RanchAnalyzer` 没有独立持久化接口。QA 不创建数据库/服务，不读真实运行配置。后端请提供已实现的可控存储与模型入口（已有依赖即可），使真实调度器、取消保存屏障可独立执行。
4. **恢复验证边界：** JSON 序列化 + 渲染只证明旧记录可展示，不证明真实 PostgreSQL job 恢复。该项必须在运维提供的独立测试环境做 smoke 或由真实持久化 seam 验证并标注范围。
5. **错误门槛单独执行：** `ProfileAgentAcceptanceGates` 刻意不使用默认 Surefire `*Test` 后缀；发布必须显式执行 gates 命令，不能只引用绿色 baseline。

## 集成待办

TL 给出明确后端/前端集成 SHA 后，在 QA 分支合入该提交；先适配实际测试入口，再逐项执行 QA-09～16，记录失败与确切命令。真实模型验证与确定性替身结果分开记录。没有模型输出、数据库恢复与装配的实际证据时不宣称发布通过。

## 产品 A01–A23 映射

已阅读产品提交 `0a0e2a90ce2c83fe66e153fef9beebb9f0ba8a8f`。下面是完整情景状态；“部分验证”不能算该情景通过。

| 产品 ID | 当前状态 | 证据或待验证点 |
|---|---|---|
| A01 | 部分验证 | QA-02 主体输入通过；保存、循环、书籍状态待测 |
| A02 | 部分验证 | QA-01 主体引用通过；生成保存待测 |
| A03 | 部分验证 | 书籍不能作为 evidenceIds；knowledgeIds 门槛失败待修 |
| A04 | 失败 | 数据保存不改画像通过；完整画像输入随目标变化 |
| A05 | 部分验证 | 原有 RanchAnalyzerTest 无材料拒绝通过；受限输出待测 |
| A06 | 部分验证 | 冲突原文仍在输入；观察改变生成结论待测 |
| A07 | 部分验证 | 空集合检索通过；empty_library 最终状态待测 |
| A08 | 部分验证 | 无命中检索通过；no_match 最终状态待测 |
| A09 | 未测 | 等真实 AgentScope 与模型 seam |
| A10 | 部分验证 | 转述 inferred/主体过滤通过；最终生成与界面来源待测 |
| A11 | 失败 | 未知人物/跨主体 ID 拒绝通过；never-read knowledgeIds 未拒绝 |
| A12 | 部分验证 | 数据 revoke 通过；删除命令/书籍快照清理待测 |
| A13 | 未测 | 真实阶段、事件上限与隐私边界待测 |
| A14 | 未测 | 三处取消竞态、传播与零提交待测 |
| A15 | 未测 | 定向/重复取消、cancelling 互斥待测 |
| A16 | 部分验证 | 序列化旧画像渲染通过；真实关闭重开待测 |
| A17 | 未测 | 隔离持久化与重启待测 |
| A18 | 未测 | 进度修订隔离与保存 CAS 待测 |
| A19 | 未测 | 步数/总时限/工具输出上限待测 |
| A20 | 未测 | 模型/工具失败不覆盖待测 |
| A21 | 未测 | 升级后快速切换/任务归属待测 |
| A22 | 部分验证 | 旧画像直接校验/渲染通过；旧 job/HTTP 接口待测 |
| A23 | 部分验证 | 原有策略渲染/校验回归与 QA 原文转义通过；生成路径待测 |

真实模型验证：**未执行**。实际运行装配：**未执行**。这两项不能由确定性通过数推断。

## 本阶段新增测试实跑结果

- `tests/acceptance/run.sh baseline`：退出 0；Java **48/48**（新增 8），Node **27/27**（新增 3），0 skip。
- `tests/acceptance/run.sh gates`：退出 1；**2 tests / 2 failures / 0 errors / 0 skip**。失败分别为完整画像输入因 goal/goalType 变化、未知书籍 ID 未抛出异常，证实 QA-09/QA-10，非测试编译或装配失败。
- 首次新增检索测试失败曾为预期 1 个片段、实际 2 个；根因是现有 rank 同时索引书名，《虚构倾听练习》让园艺段也弱命中。断言修为相关段排名第一、来源原文一致、结果受限；无命中另用无共有词项的查询验证。该行为是保留词项检索的精度限制，不扩大范围重写检索。
- 真实模型、真实数据库、浏览器实际重开及服务装配均未执行。下一阶段由 TL 明确集成提交后继续。

## 前端阶段补充（ed83e4f）

QA 已按 TL 指示 cherry-pick 前端提交为 `00febeb`，并完成独立源代码审阅。新增 7 个组合用例后，Node **49/49**、0 skip；详见 `docs/qa/frontend-profile-agent-review.md`。当前/历史本人跨人物 me 引用、对象正反证主体隔离、旧画像/三类知识状态、取消期间切换与迟到轮询、定向与重复取消前端行为均通过；未发现新增阻断缺陷。A14 后端零提交与 A17 持久化恢复依然未测，不把前端测试升级为端到端成功。

## 后端阶段补充（持续更新）

最新链路、缺陷和真实程度请见 `docs/qa/runtime-profile-agent-review.md`，它覆盖上方初期“未测”状态。已验证实际模型工具观察改变最终保存、取消迟到零提交、保存优先线性化、修订、内存恢复、删书及策略。原两项 gates 转绿；仍发现静默 provider 源未收到取消（a59b21e），不得宣称全绿。前端关闭终态提示缺陷在461b3dc同断言转绿，Node58/58。
