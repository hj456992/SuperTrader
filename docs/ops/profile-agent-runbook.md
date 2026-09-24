# 画像 Agent 启动、预检与回滚

## 边界与现状

本说明只涉及当前应用与已有 DSH 插件，不创建数据库、容器、服务或云资源。原应用的 48740 端口不动。所有 smoke 必须在隔离工作树、独立端口、**已存在且明确专用于虚构数据的 PostgreSQL 数据库**，或 TL 明确授权的维护库独占临时 schema 中进行；若两者都没有，停止数据库 smoke 并交 TL 决定，不能用真实业务表替代或自行建库。

2026-09-24 只读基线：Java 17.0.20、Maven 3.9.16、Node 24.14.0 可用，48740 有 Java 监听。未读取原实例业务接口、停止原实例、访问真实聊天、迁移或销毁数据库。

## 构建

在指定隔离工作树执行：

```sh
python3 -m unittest discover -s tests/ops -v
zsh -n build.sh
zsh build.sh
node --test web/src/*.test.js
# 可选真实制品装配探针（不连业务数据库，不发模型请求）
python3 tests/ops/check_plugin_assembly.py
```

`build.sh` 尊重 `MAVEN_BIN`，默认本机 `/Users/hou/.local/apache-maven-3.9.16/bin/mvn`；构建业务与已有飞书插件并运行两者 Java 测试，安装锁定前端依赖，再打包 UI。脚本创建 `web/dist/vendor`，避免干净工作树构建时 copyFile 失败。若单独构建 UI：

```sh
mkdir -p web/dist/vendor
npm --prefix web run build
```

`DSH_JAVA_HOME` 指向已准备的底座；`DSH_PACKAGES` 指向既有 Cordis/cosmokit 包。底座源码与制品只读，不从本脚本触发重建或安装。本机旧 Maven 缓存与底座 target 制品有差异；团队本轮通过 `MAVEN_OPTS=-Dmaven.repo.local=/Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/ailiao-upgrade/backend/.local/m2 zsh build.sh` 使用后端准备的兼容依赖缓存，不更改共享缓存。底座 Maven 合同、app-boot 和插件必须来自兼容的一批构建；不能以源码已存在或 JAR 文件已存在代替二进制兼容性验证。

## 预检和装配

凭据仅由启动进程环境提供。禁止把真实值写进命令记录、文档、测试夹具或仓库；不要 `env`、`printenv`、`set -x` 或打印子进程完整环境。

必需环境：`DEEPSEEK_API_KEY`；显式数据库配置时 `GARDEN_DB_URL`、`GARDEN_DB_USER`、`GARDEN_DB_PASSWORD` 必须同时非空。可选 `JAVA_BIN` 指定 Java 可执行文件，`GARDEN_PORT` 默认 48740。启动器始终把 `GARDEN_WEB` 指向本工作树 `web/dist`。

兼容旧启动方式：三项数据库变量均未设置时，只在内存读取底座 `.local/postgres.env`，连接**已经存在**的 `garden_demo_20260922`。已移除原启动器隐式 `CREATE DATABASE`。该默认值不适用于隔离 smoke；smoke 必须显式给出三项测试数据库变量。

在已经配置好测试凭据的专用终端中执行：

```sh
GARDEN_PORT=48749 WECHAT_HISTORY_EMBEDDED=0 python3 run.py --check
GARDEN_PORT=48749 WECHAT_HISTORY_EMBEDDED=0 python3 run.py
```

`--check` 检查配置完整性、端口可绑定、Java >=17、宿主与插件制品、前端入口和飞书适配脚本；不写 bootstrap、不启动宿主、不连接数据库或模型，也不读取微信配置。端口检查存在检查到启动之间的竞争窗口，最终以实际启动为准。预检通过不代表数据库可连、JAR 兼容、Agent 成功执行或模型凭据有效。

生产启动生成 `.runtime/bootstrap.yml`（目录 0700、文件 0600），只写插件路径与公开装配配置，不写模型或数据库凭据。凭据经子进程环境传递。继承 Java 的 stdout/stderr 不属于预检的脱敏保证；真实运行日志视为私有，不把未经审阅的日志上传仓库。原有 `WECHAT_HISTORY_EMBEDDED=1` 仅保留兼容入口，会调用原历史插件配置逻辑；本轮 smoke 必须关闭它，禁止调用平台采集接口。

装配按后端 `docs/backend/profile-runtime.md`（首版 98e1269，并按源码核对将 agent-loop 的模型依赖修正为 `llmRuntime`）执行：保留 model-registry、model-deepseek、feishu-history、garden-demo，加入 sessions、session-projections、agents、prompt-context、tools、agent-loop。Garden 注入原有服务及 agents/systemPrompt/tools。会话为内存目录，不加载 storage/storage-pg/checkpoint；业务记录仍复用现有 `garden_ranch_documents`。

## TL 隔离验收步骤

