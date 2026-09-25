# G01 提示修正后的唯一一次真实复验

2026-09-25，TL明确授权候选 `a51f04757c5f9628b1d18328b24ebc21b43e4fe8`。**执行、保存和引用通过；reply时间定向项通过本样本，性别称谓项仍未通过。** 已先简报TL并完成清理；本报告独立于修前 `9c1791d`，不覆盖修前结果，不包含发布。

- 启动前核对候选树干净、固定SHA一致；相对47720的StrategyRuntime仅STRATEGY提示新增两行约束。garden JAR SHA256 `96a6749bb933029c2bba41c5e2755c0e72372e2cb9fba8a76de7b9a50b7a4e08`；feishu `bce78edabfefaef79d28c842966a84a6db72aa5c9224c26a90dfaa65c3f0d21f`；ranch-ui.js `e0f6b3a80a7ab2f712c4a4132f116f0d2249925f654e7f7492a9faec0c771e60`，均匹配授权。
- 读取当前候选已有Surefire：Transport 2、StrategyRuntime 9、StrategyExecution 5、ProfileAgentAcceptanceStrategy 4，零失败/错误/跳过。整数6000仍依据真实provider序列化门槛、攻略Registry请求检查与本次真实成功，不抓网络payload、不重复跑全套。
- 复用同一原创虚构fixture和修前人工预置画像，深比较确认一致。**本轮未重新验证画像生成。** 维护库postgres中的精确schema `ailiao_strategy_smoke_20260925_0e2d7c0a` 创建前不存在；48749普通bind成功。preflight、10插件active、idle基线均通过。
- 恰一次攻略POST，无外层重发；5.488秒观察到done，step=1/maxSteps=2，未触发结构修复。runId=`0018f2ae-134a-4182-887e-7e9c95602270`。events：preparing→knowledge→preparing→reasoning→validating→saving；终态job.phase=finished。
- revision恰0→1、攻略恰0→1；runId匹配、basedOnRevision=0、stale=false；原profile/analyses、双方材料、self、library与book文档不变，新Cookie读取一致。引用G01-M3/M4/M1/M2/M5均可定位，K-G01-book-0保存片段与虚构书籍完全一致。

人工定向阅读：

| 项目 | 本次结果 |
| --- | --- |
| 未知性别称谓 | 未通过：overview“根据林舟本人的材料，他偏好…”与why“依据林舟本人材料，他偏好…”仍使用无依据的“他”。没有使用“她”不等于全文称谓门槛通过 |
| reply旧日期 | 通过本样本：回复改为“我这边还要先核对自己的时间，确认后再跟你定具体哪天和地点”，未把旧材料“这周”迁作当下承诺 |
| 保留当前情境 | reply保留“下周”，与当前situation一致；没有锁定未知具体日期 |
| 其他可读性 | 5个具体步骤、停止追问信号及局限可读；最终产品判定交TL。一次样本不证明提示约束确定性保证，不追加模型请求 |

私有输出目录：`/Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/ailiao-upgrade/ops/.local/g01-strategy-smoke-a51f047-20260925/`。`product-review.md`供产品阅读；`fictional-strategy-result.json`保留纯虚构上下文、画像、攻略及元数据，SHA256 `b10e751b2cbbb377e399e147f9d3ab33c838a1e858ba9177a11ffcc3e5e78af6`；`cleanup.json`保存清理证据。目录0700、文件0600、gitignored，无凭据/Cookie。

测试PID20479正常SIGTERM退出143、48749普通bind成功；仅删除本次准确schema，随后pg_namespace计数0。测试包装session52195退出并清内存凭据。修前JSON SHA256仍为 `39c745786ef92f69018e22e44158ec6a89a918ab736636e517d1e24de3823653`；修前报告/样本未改。线上4754、Java10025与session67009均未操作，未访问业务库或真实聊天，未部署、未push。
