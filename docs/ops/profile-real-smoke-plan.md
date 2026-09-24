# 集成版真实 DeepSeek smoke 准备

状态：已准备，尚未启动。只接受监督 TL `01a0d244-0bdd-71e2-b824-6ac31bb31078` 发出的最终集成 SHA 后启验；不能用运维分支旧业务 JAR替代集成版。

## 固定隔离边界

- 集成工作树：`/Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/ailiao-upgrade/tl`。
- 唯一 HTTP 地址：`http://127.0.0.1:48749`。禁止访问 48740，也不调用 sources/source-list/source-members/link-source 等平台接口。
- 既有库 `postgres`；schema 仅 `ailiao_profile_smoke_0e2d7c0a95d2`。JDBC 的 currentSchema 不包含 public。
- 使用运行手册中的内存凭据包装；真实 DEEPSEEK_API_KEY 仅继承环境，不打印、不保存。WECHAT_HISTORY_EMBEDDED=0。
- 开始前核对指定 SHA、构建来源与端口空闲；只有本任务亲自 Popen 创建并持有的进程对象才可停止。日志通过内存管道读取，不把完整模型消息/凭据写进验收报告。
- 全部材料来自 `tests/ops/fixtures/profile-real-smoke.json`，不读取真实聊天。CookieJar 只在内存；每次重启重新 GET 首页获得 Cookie。

## 已核对的接口

当前后端实现 `1c345b2` 与 HttpApi：

| 操作 | HTTP/body |
|---|---|
| 读取 | GET `/` 建立 Cookie；GET `/api/ranch-state` 返回 state 和 job |
| 本人信息 | POST `/api/ranch-self-update` `{revision,name,about,style,boundaries}` |
| 人物 | POST `/api/ranch-person-create` `{revision,name,goalType,goal,notes}`；返回 state.people 获取新 id |
| 材料 | POST `/api/ranch-material-add` `{revision,id,speaker,label,text}`；返回材料实际 id |
| 上传书籍 | POST `/api/ranch-knowledge-upload` `{revision,filename,title,dataBase64}`，UTF-8 虚构文本在内存 base64 编码 |
| 画像 | POST `/api/ranch-analyze` `{revision,id}` 返回 job；随后轮询 state |
| 相处目标 | POST `/api/ranch-person-update` `{revision,id,goal}` |
| 取消 | POST `/api/ranch-cancel` `{runId}`；不需要 revision |
| 策略 | POST `/api/ranch-strategy` `{revision,id,situation}` |

每次 JSON 写请求带本测试实例 Cookie、Origin、X-Garden-Request: 1、Content-Type: application/json。每次写入前以最新 state.revision 为准；不通过数据库直接构造业务状态。起始状态必须人物/书架为空且无旧画像，避免重复使用非空测试命名空间。

## 检查顺序与通过依据

1. 等待完整 10 插件 active=10 和 HTTP 首页可访问。只看到依赖 9 插件 active 不能算业务启动通过。
2. 导入 fixture 的本人、人物和6条材料，保存返回的实际材料 id 及归属。本人白名单包括 self 的 me 以及人物内的 me；对方白名单只有人物的 them；背景 id 始终禁止作为人物事实引用。
3. 无书情况下运行一次本人画像：期望 done、knowledgeStatus=empty_library、knowledge为空、明确 uncertainties。事件实际出现 knowledge，后续 reasoning step 增长；这才证明有工具检索后再次调用模型的可观测证据。每个步骤记录 revision：running/cancelling 期间不变化，成功保存只增长一次。
4. 上传虚构书籍，运行对方画像与本人画像。分别核对 runId、version、basedOnRevision、非空 summary/facets、所有 evidenceIds/counterEvidenceIds 属于目标白名单，知识 id 只出现在 knowledgeIds。画像所有引用都能定位到本次已导入材料或返回书摘；method text 与虚构书籍实际片段一致，知识不得被当成人物事实。知识检索匹配由真实结果记录，若 no_match 或没有实际引用，不能冒称已验证“带书籍引用成功”。
5. 记录真实 job 阶段和最大 step，确认有限事件、无内部推理文本。至少一次包含 knowledge 后的更高 reasoning step；没有该证据时报告缺口，不能从 prompt 中有工具名推导执行成功。
6. 只修改 goal 后核对原人物 profile 的事实字段（summary/facets/knowledge/uncertainties/runId）保持一致；不以再次模型调用的随机措辞比较代替事实独立检查。用虚构 situation 运行一次旧策略，期望 done、新策略含目标本人引用，不能编造用户已有空闲时间。
7. 新画像刚启动后先发错误 runId，确认本任务未被取消；再发正确 runId、重复取消，等待 cancelled。比较之前 profile/runId 与 revision，确认未发生迟到保存。此实测不能代替确定性测试中的所有竞态窗口。
8. 重新建立浏览会话，确认已保存本人/人物画像仍可读取。
9. 重启中断检查：开启新画像并确认 running 后，仅对本任务进程做异常中断；正常 Ctrl+C 可能完成取消并持久化 cancelled，不能拿它强行验证 interrupted。异常中断后从同一 SHA/同一 schema/端口重启，期望旧 job=interrupted、旧已存画像保留、revision 未受运行进度影响。精确记录正常退出与异常中断的区别，不停止其他 PID。
10. 删除虚构书籍前保存其 documentId 与画像 current/history 引用；调用 `/api/ranch-knowledge-delete` `{revision,id:书籍documentId}`，确认当前画像与 analyses 不再有可用的已删书引用，策略已被标为过期。未曾产生书摘引用时本项只能验证删除流程，不能宣称覆盖引用撤回。
11. 取消/重启/删书变更完成后通知 TL 与前端，交接最终虚构状态供浏览器验收；此时保留本任务实例。未经协调不与前端同时取消或重启。
12. 前端验收结束并由 TL 协调后，停止本任务持有的进程，确认48749释放；核对 schema 精确归属后仅删除 `ailiao_profile_smoke_0e2d7c0a95d2` 及其对象。通过 pg_namespace 只读检查名称不存在，报告 PID退出、端口释放、schema清理布尔证据。

## 时间与失败处理

每个真实画像轮询上限150秒（覆盖后端110秒与收尾），策略上限250秒（既有策略至多两次调用），启动上限45秒。超时/模型错误不自动无限重试；收集脱敏错误类别、阶段、状态、step、revision和最终 SHA，报 TL。若核心画像失败，停止新增真实模型请求，以免重复付费；是否诊断性重试由已知故障及 TL 安排决定。真实调用与确定性替身证据分别记录。

报告只输出布尔断言、数量、状态、提交与虚构对象标识；不输出进程环境、Cookie、原始模型响应或完整日志。最终真实运行结果另增记录，不改写“尚未启动”的历史准备事实来冒充已经完成。

## 已准备的显式 HTTP 探针

`tests/ops/profile_http_smoke.py` 使用内存 Cookie，固定48749，并核对 TL 集成树的完整 SHA；不启动进程、不建/删 schema。收到 SHA、运维持有对应运行进程后，按阶段显式执行：

```sh
python3 tests/ops/profile_http_smoke.py --expected-sha "$SMOKE_SHA" --confirm-isolated --phase setup
# 后续逐项：self-empty、upload、profiles、strategy、cancel、reopen、delete-book
```

不要并发执行这些阶段；每一阶段只输出检查名、状态及数量，不输出 Cookie、凭据、模型正文。重启中断检查与进程/schema清理由 ops 控制本任务 Popen 对象另行执行，不能由 HTTP 探针猜测或杀进程。此脚本目前仅通过语法/帮助入口验证，尚未对真实服务执行。
