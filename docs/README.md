# 爱聊文档

## 架构评审 · 2026-09-24

[完整文字版](architecture-review-20260924/架构评审.md) · [离线交互 HTML](architecture-review-20260924/index.html) · [图解文件](architecture-review-20260924/assets)

1. 实际使用记录：12 条路径、3 张虚构示例截图。
2. 业务理解：会话成员、关注的人、材料、画像、情境与攻略。
3. 当前部署、组件依赖、导入和分析流程。
4. 目标数据模型、任务提交协议、设计模式。
5. Cordis、DeepSeek Harness、AgentScope 的分工与接入边界。
6. 10 项修复建议，以及迁移、验收和回退方案。

报告形成后，本地代码继续演进，新增了平台按需读取能力。报告是评审时点的快照；当前源码与启动说明以[仓库 README](../README.md)为准。建议设计不等于已完成修复。

HTML 中的图片已内嵌，下载单个 HTML 即可离线阅读；GitHub 不直接执行 HTML。应用源码链接已转换为仓库链接，外部 dsh-java 源码不在本仓库。

## 评估证据

- [AssessmentProbe.java](evidence/AssessmentProbe.java)：调用实际业务类的内存探针；不连接数据库、不调用模型。
- [probe-results.json](evidence/probe-results.json)：评审时的四项结果。
- [图解验证记录](architecture-review-20260924/验证记录.json)：交付时的渲染、布局和阅读验证。

发布版本仅包含虚构示例截图，不包含本机真实聊天数据库和运行状态。
