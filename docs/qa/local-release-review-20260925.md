# 本地实际入口发布：独立 QA 审核

日期：2026-09-25。固定生产候选 `4754cb10e45478a1347c1b1a49e5078829f86f59`；原运行版本由 TL 确认为 `b410f0f321bae43a921cda70f6375277434d2f7c`。本报告不授权 QA 上线，也不表示已经切换成功。

## 结论

**确定性发布门槛通过，未发现必须改生产代码或迁移旧数据的阻断。可交 TL/运维按下述单实例、备份和制品条件切换。** QA 未读取业务数据库、真实聊天或密钥，未请求模型、启动服务、停止原进程或实施回退。实际备份、制品、数据库目标及切换后的检查仍须由执行发布的角色完成；任一条件失败即停止切换，不能用本报告代替现场确认。

与已验收最终集成 `dd75595b467d579edc5756e674494f7e7a24ee60` 比较，4754cb1 **只有 README.md 变化**。QA 工作树原先干净，通过普通 merge 接入候选并保留双方历史（合并提交 `c83b60c`），未 reset；合入后的完整受跟踪树与4754cb1相同。新增内容仅为本报告和合成测试，不改变发布制品。

## 本次执行证据

| 检查 | 新执行结果 | 范围 |
|---|---|---|
| `tests/acceptance/run.sh release` | Java86/86、Node60/60，零失败/错误/跳过，退出0 | 含明确独立 gates、真实 AgentScope 调度+模型传输替身、provider serializer；无真实模型 |
| `python3 -m unittest discover -s tests/ops -v` | 15/15，退出0 | 临时目录、虚构凭据、启动替身、smoke协议控制用例 |
| `zsh -n build.sh` | 退出0 | 构建脚本语法；本轮未重建飞书或完整发布包 |
| `ProfileAgentAcceptanceReleaseTest` | 新增2/2，零失败/错误/跳过 | 终态无额外写入、重启写入次数、业务已保存但job未结束窗口 |
| `rollback-profile.test.mjs` | 新增1/1，零失败/跳过 | 从Git读取准确旧版 renderer，实际渲染含新字段的纯虚构文档 |

完整release先执行，新补2 Java+1 Node随后定向执行；不能将两次执行写成“已跑一次88/61完整release”。现有 release 自动纳入新增测试，旧renderer测试要求Git中保留准确b410f0f对象。所有 Maven 命令使用已有兼容私有缓存，且仅写QA自己的target：

```sh
MAVEN_OPTS='-Dmaven.repo.local=/Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/ailiao-upgrade/backend/.local/m2' tests/acceptance/run.sh release
/Users/hou/.local/apache-maven-3.9.16/bin/mvn -o \
  -Dmaven.repo.local=/Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/ailiao-upgrade/backend/.local/m2 \
  -Dtest=ProfileAgentAcceptanceReleaseTest test
node --test tests/acceptance/rollback-profile.test.mjs
```

日志在忽略目录：`target/qa-local-release-20260925.log`、`qa-local-ops-20260925.log`、`qa-local-release-extra-20260925.log`、`qa-local-rollback-20260925.log`。

## 原数据加载与启动写入

- 对比旧/新 `RanchStore`：仍为 `garden_ranch_documents(id,document)`，`state` 和 `book:ID` 不变，按 JSON ObjectNode 读取，无全量映射/迁移。旧 `garden_person` 的 Store 源码无差异。已有字段不因加载而丢弃，旧画像新可选字段缺失仍能渲染/校验。
- 启动本身不是纯只读：Store/RanchStore仍执行既有 `CREATE TABLE IF NOT EXISTS`、`INSERT state ... ON CONFLICT DO NOTHING`。正确原库已有state时不覆盖；错误目标为空时会初始化空state，所以**看到空页面不能当作正常升级**。
- 新增 `profile-job` 是同表独立文档。缺失时 `readJob` 返回idle，不插入；已有idle/done/error/cancelled/interrupted在启动和正常关闭均不写job。新增测试逐一验证，也比较完整业务state和书籍不变。
- 仅已有running/cancelling在构造Analyzer时转interrupted，并写一次profile-job；后续重启幂等，不增加业务revision，不自动恢复模型请求。已有测试与新增测试均覆盖两种活动状态。
- 业务结果保存与job终态写入不是一个事务。若前者已成功、后者未完成就停机，重启可显示interrupted但画像已经保存；新增测试保留这一合成窗口中的完整state、runId、revision。发布验证应比对profile.runId/basedOnRevision/业务revision，不能据interrupted重复生成或认定结果未保存。
- 老材料at仍在存储；spokenAt只在确证新来源写入。旧记录无spokenAt按日期未知提供给模型，不回填旧日期、不重生成旧画像；已保存旧措辞不会自动修正。旧对象RQ-02 P3的未重跑范围继续保留。

