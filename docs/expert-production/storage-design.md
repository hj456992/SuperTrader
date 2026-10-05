# 专家生产的集群存储（设计来源2026-09-28，实现验收进行中）

2026-10-05实施更新：用户随后明确要求统筹确保完整流程通过。当前真实Java服务已按下述15张表在独立开发数据库落地，SQL资源与[仓库配套SQL](schema.sql)同步；审核、来源、短事务和任务接管已有真实PG测试。业务使用Redis共享调用额度及PG事件补读；缓存/通知辅助类尚未接入业务。共享文件目录由`EXPERT_SHARED_DIR`配置，不额外部署对象存储。旧Demo资料在创建生产构建时按所选不可变版本导入PG，原有state与专家审批不迁移覆盖。后续段落保留原设计背景，具体已实现/未实现以[实施计划](implementation-plan.md)及[独立验收](acceptance-results.md)为准；两支原创样本已分别通过显式动作与纯自然语言确认的真实模型/HTTP完整流程，各9次批准；整书11批、313片段预学习及概要生成已完成，概要零批准、待管理员审核。105项Java与18项前端通过；浏览器明确拒绝访问、偶发数据库建连异常仍保留待查，不能宣称全部验收完成。

本轮实施前先交付[SQL](schema.sql)、[概要审核接口合同](api-contract.md)和[状态流转](summary-review-state.md)。[原型验收记录](verification.md)仅描述当时的设计检查；当前真实十二步实现与检查见上方实施更新。逻辑表仍为15张。

本节响应管理员侧专家生产的集群要求。用户已明确允许在必要位置引入 Redis；下面解释职责划分和表结构；核心DDL已在隔离开发库执行，复用已有Redis。未覆盖迁移旧state.json，也未实施最终用户的L1/L2/L3执行。

#### 先按数据用途选择存储

建议沿用爱聊已有 PostgreSQL 技术栈，专家生产使用独立 ep_ 表；不继续把全部团队、聊天和书籍放在一行 JSON 中。Redis 保存可重建的缓存、通知及限流状态；原 PDF、OCR ZIP、页面图片等放在集群共享的文件存储，数据库保存对象位置和校验摘要。共享存储建议使用现有对象存储（例如已有 OSS/S3 兼容服务），也可接已有共享卷；具体服务尚待确定，本次不自行部署新服务。本地磁盘只作为可丢弃的临时工作目录。

| 存储 | 保存什么 | 为什么 |
| --- | --- | --- |
| PostgreSQL | 全部草稿、原文片段、版本、消息、反馈、澄清、人工确认、任务、事件 | 事务、唯一约束、外键和并发校验支撑可恢复的真实状态 |
| Redis | 不可变片段/成果版本缓存、跨节点更新通知、供应商调用限流 | 多实例共享热点数据、及时更新页面并控制集群调用速率 |
| 共享文件存储 | PDF、OCR ZIP/布局文件、图片 | 任一工作节点都能继续解析或查看原件，避免文件只存在上传节点 |

所有成果从创建时入库；机器生成完成与管理员审核完成分别记录。生成内容较大时按完整学习单元逐项保存，生成中也保留任务/单元记录，不等整本书或全部专家完成后才首次写库。

#### 核心关系和字段约定

一份资料有多个固定版本，每个版本有多个原文片段。一支团队有多个构建版本；每次构建绑定明确的资料版本，并保存多个学习/审阅成果。每个成果有多个内容修订；消息、审核、澄清和依赖关系指向具体修订。集群任务也绑定它处理的目标修订。

下面是逻辑表结构说明，可执行SQL单独保存在配套资源。实体主键默认 uuid；时间为 timestamptz；编号、计数、并发版本为 bigint/int；状态及标识为 text 并配 CHECK/枚举约束；可变结构正文用 jsonb。实体表包含 created_at、created_by，允许更新的表另有 updated_at；管理员身份关联项目统一身份体系，不另造一套账户系统。外键按构建/团队范围校验，不允许跨团队引用；具体复合外键与迁移顺序在实现设计中落实。

