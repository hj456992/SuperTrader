# 爱聊后端架构差距与最小批次评估

日期：2026-09-25。性质：只读源码评估及后续建议，不是新增生产功能的实施记录，也不是本轮发布的额外门槛。

## 结论与核对基线

固定发布候选为 `4754cb10e45478a1347c1b1a49e5078829f86f59`，旧运行源码为 `b410f0f321bae43a921cda70f6375277434d2f7c`。讨论依据为 `outputs/ailiao-architecture-20260925/爱聊整体架构与核心功能.md`。通过 `git show`、`git diff` 核对提交对象，没有切换、重置或修改 TL／原实例工作树。当前后端分支的生产 Java 与候选无差异；本提交只新增本文。

架构文档对现状的主要描述与代码一致：画像为 DSH／AgentScope 单 Agent 循环，攻略为直接 ModelRegistry 调用；全局 revision、进程内保存屏障、独立 job 文档、词项检索与固定窗口分片均已存在。更正撤销传播、细粒度依赖及原子恢复仍是缺口。不能把这些缺口全部转成这一批需求。

建议先发布已验收候选；后续首批只补齐**攻略执行的取消、总时限和真实阶段**，沿用直接模型路径、最多两次结构／引用校验尝试。检索评测、来源更正和恢复协议分别列为后续可选批次，不一起开工。

本次未执行模型请求、平台读取、数据库访问、构建或业务测试。已有 Java 86／Node 60 等验收结果来自候选的 `docs/qa/tl-integration-verification.md`，不作为本次新跑结果。

## 旧数据到候选版本：快速兼容结论

未发现由本轮代码差异导致、要求正常旧版数据先迁移的发布阻塞。这是源码层面的结论，不能代替运维对实际发布制品及备份的核验。

| 项目 | 候选行为及影响 | 发布核验重点 |
| --- | --- | --- |
| 数据表 | 沿用 `garden_ranch_documents`，保留 `state` 与 `book:<id>` JSONB；没有 ALTER／全库转换 | 使用原配置中的正确数据库和 schema；不将临时验收 schema 当生产库 |
| 任务记录 | 新增同表独立 `profile-job`；缺失时读为 idle；首次任务写入 | 启动角色需要既有表的读写权限；单任务锁不跨进程 |
| 旧画像／历史 | 新增 runId、version、basedOnRevision、knowledge 等字段不在读取时强制要求；旧画像无 version 时下次画像生成从 1 开始 | 合成旧结构应覆盖本人无 analyses、画像无新增字段及旧攻略；这不是旧历史的全局版本编号 |
| 旧材料 | 保留原 at、原文、ID；没有 spokenAt 则对模型隐藏 at，日期未知；无批量回填 | 旧存储不变；已有画像中的旧日期措辞不会自动修正，用户重新生成才走新约束 |
| 书架 | 已有 chunk ID／文字不重切；旧画像无 knowledge 时按空集合遍历；禁用／删除影响引用该书的画像 | 删除会清理相关当前及历史画像，是已验收的业务语义变化，前端提示应与后端同步 |
| 接口 | 沿用命令和 revision；cancel 的 runId 可选；无 runId 仍取消当前任务 | 新页面需与新后端同版；旧页面无定向 runId，不具备同等防误取消能力 |
| 宿主与插件 | 新 garden 依赖已验收 Agent／Session／Context／Tools／AgentLoop 及相匹配合同制品 | 不能只替换 garden.jar；不得用旧共享 SNAPSHOT 重新构建后声称与验收制品一致 |
| 回退 | 新字段是附加字段，旧代码通常可读；旧 RanchKnowledge 删书不会清理新版 profile/history 中的知识引用 | 回退后写入不能视为语义兼容，旧版没有只读模式；新版清理的历史也不会因降级恢复。按备份／回滚手册处理，不能仅换 JAR 承诺数据恢复 |

发布时应先停止旧写入实例，再让新实例接同一业务库。两个进程即使端口不同，也共享 state revision 与单个 profile-job：revision 可以拒绝旧写入，但不能防止重复模型调用、job 相互覆盖或新进程把旧进程的任务标成 interrupted。单实例切换是当前实现边界，不建议为本次发布加分布式锁。

启动把残留 running／cancelling 转为 interrupted，但业务结果与 job 终态分步写入：可能已经保存画像，再中断于 done 写入前。核验应同时看结果 runId、basedOnRevision、revision 和 job，不能把 interrupted 解读为“一定没保存”。攻略目前没有 runId，不能同样精确关联。

已将以上快报直接发给 QA、运维及唯一执行 TL。生产发布仍以 TL／运维的实际检查为准；本评估没有读取真实聊天做兼容夹具。

