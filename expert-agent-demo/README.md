# 爱聊 · 专家实验室（DSH Java 插件 Demo）

> GitHub 源码快照（2026-09-28）：本目录为独立专家实验室，尚未集成到主应用。仅提交源码、测试、说明及原创虚构测试 PDF；文中提及的真实书籍 OCR、截图、专家运行数据和验收记录保留在开发机，不包含在公开仓库。脚本中的本机路径需按 README 的环境变量配置；UI 测试仍含本机 Playwright 路径。提示词优化目前仅为对话草稿，尚未应用到代码。

上传 PDF 到 MinerU 精准云 API 进行 OCR，保留原件、页码与资料版本，再选择资料生成专家草稿并试聊。

**真实验证状态（2026-09-27）：** 首次312页直接上传被200页限制拒绝。用户确认拆分后，两份临时PDF已顺序完成云解析并合并入库：312页、166,544字符、313个片段。原件及旧启用专家保持不变。逐页布局用于纠正云端段落跨页合并；图表文字未全部OCR，保留原图并记录局限。新专家生成/试聊等待参数确认。证据：`evidence/mineru-split/live-result.json`、`ocr-verification.json`、`original-verification.json`。

这是可加载的 DSH 插件：`META-INF/dsh-plugin.properties` 声明 `dev.ailiao.expert.ExpertLabPlugin`，产物为 `target/expert-lab.jar`。文档加工使用宿主 `ModelRegistry`，试聊中的主专家和子 Agent 使用宿主 `AgentRegistry` / AgentScope 循环。页面及任务记录展示实际调用结果，没有预置回答。业务插件与 DSH 底座、既有 `outputs/demo` 分开；当前未接入正式爱聊页面或数据。

## 本机运行

当前地址：<http://127.0.0.1:48760/>。页面已有用原创三页资料生成的“职场沟通顾问 · 演示”，可查看分工或开始新试聊。

```sh
cd /Users/hou/Documents/Codex/2026-09-22/garden-product-design/outputs/expert-agent-demo
zsh build.sh
python3 run.py --check
python3 run.py
```

启动环境需已设置 `DEEPSEEK_API_KEY`，脚本不把密钥写入配置。默认使用当前已可用的 `deepseek-v4-flash`；生成和试聊会向配置的模型服务发送所选资料文字、问题和必要历史，并产生模型调用费用。上传会将原 PDF 发送给 MinerU，并可能消耗解析额度。Finder 双击 `.command` 不一定继承终端密钥，推荐在已有凭据的终端运行上述命令。停止独立宿主使用该终端 Ctrl-C。

依赖：Java 17、Maven、与当前宿主匹配的 DSH 合同/插件 jar、Python 3 + pypdf。默认路径按本机已有运行环境配置，没有新增数据库服务。

| 配置 | 用途 |
| --- | --- |
| `DSH_JAVA_HOME` | DSH 项目及已构建的宿主、插件目录 |
| `MAVEN_BIN` / `JAVA_BIN` | Maven 和 Java 路径 |
| `EXPERT_MAVEN_REPO` | 与宿主匹配的合同缓存；默认使用已有爱聊升级工作目录的 `.local/m2`，共享 `~/.m2` 当前含旧合同 |
| `EXPERT_PYTHON` | 已安装 pypdf 的 Python；核对原件页数，并按用户确认制作临时分卷 |
| `MINERU_API_TOKEN_FILE` | 私密 Token 文件，默认 `~/.config/mineru/token`；文件权限 600、目录 700 |
| `MINERU_API_TOKEN` | 可选环境变量，优先于文件；不要写入仓库或日志 |
| `EXPERT_LAB_PORT` | 本机端口，默认 48760 |
| `EXPERT_LAB_DATA` | 独立数据目录，默认 `.local/data` |
| `EXPERT_PROVIDER` / `EXPERT_MODEL` | 模型标识；启动器默认加载 DeepSeek provider，其他 provider 需在宿主装配中提供 |

## 怎样体验

1. 超过云端200页限制而失败的整书，可点击“分成每份最多200页，解析后合并”。每份顺序解析、各自保存云任务编号；全部页码验证后才写入同一资料版本。归档和引用保留原始整书，原失败记录保留。
2. 资料库上传 PDF；后台申请上传地址、原件 PUT、每 5 秒查询 OCR、下载并核对全部物理页码、完整入库。给已有资料选择“新版本”会保留旧版本与原文件。测试资料见 `evidence/演示资料-职场沟通方法.pdf`，内容为原创虚构演示材料。
3. 在专家工作台填写名称、职责，勾选资料并逐份指定版本。同一资料只有一个版本选择框，后端也拒绝重复选择。
4. 生成时先按页保留出处和切片，再把全部选中文本分批交给模型，提炼方法与适用边界，最后归并成 1–6 个子 Agent。切片用于处理长文，不是一片创建一个 Agent。
5. 在分工卡片展开方法和原文；进入试聊，观察主专家选择哪些专业分工、子 Agent 核读哪些来源及返回什么结论。
6. 有成功子 Agent 分析和有效引用的试聊，才取得启用资格；问候、所有委派失败、取消、超时均不会取得资格。管理员仍需自己判断是否满意，再点击启用。
7. 修改配置后再生成，得到新的专家版本。它冻结名称、职责、资料版本、方法、分工和模型标识；旧启用指针保持不变。切换版本自动开始新试聊。

“启用”目前只设置 Demo 内的专家版本指针，不会发布到现有爱聊，也不表示模型已经获得或通过专业能力认证。

## 模块和复用边界

