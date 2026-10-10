# 概要审核第一闭环接口契约

## 工作台候选入口（2026-10-10，已实现接口）

`POST /api/expert-production/v1/proposals` 接收 `{"documents":[{"documentId":"…","documentVersionId":"…"}]}`，不要求先填写名称或职责。使用现有本地 Host/CSRF 校验，返回 HTTP 202 和已有 acceptedBuild 结构；管理员身份仍是本地 Demo 的 local-admin，未接入正式多租户鉴权。

服务验证资料就绪及版本归属，按资料/版本身份规范排序后派生确定的团队和构建 ID。同组合重复、重排、并发请求及资料改名都复用原构建，不重复排队；取消的构建允许创建确定的后继构建，暂停或失败不会被入口隐式恢复。旧 `PUT /builds/{buildId}` 保持完整创建请求指纹校验。工作台创建请求额外记录 `origin=workbench`，复用已有预学习与概要生成任务及表结构。

该来源的快照额外包含 `proposal.documents` 和 `proposal.sources`，后者只有 `chunkId/documentVersionId/pageNo/title` 元数据；正文仍由现有来源接口读取。`summary.body.specialists` 中每位候选除原有字段外，还须提供非空 `typicalQuestions` 和 `sourceIds`，来源 ID 校验为本轮真实输入中的片段，修订概要也执行校验。候选数量仍为1–6，展示和进入审阅不会产生批准记录，概要确认后才继续生成专家完整提示词。

下面保留最初的概要闭环设计合同，包含当时尚未实现的阶段描述；当前完整管理员生产状态及验证以 [集成记录](integration-results.md) 为准。

2026-10-05，详细设计草案。依据 [本轮范围](README.md)、[存储设计](storage-design.md)、[建表草案](schema.sql)。未实现接口、执行建表或部署 Redis。仅展开已上传资料的预学习与概要审核；概要确认后停在 `phase=specialists,status=active`，不创建真实专家，不把整个构建标为 completed。

## 状态与数据约定

| 维度 | 枚举与职责 |
| --- | --- |
| build.phase | prelearning / summary / specialists / fallback / router / production_config / final_review；后五阶段只有导航，本轮不执行 |
| build.status | active / paused / cancelled / completed；审核中保持 active，机器失败不等于人工否决 |
| generation_status / job.status | queued / running / succeeded / failed / cancelled |
| review_status | pending / needs_reason / changes_requested / completed |
| dependency_state | current / stale |
| clarification.status | open / resolved；需要理由使用 kind=rejection_reason |
| message.processing_status | queued / running / succeeded / failed / cancelled；succeeded只表示该消息已处理，不表示概要获批 |

基路径 `/api/expert-production/v1`，JSON 使用 camelCase，对应 SQL 的 snake_case。身份来自已有服务端认证；请求不能通过 actorId/createdBy 冒充管理员，所有读取及幂等重放也须鉴权。UUID 由应用生成，包括浏览器生成的 buildId/clientRequestId；无数据库扩展。编号、lockVersion、事件游标使用十进制字符串传输，避免 JavaScript 精度丢失。时间使用带时区 ISO 8601。

`current_artifact_id` 表示待办位置，`focus_revision_id` 表示聊天焦点，`waiting_question_message_id` 表示真正待答问题。查看原文、历史版本不改变待办；GET 无状态副作用。自由提问不修改成果，不推进审核。必要时由消息显式切换焦点。

内容摘要定义为 SHA-256：对 `{schemaVersion,kind,body,systemPrompt,sources,dependencies}` 做应用统一的规范 JSON 序列化（UTF-8、对象键排序、来源/依赖稳定排序、数字规则固定），不含可变审核状态。规范算法须作为实现契约固定并配跨语言样本；不可用浏览器任意 JSON 字符串代替。概要正文必含 `title:string, summary:string, outline:array, limitations:array`；完整正文、来源与依赖校验后才能 succeeded+sealed。生成中的部分 body 允许不完整，但不可审批。

