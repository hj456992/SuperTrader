# 爱聊 · 关系牧场

2026-10-10：专家工作台已改为先选择资料版本，自动分析同一团队的1–6位候选子专家，再进入同一构建的人工审阅。真实312页《金字塔原理》完成11批、313片段预学习，生成5位候选；浏览器验证原文查看、刷新复用和同构建审阅入口，保持零批准。最终Java 167项、前端40项全部通过。当前上传资料和学习成果完整保留。

2026-10-10：修复MinerU上传恢复、超过200页预检及安全错误诊断；下载上限改为15分钟且受整次30分钟预算约束。本机仅对MinerU两个文件域名采用直连后，真实312页书籍已完整导入为1份版本（166,508字、313片段），原PDF不变，专家0，留管理员继续生产。最终154项Java、18项前端测试全部通过。此前10月6日团队与学习成果已按用户要求备份并清空，属于历史验收。

爱聊是一个以人物为中心的本地交流辅助应用：从聊天上下文和历史材料中理解对方的特点与诉求，结合双方画像、当前情境和书籍方法，给出候选回复与行动建议。用户核对依据、编辑回复，并决定如何行动。

**这是开发中的源码仓库。** 运行依赖另行准备的 dsh-java 底座、模型凭据和数据库，部分聊天来源读取功能依赖 macOS。仓库名仍为 `SuperTrader`，当前内容为爱聊，原项目保留在 Git 历史中。

