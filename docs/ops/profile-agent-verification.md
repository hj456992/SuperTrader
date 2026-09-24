# Task 5 运维验证记录

日期：2026-09-24。分支 `ailiao/ops-profile-agent`，基线 `88cdbac`。仅修改启动/构建、运维测试/文档及必要 README。

| 验证 | 命令/方式 | 实际结果 |
|---|---|---|
| Python 启动与构建行为 | `python3 -m unittest discover -s tests/ops -v` | 10 通过；虚构配置、临时文件、替身 Java |
| Shell 语法 | `zsh -n build.sh` | 通过 |
| 实际业务与飞书 Java 构建 | `MAVEN_OPTS=-Dmaven.repo.local=/Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/ailiao-upgrade/backend/.local/m2 zsh build.sh` | 业务 40、飞书 31 测试通过；两项制品和 UI 构建成功 |
| 原前端回归 | `node --test web/src/*.test.js` | 24 通过；这是运维分支基线 UI，不冒充新前端集成结果 |
| 真实宿主依赖装配 | `python3 tests/ops/check_plugin_assembly.py` | configured=9, active=9；真实底座与插件 JAR、虚构模型 key；未加载访问业务 DB 的 garden |
| 独立配置只读预检 | 运行手册中的内存环境包装 + `run.py --check`，端口 48749、明确 currentSchema | 通过；不连接数据库、不写 bootstrap、不调用模型 |
| 差异检查 | `git diff --check` | 通过 |

失败到修复证据：先写启动器测试，原脚本缺 --check/Java版本/配置完整性/端口保护/0600 权限等，6 项失败；实现后通过。插件清单测试先失败（无六项依赖与 agent-loop 制品检查），装配后通过。构建测试及实际干净构建复现 vendor 目录缺失；按 TL 指定在 build.sh mkdir 后实际构建通过。

初次一次性宿主探针过早停止读取输出，误将未观察到 ready 报告为失败；完整读取后证明宿主 active=9 并自然退出0。已交付的独立探针读取完整输出，避免此测量错误。最终配置中的 `llmRuntime` 已按实际 ModelRegistry.KEY 核对，未仅依赖原清单笔误。

隔离资源：TL 授权维护库 `postgres` 的唯一 schema `ailiao_profile_smoke_0e2d7c0a95d2`，由 ops 创建，等待 TL 集成版使用。未新建数据库。严格单 schema currentSchema，不回退 public。验收结束后 TL/ops 仅清理此准确名称的本任务对象。

尚未声称完成：本分支不含新后端实现；完整 10 插件业务启动、真实模型画像、HTTP取消、持久化重开/中断状态、旧版本回滚都由 TL 在集成提交上继续验收。后端报告的确定性 AgentScope 测试是另一项证据，此处不混为本任务亲自验证。当前用户实例、业务库和真实聊天未操作。