## 1 建立构建

`PUT /builds/{buildId}`。buildId 同时作为创建幂等身份；已有 teamId 用其权限检查，新 teamId 必须带 teamName 并有创建权限。服务生成 buildNo，不接受客户端指定。name/responsibility 是构建输入，不暗中写成已确认专家定义。

```http
PUT /api/expert-production/v1/builds/10000000-0000-4000-8000-000000000001
Content-Type: application/json
```
```json
{
  "teamId":"20000000-0000-4000-8000-000000000001",
  "teamName":"表达能力专家团队",
  "name":"表达专家",
  "responsibility":"帮助组织观点与撰写汇报",
  "documents":[{
    "documentId":"30000000-0000-4000-8000-000000000001",
    "documentVersionId":"40000000-0000-4000-8000-000000000001"
  }]
}
```

首次 `201`，同身份同内容重放 `200`，返回同一 build/job 身份；同 buildId 不同创建内容 `409 IDEMPOTENCY_KEY_REUSED`。请求原规范内容及摘要保存在首次 system 消息 `context_snapshot.creationRequest`，其 `client_request_id=build:create`；动态状态不用于判定创建重放。既有团队不得被此接口重命名。所选资料须已解析成功、有读取权限；同一资料重复选版 `422`。

短事务：必要时创建 team，锁 team 分配 buildNo；新建 build、build_document、学习任务及概要 artifact/revision 空草稿，设置待办和当前草稿指针，写首条消息及事件，然后提交。预学习单元从开始创建即入库，完成一个保存一个；初始概要处于 queued/pending。事务外才调用模型。请求不等待全书分析。

```json
{
  "buildId":"10000000-0000-4000-8000-000000000001",
  "phase":"prelearning","status":"active","lockVersion":"0",
  "jobId":"50000000-0000-4000-8000-000000000001",
  "snapshotUrl":"/api/expert-production/v1/builds/10000000-0000-4000-8000-000000000001/snapshot"
}
```

## 2 读取恢复快照

`GET /builds/{buildId}/snapshot?messageBeforeSeq=80&messageLimit=50`。默认返回最新50条消息，最多100条；向前翻页游标只影响消息窗口，不影响当前完整状态。响应来自一次只读 REPEATABLE READ 事务；不在事务内请求 Redis/模型/文件存储，必要正文已在 PG。返回与快照一致的 `lastEventSeq`，前端随后从该位置补读，不能先查询事件最大值再分别读状态。

下面 UUID 均为示例，hash 中的64位字串只是结构样本，不代表真实计算结果。

```json
{
  "buildId":"10000000-0000-4000-8000-000000000001",
  "phase":"summary","status":"active","lockVersion":"7","lastEventSeq":"18",
  "progress":{"jobStatus":"succeeded","phase":"awaiting_review"},
  "currentArtifactId":"60000000-0000-4000-8000-000000000001",
  "focusRevisionId":"70000000-0000-4000-8000-000000000001",
  "waitingQuestionMessageId":"80000000-0000-4000-8000-000000000001",
  "summary":{
    "revisionId":"70000000-0000-4000-8000-000000000001","revisionNo":"1",
    "generationStatus":"succeeded","reviewStatus":"pending","dependencyState":"current",
    "contentSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "body":{"title":"全书概要","summary":"示例概要正文","outline":[{"title":"先明确结论"}],"limitations":["部分图表须核对原图"]},
    "sources":[{"chunkId":"90000000-0000-4000-8000-000000000001","pageNo":12,"startOffset":0,"endOffset":42}],
    "sealedAt":"2026-10-05T10:00:00+08:00"
  },
  "clarifications":[],
  "messages":[{"id":"80000000-0000-4000-8000-000000000001","seq":"12","role":"assistant","content":"请审核这份完整概要及来源。","presentation":{"revisionId":"70000000-0000-4000-8000-000000000001","scope":"summary.full","contentSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}],
  "olderMessagesBeforeSeq":"12","hasOlderMessages":true,
  "allowedActions":["ask","read_source","approve_summary","reject_summary","request_changes"],
  "nextStageImplemented":false
}
```

