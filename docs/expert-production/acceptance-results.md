# 独立生产验收执行记录

日期：2026-10-06。工作区：`work/expert-production`。最新冻结副本 **v6** 的未过滤全量由**统筹执行**：**136项Java通过，0失败／错误／跳过；前端18项通过**。监督角色只读独立核对完整日志、27份Surefire XML、证据JSON、前端日志及66份源码哈希，结果相互一致，受测src和pom与核对时当前源码相同。监督没有运行本轮PG连接或Maven，不把统筹运行写成自己执行。v3的129项中1个清理error、v5的134项中间通过均在下文保留；统筹随后提供真实CUA/API测试队完成、最后重启及浏览器刷新保持的证据；监督已只读核对重启后proof中的状态与计数，浏览器操作、重启和语义审核均归统筹执行。

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

## 9b1aacf 后冻结增量的独立复验（13:12:18，尚未提交）

统筹另行要求复验已冻结的章节定位、无方法尾批、重复恢复／重试控制、JSON 和 worker 安全诊断，以及真实流程脚本的恢复／自然语言审批选项。本轮未修改生产实现、未调用供应商、未控制浏览器，不提交或上传；等待统筹完成真实书籍与文档收尾。

在独立数据库环境再次执行未过滤的 `mvn test`（未 package/clean），2026-10-05 13:12:18 +08:00 退出 0，总耗时 **4 分 27 秒**。日志 `/tmp/expert-production-post-9b1aac-tests.log`，Surefire XML 独立核对为 **105 项、0 失败、0 错误、0 跳过**。前端真实模块／协议 fixture 测试另跑 **18 项通过、0 失败、0 跳过**，三份入口 JS 和流程脚本 Python 语法检查通过，已有差异及新增文本的格式检查通过。

相对已上传的 91 项，增加 14 项：

| 新增或增强测试 | 本轮通过数 | 相对上一轮新增 |
| --- | ---: | ---: |
| ProductionChapterTest | 4 | 4 |
| ProductionChapterIntegrationTest | 1 | 1 |
| ProductionControlRetryTest | 3 | 3 |
| ProductionEmptyPrelearnIntegrationTest | 1 | 1 |
| ProductionPrelearnTest | 6 | 3 |
| ProductionDiagnosticsTest | 1 | 1 |
| ProductionModelFailureTest | 4 | 1 |

其余原有 48 项与既有生产用例一并复跑。章节集成使用真实 PG 的缺少章节资料，只从已验证文档版本／页码补标签，来源偏移和用途保持。无方法尾批必须给出原因并覆盖本批全部来源；重试保留已封存前批及其哈希和原文引用，空批原因仍进入概要输入。新 clientRequestId 的重复 resume/retry 在 queued、running 和已可审状态不新增付费模型调用，失败／暂停后的合法恢复另行验证。

流程脚本在本轮只进行源码核对与语法检查；`post` 仍核对本次消息失败状态，不能因为忽略历史 interpret_message 失败记录而把当前操作算成功。`--natural-approvals` 的真实供应商和完整团队执行由统筹另行记录，不能从本次确定性测试推定。

**本轮仍有 5 条 worker PSQLException。** 新安全诊断均为 `SQLState=08001 vendorCode=0`，堆栈定位 `ProductionDatabase.connect:26 → run → transaction → ProductionJobQueue.claim:23 → ProductionService.workLoop:164`。因此本次可定位为驱动建连阶段异常，领取循环捕获后继续；未获取更深原因，不能断言网络、连接数或超时根因，也不能将此前没有 SQLState 的 HTTP snapshot 500 或旧 worker 日志认定为同一问题。JUnit 状态断言全部通过，不等于后台异常不存在或已修复。

66 份源码、测试、运行配置和真实流程脚本在执行前后 SHA-256 完全一致，无候选漂移。冻结清单规范 JSON 的 SHA-256 为 `232822fe108e153dab242bc06d7dc618e428f9eb8465f2b65c7d494c24269fa4`。关键源码与本次受测编译类：