| 表 | 关键字段 | 关系/约束与设计原因 |
| --- | --- | --- |
| ep_document | id, title:text, created_by:text | 资料的稳定身份，上传新版不制造另一份逻辑资料 |
| ep_document_version | id, document_id:uuid, version_no:int, original_object_key:text, original_sha256:text, ocr_object_key:text, parser_config:jsonb, parse_status:text, page_count:int | UNIQUE(document_id,version_no)；版本固定解析配置及原件/OCR位置；对象先核对完整性后才标解析就绪，不保存临时签名URL作为永久位置 |
| ep_source_chunk | id, document_version_id:uuid, chapter_path:text, page_no:int, chunk_no:int, text:text, text_sha256:text, locator:jsonb, quality_flags:jsonb | UNIQUE(document_version_id,page_no,chunk_no)；保留物理页、OCR块位置及疑点；入库原文不可被管理员反馈静默覆盖 |
| ep_team | id, name:text, completed_build_id:uuid, lock_version:bigint | 稳定团队身份；completed_build_id指向已完成生产快照，不等于发布到用户侧 |
| ep_build | id, team_id:uuid, build_no:int, phase:text, status:text, current_artifact_id:uuid, focus_revision_id:uuid, waiting_question_message_id:uuid, manifest_revision_id:uuid, lock_version:bigint, message_seq:bigint, event_seq:bigint | UNIQUE(team_id,build_no)；待办位置与聊天焦点分开；完成时冻结清单，修改已完成团队另开构建版本；进度序号不冒充内容版本 |
| ep_build_document | build_id:uuid, document_id:uuid, document_version_id:uuid | PK(build_id,document_id)；复合外键保证版本属于对应资料，固定本次构建选用哪版书 |
| ep_artifact | id, build_id:uuid, kind:text, agent_role:text, logical_key:text, sort_no:int, current_revision_id:uuid, dependency_state:text | UNIQUE(build_id,logical_key)；区分成果稳定身份与当前修订；代理角色区分specialist/fallback/router；每个构建最多一个fallback和router，由条件唯一索引约束 |
| ep_artifact_revision | id, artifact_id:uuid, revision_no:int, based_on_revision_id:uuid, schema_version:int, body:jsonb, system_prompt:text, content_sha256:text, generation_status:text, review_status:text, generator_config:jsonb, change_reason:text, sealed_at:timestamptz | UNIQUE(artifact_id,revision_no)；送审后正文、提示词及摘要封存，修改另建修订；审核状态变更仍可发生且必须有事件/审核记录 |
| ep_revision_source | revision_id:uuid, chunk_id:uuid, start_offset:int, end_offset:int, purpose:text | PK(revision_id,chunk_id,start_offset,end_offset)；一个完整方法可关联多页来源；偏移按统一Unicode码点计数并核验范围 |
| ep_revision_dependency | revision_id:uuid, depends_on_revision_id:uuid, relation:text | PK(revision_id,depends_on_revision_id,relation)；记录专家采用哪些方法、问答指向哪位专家修订、最终清单包含哪些修订；同构建内校验、禁止自引用及循环依赖 |
| ep_message | id, build_id:uuid, seq:bigint, role:text, actor_id:text, client_request_id:text, content:text, reply_to_message_id:uuid, context_snapshot:jsonb, interpretation:jsonb, processing_status:text, result:jsonb | UNIQUE(build_id,seq)、UNIQUE(build_id,client_request_id)；保留原话、展示版本/摘要、结构化意图及改写，重试返回同一处理结果；seq在构建行锁内分配 |
| ep_review | id, build_id:uuid, revision_id:uuid, scope:text, decision:text, reason:text, actor_id:text, message_id:uuid, action_no:int, presented_message_id:uuid, reviewed_sha256:text, decided_at:timestamptz | UNIQUE(message_id,action_no)；记录确认了哪个范围、哪个修订及哪次展示；同一句多动作不重复执行；拒绝但无原因允许reason为空并等待澄清 |
| ep_clarification | id, build_id:uuid, artifact_id:uuid, revision_id:uuid, kind:text, question:text, status:text, answer:text, opened_message_id:uuid, resolved_message_id:uuid, resolution_revision_id:uuid | 跟踪澄清问题，不能仅靠聊天摘要猜测是否已解决；修改时逐项记录已解决/仍适用，原修订和回答历史保持 |
| ep_job | id, build_id:uuid?, document_version_id:uuid?, kind:text, target_revision_id:uuid?, expected_current_revision_id:uuid?, dedup_key:text, status:text, phase:text, input:jsonb, checkpoint:jsonb, external_refs:jsonb, lease_owner:text, lease_until:timestamptz, lease_epoch:bigint, attempt:int, next_run_at:timestamptz, cancel_requested:boolean, error:jsonb | UNIQUE(dedup_key)；OCR可只有资料目标，构建任务绑定build和目标；约束至少一个有效目标。保存MinerU分卷任务编号、输入版本和可恢复步骤；lease_epoch阻止旧工作者提交 |
| ep_event | id:uuid, build_id:uuid, seq:bigint, type:text, payload:jsonb, published_at:timestamptz?, publish_attempts:int, next_publish_at:timestamptz | UNIQUE(build_id,seq)；业务事件与状态同事务保存，同时作为待通知记录；发布失败重试，页面按构建内序号补读。seq在构建行锁内分配，避免全局序列先分配后提交导致漏读 |

