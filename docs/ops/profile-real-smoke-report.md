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