快照必须额外返回当前待答消息及其展示上下文，即使它不在最近消息窗口；返回全部未决澄清、当前任务错误/取消状态、最近动作处理结果，已解决澄清可另随历史消息分页。`allowedActions` 为便利提示，写入时仍重检。浏览器只在完整展示正文及来源入口后回传该 presentation；后端只能验证服务过的展示身份与客户端确认，不能证明管理员实际阅读。

## 3 保存消息与自然语言确认

`POST /builds/{buildId}/messages`，同一 build 内 `clientRequestId` 唯一。原话 `content` 原样保存，任何改写独立放 interpretation。`expectedLockVersion` 是读取到的业务状态版本，不是消息数。

```json
{
  "clientRequestId":"a0000000-0000-4000-8000-000000000001",
  "content":"这份概要准确，可以通过。",
  "expectedLockVersion":"7",
  "replyToMessageId":"80000000-0000-4000-8000-000000000001",
  "reviewContext":{
    "artifactId":"60000000-0000-4000-8000-000000000001",
    "revisionId":"70000000-0000-4000-8000-000000000001",
    "presentedMessageId":"80000000-0000-4000-8000-000000000001",
    "contentSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    "scope":"summary.full"
  }
}
```

先按幂等键查已有消息：同 payload 返回同 messageId/保存结果，不重复执行；不同 payload 返回409。身份绑定和规范请求保存在 context_snapshot，不能只比较content。幂等查找优先于旧版本检查，使成功后重试仍得到成功结果。

新消息在 build 锁内保存原话、服务器上下文、序号；若显式 reviewContext 已是旧版，同事务保存 `processing_status=failed/result.httpStatus=409` 和事件，再返回 `409 STALE_REVIEW_CONTEXT`，不创建审核。旧 lockVersion 同理 `409 STALE_BUILD`。这保证被拒绝的管理员原话也可恢复。普通自由提问可省略reviewContext；仅凭省略字段或content中的“通过”不获得审批权。

其余请求持久保存消息及 `kind=interpret_message` 任务，返回 `202`：

```json
{"messageId":"b0000000-0000-4000-8000-000000000001","processingStatus":"queued","result":null,"lastEventSeq":"19"}
```

模型在事务外做意图识别及改写，可一次输出结构化建议：`intent, targetRevisionId, scope, rewrittenText, evidenceMessageIds, conditional, ambiguous, proposedActions`。这只是建议。服务按构建内管理员消息seq顺序处理语义动作，前一消息未终结时后一消息等待；生成任务可以同时进行。恢复不能越过失败但尚未决定重试/终结的消息。已处理失败为终结；再次尝试由新clientRequestId发起，不静默重放副作用。

落地动作时再次锁 build/job，复核接收时版本、最新待答问题、真实原话及权限；同一消息多动作以稳定actionNo编号。事务统一保存解释、assistant回复、review/clarification、修订/任务、状态及事件。模型的“确认”建议不足以批准；含条件、仅认可解释/职责/修改方向、来源引文内“通过”、范围含糊等都先澄清。明确且合法的自然语言确认直接通过，无需再点按钮。

| 管理员表达及上下文 | 保存结果 |
| --- | --- |
| 已展示当前概要后明确确认 | review(approve,summary.full)，revision.review_status=completed；phase=specialists，待办指针置NULL，焦点可保留已确认概要；提示下一阶段未实现 |
| “不对”但没有原因 | review(reject,reason=NULL)，needs_reason；clarification(open)，生成追问并更新waitingQuestionMessageId；不立即改稿 |
| 回复具体否决原因 | 保存澄清answer/resolved_message_id；当前补原因消息另追加reject及真实reason，以messageId/actionNo防重；旧revision=changes_requested，原reason=NULL记录不改写；新revision.change_reason引用该原因 |
| 有具体原因的否决/明确改稿 | 保存拒绝及原因（改稿指令本身不伪造reject审核），旧版changes_requested；创建新草稿revision并切换current_revision_id；排入revise_summary |
| “修改后就可以” | 只授权指定修改；新稿pending，重新展示后再确认 |
| 解释后的“可以” | 表示解释被理解或范围不明；不套用先前概要展示作为批准依据；必要时追问 |
| 自由询问或查原文 | 保存消息与答复，不改body、review_status、phase；不自动替换原审核待办 |

