# 多厂商实时通知官方文档证据档（2026-10-27）

> 目的：为 `docs/specs/2026-10-27-multi-vendor-live-notification-design.md` 提供可复核的原文出处。
> 调研方式：Anysearch 检索 + 官方文档页正文提取。**未对任何厂商后台发起登录，未改动 App 源码，未提交 git。**
> 证据分级：**【官网原文】** 官方文档站正文可直接支撑｜**【二手】** 第三方或论坛来源，仅作线索｜**【未取到】** 本次未取得正文，仅记录入口 URL。

---

## 1. Android 16 原生 Live Updates

| 项 | 内容 |
| --- | --- |
| 官方文档 | <https://developer.android.com/develop/ui/views/notifications/live-update>（**【未取到正文】** 抓取失败，以下要点来自检索摘要与第三方文档交叉） |
| Compose 版 | <https://developer.android.com/develop/ui/compose/notifications/live-update>（**【未取到正文】**） |
| 中文版 | <https://developer.android.com/about/versions/16/features/progress-centric-notifications?hl=zh-cn>（**【未取到正文】**） |

**【二手，但引用官方条款】** OneSignal 文档 <https://documentation.onesignal.com/docs/en/android-live-notifications> 给出可直接引用的要点：

- 「Android documents them as Live Updates, while the underlying APIs call them **promoted ongoing notifications**.」
- 三项判定标准：**ongoing**（进行中，有明确起止）、**user-initiated**（用户主动发起）、**time-sensitive**（全时段值得关注）。
- 官方明确列为**不适合**提升的内容：**广告、推广、聊天消息、提醒、即将到来的日历事件、App 功能快捷方式**。
- 明确列为**适合**的：进行中的导航、通话、行程、外卖、锻炼、文件上传、用户主动关注的比赛。
- 版本分层：
  - `Notification.ProgressStyle`、`setShortCriticalText`、`hasPromotableCharacteristics()`、`canPostPromotedNotifications()` 在 **API 36** 加入；
  - `setRequestPromotedOngoing` 与 `EXTRA_REQUEST_PROMOTED_ONGOING` 在 **API 36.1** 加入，因此 `compileSdk 36` 下必须用字符串 key 请求提升；
  - 提升形态只在 **Android 16 QPR1（API 36.1）及以上**出现，Android 16.0 及更低版本通知仍会就地更新，只是**不被提升**。
- 提升后表现：置于通知抽屉顶部、锁屏醒目位置、抽屉收起时以 **status chip** 出现在状态栏。
- 用户可以把实时更新降级回普通通知，也可以对整个 App 关闭提升。
- `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` + `Settings.EXTRA_APP_PACKAGE` 用于引导，**启动前需 resolveActivity**。
- 被路由的第三方形态：**Samsung 会把 Live Updates 路由到 Now Bar**，并在 AOD 上呈现。

资格条件的另一种表述（检索摘要，来自官方 Compose 页）：通知必须是 `Standard` / `BigTextStyle` / `CallStyle` / `ProgressStyle` 之一。

**【二手线索】** 社区问题 `stackoverflow.com/questions/79672535` 报告 `canPostPromotedNotifications()` 长期返回 false、菜单缺失 chip 的现象（本次未取到正文，仅记录现象与 URL）。

---

## 2. OPPO ColorOS 流体云 / 泛在服务

### 2.1 官方文档入口（**【官网原文】，已取正文）

| 页面 | URL | 本次取到的关键内容 |
| --- | --- | --- |
| 流体云模板 2.0 | <https://open.oppomobile.com/documentation/page/info?id=12658> | 通用模板 / 对称模板的完整字段表、信息流转表、示例代码 |
| 服务接入指南 · 接入准备 | <https://open.oppomobile.com/documentation/page/info?id=13572> | 服务形态、服务信息、方案选型、卡片模板选型 |
| SeedlingSupportSDK 接入指南 | <https://open.oppomobile.com/new/developmentDoc/info?id=12719> | SDK 版本与变体、集成方式、`SeedlingCardWidgetProvider` 回调 |
| config.json | <https://open.oppomobile.com/documentation/page/info?id=12645> | `notification` 入口需在 `card-config.json` 的 `support` 字段支持 `notification_lg`；入口枚举含 `statusbar`、`息屏`、`headset` |
| 负一屏平台合作协议 | <https://open.oppomobile.com/new/developmentDoc/info?id=10213> | 负一屏/卡片的法律与分发条款 |
| 开放平台首页 | <https://open.oppomobile.com/> | 智慧服务目录含「小布建议 / 负一屏 / 流体云 / 设计规范 / 服务接入指南」 |

