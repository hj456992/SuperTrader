# 爱聊 · 专家实验室

独立的 DSH Java 插件：通过 MinerU 解析 PDF、保留原件与资料版本，再由管理员逐步审阅书籍概要、专家完整提示词和团队配置。

2026-10-06：管理员生产流程已通过真实模型、PostgreSQL/Redis与本地浏览器完整验收。最终未过滤Java回归136项、前端18项，均无失败、错误或跳过；测试团队9次批准后completed，重启与刷新后保留。312页整书11批学习成果保持不变，修订概要覆盖四篇及附录、16处引文可读取，仍待管理员审核、零批准。

[返回项目首页](../README.md) · [生产流程与合同](../docs/expert-production/README.md) · [前端验证](../docs/expert-production/frontend-verification.md)

## 使用流程

1. **导入资料**：上传 PDF，等待 MinerU 云解析。给已有资料上传新版本会保留旧版及原件；资料保留物理页码和片段来源。
2. **选择输入**：填写团队名称、职责，勾选资料并为每份资料指定一个版本。
3. **开始生产**：点击“生成专家团队”，创建数据库构建并进入 `#production/{buildId}`。先分批预学习并保存成果，再生成整书概要。
4. **审核概要与专家**：概要确认后，依次审阅 1–6 位专业专家、额外兜底专家和路由主专家。每位专家展示职责、模型、工具、特色能力、边界、来源与完整提示词。
5. **反馈并修订**：可提问、查看原文、否决或要求修改。否决无原因时先追问；必要澄清或修改产生新稿，须重新审阅。确认绑定实际展示的当前版本，不能只同意名称或职责就视为完整提示词通过。
6. **确认配置与团队**：审阅关键词、书中示例问答及最终团队清单。全部必要成果确认后才记录团队完成。

所有草稿、消息、修订和审核从一开始保存到 PostgreSQL。生成状态与人工审核状态分别记录；页面支持历史查看、任务暂停／恢复／取消、失败重试和重新打开构建。

团队完成表示管理员生产结束。当前生成 L1/L2/L3 所需配置，不执行最终用户侧路由、向量调用或向量索引，不自动部署或启用到主应用。

旧专家版本的试聊与启用接口保留兼容：旧“启用”只设置本 Demo 的版本指针。旧试聊与新生产是不同流程，不能把旧试聊成功当作新团队生产验收。

## 运行专家实验室

需要 Java 17、Maven、Python 3 + `pypdf`，以及已构建且合同兼容的 dsh-java 宿主和插件。使用主应用同一类底座，但启动独立进程；本仓库不附带底座源码、制品或凭据。

在仓库根目录先进入本目录：

```sh
cd expert-agent-demo

# 先设置下表中的依赖路径、模型及数据库环境变量
zsh build.sh
python3 run.py --check
python3 run.py
```