| 模块 | 职责 |
| --- | --- |
| `ExpertLabPlugin` | 宿主依赖装配、资源生命周期 |
| `LabStore` / `Knowledge` | 资料和专家版本、原子快照、片段与词项检索 |
| `PdfImport` / `extract_pdf.py` | 原件页数与空密码可读检查、私密临时分卷；旧文字提取代码仍保留供回归 |
| `MineruClient` / `MineruImport` | 官方 API、私密导入记录、暂停/恢复、取消与幂等入库 |
| `MineruResult` / `MineruPageLayout` | ZIP累计体积、页清单及逐页布局校验，恢复原件页码，保留文本与原块 |
| `ExpertBuilder` / `ModelCalls` | 分批研读、方法提炼、专业分工、引用校验 |
| `ExpertRuntime` | 主专家 `consult_specialist`；子 Agent `read_passage` / `search_knowledge` |
| `Jobs` / `LabHttp` | 后台进度、共享时限、取消屏障、本机 HTTP |
| `resources/web` | 资料库、专家配置、管理员试聊页面 |

复用时保留插件 jar、提取脚本及配置，把插件条目加入兼容宿主 bootstrap 即可加载；`run.py` 给出了完整独立装配示例。它注入 `llmRuntime / agents / systemPrompt / tools`，依赖现有 model、session、agent-loop 等插件。不需要把编排写进 DSH 底座。当前跨模块入口是插件的 HTTP API，尚未抽取供其他业务插件 `require` 的公共专家合同；统一鉴权、生产存储、主站页面和正式服务合同属于后续集成工作。

主要接口：`GET /api/state`、`POST /api/documents`（原始 PDF，202 返回 importId/jobId）、`GET /api/imports/{id}`、`POST /api/imports/{id}/resume`、`POST /api/imports/{id}/split`（显式同意页数超限后拆分）、`GET /api/document-version?versionId=`、`GET /api/original?versionId=`、`POST /api/experts`、`GET /api/jobs/{id}`、`POST /api/jobs/{id}/cancel`、`POST /api/chat`、`POST /api/activate`、`GET /api/passage?id=`。写接口须携带 state 返回的 `X-Lab-Token`。仅监听 loopback，并校验 Host 和 Origin；不适合直接公网使用。

## 边界与验证

- 上传仍为 20MiB。本地按官方文档检查不超过 600 页，但本次真实服务拒绝超过 200 页。云 OCR 使用 vlm、ch、强制 OCR、表格/公式识别；入库取消 25 万字符限制，不截断。
- 已批准：连接 15 秒、API 30 秒、上传/下载各 5 分钟、导入观察窗口 30 分钟；查询连续失败 3 次暂停。后台最多 2 个任务，其中 OCR 最多 1 个。全部分卷ZIP累计最多 200MiB，读取 JSON/Markdown 累计 100MiB，超限不入库。
- 尚未批准且未改动：专家生成选择最多 8 份、总计 25 万字符，以及以下切片、生成和试聊限制。本书已取得实际166,544字符，生成预算正在一次确认，旧代码数值不代表用户认可。
- 资料保留版本和页码，段落优先切分，每片最多 2400 字符；研读每批约 18000 字符。跨页方法、复杂排版、模型遗漏仍需要人工核对。
- 每次生成最长 20 分钟；单轮试聊共享 6 分钟、最多 4 次委派、全队最多 24 个模型步骤；同一会话最多 8 轮。结构化回答解析失败仅允许一次格式重试，仍受总时限与步骤限制。
- 子 Agent 仅可检索自身绑定的方法来源；已存在的来源 ID 校验不等于语义结论得到证明。没有长书专业能力基准、领域专家评审或负载验收。
- 数据用本地 JSON 原子快照和 PDF 原文件持久保存。云导入另有私密记录，保存原件、batch ID、阶段与成功版本；已提交的任务恢复时查询原编号，按 importId 幂等入库。上传结果不确定时只能查询云端是否已接收，不能承诺补传。取消仅停止本地处理，云端可能继续。生成/试聊任务仍只在内存；没有通用耐久队列、多租户或生产迁移。
- 会话在后端保存并支持当前页面连续追问，页面刷新不自动恢复已经完成的历史会话列表。

验证入口：`zsh build.sh`（包含新增云协议、页码、长文及恢复测试，数量以当次报告为准），`tests/pdf-boundaries.py`（旧文字提取与拒绝路径），`tests/ui-regression.mjs`（版本切换、断线恢复）、`tests/ui-mineru-regression.mjs`（恢复导入、保留专家、原件链接）、`tests/ui-mineru-split.mjs`（显式拆分）。`MINERU_REAL_CONFIRM=1 node tests/mineru-live.mjs` 是真实整书云上传，会提交新任务并可能消耗额度，不应反复执行来掩盖失败。旧 `tests/e2e.mjs` 的同步上传假设不再适用于当前云上传入口。结果与截图在 `evidence/`；测试桩不代表云 OCR 成功。技术文档仍维护在相邻 `ailiao-comparison-20260926` 原文档中。

分卷真实验收：`tests/mineru-split-live.mjs`；首次执行会消耗云额度，仅在已获批准时运行。最终48项Java测试通过。真实ZIP解析与3,952个物理文本/公式/表格跨度校验、6页渲染对照见 `evidence/mineru-split/`。解析完成只证明数据链路和页码保留，不代表专业能力或逐字OCR准确性。

## 管理员生产审核设计

新增[概要审核设计与原型](../docs/expert-production/README.md)，供审阅生产聊天流程。原型独立运行，不修改上述现有插件接口或真实运行数据；PostgreSQL/Redis及真实模型处理仍待实现。