成功处理后的消息result示例：
```json
{"httpStatus":200,"actionResults":[{"actionNo":0,"type":"approve_summary","revisionId":"70000000-0000-4000-8000-000000000001","reviewStatus":"completed"}],"phase":"specialists","status":"active","lockVersion":"8","nextStageImplemented":false}
```

异步解析期间别人改稿，worker保存409结果并发布message.processed，不推进任何审核。初次HTTP已202，不能事后改变该HTTP状态；通过快照/事件得到 `result.httpStatus=409`，同键重试返回保存的409。统一错误体：`{"error":{"code":"STALE_REVIEW_CONTEXT","message":"概要已更新，请查看新稿后重新确认","currentRevisionId":"…","currentLockVersion":"8","snapshotUrl":"…"}}`。刷新后确认须使用新操作身份，不能把旧请求自动改成批准新稿。

## 4 异步生成与短事务

无需单独暴露“模型生成HTTP流”接口；创建构建、合法改稿消息产生PG任务。`GET /builds/{buildId}/jobs/{jobId}` 返回 `id,kind,status,phase,targetRevisionId,cancelRequested,attempt,error`，不返回供应商凭据、私有checkpoint或签名URL。202表示排队，succeeded表示已保存生成稿；必须另看reviewStatus。

修订策略：接受修改时先创建空草稿新版本并切换 artifact.current_revision_id；旧稿仍可查看且确认历史保留。任务 `target_revision_id = expected_current_revision_id = 新草稿ID`，`based_on_revision_id=旧版ID`。任务输入固定依赖版本及消息/澄清ID，不能执行时随意读取“最新输入”。构建位置留summary，新稿pending；废弃同目标旧任务设置cancel_requested并撤销其提交资格，绝不覆盖旧封存稿。只有已封存旧稿设changes_requested；若改的是仍生成中的未封存草稿，旧稿改为generation_status=cancelled、review_status仍pending，不能制造未送审稿的人工审核状态。修改后仍适用的澄清保留并由服务计算其适用范围；有未决必要问题不得确认。

领取及提交顺序（SQL为说明，不在本轮执行）：

1. 构建任务始终按 `build -> job -> artifact/revision` 加锁；若涉及team则team最先。候选build可用 `FOR UPDATE SKIP LOCKED` 短事务领取，再锁任务，重检queued且到期/旧running租约过期、未取消；生成任务要求build=active，interpret_message在paused/cancelled仍可领取以处理查看、解释或合法控制，严禁因此恢复生成或审核。递增lease_epoch、attempt，写owner/until并提交。仅资料解析任务没有build锁。不要先锁job再等build，以免与审批形成反向锁序。
2. 事务外做限流与外部调用。固定租约期限L，续期间隔小于L/3，均为服务配置，不承诺恢复时限。续租只用同owner/epoch、status=running且lease_until>数据库当前时间的条件更新；失租不能续活。等待外部响应时也续租并检查取消，不能只等token到来才检查。
3. 每次保存部分草稿、checkpoint和最终结果都重新短事务锁build/job：检查同owner/epoch、有效租约、未cancel_requested、build仍active、target仍为artifact当前修订、输入依赖仍有效、目标未封存。全部成立才写。新结果先存body/来源/依赖并校验，成功时同事务设置succeeded、sealed_at/hash、pending、summary待办、完整展示消息及summary.ready事件。部分草稿不发布“可以确认”。
4. 取消/暂停消息经程序识别后，在同一短事务锁build及相关生成job：设置build为cancelled/paused，job.cancel_requested=true、lease_epoch加1、status=cancelled、lease_owner/lease_until=NULL；未完成目标generation_status=cancelled，已封存成功稿不变。此处cancelled表示执行资格已撤销，不声称外部调用已停算。旧worker下一次续租/检查失败后停止本地调用并清理，不能再写结果。控制消息自己的interpret_message任务正常提交处理结果；其他消息落地时须重新检查paused/cancelled，只允许查看、解释及合法控制操作。关闭SSE只释放监听。暂停恢复无须等旧外调结束：新消息创建全新jobId/dedup_key，旧任务及其取消标记、epoch、检查点保留；新任务input记录resumedFromJobId，在未封存目标按检查点续接并重置generation_status=queued，已封存内容不重写。取消构建不能用继续复活，须另建构建。外部调用是否停止取决于供应商，不宣称撤销费用。
5. 已失租worker不得写任何业务结果，也不得将已被新worker领取的job标失败。当前owner仍有效但目标过期时只终结该任务并记录废弃原因。机器失败保存failed/error与事件，review保持pending；重试须经过明确继续/重试消息，用新消息派生dedup_key，复用未封存目标或新建修订，不改历史。

