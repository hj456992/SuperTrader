# 生产模型与 HTTP 测试合同

本轮实现合同，2026-10-05。`ProductionModel.json(purpose,input,cancelled)` 只返回候选 JSON，程序验证后才能写入。下面固定三种 purpose，测试替身只放 src/test。

## prelearn

输入 `{buildId,name,responsibility,batchNo,passages:[{id,documentVersionId,pageNo,text}],previousUnits:[body]}`。
输出 `{summary:string,methods:[{title:string,when:string,steps:string|string[],limits:string,sourceIds:string[]}],sourceIds:string[],coverage:{processedSourceIds:string[],limitations:string[]}}`，文本必须非空，steps数组为有序非空文本数组，不能用未约定对象替代步骤。程序将数组无损规范为编号文本，同时保存原始数组`stepsOriginal`。生产校验不继承旧Demo每字段2400字、12来源的限制，不截断完整方法；仍执行现有ModelCalls总输出预算、真实来源和全批覆盖检查。
必须引用本批原文，所有本批id必须纳入coverage；每个方法必须有来源。previousUnits用于跨页方法衔接，不能把片段边界当成概念边界。

## generate

输入 `{buildId,name,responsibility,kind,agentRole,logicalKey,plan,learningUnits:[覆盖摘要及方法索引],relevantMethods:[完整方法],approvedArtifacts:[职责/边界摘要],sources:[{id,documentVersionId,pageNo,text}],previousRevision:revision|null,changeReason:string,contextCoverage:object}`。
整书全文保存在PG，单次generate使用最多18000字符的相关完整方法和18000字符原文窗口；全部批次保留覆盖索引，省略或预览明确标注。已批准专家的完整prompt不在每轮重复注入；当前修订对象保留原完整稿。总输入超过90000字符拒绝调用并保留可重试状态，不能无界发送整书。`contextCoverage`记录原文总数、所选原文身份、学习批次数和本次完整方法数；输出只能引用实际进入本轮窗口的来源。
输出统一 `{body:object,systemPrompt:string,sources:[{chunkId,quote,purpose}],clarifications:[string]}`。quote必须逐字复制本轮指定chunk中的唯一原文，程序精确匹配后计算Unicode码点左闭右开范围，模型不手算offset。不匹配、多处匹配、未知chunk或切断码点均拒绝。兼容旧来源`{chunkId,startOffset,endOffset,purpose}`的合法整数偏移；若引文与偏移同时提供，二者必须完全一致，不自动夹边界或修正冲突。持久化与摘要使用规范化`{chunkId,startOffset,endOffset,purpose}`，原文通过不可变chunk可重建。sources非空、必须在本轮原文窗口。

- kind=book_summary: body `{title,summary,outline:[object|string],limitations:[string],specialists:[{key,name,responsibility,methodTitles:[string]}]}`；specialists 1–6，key唯一。
- kind=agent: body `{name,description,summary,responsibility,model,tools:[string],capabilities:[string],boundaries:[string],chapters:[string],overlapAnalysis:string}`；systemPrompt为完整业务提示词，非摘要。agentRole=specialist/fallback/router，router只负责路由。
- kind=keyword_rule: body `{rules:[{specialistKey,keywords:[string]}],multiMatch:"L3",noMatch:"L2"}`。
- kind=qa_example: body `{examples:[{specialistKey,question,answer,sourceIds:[chunkId]}],metric:"cosine",threshold:0.90,comparison:">",multiMatch:"L3",noMatch:"L3",embeddingProvider:"aliyun-bailian"}`。实际向量执行不在此范围。
- kind=team_manifest: body `{summary:string,flow:[string],limitations:[string]}`；程序另注入真实经批准revision身份列表，不接受模型伪造成员。

clarifications为空代表模型无待澄清问题；非空逐条持久化，处理完之前不得批准。解释/修订用真实模型，非模板替代。每条必要澄清由管理员原话及interpret.resolveClarificationIds明确绑定。

## interpret