### 2.2 流体云模板 2.0 原文要点（通用模板）

声明方式：

```xml
<card-template entry="notification" category="general">
```

对称模板：

```xml
<card-template entry="notification" category="symmetry">
```

通用模板字段表（原文摘录）：

| 信息 | 描述 | level | 组件类型 | 必填 | 分组 |
| --- | --- | --- | --- | --- | --- |
| `A*` | 服务图标 | 0:A* | `<image><lottie><widget>` | 是 | |
| `B*` | 核心信息 | 1:B* | `<text>` | 是 | |
| `B1*` | 核心信息 | 1-1:B1* | `<text>` | 是 | |
| `A1*` | 服务图标 | 0-1:A1* | `<image>` | 是 | G1 |
| `C1` | 服务名称 | 2-1:C1 | `<text>` | 是 | G1 |
| `C2`/`C3` | 辅助信息 | 2-2 / 2-3 | `<text>` | 否 | |
| `D`/`D1` | 图标/图片/动图/小组件 | 3 / 3-1 | `<image><lottie><widget>` | 否 | 仅车机，支持车机则必选 |
| `C4` | 辅助信息 | 2-4 | `<text>` | 否 | G3 |
| `D2` | 图标 | 3-2 | `<image>` | 否 | G3 |
| `D3` | 图标/图片/动图/小组件 | 3-3 | `<image><lottie><widget>` | 否 | 和 E1/E2/E3 不能共存 |
| `D4` | 进度条 | 3-4 | `<progress>` | 否 | G2 |
| `D5` | 图标/图片/动图 | 3-5 | `<image><lottie>` | 否 | G2，会出现在 D4 当前进度位置 |
| `E1`/`E2`/`E3` | 按钮 | 4-1/4-2/4-3 | `<button>` | 否 | 与 D3 互斥；图形按钮最多 2 个 |
| `F` | 整体背景 | 5:F | — | 是 | 支持 `bg-image` / `bg-video` |
| `Z` | 语音播报内容 | — | `<voice>` | 否 | |

信息流转表（通用模板，原文摘录）：

| 入口 | 展示信息 |
| --- | --- |
| 入口1 直板/大折/平板状态栏胶囊 | `A*+B*` |
| 入口2 直板/大折/平板状态栏卡片 | `A1*+C1+F3+B1*+F1+D1+C2+C3+C4+D2+F2+D3+E1+E2+E3+D4+D5` |
| 入口3 直板/大折/平板状态栏气泡 | `A*` |
| 入口4 锁屏 | 同入口2 |
| 入口5 AOD | `A1*+B1*+C2` |
| 入口6 外屏底部胶囊 | `A1*+B1*+C2` |
| 入口7 外屏通知中心 | `A1*+B1*+C2` |
| 入口12 车机卡片 | `A1*+B1*+C1+C2+D*` |
| 入口11 耳机播报 | `Z` |

模板设计规则（原文摘录）：`"*"` 表示必选信息；`[A]、[B*]` 主要用于状态栏胶囊/气泡，`[A1*]、[B1*]` 用于其他触点，两组相互独立；`[D3]` 为图片信息时不可显示按钮 `[Ex]`；背景 `[F1]` 常态为高斯模糊；`[G1]` 由 `[A1*]` + `[C1]` 构成，缺一不出现；`[G2]` 由 `[D4]` + `[D5]` 构成，支持设置分段。

进度条示例（原文）：

```xml
<div level="6-2:G2">
  <image level="3-5:D5" src="{{ $r('images.logo') }}"></image>
  <progress level="3-4:D4" step="2" color="#FFCC24" bg-color="rgba(255,255,255,0.2)" percent="{{ percent }}"></progress>
</div>
```

