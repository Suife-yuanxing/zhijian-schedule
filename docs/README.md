# 文档索引 · 纸间课表

[纸间课表](../README.md)（PaperTimetable）的工程文档总入口。文档随代码一起提交在仓库内，不依赖任何外部网盘。

## 分类约定

| 目录 | 放什么 | 命名 | 生命周期 |
| --- | --- | --- | --- |
| [`research/`](research/) | 调研笔记：教务厂商、同类产品、技术可行性。结论带证据分级（【事实】/【建议】/【估算】） | 主题名，必要时加 `YYYY-MM-DD-` 前缀 | 只追加，不改历史结论 |
| [`plans/`](plans/) | 某个变更的**实施计划**：任务拆解、验收标准、构建与测试命令 | `YYYY-MM-DD-主题.md` | 该变更交付后归档，不再修改 |
| [`specs/`](specs/) | **设计规格**：项目总体技术方案 + 各变更的设计文档 | 变更设计为 `YYYY-MM-DD-主题-design.md`，与 `plans/` 成对 | 总体方案随版本修订；变更设计归档 |
| [`testing/`](testing/) | 本地测试夹具说明与手工 E2E 步骤 | 按夹具名 | 随夹具代码同步更新 |
| [`adr/`](adr/) | **架构决策记录**（ADR）：不可逆或高成本的取舍 | `NNNN-动词短语.md`，规则见 [ADR 使用说明](adr/README.md) | 只追加，历史条目不改 |

两条稳定约定：

1. **日期取首稿完成日**，不是提交日；`plans/X.md` 对应 `specs/X-design.md`。
2. **路径一经发布即视为稳定契约**：改名会让提交信息、issue 与代码注释里的引用失配。除目录分类迁移外不要移动或重命名文档。

配套的仓库级文档：[README](../README.md) · [CONTRIBUTING](../CONTRIBUTING.md) · [CHANGELOG](../CHANGELOG.md)。

---

## research/ — 调研笔记