以上为源代码审查和合成内存Repository/renderer测试，不冒称真实业务库恢复演练。旧数据到新生成、书架删除、旧画像渲染分别由已执行的RuntimeTest、AcceptanceTest和legacy-profile测试覆盖；无需把真实聊天复制进测试。

## 发布执行清单（TL/运维）

1. **冻结与退路**：确认源4754cb1、本地改动情况、旧b410f0f工作树及旧制品可用；保留旧入口配置。确认目标数据库/schema与原实例一致，并取得权限受控的发布前备份及摘要/恢复方法；不得将内容或凭据放入仓库。本批不新建业务库，也不复用已清理的smoke schema。
2. **成套制品**：使用兼容Maven缓存构建app、UI和既有插件；记录制品摘要。DSH host、ModelRegistry、Agent/Session/Tools等必须为已验收的兼容一组，不能只热替换garden.jar或混入共享过期SNAPSHOT。宿主装配应为配置的10个插件全部active（若明确保留原嵌入式微信配置，按实际清单另核，不能盲按10计）。
3. **预检与端口**：Java>=17，app/UI/插件/采集脚本存在；数据库三变量同时非空或使用已核对的原默认路径；模型环境已配置。`run.py --check`只验证本地条件，不证明DB/模型有效。原48740正在占用时预检应拒绝，不能因此误判新代码坏；停止旧实例后在实际目标端口再次预检。仅停已确认归属的原PID，不使用全局kill。
4. **单实例切换**：先停止新分析/写入、确认无活动job并核对业务revision，正常停止旧写入进程并等退出，再连接原库启动新版。不同端口不等于数据隔离；任务锁在进程内，同一profile-job键不能由新旧实例并行管理。
5. **加载验收**：核对服务ready、装配、实际页面入口及assets；与发布前摘要比较人物数、材料数、书架、当前/历史画像和业务revision。只读访问不得触发模型、导入、自动保存。缺失/串主体/乱码/旧UI缓存造成新交互失效均先停止开放写入。检查当前job，若interrupted按上述保存窗口核对，不自动重跑。
6. **实际使用入口**：本人/对象切换、当前及历史画像、聊天依据/方法依据展开、旧画像显示、目标入口应可用。发布核对不点击生成、确认删除或平台导入，不用真实内容测试；需要额外写入验证时先交TL决定，仍用独立合成材料。该轮不重复真实模型smoke。
7. **交付记录**：发布角色记录新PID、端口、制品摘要和前后业务元数据结论，确认旧进程不再写入。任何失败保留脱敏阶段与私有日志；QA此报告不代替实际切换结果。

## 回退触发与兼容限制

| 触发 | 处理 |
|---|---|
| 构建/装配/依赖不匹配、预检失败、备份或目标库不明 | 不停止原实例/不切换；先补齐条件 |
| 新版不能启动、API不可用、旧资料缺失或未经授权业务revision变化 | 停止新写入，核对目标与备份摘要；由TL决定回旧制品或修新版，不以空库继续运行 |
| 无新业务写入且元数据/摘要一致 | 可停止新版并恢复原成套制品/入口，保留新增job文档；旧版按id读取state/book，忽略profile-job，无需自动删表/删文档 |
| 已有新业务写入、发现引用撤销差异或内容不一致 | 不自动恢复发布前DB快照，不丢弃新增记录；先保留数据并优先修新版本，再由TL判断回退路径 |

旧renderer实测可读取含runId/version/knowledgeStatus/knowledge/counterEvidenceIds/spokenAt的新合成数据并保留对象，但不展示新方法详情；**这只证明读取兼容，不是写入等价**。旧版RanchKnowledge删书只清攻略，不会清新版画像及历史画像中的书摘引用，故不能让回退旧版后的删书被当成新版撤销语义。旧版也不具有强制只读模式；若应急查看旧版，需明确操作约束或隔离副本验证，不能把“只读回退”说成已有产品功能。

## 下一最小批次建议

完成本批入口切换后，优先单独处理“业务已保存但job终态失败/重启”时的可辨认提示与事实一致性（现有文档/runId即可），并保留旧版回退写入限制。旧对象RQ-02措辞按下一次正常更新观察，不为关闭低优先级项强制重跑所有真实画像。不建议本批增加多Agent、后台订阅、全历史导入或新服务。