### 2.3 SeedlingSupportSDK 原文要点

- 定位：「OPPO 提供给第三方应用开发泛在卡片服务的开发工具包」，提供①感知生命周期 ②更新卡片数据 ③主动推荐出卡。
- 三步接入：申请泛在卡片服务 → 开发 UPK 提供 UI 布局 → 接入 SeedlingSupportSDK 提供数据。**「申请泛在卡片服务，请联系 OPPO。」**
- 三种变体，版本 **3.0.7**：

| 变体 | 说明 | AAR 体积 |
| --- | --- | --- |
| `com.oplus.pantanal.card:seedling-support-lite` | 只支持泛在服务 | 252.7 KB |
| `...:seedling-support-liteQuick` | 支持快应用轻卡 + 泛在部分功能 | 283 KB |
| `...:seedling-support-external` | 同时支持泛在与速览接入 | 574 KB |

- 三个变体**均自动初始化**，无需额外 init。
- 必须继承实现 `SeedlingCardWidgetProvider`，其回调在**子线程**执行（不可处理 UI）：`onCardCreate` / `onShow` / `onHide` / `onDestroy` / `onSubscribed` / `onUnSubscribed` / `onCardObserve` / `onUpdateData`；更新数据调用 `updateData(card, bundle)`。
- 流体云尺寸回调：状态栏入口下，胶囊 ↔ 面板切换会触发 size 变化，尺寸常量 `Constants.FluidCloudSize.NOTIFICATION_SM`（胶囊）与 `Constants.FluidCloudSize.NOTIFICATION_LG`（面板）。

### 2.4 接入准备原文要点

- 服务形态：**流体云：状态栏、通知中心、锁屏**；小布建议：桌面卡、负一屏（含 2*2、2*4）；负一屏；跨端设备：车机、手表、耳机。
- 需向 OPPO 提供：QPS 峰值、QPS 均值、单日请求总量、单次履约请求量、请求频率、应用包名、开发者 ID（主账号）、AppID；**首次接入需注册 OPPO 开放平台并完成企业认证**。
- 方案选型：
  - **云侧方案**：适用于订单派送、航班火车、团购提醒等无需端侧保活的场景，需确定账号关联方式；
  - **端侧方案**：适用于导航、本地短时提醒等依赖端侧进程存活的场景。
- 卡片模板：进度可视化模板（打车/外卖进度）、强调信息模板（取餐/取号）、赛事模板、对称模板（航班/火车）、大图形模板（导航）。
- 其他因素：是否有落地页与 deeplink、是否接入微信小程序、是否支持多实例。

### 2.5 项目既有调研（本地文档，非本次新取）

`docs/specs/课程表APP技术方案.md` §6.3 已记录：ColorOS 不存在公开的字段级课程表同步 SDK；WakeUp、超级课程表走的是**小布建议团队与头部 App 逐一商务对接的白名单合作（NDA）**；意图共享核心数据单元为 `IntelligentIntent`，`intentAction.actionStatus` 三态为 **0 创卡 / 1 刷新 / 2 销卡**；`intentName`、`serviceId`、垂域 `entityName`、`milestone` 码值均需商务提供。

---

## 3. 小米 HyperOS 超级岛 / 焦点通知

**官方文档（**【官网原文】，已取正文）：<https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131>
页面标题「开发指南｜小米澎湃OS开发者平台」，更新时间 **2026-01-29 10:51:01**，路径为「开发文档/应用开发/服务能力/**小米超级岛**/开发指南」。另见 <https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2146>（同站，未取正文）。

原文要点：

- 两种接入方式：**客户端实现**、**MIPUSH 服务实现**。
- 客户端实现：按原生通知流程构建，然后注入扩展参数：
  ```java
  notification.extras.putString("miui.focus.param", islandParams);
  notificationManager.notify(1, notification);
  ```
- 岛通知内容包含四部分：**交互能力数据、摘要态数据、焦点通知数据**（结构为 `{"param_v2": { 交互能力数据 / 大岛内容数据 / 小岛内容数据 / 通知内容数据 }}`）。
- 通知属性数据字段表：

