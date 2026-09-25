# G01 攻略执行边界 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `executing-plans` to implement this plan task-by-task after the sole execution TL approves production implementation. 使用现有团队与工作树，不新建子代理，不自行执行发布。

**Goal:** 保持攻略直接 ModelRegistry 路径，在最多 2 次模型调用内实现可核验的取消、110 秒应用总预算、流期间输出上限及真实阶段，并保持迟到结果零保存。

**Architecture:** RanchAnalyzer 管理任务身份、冻结输入、检索与保存屏障；应用内 StrategyRuntime 管理单次攻略的取消信号、统一 deadline、直接调用与结构／引用修复。每个任务有独立 Run，贯穿检索前到保存前；不建立 Agent 或另一套工具循环。

**Tech Stack:** Java 17、现有 DSH ModelRegistry、Reactor Publisher／Mono／Flux、Jackson、JUnit、现有 RanchRepository。所有验证用合成材料及已有依赖。

## Global Constraints

- 本提交只获准复现测试及实施计划。生产实现必须等唯一执行 TL `01a0d244-0bdd-71e2-b824-6ac31bb31078` 明确批准，固定发布候选 `4754cb1` 不随本文改变。
- 最多 2 次模型调用 = 首次 + 至多 1 次结构／引用校验修复；不是两次额外修复。每次整数 maxTokens=6000。
- 应用层 110 秒总预算，从 Analyzer worker 入口开始，包含检索、异步准备、本次及修复调用、校验和保存前检查。同步 JDBC 仍受现有 connect/socket 超时约束；不能承诺已进入 JDBC 的调用即时回收或回滚已提交结果。
- 最多 50,000 个 Java 字符（UTF-16 code units，与现有 String.length 口径一致）；最多 20,000 个流事件，全部类型计数。上限在消费时检查，超过即撤销订阅。
- 不调用真实模型、数据库、平台聊天；不把真实聊天作为夹具。不改公共 DSH、共享／私有 Maven 缓存，不新增依赖／服务／数据库。
- 不新增 Agent、工具、来源同步、事务恢复、检索升级或新前端功能。不切换原实例，不 package／替换发布制品，不 push。
- 保留目标与双方材料、自述、既有对象画像、书籍检索及输出结构，不要求本人画像，不把 self.profile 加入攻略。旧记录不回填；新攻略只附加 runId／basedOnRevision。

## 依据与已完成复现

基线后端生产 Java 与 `4754cb1` 相同。输入依据：`0a734ca:docs/backend/architecture-gap-assessment-20260925.md`、产品 `33d0b08:docs/product/architecture-gap-backlog-20260925.md` 的 G01，以及 TL 对“首次+一次修复”和 110 秒预算的澄清。

`src/test/java/dev/garden/StrategyExecutionReproduction.java` 使用真实 `DefaultModelRegistry`，仅替换外部 Adapter 与内存 Repository，不复制生产循环。类名有意不匹配普通 Surefire `*Test`；只有以下命令显式运行红门槛：

```sh
/Users/hou/.local/apache-maven-3.9.16/bin/mvn -o -Dmaven.repo.local=.local/m2 -q -Dtest=StrategyExecutionReproduction test
```

| 用例 | 当前结果与直接证据 |
| --- | --- |
| silentCancelMustReleaseOriginalProviderAndAllowAnotherTask | 红：2秒内原始 Flux.create.onCancel 未收到，job 仍 cancelling；关键断言在应急 sink.complete 之前 |
| cancelledPreparationMustNotSubscribeItsLatePreparedCall | 红：cancel 已获准、异步 prepare 后完成，仍订阅 1 次，预期 0；现有保存屏障仍阻止保存 |
| completedStrategyMustRecordActualReasoningAndValidationPhases | 红：实际持久阶段只有 preparing:0、knowledge:0、saving:0、finished:0，缺 reasoning／validating |
| invalidFirstCitationCanAlreadyBeRepairedByExactlyOneSecondCall | 绿：第一份未知引用被拒，第二份合法引用保存，恰 2 次调用，整数 6000 保持 |

四项共 3 failures、0 errors、0 skips。每项有 10 秒 JUnit 外层边界，源清理后最多等待 3 秒；没有等待生产 110 秒或制造挂起的真实请求。首次基线普通 `mvn ... test` 56 项通过，红门槛未被自动纳入；这是当前后端树结果，不冒充 TL 全量 86 项。