## 1. 攻略执行一致性：建议优先

### 已有保障与实际差距

`RanchAnalyzer.start/run/generate/cancel` 已共用任务准入、冻结快照、run 身份检查和 synchronized 保存屏障。攻略需要有效对象画像，保存前仍执行全局 revision 检查；取消先通过屏障则迟到结果不保存。无需为了这些保障把攻略改成 Agent。

差距集中在执行等待：

- `generate` 每次 prepareCall 最多等 15 秒，流 `collectList().block` 最多等 110 秒；两次尝试各自重置等待。因此不是“整个攻略 110 秒”。画像则使用单个 deadline。
- 攻略传 BooleanSupplier，但没有画像作用域的 abort→Publisher 撤销桥接；`cancel` 的 agent 字段在攻略分支为空。仅凭应用代码不能证明静默 provider 会立即释放，需独立源订阅断言。
- 攻略只明确发布 knowledge 与 saving；maxSteps=2，但缺少每次模型开始、校验的实际 step／phase，不能把这些字段当准确进度。
- 输出 50,000 字符限制在 collectList 完成后才检查，不能限制收集期间的内存增长。画像的六步预算不能作为攻略的保护说明。
- 新攻略有随机 id，但没有任务 runId／basedOnRevision。图片式进度展示、重启后核对与将来的提交恢复难以一致关联。
- `RanchData.input` 已给攻略投影日期，但仅 ProfileRuntime 的提示解释 spokenAt 语义。攻略输入还含旧存储画像，旧结论中的日期误判不会被投影自动删除。

### 方案比较

| 方案 | 收益／代价 | 建议 |
| --- | --- | --- |
| 保留直接调用，局部攻略执行器 | 明确取消、总预算、输出上限与阶段；不增工具、不改结果结构校验 | 推荐首批，范围可用确定性测试收敛 |
| 攻略也迁移 Agent | 可复用 Agent 生命周期，但引入工具循环、步数／修复策略变化与新的真实模型验收范围 | 当前收益不足，不作为取消修复前提 |
| 只补文案／保留现状 | 成本低，但没有补上静默订阅释放和整体超时保障 | 可作为当前发布说明，不能算技术缺口关闭 |

### 首批拟改内容与边界

拟新增应用内 `StrategyRuntime.java`，从 RanchAnalyzer 提取攻略的直接调用执行。只接收冻结输入、知识片段、取消信号和 progress；不负责写业务库。检索仍由现有 RanchKnowledge 完成，不新增 RAG 架构或模型调用次数。

每个攻略使用单个可重放的取消信号和单调时钟 deadline。建议整个任务预算 110 秒，从 worker 开始计时；准备、检索、两次尝试、校验共享剩余额度。已开始的同步 JDBC 检索受现有连接／socket 超时约束，返回后必须检查 deadline，不能承诺线程或数据库调用瞬时终止。prepareCall 等待采用剩余预算与 15 秒的较小值；取消必须能结束本地等待，准备结果迟到时不得再订阅模型。流采用取消完成信号撤销上游，不能重复使用已知有缺陷的 other.onError 方式。

文本增量在收集时计数，超过 50,000 字符即撤销订阅并失败；同时限制累计事件数量，初始建议 20,000 个，由纯协议测试校验，不保存完整无界 chunks 列表。只接受正常 stop 的完整 JSON。结构／引用校验失败可以再尝试一次；取消、超时、传输失败和输出超限不得触发修复重试。保留整数 maxTokens=6000。

准确发布 knowledge、reasoning(step=1/2)、validating、saving；cancelled 只能在本地订阅释放并退出执行之后发布。保存仍由 Analyzer 的现有监视器和全局 revision 完成，不把写库放入执行器。新攻略附加 runId、basedOnRevision，保留原 id 与所有现有字段；不伪造旧画像版本，不回填旧记录。攻略提示明确日期未知／既有画像局限，禁止从录入顺序推断时间；不声称这能自动修复旧画像所有自然语言内容。

本批不改变全局 revision、不实现恢复续跑／状态原子提交、不改书籍切分或排序、不同步来源更正，不改 ProfileRuntime，不增加 Agent 或底座合同。

### 具体文件及验收

