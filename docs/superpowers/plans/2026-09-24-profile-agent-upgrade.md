# 爱聊画像 Agent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. The user explicitly selected five role tasks coordinated by TL; do not ask them to choose an execution mode again.

**Goal:** 交付有书籍 RAG、真实工具循环和可核查依据的本人及对象初步画像。

**Architecture:** 爱聊业务校验包住现有 DSH/AgentScope 执行循环。保留当前存储和前端框架，通过兼容字段增加任务阶段和画像依据。

**Tech Stack:** Java 17、现有 dsh-java/AgentScope 合同、PostgreSQL、JavaScript/esbuild、JUnit 5、Node test。

## Global Constraints

- 范围、接口、验收按 `../specs/2026-09-24-profile-agent-upgrade.md`，扩展需用户决定。
- 各角色只改自己拥有的文件和工作树；外部底座只读；禁止真实聊天/密钥入库。
- 保留词项检索与轮询；不得新增服务、数据库、向量依赖、多 Agent 或产品功能。
- 模型路径必须复用已验证的 DSH/AgentScope 集成，不能以直调模型伪装。
- `/Users/hou/.local/apache-maven-3.9.16/bin/mvn` 是本机 Maven；优先读取 MAVEN_BIN 覆盖。

### Task 1: 产品验收基线（产品经理）

Files: create `docs/product/profile-agent-acceptance.md`。
Consumes: approved scope and v1 schema. Produces: numbered acceptance scenarios and exact user-facing status copy.

- [ ] 阅读现有用户界面及基线，记录本人/对象两条路径。
- [ ] 为成功、资料不足、无书、冲突、取消、重开各写 Given/When/Then。
- [ ] 明确书籍是方法、聊天是事实、目标只影响建议的界限。
- [ ] 按基线逐条审阅，不得引入新功能；提交文档并报告。

### Task 2: 执行引擎与画像契约（后端）

Files: modify `src/main/java/dev/garden/RanchAnalyzer.java`, `RanchKnowledge.java`, `RanchData.java`, `RanchStore.java`, `RanchService.java`, `GardenPlugin.java`, `pom.xml`; create focused runtime/tool helpers only where needed. Tests in corresponding `src/test/java/dev/garden/` files.
Consumes: v1 HTTP/profile schema. Produces: working DSH-backed profile execution, scoped tools, runtime manifest at `docs/backend/profile-runtime.md`.

- [ ] 读取底座 Agent/CreateAgentOptions/ToolRegistry/SessionStore 实际合同，给 TL 与运维提供插件和装配清单；若需要公共合同改造先停止该扩展并报告。
- [ ] 为书籍工具结果回填后模型再次生成写失败测试，使用可控模型替身，不访问私人资料。
- [ ] 实现工具作用域、材料和书籍检索、现有循环桥接，使测试通过。
- [ ] 为主体串用、未知 ID、无书/无命中/预算/冲突、取消与保存竞态写失败测试并实现必要行为。
- [ ] 加兼容画像字段、有限进度事件及最小任务持久化；避免 job 写入使 state revision 自增。
- [ ] 运行 Maven 测试，报告确定性测试与实际运行验证的区别；提交。

### Task 3: 任务与证据展示（前端）

Files: modify `web/src/ranch-ui.js`, `web/src/ranch-view.js`, existing ranch CSS; create focused event/profile rendering helper and tests only as needed.
Consumes: v1 job/profile schema. Produces: legacy compatible views for phases, references, uncertainty, cancelling/interrupted.

- [ ] 先用内存夹具写 Node 测试：旧画像可显示，书摘不变成人物事实，HTML 内容转义。
- [ ] 增加真实阶段反馈、任务 ID 定向取消、状态隔离；保留轮询与既有视觉风格。
- [ ] 展示画像知识引用、限制/反证、未知项；可选字段缺省不崩溃。
- [ ] 测试快速人物切换不会显示他人的任务或书摘；取消期间不能重复启动冲突任务。
- [ ] 执行 `node --test web/src/*.test.js` 及前端构建，提交并报告。

### Task 4: 独立验收（测试）

Files: create `src/test/java/dev/garden/ProfileAgentAcceptanceTest.java`, `tests/acceptance/` fixtures/scripts and `docs/qa/profile-agent-report.md` as suitable after inspecting backend test seams.
Consumes: v1 contract and implementation commits. Produces: independent regression evidence and defect list.

- [ ] 基线运行 Maven 和 Node 测试，建立虚构聊天/书籍夹具。
- [ ] 为主体归属、书籍来源、目标独立、循环继续、取消保存屏障、旧数据兼容建立可执行断言。
- [ ] 后端完成后在自己的分支合入明确提交，跑验收；禁止修改生产代码来掩盖失败。
- [ ] 报告真实失败、缺少验证的行为和确切复现命令，给 TL 复核。

### Task 5: 启动与发布（运维）

Files: `run.py`, `build.sh`, `scripts/`, `tests/ops/`, `docs/ops/profile-agent-runbook.md`, necessary README paragraphs.
Consumes: backend runtime manifest. Produces: minimal launcher composition, safe preflight and rollback documentation.

- [ ] 检查原启动器依赖与进程/端口现状，只读检查不打印环境或凭据。
- [ ] 用虚构配置验证缺少制品时明确报错；按后端提供的清单补齐已有插件装配。
- [ ] 确认不写盘模型密钥、不提交聊天数据，原开发实例不被擅自停止。
- [ ] 准备独立端口与测试数据库的 smoke 方法、备份/回滚和重启中断检查。
- [ ] 运行对应 Python 测试与构建检查，提交并报告。

### Task 6: TL 集成与交付

- [ ] 记录五个角色任务 ID、分支、进度和阻塞；每次范围变化先审查。
- [ ] 审核和顺序合入各角色提交，解决接口冲突；新增需求不得顺手实现。
- [ ] 在同一个集成提交上运行 Java/Node/启动器测试及隔离环境 smoke。
- [ ] 对关键取消链路、来源与角色边界审阅，不以 mock 通过冒充真实模型验收。
- [ ] 完成本地可运行交付并明确测试范围、限制及回滚方式。
