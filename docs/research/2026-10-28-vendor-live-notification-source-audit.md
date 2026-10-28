# 多厂商实时通知 · 原文抓取留档审核

日期：2026-10-28
用途：`_wk_docs/` 这个临时抓取目录在删除前做一次内容审计与留档说明。
对应正文：[`2026-10-27-vendor-live-notification-docs.md`](2026-10-27-vendor-live-notification-docs.md)
及其三个分册。

## 1. 这个目录是什么

10-27 做多厂商实时通知调研时，为了绕开搜索工具对官方文档站抓取失败的问题，
直接用本地抓 HTML → 去标签 → 存 txt 的方式留了一份**原文快照**：

- 14 个 `.html` 原始页面（共约 2.6 MB）；
- 3 个 `strip*.py` 去标签脚本（`strip.py` 处理小米 7 页、`strip2.py` 处理 Android/OPPO/荣耀、
  `strip3.py` 处理剩下两页）；
- 12 个 `.txt` 抽取结果（放 `txt/`）。

**目录名带 `_wk_` 前缀、不在 `.gitignore` 里、也没有被任何已提交文档引用**——
它是工作目录而不是仓库资产，本次审计后删除。

## 2. 逐份内容核对

判定标准：抽取出的纯文本里能否命中该页应有的关键标识符。

| 页面 | 标题 | 抽取文本量 | 关键标识命中 | 判定 |
| --- | --- | --- | --- | --- |
| `a16_live` | Create live update notifications | 27,719 字符 | `POST_PROMOTED_NOTIFICATIONS` / `setRequestPromotedOngoing` / `FLAG_PROMOTED_ONGOING` | **正文完整** |
| `a16g` | Progress-centric notifications | 24,471 字符 | `ProgressStyle` / `ProgressStyle.Segment` | **正文完整** |
| `mi_2131` | 开发指南 | 14,396 字符 | 岛通知构建方式 | **正文完整** |
| `mi_2142` | 展开态设计规范 | 9,595 字符 | 模版结构、组件字段 | **正文完整** |
| `mi_2143` | 摘要态设计规范 | 4,259 字符 | 大岛 / 小岛模板 | **正文完整** |
| `mi_2132` | 接入流程 | 3,491 字符 | 开发准备、提交流程 | **正文完整** |
| `mi_2146` | 常见 Q&A | 3,421 字符 | 接入问题、审核问题 | **正文完整** |
| `mi_2140` | 业务介绍 | 2,127 字符 | 通知体系介绍 | **正文完整** |
| `mi_2144` | 方案提报说明 | 966 字符 | 提报要求 | **正文完整** |
| `oppo_wiki` | OPPO 开放平台 | 202 字符 | 无 | **仅壳页** |
| `oppo_13572` | OPPO 开放平台 | 21 字符 | 无 | **仅壳页** |
| `honor_100158` | （无标题） | 0 字符 | 无 | **仅壳页** |
| `honor_100255` | （无标题） | 0 字符 | 无 | **仅壳页** |
| `honor_yoyo` | （无标题） | 0 字符 | 无 | **仅壳页** |

三个荣耀页面 **MD5 完全相同**（`46017edd…`），都是登录墙返回的同一张空壳页——
也就是说荣耀那一份调研当时**一份正文都没拿到**，与已提交分册里的判断一致。

OPPO 两页都停在「OPPO 开放平台」首屏框架，正文由前端异步渲染，去标签后只剩壳；
两者 MD5 不同但内容同质。

## 3. 发现的矛盾（需更正）

已提交的 [`2026-10-27-vendor-live-notification-docs.md`](2026-10-27-vendor-live-notification-docs.md) 第 13~15 行
把三条 Android 官方链接标为 **【未取到正文】**：

```
| 官方文档 | https://developer.android.com/develop/ui/views/notifications/live-update（【未取到正文】抓取失败） |
| Compose 版 | ...（【未取到正文】） |
| 中文版 | ...（【未取到正文】） |
```

但 `_wk_docs/a16_live.html` 抓到的正是第一条的**完整正文**，且内容足以支撑设计文档里的
关键结论（提升条件、`POST_PROMOTED_NOTIFICATIONS` 权限、`setRequestPromotedOngoing`、
`FLAG_PROMOTED_ONGOING` 与 `hasPromotableCharacteristics()` 三个探测 API、
以及「OEM 可追加额外条件」这条免责）。

