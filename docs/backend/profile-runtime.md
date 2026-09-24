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
| agent-loop | dsh-java-agent-loop | plugins/agent-loop/target/agent-loop.jar | agents,sessions,sessionProjections,systemPrompt,tools,toolScheduler,llm |
| garden（已有） | 保留已有 name | garden-demo.jar | 原 inject 加 agents,systemPrompt,tools |

不需要 presets、approval、fs-local、tool-fs、attachments、remote-gateway，也不需要新服务或数据库。工厂直接 followup 已准入的文本 UserMessage，不走附件/API-session 入口。新插件除已有业务 PG 写入均为进程内状态。应用 POM 增加 tools-contract provided；测试使用现有插件实现 test scope，生产 JAR 不打入底座/SDK 第二份类型。

## 环境与 smoke

继续使用 GARDEN_DB_URL、GARDEN_DB_USER、GARDEN_DB_PASSWORD、GARDEN_WEB、DEEPSEEK_API_KEY；GARDEN_PORT 选择未占用独立端口（原 48740 不动）。本轮不新增必须变量。书籍上传沿用 GARDEN_PYTHON/GARDEN_EXTRACTOR 默认规则。

在运维预先提供的隔离数据库中，用虚构本人/人物材料及虚构 TXT 书籍，GET /api/ranch-state 取得 revision 后 POST /api/ranch-analyze {revision,id:"self"} 或人物 id；轮询 state.job 到终态，检查 profile.runId、knowledgeStatus/knowledge、进度事件与可核查引用。POST /api/ranch-cancel {runId} 定向取消。不得使用真实聊天/运行数据库作测试夹具。

当前证据：合同源码核验；后续将追加确定性真实底座装配测试结果。此文不声称已通过真实模型 smoke。