**尚未运行复现的边界：** 两次调用跨越总时限。当前生产硬编码每次 block(110s)，没有 Duration／时钟注入；源码表明 prepare 最多15秒和 stream 最多110秒在两次尝试中重置，但本轮不为证明它耗时数分钟，也不修改生产常量／反射改时钟。Task 2 使用短 Duration seam 验证统一预算，不把源码推演写成已完成运行测试。准备阶段“未完成 Future 时本地先结束”、源释放先于终态持久化也由下列独立门槛进一步覆盖。

根因定位：RanchAnalyzer.cancel 只对非空 agent 调 cancel，而攻略分支没有 agent；generate 只向 Registry 传轮询 BooleanSupplier，没有订阅取消桥接，也没有 prepare 返回后的停止检查；阶段只在分支外记录 knowledge/saving。实际 DefaultModelRegistry 保留一次性 PreparedCall 及错误转 finish 合同，并不会替应用为静默源主动订阅取消信号。

## 文件结构与精确接口

| 文件 | 职责／变更范围 |
| --- | --- |
| `src/main/java/dev/garden/StrategyRuntime.java`（新增） | Run 控制对象、有界直接调用、流文本收集、JSON／引用校验；不读写业务库 |
| `src/main/java/dev/garden/RanchAnalyzer.java` | 接纳时创建 Run、worker begin、检索前后检查、取消／关闭转发、保存屏障、真实阶段与结果身份 |
| `src/test/java/dev/garden/StrategyExecutionReproduction.java` | 已提交的红门槛；实现阶段改名为 `StrategyExecutionTest.java` 纳入普通套件，保留原断言 |
| `src/test/java/dev/garden/StrategyRuntimeTest.java`（新增） | Duration seam 下超时／两次共享预算／输出／协议／取消顺序测试 |
| `src/test/java/dev/garden/RanchJobTest.java` | 攻略保存竞争、身份、旧数据、检索预算及关闭；必要合成辅助仅放测试侧 |
| `docs/backend/architecture-gap-assessment-20260925.md` | 实现后仅记录 G01实际交付及限制，不顺带实施其余选项 |

拟定包级接口（以下只是文档，不是本轮实现）：

```java
final class StrategyRuntime {
    StrategyRuntime(ModelRegistry models); // timeout=Duration.ofSeconds(110)
    StrategyRuntime(ModelRegistry models, Duration timeout); // package-private QA seam
    Run newRun(BooleanSupplier externallyCancelled);

    @FunctionalInterface
    interface RevisionCheck { void check() throws Exception; }

    final class Run implements AutoCloseable {
        void begin(); // worker入口仅一次设置System.nanoTime截止时间
        void cancel(); // 幂等；原子标志+可重放的Sinks.Empty<Void>完成信号
        void check() throws TimeoutException; // 取消抛CancellationException；到期抛TimeoutException
        ObjectNode generate(ObjectNode input, ArrayNode passages,
                            ProfileRuntime.Progress progress,
                            RevisionCheck revisionCheck) throws Exception;
        @Override public void close(); // 幂等清理，撤销仍活动的本地订阅
    }
}
```

`Run.generate` 返回已验证的结果 JSON；不添加 id/createdAt/stale/knowledge/runId，不写 Repository。这些字段仍由 Analyzer 一处完成。`RevisionCheck` 在每次准备前检查当前 revision，查询异常直接失败，不能被当作模型结构问题修复。ProfileRuntime.Progress 仅复用已有三参数回调类型，不改画像实现。

RanchAnalyzer 保留现有 3／4 参数构造器，默认内部构造 StrategyRuntime(models)；增加包级 5 参数构造器 `(ModelRegistry models, RanchRepository store, RanchKnowledge knowledge, ProfileRuntime runtime, StrategyRuntime strategyRuntime)` 供 QA 使用短 Duration。生产 GardenPlugin 接线不变；不增加公开 HTTP 参数、环境配置或新服务。

把目前 private STRATEGY 提示迁入 StrategyRuntime；沿用 RanchAnalyzer.COMMON。迁移时只补攻略日期约束：有 spokenAt 才视为确证日期，无则未知；录入顺序不表示同日／频率；旧画像的时间概括不能替代原文日期。保持目标、双方资料、方法与事实分离，不进行自然语言分类器改造。

## 生命周期与预算定义

