# 画像 Agent 接入与运行清单

2026-09-24：已读取本机 dsh-java 的 Agent、AgentRegistry/Factory、CreateAgentOptions、SessionStore、ToolRegistry/ToolScheduler、AgentScopeRuntime、DshStepMiddleware、DshResultMiddleware 实现。无需修改公共合同。

## 接入

画像通过 AgentRegistry.create(ownerCtx, options) 创建每次任务独立 Agent；setup 中按 agentCtx.scopeKey() 登记书籍检索、聊天检索、上下文展开三个只读工具和系统提示。ToolsPlugin 将作用域可见工具加入模型请求；DshResultMiddleware 交给现有 ToolScheduler 执行并回填观察；AgentScopeRuntime 内部是真实 AgentScope 2.0.1 ReActAgent。应用不再造工具循环。画像输入删除 goal/goalType，主体证据白名单和最终引用校验仍在应用层。

agent/pre-step 控制最多 6 次模型调用，任务总时限 110 秒，工具内容有界；Agent.cancel(cause,false) 传播取消，whenIdle 等待实际收尾。最后只接受正常完成的完整 JSON。旧策略保留当前模型入口。

SessionStore 是内存会话目录，SessionPersistence 在工厂中可选。本轮不装配 storage/storage-pg/session-checkpoint-policy，不保存模型内部会话；业务画像与最小 job 记录复用现有 garden_ranch_documents，job 独立文档不增加业务 revision，重启 running/cancelling 转 interrupted。

## 启动插件

表中 artifact 相对 dsh-java 根目录；所有版本为本机 0.1.0-SNAPSHOT。config 均省略。保留已有 llm/model-deepseek、微信/飞书和 garden 配置。

| id | name | artifact | inject |
|---|---|---|---|
| sessions | dsh-java-session | plugins/session/target/session.jar | sessionProjections |
| session-projections | dsh-java-session-projection | plugins/session-projection/target/session-projection.jar | 无 |
| agents | dsh-java-agent | plugins/agent/target/agent.jar | 无 |
| prompt-context | dsh-java-context | plugins/context/target/context.jar | 无 |
| tools | dsh-java-tools | plugins/tools/target/tools.jar | systemPrompt |
| agent-loop | dsh-java-agent-loop | plugins/agent-loop/target/agent-loop.jar | agents,sessions,sessionProjections,systemPrompt,tools,toolScheduler,llmRuntime |
| garden（已有） | 保留已有 name | garden-demo.jar | 原 inject 加 agents,systemPrompt,tools |

不需要 presets、approval、fs-local、tool-fs、attachments、remote-gateway，也不需要新服务或数据库。工厂直接 followup 已准入的文本 UserMessage，不走附件/API-session 入口。新插件除已有业务 PG 写入均为进程内状态。应用 POM 增加 tools-contract provided；测试使用现有插件实现 test scope，生产 JAR 不打入底座/SDK 第二份类型。

## 环境与 smoke

继续使用 GARDEN_DB_URL、GARDEN_DB_USER、GARDEN_DB_PASSWORD、GARDEN_WEB、DEEPSEEK_API_KEY；GARDEN_PORT 选择未占用独立端口（原 48740 不动）。本轮不新增必须变量。书籍上传沿用 GARDEN_PYTHON/GARDEN_EXTRACTOR 默认规则。

在运维预先提供的隔离数据库中，用虚构本人/人物材料及虚构 TXT 书籍，GET /api/ranch-state 取得 revision 后 POST /api/ranch-analyze {revision,id:"self"} 或人物 id；轮询 state.job 到终态，检查 profile.runId、knowledgeStatus/knowledge、进度事件与可核查引用。POST /api/ranch-cancel {runId} 定向取消。不得使用真实聊天/运行数据库作测试夹具。

当前证据：合同源码核验；后续将追加确定性真实底座装配测试结果。此文不声称已通过真实模型 smoke。

## 确定性装配验证与构建缓存

