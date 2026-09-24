> 当前微信采集入口已改为 dsh-java wechat-history 插件，调用 wechat-cli history；真实微信密钥初始化已完成。最新接入及启动说明见 [logbook/README.md](logbook/README.md)。下文旧采集说明以此为准。

> 当前实现已替换为独立聊天日志应用与爱聊只读接口；不再执行下述旧OCR流程。请以 [logbook/README.md](logbook/README.md) 为准。

# 爱聊（ai chat）Demo 实现与运行

## 微信可见消息采集扩展（2026-09-23）

新增 [48741 微信采集页面](http://127.0.0.1:48741/wechat.html)，由 Objective-C/Vision 本地采集器、Python 采集服务及独立 SQLite 存储提供。原48740 Java进程和人物PostgreSQL继续运行；扩展入口通过同源代理复用人物API。群成员、消息和覆盖片段独立保存，未送入人物分析上下文，不受人物80条输入上限限制，也不自动调用模型。

双击 `启动微信采集.command` 可启动扩展；开始后在指定微信群直接滚动，停稳页面会自动采样。锁屏、最小化或不能核实目标群时停止入库。这里只覆盖可见页面，OCR置信度和覆盖缺口都保留，不能承诺全量历史同步。文件、运行命令、权限和测试说明见 [wechat/README.md](wechat/README.md)。

下文记录原有人物分析模块，其数据库与模型边界保持不变。

## 装配关系

复用本机 `/Users/hou/Documents/Codex/projects/dsh-java`，核实源代码版本为 `e39417f`。本次没有改动底座源码、已有数据库或其他服务。

后端由已有 `app-boot/target/dsh-java.jar` 启动，使用独立 bootstrap 装配3个插件：

| 插件 | 作用 |
|---|---|
| dsh-java-model-registry | 提供 `llmRuntime` / `ModelRegistry` |
| dsh-java-model-deepseek | 原有 DeepSeek 适配器，发起真实请求 |
| garden-demo | 人物记录、证据校验、异步分析、本地 HTTP 界面与 API |

这条受控业务流程直接消费底座 ModelRegistry，没有调用底座的 AgentScope agent-loop，也没有把任意工具或全自动代理放进社交流程。后续可在明确需求后继续组合其他底座能力。

前端使用底座原样复制的 `bootstrap.js`、原版 `dsh-client-modules/client.js` 和本机原版 Cordis 4.0.2。外壳只创建模块系统与 Cordis Context；`garden-ui` 经模块加载器导入，通过 `ctx.plugin` 启动，DOM 事件、轮询和请求取消由 `ctx.effect` 清理。自有 UI 是根插件，不复用原 Agent 会话界面。

## 源码位置

| 文件 | 职责 |
|---|---|
| src/main/java/dev/garden/GardenPlugin.java | 插件生命周期与依赖注入 |
| src/main/java/dev/garden/GardenService.java | 人物、消息、纠正、已发反馈与版本校验 |
| src/main/java/dev/garden/Analyzer.java | 冻结输入、真实调用、结构/引用校验、取消与过期结果拦截 |
| src/main/java/dev/garden/Store.java | PostgreSQL 人物聚合与原子版本更新 |
| src/main/java/dev/garden/HttpApi.java | 静态文件、HTTP API、本地会话与来源检查 |
| web/src/garden-ui.js | 聊天时间线、固定回复输入区、交流助手与前端插件生命周期 |
| web/src/host.js | 最小前端装配外壳 |
| web/public/style.css | 桌面和窄窗口布局 |
| run.py / build.sh | 独立启动与构建 |

## 数据与模型

- 独立数据库：`garden_demo_20260922`，复用已运行 `dsh-java-postgres` 的 PostgreSQL 17 服务，连接 `127.0.0.1:15440`。不会重建或清空底座数据库。
- 单表 `garden_person`，UUID 主键、revision 与 JSONB 聚合。每个人独立保存名称、群/场景、目标、原文、来源标识、当前分析和幂等导入标识。
- `source=sample` 明确表示虚构示例。`source=imported` 是用户手动导入，未自动核验。
- `them`、`me`、`note` 分开表示对方原话、我的原话、用户备注。只记录加入时间，不伪造真实发送时间。
- 同一导入操作 ID 重试不重复写入。不同交流中相同的“好的”等原话会保留。当前没有微信消息 ID，因此不能自动识别跨批次重叠聊天记录，导入前需要自己检查。
- 模型 `deepseek-v4-flash`，关闭额外推理输出，输出上限3500 tokens，每次流等待最多100秒。格式/证据校验失败时最多自动重新生成一次，仍只使用同一份原始资料；网络错误不自动重试。两次都失败时不保存，用户可手动重试。
- 每次分析只发送所选人物的名称、目标、来源标签、消息与新消息 ID 标记。旧模型结论不当作事实反复回灌。
- 模型输出要求特点/诉求/回复依据引用当前人物的消息 ID；引用不存在或结构错误则不保存。只存白名单产品字段，不存 reasoning 流或其他额外字段。证据存在不等于推断正确，仍需要人工判断。
- 同时仅运行一个模型任务。取消后不保存结果；分析期间上下文变化，版本比较阻止旧结果写回。纠正/删除撤销旧分析和上次输入标记。
- 复制不写数据库；用户确认已自行发出后，保存其实际编辑文本并标记 `selfReportedSent`。不会认为复制等于发送，不把手动确认当作微信自动核验。
- 不计算亲密度或游戏成长；植物只作界面意象。

## 本地访问与凭据

仅监听 `127.0.0.1:48740`。严格校验 Host；API 需要本次运行随机生成的 HttpOnly/SameSite=Strict Cookie。写操作还检查同源 Origin、JSON Content-Type 和自定义请求头，无跨域许可。前端启用 CSP，模型和导入内容通过转义显示，不作为脚本执行。

模型密钥只从启动进程的 `DEEPSEEK_API_KEY` 读取。默认脚本从底座 `.local/postgres.env` 读取已有 PostgreSQL 凭据，仅进入子进程环境；不把任何密钥或密码写进工程。`.runtime/bootstrap.yml` 只包含制品路径与插件配置。日志不包含对话正文、模型输出和凭据。

这是个人本机 Demo，数据库并未做应用层加密；同一电脑上有数据库权限的程序仍能读取资料。删除清除当前数据库中的人物聚合与分析，不覆盖数据库底层 WAL/备份，也不能撤回提供方已收到的请求。

## 依赖与迁移

- Java 17，Maven 3.9；本机 Maven 默认 `/Users/hou/.local/apache-maven-3.9.16/bin/mvn`。
- 底座已有启动/模型插件 jar，以及本地 Maven 仓库的 `dev.dsh:agent-contract:0.1.0-SNAPSHOT` 及其传递合同。
- Node.js/npm；前端固定 esbuild 0.25.5，`package-lock.json` 锁定依赖。
- 原版浏览器包默认位于 `/Users/hou/Documents/Codex/DSH/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai`，只读复用。
- PostgreSQL JDBC 42.7.10、Jackson 2.21.1 随独立插件打包；底座合同保持 provided，避免重复内核类型。

可覆盖环境变量：

| 变量 | 作用 |
|---|---|
| DSH_JAVA_HOME | 底座目录 |
| DSH_PACKAGES | 构建前端时的原版浏览器包目录 |
| MAVEN_BIN | 构建脚本的 Maven 可执行路径 |
| GARDEN_PORT | 本地端口，默认48740 |
| GARDEN_DB_URL / GARDEN_DB_USER / GARDEN_DB_PASSWORD | 全部提供时连接自选的**独立**数据库，跳过默认 Docker 数据库创建 |
| DEEPSEEK_API_KEY | 模型凭据 |
| DEEPSEEK_BASE_URL | 底座适配器可选的服务地址 |

初版依赖本机已有底座环境，尚未打包成独立可安装的 `.app`。

## 验收记录（2026-09-22）

- Maven 编译打包成功，前端 esbuild 构建成功。
- 实际启动报告3个插件全部 ACTIVE；浏览器页面由 garden-ui 插件呈现。
- 虚构摄影对话真实生成理解、回复与行动；录入编辑后的已发内容和对方后续，再次真实生成不同回应。期间遇到模型字段格式波动导致分析失败，结果未写入，手动重试成功。
- 服务重启后，人物 UUID、版本、全部原文与已保存内容一致。
- 未鉴权 API 返回401，跨域写入返回403；旧版本修改返回409。
- 同名人物隔离，同一导入操作重试幂等，不同交流的相同原文保留。
- 纠正/删除原文撤销旧分析；超总量纠正被拒绝且版本不变。
- 取消分析不写入；正在分析时更新记录，迟到结果不能覆盖新上下文。
- 浏览器验证：编辑与手动确认；紧凑模式切换保留回复/后续草稿；桌面与窄窗口布局；证据定位与纠正入口。
- 独立代码审查发现的状态快照竞态、纠正容量遗漏和编辑草稿丢失问题已修复。

验收临时人物已清理，只保留明确标记的虚构小林示例，供继续体验。外部模型有波动，Demo 不保证每次都生成可用回复。

## 聊天主界面修订

根据用户修正，点击人物直接打开完整聊天时间线，不再以分析报告作为主页面。所有当前保存的消息都渲染为连续气泡，初次进入定位到最新消息，可向上滚动查看全部历史；不伪造未接入的微信记录。底部固定回复区，右侧是可追溯的交流助手，窄窗口以可收起侧栏呈现。原文纠正与删除可从消息下方操作。


## 2026-09-23：界面对齐 DeepSeek Harness

- 样式依据：本机固定安装版 `dsh-client-ui-theme` 的中性配色和 shell 的圆角控件；正文 `#0f1115`、辅助文字 `#61666b`、次级文字 `#81858c`、侧栏 `#f5f6f7`。
- `web/public/theme.css`：共享颜色变量、焦点样式和减少动画偏好。`style.css` 引用它，调整人物聊天、侧栏、助手、输入区、弹窗及手机布局；`logview.css` 统一日志阅读与独立导入应用。
- `web/src/garden-ui.js`：仅将植物 SVG 替换为线性聊天图标。插件挂载、接口、保存、分析及按钮行为不变。
- `npm --prefix web run build` 生成 `web/dist`，现有静态服务直接读取产物，无需修改 Java 服务。
- 验证：构建成功；Playwright 检查 1440 / 900 / 390 像素宽度无水平溢出，聊天助手、添加人物弹窗可以开关，在线日志阅读可连接。日志当前为空，因此搜索只核实空列表渲染，没有将其称为真实消息筛选验收。无浏览器脚本错误。
- 人物界面检查使用浏览器请求隔离示例，因为检查开始时原人物服务未监听；没有写入示例数据、调用模型或提交表单。截图和验证摘要见工作区 `work/ui-refresh/`。
- 随后通过原有 `run.py` 启动人物服务，控制台确认 48740 就绪；最终在线界面检查结果保存在 `work/ui-refresh/live-verification.json`。没有修改启动逻辑或凭据。
