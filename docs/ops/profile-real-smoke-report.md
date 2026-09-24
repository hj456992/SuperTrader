# 真实模型 smoke 执行记录

## 首次执行：请求格式失败，已停止

- 授权集成 SHA：`b5df7ee90d19f9ebe2d83f27e73749c26ea2d171`。
- 工作树：`work/ailiao-upgrade/tl`；端口48749；PID55880。
- 既有 postgres 库中独占 schema：`ailiao_profile_smoke_0e2d7c0a95d2`。启动前只读确认 schema 存在且0张表。
- 宿主真实装配：configured=10、active=10，HTTP 首页就绪。

| 制品 | SHA-256 |
|---|---|
| target/garden-demo.jar | 54b6d70c0032bb9d742b13b47889cb4707c16dfbb6b68953ee0af83472ec0cab |
| plugins/feishu-history/target/feishu-history-plugin.jar | bce78edabfefaef79d28c842966a84a6db72aa5c9224c26a90dfaa65c3f0d21f |
| web/dist/ranch-ui.js | d729a1b1c6534687b6125f62fdc81e8de5935cdead4cb8602bff83305138294a |

setup通过：导入纯虚构本人、人物和6条材料，revision=8。self-empty仅发起一次真实请求，检查 `profile-done` 失败；job=error、step=1、phase=finished。事件为preparing/reasoning/validating，没有knowledge/evidence；本人画像仍空、revision仍8，未保存结果。

内存日志脱敏根因：DeepSeek返回 LlmError，拒绝 `max_tokens: 6000.0` 的浮点类型，要求u32整数。业务记录异常类别IllegalStateException。没有401/403/429或超时标记。宿主日志没有assistant/message、turn/end、tool/call事件明细，因此不据此声称精确session事件计数；HTTP job可证明未到工具阶段。

失败立即上报监督TL，后续upload/profiles/strategy/cancel/reopen/delete-book均未执行；没有为通过检查循环重试。TL交由后端修复应用请求边界，运维未修改生产代码。

按TL指令，对本任务亲自创建并持有的PID55880调用terminate；进程退出143，随后重新绑定48749成功，证明端口释放。schema保留，停止后从该schema只读核对存储revision=8。下一版须新固定SHA与build完成通知，只重新执行self-empty一次，setup不重复。

真实凭据和Cookie仅内存，日志仅由内存管道读取；未写盘模型密钥、完整模型内容或进程环境。未访问原48740，未读取真实聊天或操作现有业务表。

## 整数请求修复版：结构链路通过，服务保留验收

服务冻结 SHA：`5bfd8990244d6281e16b2d2c1bf0e6868fe71b3c`。TL完成构建后运维续验，未重复setup，启动前确认原revision=8。

- 新业务 JAR SHA-256：`d112fdf5ef59ce250014b5c929c8dd5fc35cfe0f1b5ea1335610b6291c627208`。
- 飞书 JAR 与 ranch-ui.js SHA-256 与首次记录一致。
- 启动PID60227，宿主configured=10/active=10，独立48749首页ready。

| 阶段 | 实际结果 |
|---|---|
| self-empty一次 | done，step2，5 facets，empty_library，0书摘，明确uncertainties；知识检索之后再次reasoning |
| upload一次 | 虚构TXT成功，书架1份 |
| person有书一次 | done，step3，5 facets，1书摘 |
| self有书一次 | done，step2，4 facets，1书摘 |
| strategy一次 | done，步骤/目标引用存在；仅改goal前后profile完全相同 |
| cancel一次 | 错误runId未取消；正确runId与重复取消收尾cancelled；profile/revision未迟到改变 |
| reopen | 新内存Cookie会话仍可读取原已存本人/人物画像 |
| 异常中断与重启 | 新任务确认running后仅kill本任务PID60227，退出-9；重启PID61019后同runId=interrupted、两份旧画像完全相同、revision仍14 |
| delete-book一次 | 删除前current共2条、history共2条匹配书籍引用；删除后均无可用旧引用。current/history撤回均实际覆盖 |
| 恢复upload | 重新上传同一虚构书籍，revision16 |
| 恢复person一次 | 实际done，step2，4 facets，1书摘，basedOnRevision16、保存revision17；见下方探针误判说明 |
| 恢复self一次 | done，step2，5 facets，1书摘，最终revision18；没有重复person请求 |

