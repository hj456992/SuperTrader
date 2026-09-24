# 聊天日志与爱聊

当前采集入口是 **dsh-java 插件 → wechat-cli history → 独立日志 → 爱聊**。忽略 new-messages；OCR 和旧 DatabaseWorker 在 history 模式下不启动。ZIP 仅保留为可选历史导入。

[爱聊](http://127.0.0.1:48741/wechat.html) · [独立日志](http://127.0.0.1:48742/)。启动 `python3 outputs/demo/logbook/launch.py` 或 `聊天日志.app`。原有人物服务48740独立运行。

## 插件与初始化

插件目录 `outputs/demo/plugins/wechat-history`，真实实现 dsh-java `Plugin.start`，资源由 `context.own` 释放。当前用同一 dsh-java 底座的独立宿主加载，避免重启人物服务。将来启动主宿主时可用 `WECHAT_HISTORY_EMBEDDED=1` 装配同一个插件；先停止独立 history 宿主，避免同一数据目录重复采集。

私有 `work/chatlog/source-mode.json` 选择 `wechat_cli_history`。`database-source.json` 提供 `dbRoot`、`keyFile`；启动器生成 `history-plugin/cli-config.json`、bootstrap 与运行日志。配置和密钥只留本机，文件0600、目录0700。

真实微信初始化已成功：当前4.1.13版本的上游固定格式扫描器未找到密钥，改用数据库加载阶段捕获并验证2个所需数据库密钥后，真实history读取成功。爱聊已同步本机目标群13,225条记录，状态为connected，空闲轮询未重复新增。密钥保存在原配置位置，不需要再运行初始化。来源及适配细节见 `../wechat-cli/README.md` 与 `../../../work/wechat-history-plugin/` 中的验证记录。

用户执行脚本已备份并重签原版微信，添加get-task-allow；原版备份保留。重签可能影响部分功能或自动更新。

## 自动同步行为

- 仅唯一匹配“卧底不追高”，取得真实群ID后读取对应消息表；重名时拒绝猜测。
- 每3秒调用结构化 history，最多200条；首轮从本机已有最早记录补齐，后续按分库 local_id 增量。
- 加密数据库及WAL复制到账号隔离的私有临时快照，再用SQLCipher只读解析；不会写原微信数据库。临时快照读完删除。
- 账号/群/分库/local_id 去重。消息与检查点事务提交；保存失败重试同一批，进程重启从已提交进度继续。
- 保存秒级时间与真实ID。非文本显示类型占位，尚未提取数据库媒体附件。
- 展示和搜索限最近2000条数据库消息，总数包含全部已保存记录。手机未同步到本机的历史不在范围内。

## 接口与隐私

服务仅监听127.0.0.1。history 的 checkpoint/batch/status 接口仅接受私有 `X-Logbook-Token`，浏览器cookie不能写入采集批次。爱聊通过只读接口读取日志。没有自动发送、外部模型调用或远程上传。

ZIP导出记录保留导入时指定的群归属说明，不伪装成数据库消息；相同包按SHA256去重，不同包可能重叠。

## 验证与重建

需要Java17、Python3.12、SQLCipher动态库、zstd；CLI使用项目内隔离venv。插件的构建命令见其README。

```sh
PYTHONPATH=outputs/demo python3 -m unittest discover -s outputs/demo/logbook/tests -p 'test_*.py'
node --test outputs/demo/logbook/tests/test_ui.cjs
npm --prefix outputs/demo/web run build
outputs/demo/wechat-cli/.venv/bin/python work/wechat-history-plugin/verify_pipeline.py
python3 outputs/demo/logbook/launch.py
```

测试采用人工加密数据库、WAL和合成ZIP。测试结果不能代替真实微信验收。微信来源适配保留上游Apache2许可；原SQLCipher封装溯源见THIRD_PARTY_LICENSES.md。
