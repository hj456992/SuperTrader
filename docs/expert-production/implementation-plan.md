# 管理员专家生产完整流程实施计划

> 执行方式：复用既有角色并行分工，使用 test-driven-development 与 executing-plans；统筹集成，监督角色独立验收。用户已明确要求统筹确保完整流程通过，本计划立即执行，不另增加审批往返。

**Goal:** 将已讨论的管理员十二步生产流程接入真实 Java 服务、模型与 PostgreSQL，完成独立验收后推送 GitHub。

**Architecture:** 保留独立 DSH 插件，新增生产模块，沿用15张ep_表及现有资料上传/MinerU入口。构建Master负责语义交流，程序负责版本化审核与阶段推进；PostgreSQL是唯一权威，Redis用于可重建通知/缓存与共享额度。最终用户L1/L2/L3仅产出经审核配置，不实施用户侧路由。

**Tech Stack:** Java17 / DSH ModelRegistry / Jackson / PostgreSQL JDBC42.7.10 / 原生HTTP / 原生JS / Redis RESP。

## 全局约束

- 工作区：`work/expert-production`；所有角色只改本工作区指定文件，不改正在运行的旧Demo或真实state，不自行提交/推送。
- 从构建创建开始存入DB；生成与人工状态分离；人工确认绑定已展示对象、当前版本、摘要与范围；原文/历史确认不可覆盖。
- 预学习按完整方法关联原文；概要优先审核，随后1–6位专业子专家、额外兜底、路由主专家、关键词和示例问答、最终清单。所有必要专家字段及完整提示词都可审阅。
- 无原因否决追问；依据真实原因调用模型新建修订；讨论不推进；条件/引用/局部认可不批准整体。上游改变只使受影响依赖过期。
- 程序短事务/幂等/任务租约控制跨节点；暂停不阻断解释和恢复；取消不可复活；迟到结果不能覆盖新目标。
- 共享文件目录作为已同意的共享卷适配，路径经配置，两节点使用同一目录；不新增对象存储服务。
- Redis/PG复用现有本地实例，建立专用开发数据库/测试schema，不接原爱聊业务表。测试采用受控模型验证状态机，再做真实模型链路验证；假响应不得被描述为模型能力通过。
- 保留旧上传/试聊能力；不新增用户侧功能，不嵌入prompt-optimizer，不部署到公网。

## 文件所有权

| 角色 | 独占文件 |
| --- | --- |
| 统筹 | ProductionDatabase.java、ProductionJobQueue.java、ProductionRedis.java及其测试；pom.xml、run.py、资源production-schema.sql、依赖/私密启动环境与文档集成 |
| 后端开发 | ProductionService.java、ProductionModel.java、ProductionPrompts.java、ProductionHttp.java及可拆分的Production*业务类（避开统筹三类）；修改LabHttp.java、ExpertLabPlugin.java、ModelCalls.java；业务单元测试 |
| 产品与Demo | src/main/resources/web/production.js、production.css及现有index.html/app.js/style.css必要入口接线；前端专项测试 |
| 监督与集成 | src/test/java/dev/ailiao/expert/ProductionAcceptanceTest.java、ProductionTestServer.java、tests/production-*及docs/expert-production/acceptance-*；只提实现缺陷，不改业务代码 |

## 固定Java协作接口

所有类位于 `dev.ailiao.expert`，与现有Json共用包；包内可见即可。

```java
final class ProductionDatabase {
  interface SqlWork<T> { T run(java.sql.Connection c) throws Exception; }
  ProductionDatabase(String url,String user,String password,String schema);
  static ProductionDatabase fromEnvironment(); // 无配置返回null，旧Demo仍可用
  void migrate() throws Exception;
  <T> T transaction(SqlWork<T> action) throws Exception;
  <T> T read(SqlWork<T> action) throws Exception;
  <T> T locked(String buildId,SqlWork<T> action) throws Exception;
  static int execute(java.sql.Connection c,String sql,Object... args) throws Exception;
  static java.util.List<com.fasterxml.jackson.databind.node.ObjectNode> query(java.sql.Connection c,String sql,Object... args) throws Exception;
  static com.fasterxml.jackson.databind.node.ObjectNode one(java.sql.Connection c,String sql,Object... args) throws Exception; // 未找到返回null
}
interface ProductionModel {
  com.fasterxml.jackson.databind.node.ObjectNode json(String purpose,com.fasterxml.jackson.databind.node.ObjectNode input,java.util.function.BooleanSupplier cancelled) throws Exception;
}
```

DB结果列按SQL标签输出snake_case；jsonb读为JSON节点，UUID为字符串。参数支持String、UUID、数字、boolean、JsonNode、null。String参数用Postgres unspecified类型推断，必要时SQL显式cast。未知build的locked抛IllegalArgumentException。服务用事务内SQL实现具体业务，不在事务内调模型。

