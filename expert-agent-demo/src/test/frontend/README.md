# 生产审阅前端专项检查

工作区：`work/expert-production`，分支 `ailiao/expert-production`。仅修改 `expert-agent-demo/src/main/resources/web/` 与本前端测试目录；未修改其他角色业务类、旧运行目录或真实数据。

## 执行

在工作区根目录执行：

```sh
node --test expert-agent-demo/src/test/frontend/production-ui.test.cjs
node --check expert-agent-demo/src/main/resources/web/production-api.js
node --check expert-agent-demo/src/main/resources/web/production.js
node --check expert-agent-demo/src/main/resources/web/app.js
```

2026-10-05：18 项通过、0 失败。先记录缺少模块的失败，再实现；草稿丢失、明确否决、旧版讨论及跨构建完成提示残留均有失败→修正→通过记录。测试执行真实前端模块，HTTP/DOM 边界使用本目录测试适配器与协议形状 fixture；不是 PostgreSQL/模型/浏览器端到端证明。

## 已接线的生产入口

- 旧资料上传与旧试聊维持原接口；旧“生成专家团队”改为 `PUT /api/expert-production/v1/builds/{uuid}`，携带选中的真实资料/版本、职责、新团队身份，成功跳到 `#production/{buildId}`。
- `production-api.js` 负责真实 HTTP、CSRF、展示上下文与审核范围；`production.js` 负责独立管理员视图；`production.css` 只作用于 `ep-*` 样式。页面没有外部依赖，不需要构建。
- 页面/构建刷新以 URL 身份 + 服务端 list/snapshot/events 恢复，不以 localStorage 或 sessionStorage 保存生产审核事实。旧上传/旧试聊原有 sessionStorage 恢复不属于生产模块。
- 历史焦点写入 URL 的 artifact/revision 参数，生产待办以服务端 currentArtifact 为准。只批准当前完整稿，完整专家提示词直接展示；概要/一般成果/团队分别用 summary.full/artifact.full/team.full。
- 自由聊天原话发送给真实 API，无前端意图规则、固定摘要或固定回复。历史稿讨论不会携带失效审核身份；使用关联展示消息作为 replyToMessageId，不能由这条问题批准旧稿。
- 明确拒绝通过 reject 按钮绑定当前版本，无原因交给服务端追问。暂停、恢复、取消、失败重试为真实 action；取消有页面确认，完成/取消构建为只读。网络结果未知的重发保留完整请求身份，旧版本409保留原话并读取新状态，不自动批准新版。
- 关键词、问答、最终流程和真实成员修订列表按服务端内容完整展示；来源由受控 build/chunk API 读取，文本均用 textContent。

## 集成条件与待验证

`LabHttp` 静态资源白名单须包含 `/production-api.js`、`/production.js`、`/production.css`（当前协作代码已看到这些映射）。快照须持续提供 camelCase artifact/revision 字段和 assistant.presentation；读不到匹配的展示消息时前端禁止确认，不能用浏览器自行生成的 hash 冒充展示记录。

前端测试已按 implementation-plan/model-contract 的响应形状实现。当前快照未含 reviews 时不会伪造审核历史，仅能显示实际聊天、版本状态与已返回澄清；后端后续若返回 snapshot.reviews/revision.reviews，页面会显示。原文仅显示接口实际返回文本；返回 originalPageUrl 且属于本构建同源路径才展示原件链接。

真实浏览器检查尚待统筹提供隔离测试服务地址与数据，需使用 CUA 验证实际 LabHttp 页面、刷新、来源、全阶段推进与布局。此角色没有启动/停止服务，没有调用模型供应商，没有读取密钥、真实 state 或书籍文件。禁止将这18项前端测试写成完整12步或真实模型验收已通过。
