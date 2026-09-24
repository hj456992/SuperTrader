# 材料时间来源独立验收

2026-09-24。真实模型将手动材料的录入时间误当发言日期，TL 要求只修复模型输入边界。QA 使用全虚构材料，不读取真实聊天或调用外部模型。

## 结果与范围

`ProfileAgentAcceptanceTimeTest` 在后端 `d4ed559` 上 **5 tests / 5 failures / 0 errors / 0 skipped**；合入 `df23b58748dc3f3badad8b39c63b4f40ba508c23` 后，同组断言 **5/5 通过，0失败/错误/跳过**。QA 被测 `src/main`、`web/src`、`pom.xml` 与 TL 集成 `d62253a` 差异为空。

| 独立用例 | 实际验证 |
|---|---|
| 初始投影 | 本人/对象的手动材料、旁人背景、有 sourceKey 但无确证日期的旧来源均不带 at/spokenAt；真来源保留 spokenAt；原输入和 state 不变 |
| 真实工具链路 | 本人/对象各执行 book_search → chat_search → 两次 chat_context → 最终回答；检查实际模型初始消息及收到的工具结果，三处保持同一时间边界；state 不变 |
| 原始排序 | 将最新手动材料放在数组首部，再追加160条旧材料；截取160条时最新材料仍保留在末位，模型投影无录入日期，state 不变 |
| Live 来源 | 微信/飞书分别归一化并选取有效、缺失、非法消息日期；仅有效原始消息 at 成为 spokenAt，capturedAt 回退不成为发言时间，源对象不变 |
| Log/legacy 来源 | 实际纯映射方法仅把 sentAt 标为 spokenAt；capturedAt 和 legacy importedAt 不被提升为发言时间，旧来源对象不变 |

`sourceKey` 只说明来源，不证明 at 的时间语义。原 at 继续留在存储供排序；模型收到的材料移除 at，仅提供确证的 spokenAt。旧记录不迁移，未知日期保持未知。

## 复现命令

```sh
/Users/hou/.local/apache-maven-3.9.16/bin/mvn -o \
  -Dmaven.repo.local=/Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/ailiao-upgrade/backend/.local/m2 \
  -Dtest=ProfileAgentAcceptanceTimeTest test
```

红绿日志分别保留在忽略目录 `target/qa-time-provenance-red.log`、`target/qa-time-provenance-green.log`。测试名匹配现有 release 的 `*Test`，无需修改发布命令。

此轮 QA 仅执行上述独立定向回归；TL 另报原完整 release Java81/81、Node60/60及ops15项通过，不混计为 QA 本轮执行数。新增5项尚待集成后计入总数。确定性模型替身只证明输入/工具边界，修复后真实模型日期表述仍由 TL 单独复验。