本机共享 ~/.m2/repository 的旧 SNAPSHOT 缺少 Agent.whenIdle/scoped ToolRegistry；不得使用其成功编译作为新路径验证。当前 backend/.local/m2 是该缓存的独立 APFS clone，dev/dsh 的 JAR 已替换为 dsh-java 现有 target 制品（2026-09-22）；未修改共享缓存或底座源码。命令：`/Users/hou/.local/apache-maven-3.9.16/bin/mvn -o -Dmaven.repo.local=.local/m2 test`。其它角色可将该目录绝对路径作为 maven.repo.local；发布构建须采用一致的新合同/插件/host 制品。

ProfileRuntimeTest.Harness 使用真实 PluginManager + Agent/Session/Projection/Context/Tools/ModelRegistry/AgentLoop 插件，只替换 ModelRegistry.AdapterCall.stream。其 self/person 两步检索测试已通过；ProfileToolBoundaryTest 实际三步执行书籍检索、冲突上下文检索、再生成改变后的结果，并拒绝跨会话展开。RanchJobTest 覆盖任务身份、进度不增 revision、模型运行中取消不保存、旧 runId 不取消当前任务、重启中断。模型替身包含真实 block-end 完整块协议。所有材料均为测试中虚构文本，没有真实模型调用。

保存前校验为 `RanchData.validateProfile(result, readInput, actualReadKnowledge)`，只承认实际进入初始输入/工具观察的材料；两参兼容入口不允许书籍引用。补查观察明确给出新增白名单。删除书籍会移除引用它的当前及历史画像，禁用则标记过期。

追加预算验证：ProfileBudgetTest 已覆盖无启用书籍、无词项命中、模型连续要求工具时最多调用 6 次、1秒测试时限内模型停滞超时。运行预算耗尽给出明确失败文案，未产生可保存半截结果；生产时限仍为 110 秒。取消测试中底座会记录预期 aborted 异常；测试断言检查零保存，不将日志中存在取消异常视为失败。

超时全量验证曾暴露底座只逐块检测取消的边界：静默模型流可能不发下一块。因此应用在已有 llm/stream 作用域瀑布将 AgentAbortSignal.onAbort 接到 Publisher 取消，确保无块等待也会撤销订阅；没有修改底座或另造循环。

最终后端本地验证：`mvn -o -Dmaven.repo.local=.local/m2 -q package` 退出 0，51 个 Java 测试、0 failures、0 errors。已检查产物 garden-demo.jar 不包含 dev/dsh 或 io/agentscope 类，避免插件类加载器内复制共享合同/SDK。日志中的 SLF4J 未配置 provider 警告和取消用例的预期异常不代表失败。实际 PostgreSQL HTTP smoke、真实模型质量验收及 QA 独立取消竞态门槛由监督任务整合，本提交不冒充已完成这些环境验证。

运维 589728d 只读审查：run.py 的六个新插件及 garden inject 与本文一致，ModelRegistry 的键为 llmRuntime；未装配 SessionPersistence，保留内存会话。agent-loop 没有单独 readiness 服务，当前装配顺序在 garden 前；创建若遇到未注册 factory 会明确失败。未新增底座服务或启动预检 Agent。build.sh 需通过 MAVEN_OPTS 的 maven.repo.local 使用上述一致制品缓存。

## QA 订阅释放缺陷修正

QA 在 a59b21e 发现任务已 cancelled 但模型 provider 订阅没有收到取消，原 51 项测试未覆盖该断言。后端以原生 Flux.create（先登记 sink.onCancel，再通知已订阅）独立复现红测。根因是本机 Reactor 3.8.4 的 FluxTakeUntilOther：other.onError 只向下游发错误，不取消主订阅；other.onComplete 才执行 cancelMainAndComplete。应用作用域 abort Mono 因此改为 sink.success，Agent 的 aborted 信号及保存屏障保持不变。没有修改底座。

针对性验证 `mvn -o -Dmaven.repo.local=.local/m2 -q -Dtest=RanchJobTest,ProfileBudgetTest test` 退出 0，7/7：用户取消与超时均断言真实 provider 取消回调，不能仅以任务终态代替释放。QA 将保留独立原断言复测。