输入 `{buildId,message:{id,content,reviewContext,replyToMessageId},snapshot:工作快照,sources:[原文片段],contextCoverage:object}`，有action按钮时无需本purpose，程序以按钮表达且仍硬校验。工作快照保留当前完整稿、所有未决澄清、近期12条消息及被回复/待答消息，其余专家仅传摘要；管理员本条原话不裁剪，原文窗口限18000字符，总输入限90000字符。完整历史继续存在PG与管理API，不向模型声称全部历史都在上下文中。
输出 `{intent:"ask|read_source|approve|reject|revise|provide_reason|pause|resume|cancel|retry|focus",targetRevisionId:string,scope:string,rewrittenText:string,reason:string,reply:string,conditional:boolean,ambiguous:boolean,quoted:boolean,resolveClarificationIds:[id],sourceIds:[chunkId]}`。
模型返回的`rewrittenText`缺失、null或空白文本时，程序逐字保留`message.content`并保存`rewritten=false`；非字符串拒绝，非空字符串保留原值并按与原话是否相同记录`rewritten`，不补猜语义。三个安全标志必须全部为明确的布尔false才可进入状态修改分支；任一为true、缺失、null或其他类型时仅回复澄清，不执行控制任务、审核/修订、焦点切换或问题关闭。显式按钮由服务构造明确意图，仍执行版本与范围校验。
只有 unconditional + unambiguous + 非引文 + 完整scope + reviewContext真实展示 + 当前版本才可批准。无原因reject追问；provide_reason/revise必须实际reason，生成新版本pending；普通ask/read_source不推进。若管理员回答requirement/source_conflict，必须携带其展示上下文，按原话形成新修订并展示新的完整提示词后再审批；不能只关闭问题后批准未纳入答案的旧提示词。纯confirmation_scope澄清可以只解锁范围。reply是对管理员的公开回答，不是内部思考。多意图或不明对象必须ambiguous=true并追问，不能擅自批准。

## 生产模型失败诊断与预算

生产调用独立读取`EXPERT_PRODUCTION_MAX_TOKENS`（默认7000，允许1024–32768）和`EXPERT_PRODUCTION_MAX_CHARACTERS`（默认40000，允许4000–262144）；旧Demo调用预算保持原值。默认预算尚未提高，实际供应商是否接受更高预算仍需验证。

生产模型失败在任务`error`和`job.failed`事件中保存`code`、白名单`finishKind`、`maxOutputTokens`、`maxOutputCharacters`和安全说明，不保存模型正文、供应商原始异常或凭据。`max-tokens`/`length`或本地字符上限对应`MODEL_OUTPUT_LIMIT`；缺失结束标记为`MODEL_STREAM_INCOMPLETE`；正常结束但JSON无效为`MODEL_JSON_INVALID`；网络I/O异常为`MODEL_TRANSPORT_FAILED`；超时为`MODEL_TIMEOUT`。供应商错误终态、未知终态、配置无效及未分类异常分别保留独立code。字符上限触发时`finishKind=none`，不可据此声称供应商token用尽。失败不修改已完成批次检查点，重试跳过已封存成功批次。

## 审核请求与 HTTP 接口

`new ProductionHttp(ProductionService service)`；`boolean handle(HttpExchange exchange)`匹配`/api/expert-production/v1`并处理、返回true，否则false。Host/CSRF由外层LabHttp处理；独立服务器需同样校验，不能裸露写接口。

消息 `{clientRequestId,content,expectedLockVersion,replyToMessageId?,reviewContext?,action?}`。
reviewContext=`{artifactId,revisionId,presentedMessageId,contentSha256,scope}`，直接取最近assistant presentation字段加其message.id；完整scope分别summary.full/artifact.full/team.full。snapshot.messages会带presentation，currentArtifact.currentRevision包含完整body、systemPrompt、sources。
action=`{type:approve|reject|pause|resume|cancel|retry,scope}`；reject原因用request.reason，可为空（追问）。所有message保存真实content。状态控制无需reviewContext，approve/reject必须；自然语言审批也必须携带对应展示上下文。模型不能提供缺失的授权。
PUT构建与POST消息返回202；同payload幂等重放返回已保存result状态；旧version/hash与幂等重用409。阶段completed表现为status=completed, phase=final_review。
