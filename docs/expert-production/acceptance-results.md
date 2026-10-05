# 独立生产验收执行记录

日期：2026-10-05。工作区：`work/expert-production`。最新未过滤全量测试：**82项全部通过，零失败／错误／跳过**，包含旧48项及生产新增34项。此结果对应后文固定编译类；后续真实模型引文修复需要另行回归。真实模型、宿主装配和浏览器验收由统筹另记；一次历史快照500仍保留为未定位问题。

## 环境与边界

使用统筹提供的专用 PostgreSQL，逐用例新建 `ep_qa_<随机UUID>` schema，临时原文及共享目录、本机随机 HTTP 端口。只在测试 schema 注入租约过期故障，不读写真实应用 state、书籍或审批。真实 ProductionDatabase / ProductionService / ProductionHttp 参与执行；仅模型语义输出由 `src/test` 的 ControlledModel 替代。

资料起点由 LabStore 注入两页原创文字及测试用文件字节，不调用 PDF/MinerU 解析。HTTP 测试从选择已解析版本创建构建开始。它不能替代上传/OCR质量、真实 DSH 宿主的驱动装配或浏览器界面验收。

运行入口：在 `expert-agent-demo` 执行 `python3 .local/with-env.py sh tests/production-acceptance.sh`。私密环境文件不打印、不提交。脚本缺少显式数据库环境时直接失败，不把跳过测试记为通过。常规状态测试显式关闭 Redis 额度服务，故障测试注入实际不可连接的 Redis 配置；正常 Redis 命令由统筹的独立基础测试验证。

## 首轮红测

首个用例先要求“HTTP创建后、worker尚未启动时，数据库已有 active/prelearning 构建、pending 草稿与 queued 任务”。执行 Maven 定向测试退出 1，ProductionDatabase / ProductionService / ProductionRedis 当时尚不存在，测试无法编译；日志 `/tmp/expert-production-acceptance-red.log`。

这是缺失生产实现的编译门槛记录，**不是行为断言红测，也不是数据库连通结果**。接口落盘后将继续实际行为失败与修复验证。

## 第一轮行为红测（9项版本）

后端首次集成构建实际运行了当时的9项独立验收：8通过、1失败，补否决原因后等不到新修订，日志 `/tmp/expert-backend-build.log`。独立角色读取完整失败及生产实现确认：先将待补原因置 resolved，再按模型返回的澄清ID检查 open，导致同一次操作回滚；同时缺少由补原因消息产生的有原因 reject 记录。已交后端修复，原断言不变。此轮由后端执行，不混称独立角色亲自复跑的最终结果。

## 新增覆盖及串行复验

扩展到14项后，第一次独立定向执行被并行开发中新加的 Queue.defer 接口缺失阻断 testCompile，日志 `/tmp/expert-production-acceptance-14.log`。这是编译门槛，未运行14项行为。接口完成后重新执行，结果见下文。

Redis故障仍保留既有 `api-contract.md` 第8节门槛：额度不可用不得调用模型，工作应持久 queued 等待；统筹核对原合同后确认不将此断言降为普通生成失败。此要求不等于重新请求模型或部署新重试设施。

14项首次行为执行（`/tmp/expert-production-acceptance-14b.log`）：**12通过、2失败、0错误、0跳过**。完整十二步、补原因、必要澄清、依赖局部失效、重启及双实例租约代次均通过；未通过项为：

- `sourceEndpointEnforcesSelectedVersionsAndCodePointOffsets`：请求原文 `startOffset=1&endOffset=4` 却返回整段；测试含补充平面字符，以码点而非 UTF-16 下标计算范围。
- `configuredRedisOutageDoesNotCallModelAndKeepsDurableWorkQueued`：模型调用已被阻止，但 job 被记为 failed，违背 queued 等待的既有合同。

已将两项失败交给后端，原断言保持。后端补充来源偏移校验、额度异常单独延后领取，并有界等待本地工作线程关闭；最终复跑结果见下文。

后续14项复跑（`/tmp/expert-production-acceptance-14-final.log`）：**13通过、1失败**。来源偏移及 Redis queued 已通过；双实例接管时一次快照 HTTP 500，日志仅给出 PSQLException。原用例先前通过不抵消此失败，测试不会重试500后冒称成功。已要求记录脱敏 SQLState，并在测试服务器仅作失败诊断读取、保留原始失败。原因尚待定位。

## 澄清与分工更新的追加门槛

统筹复核指出仅解决问题状态不等于把答案纳入专家，因此加强既有澄清用例：管理员回答后必须生成新的 pending 完整提示词、包含该答案并重新展示，不能直接批准旧提示词。另新增同 key 分工测试：概要修订把职责从“组织汇报”改成“只处理技术汇报”后，专家新稿必须读取新版 plan，不能沿用旧生成配置。