| 参数 | 必选 | 类型 | 含义 |
| --- | --- | --- | --- |
| `business` | 是 | String | 运营场景（数据统计） |
| `islandFirstFloat` | 否 | boolean | 首次出现是否自动展开态，默认 `true` |
| `enableFloat` | 否 | boolean | 更新时是否自动展开，默认 `false` |
| `timeout` | 否 | int | 通知默认消失时间，单位 min，默认 **720** |
| `cancel` | 否 | boolean | 是否直接结束通知（MIPUSH 下为移除通知） |
| `updatable` | 否 | boolean | 是否为持续性通知，默认 `false` |
| `reopen` | 否 | String | 相同 id 被 cancel 后再次发送是否显示，前提 `update=true`；`reopen`/`close`，默认 `close` |
| `filterWhenNoPermission` | 否 | boolean | 焦点通知权限关闭时：`false` 通知正常显示不被过滤；`true` 通知后被过滤不显示。默认 **false** |
| `orderId` | 否 | String | 订单号（MIPUSH 字段） |
| `sequence` | 否 | long | 本订单第几次更新（MIPUSH 实时更新类**必传**） |
| `param_island` | 是 | Object | 岛相关数据 |
| `carType` / `carColor` | 否 | String | 汽车类型/颜色 |

- 岛属性数据字段表：

| 参数 | 必选 | 类型 | 含义 |
| --- | --- | --- | --- |
| `islandProperty` | 否 | int | `1` 信息展示为主 / `2` 操作为主，默认 `1` |
| `islandOrder` | 否 | boolean | 更新时摘要态隐藏是否更新岛排序，默认 `false` |
| `islandTimeout` | 否 | int | 岛自动消失时间，单位 s，默认 **3600** |
| `dismissIsland` | 否 | boolean | 摘要态消失 |
| `highlightColor` | 否 | String | 文字强调色 |
| `bigIslandArea` | 是 | Object | 大岛内容 |
| `smallIslandArea` | 是 | Object | 小岛内容 |
| `shareData` | 否 | Object | 拖拽分享参数：`pic` / `title` / `content` / `shareContent` |

- 息屏显示数据：`aodTitle`（必填，息屏焦点通知显示文案）、`aodPic`（否，息屏模式图标，不传默认应用图标，自定义需与 `miui.focus.pics` 对应）。
- MIPUSH 侧：沿用现有接口，扩展参数 key 为 `miui.focus.param` 与 `miui.focus.pic_XXX`，**仅支持 regId 发送**。
- MIPUSH 图片要求（原文）：
  - 单张图片大小**不得超过 100k**，超过则不下载；
  - 下载全流程最大等待 **180s**，超时失败；
  - 图片链接必须 **HTTPS**；
  - 宽高比在 **1.78(16:9) – 1(1:1)** 之间；
  - 单个通知图片数量不大于 10 张，若超过 6 张，XMSF 只会按顺序下载 10 张。
- 模板库：《小米超级岛模板库》PDF，原文给出 mag-admin 链接（`https://mag-admin.cnbj1.mi-fds.com/mag-admin/island/小米超级岛模板库20260129.pdf`），不同模板对应不同的大岛/小岛/焦点通知字段。

---

## 4. 荣耀 MagicOS 灵动胶囊

**官方文档页（**【官网原文】导航已取，正文未取到目标节）**：<https://developer.honor.com/cn/doc/guides/101571>
页面确认平台有一级菜单「服务分发 → **荣耀灵动胶囊**」，以及「服务分发 → 智慧服务 → 快捷服务 / 快应用 / 快应用卡片（JS 卡片）」多条线路。

**智慧服务配置指南**：<https://developer.honor.com/cn/doc/guides/100158>（【官网原文】，正文为导航与服务配置说明，未含胶囊字段）——原文要求「登录荣耀开发者服务平台 → 管理中心 > 生态服务 > 智慧服务平台 → 签署《荣耀智慧服务平台合作协议》」。

