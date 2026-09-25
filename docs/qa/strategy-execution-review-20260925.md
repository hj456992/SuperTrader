# G01 攻略执行保障：独立 QA

本批独立于已授权本地发布候选4754cb1。范围依据为TL采纳的33d0b08产品规格与后端c3abf1f实施计划。仅使用已有QA工作树、虚构材料、真实DefaultModelRegistry和原始Publisher；不连接真实模型、数据库、平台或原运行实例。

## 最终结果：确定性门槛通过，未发现新增阻断

后端实现 `e0dea148d7736be192f0506f9be671fe37ca642e` 接入QA后，独立三类 **16/16通过**；完整 `tests/acceptance/run.sh release` 实际退出0，**Java124/124、Node61/61，0失败/错误/跳过**。这包含既有画像、原gates、来源时间、provider serializer及回退读取门槛，不把画像证据外推为攻略取消证据。

QA被测 `src/main` 与e0dea14完全一致；相对已上线4754cb1，生产变化仅 `RanchAnalyzer.java` 与新增 `StrategyRuntime.java`。QA保留4754的前端/启动器，因此不能称整个QA树与后端分支相同。接入时缺少前置评估/计划文档和复现测试旧文件造成cherry-pick基线冲突，均取e0dea14对应完整文件解决，没有另改生产实现。

| QA测试类 | 执行数 | 实际覆盖 |
|---|---|---|
| ProfileAgentAcceptanceStrategyTest | 4/4 | 原始source-cancel先于持久cancelled、取消后新run可用；prepare Future未完成时先结束，再迟到零订阅且不污染新任务；真实阶段顺序、新runId/basedOnRevision、旧画像/攻略保留；首次错误引用恰修复一次、整数6000 |
| ProfileAgentAcceptanceStrategyLimitsTest | 7/7 | 两次共享2秒总期限；连续chunk不续期；检索450ms预算耗尽/取消后零prepare；prepare350ms到期后迟到零订阅；50001字符/20001事件无finish即取消且不修复；missing finish/length/aborted/error/传输异常不修复，非法JSON最多2次；精确50000字符/20000事件仍成功且block-end不重复拼文本 |
| ProfileAgentAcceptanceStrategySaveTest | 5/5 | 第二次修复中取消且迟到有效响应零保存；完整响应已到但cancel先占监视器则零保存；保存先占监视器则并发cancel不能改done，仅保存一次；revision改变拒绝旧结果；mutation前预算耗尽拒绝已校验结果 |

首次候选运行16项中15通过，唯一失败为QA将JSON IntNode(7)与LongNode(7)按节点类型判不等；协议只要求整数及相同值。修正为 `isIntegralNumber()` + `asLong()` 相等，未要求后端修改正确的long字段。随后上述完整release覆盖修正后的全部16项并通过。初次日志 `target/qa-g01-independent-candidate.log`、最终 `target/qa-g01-full-release.log` 保留在忽略目录。

只读审查确认：接纳时创建独立Run，worker唯一begin建立deadline；检索前后、每次prepare、收集Mono及保存锁内/mutation检查同一预算；取消用完成信号撤销上游，原Future允许迟到但不派发；纯校验异常才进入第二次尝试；旧Run清理按对象/任务身份，不清新Run状态。业务保存仍由Analyzer完成，Runtime不持久化，不改公共底座、画像工具循环、引用规则、来源或数据库布局。

**保证边界：** 2秒/450ms等为确定性测试注入预算，生产默认仍110秒。原始onCancel证明本地订阅释放，不证明供应商端已停算；PreparedCall没有dispose，同步JDBC仍受既有连接/socket超时约束。已进入真实数据库提交的回收边界、state/job分步保存故障不由本批修复。未package、部署、连接真实模型/数据库或访问线上实例；自然语言结果质量和实际攻略模型调用不以本轮替身测试代替。是否做有界真实smoke或部署由TL单独决定，不把G01通过写成4754线上已更新。