1. `start` 在同步接纳段创建独立 Run，external cancellation 捕获 token 并检查 `!active(token)`。取消信号从接纳起存在，deadline 尚未开始；任务入队后被取消不能再触发检索或模型。publish 失败则关闭并清理该 Run，不留下“活跃任务”引用。
2. 把 Run 作为最终局部变量传给 worker；worker 的 try 入口 `begin()`，在 RanchData.input、检索前后 `check()`。即使无模型调用，检索耗尽预算后也不得再 prepare。界面准备阶段的开始与实际 worker 开始对应。
3. 同一 Run 的 deadline 用 System.nanoTime，绝不在修复时重置。每次 prepare 本地等待上限为 min(15秒, remaining)。接收未完成 CompletionStage 的等待必须同时监听取消，取消后无需等该 Future 完成才能结束本地任务。
4. 原合同 PreparedCall 无 dispose 方法；本地等待取消不等于供应商底层准备资源释放。使用 `Mono.fromFuture(stage.toCompletableFuture(), true)` 允许原 Future 迟到完成，但消费方已解订阅；返回后与真实订阅前再 `check()`。旧 Run 的迟到准备结果不得调用 stream、保存或改新任务状态。
5. 用可重放的完成信号做 `takeUntilOther`，取消先发生也能观察到；不使用 `other.onError`。所有正常／取消／超时／输出超限路径都必须释放订阅。信号与分发并发时允许“零订阅”或“已开始订阅立即取消”，但已明确取消后才完成的 prepare 必须零订阅。
6. 成功 PreparedCall 后，在真正订阅的 defer 边界检查停止，记录 reasoning(step=attempt)，再进入单次 `call.stream(request)`。收集正常结束且 finish.kind=stop 后记录 validating，同步解析和校验；成功才交回 Analyzer。
7. Analyzer 在 saving 前、获得原保存监视器后、store.update 的 mutation 内均检查 Run。取消先经监视器获准则零保存；保存先完成保持 done。已进入 JDBC 的阻塞／提交仍有原有外部回收例外；不新增跨文档原子协议。
8. worker finally 先关闭 Run 再发布 cancelled；当前 Run 字段只在 `currentRun == thisRun` 时清除，避免旧 finally 清掉新接纳任务。cancel 捕获身份匹配的 Run，在锁外发取消；重复／旧 runId 无效，不干扰画像 agent。正常完成的清理也不能把 done 改成 cancelled。
9. close 使用同一取消路径并保持现有 executor shutdown；不引入额外常驻线程或外层重试调度器。持久化失败及“结果已写、job未写”故障仍按现有边界记录，G01 不承诺已修复恢复协议。

取消／超时的本地等待参考结构：

```java
// stop是每Run独占Sinks.Empty<Void>，cancel使用tryEmitEmpty，绝不error。
var prepared = Mono.fromFuture(stage.toCompletableFuture(), true)
    .takeUntilOther(stop.asMono())
    .timeout(prepareRemaining)
    .block();
check(); // null也必须先辨别取消／超时，不可落入结构修复
if (prepared == null) throw new CancellationException("攻略已停止");
```

实际方法中应使用具名剩余预算 helper，统一 TimeoutException 的拆包与分类；Reactor block 包装异常不能误进入结构修复。流的 `.timeout(remaining)` 放在最终单值结果 Mono 上，表示整个收集的剩余总时限；不要使用按每个 chunk 重置的 idle-timeout 冒充 deadline。prepare 同样不得在每次 chunk 延长期限。

## Task 1：取消桥接、迟到准备与真实阶段

**Files:** 新增 StrategyRuntime.java；修改 RanchAnalyzer.java；将复现文件改名 StrategyExecutionTest.java；新增 StrategyRuntimeTest.java。

**Interfaces:** 消费上文 ModelRegistry／RevisionCheck／Progress；产出 Run.begin/cancel/check/generate/close，并由 Analyzer 保存已校验 ObjectNode。

- [ ] 在生产改动前按显式命令重跑三个原红门槛，记录失败而非编译错误；保留恰两次引用修复对照。
- [ ] 新增准备中取消测试：返回未完成 CompletableFuture；wait preparing 后 cancel；不完成 Future 也在2秒内到 cancelled、零保存；随后完成 Future，等待完成回调栅栏后仍零订阅；再发新 run 并完成，旧回调不改变新结果。
- [ ] 新增顺序断言：合成 Repository 与原始 sink.onCancel 共用一个线程安全 trace；必须先有 provider-cancel，后有 persisted-cancelled，之后才进入 finally 的应急完成。避免把 sink.complete 引发的结束当成生产取消。