**推送约束（**【官网原文】摘要）**：
- <https://developer.honor.com/cn/docs/11002/guides/notification-push-standards>：「荣耀推送服务实施消息分类管理，**资讯营销类消息按应用类别设定每日推送数量上限（最高 5 条/日）**，服务与通讯类消息无限制。」
- <https://developer.honor.com/cn/docs/11002/guides/notification-class>：需申请**自分类权益**，避免默认归为资讯营销类。
- <https://developer.honor.com/cn/docs/11002/guides/configuration-content>：页内目录同时列出「荣耀灵动胶囊」与「推送服务配置内容」。

**【二手线索，未取到官方正文】** 荣耀论坛帖 <https://club.honor.com/cn/thread-30163837-1-1.html>（标题「灵动胶囊接入安卓实时通知规范」）与 <https://linux.do/t/topic/3064474> 指向同一判断：**荣耀灵动胶囊接入 Android 16 实时通知规范**（后者标题作「个人开发者如何把应用送上荣耀灵动胶囊？Android 16 实时通知 Demo 指南」；该页正文含针对 AI 抓取的拒绝声明，**本文未采用其正文内容，仅记录标题与 URL 作为线索**）。另有 MagicOS 8.0 灵动胶囊功能说明帖 <https://club.honor.com/cn/thread-28457439-1-1.html>。

荣耀官方用户侧说明：<https://www.honor.com/cn/support/content/zh-cn15867850/>（设置 > 通知和状态栏 > 灵动胶囊 开关）。

---

## 5. vivo OriginOS 原子岛 / 原子通知

**【官网原文，已取正文】** <https://dev.vivo.com.cn/documentCenter/doc/896>（文档中心首页导航）与 <https://dev.vivo.com.cn/documentCenter/doc/359>（通知相关规范页）。关键原文：

- 平台一级导航含「**vivo 通知栏 → 规范介绍**」（<https://dev.vivo.com.cn/documentCenter/doc/577>）、「Android 适配」「折叠屏适配」「vivo 桌面」等。
- 通知相关规范页原文：「通知，指出现在通知栏、锁屏、悬浮的应用消息。」
- 智慧服务线：意图框架 <https://developers.vivo.com/product/ai/consciousnessPL>、蓝心智能开放平台 <https://developers.vivo.com/product/ai/blueSmartPL>。

**【官网/第三方摘要】** 原子通知规范页 <https://dev.vivo.com.cn/wap/documentCenter/doc/895>：「原子通知作为一种通知形态，接收事件的实时变化信息，有明确的预期，通常为用户主动行为触发……详细定义原子通知触发的前置条件和时机。」

**【第三方推送接入文档，引用 vivo 官方前置条件】** 阿里云 <https://help.aliyun.com/zh/document_detail/3030718.html>：「已参考**原子通知接入指导申请原子岛接入权限**」「已参考原子通知规范依据样式……」——即**原子岛接入权限为前置**。

**【第三方】** 原子岛产品演进：OriginOS 5 将原子通知升级为原子岛、与前置摄像头融合，可展示通知、音乐播控、快捷操作、应用进度（<https://news.mydrivers.com/1/1005/1005485.htm>、<https://finance.sina.com.cn/tech/digi/2025-06-11/doc-ineztfhw4487045.shtml>）；用户侧设置路径「设置 > 通知与状态栏 > 原子岛」（<https://www.vivo.com.cn/service/questions/all?categoryId=170&questionId=1751>）。

---

## 6. 华为 HarmonyOS 实况窗（Live View Kit）

**【官网原文，已取正文】** 模块页：<https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-liveviewmanager>
**Live View Kit 简介**（导航 + 目录已取）：<https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-introduction>（更新时间 2026-08-29 09:41）
**指南入口**：<https://developer.huawei.com/consumer/cn/doc/doccenter-capabilities/live-view-kit-guide>
**SDK 页**：<https://developer.huawei.com/consumer/cn/sdk/live-view-kit>

原文要点：