完整复验命令（同一私有兼容缓存、QA自己的target）：

```sh
MAVEN_OPTS='-Dmaven.repo.local=/Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/ailiao-upgrade/backend/.local/m2' tests/acceptance/run.sh release
```

## 历史：独立基线红测

被测生产代码与4754cb1一致。新增 `ProfileAgentAcceptanceStrategyTest` **4项 / 1通过 / 3失败 / 0错误 / 0跳过**，测试耗时4.544秒、Maven退出1。此为缺陷复现，不能作为G01发布通过依据；本测试匹配普通`*Test`，请勿将此红测提交混入已验收4754发布候选。

| 独立用例 | 基线观察 |
|---|---|
| 静默源取消先于持久终态，并能开始下一任务 | 原始Flux.create.onCancel在2秒内未到；断言失败后才发应急complete。后续终态/新任务断言未到达，未声称通过 |
| prepare未完成时取消先结束，迟到结果零订阅 | 保持Future未完成，cancel后2秒仍cancelling；finally才完成Future清理。后续迟到零订阅/新任务不污染断言未到达 |
| 实际阶段、结果身份与旧资料保留 | 生成完成但只有preparing:0/knowledge:0/saving:0，缺reasoning:1/validating:1；后续附加身份/旧资料断言仍待修后验证 |
| 首次引用错误，只修复一次 | 通过：实际2次调用，maxTokens为整数6000，最终仅1次保存 |

```sh
/Users/hou/.local/apache-maven-3.9.16/bin/mvn -o \
  -Dmaven.repo.local=/Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/ailiao-upgrade/backend/.local/m2 \
  -Dtest=ProfileAgentAcceptanceStrategyTest test
```

原始日志：`target/qa-g01-independent-red.log`（忽略目录）。JUnit外层12秒，受控等待2秒；应急清理只在finally，不用其结果抵消取消失败。后端原4项复现报告另外只读核对，不混计为QA执行数。

## 独立验收预言与接口交接

1. `source-cancel` 必须先于持久 `cancelled`，且两者均发生于测试应急完成前；原策略、画像、revision深相等。旧run/重复cancel不影响新任务。
2. 未完成prepare取消后，本地worker先结束；旧Future随后完成仍零stream订阅，不改变新任务状态。只承诺本地等待和订阅边界，PreparedCall无dispose，不声称底层准备网络被撤销。
3. deadline从worker入口开始，贯穿检索、准备、两次调用、校验和保存前。检索耗尽预算后零prepare；同步检索取消后可等原有阻塞返回，但返回后不得继续调用模型。
4. 第一次非法引用消耗预算后，第二次静默流只能使用剩余时间，期限届满触发原始onCancel，无第三次尝试/保存；连续chunk不能重置总时限。
5. 50,001字符或20,001事件在没有finish时也必须及时撤销源；取消、超时、传输异常、非stop终态、输出超限均零修复。只有JSON/结构/引用失败可重试一次。
6. reasoning/validating的1/2步与实际请求、校验顺序一致；saving只在成功校验后。每次整数6000，新攻略附runId/basedOnRevision，旧策略/画像不回填。
7. 保存前取消或revision改变零提交；保存先获得屏障并成功则仍done，仅1次保存。闸门控制先后，不使用sleep猜竞态；G01不宣称解决state/job分步持久化恢复窗口。
8. 保留目标、双方材料/自述、对象画像、书籍方法和原攻略引用规则；不要求本人画像，不接入self.profile，不迁移Agent，不自动发送消息。

后端固定包级 `StrategyRuntime(models,Duration)`、独立 `Run.begin/cancel/check/generate/close` 和Analyzer五参构造注入接口。QA接受固定50000/20000上限，不要求新的公共配置；最终已通过该接口注入短Duration完成上列用例，原始取消门槛没有弱化。本文保留红测观察用于追溯，最终状态以前述结果为准。
