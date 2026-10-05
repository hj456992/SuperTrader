# 管理员专家团队生产

本目录保存管理员十二步生产流程的实现合同与验收证据。实际页面在专家服务的 `#production`，点击“生成专家团队”后进入真实数据库构建；旧 `prototype/index.html` 仅保留为早期设计演示。

当前代码已实现完整阶段。105项Java、18项前端组件测试通过；原创样本分别通过显式操作确认与纯自然语言确认的真实模型/HTTP全流程，每队9次批准后完成。312页整书完成11批预学习、覆盖313个片段，概要保持待管理员确认。浏览器访问被明确拒绝，页面端到端尚未验收；数据库偶发建连失败仍在保留诊断，不能把测试通过等同于全部验收完成。证据见[真实模型集成](integration-results.md)、[独立验收](acceptance-results.md)与[前端验证](frontend-verification.md)。

## 生产过程

资料预学习和原文关联 → 整书概要 → 逐位专业专家 → 额外兜底专家 → 路由主专家 → 关键词规则 → 书中示例问答 → 完整团队清单。专业分工按内容生成1–6位，不固定六位。

所有草稿从构建创建开始入库。生成成功只表示可审核；每位专家展示职责、模型、工具、特色能力、边界和完整业务提示词，由管理员确认当前版本后推进。否决无原因先追问；澄清影响定义时生成新稿再审。原文逐字摘录由程序定位，引用与审批均绑定不可变版本。团队最终完成只表示管理员生产结束，不自动启用、部署或批准用户侧试聊。

L1关键词、L2问答/余弦严格大于0.90、多个专家命中交L3等作为待审生产配置保存；本轮未执行最终用户侧路由、向量调用或入向量索引。

## 运行

在仓库 `expert-agent-demo` 中构建，再使用已有DSH宿主启动：

```sh
zsh build.sh
python3 run.py --check
python3 run.py
```

沿用原来的 `DSH_JAVA_HOME`、`DEEPSEEK_API_KEY`、`EXPERT_LAB_PORT`、`EXPERT_LAB_DATA` 配置。管理员生产另需：

| 环境变量 | 用途 |
| --- | --- |
| `EXPERT_DB_URL` | PostgreSQL JDBC URL |
| `EXPERT_DB_USER`、`EXPERT_DB_PASSWORD` | 仅启动环境提供，不写入仓库或页面 |
| `EXPERT_DB_SCHEMA` | 专用schema，默认 `expert_production`；首次启动事务建表 |
| `EXPERT_SHARED_DIR` | 所选原PDF的共享目录，多实例必须指向相同存储 |
| `EXPERT_REDIS_URL`、可选 `EXPERT_REDIS_PASSWORD` | 共享供应商额度；配置后故障保留queued并停止新付费调用 |
| `EXPERT_MODEL_CALL_LIMIT` | 共享分钟调用额度，默认120 |
| `EXPERT_PRODUCTION_MAX_TOKENS` | 生产模型单次输出预算，默认7000；仅改变生产调用 |
| `EXPERT_PRODUCTION_MAX_CHARACTERS` | 生产输出字符上限，默认40000；超限拒绝保存不完整结果 |

未配置数据库时，旧资料库仍可用，生产接口返回明确503。Redis未配置仅适用于单机开发；集群应配置共享额度，不能把关闭Redis当作故障时自动绕过限额。

PostgreSQL是构建、审核、消息、任务与事件的权威。页面从PG事件补读；Redis当前业务接入额度控制，缓存和Pub/Sub辅助代码尚未接入服务。旧上传/MinerU路径保持原有实现，不宣称其本地任务已经具备跨节点接管。服务继续使用现有本地访问边界与local-admin身份，不是新增公网登录系统或生产集群部署。

数据库详情见[存储职责与15表理由](storage-design.md)及[DDL](schema.sql)。应用实际迁移资源为 `expert-agent-demo/src/main/resources/production-schema.sql`，两者同步。首次建表只在专用空schema执行；不是任意历史生产版本的自动升级工具。

## 验证入口

- [实施分工与门槛](implementation-plan.md)：范围、接口、失败条件与证据分层。
- [生产模型合同](model-contract.md)：预学习、生成、意图识别/改写的类型、来源和安全失败信息。
- [独立验收报告](acceptance-results.md)：受控模型+真实PG/HTTP、幂等、澄清、依赖与租约故障。
- [前端验证](frontend-verification.md)：组件检查与真实浏览器证据分别记录。
- [真实模型流程脚本](checks/real-model-flow.py)：创建明确标注的隔离测试团队；只自动批准该新测试构建，私密响应由`--output`保存，不进入仓库。
- [首次失败基线](flow-test-report.md)：实施前生产接口404、原型止于概要，保留为历史证据。

公开仓库不含凭据、运行state、数据库备份、上传书籍、私密OCR或模型原始响应。