默认打开 [http://127.0.0.1:48760/](http://127.0.0.1:48760/)，生产页为 [#production](http://127.0.0.1:48760/#production)。端口可通过 `EXPERT_LAB_PORT` 更改；停止使用运行终端的 `Ctrl+C`。首次克隆没有开发机上的资料和专家，可自行上传仓库附带的[原创三页演示资料](evidence/演示资料-职场沟通方法.pdf)。

`build.sh` 使用 Maven 离线构建，需要预先准备依赖缓存。默认脚本含开发机路径，换电脑请显式设置下表中的路径。

| 配置 | 用途 |
| --- | --- |
| `DSH_JAVA_HOME` | 已构建的 dsh-java 项目根目录 |
| `MAVEN_BIN` / `JAVA_BIN` | Maven 和 Java 可执行文件路径 |
| `EXPERT_MAVEN_REPO` | 与宿主匹配的 Maven 合同与依赖缓存 |
| `EXPERT_PYTHON` | 已安装 `pypdf` 的 Python 可执行文件 |
| `DEEPSEEK_API_KEY` | 仅通过进程环境传入的模型凭据 |
| `EXPERT_PROVIDER` / `EXPERT_MODEL` | provider／模型标识；启动器默认值为 `deepseek`／`deepseek-v4-flash`，可用性以自身服务配置为准 |
| `EXPERT_LAB_PORT` / `EXPERT_LAB_DATA` | 默认端口 `48760`；旧资料与导入数据目录默认 `.local/data` |
| `MINERU_API_TOKEN_FILE` | 私密 Token 文件，默认 `~/.config/mineru/token`；建议文件权限 `600`、目录 `700` |
| `MINERU_API_TOKEN` | 可选环境变量，优先于 Token 文件 |

启动器需要 `app-boot/target/dsh-java.jar` 和以下底座插件制品：model-registry、model-deepseek、session、session-projection、agent、context、tools、agent-loop。具体路径及注入见 [run.py](run.py)。本插件产物为 `target/expert-lab.jar`。

### 管理员生产配置

| 配置 | 用途 |
| --- | --- |
| `EXPERT_DB_URL` | 已存在的 PostgreSQL 数据库 JDBC URL |
| `EXPERT_DB_USER` / `EXPERT_DB_PASSWORD` | 数据库凭据，仅放进程环境 |
| `EXPERT_DB_SCHEMA` | 专用 schema，默认 `expert_production`；首次启动创建表 |
| `EXPERT_SHARED_DIR` | 生产构建引用的原 PDF 目录，默认数据目录下的 `shared`；多实例必须访问同一存储 |
| `EXPERT_REDIS_URL` / 可选 `EXPERT_REDIS_PASSWORD` | 共享调用额度；配置后故障会保留排队任务并停止新的付费调用 |
| `EXPERT_MODEL_CALL_LIMIT` | 共享分钟调用额度，默认 `120` |
| `EXPERT_PRODUCTION_MAX_TOKENS` | 生产模型单次输出预算，默认 `7000` |
| `EXPERT_PRODUCTION_MAX_CHARACTERS` | 生产输出字符上限，默认 `40000`；超限拒绝保存不完整结果 |

未配置 `EXPERT_DB_URL` 时旧资料库仍可用，但新生产接口返回 `503`。Redis 未配置仅适用于单机开发；不应在共享限额故障时通过关闭配置绕过限额。首次建表针对专用空 schema，不是任意历史版本的自动迁移工具。

`--check` 检查文件、空闲端口与配置格式，不验证数据库、Redis 或模型连通性。**配置数据库时，预检可能创建 `EXPERT_SHARED_DIR` 目录。** 真正启动后才执行数据库初始化并启动工作线程。

## 存储与恢复边界

| 数据／任务 | 保存与恢复方式 |
| --- | --- |
| 新生产构建、草稿、版本、审核、澄清、消息、事件 | PostgreSQL 为权威来源，页面通过快照与事件读取 |
| 新生产后台任务 | PostgreSQL 持久队列、租约与代次校验，阻止过期工作线程提交 |
| 模型共享额度 | Redis 已接入；缓存和 Pub/Sub 辅助代码尚未用于业务服务 |
| 生产引用的 PDF | 复制到配置的共享目录，构建保存资料版本与来源身份 |
| 旧资料库、旧专家、旧试聊 | 本地 JSON／PDF 与原有运行机制，不宣称跨节点接管 |
| MinerU 导入 | 本地私密导入记录保存原件、云任务编号和进度；恢复查询原编号并按 importId 幂等入库 |

原文引用由程序核对所选资料，精确引文唯一匹配后转换为 Unicode 码点区间；引用可定位不代表模型解释必然正确。来源、任务和审核规则见[生产模型合同](../docs/expert-production/model-contract.md)与[存储说明](../docs/expert-production/storage-design.md)。

服务只监听 loopback，校验 Host、Origin 与写请求 `X-Lab-Token`，沿用本地管理员身份。当前没有新增公网登录、多租户隔离或正式集群部署。

## PDF 云解析

上传会将原 PDF 发送到 MinerU，生成和聊天会将选中材料及必要上下文发送到配置的模型服务，并可能消耗额度。凭据、原件和 OCR 结果不随源码公开。

- 上传上限为 20 MiB。本地检查最多 600 页；此前真实服务对超过 200 页的任务返回限制，因此页面提供显式确认后的每份最多 200 页拆分、顺序解析与合并。
- 原始整书始终保留，引用页码仍对应原件。分卷 ZIP 累计最多 200 MiB，JSON／Markdown 读取累计最多 100 MiB，超限不入库。
- 连接 15 秒、API 30 秒、上传／下载各 5 分钟；导入观察窗口 30 分钟，连续查询失败 3 次暂停。取消本地处理不能保证云端停止。
- 云解析结果按页核对后入库，图表、复杂版式、OCR 疑点和跨页方法仍需人工复核。

历史真实验证曾完成一份 312 页资料的两卷解析与合并；这是数据链路和页码验证，不是逐字准确性或专家能力证明。书籍、OCR、截图和原始响应为私密证据，公开仓库只保留实现、测试及原创虚构 PDF。

## 验证

| 验证层级 | 当前结果与边界 |
| --- | --- |
| Java 与前端组件 | 最终未过滤Java 136项、前端18项通过，均无失败、错误或跳过；受测源码哈希一致 |
| 原创三页资料：显式审核请求 | 真实 DSH 宿主、模型、HTTP 和 PostgreSQL 全流程完成，9 次审批，最终 `completed`，无未决澄清 |
| 原创三页资料：自然语言审批 | 通过真实意图模型识别审批，全流程完成，9 次审批，最终 `completed`，无未决澄清 |
| 312 页整书 | 313片段、11批预学习保持；概要v2覆盖四篇与附录，16处引文经原文接口核验，pending、零批准 |
| 真实浏览器 | 统筹通过真实CUA完成创建、讨论、无原因否决追问、修订、逐专家完整提示词确认、关键词/问答/团队清单审核；重启与刷新后仍completed |

两个真实模型测试团队均包含 3 位专业专家、额外兜底与路由主专家，完整提示词和最终团队清单已纳入审批。自动审批只用于明确标注的独立测试团队；该样本通过不代表长书专业能力或所有领域的质量保证。

临时建连故障已增加事务前有界恢复，持续不可用返回503；不重放SQL或提交、不降低TLS策略。恢复环境后宿主与虚拟机各200次SSLRequest均正常；这些采样不证明历史网络超时永久消失，也不倒推旧snapshot500的具体原因。详见集成报告。

以下命令在 `expert-agent-demo/` 执行。Java 数据库测试需配置独立测试数据库的 `EXPERT_DB_URL`、`EXPERT_DB_USER`、`EXPERT_DB_PASSWORD`，并授予创建／删除测试 schema 的权限；测试使用随机独占 schema，不应连接正式业务库。

```sh
# 完整 Java 测试并打包；生产数据库测试需上述环境
zsh build.sh

# 专项独立 HTTP + PostgreSQL + 受控模型验收
sh tests/production-acceptance.sh

# 前端协议／组件行为检查，无真实浏览器或供应商调用
node --test src/test/frontend/production-ui.test.cjs
node --check src/main/resources/web/production-api.js
node --check src/main/resources/web/production.js
node --check src/main/resources/web/app.js
```

[数据库验收脚本](tests/production-acceptance.sh)要求显式数据库环境，缺失时直接失败。完整 Maven 测试中部分数据库用例在环境缺失时会跳过，因此须检查失败及跳过计数，不能只看构建成功。

最新用例数、修复记录及尚未定位的问题见[独立验收](../docs/expert-production/acceptance-results.md)。[前端报告](../docs/expert-production/frontend-verification.md)单独记录组件测试和真实浏览器状态。受控模型不会消耗供应商额度，也不能代替真实模型验证。

旧解析与兼容检查包括 `tests/pdf-boundaries.py`、`tests/ui-regression.mjs`、`tests/ui-mineru-regression.mjs` 和 `tests/ui-mineru-split.mjs`；UI 脚本仍有开发机环境依赖。真实云脚本 `tests/mineru-live.mjs`、`tests/mineru-split-live.mjs` 及[真实生产流程脚本](../docs/expert-production/checks/real-model-flow.py)会产生外部调用和测试数据，不包含在普通自动化验证中。

## 代码导航

| 文件／模块 | 职责 |
| --- | --- |
| `ExpertLabPlugin`、`LabHttp` | DSH 装配、生命周期、本地 HTTP 与生产路由 |
| `ProductionService`、`ProductionContext`、`ProductionRules` | 流程编排、上下文、版本与审核约束 |
| `ProductionDatabase`、`ProductionJobQueue`、`ProductionRedis` | 事务持久化、租约队列和共享额度 |
| `ProductionModel`、`ProductionPrompts`、`ModelCalls` | 模型合同、提示词、调用及输出校验 |
| `MineruClient`、`MineruImport`、`MineruResult`、`MineruPageLayout` | 云导入、恢复、结果限制与物理页码还原 |
| `LabStore`、`Knowledge`、`ExpertBuilder`、`ExpertRuntime` | 旧资料、旧专家生成和试聊兼容路径 |
| `src/main/resources/web/production*` | 管理员生产页面、API 客户端及样式 |
| `src/main/resources/production-schema.sql` | 实际建表资源，与文档 DDL 同步 |

生产 API 前缀为 `/api/expert-production/v1`；旧资料／导入／试聊 API 保留在 `/api/`。构建入口为 `PUT /api/expert-production/v1/builds/{uuid}`，接口细节与版本约束见[实施合同](../docs/expert-production/implementation-plan.md)。早期 [prototype](../docs/expert-production/prototype/README.md) 仅为历史演示。
