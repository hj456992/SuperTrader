# 2026-09-25 本地发布准备与执行记录

状态：**TL授权的实际入口切换已完成，运维验证通过，静态资源已交前端独立复核**。以下准备与切换步骤保留阶段语义；执行事实见末节。全程未运行真实模型业务测试。

## 固定版本、入口与现场

- 发布源固定为`4754cb10e45478a1347c1b1a49e5078829f86f59`。集成树后续增加的本批计划、QA等文档/测试不等于新生产制品。
- 原目录`/Users/hou/Documents/Codex/2026-09-22/garden-product-design/outputs/demo`，main=`b410f0f321bae43a921cda70f6375277434d2f7c`，工作树干净；已确认是候选祖先，可以快进。
- 原入口`启动Demo.command`可执行，内容是切换到自身目录并调用`python3 run.py`；新候选保留此入口。
- 旧进程PID99465，PPID1，工作目录为原目录，启动于2026-09-24 11:49:05本地时间；命令仅核对宿主JAR和bootstrap位置，未打印环境。
- 宿主为既有`/Users/hou/Documents/Codex/projects/dsh-java/app-boot/target/dsh-java.jar`，bootstrap为原目录`.runtime/bootstrap.yml`，GARDEN_WEB为原目录`web/dist`。现有JAR/UI修改时间早于进程启动。
- 用户launchctl作业列表没有PID99465对应条目，原父shell已退出，不能从PPID1还原历史终端。没有新增自动服务/重启器。
- 通过macOS进程参数接口**仅在内存**读取实际运行配置，定位为既有PostgreSQL `127.0.0.1:15440/garden_demo_20260922/public`。两张业务表；未输出数据库用户名、密码或模型凭据。
- 模型与数据库必需凭据在旧进程中均存在；当前运维进程模型key与原实例一致（只核对布尔值），launchctl全局模型key不存在。原脚本不保存密钥，手动重开入口仍需具有既有模型环境的终端；本批不增加密钥落盘或全局环境写入。
- 当前embedded微信历史关闭。不会顺带重启/接入独立日志或采集服务。

准备时只读业务元数据：牧场revision39、人物4、书架1、本人物料0；旧会话people4；牧场/旧链路job均idle。真实HTTP响应只在内存处理并输出计数/版本/状态，不展示人物、聊天或书籍内容，不替用户刷新浏览器。

## 私有备份与制品

私有目录：

```text
/Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/ailiao-upgrade/ops/.local/local-release-20260925-015448
```

该目录Git忽略，权限0700；全部归档与manifest权限0600，不能作为公开附件或测试fixture。准备期间业务摘要前后相同，但这是**在线准备快照**，不是停旧之后的最终发布快照。

| 文件 | 内容/完整性 |
|---|---|
| business-preparation.dump | 既有业务库pg_dump自定义格式，20,048字节；未包含角色密码。pg_restore列目录与完整解压生成SQL到`/dev/null`均成功，未向数据库恢复 |
| source-old.tar | 固定旧提交的tracked源码 |
| artifacts-old.tar | 实际旧garden JAR、飞书JAR、完整web/dist、run.py/build.sh/原.command及旧bootstrap |
| source-candidate.tar | 固定4754cb1源码 |
| artifacts-candidate.tar | 已验收业务/飞书JAR与完整候选web/dist |
| dsh-compatible-artifacts.tar | 只读备份当前兼容DSH宿主与8个底座插件JAR，共9制品；没有改底座 |
| manifest.json | 各归档SHA/大小/权限、逐制品SHA、指定静态资源SHA、版本与脱敏数据库摘要，不含凭据/正文 |

全部tar成员路径已检查无绝对路径或`..`，并逐文件读取至结尾。数据库archive可完整解析，但**未做真实数据恢复演练**，不能将此说成已验证实际恢复。

关键SHA-256：

| 项目 | SHA-256 |
|---|---|
| 准备数据库dump | ac8125be286f7b6344984df2cf527df4b52bc48e0a0f313f699e3026ff1e4a5e |
| 旧garden-demo.jar | 21ae7ed3d49e7650511707de81761eef45f18661bc88e786f37a22e57285ef49 |
| 候选garden-demo.jar | a9a873e917958fdbc8f482f0c0dba5962ca0458b1960186f741ba459f4438711 |
| 旧ranch-ui.js | 4b697776205b0d3033c32c9549b094ec65f87cc9e57168545a65c651dbfbacaf |
| 候选ranch-ui.js | d729a1b1c6534687b6125f62fdc81e8de5935cdead4cb8602bff83305138294a |