| 文件 | 源码 SHA-256 | 编译类 SHA-256 |
| --- | --- | --- |
| `ProductionService` | `015c080ce426d2e2d6f7cf4e20ccfe62b099e8f56d432b1903ccc127856a9806` | `ed58a594d9d874936c51ca40bf3bf97bc3e2496d0e50de2d2cb85c723ea39482` |
| `ProductionRules` | `21dedf652482b4b1e69e725c358bfa284e182bd854400667fb8b19ce1518133c` | `ada0db46b31a8277db942fcfec556595cc9c81bbe29bc150474fc0fd69408be4` |
| `ProductionContext` | `8987118de6dd1f2c519519af928659f15a208bab120fa8a030106ada006fcf84` | `ff7ed46709c59018b6227b366b4513b4b8a10a62e1d0ddb5d6c495a78b17c3b1` |
| `ProductionModelException` | `74daf5f694be114486f9481ae8635b86991e2332dfaa84fbca87c2c361262d71` | `951c387fff24adb4be6986e06202f0c98c135e949357c9128043fb4ae52b9b36` |
| `ProductionDiagnostics` | `3c08cfb164c18d58901d190fb3c73b7d22140a580f42a40655bf9c42ae435e5d` | `b38bfd114bdbbe7b8dc3620b605e140bf505055d270b6fe48bba6d16c1feac22` |
| `ModelCalls` | `171593f0c11df799e0e41705b6dd4775d2499d2d94fbb54947da70ac4129e7fa` | `68a9acff5e3b62d5c65a77cd52f4775998ced3948ed58d9dcf66c3c2f64cd3d5` |

本轮结果只覆盖上述冻结增量；后续代码修改必须取得相应新证据。构建目录已交还统筹。GitHub 已上传版本仍为 `9b1aacf`，本轮没有更新远端。

## 安全异常链诊断增量定向复验（13:23:18，原连接配置）

仅在上次 105 项基础上补充了 ProductionDiagnostics 的时间戳、安全 cause 链与长度限制，未重复运行全量。按统筹指定，执行 `ProductionDiagnosticsTest` 全部 3 项、`ProductionInterpretationTest#unsafeIntentCannotMutateControlReviewFocusOrClarifications`，以及 `ProductionAcceptanceTest` 的 `summaryRevisionWithSameSpecialistKeyUpdatesTheNextExpertPlan` 和 `completeTwelveStepsRequireEveryDisplayedArtifactAndFinalManifestApproval`。

2026-10-05 13:23:18 +08:00 退出 0，耗时 **1 分 37 秒，6 项通过、0 失败／错误／跳过**。日志 `/tmp/expert-production-cause-recheck.log`。只核对上述三份当次 Surefire XML 和其中实际方法名，没有把残留的其他测试 XML 计入本次。63 份源码／测试在执行前后哈希一致，没有调整业务断言或配置以隐藏这次失败日志。

此次复现 1 条 worker 异常，安全证据：

```text
time=2026-10-05T05:22:19.971242Z
PSQLException SQLState=08001 vendorCode=0
ProductionDatabase.connect:26 → ProductionJobQueue.claim:23 → ProductionService.workLoop:164
cause[1]=java.net.SocketTimeoutException
PGStream.receiveChar:477 → ConnectionFactoryImpl.enableSSL:627 → tryConnect:207
```

可据此定位 **PostgreSQL 建连期间，SSL 协商响应读取超时**，不是已执行业务 SQL 时的冲突证据。仍没有足够证据确定为何响应超时，也不能把没有异常链的旧 worker 日志或历史 HTTP 500 认定为同根因。日志不含异常 message、SQL、参数、URL、凭据或资料正文，保留完整安全栈供统筹排查。

统筹随后只读确认该本地 loopback 验收库 `SHOW ssl=off`，计划仅调整私密测试 URL 后进行同组对照；这不改变源码默认或远端／集群 TLS 策略。对照结果另记，不能提前写为故障已修复。

本次 `ProductionDiagnostics` 源码 SHA-256：`f6c3f41fdfb9436d0b73953b0801607b913a6eae59b339fe93d1bd75199dcb2e`；受测类 SHA-256：`f54ae5e5a70c6cc801d2927b6aba8b1ecc9bac5e907c7117b14e0c4335b70362`。

