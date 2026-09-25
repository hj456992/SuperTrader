# G01 a51f047 发布后静态资源核验

结论：11/11 静态 GET 与 10/10 本地静态文件 SHA-256 均匹配运维批准候选清单；本检查范围内未发现发布阻断。

- 时间（Asia/Shanghai）：`2026-09-25T09:54:04.912324+08:00` 至 `2026-09-25T09:54:04.966020+08:00`。
- 固定候选：`a51f04757c5f9628b1d18328b24ebc21b43e4fe8`。
- 实测地址：`http://127.0.0.1:48740`；本地文件：`outputs/demo/web/dist`（项目根目录 `/Users/hou/Documents/Codex/2026-09-22/garden-product-design`）。
- 预期来源：`work/ailiao-upgrade/ops/.local/local-release-g01-20260925-014124/manifest.json` 的 `candidateArtifactSha256`。
- TL 交接 Java PID `24259`；garden JAR SHA-256 `96a6749bb933029c2bba41c5e2755c0e72372e2cb9fba8a76de7b9a50b7a4e08`。PID/JAR 为交接信息，本轮未额外检查。

全部响应为 `200`、`Cache-Control: no-store`，MIME 符合文件类型。下表 SHA-256 同时匹配 HTTP 内容、原发布目录文件与批准清单；`/` 复用 `index.html` 本地比对，因此为 11 GET／10 文件。

| 静态路径 | MIME | 字节数 | SHA-256 |
| --- | --- | ---: | --- |
| `/` | `text/html` | 528 | `7fac8e49b0675042c249a523995050dd1c5188fdc2951ae4f5208bdc070bec74` |
| `/index.html` | `text/html` | 528 | `7fac8e49b0675042c249a523995050dd1c5188fdc2951ae4f5208bdc070bec74` |
| `/ranch.css` | `text/css` | 22343 | `275b62e1392158dda19a2f0e1aedd30047e9d5ce294aa44c05411769a03f9c44` |
| `/host.js` | `text/javascript` | 66537 | `e07d6815f5efa6503a2da6f261a9a3f384ca64ad512be59cac4d725dd59ea5fa` |
| `/ranch-ui.js` | `text/javascript` | 61648 | `e0f6b3a80a7ab2f712c4a4132f116f0d2249925f654e7f7492a9faec0c771e60` |
| `/vendor/bootstrap.js` | `text/javascript` | 3136 | `223f5e15ec98384cdb960fc38e8ec2dffaf5c54a4d4af199488f99e988ad7ea5` |
| `/vendor/client-modules.js` | `text/javascript` | 18621 | `9361241bfbe8a02864df5df9fabf9fbbfa0f5a1914025badab36bdf1b9481751` |
| `/conversations.html` | `text/html` | 567 | `00c4ba17ddf357c13ce2639a338fd78ef2efff7f267a318d728f119198b1bb6a` |
| `/garden-ui.js` | `text/javascript` | 47612 | `5301847edfd37b00128c561cb55536e90a23ddba20eccb5c83c61b4ae0d34737` |
| `/style.css` | `text/css` | 23522 | `3d1619f401d55b526ced3c123f77b2dc26d713397ed208ff0270de82cae9e8bf` |
| `/assistant.css` | `text/css` | 6486 | `cd05e412162b14727d79c8e8e444164b9a8828459dfaf9c4f0f3905b6e68b179` |

首页 `/` 与 `/index.html` 内容 hash 相同；只解析 HTML 文本确认其引用 ranch.css、两份 vendor loader 和 host.js。兼容入口 `/conversations.html` 引用 style.css、assistant.css、相同 loader 和 host.js，均已包含在本轮静态核验内。

方法：Python 标准库读取固定文件并对固定路径逐次 GET，不跟随重定向；仅将状态、MIME、缓存策略、字节数、hash 和静态引用结论写入本记录，未保存响应正文或 Cookie。

范围限制：未执行主 JS，未打开或刷新用户页面，未请求业务 API、读取数据库或调用模型；未构建、替换制品、重启服务或更改用户数据。本结果仅证明批准静态资源已发布可达，不代表动态 UI、模型或业务链路验收，也不代表 G08 持久恢复问题已解决。

本次仅新增本发布记录，核验完成后停止并保留前端工作树。