两项与租约用例的定向执行（`/tmp/expert-production-acceptance-enhanced-red.log`）：**3项中2失败、1通过**。澄清用例等不到新版；分工用例得到旧职责“组织汇报”。这两项行为红测已交后端修复，断言不放宽。本次租约用例未再出现500，但仍保留前次未定位异常。

独立代码核对还发现：当前 Service 接入 Redis 额度许可，缓存/通知 helper 尚未用于业务服务。现有PG轮询可以完成流程，但不能据此宣称三项Redis职责均已实现。

后端根据两项增强红测修复：澄清形成新待审修订并重新展示，生成专家时读取当前概要的同 key plan。最终15项独立复验已确认这两处修复通过。

## 已编写的独立行为用例

ProductionAcceptanceTest 当前包含15项：创建即入库、完整十二步、否决补原因、条件/引用/局部/讨论不批准、幂等与旧版、防伪展示上下文、越界生成来源、取消后的迟到输出、重启恢复、必要澄清、依赖局部失效、原文码点范围、Redis故障、双实例租约代次、同key分工职责更新。

当前没有真实模型、浏览器或吞吐验收结论；这些不能从受控模型测试推定。旧35项原型检查与当前Java生产测试彼此独立。

## 最终独立复验

2026-10-05 12:13:58 +08:00 完成以下执行，Maven退出0，耗时2分11秒；日志 `/tmp/expert-production-acceptance-15-db-redis.log`，随后独立读取Surefire XML核对同一计数：

```sh
python3 .local/with-env.py env \
  PRODUCTION_ACCEPTANCE_TEST=ProductionAcceptanceTest,ProductionDatabaseTest,ProductionRedisTest \
  sh tests/production-acceptance.sh
```

| 层级 | 结果 | 证据含义 |
| --- | --- | --- |
| 独立 HTTP + PG + 受控模型 | 15/15 | 8次明确审核覆盖两位专业专家样本的十二步生产要求，最终团队绑定真实审批快照；不依赖旧试聊启用 |
| 真实 PG 基础 | 7/7 | 包括领取互斥、旧epoch、延期和checkpoint提交时过期回滚；用例由统筹编写，本轮独立执行 |
| Redis 基础 | 2/2 | 用例由统筹编写，本轮独立执行；不将helper通过扩大为业务缓存/通知已接入 |

受测编译类 SHA-256（随后源码兼容修复须由统筹再次回归，不能沿用本次为新代码背书）：

- ProductionService：`1638144d15a857dc283204730a53ff486ac517496c6bc1ab3fd255e0488cfd54`
- ProductionHttp：`0ed505a1a46e2d4ed8b4a807180352c5a202fba3a55784b7190375a9e9b96874`
- ProductionJobQueue：`86c0aac930eeb7eb7137ac8cc76d0790752d12a3d4eb8d901090189210bf3af0`
- ProductionDatabase：`68d3e6e3113bc415b5378820d14a2b7712a72048205c16b6f8ae8eccdcfb5601`
- ProductionContext：`27a4d57324b13cb0589aa943aaccf458218ebf16075a559f17b03a4bd7e538e3`

一次快照500在后续定向和最终完整运行中未再出现，但原日志未捕获SQLState，不能据此声称已找到根因或已修复。已保留后端SQLState诊断和测试失败cause；没有增加HTTP500重试把失败变为通过。真实宿主旧资料parser缺失的22P02由统筹另行定位，不与该快照异常混为同一问题。

本角色未改业务实现、未提交推送、未部署、未发起真实供应商调用。最终源码、真实模型、浏览器及架构文档整体验收仍由统筹完成。

## 历史快照500的定向追踪

统筹反馈已检查 PostgreSQL 容器在该次失败附近的日志（04:05–04:07 UTC），未见对应 ERROR/FATAL。此为统筹提供的数据库日志观察，不能据此确定为网络瞬态，也不能与旧资料导入的22P02归并。

独立角色随后将 `secondInstanceTakesExpiredLeaseAndLateOldWorkerCannotOverwriteItsResult` 连续执行3次，分别 **1通过/0失败/0错误/0跳过**，耗时4.890、4.534、4.364秒，整组退出0；日志 `/tmp/expert-production-lease-repeat-1.log`、`-2.log`、`-3.log`。均未触发新增SQLState诊断。测试仍将任何HTTP500直接判失败，没有靠重试跳过故障。

本次仅证明三个独立随机schema下的定向用例通过。原单次500的根因仍未定位，保留观察项；不以稳定复跑代替根因证据。没有访问浏览器、原业务数据或供应商接口。

## 最后一次未过滤全量测试（12:22:04）

