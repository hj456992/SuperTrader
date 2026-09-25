# G01 原入口发布执行记录

2026-09-25，TL接受产品 `b37bc89` 的已知限制交付结论后明确授权切换。固定生产源 `a51f04757c5f9628b1d18328b24ebc21b43e4fe8` 已部署原 `http://127.0.0.1:48740/`。运维只读验收通过，READY及新PID已先交TL安排前端静态复核。Q01称谓仍open，Q02仅该复验样本关闭，不宣称全语言质量通过。

1. 最终重核原 `outputs/demo` clean/4754、PID10025启动时刻/父进程/工作目录/bootstrap/监听身份、实际内存环境、两job idle及全部备份/候选/外部hash。候选来自私有已验归档，没有为TL后续文档HEAD重新构建。
2. 只向PID10025发送SIGTERM，正常退出，无强杀；确认48740普通bind成功后备份。停旧后的idle检查脚本起初对缺失的profile-job行直接JSON解码报错；RanchStore.readJob对缺失行返回idle，HTTP此前也已核对idle。仅修正内存校验脚本按该缺省语义读取，未写业务文档或重复停止。错误立即简报TL后继续恢复入口。
3. 最终静止 `business-cutover.dump` 为20,048字节、0600，SHA256 `2632f37a8e227b47fc0e06fd3cd7164734b2f03a6562e6059eb546c62775113d`。pg_restore list及完整解析到/dev/null通过；静止摘要与停止前一致。没有执行数据库restore。
4. 原main从4754执行ff-only到固定a51f047；原目录成套替换garden/feishu JAR及完整web/dist，换下UI保存在本批私有目录 `old-dist-before-cutover`。外部DSH9制品hash保持不变。原目录`run.py --check`退出0，随后使用实际继承环境运行原 `启动Demo.command`。
5. 原入口configured=10/active=10，新launcher PID24257，实际Java/listener PID24259；cwd与bootstrap、GARDEN_WEB仍指原目录，DB/model/port等实际配置与旧实例内存比较一致。garden SHA256 `96a6749bb933029c2bba41c5e2755c0e72372e2cb9fba8a76de7b9a50b7a4e08`、UI `e0f6b3a80a7ab2f712c4a4132f116f0d2249925f654e7f7492a9faec0c771e60`匹配；原树clean/a51f047。
6. 新Cookie只读结果：revision39、牧场人物4/书1/本人物料0、legacy人物4、两job idle，与停止前一致。两业务文档摘要与最终静止快照完全一致；未调用模型、导入、编辑真实数据或刷新用户页面。停旧发信号至10插件ready共109.031秒，包含校验脚本修正；未发生新旧业务宿主并行。

所有归档、最终快照、逐文件hash及脱敏元数据保留于 `/Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/ailiao-upgrade/ops/.local/local-release-g01-20260925-014124`，manifest已追加finalCutoverBackup/releaseResult。回退源是4754（10插件），不是b410；任何发布后新写入不得被本批快照自动覆盖。本次没有执行回退，不声称已演练恢复。

**持续运行：新托管session49933必须保持，监督Python PID23358、launcher24257、Java24259，独立进程组24257，日志线程持续排空至有界内存。不得在本turn收尾时退出监督器、关闭管道或清理新服务。** 旧session67009未主动关闭，其原服务已按授权正常停止；后续应以24259/49933作为当前服务身份，不再把10025当在用PID。没有新增自动服务或重启平台。

前端静态核验预期值为私有manifest的candidateArtifactSha256；运维未替代其独立检查。部署后只读验收不等同新一轮真实模型或完整动态UI验收。本记录仅ops文档提交，未push。