候选JAR与上一轮真实时间修复验收完全同hash；`d62253a→4754cb1`的`src/main/plugins/web/run.py/build.sh/pom.xml`无差异。本轮未重建JAR覆盖已有制品。实际无业务DB、无模型调用的9插件宿主探针再次通过configured9/active9，工具使用虚构key；这不是新业务库双实例测试。新业务完整10插件启动仅在停止旧实例后进行。

## 切换步骤（等待TL指令）

1. **最终门槛**：TL确认固定候选及QA/前端清单；提示用户先保留未提交输入，切换后会话Cookie失效需重开，但不擅自刷新其页面。原目录必须仍干净、HEAD仍b410f0f，原PID启动时间/cwd/bootstrap仍吻合；出现变化停止覆盖并报TL。
2. **重新取证**：重读两条链路job元数据，任一running/cancelling则不停止，等其终态并向TL说明。核对全部候选/外部制品hash与私有manifest；真实运行环境再次取入内存。当前应用无维护只读开关，最后检查到停止之间仍有用户提交窗口，需TL协调短暂避免提交。
3. **单实例停止**：只对已核对的旧PID99465发送SIGTERM，等待退出；不使用pkill/killall，不强杀挂起实例。超时报告TL。确认48740无listener且普通bind成功，TIME_WAIT可自然等待，不放宽端口检查。
4. **最终静止备份**：旧实例已停后，以其实际数据库身份在既有容器内执行`pg_dump -Fc --no-owner --no-privileges`，输出到同私有目录的新文件`business-cutover.dump`，mode0600；再做list及完整解析到/dev/null、SHA验证。保存此刻两业务表的行数/摘要和牧场revision为**最终回退判据**。不输出真实内容。任何备份失败先恢复旧制品/进程，不继续部署。
5. **源码快进**：仅在上述条件成立后执行：

   ```sh
   git -C /Users/hou/Documents/Codex/2026-09-22/garden-product-design/outputs/demo merge --ff-only 4754cb10e45478a1347c1b1a49e5078829f86f59
   ```

   不reset、不强推、不改其他角色树；失败停止并排查，不能强制覆盖。
6. **成套制品**：从已校验的`artifacts-candidate.tar`在私有目录展开，确认garden/feishu JAR及整个web/dist。对原目录目标JAR先写同目录临时文件再rename；整个web/dist以暂存目录置换，保留换下目录在私有发布目录。绝不只换一个JAR或现场用旧共享Maven缓存临时构建。核对外部DSH9制品hash未变，不修改底座。
7. **原入口启动**：内存环境继承实际旧进程配置，保持数据库/模型/48740和原路径。通过原`启动Demo.command`（zsh，原cwd）启动；持有本次进程对象并核对实际监听Java PID。输出经内存管道保留有界日志，只提取ready/异常类；不写密钥或全量业务日志。run.py会生成原路径bootstrap并使用10插件，不能在此之前让候选连接同库。
8. **发布验证**：确认configured10/active10、实际cwd/宿主/新bootstrap、JAR与静态资源hash；只读取得新Cookie与业务计数/版本/状态，核对两业务表摘要与最终静止备份一致。不启动模型、不导入、不编辑真实数据。已存画像不会自动重生成或修正文案。
9. **前端与TL复核**：交付原GARDEN_WEB绝对路径、实际监听PID和静态文件SHA。前端仅HTTP GET资源检查MIME/no-store/hash，不加载会自动请求人物的动态页面；TL独立核对。记录最终source/JAR/运行PID、数据摘要是否相同。

## 精确回退边界与步骤

**未出现新业务写入，且state/book及旧garden_person摘要仍与停旧最终快照完全一致：** 可以回旧源码和旧运行制品，继续使用同一个现存数据库，默认无需恢复数据库。先停止本次持有的新实例、确认端口释放；工作树必须没有用户改动，然后：

```sh
git -C /Users/hou/Documents/Codex/2026-09-22/garden-product-design/outputs/demo switch --detach b410f0f321bae43a921cda70f6375277434d2f7c
```

