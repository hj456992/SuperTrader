# G01 最小真实攻略 smoke 计划

状态：仅准备；等待唯一执行 TL `01a0d244-0bdd-71e2-b824-6ac31bb31078` 给出固定候选、QA通过及执行许可。当前4754原入口48740与托管session67009持续保留，本次未创建schema、启动宿主或调用模型。

1. **固定候选与隔离。** 核对TL指定完整SHA及业务/provider JAR hash，复用其已构建候选；ops旧JAR不能代替。只用既有PostgreSQL维护库`postgres`、新schema `ailiao_strategy_smoke_20260925_0e2d7c0a`、端口48749。先确认schema不存在、端口普通bind成功；任一冲突即停止，不复用未知资源或杀占用进程。JDBC固定为`jdbc:postgresql://127.0.0.1:15440/postgres?currentSchema=ailiao_strategy_smoke_20260925_0e2d7c0a`，不含public，DB三项配置全部显式传入内存，`WECHAT_HISTORY_EMBEDDED=0`。不访问当前业务库、聊天或来源接口。

2. **一次预置原创虚构夹具。** 复用`tests/ops/fixtures/profile-real-smoke.json`的小禾、林舟、6条材料、练习册和strategySituation；在宿主启动前同一事务创建本schema的`garden_ranch_documents(id text PRIMARY KEY, document jsonb NOT NULL)`，参数化写入state、book及idle的profile-job。state revision=0、1人物/1书、本人profile=null、strategies为空；材料按fixture顺序赋G01-M1至M6，对象id=G01-person，书id=G01-book，唯一完整书摘id=K-G01-book-0。书籍metadata/chunks沿现有RanchKnowledge形状，片段text与fixture原文完全一致。对象画像预置如下合法内容，analyses存同一夹具副本；不设置spokenAt，不将入库时间当作发言日期。

```json
{"summary":"林舟表示喜欢提前一天约定散步时间，不习惯临时改变地点。","facets":[{"category":"散步安排","text":"林舟表示喜欢提前一天约好散步时间，不太习惯临时改变地点。","kind":"explicit","evidenceIds":["G01-M3"],"knowledgeIds":[],"counterEvidenceIds":[]}],"uncertainties":["实际发言日期未知；下周是否有空及是否愿意见面仍需询问。"],"knowledge":[],"runId":"fixture-g01-profile","basedOnRevision":0,"version":1,"stale":false}
```

   **画像为人工预置，本轮不重新验证画像生成、工具循环或书籍上传。** 只读确认本schema基线后，由本任务独立Popen启动候选run.py（先`--check`），保留进程身份与有界内存日志；45秒内确认10插件active、48749首页200、state与基线一致。生产session不参与测试生命周期。

3. **只发一次正常攻略。** 复用`profile_http_smoke.py`的Probe、内存Cookie、request/state/person/require；不用旧strategy()（它会先改目标），不执行setup/upload/profiles等阶段。以一个小的定向调用片段POST `/api/ranch-strategy`，body为`{revision:0,id:"G01-person",situation:FIXTURE["strategySituation"]}`；发起前标记本次已提交，响应不明也不重发。之后仅GET state，每0.6秒轮询，观察上限150秒。现有wait仅支持画像saving过渡，攻略片段只允许同run/同target、kind=strategy、phase=saving时revision=1且恰有一份对应runId/basedOnRevision=0的攻略，然后继续等待正式done；其他变化失败。不新建通用框架或扩大原探针阶段集合。

4. **预算与验收。** 一次业务POST，provider正常1次，最多允许实现内部首次+1次结构/引用修复（总计2次），无外层重试；每次整数max_tokens=6000，应用110秒共享总预算。复核现有真实provider序列化门槛与候选攻略整数6000测试证据，结合真实请求成功记录；不抓网络payload，不将旧画像测试单独当作G01全部证据。QA确定性测试负责静默取消、总预算/保存竞争与流上限，不故意制造真实失败或补跑模型。

   正常终态须done，实际job阶段可见preparing/knowledge/reasoning/validating/saving/finished（修复可重复中间阶段），step为1或2；revision恰0→1，唯一新增攻略runId=job.id、basedOnRevision=0，原画像/analyses、双方材料和书籍不变。用新内存Cookie再读一次确认可读。人工阅读overview/steps/reply/why，确认建议可执行、尊重拒绝且未编造本人空闲、共同经历或未知日期。evidenceIds须能定位虚构原文，至少含目标本人G01-M3/M4，不能把me/context归给林舟；knowledgeIds仅允许K-G01-book-0且保存片段与原书一致。未用书摘如实记not_used，不为补引用重跑或宣称带书引用已验。

5. **失败、清理与交接。** 模型失败或断言失败立即停止新增请求，记录脱敏状态/阶段/step/revision与保存情况，不自动重试。150秒未终态只取消本测试run收尾，最多再等15秒，仍未结束只终止自有测试进程；此举不算真实取消验收。成功或失败均停止亲自持有且核对身份的测试Popen，确认退出及48749释放后，仅在current_database=postgres且本次创建归属明确时执行`DROP SCHEMA ailiao_strategy_smoke_20260925_0e2d7c0a CASCADE`，按pg_namespace精确计数0确认；不清其他资源。报告候选/hash、真实请求与阶段、保存/可读/引用结论、画像预置说明、进程/端口/schema清理结果给唯一TL；凭据与完整原始响应不入报告。

后续若部署原入口，须TL另行许可，复用本批备份、空闲、单实例、hash、只读验收流程；smoke许可不包含发布。本轮仅提交此计划，不运行上述步骤，不push。