第二份 `a16g.html`（Progress-centric notifications）同样是完整正文，
补齐了 `ProgressStyle` / `Segment` / `setProgressSegments` 的分段模型。

初稿此处写的是「真正没拿到的只有 Compose 版与中文版两条」。**10-28 复核推翻了这句话**：
那两条同样拿得到，只要换到同源的官方中国镜像 `developer.android.google.cn`
（Compose 版 3,823 字、进度中心通知 2,624 字、`ProgressStyle` reference 182,351 字，全部命中标识符）。

所以真正的教训有两条，比「哪几条拿到了」更重要：

1. **`extract` 工具抓不到 ≠ 页面拿不到**。工具的失败模式（主站连接超时、反爬、只认静态 HTML）
   与页面的可获取性是两件事；
2. **换域名也算换通道**。`developer.android.com` 从本机 `curl` 直接超时（exit 28），
   而 `developer.android.google.cn` 同一批文档 200 正常返回——两者是同一份官方文档源，
   只是前者本网络不可达。

## 4. 处理决定

1. 本目录**不进仓库**（它不是仓库资产，且 2.6 MB 的第三方页面快照没有版本价值）；
2. 审计结论沉淀成本文（`docs/research/2026-10-28-vendor-live-notification-source-audit.md`）；
3. ~~已提交分册里的【未取到正文】标注保持原样不改~~
   → **已按用户要求就地更正**：主档 §1 与 §9 的三条 Android 链接、四份分册里所有
   「抓取失败 / 未取到正文」标注，均已补上 10-28 复核结论。**历史判断没有被抹掉**——
   被更正的原文一律用 `~~删除线~~` 保留，读者能同时看到「当时怎么判断」和「复核后是什么」。
   这符合 `docs/research/` 的「可原位勘误但须留痕」用法，体例参照
   `docs/specs/2026-10-27-multi-vendor-live-notification-design.md` §1.3：
   「勘误保留原编号，避免与后续讨论失联」；
4. 目录本体已删除。

### 4.1 本轮复核顺带改掉的三处实质错误

这几条不只是「通道标注」，而是**会误导实现**的错误：

| 位置 | 原文 | 更正 | 依据 |
| --- | --- | --- | --- |
| 苹果/AOSP 分册 §2、§6；OPPO/小米/荣耀分册 §0.1 | `Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS` | `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` | 官方指南页两处均写后者；`javap -constants android.provider.Settings`（android-36 rev 2）只有后者，前者在常量表与 class 字符串表里**都不存在** |
| 苹果/AOSP 分册 §1；OPPO/小米/荣耀分册 §0.1 | `MetricStyle` 与 `ProgressStyle` 并列为提升资格样式，未提版本 | 补注 `MetricStyle` 是 **API 37** 才加入，`compileSdk=36` 下引用不到 | `reference/android/app/Notification.MetricStyle` 页首 "Added in API level 37" |
| 华为分册 §5 第 2 条 | 华为「开发实况窗场景」正文记作「未取正文」 | 正文在 `liveview-introduction`（17,836 字），原表记的 `liveview-scenes` 是错 URL | 直接抽取该页，「最长不得超过 8 小时」与 TIMER 场景下的「课程提醒」不适用均逐字在页 |

另有一条**利好实现**的发现：项目已依赖的 `androidx.core:core:1.17.0` **已经提供**
`NotificationCompat.Builder.setRequestPromotedOngoing(boolean)` 与 `NotificationCompat.ProgressStyle`
（含 `Segment` / `Point`），提升请求不必再手写字符串 key。设计文档已补注。

## 5. 方法备注（对下次调研有用）

官方文档站（developer.android.com、小米澎湃OS 开发者平台）对「摘要抽取」这类工具
返回空或截断，但对**直接抓 HTML** 是放行的。所以遇到 `extract_failed` 时的正确次序是：

1. `curl` / 浏览器把 HTML 落盘；
2. 用 `strip*.py` 这类去标签脚本抽正文；
3. 抽完**立刻**用关键标识符（类名、权限名、字段名）验证是不是拿到了真正文——
   光看字符数会被壳页骗（OPPO 那两页都有 200 字符的壳）。

本次的教训正是第 3 步没做：`a16_live` 的正文其实到手了，但当时没做标识符校验，
误判成「没拿到」，把结论写死进了已提交文档。
