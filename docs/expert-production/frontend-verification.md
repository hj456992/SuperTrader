# 管理员生产前端验证与交接

日期：2026-10-05。角色：产品与 Demo。工作区：`/Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/expert-production`，分支 `ailiao/expert-production`。

## 当前结论

生产前端实现及入口接线已经落盘；本次重新运行18项前端行为测试，全部通过，JS语法和差异格式检查通过。这里的测试使用真实前端代码、协议形状响应 fixture 和最小DOM适配器，不连接模型或PG，不代表完整十二步、真实浏览器、模型正确性或集群恢复已经验收。

真实页面 CUA 复验尚未执行：等待统筹确认隔离服务 `http://127.0.0.1:48763/` 已启动。按分工，产品角色不启动/关闭服务、不运行当前由QA独占的Maven、不自行调用供应商。真实模型完整流程由统筹验证。

## 本角色完整文件清单

以下路径均相对上述工作区：

| 文件 | 操作 | 职责 |
| --- | --- | --- |
| `expert-agent-demo/src/main/resources/web/production-api.js` | 新增 | 真实HTTP/CSRF、服务端展示身份、版本/scope、路由与历史选择 |
| `expert-agent-demo/src/main/resources/web/production.js` | 新增 | 管理员构建列表、全阶段审阅、完整稿、来源、差异、澄清、聊天、任务控制 |
| `expert-agent-demo/src/main/resources/web/production.css` | 新增 | 独立ep-*页面样式、桌面/窄屏布局 |
| `expert-agent-demo/src/main/resources/web/index.html` | 修改 | 加载生产模块及样式，增加生产审阅导航与挂载区，调整生成入口说明 |
| `expert-agent-demo/src/main/resources/web/app.js` | 修改 | 旧生成入口改为创建生产构建并进入#production；保留上传、旧试聊及其恢复逻辑 |
| `expert-agent-demo/src/test/frontend/production-ui.test.cjs` | 新增 | 18项API/组件真实行为测试 |
| `expert-agent-demo/src/test/frontend/dom-harness.cjs` | 新增 | 测试专用最小DOM边界；无浏览器控制或网络访问 |
| `expert-agent-demo/src/test/frontend/README.md` | 新增 | 前端测试运行、接线说明及限制 |
| `docs/expert-production/frontend-verification.md` | 新增（统筹本次指定） | 本验证与交接记录 |

未修改其他角色Java业务类/测试、旧Demo目录、真实state或私密配置。没有提交、推送、部署、新开任务或扩展委派。

## 测试命令与实际结果

在工作区根目录执行：

```sh
node --check expert-agent-demo/src/main/resources/web/production-api.js
node --check expert-agent-demo/src/main/resources/web/production.js
node --check expert-agent-demo/src/main/resources/web/app.js
node --test expert-agent-demo/src/test/frontend/production-ui.test.cjs
git diff --check -- expert-agent-demo/src/main/resources/web expert-agent-demo/src/test/frontend
```

本次结果：各命令退出码0；测试 `tests 18 / pass 18 / fail 0 / skipped 0 / cancelled 0`。JS语法检查无输出；差异格式检查无错误。

TDD证据：首次8项用例因缺少真实API模块失败，再实现通过；新增4项组件用例因页面不存在失败，再实现通过。后续用例曾分别暴露控制操作清空草稿、拒绝按钮未提交、旧版问题携带失效审核身份、切换构建保留完成提示，均先观察失败再修复。最后一轮未放宽这些断言。

18项实际检查：

1. 完整专家稿绑定服务端展示消息、版本、hash与完整范围。
2. 未展示完整提示词、历史版本、未决澄清、暂停或依赖过期时不能按钮确认。
3. 自由聊天保留原话，无前端批准推断；缺少展示上下文仍可提问。
4. 创建使用PUT、现有CSRF及选定真实资料版本身份。
5. 409/503传递真实错误，不返回模拟成功。
6. 来源请求编码chunk及偏移，只访问预定来源端点。
7. 最终清单用team.full，概要用summary.full。
8. 历史焦点不改服务端待办，URL可恢复选定身份。
9. 完整提示词、模型、工具可见，重开页面从服务端状态恢复。
10. 409保留管理员输入并刷新状态，不乐观批准。
11. 后台失败及未配置数据库显示实际错误，没有固定演示概要。
12. 确认点击发送真实版本化审核请求，前端不自行推进阶段。
13. 暂停等任务控制保留尚未发送的草稿。
14. 明确否决按钮发送带版本的reject，无原因由服务端追问。
15. 网络结果不明时，手动重发保留完整原请求和同一身份。
16. 历史稿提问不附带失效审核身份，原话不被改写。
17. 读取新构建失败时不会残留上一构建完成提示。
18. 关键词、问答与最终流程完整展示服务端响应，并用正确scope确认。

“真实响应”在测试名中指实际协议形状，输入数据是测试fixture，不能解释为已调用48763生产HTTP或真实模型。DOM测试的组件重建不等于真实浏览器刷新实测。

## 已接通的页面行为

旧“生成专家团队”提交 `PUT /api/expert-production/v1/builds/{uuid}` 后进入 `#production/{buildId}`。页面通过list/snapshot/events读取权威状态；不使用localStorage/sessionStorage存储生产审批事实。旧上传/试聊既有sessionStorage恢复保持不变。

完整专家稿包含职责、模型、工具、特色能力、边界及完整业务提示词；关键词、问答、最终flow及members均展示服务端内容。生成状态与人工状态分开。历史版本与当前待办分离，拒绝、确认、暂停、恢复、取消和失败重试通过API处理，自由聊天交给后台真实意图识别。界面中没有固定模型回复、固定书籍概要或正则意图批准。

