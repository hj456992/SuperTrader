# 爱聊 · 关系牧场

一个以人物为中心的本地交流辅助应用：整理双方聊天材料，生成带原文依据的本人／对象画像，再结合当前情境、相处目标和书籍方法，提供可编辑的相处建议。最终如何回复和行动，由用户决定。

此仓库当前内容已由 SuperTrader 替换为爱聊；原项目仍保留在 Git 提交历史中。**当前源码是本地开发版本，依赖另行准备的 dsh-java 底座与 macOS 环境，不是克隆后即可独立启动的发行包。**

## 本次更新：画像 Agent 与书籍检索

本人和对象画像现已接入 **DSH / AgentScope 的真实工具循环**：模型可按需检索聊天、展开上下文和查阅书籍，再根据实际读到的材料形成画像。书籍检索复用现有词项匹配，没有新增向量数据库或独立检索服务。

| 能力 | 当前行为 |
|---|---|
| 依据可查 | 区分本人、对象和背景材料；聊天作为事实依据，书籍作为方法参考；引用仅接受本次实际读到的材料 |
| 结论有边界 | 展示直接信息、推测、置信理由、反向证据和待了解事项；相处目标不进入画像输入 |
| 材料不足可见 | 无书或检索无命中时明确标记限制；不补造书籍引用 |
| 进度与取消 | 轮询显示实际执行阶段；按任务取消，迟到结果不能覆盖已保存画像 |
| 保存与恢复 | 画像及任务状态持久化；重启后未完成任务标记为中断，不自动续跑模型 |
| 时间来源明确 | 录入／采集时间不当作发言日期；仅向模型提供来源确证的发言时间，无法确认时保持未知 |

每次画像任务最多调用模型 6 步，总时限约 110 秒，单次输出预算为 6,000 tokens。页面展示阶段进度，不是逐字流式输出。删除书籍会移除依赖它的当前及历史画像与留存书摘。

[完整验收报告](docs/qa/tl-integration-verification.md) · [启动与预检手册](docs/ops/profile-agent-runbook.md) · [画像实现说明](docs/backend/profile-runtime.md)

## 使用流程

1. **关注一个人**：创建人物，填写称呼、相处目标和补充说明。
2. **整理材料**：粘贴原文，或选择微信／飞书会话中的成员；区分对方、我的发言和背景。跨会话身份由用户确认。
3. **形成认识**：生成对象画像，查看直接信息、推测、原文依据、书籍参考和待了解事项。
4. **理解自己**：维护自述、表达习惯和边界，汇总明确属于自己的材料，生成本人画像。
5. **获得建议**：输入当前情境，结合目标人物画像、双方原始材料与书架方法生成候选回复及步骤。
6. **由人决定行动**：复制与生成都不代表发送；本应用不会自动替用户发送聊天消息。

资料书架支持 TXT、Markdown、可选中文字的 PDF、DOCX、EPUB。模型分析只在用户触发相应操作时调用配置的 DeepSeek 服务，相关选定材料及检索片段会随请求发送。本地运行不等于模型离线运行。

## 当前技术组成

| 部分 | 实现与职责 |
|---|---|
| 前端 | JavaScript、esbuild、DSH ModuleLoader、`@deepseek-ai/cordis` 页面插件 |
| 业务后端 | Java 17；dsh-java 插件宿主；JDK HttpServer；人物、材料、画像、攻略与书架 |
| 画像执行 | DSH AgentLoop / AgentScope；作用域内只读工具 `book_search`、`chat_search`、`chat_context`；应用校验主体和引用后保存 |
| 模型与攻略 | ModelRegistry 接入 DeepSeek；攻略沿用既有分析入口，结合对象画像、双方原始材料、目标与书籍，不要求先生成本人画像 |
| 数据库 | PostgreSQL 保存业务数据及任务记录；进度更新不增加业务 revision；独立 Logbook 用 SQLite 保存日志 |
| 来源读取 | `RanchLiveSources` 按需读取平台会话；飞书插件；微信 CLI 与独立历史采集插件 |
| 会话与依赖 | Agent 内部会话保留在内存；复用现有底座插件与 PostgreSQL，没有新增服务或数据库 |

微信平台按需读取与独立日志导入是不同路径。按需读取当前选择的会话最近消息，受本机同步和读取上限约束；飞书受客户端已加载内容限制。人物关联不是自动订阅全部历史，也不承诺来源更正／撤销已经端到端同步。

## 环境准备

需要 Java 17、Maven、Node.js/npm、Python 3、zsh、PostgreSQL，以及可用的 DeepSeek 凭据。微信／飞书和原生读取相关能力面向已配置的 macOS；Python 文件提取依赖按实际格式安装，例如 PDF 使用 `pypdf`。