1. 确认测试数据库或 TL 授权的独占 schema 已存在、仅含虚构数据，并确认测试端口空闲。先记录代码提交、底座版本/制品校验和及测试前状态；不要记录连接密码。没有明确隔离位置即报告阻塞，不执行下面步骤。
2. 在测试终端以前述命令启动，检查宿主实际 active 插件数以及 AgentScope 路径加载。预检或 Python 替身测试不能替代此项。
3. 浏览 `http://127.0.0.1:48749/` 获得会话 Cookie。创建虚构人物“林舟”；粘贴“林舟：我喜欢提前一天安排见面。”“我：我需要先确认时间再答复。”并明确主体。用虚构 TXT 书籍写“沟通练习：先复述明确表达，再提出开放问题；不足以据此判断稳定人格。”作为测试方法资料。不要使用平台导入。
4. 从 `/api/ranch-state` 取 revision；调用 `POST /api/ranch-analyze`，分别以 `id:"self"` 和实际新建人物 id 验证。JSON 写请求需要该测试实例 Cookie、`Origin: http://127.0.0.1:48749`、`X-Garden-Request: 1`、`Content-Type: application/json`。Cookie 只保存在客户端内存，不写 curl cookie 文件。
5. 轮询同一实例 state.job 到终态，核对真实阶段、runId、knowledgeStatus、实际读取的书摘和聊天引用，确认至少一次工具结果影响之后模型调用。保存后重新打开页面，检查画像仍可读取；修改相处目标不能重写画像事实。真实模型调用单独标记为真实 smoke，会使用既有模型服务。
6. 新任务运行时用 `POST /api/ranch-cancel {runId}` 取消；确认最终 cancelled 且迟到结果未保存，再做旧 runId 不影响新任务检查。确切取消竞态以确定性 Java/QA 测试补充，不能只靠手动点击宣称覆盖。
7. 中断检查仅针对本任务亲自创建并持有的测试进程：正常 Ctrl+C 可能完成取消并持久化 cancelled；验证 interrupted 时，在确认 running 后异常中断该测试进程，待其退出后按相同测试配置重启，确认旧 job 显示 interrupted、已存画像保留、业务 revision 不因进度而增长。不宣称自动恢复模型任务；不使用 `pkill java`、`killall` 或扫描并终止其他实例。

## 备份与回滚

本轮不执行真实数据备份、恢复或迁移。发布前由 TL 确认已存在的备份流程与恢复演练；数据备份必须落在私有且权限受控的位置，不能作为测试夹具或提交。不要把密码作为数据库工具命令行参数。

隔离 smoke 失败：仅 Ctrl+C 停止本次测试宿主，记录脱敏错误阶段、提交和失败测试；保留测试库供排查，不 drop/truncate，不自动回写旧文档。当前用户实例不受影响。

代码回滚：保留上一可用版本所在工作树及其制品，停止新测试宿主后，从上一版本目录以相同的**测试**数据库配置和独立端口启动；切换前审查已有兼容字段读取测试。避免 `git reset --hard` 覆盖团队工作。生产切换/恢复由 TL 统一执行，此任务不操作原运行目录。旧版本不理解 interrupted 等字段时，先用离线兼容测试确认，不能仅凭“字段可选”声称可以无条件回滚。

## 验证证据

运维测试使用临时目录、虚构密钥/数据库名和替身 Java 进程，覆盖启动器可观察行为和凭据不输出/落盘；没有访问数据库或真实模型。最终构建、Node 测试与真实制品装配结果随提交回报 TL。若缺少隔离数据库/schema 或二进制兼容性，必须把真实全链路 smoke 标为未完成。

## 本轮 TL 授权的隔离位置与私有启动

2026-09-24 TL 授权在既有 `postgres` 维护库创建本任务临时 schema。ops 已创建 `ailiao_profile_smoke_0e2d7c0a95d2`，创建时为空；归属 ops/TL 本轮集成验收，未使用任何业务库数据。JDBC 必须严格为：

```text
jdbc:postgresql://127.0.0.1:15440/postgres?currentSchema=ailiao_profile_smoke_0e2d7c0a95d2
```

不追加 public，不修改数据库级 search_path。Store/RanchStore 的非限定表名将只解析到该 schema；schema 不存在或无权限时应报错。所有 smoke 完成后由 TL/ops 核对归属，仅删除这个准确名称及其虚构对象；不使用宽泛 schema 匹配或清理其他实例对象。

模型密钥已在 ops 进程环境中确认存在（仅布尔检查）；不代表真实模型已经验证。后端集成通过后，从**集成工作树**运行以下内存配置启动方式。命令不会保存私有配置或输出凭据，也不会创建 schema：

```sh
python3 - <<'PY'
import os
from pathlib import Path
import sys
import run
base = Path(os.environ.get('DSH_JAVA_HOME', run.DEFAULT_BASE))
# 获取已有本机数据库凭据；先去除可能指向其他库的显式覆盖。
source = dict(os.environ)
for key in run.DB_KEYS:
    source.pop(key, None)
env = run.runtime_environment(source, base)
env.update(
    GARDEN_DB_URL='jdbc:postgresql://127.0.0.1:15440/postgres?currentSchema=ailiao_profile_smoke_0e2d7c0a95d2',
    GARDEN_PORT='48749', WECHAT_HISTORY_EMBEDDED='0')
os.execve(sys.executable, [sys.executable, str(Path('run.py').resolve())], env)
PY
```

预检时仅在最后参数列表加 `'--check'`。首次真实启动前保留 `--check` 验证，然后移除；此处没有任何真实凭据字面值。Java 实际日志应留在交互终端，若另行保存，按私有运行记录管理而非提交。