[主应用启动](#运行主应用) · [专家实验室](expert-agent-demo/README.md) · [管理员生产流程](docs/expert-production/README.md) · [文档索引](docs/README.md)

## 当前版本 · 2026-10-06

项目包含两个独立入口：

| 入口 | 解决什么问题 | 当前状态 |
| --- | --- | --- |
| 爱聊主应用，默认 `48740` | 整理人物材料、生成本人／对象画像、提供交流建议 | 已有本地交付；画像 Agent 和攻略执行保障的验证见下方历史报告 |
| 专家实验室，默认 `48760` | PDF 云解析、资料版本管理，以及管理员逐步审阅专家团队 | 真实模型HTTP及浏览器管理员全流程通过；整书概要待审核，尚未集成主应用 |

本次源码更新将专家生产从概要原型推进到实际 HTTP、PostgreSQL、模型与页面接线：保存草稿和来源、逐项审核完整提示词、记录否决与澄清、处理修订和任务恢复，最后确认团队清单。**生成成功不等于管理员批准；自动化测试通过不等于整体验收完成。** 最新证据见[程序验收报告](docs/expert-production/acceptance-results.md)与[真实模型集成记录](docs/expert-production/integration-results.md)。

2026-10-06：管理员生产流程已通过真实模型、PostgreSQL/Redis与本地浏览器完整验收。最终未过滤Java回归136项、前端18项，均无失败、错误或跳过；测试团队9次批准后completed，重启与刷新后保留。312页整书11批学习成果保持不变，修订概要覆盖四篇及附录、16处引文可读取，仍待管理员审核、零批准。

最终用户侧的 L1/L2/L3 路由执行、向量索引及主应用接入仍未实现；当前只生成和审核相应配置。源码上传也不会替换已有本地运行实例。

## 主应用怎样使用

1. **选择一个人**：填写称呼、相处目标与补充说明。
2. **整理聊天材料**：粘贴原文，或选择已配置的微信／飞书会话，区分对方、本人和背景；跨会话身份由用户确认。
3. **理解双方**：生成对象／本人画像，查看直接信息、推测、原文依据、反向证据和待了解事项。
4. **获得建议**：输入当前情境，结合人物画像、双方材料和书籍方法生成候选回复与行动步骤。
5. **核对并行动**：自行编辑、复制和发送。复制与生成都不代表已发送，应用不会自动替用户发送聊天消息。

资料书架支持 TXT、Markdown、可选中文字的 PDF、DOCX、EPUB。画像通过 DSH / AgentScope 工具循环按需检索聊天和书籍；聊天作为事实依据，书籍作为方法参考。材料不足时展示限制，引用存在仍需人工核对其含义。

主应用画像任务最多 6 个模型步骤，总时限约 110 秒；攻略最多首次调用加一次结构／引用修复，共享约 110 秒时限。页面显示实际阶段，支持取消并阻止迟到结果覆盖当前任务；不是逐字流式输出。详细边界见[画像实现](docs/backend/profile-runtime.md)及[攻略验收](docs/qa/tl-g01-integration-verification.md)。

## 专家团队怎样生产

在独立[专家实验室](expert-agent-demo/README.md)上传 PDF、完成 MinerU 解析并选择资料版本，再填写团队名称与职责。管理员依次审阅：

**资料预学习 → 整书概要 → 专业专家 → 兜底专家 → 路由主专家 → 关键词 → 示例问答 → 完整团队清单。**

草稿从构建创建起入库，专业分工按内容生成 1–6 位。每位专家展示职责、模型、工具、特色能力、边界、来源与完整业务提示词；确认绑定实际展示的具体版本。否决缺少原因时先追问，修改后产生新稿，受影响的确认需要重新审核。

PostgreSQL 保存构建、版本、审核、消息、任务与事件；数据库租约和代次校验支持任务接管。Redis 当前接入共享调用额度控制，缓存和通知尚未接入业务。多实例还需要共同可见的原件存储；旧上传任务和旧试聊继续保留原有本地状态机制。详见[流程、配置与验证入口](docs/expert-production/README.md)。

## 运行主应用

需要 Java 17、Maven、Node.js/npm、Python 3、zsh、PostgreSQL 及 DeepSeek 凭据。Python 文件提取依赖按格式准备，例如 PDF 使用 `pypdf`。

先准备与本仓库兼容的 **dsh-java 宿主、合同 Maven 依赖、模型及 Agent 循环插件**，以及前端 DSH 模块和 Cordis 包。仅安装 AgentScope SDK 不足以运行本项目。确切制品清单与装配见[启动手册](docs/ops/profile-agent-runbook.md)。

| 环境变量 | 用途 |
| --- | --- |
| `DSH_JAVA_HOME` | 已构建的 dsh-java 根目录 |
| `DSH_PACKAGES` | 包含 `cordis/` 和 `cosmokit/` 的包目录 |
| `MAVEN_BIN`、可选 `MAVEN_OPTS` | Maven 路径；可用 `-Dmaven.repo.local=…` 指定兼容依赖缓存 |
| `DEEPSEEK_API_KEY` | 模型凭据，仅通过进程环境传入 |
| `GARDEN_DB_URL` / `GARDEN_DB_USER` / `GARDEN_DB_PASSWORD` | 已存在的独立 PostgreSQL 数据库，三项一起配置 |
| `GARDEN_PYTHON`、可选 `GARDEN_EXTRACTOR` | 具备提取依赖的 Python；提取脚本默认 `knowledge/extract.py` |
| `GARDEN_PORT`、可选 `JAVA_BIN` | 主应用端口，默认 `48740`；Java 路径 |
| `GARDEN_WECHAT_PYTHON` / `GARDEN_WECHAT_SOURCE` | 可选的本机微信适配器和已授权来源配置 |

脚本保留开发机默认路径，换电脑必须按实际环境配置。数据库三项均未设置时，启动器尝试读取底座 `.local/postgres.env` 并使用已有独立数据库，不自动建库。

在仓库根目录执行：

```sh
export MAVEN_BIN="$(command -v mvn)"
zsh build.sh
python3 run.py --check
python3 run.py
```

构建包含主应用、飞书插件、前端与 Maven 默认测试。主应用 `--check` 只读核对配置、端口和制品，不连接数据库或模型；通过预检不代表实际连通。启动后打开 [http://127.0.0.1:48740/](http://127.0.0.1:48740/)，在运行终端按 `Ctrl+C` 停止。

专家实验室有[独立启动步骤](expert-agent-demo/README.md#运行专家实验室)，使用 `EXPERT_*` 配置，不由上述命令启动。日志入口另用 `python3 logbook/launch.py`，详见 [Logbook](logbook/README.md)。微信历史插件为可选组件，不包含在主构建中；配置见[微信插件说明](plugins/wechat-history/README.md)。

## 验证与已知边界

| 范围 | 证据及限制 |
| --- | --- |
| 本次管理员生产自动化 | 最终Java 136项、前端18项通过；[程序验收](docs/expert-production/acceptance-results.md)、[页面验证](docs/expert-production/frontend-verification.md)。含连接故障、任务关闭、长书上下文与修订回复回归 |
| 原创资料真实模型流程 | 两条HTTP链路及一条真实浏览器审核链路，各9次批准完成；[集成记录](docs/expert-production/integration-results.md)。整书概要修订后仍待管理员审核 |
| 主应用攻略 G01，2026-09-25 | [Java 124、Node 65 及隔离真实模型记录](docs/qa/tl-g01-integration-verification.md)；该历史版本保留性别未明确时使用“他”的 Q01 问题 |
| 主应用画像升级 | [历史集成验收](docs/qa/tl-integration-verification.md)，包括引用、取消、重启中断及删书处理；旧对象摘要仍有“用户描述”的措辞歧义 |
| 本地发布与数据保护 | [发布记录](docs/ops/local-release-g01-execution-20260925.md)与[独立审核](docs/qa/local-release-review-20260925.md)；这些是既有本地版本的证据 |

复现主应用确定性检查（外部依赖和离线缓存须就绪）：

```sh
sh tests/acceptance/run.sh release
python3 -m unittest discover -s tests/ops -v
"$MAVEN_BIN" -f plugins/feishu-history/pom.xml test
```

专家生产的数据库和前端测试见[专家实验室验证说明](expert-agent-demo/README.md#验证)。真实模型验证会调用供应商，使用隔离端口、测试数据库与虚构材料；不包含在普通确定性测试中。

模型分析会把选定材料和必要上下文发给配置的服务；PDF 云解析会把原件发给 MinerU。本地运行不等于离线推理。模型样本有限，取消也不保证供应商立即停止计算。微信读取受本机同步和数量上限约束，飞书读取受客户端已加载内容限制；人物关联不代表持续订阅全部历史。

## 目录与文档

```text
src/                  主应用 Java 业务与测试
web/                  主应用 Cordis 前端
expert-agent-demo/    独立专家实验室、生产审核页面与测试
plugins/              飞书和微信历史插件
ranch_sources/        平台会话按需读取适配器
logbook/              独立聊天日志与 SQLite 存储
knowledge/            主应用书籍文字提取
wechat-cli/           带上游来源及许可记录的 CLI 适配
tests/               主应用独立验收与运行探针
docs/                 产品、实现、验收及历史架构文档
```

- [管理员生产入口](docs/expert-production/README.md)：当前流程、配置、合同和验收。
- [主应用运行手册](docs/ops/profile-agent-runbook.md)：依赖、预检、装配、排障和回滚。
- [主应用产品要求](docs/product/profile-agent-acceptance.md)与[后续差距](docs/product/architecture-gap-backlog-20260925.md)：已完成范围与待实施事项。
- [聊天日志](logbook/README.md)、[飞书](feishu/README.md)、[微信适配](wechat-cli/README-ADAPTATION.md)：组件使用说明。
- [历史架构评审](docs/README.md)与[早期 Demo 记录](README-详细.md)：历史快照，其中的旧流程和建议不代表当前实现。

## 源码、数据与许可

仓库提交源代码、测试、构建清单和文档，不包含密钥、聊天数据库、用户上传书籍、私密 OCR、运行状态、备份、依赖缓存或构建产物。首次克隆不会获得开发机上的人物、聊天和书架数据；专家实验室仅附带原创虚构演示 PDF。

第三方来源及许可见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md)、[微信 CLI 来源](wechat-cli/UPSTREAM_SOURCE.json)和[适配许可](wechat-cli/ADAPTER_LICENSES.md)。外部 dsh-java 与 DSH 包不包含在本仓库，第三方组件的许可不等于整个项目的许可。