`ep_job.dedup_key` 示例 `build:{id}:prelearn`、`message:{id}:interpret`、`message:{id}:action:0:revise`。任务租约只能限制提交，不保证供应商只执行/收费一次；外调结果不明先保存external_refs并核查，无法核查时failed并向管理员说明，不盲目重发。所有任务输出含消息/修订身份，不能按“最近一个任务”挂接。

业务状态/待办/焦点/审核上下文改变时递增build.lock_version；仅追加消息、事件发布重试或租约心跳不递增。消息序号、事件序号在同build行锁内递增并和记录一起提交，禁止预留全局号后晚提交。ep_event同时充当outbox，不新增表。

## 5 事件补读与SSE

`GET /builds/{buildId}/events?afterSeq=18&limit=100`：默认100、最多500，按seq升序返回 `{events:[{id,seq,type,payload,createdAt}],nextAfterSeq,hasMore}`；空页保持原游标。必须读权威PG，不能因Redis没通知就返回无变化。事件包含messageId/revisionId/jobId和必要状态，正文从快照读取，避免大内容重复传输。

`GET /builds/{buildId}/events/stream?afterSeq=18`：SSE的 `id` 是构建内seq；重连接受 Last-Event-ID，有该header时优先于query并校验非负整数。不重复启动生成。先按PG游标补读，再持续轮询；Redis通知仅唤醒一次PG查询，所以补读与订阅切换没有只靠通知的丢失窗口。

```text
id: 20
event: message.processed
data: {"messageId":"b0000000-0000-4000-8000-000000000001","result":{"httpStatus":200,"phase":"specialists","nextStageImplemented":false}}

```

最小事件集合：build.created、message.created、message.processed、job.progress、summary.draft_saved、summary.ready、review.recorded、clarification.opened、clarification.resolved、build.updated。前端按seq去重，看到跳号先补读；连接有界缓冲，慢客户端断开后补读；定期心跳不分配事件序号。无上线性能承诺。

本草案不做事件删除，因此不存在主动过期窗口；未来如清理历史，须增加保留游标约定，旧游标返回 `410 CURSOR_EXPIRED` 并要求快照恢复，不能静默跳过。非法或超过当前event_seq的游标返回400。PG事务提交后通知失败不影响已完成审核。

## 6 读取原文

`GET /builds/{buildId}/sources/{chunkId}?startOffset=0&endOffset=42`。必须通过build_document验证片段属于本次选定版本并检查资料权限，不能凭chunkId跨资料读取。缺省偏移读取整片段；显式偏移为0起始、左闭右开Unicode码点，Java用codePointCount/offsetByCodePoints，不能直接按UTF-16下标截取。越界422。