- [adapter-architecture.md](research/adapter-architecture.md) — 教务适配器架构 v1.1：主张「原生 Kotlin 为骨架、JS 脚本只取数、解析留在设备端、远程只分发脚本与元数据」，并定下主线（HTTP 重放）与兜底（前台 WebView 注入）双执行模型。
- [industry-import-practices.md](research/industry-import-practices.md) — WakeUp / 小爱 / 拾光 / 下节啥课 / ClassIsland / 超级课程表 的多教务导入做法对比，结论是业界一致采用「学校列表 + 通用教务兜底 + 文件与手动兜底」三层结构，且解析必须跑在已登录的 WebView 上下文里。
- [jw-vendor-landscape.md](research/jw-vendor-landscape.md) — 教务厂商覆盖与抓取可行性：公开的 4140 条学校目录中有约 1470 条可判协议（正方新版 562、强智 292、青果 164、超星 135…），并指出当时的 `ZhengFangAdapter` 实际实现的是强智 jsxsd。
- [2026-10-19-multi-jw-import.md](research/2026-10-19-multi-jw-import.md) — 多教务导入的落地方案：首批只做正方新版 / 强智 / 金智 URP / 青果四家，给出适配器分层、兜底策略与排期。
- [2026-10-25-script-engine-notes.md](research/2026-10-25-script-engine-notes.md) — 给脚本作者的一页契约说明：`PAPER_SCRIPT_MANIFEST` 头部格式与 `fetchSchedule(ctx)` 的返回值信封字段。
- [2026-10-26-jw-import-channels.md](research/2026-10-26-jw-import-channels.md) — 全国高校教务课表导入渠道全景：A 层 8 类数据出口 + B 层 11 种客户端导入方式 + 覆盖率测算与 P0-P2 优先级。
- [2026-10-26-channel-matrix-vendor.md](research/2026-10-26-channel-matrix-vendor.md) — 教务侧渠道矩阵细化：8 类出口逐家端点、字段、坑与证据（E01-E45），含导出通道与研究生系统的纠正。
- [2026-10-26-channel-matrix-client.md](research/2026-10-26-channel-matrix-client.md) — 客户端渠道矩阵细化：WakeUp/超级课程表/星链/Sleepy/拾光/小爱等产品 × 渠道对照，分享码与文件导入的工程取舍。
- [2026-10-27-vendor-live-notification-docs.md](research/2026-10-27-vendor-live-notification-docs.md) — 实时通知厂商文档证据档（主档）：Android 16 / OPPO / 小米 / 荣耀 / vivo / 华为 / 苹果逐条列官方 URL 与原文摘录，按【官网原文】/【二手】/【未取到】分级，并含本项目实现现状对照表；§9 为 10-28 复核后的**逐页正文可取状态总表**（13 条里 9 条确认可取、3 条确认不可取、1 条属工具边界）。
- [2026-10-27-vendor-live-notification-docs-oppo-mi-honor.md](research/2026-10-27-vendor-live-notification-docs-oppo-mi-honor.md) — 同上，OPPO / 小米 / 荣耀分册（574 行）：三家与原生 ProgressStyle 的关系、接入门槛、商务邮箱与白名单机制；结论是三家都不能靠普通通知自动上岛，小米走私有 `miui.focus.param` 协议。
- [2026-10-27-vendor-live-notification-vivo-huawei.md](research/2026-10-27-vendor-live-notification-vivo-huawei.md) — 同上，vivo / 华为分册：vivo 原子通知 `notification.superx.*` 字段全集与原子岛左右岛模板、华为 Live View Kit 与 HMS 两条线路的差异；关键结论是华为 `TIMER` 场景明确排除「课程提醒」。
- [2026-10-27-vendor-live-notification-apple-aosp.md](research/2026-10-27-vendor-live-notification-apple-aosp.md) — 同上，苹果 / Android 原生分册：Live Activity 的 4KB 帧上限、8h/4h/12h 生命周期、priority 5/10 预算模型，以及 Android 16 提升的 9 项硬条件与 96dp chip 限制。
- [2026-10-28-vendor-live-notification-source-audit.md](research/2026-10-28-vendor-live-notification-source-audit.md) — 多厂商实时通知的**原文抓取留档审核与就地勘误记录**：逐份核对 10-27 那批 HTML 快照抽到了什么（Android 两页为完整正文、小米 7 页完整、荣耀 3 页 MD5 相同全是登录墙空壳、OPPO 2 页只有壳），给出「`extract` 失败 ≠ 页面拿不到」的判定法与标识符校验流程，并记录本轮对四份证据档的原地更正（Settings 常量名写错、`MetricStyle` 属 API 37、华为正文 URL 记错）与留痕方式。
- [2026-10-28-jelly-capsule-motion.md](research/2026-10-28-jelly-capsule-motion.md) — 底部浮岛胶囊「果冻」动效规范与灵感调研：六条可落地的外部规范（NN/g 时长、迪士尼 squash & stretch、follow-through、Reduce Motion、Apple 两参数弹簧模型）、「移动途中先拉长再回收」的双瓣波形推导与逐值表、一加 PKG110 上的逐帧实测数据，以及取不到原文的部分据实标注。

> **学校目录数据**：`app/src/main/assets/jw/schools.tsv`（1144 条，随 APK 打包）由 [tools/gen_school_catalog.py](../tools/gen_school_catalog.py) 从学校目录快照生成，只收录「协议属于本应用已实现解析器 + 带教务入口 URL」的条目。`verified=1` 表示目录标记为「已实现配置」，`0` 表示「待验证·可尝试」——**两者都不代表该校当前实测可用**，UI 里必须区分展示。目录快照本身不入库，重建时把快照路径作为第一个参数传给脚本即可。

## plans/ — 实施计划

- [2026-10-15-in-app-update-download.md](plans/2026-10-15-in-app-update-download.md) — 应用内更新下载 v1：把「跳到浏览器自己找 APK」改成应用内点击即下载，`ApkDownloader` 做状态唯一来源、`UpdateStore` 管目录与断点元数据、dataSync 前台服务渲染进度通知。
- [2026-10-15-update-download-acceleration.md](plans/2026-10-15-update-download-acceleration.md) — 下载加速 v2：8 路并发分片 + 镜像源链，服务端不支持 Range 时自动退回单连接路径；分片规划、降级判据与续传点全部收进可单测的纯逻辑。
- [2026-10-15-liquid-glass-theme.md](plans/2026-10-15-liquid-glass-theme.md) — 第 6 套主题「液态玻璃」实现计划：解析式极光背景 + 零模糊玻璃材质，A13+ 机型叠加 AGSL 真折射，旧机走渐变光影档。
- [2026-10-24-exam-academic-center.md](plans/2026-10-24-exam-academic-center.md) — 学业中心一期实施计划：考试列表与编辑、强智考试抓取、手动项保护的合并决策、考前 24h/1h 两档闹钟、今日页倒计时卡、胶囊伪课程与学期绩点趋势。
- [2026-10-25-jw-script-engine.md](plans/2026-10-25-jw-script-engine.md) — 教务脚本引擎 12 任务实施计划：WebView 页面上下文注入执行器、manifest 契约、内置/本地导入/远程仓库三件套分发。