## 本地非 SSL 验收库连接配置对照（13:26:24）

统筹只读确认该 loopback PostgreSQL 实例未启用 SSL，随后仅在忽略的私密 `integration-env.json` 中为本地专用验收库 URL 显式指定 `sslmode=disable`，保留权限 600。监督角色启动前在内存断言目标为既定 loopback 端口及该参数，不输出 URL 或凭据。**没有修改源码连接默认、远端或集群 TLS 策略，也没有新增基础设施。**

相同 6 项定向用例再次执行，2026-10-05 13:26:24 +08:00 退出 0，耗时 **1 分 26 秒**；指定三份当次 Surefire XML 确认为 **6 通过、0 失败／错误／跳过**。日志 `/tmp/expert-production-local-ssl-recheck.log`，未出现 worker、HTTP 或 SQLState 异常记录。两轮使用相同 63 份源码／测试哈希，不放宽断言，不跳过场景。此前全量 105 项的报告不因本次定向覆盖而改称全量 107 或 111 项通过。

| 对照 | 用例结果 | 日志观察 |
| --- | --- | --- |
| 原连接配置 | 6/6 通过，1分37秒 | 1条08001，cause为SocketTimeoutException，位于enableSSL响应读取 |
| 仅本地非SSL库显式配置 | 6/6 通过，1分26秒 | 本次未记录worker/HTTP/SQLState异常 |

本次证明该本地配置下指定路径通过且没有复现异常。它符合避免对不支持 SSL 的本地服务进行探测的预期，但一次对照不能证明原响应超时的底层原因，也不能证明历史 snapshot 500 或所有旧 worker 异常已根治。其余环境须按各自 TLS 配置运行，不照搬本地验收参数。

构建目录已交还统筹；本角色未重启服务。统筹随后记录最新包重启后的两队 completed/各9审核，以及真实整书概要 pending/零批准/同修订/11批保持，详见[真实集成记录](integration-results.md)。README与重点复核已按上述证据更新，浏览器验收仍未通过；代码交付不能表述为全界面或生产部署验收完成。

## 2026-10-06：b1dab52 独立复核与执行权限边界

统筹要求待原有 Lima/PG/Redis 恢复后，监督独占原模块 target 执行未过滤全量。监督确认开始时 HEAD 为 `b1dab5247dc9fbeba796885564c701a6cdebdad9`，工作区干净且无存量 Surefire 进程。未自行启动VM、数据库或宿主，未执行clean/package，未改业务实现。

实际直接连通预检在读取私密环境到内存后，对既定loopback PostgreSQL与Redis分别返回 `PermissionError errno=1`（Operation not permitted）。这发生在socket建立阶段，不能解释为数据库未启动、业务SQL错误或本次HTTP500。监督没有换通道、改变权限或反复启动数据库测试。原target没有被监督的Maven占用，已可由统筹使用。

本轮监督亲自执行的结果：前端 **18通过/0失败/0跳过**；三个JS入口语法与Git差异格式检查通过。Python首次py_compile因macOS默认缓存目录在可写范围外而被拒绝，不是语法错误；随后仅在内存进行AST解析和compile，启动器及真实模型流程脚本语法均通过，没有写入该受限缓存目录。

向统筹回报状态的MCP工具同样被自动审批策略拒绝，返回“requires approval, but approval policy is never”。未绕过拒绝。本记录是原任务要求的验收产物，不伪称状态消息已成功送达。

### 对统筹隔离全量的只读证据核对

统筹使用 `expert-agent-demo/.local/final-validation` 隔离副本运行；监督在2026-10-06 10:29 +08:00独立读取并核对：

| 证据 | 核对结果 |
| --- | --- |
| 完整Maven日志 `.local/final-validation-tests.log` | BUILD SUCCESS；116项、0失败/错误/跳过；48.939秒；完成于10:23:20 +08:00 |
| 26份当次Surefire XML | 合计116/0/0/0，与Maven总结一致 |
| 隔离副本65份src文件 | 文件集合及逐文件SHA-256均等于Git提交b1dab52；不能只与正在修改的工作区比较 |
| 双实例租约用例 | `secondInstanceTakesExpiredLeaseAndLateOldWorkerCannotOverwriteItsResult`通过，XML耗时0.691秒，无failure/error/skipped节点 |
| worker异常日志 | 本份统筹全量日志未出现 `Production worker:` 异常行；不能由此抹除历史异常 |