- Live View Kit 位于「应用服务」分类下，同级含 Account Kit、Calendar Kit、Notification Kit、Push Kit 等。
- `liveViewManager` 模块定位原文：「本模块提供 Live View Kit 的基础能力，包括**创建、更新和结束实况窗、获取实况窗和检查实况窗开关**的功能。起始版本：4.1.0(11)」，适用 **Phone 5.0.0(12)+ / PC·2in1 5.0.1(13)+ / Tablet 5.0.0(12)+**。
- 相关 API：`LiveViewCardExtensionAbility`、`LiveViewLockScreenExtensionAbility`、`LiveViewLockScreenExtensionContext`、`LiveViewCardExtensionContext`、ArkTS API 错误码、**「实况窗 Live View Kit 与 Push Kit 的 API 字段关联」**。
- 指南目录含：场景介绍、时段性、时效性、变化性、产品优势、支持的范围与场景、**实况窗场景准入原则**、实况窗支持对接的场景、**受限说明**、**通用约束限制**、**通过 Push Kit 创建和更新实况窗的约束限制**、基于地理位置的实况窗提醒功能约束限制。
- **生命周期上限**（检索摘要引用官方页）：「**单个实况窗的生命周期最长不超过 8 小时，超过 8 小时后，系统会认为通知结束。**」同时见于 <https://github.com/liasica/harmonyos-skills/blob/master/harmonyos/references/harmonyos-guides/push-update-liveview.md>。
- **Push 创建场景白名单**（检索摘要引用官方页）：「通过 Push Kit 创建实况窗当前仅支持 **FLIGHT、TAXI、TRAIN、DELIVERY、QUEUE、RENT、EXPRESS、CHECK_IN、TRADE** ……」——**教育/课程不在其列**。远程创建另有频率限制（阿里云文档 <https://help.aliyun.com/zh/document_detail/2982112.html> 提到「检查是否触发频率限制」「远程创建实况窗限制」）。

---

## 7. Apple iOS Live Activities

**【官网原文，已取正文】** <https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities>
**【官网原文，检索摘要引用】** <https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications>

原文要点：

- 展示位置：**Lock Screen、Dynamic Island、CarPlay、配对的 Mac / Apple Watch**；UI 用 SwiftUI，生命周期由 ActivityKit 管理。
- **必须支持全部形态**：系统会在用户各设备间自动选择最佳形态。
- Dynamic Island 形态：
  - `compact`：由 leading（TrueDepth 摄像头左侧）与 trailing 两个元素组成，是两个独立 view 但在岛上形成整体；
  - `minimal`：多活动并存且设备无法同时展示两个 compact 时使用；也出现在 StandBy 锁屏顶部；
  - `expanded`：长按 compact/minimal 触发；
  - 举例：**iPhone 18 Pro 可同时展示两个** compact 活动（宽度收窄），其他设备两个活动都显示为 minimal，一个贴合灵动岛、另一个脱离。
- **时长上限**：活跃最长 **8 小时**，之后系统自动结束并**立即从灵动岛移除**；锁屏上再保留最多 **4 小时**（或用户移除），即**锁屏总计最长 12 小时**。
- **资源限制**：图片资源分辨率必须 ≤ 该设备展示尺寸，否则系统可能**启动失败**。
- **沙箱隔离**：每个 Live Activity 独立沙箱，**不能访问网络、不能接收位置更新**；动态数据只能由 App 内 ActivityKit 或 ActivityKit 推送更新。
- 工程要求：加 Widget Extension（勾选 Include Live Activity）；`Info.plist` 设 `NSSupportsLiveActivities = YES`（或作为 custom iOS target property 设置）。
- **推送预算**（原文）：系统按小时给一定量的 ActivityKit 推送预算；超限会被 **throttle**；把 `apns-priority` 设为 **5** 的低优先级推送**不计入预算**，可先试 5；**混用 5 与 10 通常能避免被节流**；需要更频繁更新时可开 `ActivityAuthorizationInfo().frequentPushesEnabled`（higher update budget）。
- payload 结构原文示例：`aps.timestamp`、`aps.event`（`update`）、`aps.relevance-score`（100，用于多活动排序）、`aps.content-state`。
- 论坛实证（**【二手】** <https://developer.apple.com/forums/thread/799505>）：短时间内连续发送多个 push-to-start，**约 10 次后**新活动不再能启动，提示「Live Activity budget exceeded」；另有帖子报告预期 3 秒刷新实际变成 30–60 秒甚至不刷新（<https://developer.apple.com/forums/thread/797554>、<https://developer.apple.com/forums/thread/797540>）。