每次画像检查了人物evidenceIds/counterEvidenceIds归属、knowledgeIds只引用返回书摘、书摘内容确实出自虚构书籍、runId与basedOnRevision、有限step/event、检索后更晚的reasoning、正式保存只增长一次revision。报告不输出模型全文或凭据。这些结构与来源断言不能证明所有生成句子的语义完全正确。

### 两个探针/运行边界的如实记录

1. 异常kill后第一次重启预检暂时不可bind48749。只读lsof未发现listener，随后普通bind自然成功；与TIME_WAIT等待相符。向TL报告并获得继续确认后，以原SHA/JAR实际重启一次，未改预检或停止其他进程，未新增画像请求。
2. 恢复person的原probe在运行中观察revision16→17时报 `progress-does-not-change-revision`。之后只读确认该任务done、当前profile.runId匹配、basedOnRevision16、revision17。失败瞬间phase没有保留，不能宣称直接观测到那个phase；TL和后端核对源码确认存在合法saving窗口：业务保存先提交，独立job随后发布done。原probe把这个窗口也当进度写入，是过严断言。

probe修正仅允许当前job为running/profile/saving、job.id/targetId对应本目标、已存profile.runId为本run、basedOnRevision为起始版本、revision恰+1时继续有界等done；其他变化仍失败。回归先复现合法窗口被误拒，修后通过，并确认其他run的保存不能通过。未修改服务生产代码或冻结HEAD。

恢复person未重跑：对现存结果只读核对完整引用、阶段、版本、历史、书摘后判通过；仅补原计划尚未请求的self一次。服务版本与probe修正版本分开记录。

### 当前交接与未完成项

- 按TL要求保留PID61019和独占schema，48749稳定revision18/job done，供前端浏览器与产品只读验收；没有清理或追加模型调用。
- 产品对真实本人样本发现“将手动材料录入时间at当作发言日期”的语义问题，TL已交后端核对最小修复。该项未解决，不能把本表的结构链路通过称为全部业务验收通过。
- 后续针对性复验及最终停进程/准确schema删除均待TL协调；旧版回滚未实际执行，不声称完成。

## 时间语义修复：本人定向复验

服务源 SHA：`d62253a121b0ae90476212b5245a53fa2c873089`；业务 JAR SHA-256：`a9a873e917958fdbc8f482f0c0dba5962ca0458b1960186f741ba459f4438711`，均在启动前核对。上一测试PID61019已按TL要求正常terminate退出143，48749普通bind成功；停机后独占schema的revision18与双方画像均保留。

本版只启动PID66300，宿主10/10 active；仅调用一次 `Probe.profile('self','used')`，没有重跑setup、对方或策略。结果done、step2、4 facets、1书摘，引用及检索后再次模型调用断言通过，正式保存revision18→19。本次探针实际遇到受限saving过渡并继续等待done，最终只保存一次。

额外只读扫描summary、facet text与uncertainties，没有“同一天/同一日/同日/当天”或具体日历日期格式；facet kind分布explicit2、inferred2。词句扫描不代替产品对语义是否谨慎、explicit是否准确的独立审阅，服务暂保留revision19供产品复核。

测量说明：首次额外日期正则直接通过PTY输入中文时出现终端编码干扰，正则报错；此时画像调用及保存已经完成。仅对内存中已有文本用Unicode转义重做扫描，没有重复模型请求或修改生产代码。

当前按TL指令保留PID66300/48749和准确schema，等待产品结论及最终清理指令。未输出模型全文、Cookie或凭据。