```java
final class ProductionJobQueue {
  record Claim(com.fasterxml.jackson.databind.node.ObjectNode job,String owner,long epoch) {}
  ProductionJobQueue(ProductionDatabase db);
  String enqueue(java.sql.Connection c,String buildId,String kind,String targetRevisionId,com.fasterxml.jackson.databind.node.ObjectNode input,String dedupKey) throws Exception;
  Claim claim(String worker,java.time.Duration lease) throws Exception; //无任务返回null
  boolean renew(Claim claim,java.time.Duration lease) throws Exception;
  boolean live(Claim claim) throws Exception;
  <T> T withLease(Claim claim,ProductionDatabase.SqlWork<T> action) throws Exception;
  void complete(java.sql.Connection c,Claim claim,com.fasterxml.jackson.databind.node.ObjectNode checkpoint) throws Exception;
  void fail(Claim claim,String safeMessage) throws Exception;
  void defer(Claim claim,String safeMessage,java.time.Duration delay) throws Exception; //共享额度不可用时保留queued
  void cancelGeneration(java.sql.Connection c,String buildId) throws Exception;
}
final class ProductionRedis {
  static ProductionRedis fromEnvironment(); //无配置返回disabled实例
  boolean enabled();
  java.util.Optional<String> get(String key);
  void cache(String key,String value,java.time.Duration ttl);
  void notifyChanged(String buildId,long seq);
  boolean permit(String provider,String credentialAlias,int limit,java.time.Duration window) throws Exception; //配置了但不可用时抛异常，禁止绕过额度继续付费
}
```

队列提供数据库领取/租约，不启动线程；业务服务启动2个worker及续租调度，关闭服务中断本地工作但保留DB可接管记录。生成和interpret各在同构建最多一个有效任务，可并行，因此暂停消息可处理正在生成的任务。超时失租必须丢弃本地结果。所有新任务input记录必要输入版本与消息身份。

业务服务供HTTP和独立测试使用：

```java
final class ProductionService implements AutoCloseable {
  ProductionService(ProductionDatabase db,ProductionModel model,LabStore legacy,ProductionRedis redis,java.nio.file.Path sharedFiles);
  void start();
  com.fasterxml.jackson.databind.node.ObjectNode create(String buildId,com.fasterxml.jackson.databind.node.ObjectNode request) throws Exception;
  com.fasterxml.jackson.databind.node.ObjectNode snapshot(String buildId) throws Exception;
  com.fasterxml.jackson.databind.node.ObjectNode message(String buildId,com.fasterxml.jackson.databind.node.ObjectNode request) throws Exception;
  com.fasterxml.jackson.databind.node.ObjectNode list() throws Exception;
  com.fasterxml.jackson.databind.node.ObjectNode events(String buildId,long afterSeq,int limit) throws Exception;
  void close();
}
```

create/message由服务返回含status/httpStatus的处理结果供HTTP封装，参数/版本异常用后端定义ProductionException携带httpStatus/code。测试构建需使用src/test中的模型替身，不在真实服务加入fixture开关。具体模型purpose和输出JSON由后端明确写入同目录model-contract.md，并及时通知监督角色；不能让生产代码依赖测试模型返回固定阶段。

## HTTP与页面合同

保留api-contract.md既有创建/消息/事件/原文端点，在 `/api/expert-production/v1` 扩展全流程。

- `GET /builds` 返回 `{builds:[{buildId,name,phase,status,updatedAt}]}`。
- `PUT /builds/{uuid}` 用既有team/name/responsibility/documents请求。默认登录身份继承现有本地访问边界，服务端固定local-admin，不能接受客户端伪造actor。
- `GET /builds/{id}/snapshot` 返回既有字段，并包括 `currentArtifact`、`artifacts`、`jobs`、`clarifications`、`messages`。artifact为 `{id,kind,agentRole,logicalKey,sortNo,dependencyState,currentRevision,revisions}`；revision为 `{id,revisionNo,body,systemPrompt,contentSha256,generationStatus,reviewStatus,sources}`。currentArtifact可以为null；消息含服务端presentation：`{artifactId,revisionId,contentSha256,scope}`。
- `POST /builds/{id}/messages` 用原合同，原话content必保留。UI可额外传 `action:{type:'approve'|'reject'|'pause'|'resume'|'cancel'|'retry',scope:'summary.full'|'artifact.full'|'team.full'}` 表达真实按钮操作；服务仍验证CSRF、原话、状态、版本及展示身份，不能信任客户端approved标志。自由聊天必须走真实模型识别/改写，不能用少数正则代替。
- 专家 `artifact.full` 明确包含完整业务提示词，只有实际展示且必要澄清解决才能批准。名称/职责局部讨论不落整体批准。
- `GET /builds/{id}/events?afterSeq=...` 用PG补读，UI轮询即可，不因没有SSE而卡住交付。服务输出真实阶段/错误，不展示内部推理。
- 创建/消息202、快照200；幂等与旧版本409；资料未就绪422；数据库未配置503（明确错误，不能静默使用localStorage模拟）。
- 生产UI在实际服务 `#production`，点击生成团队进入这里。旧概念原型继续留docs，生产UI禁止固定书摘/固定模型回复/用localStorage保存审核事实。