| 文件 | 拟变更／验证 |
| --- | --- |
| `src/main/java/dev/garden/RanchAnalyzer.java` | 攻略委托、取消信号接线、真实阶段、附加 runId／basedOnRevision；保留保存屏障 |
| `src/main/java/dev/garden/StrategyRuntime.java`（拟新增） | 有界直接模型执行、整体 deadline、取消桥接、两次校验尝试；测试可注入短 deadline |
| `src/test/java/dev/garden/StrategyRuntimeTest.java`（拟新增） | 正常完整结果；第一次非法引用、第二次修正；两次调用共用预算；静默取消／超时触发原始 Flux.create.onCancel；准备阶段取消后零订阅；文本／事件超限取消；整数预算 |
| `src/test/java/dev/garden/RanchJobTest.java` | 有效旧对象画像的攻略、旧 runId 无效、保存前取消零保存、保存先完成仍 done、revision 冲突零保存、取消后可重新开始、阶段与持久化身份 |
| `src/test/java/dev/garden/RanchDataTest.java` | 现有攻略引用规则保留；如需扩展仅增加合成旧画像／材料的兼容断言 |

验收使用虚构材料与真实本地 ModelRegistry／Publisher 合同替身，不用真实聊天。先用静默流取消和总预算测试复现，再实现；断言不能仅看 job=cancelled，必须看 provider onCancel、零保存及后续新任务可运行。画像现有预算、作用域工具、取消和终态测试应继续通过。QA 独立故障注入；通过后 TL 决定是否需要一次有界真实攻略 smoke，不默认重复全部模型验收。

预计后端实现及确定性回归 1–2 人日，独立 QA／集成 0.5–1 人日，受 prepareCall 能否及时本地解订阅的合同边界影响。这是工作量区间，不是交付时限承诺。无新服务／库／付费依赖，无 schema 变更、批量迁移或前端必需改动。新附加字段旧页面忽略；旧策略继续读取。若运行合同要求公共 DSH 改动才能实现某项保证，应先报告局部降级方案，不能自行扩大范围。

## 2. 词项检索、段落边界与评测：独立后续选项

`RanchKnowledge.rank` 对书名+片段文本取去重词项：英文／数字长度至少二，中文双字片；对命中的查询词求 IDF 式权重和，无词频饱和、长度归一、语义向量或重排。相同分数按输入顺序；所有启用书籍每次重新读取并分词。`ProfileRuntime.chat_search` 复用 rank，因此它也不是语义搜索。单字查询、同义改写可能漏检，重复书名和重叠片段可能占据前列；这些是代码导出的风险，不是已量化的线上缺陷率。

`chunk` 在每个提取 page 内按 900 字符／780 步长切片，保留 location，但可能截断否定、条件或列表。extract.py 已保留部分换行，DOCX 为整篇正文、PDF 为每页、EPUB 为顺序章节；现有 location 不等于真实标题层级，不能据其编造页码或跨章父节点。

最小路线先做离线评测，后决定算法，不先抽象 Retrieval Strategy 或引入向量：

1. 建议新增 `src/test/resources/retrieval/queries.json` 与原创书摘 fixture，约 30–40 个查询，标注可接受片段／必要上下文、无关负例。含中文同义、单字、否定、标题噪声、跨窗口、重复片段、多书及无命中；由产品／QA核对标签，留出约三分之一作保留集。
2. 在拟新增 `RanchRetrievalEvaluationTest` 离线报告 recall@6、MRR@6、无相关查询误返回率、重复窗口比例及必要限定语是否完整；先记录现状，再约定改进门槛。不能用“ID合法”替代“检索相关”。耗时另用固定合成书架规模报告，不把易抖动耗时写成普通单元测试门槛。
3. 若边界丢失是主要原因，再只对新上传文件采用段落优先分片；超长段仍有界切分。保留原始位置、明确 splitVersion，不自动重切旧书。现有 K-documentId-index 与已存引用必须保留；旧书重建需新 documentId 和显式生命周期处理。
4. 父段扩展会改变模型实际读取范围与预算；只有能提供真实、稳定、可追踪的片段 ID 才纳入引用白名单。不能命中一个小片就暗中允许引用整章。只有评测证明需要才增加扩展。

评测基线约 0.5–1 人日（另需标注审阅），段落切分及兼容回归另约 1–2 人日；父段扩展不计入该估算。落点 RanchKnowledge、knowledge/extract.py（仅提取信息确有不足时）、RanchKnowledgeTest／knowledge/test_extract.py。无重切则旧数据不变。检索质量未量化，暂不承诺某个算法一定更好。

## 3. 来源 ID、更正及撤销：先解决身份，不能靠去重推断

`RanchSources.select` 将 conversationId/memberId/messageId 组合成 sourceKey，并从中派生材料 ID。`RanchData.linkSource` 遇到已有 sourceKey 直接跳过：同一源消息文字改变不会更新已关联材料；局部快照中不再出现也不会删除。删除本地材料会大范围撤销结果，但不是来源撤回事件同步。

