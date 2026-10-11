# 专家生产全流程图

[查看 SVG](expert-production-flow.svg) · [下载 PNG](expert-production-flow.png)

图示为 2026-10-12 代码的管理员搭建链路。按“接入与保存、学习与生成、人工审阅、确认与完成”分层，紫色回线表示讨论澄清、修改重做和下一稿。

- 每位专业子专家、兜底专家和主专家都有独立完整提示词，逐稿确认。
- PostgreSQL 保存构建、修订、引用、审核、澄清、消息、任务和事件；共享文件保存资料版本。
- Redis 当前用于模型调用共享额度，任务持有与恢复由 PostgreSQL 租约和行锁承担。
- 浏览器每 2.5 秒读取增量事件，有变化再取快照；当前实现为轮询，不是 SSE。
- L1/L2/L3 在此阶段生成并审核配置，不代表最终用户侧路由已运行或部署。

生成 SVG：`python3 render-production-flow.py`。PNG 由 CairoSVG 按 1680×2840 渲染；使用 fireworks-tech-graph 的 XML、箭头、几何和构图检查，均通过，并人工检查中文、箭头及间距。

完整实测结果见 [流程验收记录](../flow-test-report.md)。