```java
// trace由测试的原始source和Repository.writeJob共同记录，不由生产伪造。
assertTrue(released.await(2, TimeUnit.SECONDS));
awaitStopped(analyzer); // 必须在应急sink.complete之前
assertTrue(trace.indexOf("provider-cancel") < trace.indexOf("persisted-cancelled"));
assertEquals(0, repository.saves);
```

- [ ] 实现上述 Run 接口；从 Analyzer 提取原 generate 的攻略分支。保存前后职责不移动到 Runtime，不保留无调用者的旧 profile 分支／重复攻略循环。
- [ ] `cancel` 对身份匹配的 Run 发信号，给异步准备等待和源订阅接同一信号；在准备返回、分发前与每个事件消费处检查；finally 关闭且按对象身份清空。
- [ ] 只在预备调用时记录 preparing，在分发边界 reasoning(step=1/2)，正常收集后 validating，Analyzer 再 saving；消息用“攻略／建议”，不写“正在保存画像”。
- [ ] 运行 `mvn -o -Dmaven.repo.local=.local/m2 -q -Dtest=StrategyExecutionTest,StrategyRuntimeTest,RanchJobTest,ProfileBudgetTest test`，新取消门槛及原画像取消／超时门槛均应通过；提交本单元。

## Task 2：单 deadline、流上限与严格两次调用

**Files:** StrategyRuntime.java、StrategyRuntimeTest.java、RanchJobTest.java。

**Interfaces:** 复用同一 Run，Duration 仅构造器注入；内部固定字符 50,000、事件 20,000；不加入新的 HTTP／环境配置。

- [ ] 写短总预算红测：Duration=2秒；第一调用由测试闸门耗掉约1.2秒后发完整但引用无效 JSON；第二调用是静默原始 Flux.create。断言第二次订阅发生、总期限后源 onCancel、零保存、没有第三次调用，且从 begin 到终态小于3秒。每项JUnit外层10秒；闸门 finally 释放。不能只检查第二次自己的2秒结束。
- [ ] 写检索耗尽预算用例：合成 Repository.book 阻塞闸门（非真实 JDBC），worker begin 后耗掉短预算再返回；断言 prepare 次数0，结果未保存。另测检索期间cancel，在其返回前可以仍cancelling，但返回后不可prepare。
- [ ] 写连续发少量chunk的超时用例：chunk间隔始终短于期限，但总时长超过期限；断言源被取消，证明不是按chunk重置idle timeout。
- [ ] 写文本与事件红测：原始 source 分别发总计50,001字符／20,001事件，不发finish；断言收到onCancel、本地等待结束、无修复调用、零保存。文本不追加超限片段；事件超限不能因usage/reasoning等非text事件而绕过。

```java
// 先注册源取消观察，再投递；无需finish，超限也应终止。
sink.onCancel(released::countDown);
sink.next(Map.of("type", "text-delta", "index", 0, "text", "字".repeat(50001)));
assertTrue(released.await(2, TimeUnit.SECONDS));
assertEquals(1, calls.get());
assertEquals(0, repository.saves);
```

- [ ] 将剩余预算贯穿 prepare、整体收集Mono、解析／校验返回和保存前；不以每次110秒替代。输出采用单个StringBuilder和计数器，不collectList保留全部chunks。每个事件先计数、检查取消／期限，再处理text增量。
- [ ] 只允许纯JSON解析失败或 RanchData.validateStrategy 的结构／引用异常修复一次。正常finish前无完整结果、error/aborted/length终态、传输异常、输出超限、超时、取消、revision/持久化异常均不修复。不要用覆盖整个调用的catch(IllegalArgumentException)混入基础设施错误。
- [ ] 补全协议表测试：无finish、finish非stop、传输异常、第一及第二份均非法JSON、第一引用非法第二合法、首份合法。对应调用数分别1/1/1/2/2/1；保存数分别0/0/0/0/1/1。每次通过真实 Registry 检查实际请求 maxTokens 是整数6000。
- [ ] 对协议stop后的完整文本保留原有Markdown fence兼容；不从不完整流或截断文本中猜出可保存JSON。不重复拼接 block-end 里的完整块与此前delta。
- [ ] 运行 `mvn -o -Dmaven.repo.local=.local/m2 -q -Dtest=StrategyRuntimeTest,StrategyExecutionTest,RanchJobTest test`。所有预算／输出／两次调用门槛通过后提交；测试时限与生产时限分开报告。