统筹先前发出的114为计数脚本硬编码预估，随后已纠正。监督以原始Maven总结与XML确认116，没有把计数脚本断言失败算作测试失败。

受测提交src规范清单SHA-256：`12a4644247efe1da3b000ec612be09e1b99b7d2b354c9a0a493f8a323eac1bfb`。完整Maven日志SHA-256：`739366212a98abf0857b740a20a76489507ed713630cfd9f49875fabfe64ee6a`。日志、运行环境和临时副本均保留在忽略目录，不上传凭据或运行材料。

核对时工作区已出现ProductionContextTest的新修改；统筹亦通知准备修长书上下文选取。因此“隔离副本等于b1dab52”与“当前工作区已变化”同时成立，前述116项结果只绑定冻结提交，不为尚未完成的新修复背书。

### 历史snapshot500的现存证据与缺口

原 `/tmp/expert-production-acceptance-14-final.log` 在本轮已不存在，限定搜索本模块 `.local` 的对应14-final/snapshot/500/lease-repeat文件名未找到原始副本。没有重建或补造原日志。本轮没有执行prefer与本地显式配置的数据库对照，原因是监督连接预检被EPERM阻断。

| 能确认的事实 | 仍缺的信息 |
| --- | --- |
| 既有记录为14项中13通过、1失败；双实例接管场景的snapshot返回HTTP500，异常类PSQLException | 原始请求时间、build/request/worker身份、确切失败调用点和当时完整SQLState/cause/栈已不可从缺失日志核实 |
| 当前同名测试阻塞旧概要生成，定向使租约过期，启动第二实例，读取快照并释放旧工作线程，断言代次接管及稿件不被覆盖 | 不能断言历史500发生在first ready轮询、第二HTTP端点、释放旧线程之后哪一次读取；也缺失败时的锁、租约和连接状态 |
| 当前snapshot经ProductionService.snapshot→ProductionDatabase.read，读取事务使用REPEATABLE_READ | 当前实现不等于当时尚未提交的14项候选；缺原始cause时不能判定历史错误来自connect、search_path、查询、提交或其它阶段 |
| 当前测试助手对任何非200抛AssertionError；额外直接读取只附诊断cause，不把原HTTP失败改成通过 | 后加的诊断不能反推丢失的历史cause；b1dab52的建连恢复回归也不能证明历史500就是SSL探测超时 |

结论：现有证据尚不能把历史500确认为业务/事务缺陷，也不能排除该类问题或强行归入SSL同源。统筹本轮冻结版本的同名用例通过，是新的正向证据，不是历史根因证明。后续若出现真实500，必须保留当次安全异常链和请求阶段；本轮未新增无界复跑、失败重试或宽松断言。

## 2026-10-06：v3 真实失败、v5 中间结果与最终冻结 v6 只读复核

本轮职责仅为读取统筹运行证据并核对源码，未尝试PG网络、未执行Maven、未修改业务代码，也未提交或上传。本轮所有构建、测试运行及服务操作归统筹；监督独立核对结果如下。

### 各轮实际结果

| 副本 | Maven完成时间（+08:00） | 实际计数 tests/failures/errors/skipped | 状态及范围 |
| --- | --- | --- | --- |
| v3 | 2026-10-06 10:34:41，58.895秒 | 129/0/1/0 | BUILD FAILURE，真实保留清理错误，不作为通过 |
| v5 | 2026-10-06 10:40:04，约1分03秒 | 134/0/0/0 | BUILD SUCCESS，但不包含随后团队清单上下文修复，仅为中间证据 |
| v6 | 2026-10-06 10:45:14，约1分03秒 | 136/0/0/0 | BUILD SUCCESS，本节最终冻结程序回归 |