共15张逻辑表，不要求15个独立服务。首次建表、关键外键及事务回滚已有真实PG测试；容量、吞吐和生产高可用未进行验收。

#### 专家、概要、方法、关键词和问答放哪里

统一审核机制适用于多类成果，因此用 ep_artifact + ep_artifact_revision 统一身份、版本和确认；正文按 kind 分别做必填字段及JSON Schema校验。不能接受任意JSON并绕过字段约束。状态、版本、关联、审核都在独立列/关系表，不重新退化为一个state大文档。

| kind | body 必需内容 | 额外规则 |
| --- | --- | --- |
| book_summary | title, summary, outline, limitations | 关联本次资料版本及所依据的学习成果；章节覆盖和OCR疑点可追溯 |
| learning_unit | title, description, when, steps, limits, origin | 由完整方法/概念组织；origin区分book/admin_custom，书中内容必须有来源 |
| agent | name, description, summary, duty, model, tools, capabilities, boundaries, when | system_prompt独立text保存实际业务提示词；专业/兜底/主专家共享结构；记录提示模板及工具协议版本，避免运行时换模板导致审核失效 |
| keyword_rule | keywords, match_options, description | 用revision_dependency的routes_to关系绑定专家具体修订，不能只写专家名称 |
| qa_example | question, answer, origin, explanation | 书中示例改编保留来源；routes_to绑定专家修订；自创示例不能伪装成书中原例 |
| team_manifest | summary, workflow, settings | 依赖关系列出团队采用的确切成果修订；不自引用；管理员最终审核这个版本，团队完成时固定指针 |

generator_config保存生成内容所用供应商、模型、生成提示模板版本等，不保存密钥；agent.body.model保存该专家将来使用的模型，两者不可混淆。书籍原件和正文版本不因修改专家而变化。主专家与兜底专家只是agent的不同角色，无需复制一套审批表。

阿里云百炼用于后续向量生成，余弦相似度与>0.90阈值作为配置保存；当前先设计管理员生产的问答和确认。最终用户向量索引及L1/L2/L3实际执行另阶段设计，不把Redis默认当成向量库，也不在本轮新增独立向量数据库。

#### 状态、确认与版本

机器处理状态如queued/running/succeeded/failed/cancelled，与人工审核状态pending/needs_reason/changes_requested/completed分开。人工成果的completed只能由合法管理员确认产生。名称或职责的局部确认记录scope，不能自动完成整位专家；必要澄清解决、完整提示词已展示且管理员确认后，专家修订才完成。

送审前即有记录，生成中可保存部分草稿；完整送审时封存内容和hash。此后改稿新增revision，不覆盖旧内容。更新上游时保留旧审核事件，将依赖旧修订的当前成果标记dependency_state=stale，列出需要复核的内容；无关成果保持。同内容的错误修复也需依赖验证，不可只比较提示词文本就继承所有确认。

完整业务提示词与管理员看到的展示快照均记录摘要及版本；引用的动态原文和任务输入仍单独保存，不能把运行时任意用户输入当作已审核提示词的一部分。模型的意图判断只能提出操作，最终状态变更由程序核对原始消息、展示对象、权限和当前阶段。

#### 集群中如何确认一次，且确认正确的版本

