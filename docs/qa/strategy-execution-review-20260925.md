# G01 攻略执行保障：独立 QA

本批独立于已授权本地发布候选4754cb1。范围依据为TL采纳的33d0b08产品规格与后端c3abf1f实施计划。仅使用已有QA工作树、虚构材料、真实DefaultModelRegistry和原始Publisher；不连接真实模型、数据库、平台或原运行实例。

## 当前状态：独立基线红测已完成，修复待验

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

后端固定包级 `StrategyRuntime(models,Duration)`、独立 `Run.begin/cancel/check/generate/close` 和Analyzer五参构造注入接口。QA已接受固定50000/20000上限，不要求新的公共配置。当前测试先使用既有真实Analyzer入口；收到实现的准确SHA后，继续接入短Duration、输出边界和保存竞态用例，再用同一原始取消断言复验。尚无G01修复通过或真实模型结果。
