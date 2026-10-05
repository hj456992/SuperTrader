# 管理员生产代码重点复核

日期：2026-10-05。比较基线：`9e6b834`，包含新建但当时尚未跟踪的 Production 类、测试和前端文件。监督角色在共享源码上只读核对实现，未修改生产代码。本文记录本轮已定位的问题和修复检查，不代表已完成全部真实模型、浏览器或集群部署验收。

## 已定位并修复的问题

**P1：自然语言条件、引用或歧义意图可先执行状态变更。** 原 `ProductionService.applyIntent` 在统一语义判断前处理 pause/cancel/resume/retry，reject/revise/provide_reason 等也未全部复用批准时的条件检查。模型即使明确给出 conditional/quoted/ambiguous=true，部分分支仍可能取消任务、修改版本或关闭澄清。

后端报告通过受控模型的 conditional cancel 在真实 PostgreSQL 工作线程中复现取消，随后将 `ProductionRules.unambiguousIntent` 放在 applyIntent 的所有业务状态变更之前。三项标记必须都为布尔 false；任一为 true、缺失、null、字符串或数字时只返回澄清。显式操作按钮由服务构造无歧义意图，仍须通过原有对象、版本和状态校验。

独立只读核对确认：统一检查位于控制、focus、澄清关闭与审核／修订分支之前；`ProductionInterpretationTest` 覆盖 11 种意图乘 3 种不安全标记，以及 12 种标记格式异常，并比较状态、版本、成果、澄清和审核记录不变。该用例还验证缺失／null／空白的改写回退管理员原话，拒绝非文本改写。最新完整执行结果见[验收报告](acceptance-results.md)，不能只依赖后端的单项通过报告。

## 交付时保留的限制

- 历史一次 snapshot HTTP 500，以及后端 guard 专项日志中的一次、最终全量回归中的两次 worker PSQLException，均尚无明确根因。复跑成功不能写为已根治，也不能断言两者是同一原因。
- 真实模型整流程和浏览器验收独立记录；受控模型、组件 fixture 和 SQL 测试不能代替这些验收。
- Redis 业务接入仅包含共享额度，缓存和通知辅助方法不能算作已上线能力。
- 本轮复核不扩展最终用户 L1/L2/L3 执行、向量索引、公网部署或多租户能力。

本次源码可按开发快照描述，不能将此复核记录解释为完整生产上线批准。范围和待验项目以[实施计划](implementation-plan.md)为准。

## 9b1aacf 后冻结增量复核（章节、空尾批、任务控制与安全诊断）

本次按统筹限定范围只读复核，没有修改实现或扩展基础设施。对应最近一次完整 Java 105 项、前端 18 项及后续诊断定向 6 项；具体受测哈希和执行记录见[验收报告](acceptance-results.md)。在以下增量中未发现新增可复现的阻断问题，数据库连接异常仍作为未解决限制保留。

- **章节回退：** `ProductionRules.normalizeChapters` 只处理专家的缺失、null 或空章节数组，先验证来源，再使用实际 documentVersionId/pageNo/chapterPath 生成定位；不捏造章节名或改变引文偏移。非空错误类型、空字符串项、未知来源与无效页码不能靠回退放行。章节 4 项及真实 PG 旧资料集成 1 项通过。
- **空方法尾批：** `normalizePrelearn` 仍要求概要、明确 noMethodReason、合法 sourceIds，且 processedSourceIds 必须覆盖本批全部来源。上下文保留无方法原因和覆盖数量；重试不重写已封存前批。6 项预学习校验和 1 项真实 PG 尾批重试通过；这是结构与恢复保证，不是自动证明模型已完整理解原文。
- **恢复／重试幂等：** `ProductionService.resumeOrRetry` 在构建锁内先检查 queued/running 工作，已有可审稿也不重新生成；失败重试与暂停恢复分别校验，再入队。新 clientRequestId 不能绕过已有任务检查。3 项受控阻塞模型回归同时检查调用数、job 数、失败状态和恢复，后续完整流程定向复验通过。
- **安全诊断：** HTTP、worker 与失败记录使用常量操作标签；ProductionDiagnostics 输出 UTC、异常类型、SQLState/vendorCode、受限应用／驱动帧和 cause 类型，不读取异常 message，也不打印 SQL、参数或文件名。最多 4 层 cause，每层 12 个允许帧，识别循环引用。3 项诊断测试验证敏感哨兵不出现在日志、嵌套超时可定位及长链／循环有界；模型 JSON 错误仍只输出分类和坐标，不输出原文。

新异常链把本次 worker 08001 定位到 PostgreSQL `enableSSL` 读取阶段的 SocketTimeoutException。该定位不足以证明所有历史异常同根因，也不授权全局关闭 TLS、放宽验收或新增连接基础设施。没有给真实浏览器、用户侧路由或生产部署追加通过结论。

后续同组6项在原配置和仅本地非SSL验收库显式连接配置下各通过一次，后者未复现worker/HTTP/SQLState异常。63份源码／测试不变，未因对照更改源码TLS默认。原配置一次SSL协商读取超时已定位到具体阶段，底层原因与历史snapshot500仍未确定；没有将对照结果升级为通用修复结论。当前指定增量无新增阻断发现，真实浏览器权限拒绝仍是独立验收缺口。