假设节点A处理“第二位专家通过”，节点B同时修改这位专家。建议两个操作都先锁同一构建行，再检查当前成果指针及预期修订。成功的一方在同一事务里写审核/修订、更新状态和构建位置、追加事件。后一方重新读取状态后发现版本已变，不能对新稿套用旧确认。相同message_id/action_no重复请求直接返回已保存结果。

锁仅覆盖短数据库事务，不覆盖模型生成或管理员等待。模型请求先记录任务与输入修订，事务提交后再外调；返回时重新检查目标修订、任务租约代次、取消状态和必要依赖，只有仍有效才挂接新稿。

团队完成同样是事务：检查最终清单及其必要成员全部已确认、无必要未决澄清、依赖未过期；写build.status=completed及team.completed_build_id。完成只代表管理员侧生产，不代表用户侧部署或专业能力评测通过。

#### 工作节点接力与外部调用

待处理任务保存在ep_job，多节点以短事务领取任务、写入lease_owner/lease_until并递增lease_epoch；领取可采用SELECT FOR UPDATE SKIP LOCKED。提交领取事务后才执行外部调用，定期续租。其他节点可以接管过期任务；旧节点恢复后，其旧epoch无法提交或续租。取消在数据库留下标记，工作节点在关键步骤检查。

PostgreSQL官方明确SKIP LOCKED可用于多个消费者访问队列表；它不用于管理员审阅时读取真实状态：[SELECT文档](https://www.postgresql.org/docs/current/sql-select.html)。

消息或任务可能被重试，目标是业务结果只提交一次，不承诺外部模型只收费一次。MinerU已提交的分卷先查已保存batch ID；如果请求已发出但响应/编号未落库，记录结果不确定，先核查供应商状态，不盲目重新上传。checkpoint/external_refs按步骤保存，密钥和临时带签名URL不得出现在普通日志或事件载荷。

#### Redis在哪些地方使用

| 用途 | 建议键/频道 | 丢失或不可用时 |
| --- | --- | --- |
| 已封存版本与片段缓存 | ep:revision:{revisionId}:{hash}、ep:chunk:{chunkId}:{hash} | TTL可配置；数据库回源。缓存正文与可变审核状态分离，审批始终查数据库 |
| 跨节点页面更新和任务唤醒 | ep:build:{buildId}:changed，内容只含event ID/seq | 数据先提交，再由事件发布器通知；丢通知后按ep_event补读，后台仍轮询待办任务 |
| 供应商调用速率限制 | ep:quota:{provider}:{credentialAlias}:{window} | 多节点共享额度；Redis异常时暂停启动新的付费调用，已记录的审核和查看仍可进行；具体额度另行配置 |

Redis Pub/Sub是至多一次投递，离线订阅者可能漏消息，因此通知不能作为唯一任务队列或审核依据：[Redis官方文档](https://redis.io/docs/latest/develop/pubsub/)。ep_event先落库的事务记录解决数据库已提交但通知未发出的窗口，重复发布按event ID/seq去重。

本版不另加Redis分布式锁：短事务、条件更新和任务租约已经明确业务互斥及接管规则，避免把锁过期当成审批正确性的保障。Redis高可用拓扑按部署环境决定，不能把应用多实例与必须Redis Cluster分片等同；数据库和文件存储也需要相应高可用及备份。

#### 必要索引与验收

重点索引：ep_source_chunk(document_version_id,page_no,chunk_no)；ep_artifact(build_id,kind,sort_no)；ep_artifact_revision(artifact_id,revision_no DESC)；ep_revision_dependency(depends_on_revision_id)；ep_message(build_id,seq)；ep_review(revision_id,scope,decided_at)；ep_clarification(build_id,status)；ep_job(status,next_run_at,lease_until)；ep_event(build_id,seq)及未发布事件的部分索引。唯一约束与普通索引避免重复创建。

验收至少覆盖：两实例同时处理确认与改稿；重复消息重放；任务执行节点宕机及过期节点恢复；数据库提交后通知发布失败；Redis停机的缓存回源和通知补读；OCR分卷跨节点接续；所有确认及依赖合法才完成团队。暂不对吞吐量、恢复时限和外部调用次数作未经测试的承诺。