统筹完成旧资料parser兼容、额度等待时显式取消、预学习步骤规范化及驱动SPI修复并交还构建目录后，独立执行 `mvn test`，不执行 package/clean、不覆盖运行JAR。2026-10-05 12:22:04 +08:00退出0，耗时2分40秒，日志 `/tmp/expert-production-final-all-tests.log`；Surefire XML总计同样为82/0/0/0。

| 用例组 | 通过数 |
| --- | ---: |
| 原有解析、导入、资料、运行与工具测试 | 48 |
| ProductionAcceptanceTest | 15 |
| ProductionDatabaseTest | 7 |
| ProductionRedisTest | 2 |
| ProductionBusinessTest | 5 |
| ProductionContextTest | 2 |
| ProductionPrelearnTest | 3 |

旧 MineruImportTest 本轮5项通过，未复现此前临时目录清理错误；本角色未修改该测试或旧导入实现，不把未复现写成已根治。双实例租约本轮也通过，历史快照500仍保留。

本次受测 ProductionService.class SHA-256 为 `aaba5634ded8432e77072f32d9c3955fcbea22d170bbfaa8b95208140141ca65`，ProductionRules.class 为 `07683df254d39b2faa93d5172196f98eee81d0abed3083da52fc01023aefbc28`，ProductionHttp.class 为 `0f2478babe25b5d1adf89c7e73031efb22498e2d974871972446fc0e4cda1127`。

统筹真实模型随后发现来源偏移越界，后端正在补充引文唯一匹配到Unicode码点范围的严格规范化。本次82项不包含该后续修复，不能作为更新后的源码验收证据；相应新测试和真实模型复验须另记。

## README 与新版源码上传前复验（12:39:50）

用户明确要求监督角色优化 README 并上传新版本及 README；本角色据此接管这次源码提交。后端冻结来源精确匹配、模型失败诊断、改写回退和统一意图安全检查后，独立执行未过滤的 `mvn test`，2026-10-05 12:39:50 +08:00 退出 0，耗时 3 分 58 秒。日志 `/tmp/expert-production-upload-tests.log`；另读 Surefire XML，合计 **91 项、0 失败、0 错误、0 跳过**。

本次在此前 82 项基础上增加 ProductionSourceTest 4 项、ProductionModelFailureTest 3 项、ProductionInterpretationTest 2 项。Interpretation 的两项方法覆盖 9 种改写格式和 45 组安全标记场景，不把内部组合数另算成 JUnit 用例数。

同一源码前端 `node --test src/test/frontend/production-ui.test.cjs` 为 **18 项通过、0 失败、0 跳过**；三个入口 JS 语法检查、启动器和真实流程脚本 Python 语法检查、文档 DDL 与实际资源逐字对比及 Git 差异格式检查均通过。主 README、专家实验室 README、文档索引和历史说明已更新，现有本地 Markdown 链接可解析。59 个源码／测试及配置文件在本次冻结和验收后核对 SHA-256 无变化。

本轮受控模型测试不调用真实供应商。私密测试环境中生产输出预算为 16000 tokens / 100000 字符；代码默认仍为 7000 / 40000，不把私密调参写成所有用户的默认配置。

**后台异常仍保留：** 本次在 ProductionInterpretationTest 期间输出两条 `Production worker: PSQLException`，但最终状态断言和完整测试通过。该前缀来自领取任务事务的外层异常处理，等待后继续循环；没有 SQLState 或堆栈，不能确定为死锁、连接故障或关闭竞态。此前单次 snapshot HTTP 500 也仍未定位，不能与本次事件认定为同一根因。未添加失败重试来掩盖 HTTP 500，也不把测试通过称为零后台异常。

这是可追溯的开发源码快照，**真实模型完整团队、真实浏览器与生产部署验收仍未完成**。本次上传源代码、测试和适用文档，不上传密钥、运行数据、用户书籍、OCR 私密结果或构建产物；不切换已有本地实例。重点复核见 [code-review.md](code-review.md)。

受测关键文件 SHA-256：

- `ProductionService.java`：`0b3ab53292817a080ec2f78ed0abcb5cb6c52bbd171ba617347e3904516bae82`
- `ProductionRules.java`：`4c5a9e7c7e02a2b391c461e7dd3f24cee183dbf6c08281505746abb201d70065`
- `ProductionHttp.java`：`8a7d18e9771cd1f20e9195000b70c1309c7a74f090558b4b42ffdbe1a97d10ec`

暂存完整新增文件后的格式检查发现 `ProductionService.java` 一处行尾空格，提交前已删除；逐词字节比较一致，无语句或行号变化，未因此重复运行整套测试。上列该文件哈希为去除空格后的提交内容。