---

## 8. 本项目既有实现（本地代码，非外部证据）

| 事实 | 出处 |
| --- | --- |
| `compileSdk=36`、`targetSdk=36`、`minSdk=26`、`versionName=1.0.13` | `app/build.gradle.kts` L18-L31 |
| 用 `ProgressStyle` + `Segment(100).setColor()` 画进度 | `CapsuleNotifier.applyProgressStyle()` |
| 用字符串 key `android.requestPromotedOngoing` 请求提升 | `CapsuleNotifier.EXTRA_REQUEST_PROMOTED_ONGOING` |
| 实时卡片 ID 3001 与 FGS 骨架 ID 3000 分离 | `CapsuleNotifier` L33-L41 |
| 提升路径调用了 `setColor` | `CapsuleNotifier` L300 |
| 只查询 `canPostPromotedNotifications()`，无引导入口 | `CapsuleNotifier.canPromote()` L64-L69 |
| 倒计时交系统自绘（`setUsesChronometer` + `setChronometerCountDown`） | `CapsuleNotifier` L290-L293 |
| 系统级 `setTimeoutAfter` 兜底撤卡 | `CapsuleNotifier` L121-L123 |
| 30s 刷新循环 + 卡片缺失自愈重建 | `LiveCapsuleService` L78-L106 |
| 实时态持 partial wake lock（4h 上限） | `LiveCapsuleService` L116-L146 |
| 电池优化白名单引导（ColorOS 速冻场景） | `CapsuleReliability` |
| 状态机为纯数据（`Ongoing/AboutToStart/Upcoming/Holiday/DayDone/NoTable`） | `CapsulePlan` |

---

## 9. 本次未取到正文的页面（仅记录入口）

| 页面 | URL | 备注 |
| --- | --- | --- |
| Android 16 Live Updates（视图版） | <https://developer.android.com/develop/ui/views/notifications/live-update> | extract 失败；要点经 OneSignal 交叉 |
| Android 16 Live Updates（Compose 版） | <https://developer.android.com/develop/ui/compose/notifications/live-update> | extract 失败 |
| Android 16 进度中心通知（中文） | <https://developer.android.com/about/versions/16/features/progress-centric-notifications?hl=zh-cn> | extract 失败 |
| 荣耀「荣耀灵动胶囊」开发文档正文 | <https://developer.honor.com/cn/doc/guides/101571> | 仅取到导航；胶囊字段级页面未定位 |
| vivo 原子通知规范 | <https://dev.vivo.com.cn/wap/documentCenter/doc/895> | extract 失败；要点经检索摘要 |
| vivo 通知栏规范介绍 | <https://dev.vivo.com.cn/documentCenter/doc/577> | extract 失败 |
| 华为《开发实况窗场景》《受限说明》正文 | <https://developer.huawei.com/consumer/cn/doc/doccenter-capabilities/liveview-scenes> | 未取正文 |
| 小米超级岛模板库 PDF | mag-admin 直链 | PDF 不在 extract 支持范围 |
| 小米 HyperOS 文档 pId=2146 | <https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2146> | 同站另一页，未取正文 |

---

## 10. 检索质量备注

- 本轮 `web_fetch` 因抓取端点鉴权失败（HTTP 401）全程不可用，外部内容全部经 Anysearch 的 `search` / `batch_search` / `extract` 获得；`extract` 对 `developer.android.com` 恒定失败，对 Apple / 华为 / 小米 / OPPO 文档站成功。
- 凡标注**【二手】**的条目（荣耀接入规范、vivo 权限前置、苹果预算实证）均**未取到官方正文**，引用时需按线索对待。
- `linux.do` 页面正文含针对自动化抓取的拒绝声明，本档**仅记录其标题与 URL 作为线索，未采用其正文内容**。
