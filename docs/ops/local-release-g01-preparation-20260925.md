# G01 本地发布准备：READY，未切换

TL授权仅准备。回退源为当前已验证 `4754cb10e45478a1347c1b1a49e5078829f86f59`，候选生产源固定 `a51f04757c5f9628b1d18328b24ebc21b43e4fe8`；后续TL纯文档HEAD不改变冻结源或制品。不再追加真实模型请求；称谓遗留仍交TL/产品决定，不写成已解决。

- 原目录 `outputs/demo` 仍clean/4754。PID10025、PPID/PGID10024、启动时刻2026-09-25 08:50:21、cwd/宿主/bootstrap/48740监听均核对吻合。实际DB/model/web配置仅在内存核对，没有保存凭据。原bootstrap为10插件、0600；线上session67009保持原样。
- 只读元数据前后均revision39、牧场人物4/书1、本人物料0、legacy人物4、两job idle；在线备份前后业务文档摘要一致。未访问真实聊天、调用模型、修改业务数据或原目录。
- 固定源差异限RanchAnalyzer、新增StrategyRuntime、ranch-view及其测试；未改Store/RanchData、schema、run.py/build.sh/启动入口或底座。TL当前文档HEAD的生产路径与a51f047一致，可以从4754快进；是否执行仍须明确发令。
- 当前实装garden SHA256 `a9a873e917958fdbc8f482f0c0dba5962ca0458b1960186f741ba459f4438711`、UI `d729a1b1c6534687b6125f62fdc81e8de5935cdead4cb8602bff83305138294a`；候选garden `96a6749bb933029c2bba41c5e2755c0e72372e2cb9fba8a76de7b9a50b7a4e08`、UI `e0f6b3a80a7ab2f712c4a4132f116f0d2249925f654e7f7492a9faec0c771e60`。飞书及完整UI逐文件hash在私有manifest；兼容DSH宿主+8插件hash与上一批全部一致，未重跑9插件探针。

私有备份目录：`/Users/hou/Documents/Codex/2026-09-22/garden-product-design/work/ailiao-upgrade/ops/.local/local-release-g01-20260925-014124`，目录0700、文件0600、gitignored。

包含 `source-4754.tar`、`artifacts-4754.tar`（原实装两JAR/完整web/dist/run.py/build.sh/启动Demo.command/bootstrap）、`source-a51f047.tar`、`artifacts-a51f047.tar`、`dsh-compatible-artifacts.tar`、`business-online-preparation.dump` 和脱敏 `manifest.json`。tar安全路径与逐文件完整读取通过；DBdump为20,048字节，SHA256 `084ba6730ea25a910c18f9edfee33df0c08d9a247faf6f58b2d942815d13eabf`，pg_restore list及完整解析到/dev/null通过。**这是在线准备快照，不是最终停机快照；没有执行数据库恢复演练。**

后续沿既有发布流程，仅替换本批源与回退点：收到明确发令后重新核对PID身份、原目录clean/4754、两job idle、实际配置与全部hash；只停已核对旧实例，确认48740无listener且普通bind成功，再取新的静止DB快照和业务摘要。备份验证后才快进固定a51f047、成套替换制品、以原环境执行原目录`run.py --check`及原入口；10插件ready后只读核对静止摘要不变，交TL/前端静态复核。全过程单业务实例，不用真实业务材料做smoke。

回退本批只考虑**4754**，不使用旧b410：若没有新业务写入且摘要等于最终静止快照，停止本次新实例、确认端口释放与工作树无用户改动，切到`git switch --detach 4754cb10e45478a1347c1b1a49e5078829f86f59`并恢复本批artifacts-4754全套，以相同数据库/环境启动，预期10插件。若发生任何新业务写入或摘要变化，不自动恢复DB或假设旧版写行为等价，保留数据交TL选择向前修复或受控回退。实际回退与数据库恢复均未执行。

READY已先简报唯一TL；准备包装83598退出并清内存凭据，生产session67009未关闭。未停旧、未改原目录、未部署、未push。