拒绝无原因会发版本化reject；补充原因使用聊天。消息202只表示已保存/待处理，页面不据此伪造审核完成。异步错误在聊天处理结果中呈现。终态构建只读。网络失败可人工按原身份重发；409必须重看最新稿后再次操作，不自动批准新版。

## 与最新后端的核对及未解决项

已只读核对LabHttp：静态白名单包含 `/production-api.js`、`/production.js`、`/production.css`；artifact/revision/presentation的camelCase字段与前端匹配。

仍须统筹/后端确认的接口缺口（未在前端伪造或越权修改）：

- 最新snapshot未输出`reviews`。前端已支持snapshot.reviews/revision.reviews，但当前只能展示实际聊天、修订状态和澄清历史，不能展示未返回的审批事件。若验收要求独立审批记录，需补返回数据。
- 最新source响应仅含id/text/pageNo/documentVersionId；没有originalPageUrl，前端原件链接会隐藏。后端已有`/builds/{id}/original/{version}`路由，但前端按约定只展示服务端返回且同源同构建的原件链接。
- 最新ProductionHttp的sources分支未将startOffset/endOffset传给服务，source方法返回整片段。因此当前发送引用区间参数不能证明后端做了区间校验/截取。页面展示服务端实际原文，同时标注请求引用范围，不声称已验证区间读取。
- CUA实测未开始，不宣称视觉、响应式、原生焦点/键盘、真实刷新及全阶段集成已通过。

## 48763就绪后的CUA复验清单

1. 打开真实LabHttp页面，验证原资料库/试聊入口存在；进入生产构建列表。
2. 选择统筹准备的隔离测试构建，观察从服务器读取当前阶段、生成/审核状态。
3. 打开真实专家稿，核对完整提示词、六项信息、来源正文、历史版本及差异。
4. 在统筹指定的可操作测试团队内验证聊天、拒绝/原因、版本确认；不对真实书籍团队代替用户批准。
5. 刷新后核对构建、版本、聊天和待澄清事项；验证来源对话框、布局和终态只读。
6. 将真实浏览器证据与上述18项前端测试分开记录。模型整流程归统筹，不占用其供应商调用或审批对象。

## 交接通道状态

此前两次send_message_to_thread调用均被工具返回“MCP tool call requires approval, but approval policy is never”阻断，未声称消息送达。按本次明确指令，在本文件落盘后再尝试一次回报；实际结果随后补记。此文件可由统筹直接读取，不依赖read_thread是否返回消息正文。

本次回报结果：再次调用send_message_to_thread，目标任务`01a0e1d8-e09b-7bc0-b239-b4ad401905fc`；工具返回`isError:true`及同一审批策略拒绝文字。未成功送达，未通过其他通道绕过审批。文件产出和测试不受影响。

## CUA首次真实服务访问尝试（2026-10-05，未进入页面）

统筹通知可访问48763后，产品角色仅使用CUA：先通过`cua.getBrowser({url:'http://127.0.0.1:48763/'})`选择浏览器，再调用`cua.createBrowserTab(...,'http://127.0.0.1:48763/',{visible:false})`。

第二步在导航前被浏览器安全策略拒绝。工具明确返回：`The user declined permission for this action`，并禁止通过替代浏览器、底层命令或间接方式达到相同访问结果。因此没有获取页面、DOM或截图，没有创建测试团队，没有触发模型，也没有批准任何构建。此结果是浏览器工具的权限拒绝，不能描述为页面加载失败或服务器HTTP错误。

随后收到统筹更正：48763因隔离插件类加载器未自动注册JDBC驱动而暂未监听，统筹正在修复。这是另一个独立阻断，尚未通过浏览器连接核实。已遵照要求停止连接尝试，没有反复CUA连接，没有使用shell/Playwright/CDP/curl替代访问，也不要求其他角色代为绕过拒绝。

后续恢复条件：统筹确认服务就绪，并且本会话对该目标的浏览器访问拒绝得到解除/重新授权后，再从CUA正常入口继续。单纯服务启动不等于浏览器权限已恢复。当前18项前端测试结论保持；真实资料页面、列表、布局、完整提示词与刷新恢复仍未实测。

按统筹最新指令，此轮不再重试send_message_to_thread，回报以本文件为准。

## 统筹确认服务就绪后的状态更新（2026-10-05）

统筹最新通知：48763已成功启动，`GET /api/expert-production/v1/builds`由统筹实测200。该HTTP结果是统筹证据，本角色未重新请求该接口。

服务器未监听这一阻断现按统筹报告解除；本会话先前CUA对同一地址的明确权限拒绝尚无已解除证据。最新消息再次授权任务范围，但未说明浏览器权限拒绝已在客户端解除，因此没有重复创建同地址标签页、换浏览器或采用其他访问通道。必须先由用户/客户端恢复本会话该目标的CUA访问权限，之后才能执行真实浏览器检查；请不要把当前情况当作服务启动失败。

本轮未创建“浏览器审核验收（测试）”团队，未操作统筹自动流程团队或《金字塔原理》构建。前端代码与18项测试状态不变；真实浏览器验证继续标记未完成。回报仅更新本文件，不重试已被审批策略拒绝的send_message_to_thread。

统筹随后补充：原创3页旧资料缺少parser字段，真实创建构建返回500，后端正在兼容旧资料；修复后统筹重启并通知。本角色遵守暂停创建要求，不重复创建、不改私密state、不绕过此后端缺陷。该500来自统筹的真实创建测试，不是本角色浏览器实测结果。布局检查仍受上述CUA权限阻断。