## Task 3：结果身份、保存竞争与兼容验证

**Files:** RanchAnalyzer.java、RanchJobTest.java；只有已有引用规则确需补用例才改 RanchDataTest.java。生产 RanchData／RanchStore／ProfileRuntime 不在本批改动范围。

**Interfaces:** 既有 `RanchRepository.update(long expected, Consumer<ObjectNode> mutation)` 不变；新攻略保留id并附runId/token、basedOnRevision/snapshot.revision，状态通过已有writeJob持久化。

- [ ] 使用有旧攻略、旧对象画像但无runId/version/knowledge的合成状态开始新攻略；成功后断言旧元素深相等、新元素含正确runId及basedOnRevision，旧画像不变、revision只+1，历史上限20保持。
- [ ] 在校验后的saving进度闸门暂停并先cancel，释放后断言update未提交、原策略/画像/revision不变；对第二次修复调用重复取消门槛。旧runId及重复cancel无额外取消或保存副作用。
- [ ] 独立合成 Repository 在实际接受update的保存屏障内阻塞，先让保存赢得屏障再并发cancel，放行后断言done和仅一次保存。测试线程/闸门全部finally清理；不能使用sleep猜谁先获得屏障。
- [ ] 运行中更新合成state revision，最终结果拒绝提交；记录的模型结果与旧策略不能混存。检索无书／无命中依旧允许生成有局限的攻略，不自动加模型调用。

```java
var oldProfile = repository.read().path("people").get(0).path("profile").deepCopy();
String runId = analyzer.start(repository.read(), "person-1", "strategy", "询问是否想散步").get("id").toString();
awaitStopped(analyzer);
var state = repository.read();
var saved = state.path("people").get(0).path("strategies").get(0);
assertEquals(runId, saved.path("runId").asText());
assertEquals(0, saved.path("basedOnRevision").asLong());
assertEquals(oldProfile, state.path("people").get(0).path("profile"));
assertEquals(1, state.path("revision").asInt());
```

- [ ] 在当前 `store.update` 的同步保存区域补Run检查与新结果身份字段；catch区分取消、超时、输出超限与一般失败。取消已获准保持cancelled，保存完成仍done；不宣称job/state分步写故障已解决。
- [ ] 跑普通后端 `mvn -o -Dmaven.repo.local=.local/m2 -q test`，原画像、工具、时间、主体与引用测试不得退化。移除显式红门槛命名后确认新的*Test被普通套件实际执行，无skip藏红。
- [ ] 交QA独立验证上述取消先后、late prepare、Duration预算、流上限、保存竞争与兼容；修复只响应具体失败，不扩展范围。
- [ ] 记录最终SHA、文件范围、测试数量／命令及剩余限制交TL；是否做一次有界真实攻略smoke及发布由TL决定，后端不自行发网络请求。提交实现与证据文档。

## 本计划审阅与交接

目前只提交显式红测和本计划，未新增 StrategyRuntime 或修改 RanchAnalyzer。TL审核重点：接口和Duration seam足够QA独立注入、总预算覆盖检索至保存前、原始源取消先于终态、最多2次调用及旧数据兼容。若公共合同之外的准备资源释放能力成为阻塞，应报告确切边界，不自行改底座或换Agent。

下一步只在TL发出生产实现范围后，沿现有backend工作树执行上述任务；不向旧架构任务报日常进度。

## 获准后的执行进度

TL已明确授权G01生产实现，三个任务的生产改动已完成；接口保持本文约定。原显式复现测试改名StrategyExecutionTest并纳入普通套件，实施前新增准备未完成取消红门槛后为4红1绿；实现后5/5通过。StrategyRuntimeTest 9项与RanchJobTest 10项（含原4项）定向通过，共24项；源取消、终态与新任务断言都位于应急清理之前。完整响应后的取消用例使用真实监视器，保存先赢用闸门确认取消线程等待监视器，未用Repository重入发布回调替代真实竞争。

下一步交付稳定SHA给QA独立16项矩阵与TL审查；全量测试结果在交付消息及后端评估附录记录。没有部署授权动作，已发布4754cb1保持不动。
