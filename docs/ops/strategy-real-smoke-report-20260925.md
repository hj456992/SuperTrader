# G01 一次真实攻略 smoke 结果

2026-09-25，依据TL对 `d789b72` 计划的明确执行许可。结论：正常真实执行、保存与引用检查通过，隔离资源已清理；人工阅读发现无依据的性别代词，已交TL供产品复核，未因此追加模型请求。本轮未发布原入口。

## 固定候选与协议证据

- 候选 `47720ada5d23d828ea6a4692612b60bb3674e04c`，启动前核验TL树HEAD一致且干净。
- garden JAR SHA256：`6e6c1639846c0a8c9af463c62f796e51e8bd12f7dbd6d16924ace4280ecdcc3e`。
- feishu JAR SHA256：`bce78edabfefaef79d28c842966a84a6db72aa5c9224c26a90dfaa65c3f0d21f`。
- ranch-ui.js SHA256：`e0f6b3a80a7ab2f712c4a4132f116f0d2249925f654e7f7492a9faec0c771e60`。
- 既有真实provider JAR SHA256：`44d043162eaadedad6c267c3c9669236617e255392c49b8c0bb4d2ef6f9cb020`；宿主JAR：`b36bd1729245c7c488efa640c05889922f59674c29001f569f6f833fa57cacaa`。
- 读取候选已有Surefire结果：ProfileAgentAcceptanceTransportTest 2、StrategyRuntimeTest 9、StrategyExecutionTest 5、ProfileAgentAcceptanceStrategyTest 4，均零失败/错误/跳过；未重跑完整套件。真实provider纯序列化门槛检查整数6000并包含6000.0反例；攻略测试检查实际Registry请求及一次引用修复的整数6000。结合本次真实provider成功记录协议通过，不宣称截获了线上payload。

## 实际请求与验收

维护库 `postgres`，独占schema `ailiao_strategy_smoke_20260925_0e2d7c0a`，端口48749。创建前确认schema不存在且普通bind成功。三份文档预置后启动测试PID17973，preflight通过，10插件active，HTTP基线一致且job idle。**全部材料原创虚构，对象画像为人工夹具；本轮未重新验证画像生成、工具循环或上传流程。** 复用现有Probe的HTTP/Cookie方法和一个内存定向片段，没有新增测试框架或修改生产/探针代码。

| 检查 | 真实结果 |
| --- | --- |
| 请求次数 | 恰1次攻略POST，无外层重试；step=1，reasoning阶段1次，未触发结构修复 |
| 终态与时间 | done；从发起到观察终态6.009秒；maxSteps=2 |
| runId | `f4f9e3ba-bb00-4a70-899c-0f7085786ef5` |
| 阶段 | events为preparing→knowledge→preparing→reasoning→validating→saving；终态job.phase=finished。finish本身不追加phase事件，不把未观察到的event写成已发生 |
| 保存 | revision恰0→1、strategies恰0→1；攻略runId匹配、basedOnRevision=0、id非空、stale=false |
| 其余状态 | self、对象profile/analyses、双方材料、library及数据库book文档与基线深相等；新Cookie读取同一状态 |
| 内容 | overview/why/reply及5个步骤可读；未编造确定空闲或已答应邀约，包含拒绝/模糊答复时停止追问的建议 |
| 材料引用 | G01-M3/M4/M1/M2/M5均可定位；含目标本人依据，不含背景M6或书籍ID |
| 书摘引用 | K-G01-book-0；保存片段id/documentId/title/location/text与预置虚构原书完全相同 |

人工发现：原材料与预置画像均未说明性别，输出用“他”指林舟、“她”指小禾。已原样保留供产品判断，不将运行成功称为全部语义验收通过，也不为修词重新调用模型。静默取消、110秒总预算竞争、超限与修复分支仍依QA确定性测试，本次正常请求不声称真实覆盖这些边界。

## 私有结果与清理

- 供产品阅读：`/Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/ailiao-upgrade/ops/.local/g01-strategy-smoke-20260925/product-review.md`。
- 同目录 `fictional-strategy-result.json` 保存纯虚构上下文、预置画像、攻略、阶段及hash；SHA256为 `39c745786ef92f69018e22e44158ec6a89a918ab736636e517d1e24de3823653`。`cleanup.json`保存清理布尔证据。目录0700、文件0600、gitignored，不含凭据/Cookie/原始网络日志。
- 自有测试PID17973正常SIGTERM退出143；48749普通bind成功；仅DROP上述精确schema，随后pg_namespace精确计数0。测试包装session8653已退出并清内存凭据。
- 原入口4754、Java10025及托管session67009均未操作；没有访问业务数据库、真实聊天或用户页面。未push，未部署。真实结果及cleanup已先简报唯一执行TL，本文为后续短报告。