v5与v6各自前端日志均为18 tests/pass、0 fail/cancelled/skipped/todo。Java计数分别读取各目录27份XML并与对应Maven最后总计及证据JSON比较；没有使用预计值或叠加多个副本计数。v5、v6日志未发现 `Production worker:` 或 `Production HTTP:` 异常行；这不是抹除旧连接异常或历史snapshot500的理由。

### v3错误与实际生命周期修复

v3唯一error为 `MineruImportTest.staleAttemptCannotChangeNewAttemptsJournal` 的JUnit扩展上下文清理错误。XML记录 `org.junit.platform.commons.JUnitException: Failed to close extension context`，下层为临时目录删除失败，suppressed含 `DirectoryNotEmptyException`，涉及 `imports/i-1`。该结果不是通过，也不能靠复跑一次忽略。

监督逐行对比v3/v5源码确认，修复作用于真实 `Jobs` 生命周期：close禁止继续接收新任务，取消并shutdown后在释放Jobs监视器的情况下等待后台线程退出；最多5秒，未退出明确失败，并保留调用线程中断标记。它不是删除失败断言或延迟清理目录来掩盖问题。

JobsTest从2项增至5项，新增验证后台finally清理完成前close不能返回、清理可以取得Jobs监视器、关闭后拒绝新任务、调用线程中断状态保留，以及不停止的工作线程必须报告超时。v5的JobsTest为5/0/0/0，MineruImportTest仍为原5项且5/0/0/0。v3→v5另有ProductionContextTest从10增至12，因此总数129→134，不把全部新增用例误算为Jobs测试。

### v5为何不是最终结论

v5的证据JSON记录 `sourceFileCount=66`、`sameSource=true`，与统筹当时检查相符；监督检查时工作区已开始下一轮路由配置修复，v5和当前源码的差异准确为ProductionContext、ProductionPrompts、ProductionContextTest三个文件。v5的134项只绑定v5副本，不能为这些后续变化背书。

统筹报告真实页面团队清单出现路由配置错误，定位到approvedArtifacts投影遗漏已批准keyword_rule/qa_example完整body，随后补保留配置正文与明确提示约束。v6已包含该增量；用户侧路由执行不因此纳入范围。真实页面中旧错误清单的修订和最终确认由统筹完成，其证据见下一节，不能单从136项自动化推定页面已完成。

### v6最终证据与冻结边界

读取位置均为模块忽略目录：`.local/final-validation-v6-tests.log`、`.local/final-validation-v6/target/surefire-reports/`、`.local/final-validation-v6-evidence.json`、`.local/final-validation-v6-frontend.log`。监督独立确认：

- 27份XML的测试类集合等于受测src/test/java内全部 `*Test.java` 类集合；合计136/0/0/0，与Maven最终总计和evidence.counts完全一致。
- v6包含66份src文件，与核对时当前模块src的文件集合及每份SHA-256完全一致；pom也逐字一致。不是仅采信evidence中的sameSource布尔值。
- 前端日志实际为18项通过、零失败或跳过，未把它记成真实浏览器验收。
- 本轮未改业务断言、未加入HTTP失败重试、未新发起供应商调用；真实500仍须保留当次异常链。

| 证据 | SHA-256 |
| --- | --- |
| v6 src规范清单 | `7399faeed10aaf47146004438d147ba8641c98686867259d87eb704c92b82971` |
| v6完整Maven日志 | `2af0fc4150219d8990bd26758d9eb47a3b13f435eedd43a37911a820ad2e0e8d` |
| v6前端日志 | `dbf339214afc5bf5d309c92f8515586326478cdbe6e8490533cc94ccad51ca04` |
| v3完整失败日志 | `3e204a433fbabfec80452905c94b01b5ae5c9a1636bc37a8cb98bae0e3a32ef8` |
| v5完整Maven日志 | `25ba68aa727ddc59db1b1209da846aa7c0c962f382b8230a43825bbde0927114` |

受测关键文件：

