# 爱聊飞书读取

这是爱聊的独立 `feishu-history-plugin`，由 dsh-java 加载；界面通过 `feishu.capture` 服务获取预览。不是 Codex 代读后粘贴：爱聊按钮直接启动本机采集器。

## 使用

1. 打开并登录 `/Applications/Lark.app`，保持 Mac 解锁、飞书消息列表可访问。
2. 爱聊 → 新建会话 → 平台选择「飞书」→「加载飞书会话列表」→ 选择目标群 →「读取所选会话」。也可以先在飞书打开目标群，再用「读取飞书当前会话」。
3. 群名和消息自动回填，核对后点击「保存上下文」。保存为本地 PostgreSQL 会话，发言人与读取时间、范围保留。

读取当前飞书窗口已加载且可访问的消息，不代表全部历史。话题回复只保存当前预览并明确标记；图片保存占位，不读取图片内容。不猜测当前登录人身份；保留发言人供后续理解区分不同群成员。编辑群名或正文后将作为手动内容保存；重读可恢复来源标记。

列表只包含客户端已加载的会话（最多 200 条），可能包含群聊、单聊与机器人。列表凭据有效 5 分钟；列表重排、重名或标题变化时会拒绝猜测并提示刷新。若当前聊天与目标同名，使用「读取飞书当前会话」明确读取当前窗口。选择后会校验群标题与消息 ID，等待旧会话消息退出后才返回。

读取是按需的，没有后台轮询、自动分析或自动发送。切换飞书会话后再次点击按钮即读取新快照。原始 AX 树不写盘。辅助功能未授权、飞书未运行、未打开聊天、窗口匹配不唯一或读取中切换群时返回明确错误，不复用旧快照冒充成功。

## 构建与运行

```sh
sh outputs/demo/feishu/build-native.sh
/Users/hou/.local/apache-maven-3.9.16/bin/mvn -f outputs/demo/plugins/feishu-history/pom.xml package
/Users/hou/.local/apache-maven-3.9.16/bin/mvn -f outputs/demo/pom.xml package
npm --prefix outputs/demo/web run build
python3 outputs/demo/run.py
```

沿用爱聊环境中的模型和数据库配置。读取不使用模型密钥。插件启动参数在 `run.py`，原生读取程序限制为 `/Applications/Lark.app` 的当前窗口 `messenger-chat`。飞书 UI 结构改变导致找不到消息标识时停止并提示，而不是猜测其他区域。

## 验证

```sh
python3 -m unittest discover -s outputs/demo/feishu/tests -v
/Users/hou/.local/apache-maven-3.9.16/bin/mvn -f outputs/demo/plugins/feishu-history/pom.xml test
/Users/hou/.local/apache-maven-3.9.16/bin/mvn -f outputs/demo/pom.xml test
```

覆盖消息标识边界、公告/输入区/点赞成员排除、未知发言人、话题预览、图片地址隐藏、UTF-16/序列化容量、来源绑定，以及子进程失败、超时和关闭清理。

## 本机实测结果（2026-09-23）

独立插件在 dsh-java 中正常激活（4/4 插件 active）。通过爱聊新建页按钮读取 `GLM Coding 用户交流群 4️⃣｜官方` 的16条已加载消息（含话题回复预览），自动回填并保存为会话。保存的来源为 `feishu_desktop`，范围 `current_view`，未触发容量截断。已核对发言人、原文、公告与点赞过滤；原有会话未变化。真实导入结果保留在爱聊供查看。23项自动测试通过。

群选择器已通过爱聊界面加载 16 个真实会话，从列表切换读取 GLM 群 15 条消息和「后端&AI 技术交流群」12 条消息。随后收紧同名与切换过渡态校验。最终保存与刷新验证进行时 Mac 锁屏，需解锁后继续验证；未宣称这一轮保存完成。列表解析 4 项测试与既有消息解析 9 项测试通过。