## 任务与验收顺序

### 1 数据库/租约/Redis（统筹）
- [ ] 写真实PG集成测试：两连接竞争同一任务只有一方领取；失租旧epoch不能提交；原审批短事务回滚不半成功；Redis停机缓存回源且付费调用不放行。
- [ ] 先运行观察缺少实现的失败，再实现上述三类、15表资源迁移与环境连接；专用schema执行真实DDL/外键负例。
- [ ] 使用独立schema重复测试，证据写入验收报告，不连接真实业务表。

### 2 持久生产状态机和真实模型（后端）
- [ ] 先写业务测试覆盖全部阶段：prelearning→summary→specialists→fallback→router→production_config→final_review→completed；每阶段未批准不能推进。
- [ ] 建构建即保存记录和任务；从legacy选择版本按需导入PG原文/共享目录，不自动确认旧专家。
- [ ] 预学习逐批落库及checkpoint；保存覆盖信息/来源/完整方法；模型生成概要及1–6位分工计划；每阶段异步生成并先落库。
- [ ] 实现chat intent/rewrite、原文解释、原因澄清、修订、版本批准、依赖失效；全提示词/来源与配置可审阅；完成事务检查最终清单。
- [ ] 实现ProductionHttp接入LabHttp（原CSRF/Host校验内），ModelCalls有界适配、Plugin启动/关闭；自己的测试通过再交付统筹。

### 3 管理员页面（产品角色）
- [ ] 先写基于真实响应形状的界面行为测试，覆盖完整提示词显示、失败提示、刷新恢复与旧版本409。
- [ ] 新页面只读写真实API，展示全流程/当前待办/焦点/完整内容/来源/差异/未决问题；后续阶段可以到达。
- [ ] 接入当前资料选择和生成入口，恢复生产构建列表；未配置数据库清楚报错；所有用户文本以textContent呈现。

### 4 独立验收（监督角色）
- [ ] 先固定完整12步测试用例和失败条件；生产类可注入受控ProductionModel仅用于测试，测试替身必须放src/test，不进真实启动路径。
- [ ] 使用真实PG、HTTP、原文fixture走构建/预学习/否决追问/重新生成/完整提示词逐位确认/关键词问答确认/最终确认；重复/旧版本/条件同意/闲聊不能越权。
- [ ] 验证两服务实例、失败重试、重启恢复、取消/迟到结果、Redis异常、来源越界、已批准上游修改影响下游。
- [ ] 报告未覆盖项；不得把跳过测试、mock模型流程或单元通过写成真实模型能力验收。

### 5 集成和真实链路（统筹）
- [ ] 合并接口差异并复跑旧48项、新业务/PG/HTTP测试；修复独立验收发现，不放宽断言。
- [ ] 用隔离管理员测试团队跑真实模型全链路，模拟的管理员批准仅批准测试样本，不修改真实团队审批；真实书籍样本审核保持用户可审状态。
- [ ] CUA检查真实页面创建、交流、修订、各阶段推进与刷新，最终可完成测试团队；保留可供用户继续的入口。
- [ ] 同步既有架构相关章节/实际状态与SQL变动、测试结果，主分支集成并推送，核验远端。

## 集成中的实际证据（尚未最终验收）

- PostgreSQL数据库/租约7项、Redis2项与独立HTTP15项共24项最近一次全部通过，0跳过；18项前端组件测试通过。
- 真实DSH宿主启动发现JDBC SPI隔离问题，连接改为显式PostgreSQL驱动后接口正常。
- 真实旧资料创建发现parser元数据缺失；已补unknown/legacy兼容，未修改旧state。
- 真实整书预学习曾因旧Demo字段限制失败；生产专用格式校验已补代表性回归，需重启同构建重试。
- 浏览器工具对48763访问返回明确权限拒绝，待用户恢复授权；不得换浏览器或工具绕过。未将组件测试当浏览器验收。
- Redis服务实际接入共享额度，缓存/通知helper尚未接入业务；页面从PG事件补读。
- 独立验收曾出现一次snapshot500，后续通过尚不足以证明根因已修复，继续保留诊断与稳定复验记录。