| 类 | 源码SHA-256 | v6编译类SHA-256 |
| --- | --- | --- |
| `Jobs` | `06bc0adf0cd1ea6c69323ba32bfa635c25eb220cef0e023c9b41cd9993d80719` | `5a2b1ebf6907702ceaa22198d1f68113438ef9369966ae3bf9b156fc5395e94d` |
| `ProductionContext` | `131ca61ae6ceb403382bbad57f78a94c08d54b5bb6bdf902d487fb96dc58f72b` | `53ceabd1c5afd51ca1674c9383d9f821a9265f8fd73d5b9a36311f94ca29dc99` |
| `ProductionPrompts` | `4c66db7f813da7f01425dfd3abd3db4620685317c61198cff907c0f9ab0ec3a7` | `2a921a875c5e342f5be7153808d4baf8e58b94ff3aba66d68e0c7bfca6f75227` |

本节仅给出冻结源码的程序回归结论；后续真实页面证据见下一节。任何后续源码变更需重新对应验证证据，未给出用户侧执行或上线通过结论。

## v6 真实页面完成的统筹证据及独立只读核对

**执行角色区分：** 以下真实页面操作、模型调用、来源API核验均由统筹完成，监督没有使用浏览器或发起API/PG连接。本角色只读 `.local/final-state/verification.json`，核对其中状态与计数，并把统筹提供的页面结论与本角色实际可见证据分别记录。

统筹报告：在明确标注的测试团队中通过真实CUA逐稿进行9次审核，团队清单修订v2已正确呈现L1/L2/L3。监督从proof独立确认该测试队：

| 字段 | proof实际值 |
| --- | --- |
| status / phase | completed / final_review |
| 明确批准 | 9 |
| 未决澄清 / 活动任务 | 0 / 0 |
| 来源链接核验计数 | 39 |
| 五份专家完整prompt字符数 | 598 / 718 / 784 / 1255 / 980 |

proof保存的是统筹核验摘要，不包含完整清单正文或浏览器录像。因此“页面操作成功、manifest v2语义正确和39次API读取”归于统筹的真实执行证据，不能表述成监督亲自点击、逐条调用或重新审阅原文。v6自动化136项、前端18项及源码一致性则按上一节只读核对。

同份proof中的真实书籍构建保持active、reviewStatus=pending、0条批准、11份学习成果未变、2个未决澄清、0个活动任务。统筹另外说明其为概要v2；监督没有替管理员批准或解决这两个问题。原有两支测试队在proof中也仍为completed、各9条批准、0未决澄清。

proof SHA-256：`7253a17b15e90cde7add375ea779959e5eaec1084bbad0be3e233baa56147bc9`。原始私密proof、书籍与模型内容继续留在忽略目录；本文仅记录必要的脱敏状态。统筹后续确认已更换v6宿主进程并重新执行final-state-check，退出0；本角色本次重读的verification.json是该重启后证据，状态与上述计数保持。统筹还通过CUA刷新核对完成页仍为v2已完成、9次批准聊天保留、确认和发送均不可操作，截图布局可读。这些重启和浏览器操作归统筹执行，本角色仅复核文件，不记为自己重启或操作页面。

至此本角色要求的v3/v5/v6证据核对及报告写入完成，文件冻结供统筹整合交付；未修改源码，未执行提交、推送或部署。


## 统筹最终页面与重启补充（2026-10-06）

此节由统筹补充，不是监督角色操作浏览器的声明。统筹用CUA在本地48763测试团队79d7e5a9-ad72-4d3c-bb85-aa97aabc1339走完9次确认，最终manifest v2修正L1关键词、L2余弦严格>0.90、L3主专家路由；status=completed、phase=final_review、0未决澄清。39条来源经只读接口读取，五份专家完整prompt长度598/718/784/1255/980。最终v6宿主重启后再次读取得到同一状态，浏览器刷新显示v2已完成，approve/send均禁用，审核聊天保留。

真实书籍bb772764-3b95-4dfd-a307-ab31a224c628的概要v2仍pending、0批准、2项必要澄清保留；11份学习修订身份与原始快照完全一致。两支历史原创测试团队仍completed、各9批准。私密核验结果为module .local/final-state/verification.json，原始快照不提交公共仓库。管理员生产链路本轮完成；不把它扩写为最终用户路由执行或生产集群部署通过。