## specs/ — 设计规格

- [课程表APP技术方案.md](specs/课程表APP技术方案.md) — **项目技术总纲（10 章）**：产品定位、总体架构、数据模型、课表渲染、导入方案、提醒与胶囊、小组件、合规与商业化、后续路线。新同学从这里开始读。
- [2026-10-15-in-app-update-download-design.md](specs/2026-10-15-in-app-update-download-design.md) — 应用内下载 v1 设计：下载状态机、断点续传、通知栏进度与取消、安装交付链路与降级退路。
- [2026-10-15-update-download-acceleration-design.md](specs/2026-10-15-update-download-acceleration-design.md) — 下载加速 v2 设计：并发数取 8 的实测依据、镜像源链候选与探测排序；含 2026-10-17「镜像开关改默认开启」的修订。
- [2026-10-15-liquid-glass-theme-design.md](specs/2026-10-15-liquid-glass-theme-design.md) — 液态玻璃主题设计：禁模糊（blur）的材质定义、折射与高光算法、自适应双档的性能边界。
- [2026-10-24-exam-academic-center-design.md](specs/2026-10-24-exam-academic-center-design.md) — 学业中心一期设计：已拍板的决策表（数据来源、合并口径、提醒档位）、Room v2→v3 迁移与 UI 层级。
- [2026-10-25-jw-script-engine-design.md](specs/2026-10-25-jw-script-engine-design.md) — 教务脚本引擎设计：页面上下文注入模型、最小契约（manifest + `fetchSchedule` 信封）、分发三件套与安全模型。
- [2026-10-27-multi-vendor-live-notification-design.md](specs/2026-10-27-multi-vendor-live-notification-design.md) — 多厂商实时通知适配设计：把既有流体云胶囊抽象成 `LiveContent` + `LiveSurfaceRenderer`，一次内容投影驱动原生提升 / 小米超级岛 / 未来各厂商；含 7 条问题清单（含一条勘误）、逐厂商接入契约与实施验收记录。

## testing/ — 测试夹具

- [mock-jw.md](testing/mock-jw.md) — 本地模拟教务服务的使用说明：`node tools/mock-jw/server.mjs` 起服，模拟正方新版与强智 jsxsd，覆盖 WebView → 共享 Cookie → 适配器 → 预览 → 落库整条客户端链路，无需真实学校账号。
- [2026-10-27-real-device-jw.md](testing/2026-10-27-real-device-jw.md) — 真机实测记录：6 个适配器（强智 / 树维 classic / URP / eams5 / 青果 / 正方新版）的识别链路在 Android 16 上逐家验证，含填写地址、识别文案与页面可达性，截图见 `testing/shots/jw-real-device/`。

## adr/ — 架构决策记录

- [ADR 索引与使用说明](adr/README.md)
- [0001 本地优先、无云同步的数据架构](adr/0001-local-first-data-architecture.md)
- [0002 教务导入采用可下载 JS 脚本引擎](adr/0002-jw-import-script-engine.md)
- [0003 应用内更新走 GitHub Releases 自分发](adr/0003-github-releases-self-distribution.md)
- [0004 更新下载采用并发分片与镜像源链](adr/0004-concurrent-chunked-download.md)

---

## 怎么加一篇新文档

1. **先判断类型**：不确定能不能做、别人怎么做的 → `research/`；要做但没定怎么做 → `specs/`；定了要分几步做 → `plans/`；做完了且取舍不可逆 → `adr/`。
2. 按上表命名，日期用首稿完成日。
3. 在本文件对应分类下追加一行：**一句话摘要 + 相对链接**，摘要写清结论而不是主题。
4. 若文档改变了既有架构约定，补一篇 ADR，不要改写历史 ADR。
5. 检查改动后仍无失效链接（见 [CONTRIBUTING](../CONTRIBUTING.md#8-提交前检查清单)）。