这是明确的旧版临时回退，main引用保留候选，不强改分支。再从`artifacts-old.tar`私有暂存恢复准确旧garden/飞书JAR、完整web/dist和旧run.py/入口/bootstrap（tracked脚本应已由checkout恢复，先比hash）；保留换下的新制品。旧源码/JAR/UI须同套，外部DSH使用原兼容制品且hash相同。用仍在内存的原环境运行同原入口，确认4插件ready、原元数据/摘要、旧静态资源hash。结果报TL，由TL决定后续分支安排。

**任何新业务写入或摘要变化：** 不自动回数据库快照、不把旧版写行为当等价；先停止本次新写入进程并保护当前数据，再报TL决定向前修复或受控回退。旧版本删书不会撤销新画像/历史书摘引用，也无强制只读模式，“口头只读”不能作为旧版安全运行保障。换旧JAR不会找回新版已清除的历史；也不能为了回退丢弃新写入。

数据库恢复如确有必要，必须另外明确恢复点、会丢失的变更、数据保留与核验方案，获得TL决策后执行；本准备不执行任何restore/clean/drop业务对象。

## 本批风险与验证边界

- 本机同库任务锁仅进程内，严禁新旧宿主同时连接并提供业务写入。
- HTTP state与job独立读取、结果与终态分步提交；不能把interrupted直接等同未保存，核对业务revision/profile.runId及摘要。
- 凭据只在进程环境/内存中，未另造持久凭据配置。运维监督进程若丢失，应在停止旧实例前重新读取并核对实际配置；不能猜连接信息。
- 在线准备快照不是最终停写快照。自准备时出现新修改不丢弃，切换前重新备份并更新判据。
- Cookie会变，用户未保存表单不在数据库备份内；不给用户页面自动刷新。
- 候选生产验证继承此前隔离真实模型验收，本批在真实业务库只做数据保护与只读发布检查，绝不把真实材料当测试夹具。


## 已执行切换与验证

TL审阅准备方案、独立核对备份权限与SHA，并在完整release Java88/Node61通过后明确授权本次切换。固定发布源仍为4754cb1，没有将额外QA/规划文档混入实际部署源。

1. 最后重新核对原PID99465的实际argv/数据库配置、PPID、原目录HEAD和无用户文件改动；两条业务job均idle。模型与数据库值只在内存比较，没有输出或保存。
2. SIGTERM旧PID99465，正常退出；没有强杀。确认48740无监听并等待普通bind成功，没有新旧实例同时服务业务库。
3. 停旧后两业务摘要与停止前一致，完成`business-cutover.dump`最终静止备份，20,048字节、0600。SHA-256为`5dcfa83e51d7254b7793483e9fe460d74181588b4df77bace5a3ea4adac48a62`；完整解析到/dev/null与list均通过，未执行数据库恢复。
4. 原main从b410f0f快进到`4754cb10e45478a1347c1b1a49e5078829f86f59`。恢复的是已校验候选成套JAR/UI，原完整web/dist保留在私有发布目录`old-dist-before-cutover`；未丢弃源码/旧制品/截图。原目录Git保持干净。
5. 使用实际原目录/实际继承环境先执行`run.py --check`，退出0；随后由原`启动Demo.command`（zsh）启动，launcher PID10024，实际Java/listener PID10025。
6. 宿主configured=10、active=10；监听127.0.0.1:48740。实际bootstrap和GARDEN_WEB均指原目录；bootstrap权限0600。实际业务JAR SHA仍为`a9a873e917958fdbc8f482f0c0dba5962ca0458b1960186f741ba459f4438711`。
7. 用新Cookie只读取得元数据：前后均revision39、牧场人物4、书架1、旧链路people4、两job idle；两业务表state/book/legacy文档摘要与最终静止备份完全一致。没有模型调用、导入、修改、删除或数据库恢复。
8. 已向TL及前端交付实际`outputs/demo/web/dist`绝对路径、源SHA、PID及10项静态资源预期hash，等待独立静态-only检查与TL复核；没有代用户刷新页面。

测量说明：启动已经ready后，运维内存校验脚本中`http`局部变量遮蔽模块名，构建新CookieJar时出现AttributeError；仅改为独立模块别名继续只读校验，没有重启实例、修改生产、重发业务请求或延长已恢复服务的状态。数据验证随后完整通过。

本次没有触发回退，不能声称已实际演练旧制品回退或业务数据库恢复。私有目录保留全部准备/最终快照、源码与制品归档、逐项校验manifest。发布后出现的新用户写入必须保留，后续不能依据本轮旧快照自动覆盖。
