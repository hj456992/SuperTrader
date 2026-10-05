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