来源能力不同：微信 reader 优先使用 account+chat+serverId，缺失时用 shard/local 作为回退身份；飞书 collector 使用实际消息 DOM ID，但 RanchLiveSources 的会话身份按标题、成员按显示名构造。标题／姓名变化、同名及缺失来源 ID 都不能由应用自动无损归并。当前材料也没有单独保留内容版本及身份可信等级。

最小后续方案应限定“用户再次读取同一已确认来源后的差异预览／显式更正”，而非订阅全部历史。新导入可附加 sourceIdentity、identityKind、contentHash（以及明确有语义的 sourceVersion）；保留现有材料 id／sourceKey，别为全局规范化重编号。确证相同原消息发生变化时，由领域操作统一更新所有明确同源的投影，触发本人／对象与攻略失效。存在身份歧义时保留当前材料并请求用户确认，不能将不同人自动合并。

撤销仅接受明确来源撤回标识，或用户主动撤销；“本次窗口没读到”不是撤回。撤销后需禁止模型继续读取、清除相关当前和历史派生内容中的原文，审计只保留最小非原文标识；不能为恢复方便复制被撤销文本。旧 sourceKey 无法确认平台身份时不自动升级，不宣称全历史同步已完成。

拟落点 RanchSources／RanchLiveSources／RanchData／RanchService，必要时微信／飞书应用适配器及差异确认前端；合成测试需覆盖同 ID 改文、重复快照、窗口缩小不删、同名冲突、跨人物投影、撤销历史与运行中 revision 冲突。仅单一已确认来源的闭环约 2–4 人日，双平台与 UI 另评估；因此不塞入首批攻略修复。

## 4. 结果依赖、冲突与恢复：先保守一致，再精细化

### 依赖与冲突

当前全局 revision 能拒绝生成期间任何业务变更后的旧结果，代价是编辑无关人物也可能让任务失败。`invalidate` 与 `invalidateStrategies` 采用宽范围过期／删除；这比漏撤销更保守。画像 basedOnRevision/version 不是完整依赖：书籍方法、反证、补查材料、本人自述及被读取但未引用的上下文都可能影响结果。画像 version 在清空画像后也可重新从 1 起步，不是永久单调实体版本。

最小依赖改造先为新结果记录来源清单，保留全局 revision 做最终保存屏障。清单需覆盖实际进入输入／观察的材料内容版本、主体备注／自述版本、画像来源与书籍片段版本；不能只取最终 evidenceIds。新增相关资料是否让旧结果过期是另一条“覆盖范围变化”规则，不能被只检查已读 ID 的方案漏掉。旧结果缺少清单时仍走现有宽范围失效，不猜测补齐。

只有上述规则和并发测试成熟后才评估缩小冲突域。继续写整个 state 文档时不能简单去掉全局 revision 改成人物 revision，否则可能覆盖其他人物更新；细粒度写入／事务协议是单独工作。清单与保守失效约 2–3 人日；按人物并发不计入。

### 持久化恢复

`store.update(state)` 与 `writeJob(done)` 分属连接和提交。结果落库后 done 写失败，catch 仍可能尝试写 error，甚至再次抛错；因此当前“未保存”错误文案在这个故障窗口不可靠。job 写失败也可能让内存状态保留 running。这是代码级故障推演，本次没有注入生产故障验证。

首选独立小批次是在 RanchRepository／RanchStore 增加单次提交方法：同一 JDBC 事务内按 expectedRevision 条件写 state，并写同 run 的 done job；取消／保存仍受 Analyzer 监视器保护。提交成功后更新内存 job，重启从持久化终态读取；连接在 COMMIT 附近断开时先按 runId 读取已保存结果与 job 确认，不能盲目重复生成或再次追加历史。旧 running/cancelling 仍保守 interrupted，不续跑模型；结果缺失或身份不明不得猜 done。攻略首批补 runId 是必要铺垫。

这不需要新数据库或消息队列，但需明确 Repository 合同和异常分类，保留 SQL revision 检查。`RanchService.state` 仍是业务与内存 job 分步取值，即使写事务原子也不自动获得 HTTP 原子快照；测试及 UI 应允许有界过渡，必要时另统一读取持久化快照。

拟测试：更新前失败、事务内 job 写失败导致整体回滚、提交后响应丢失、保存与取消竞态、重启同 run 恢复、连续恢复不重复追加、旧数据无 runId。MemoryRepository 故障注入之外需在隔离 PostgreSQL 验证真实事务；约 1–2 人日后端、0.5–1 人日独立 QA。书籍上传／删除的 metadata 与 book 文档目前也分步写，失败可能留孤儿；应单独评估幂等清理，不扩展本批为通用任务平台。