```json
{"chunkId":"90000000-0000-4000-8000-000000000001","documentVersionId":"40000000-0000-4000-8000-000000000001","chapterPath":"第一章","pageNo":12,"startOffset":0,"endOffset":42,"text":"此处返回已保存原文区间；本示例省略完整42个码点。","textSha256":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","qualityFlags":["figure_text_incomplete"],"locator":{"pageNo":12,"blockId":"p12-b3"},"originalPageUrl":"/api/expert-production/v1/builds/10000000-0000-4000-8000-000000000001/source-versions/40000000-0000-4000-8000-000000000001/pages/12"}
```

textSha256始终是完整chunk的摘要，区间由offset标识。`GET /builds/{buildId}/source-versions/{versionId}/pages/{pageNo}` 经过同样授权后代理共享文件存储中的原页/原件，响应正确媒体类型；不把永久对象键当公开URL，临时签名地址不进数据库事件。本轮不选定或部署共享存储提供方。原文与管理员意见分开，补理由不修改chunk。

## 7 数据库硬约束与服务校验

| 数据库已表达的硬约束 | 必须由服务短事务表达的规则 |
| --- | --- |
| 15表主键、唯一序号/幂等键、非负计数、状态枚举 | 合法状态转移；枚举CHECK不能证明管理员确实批准 |
| build_document版本属于document；来源属于构建已选资料版本 | 鉴权、解析就绪、整书覆盖与OCR疑点展示 |
| 复合FK限定同build的revision/message/review/job/dependency | 当前目标校验、范围/原话/展示语境校验、必要澄清检查 |
| 当前修订及based_on属于同artifact；review.hash匹配对应revision | based_on更早、审批对象仍为当前、scope适用于kind |
| sealed/hash与succeeded配对；非pending审核须sealed | 已封存正文/提示词/来源/依赖不可修改；原始消息及审核历史不可覆盖 |
| 依赖禁止直接自引用、同构建；来源偏移非负有序 | 在build锁内检查新增依赖无环、relation两端kind合法、偏移上界与页数 |
| fallback/router部分唯一索引 | 不固定子专家数量；后续团队清单完成条件（本轮不执行） |
| running任务必须有租约字段 | owner/epoch/有效期/取消/目标/输入依赖同时重检，防晚到输出 |

SQL不含触发器和授权策略：应用必须收口写入，不能宣称任意直接SQL更新都受上述语义保护。updated_at也由应用更新。循环外键分两批建：先创建15表，再ALTER添加team完成构建/build焦点待办/artifact当前修订指针；数据写入先NULL指针建身份再挂接，详见schema.sql。

## 8 Redis职责与边界

沿用第13篇三项：封存revision/chunk按ID+hash缓存并PG回源；`ep:build:{id}:changed`通知只携带event身份/seq；供应商额度按provider/credentialAlias/window共享限流。额度需原子预占，Redis异常暂停新的付费调用并保留queued任务，已记录审核与读取可继续；模型意图处理如需付费也等待，不假装已理解并确认。限流异常不是生成失败或管理员否决。

通知发布器扫描ep_event未发布行，先投递再记录published_at；崩溃会重复投递，按事件ID/seq去重。Redis Pub/Sub不是唯一可靠队列，任务仍从ep_job轮询，页面仍从ep_event补读；不加Redis分布式锁、Streams、向量库或独立消息服务。本契约未定义部署拓扑及真实限额。

## 静态验收与实现前验证

本轮可检查表数、FK目标唯一性、状态一致性及草案结构，结果由 `checks/backend-static-check.py` 生成；没有执行DDL，不能据此声称迁移、事务或性能已通过。实现时至少验证：同键同内容重放/不同内容409、旧版确认与改稿并发、无原因否决再补理由、条件同意不越权、自由聊天后回到原待办、失租旧worker/取消后晚到输出、PG已提交通知未发、Redis不可用、跨构建/资料越权、刷新快照与事件无漏读。还须在隔离空库执行DDL和约束负例，绝不能直接用真实业务库试建表。
