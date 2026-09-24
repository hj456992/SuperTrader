# Task 3 前端交付与集成检查

基线：88cdbac。实现目录：`web/src/` 和 `web/public/ranch.css`。使用虚构内存夹具，没有读取真实聊天或私有运行配置。

## 契约与行为

- 消费 v1 job 的 `id/status/targetId/kind/phase/step/maxSteps/message/events`；停止按钮捕获 job.id，并提交 `{runId,revision}`。旧 job 无 ID 时保留原取消行为。
- 保留 1300ms 活跃任务轮询、5000ms 空闲轮询；cancelling 也按活跃任务处理。只显示服务返回的阶段/消息，最多展示最近 8 条 phase 事件，没有 Token 流式、假进度或内部推理展示。
- 目标人物页只展示本人的任务；他人任务仍阻止冲突生成。迟到的旧状态响应不能覆盖较新的刷新结果。完成通知核对 run ID 与目标。
- 运行中、停止中阻止新画像/攻略；停止中不再展示重复停止按钮。启动响应尚未返回、但轮询已取得 run ID 时，仍能停止。
- interrupted 提示重新生成，不声称自动续跑；页面打开仅读取状态，保留已有画像。
- 书籍方法与聊天/自述依据分开，显示 scope、confidenceReason、counterEvidenceIds 与 uncertainties。empty_library/no_match 明确提示。
- 画像证据仅从目标对应的发言中解析；本人画像可解析其他人物材料中标为 me 的发言。用户备注保留“用户转述”标记。书籍 ID 不能解析成人物事实。失效引用显示来源不可用。
- 所有新增动态文本和属性转义，旧画像/历史画像继续可读。

## 验证

- 基线 Node 24 项通过。新增展示与生命周期测试先运行失败，再实现通过。
- 最终命令：`node --test web/src/*.test.js`，39 项通过。
- `npm --prefix web ci --ignore-scripts` 安装现有锁定依赖，无依赖版本修改。
- `npm --prefix web run build` 成功，但首次干净构建需先 `mkdir -p web/dist/vendor`。既有 build.mjs 未创建 vendor 目录；源文件存在，错误为目标目录缺失。已报 TL/运维，不在前端权限范围修改构建器。
- `git diff --check` 通过。
- CUA 浏览器检查虚构静态夹具：阶段/步骤、进度展开、更新按钮禁用、聊天/反证/书籍详情展开、未知项与现有视觉样式。预览文件仅在忽略的 web/dist 中，不进入交付。

## 集成边界

尚未验证真实 Java 服务/模型端到端、真实取消传播、重启中断持久化；由后端与 TL 集成验收。本分支仅按 v1 契约和可控 HTTP 替身验证前端请求、轮询与渲染。

后端须在删除材料/书籍后清理引用并标记画像过期；前端不以当前书架推测历史 run 是否真实读取资料。任何字段差异请由 TL 通知，避免前端猜测接口。

## TL 审阅后文案修正

- 书架与上传成功提示补齐画像用途；策略说明改为目标画像、双方材料、关系目标与启用资料。
- 删除确认补充相关画像引用失效、需重新生成，保留既有策略删除语义。审阅时后端删除分支尚未加入画像清理，已明确报 TL，A12 须在最终后端上核验。
- 新增 4 项渲染/实际事件处理测试，先失败后通过；本次全套 Node 43/43、前端构建与差异格式检查通过。没有增加页面或功能。

## 后端 1c345b2 合同审查与终态提示修复

- 审查 HTTP 路由、job、profile：路由仍为 ranch-state/analyze/strategy/cancel，取消接收 runId，job.id/targetId/status/phase/step/maxSteps/message/events 和画像增量字段可由当前前端读取。
- 在本工作树忽略的 `target/frontend-review/` 中，从 1c345b2 提取 RanchAnalyzer/RanchKnowledge/RanchData/RanchRepository 源码，使用已有依赖以 UTF-8 编译。虚构内存仓库直接调用实际 analyzer.cancel(run-1)：error 与 interrupted 均保持原 job，前端输出与点击前完全相同，复现“关闭提示”无效。该探针没有启动 HTTP/数据库/模型，不替代端到端验收。
- 修复为单独的本地 dismiss-job 操作，按 runId 隐藏终态提示（旧 job 无 ID 时按完整 job 内容识别）；不发送取消 HTTP，也不修改后端状态。轮询/人物切换后保持隐藏，新 run 仍显示；旧关闭按钮不能隐藏或取消新 run。页面重开仍以服务保存的状态为准。
- cancelled 文案按 job.targetId 对应的实际 profile 决定是否提及已保存画像。首次生成取消、或只有其他人物有画像时，不再声称已有画像可查看。
- 3 项新增回归先失败后通过；全套 Node 46/46、前端构建及 diff 检查通过。
- 另一个已复现并报告 TL 的文案问题：实际 knowledge-delete 将引用该书的当前 profile 置空、对应 analyses 整条删除；原弹窗只说引用失效。按 TL 后续授权，在同一修复提交中明确“引用它的相关当前及历史画像会被移除，需重新生成”，保留攻略移除/其余待更新说明，对应交互回归先红后绿。探针输出再由前端渲染，确认旧书摘和被删画像不再出现。未修改后端或改变删除语义。
