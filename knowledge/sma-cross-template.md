# SMA_CROSS 策略模板（受控声明式白名单）

Module 9 V1 只允许一个真正可回测的白名单模板：`SMA_CROSS`（双均线交叉）。

## 模板字段

- `templateType`：固定为 `SMA_CROSS`。
- `instruments`：公开合约代码列表（与模块 8 规则一致：1–20 个去重项，每项 1–16
  个字母或数字，统一大写确定性格式）。
- `timeframe`：周期白名单（`TICK / 1M / 5M / 15M / 30M / 1H / 1D`）。
- `parameters`（严格限定，所有值必须有界）：
  - `fastWindow`：整数，2–100（快线窗口）
  - `slowWindow`：整数，3–300，且 `slowWindow > fastWindow`（慢线窗口）
  - `positionSize`：0–1（每次入场使用的资金比例）
  - `stopLossPct`：0–30（止损百分比）
  - `feeBps`：0–100（单边手续费，基点）
  - `slippageBps`：0–100（单边滑点，基点）
- `entryCondition`：入场条件（声明式文本，SMA 快线上穿慢线时买入）。
- `exitCondition`：出场条件（声明式文本，SMA 快线下穿慢线时卖出，或触发止损）。
- `riskLimits`：风险限制（仓位、止损、最大回撤说明）。
- `backtestAssumptions`：回测假设（手续费、滑点、数据区间等）。

## 禁止内容

模板中**禁止**出现：任意源代码、脚本、Prompt、表达式求值、动态类名、Shell
命令、URL、工具名、MCP 权限、账号或凭证、交易指令。非 `SMA_CROSS` 的研究讨论
可以保存为 Draft，但确定性 Validator 必须返回 `NOT_BACKTESTABLE`，不得送入
Backtest Runner。

## 规则

1. Draft 可修改；冻结后绝不可原地修改；修改冻结内容必须创建新 Draft。
2. 只有用户明确选择“进入策略共创”后才能创建 Draft。
3. 每轮最多追问一个最高影响字段。
4. 回测结果只能来自确定性本地 Backtest Runner；LLM 只解释已有结构化结果，
   不计算、不修改任何数值。