**另行准备 dsh-java：** 本仓库未包含底座源码、运行制品和私有配置。必须先取得与本项目合同兼容的底座，安装 `dev.dsh` 的 `0.1.0-SNAPSHOT` Maven 合同依赖，并构建以下文件：

- `app-boot/target/dsh-java.jar`
- `plugins/model-registry/target/model-registry.jar`
- `plugins/model-deepseek/target/model-deepseek.jar`
- 已有 session、session-projection、agent、context、tools、agent-loop 插件制品，确切装配见[运行手册](docs/ops/profile-agent-runbook.md)。
- 前端 `frontend/src/bootstrap.js` 与 `frontend/vendor/plugins/dsh-client-modules/` 文件。

还需要与当前构建兼容的 `@deepseek-ai/cordis`（本机使用 4.0.2）及 cosmokit 文件。仅安装 AgentScope SDK 不能替代这些底座合同。

| 环境变量 | 用途 |
|---|---|
| `DSH_JAVA_HOME` | 已构建的 dsh-java 根目录 |
| `DSH_PACKAGES` | 包含 `cordis/` 与 `cosmokit/` 的 `@deepseek-ai` 包目录 |
| `MAVEN_BIN` | Maven 可执行文件路径，供构建及验收脚本使用 |
| `MAVEN_OPTS` | 可选，通过 `-Dmaven.repo.local=…` 指定已准备的兼容 Maven 缓存 |
| `DEEPSEEK_API_KEY` | 仅通过启动进程环境传入模型凭据 |
| `GARDEN_DB_URL` / `GARDEN_DB_USER` / `GARDEN_DB_PASSWORD` | 独立 PostgreSQL 数据库的 JDBC URL、用户和密码；三项一起提供 |
| `GARDEN_PYTHON` | 具有文字提取依赖的 Python 路径 |
| `GARDEN_EXTRACTOR` | 可选的文字提取脚本路径，默认 `knowledge/extract.py` |
| `GARDEN_WECHAT_PYTHON` | 微信适配器 Python，默认 `wechat-cli/.venv/bin/python` |
| `GARDEN_WECHAT_SOURCE` | 本机已授权的微信来源配置路径；配置与密钥不得提交 |
| `GARDEN_PORT` | 主应用端口，默认 48740 |
| `JAVA_BIN` | 可选 Java 可执行文件路径；启动预检要求 Java 17 或更新版本 |

脚本仍保留开发机默认路径。换电脑时需设置上述路径，不要直接照搬本机绝对路径。三项数据库变量均未设置时，启动器按原本机约定在内存读取底座 `.local/postgres.env`，使用已经存在的独立数据库；不再自动创建数据库。新环境建议显式配置三项数据库变量。

## 构建与运行

以下命令在仓库根目录执行，前提是上述外部依赖、数据库及环境变量已配置。凭据不要写入 Git 文件或命令示例。

```bash
# 设置 MAVEN_BIN 为本机 Maven 可执行文件
export MAVEN_BIN="$(command -v mvn)"

# 构建主应用、飞书插件及前端，并执行 Maven 默认测试
zsh build.sh

# 从当前终端继承模型与数据库环境变量；先只读预检
python3 run.py --check
python3 run.py
```

`--check` 只读检查配置、端口、Java 和运行制品，不启动服务、不创建数据库，也不连接数据库或模型；通过预检不能代替实际连通性验证。底座合同依赖、宿主和插件必须来自兼容的一批构建，旧的同名 SNAPSHOT 缓存可能不兼容，详见[运行手册](docs/ops/profile-agent-runbook.md)。