## 批次选择与交付边界

推荐顺序是：已验收候选发布 → 攻略执行一致性一个批次 → 根据实际反馈选择“检索评测”或“结果提交恢复”之一。来源更正涉及身份及用户确认，应单独定义产品范围。上述估算不能相加后默认成为一个获准项目。

本文可独立 cherry-pick 到 TL 分支，仅含评估文档。发布候选仍为 4754cb1；不得为纳入本文替换既有生产制品。日常结论只报唯一执行 TL，并同步现有产品任务汇总，不向旧架构任务报进度。

## 主要实现依据

所有路径针对 4754cb1，可用 `git show 4754cb1:<path>`复核。

- `src/main/java/dev/garden/RanchAnalyzer.java`：start/run/generate/cancel/publish，攻略直接调用、保存与终态顺序。
- `src/main/java/dev/garden/ProfileRuntime.java`：generate、工具观察、取消桥接、实际已读白名单、nearby。
- `src/main/java/dev/garden/RanchData.java`：input/modelMaterial、validateProfile/validateStrategy、linkSource/invalidate/history。
- `src/main/java/dev/garden/RanchStore.java`、`RanchRepository.java`：文档布局、条件更新、job 独立写。
- `src/main/java/dev/garden/RanchKnowledge.java`：retrieve/rank/chunk、上传和删除生命周期。
- `src/main/java/dev/garden/RanchService.java`：state 分步读取、材料和人物变更失效规则。
- `src/main/java/dev/garden/RanchSources.java`、`RanchLiveSources.java`、`ranch_sources/wechat_reader.py`、`feishu/collector.py`：身份来源与快照范围。
- `knowledge/extract.py`、`src/test/java/dev/garden/RanchKnowledgeTest.java`：实际提取／切分与现有局部相关性测试。
- `src/test/java/dev/garden/RanchJobTest.java`、`RanchAnalyzerTest.java`、`ProfileAgentAcceptanceTimeTest.java`：已有任务、准入和时间兼容边界。
- `web/src/ranch-view.js`、`ranch-ui.js`：可选字段展示及任务轮询。
- `docs/qa/tl-integration-verification.md`、`docs/ops/profile-agent-runbook.md`：既有验收记录及发布／回滚约束。

## G01 实施记录（2026-09-25，独立于已发布的 4754cb1）

TL 明确批准后，在现有后端树实现 StrategyRuntime 与 RanchAnalyzer 接线。取消信号从任务接纳起存在，worker 设置单个110秒deadline；直接模型仍最多首次+一次结构／引用修复，每次整数6000。异步prepare等待可被本地取消，迟到准备结果零订阅；静默源使用完成信号撤销订阅。源释放先于持久cancelled，阶段包含实际reasoning/validating；新攻略附runId/basedOnRevision，旧历史不回填。

流期间最多50000个Java字符及20000个事件；超限、超时、传输或非stop终态不进入结构修复。取消／时限检查贯穿检索和保存前，保留原revision与监视器屏障。默认Runtime允许构造时models为null，兼容纯恢复测试，生成时才校验装配。未修改ProfileRuntime、RanchData、RanchStore、公共底座、依赖或检索。

三个原红门槛已提升为普通StrategyExecutionTest，另加准备未完成就结束本地任务及源取消先于终态断言。StrategyRuntimeTest验证两次共享短预算、连续chunk不续期、源取消、精确上限、终态协议及有限修复；RanchJobTest新增旧历史身份、完整响应后取消、保存先赢、revision冲突、mutation前deadline及检索耗尽／取消零prepare。定向三类共24项通过。一次保存边界用例最初通过Repository回调重入publish，造成与真实JDBC不同的内存状态覆盖；已改用真实监视器与原始流竞争，未为该测试假象修改生产发布逻辑。

同步JDBC回收、PreparedCall无dispose、供应商是否停止计算、state/job两次写入恢复等既有边界保持。未package、部署、推送或调用真实模型／数据库；QA独立矩阵和TL最终审核另行记录，不能将本地测试视为已发布。

实现候选完整后端验证：`/Users/hou/.local/apache-maven-3.9.16/bin/mvn -o -Dmaven.repo.local=.local/m2 -q test` 退出0，16 suites／76 tests，0 failures/errors/skips；StrategyExecutionTest和StrategyRuntimeTest均由普通套件自动执行。按本次报告mtime统计，未混入先前显式红测XML。日志为忽略目录 `.local/g01-full.log`；未执行package。QA独立结果仍待交接。
