# 本地授权知识库（Module 9 受控 RAG）

本目录是 Agent 受控 RAG 的**唯一**知识来源（V1 只实现本地授权知识库）。

## 安全边界

- 检索服务只读取本目录（`rebuild/knowledge/`）下显式纳入的非敏感 Markdown / JSON /
  TXT 文档；绝不读取本目录之外的任何文件。
- 明确拒绝：`../` 路径穿越、绝对路径、symlink 越界、隐藏文件（点开头）、环境变量
  引用、`.run/` 中的账号或凭证数据。
- 每个检索结果都带 `sourceId / title / 相对路径 / 片段 / 内容哈希`；回答引用知识时
  必须返回 citation。
- 知识不可用时明确标记 `EVIDENCE_MISSING`，不凭空补全。
- RAG 结果只提供研究证据，不能作为自动审批依据，也不能产生回测指标。

## 文档清单

| 文件 | 内容 |
|---|---|
| `README.md` | 本文件（知识库边界说明，可检索） |
| `sma-cross-template.md` | SMA_CROSS 白名单模板的受控声明式字段与参数范围 |
| `backtest-datasets.md` | 内置回测数据集的说明（明确标注非真实行情） |
| `market-research-notes.md` | 均线交叉策略研究的非敏感背景说明 |
| `rag-safety.md` | RAG 检索安全规则（供 Agent 自查） |

> 本目录只存放非敏感内容；禁止放入任何凭证、账户、私钥、令牌或交易指令。