主入口：[http://127.0.0.1:48740/](http://127.0.0.1:48740/)；旧会话材料：[conversations.html](http://127.0.0.1:48740/conversations.html)。端口已占用时，用 `GARDEN_PORT` 选择空闲端口；停止时在运行终端按 `Ctrl+C`。

如需独立微信历史插件，另行执行 `"$MAVEN_BIN" -f plugins/wechat-history/pom.xml package`，并按对应组件说明配置。主构建不包含该可选插件。

独立日志使用 `python3 logbook/launch.py`，具体初始化和原生依赖见 [Logbook 文档](logbook/README.md)。48741 为日志阅读入口，48742 为独立日志服务；启动主应用不等于这些服务都已启动。微信密钥初始化、客户端权限和本机数据库由用户在自己的设备上配置，仓库不携带这些状态。

## 验证与已知限制

2026-09-24 本次升级的验收记录如下，具体版本与证据见[集成验收报告](docs/qa/tl-integration-verification.md)。

| 验证范围 | 结果 |
|---|---|
| Java 业务与独立画像验收门槛 | 86 项通过 |
| Node 页面组件与独立验收 | 60 项通过 |
| Python 启动、构建与 smoke 探针 | 15 项通过 |
| 飞书插件 Java | 31 项通过 |
| 构建与装配 | 主应用、飞书插件和前端构建完成；完整业务宿主 10 个插件 active |
| 真实模型与数据库 | 使用虚构材料，在隔离环境验证检索、引用、取消、重启中断及删书清理；时间修复后补验本人画像 |
| 浏览器 | 验证本人／对象画像与引用、切换、重开页面、目标提示及删书确认 |

在依赖就绪后复现确定性验证：

```bash
# release 使用 Maven 离线模式，需要先准备完整依赖缓存
# 同时运行 Java 默认测试、额外验收门槛和全部 Node 测试
sh tests/acceptance/run.sh release

python3 -m unittest discover -s tests/ops -v
"$MAVEN_BIN" -f plugins/feishu-history/pom.xml test
```

真实模型 smoke 会调用已配置的模型服务，必须使用独立端口和仅含虚构数据的隔离数据库环境，按[运行手册](docs/ops/profile-agent-runbook.md)安排；它不包含在上述确定性测试中。

真实模型样本有限，仍需用户核对结论。已记录一处对象摘要使用“用户描述”的低优先级措辞歧义；引用归属正确，该旧对象结果未再次生成。浏览器未手测运行中取消、中断／错误态及多对象多书籍组合，这些场景的确定性覆盖与实测范围在报告中分列。客户端取消也不证明供应商端立即停止计算。

其他组件的 Python 测试分布在 `logbook/tests`、`feishu/tests`、`wechat/tests`、`wechat-cli/tests`、`ranch_sources` 和 `knowledge`，需按组件配置依赖。本轮画像验收不代表全部平台采集与原生客户端功能都已重新验证。

## 文档导航

| 文档 | 用途 |
|---|---|
| [集成验收报告](docs/qa/tl-integration-verification.md) | 当前升级的验证结果、修复记录和范围限制 |
| [产品验收标准](docs/product/profile-agent-acceptance.md) | 本人／对象画像、证据边界和交互要求 |
| [启动、预检与回滚](docs/ops/profile-agent-runbook.md) | 外部依赖、插件装配、隔离运行和排障 |
| [画像运行实现](docs/backend/profile-runtime.md) | Agent 工具循环、预算、取消与持久化设计 |
| [真实运行记录](docs/ops/profile-real-smoke-report.md) | 真实模型／PostgreSQL smoke 与最终清理证据 |
| [浏览器验收](docs/frontend/browser-acceptance-5bfd899.md) | 页面实测范围与截图位置 |
| [聊天日志](logbook/README.md) · [飞书](feishu/README.md) · [微信 CLI](wechat-cli/README-ADAPTATION.md) | 各组件配置与使用方式 |

历史资料：[架构评审入口](docs/README.md)、[图解文字版](docs/architecture-review-20260924/架构评审.md)、[离线交互版](docs/architecture-review-20260924/index.html)。这些是升级前的评审快照，其中的旧流程与改造建议不代表当前实现。离线 HTML 需下载后用浏览器打开，GitHub 文件页只显示源码。[内存评估探针](docs/evidence/AssessmentProbe.java)及[结果](docs/evidence/probe-results.json)同样属于当时版本的历史证据。

## 目录

```text
src/                       Java 业务代码与测试
web/                       Cordis 页面、样式与前端构建
plugins/feishu-history/     飞书读取插件
plugins/wechat-history/     微信历史采集插件
ranch_sources/             平台会话按需读取适配器
logbook/                   独立日志应用与 SQLite 存储
wechat-cli/                有上游来源与许可记录的 CLI 适配代码
wechat/                    日志阅读与兼容入口
knowledge/                 书籍文字提取
tests/                     独立验收门槛、启动器与运行探针
docs/                      产品要求、实现说明、验收报告与历史架构图解
run.py / build.sh          本地启动与构建
```

## 源码与数据范围

[GitHub 仓库](https://github.com/hj456992/SuperTrader)管理源码、构建清单、测试和文档；不包含模型密钥、数据库密码、聊天数据库、采集缓存、运行状态、虚拟环境和构建产物。首次克隆不会获得开发机已保存的聊天、人物资料或书架数据。上传源码不等于替换正在运行的本地实例。

`.gitignore` 已配置常见本地数据路径。提交前仍应检查暂存区，避免手工导出的聊天或凭据使用了其他文件名。

## 许可与第三方来源

见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)、[微信 CLI 上游信息](wechat-cli/UPSTREAM_SOURCE.json)、[上游 LICENSE](wechat-cli/LICENSE)及[适配代码许可](wechat-cli/ADAPTER_LICENSES.md)。外部 dsh-java 和 DSH 包不作为本次源码上传的一部分；不能将某个第三方组件的许可推断为整个仓库的许可。
