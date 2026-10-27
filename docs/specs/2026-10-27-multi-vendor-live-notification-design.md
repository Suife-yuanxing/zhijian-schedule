# 多厂商实时通知适配设计（流体云 / 实况窗 / 灵动胶囊 / 超级岛 / 灵动岛）

> 版本：v1.0　｜　编制日期：2026-10-27　｜　适用项目：纸间课表（Android / Kotlin，`com.zhijian.schedule`）
> 前置文档：`docs/specs/课程表APP技术方案.md`（§5.6 通知与系统建议样式、§6 OPPO 接口规范、§7 其他厂商生态对比）
> 证据文档：`docs/research/2026-10-27-vendor-live-notification-docs.md`
> 本文回答的问题：把已有的「流体云胶囊」这一套设计，扩成能同时适配 Android 原生 / OPPO / 小米 / 荣耀 / vivo / 华为 / 苹果 的通知效果，并给出可落地的统一适配层。

---

## 0. 一页结论

1. **现有实现已经无意间拿到了跨厂商的最大公约数**。项目当前走的是 **Android 16 原生 Live Updates**（`Notification.ProgressStyle` + `android.requestPromotedOngoing`），ColorOS 16 只是把原生实时通知渲染成了「流体云」。这意味着**原生路径本身就是 OPPO / 荣耀 / 三星等多家共用的地基**，不需要为 OPPO 单独做一套。
2. **厂商分成三条技术路线，成本差了一个数量级**：
   - 路线 A（零资质）：Android 16 原生 Live Updates，靠通知 API 本身。覆盖 OPPO ColorOS 16、荣耀 MagicOS、三星 Now Bar 等「已经收编原生规范」的 ROM。
   - 路线 B（零资质 / 需申请）：厂商私有的通知扩展参数。小米超级岛只需要 `notification.extras.putString("miui.focus.param", ...)`，**不改 SDK、不换服务、不加权限**，是目前性价比最高的第二家。
   - 路线 C（需企业主体 + 商务对接）：厂商生态卡片。OPPO 泛在服务 `.upk` + SeedlingSupportSDK、华为实况窗（ArkTS，且课程不在其 Push 白名单场景）、vivo 原子岛权限、荣耀智慧服务协议——这一层是「放大器」不是「地基」，与既有技术方案 §6 的判断一致。
3. **苹果是独立工程，不是适配**。Live Activity 属于 WidgetKit 扩展 + SwiftUI，需要另建 iOS 工程；本次只做设计对齐（字段、时长上限、预算模型），实现排在 Android 多厂商之后。
4. **文件级发现 5 个问题**（见 §1.3），其中 2 个会直接影响提升成功率，建议先修再扩展。
5. **建议的扩厂商顺序**：先修原生提升链 → 小米超级岛（改 20 行）→ 荣耀（走原生规范，验机确认）→ vivo（申请权限）→ OPPO 泛在服务（企业主体后）→ 华为（另立 ArkTS 工程）→ 苹果（另立 Swift 工程）。

---

## 1. 本地现状盘点

### 1.1 代码地图

| 文件 | 职责 | 关键常量 / 方法 |
| --- | --- | --- |
| `core/capsule/CapsulePlan.kt` | 纯数据状态机（可单测） | `sealed interface CapsulePlan`：`Ongoing` / `AboutToStart` / `Upcoming` / `Holiday` / `DayDone` / `NoTable`；`reminderMilestones()`；`isLive` |
| `core/capsule/CapsuleNotifier.kt` | 通知构建与提升请求 | `CH_CAPSULE`、`SERVICE_NOTIFICATION_ID=3000`、`LIVE_CARD_ID=3001`、`EXTRA_REQUEST_PROMOTED_ONGOING`、`canPromote()`、`post()` |
| `core/capsule/LiveCapsuleService.kt` | 前台服务 + 30s 刷新循环 + partial wake lock | `REFRESH_INTERVAL_MS=30_000`、`WAKE_LOCK_TIMEOUT_MS=4h`、卡片缺失自愈重建 |
| `core/capsule/CapsuleCoordinator.kt` | 精确闹钟驱动的状态切换、`renderNow()` | `canScheduleExactAlarms()` 降级到 `setAlarmClock` |
| `core/capsule/CapsuleTransitionReceiver.kt` | 提醒窗口 / 上课 / 下课 / 跨天的闹钟入口 | — |
| `core/capsule/CapsuleReliability.kt` | 电池优化白名单引导 | `requestIgnoreBatteryOptimization()`、`openAppDetails()` |
| `AndroidManifest.xml` L79-88 | FGS 声明 | `foregroundServiceType="specialUse"` + `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` |

构建配置：`compileSdk=36`、`targetSdk=36`、`minSdk=26`、`versionName=1.0.13`。

### 1.2 当前生效路径

```
精确闹钟（状态切换）
        │
        ▼
LiveCapsuleService（FGS specialUse，30s 循环，实时态持 wake lock）
        │  CapsuleCoordinator.renderNow()
        ▼
CapsuleNotifier.post()
        ├── LIVE_CARD_ID=3001  实时卡片（非 FGS）
        │     ├── setShortCriticalText(chip)          → 状态栏 chip
        │     ├── ProgressStyle + Segment(100)        → 进度条
        │     ├── setUsesChronometer + CountDown      → 系统自绘倒计时
        │     ├── setTimeoutAfter(...)                → system_server 兜底撤卡
        │     └── extras["android.requestPromotedOngoing"]=true → 请求提升
        └── SERVICE_NOTIFICATION_ID=3000  FGS 骨架（常驻，非实时态承载文本）
```

三条关键设计已经做对了，值得在扩展时保住：

- **实时卡片与 FGS 骨架分离**。FGS 通知被系统豁免 `timeoutAfter`，永远撤不下来；实时卡片必须是普通通知才能被系统准点撤下。这条是 2026-10-16 21:35 事故换来的，任何厂商适配都不能破坏它。
- **倒计时交给系统自绘**（`setUsesChronometer`），进程被 ROM 冻结也照常走秒。
- **状态机是纯数据**（`CapsulePlan`），厂商适配完全可以在渲染层做，不碰状态机。

### 1.3 发现的问题（含一轮勘误）

> **勘误说明（2026-10-28）**：本节初版部分结论来自未经核实的推测，实施阶段逐条复核后已修正。
> 勘误保留原编号，避免与后续讨论失联；「状态」列标明是否已落地。

| # | 问题 | 依据 | 状态 |
| --- | --- | --- | --- |
| P1 | 提升在 `SDK_INT >= 36` 就请求，但 **promoted 形态实际需要 Android 16 QPR1（API 36.1）**；API 36.0 上通知仍会就地更新，只是不被提升 | OneSignal 文档：`setRequestPromotedOngoing` 与 `EXTRA_REQUEST_PROMOTED_ONGOING` 在 API 36.1 加入；promotion 只在 16 QPR1+ 出现 | **已修**：新增 `LiveSurfaceSupport.supportsPromotedTreatment()`，把「能否请求提升」与「能否真正被提升」拆成两个判断 |
| P2 | ~~提升路径调用 `setColor` 导致失格~~ | **原判断有误**。官方一票否决项是 `setColorized(true)`（整条通知彩色化），不是 `setColor`（元素着色）；本项目从未调用 `setColorized` | **已撤稿并加固**：代码里移除遗留的 `setColor` 调用（它虽不致命但语义混乱），并在 `baseBuilder` 注释里写明两者区别，防止后人误加 `setColorized` |
| P3 | 只调用 `canPostPromotedNotifications()` 查询，不引导用户去开关页 | `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS` + `EXTRA_APP_PACKAGE` | 部分解决：现有「去开启」入口走 `ACTION_APP_NOTIFICATION_SETTINGS` 已能到达应用通知页；更精确的提升专用页需按版本另做 |
| P4 | 未调用 `hasPromotableCharacteristics()` 自检 | API 36 提供 | 未做（优先级低于 P6，见下） |
| P5 | 渲染层与 Android 原生 API 强耦合，无厂商适配层 | `CapsuleNotifier` 直接 new `Notification.Builder` | **已修**：抽出 `LiveSurfaceRenderer` + `LiveContent` + `LiveSurfaceRenderers` 注册表，见 §4 |
| **P6** | **服务会强制重发用户已划掉的实时卡片** | 官方明确：应用不得重新发布用户已关闭的动态，否则**可能被撤销该应用的发布权限**；原 `LiveCapsuleService` 每 30s 检测 `activeNotifications`，卡片不在就无条件重建 | **已修（本轮最高危项）**：新增 `CapsuleDismissTracker` + 卡片 `setDeleteIntent`，用户划掉后本场次不再重发，离开实时态自动清账 |
| **P7** | 缺少 `POST_PROMOTED_NOTIFICATIONS` 权限 | 提升硬条件之一 | **无需处理**：`AndroidManifest.xml:13` 已声明（初版漏检） |

> **P6 的细节**：原实现无法区分「系统 `timeoutAfter` 到期正常退场」与「用户手动划掉」——前者应该自愈重建，后者必须尊重。
> 修正方式是用 `setDeleteIntent`：只有用户主动清除才触发它，`timeoutAfter` 由 system_server 撤下时不走 deleteIntent。
> 这是本轮改动里唯一直接影响用户信任与权限存续的修复。

---

## 2. 厂商能力全景

### 2.1 目标平台对照表

| 平台 | 官方能力名 | 技术路线 | 接入门槛 | 与原生提升的关系 |
| --- | --- | --- | --- | --- |
| Android 16（AOSP） | Live Updates / promoted ongoing notification | A | 无 | 地基 |
| OPPO ColorOS 16 | 流体云（Pantanal 泛在服务） | A（原生）+ C（.upk） | 原生零资质；.upk 需企业认证 + 商务 | ColorOS 16 把原生提升渲染为流体云 |
| 小米 HyperOS | 焦点通知 / 小米超级岛 | B | 通知参数即可；MIPUSH 侧需推送权限 | 走 `miui.focus.param` 扩展参数，非原生提升 |
| 荣耀 MagicOS | 灵动胶囊 | A（原生规范）+ C（智慧服务） | 原生规范；智慧服务需签协议 | 论坛信息指向「接入安卓实时通知规范」 |
| vivo OriginOS 5+ | 原子通知 → 原子岛 | C | **需申请原子岛接入权限** | 独立体系，与原生提升不互通 |
| 华为 HarmonyOS | Live View Kit（实况窗） | C（ArkTS） | 需 ArkTS 工程；Push 创建有场景白名单 | 完全不同技术栈 |
| Apple iOS | Live Activities + Dynamic Island | 独立工程 | 需 Widget Extension | 完全不同技术栈 |

### 2.2 三条技术路线的成本对比

| 路线 | 代表 | 改动位置 | 预计工作量 | 前置条件 |
| --- | --- | --- | --- | --- |
| A 原生提升 | AOSP / ColorOS 16 / MagicOS | `CapsuleNotifier` 内 | 已实现，修 P1-P4 约半天 | 无 |
| B 通知扩展参数 | 小米超级岛 | `CapsuleNotifier` 加一个 extras 分支 | 1 人日 | 无（先在真机验证） |
| C 生态卡片 | OPPO .upk / 华为实况窗 / vivo 原子岛 | 新增模块 + 新增工程 | 每家 1-3 周起 | 企业主体 / 商务协议 / 权限申请 |

**结论：先把 A 修干净，再吃 B，C 层按主体资质排期。** 这与技术方案 §6 的「MVP 零资质、厂商生态为远期」判断一致，但比原判断多了一条：**B 层（小米）其实也是零资质**，原文档把小米整体归到「需商务对接」是偏保守的。

---

## 3. 逐厂商接入契约

### 3.1 Android 16 原生（地基）

**资格条件**（提升为 Live Update 的必要条件）：

- 样式必须是 `Standard` / `BigTextStyle` / `CallStyle` / `ProgressStyle` 之一；
- 必须是常驻通知 + 用户主动发起 + 时效性活动；
- **不得着色、不得分组摘要、不得自定义视图**；
- 需要用户在系统里为本应用放行实时通知。

**API 分层**（这是 P1 的根源）：

| 能力 | 引入版本 | 说明 |
| --- | --- | --- |
| `Notification.ProgressStyle` | API 36 | 进度条样式 |
| `setShortCriticalText()` | API 36 | 状态栏 chip |
| `canPostPromotedNotifications()` | API 36 | 查询用户是否放行 |
| `hasPromotableCharacteristics()` | API 36 | 自检通知是否具备提升资格 |
| `FLAG_PROMOTED_ONGOING` | API 36 | 查询是否已被提升 |
| `setRequestPromotedOngoing()` / `EXTRA_REQUEST_PROMOTED_ONGOING` | **API 36.1** | 请求提升 |

**在 `compileSdk=36` 下的正确写法**：无法调用 36.1 的 setter，所以用字符串 key 请求提升——项目当前已经这么做（`addExtras(Bundle().putBoolean("android.requestPromotedOngoing", true))`），这部分是对的。

**引导用户开启提升**：

```kotlin
val intent = Intent(Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
// Android 不保证存在匹配页面，启动前必须 resolve
if (intent.resolveActivity(context.packageManager) != null) {
    context.startActivity(intent)
}
```

**不适用场景（重要）**：官方明确把「即将到来的日历事件」列为**不适合**提升的内容。课表本质上接近这一类，因此：

- ✅ 适合：**正在上课**（进行中、用户主动进入这个时段、时效性强）。
- ⚠️ 勉强：「即将上课」倒计时窗口，属于「用户已订阅的临近事件」，有被审核/系统判定为不合规的风险。
- ❌ 不适合：「下节课」「今日已结束」「假期」等非实时态——项目当前已经正确地把这些留在 FGS 骨架通知里（`promote=false`），**这个边界要写进代码注释防止后人在扩展时误提升**。

### 3.2 OPPO ColorOS 流体云

**两条路并存**：

**(a) 原生路径（当前已用）**：ColorOS 16 收编 Android 16 原生实时通知，渲染为状态栏胶囊。零资质、零商务。**风险**：ColorOS 对提升资格比 AOSP 宽松（项目实测在 setColor 的情况下仍能上岛），但这不代表其他 ROM 也宽松，也不保证未来版本不变——所以 P2 仍然要修。

**(b) 泛在服务路径（需企业主体）**：走潘塔纳尔（Pantanal）服务体系。

- 开发：JS + CSS + OML 模板 → 构建产物 `.upk`；工具为 Pantanal DevStudio；由 UMS（服务治理框架）发现/安装/运行/分发。
- 客户端数据通道：`SeedlingSupportSDK`，版本 3.0.7，三个变体二选一：
  - `com.oplus.pantanal.card:seedling-support-lite`（只有泛在服务，252.7 KB）
  - `com.oplus.pantanal.card:seedling-support-liteQuick`（+ 快应用轻卡，283 KB）
  - `com.oplus.pantanal.card:seedling-support-external`（+ 速览，574 KB）
  - SDK 自动初始化，无需手动 init；需继承实现 `SeedlingCardWidgetProvider`。
- 卡片模板（流派二选一，`category` 决定）：
  - `<card-template entry="notification" category="general">` 通用模板（进度条 + 按钮 + 背景图/视频）
  - `<card-template entry="notification" category="symmetry">` 对称模板（更适合「上课时间 → 下课时间」这种对称信息）
- **信息流转（一次接入，多端露出）**——这是流体云最值钱的地方：

| 入口 | 展示字段 |
| --- | --- |
| 状态栏胶囊 | `A*` + `B*` |
| 状态栏气泡 | `A*` |
| 状态栏卡片 / 锁屏 | `A1*`+`C1`+`B1*`+`D1`+`C2`+`C3`+`C4`+`D2`+`D3`+`E1`+`E2`+`E3`+`D4`+`D5` |
| AOD / 外屏胶囊 / 外屏通知中心 | `A1*`+`B1*`+`C2` |
| 车机卡片 | `A1*`+`B1*`+`C1`+`C2`+`D*` |
| 耳机播报 | `Z` |

- 关键字段（通用模板）：`A*` 服务图标（`image|lottie|widget`）、`B*` 核心信息（`text`，胶囊/气泡用）、`B1*` 核心信息（其他触点）、`A1*`+`C1` 组成 `G1`、`C2`/`C3` 辅助信息、`D3` 大图（与 `E1/E2/E3` 互斥）、`D4` 进度条 + `D5` 进度位置图标 组成 `G2`、`E1/E2/E3` 按钮（图形按钮最多 2 个）、`F` 背景（`bg-image`/`bg-video`）、`Z` 语音。
- 长按胶囊展开面板会触发 `onCardSizeChange`，尺寸常量 `Constants.FluidCloudSize.NOTIFICATION_SM`（胶囊）↔ `NOTIFICATION_LG`（面板）。
- `config.json` 的 `device` 入口枚举：`aod`、`statusbar`、`notification`、`lockscreen`、`headset`。
- 云侧方案还提供「意图共享」`IntelligentIntent`，用 `intentAction.actionStatus` 三态驱动卡片：`0` 创卡 / `1` 刷新 / `2` 销卡。

**课程场景映射（官方建议 + 本项目状态机的对应）**：

| 项目状态 | `actionStatus` | 里程碑 `milestone.text` | 流体云表现 |
| --- | --- | --- | --- |
| `AboutToStart` | 0 创卡 | 「15 分钟后上课」 | 胶囊 `A*+B*`：图标 + 「还有 15 分」 |
| `Ongoing` | 1 刷新 | 「上课中」 | 面板 `G2` 进度条随课堂进度消耗 |
| 提醒节点命中 | 1 刷新 | 「距下课 5 分」 | `Z` 语音播报 + `highlightColor` |
| 下课 / `DayDone` | 2 销卡 | — | 撤卡 |

**门槛**：`intentName`、`serviceId.fluidCloud`、教育垂域 `entityName`、`milestone` 码值均由 OPPO 商务提供，调测模板不可用于正式环境（技术方案 §6.3.3 已记录）。接入准备需提供 QPS 峰值/均值、单日请求总量、单次履约请求量、请求频率、包名、企业认证开发者 ID、AppID。

### 3.3 小米 HyperOS 超级岛

**这是第二家最值得做的，因为它在通知层就能完成**。

- 客户端实现：构建原生通知后，注入扩展参数——
  ```java
  notification.extras.putString("miui.focus.param", islandParams);
  notificationManager.notify(id, notification);
  ```
- 参数结构：`{"param_v2": { 交互能力数据 / 大岛内容数据 / 小岛内容数据 / 通知内容数据 }}`
- 关键字段：

| 组 | 字段 | 说明 |
| --- | --- | --- |
| 通知属性 | `business`（必填） | 运营场景 |
| | `islandFirstFloat` | 首次出现是否自动展开，默认 `true` |
| | `enableFloat` | 更新时是否自动展开，默认 `false` |
| | `timeout` | 默认消失时间，单位 min，默认 720 |
| | `updatable` | 是否持续性通知 |
| | `filterWhenNoPermission` | 焦点通知权限被关时：`false` 退化为普通通知继续显示；`true` 直接被过滤 |
| 岛属性 | `islandProperty` | `1` 信息展示为主 / `2` 操作为主 |
| | `islandTimeout` | 岛自动消失时间，单位 s，默认 3600 |
| | `dismissIsland` | 摘要态是否消失 |
| | `highlightColor` | 文字强调色 |
| | `bigIslandArea` / `smallIslandArea` | 大岛 / 小岛内容（均必填） |
| | `shareData` | 拖拽分享 `pic/title/content` |
| 息屏 | `aodTitle`（必填）/ `aodPic` | AOD 文案与图标 |
| MIPUSH | `miui.focus.param` + `miui.focus.pic_XXX` | 仅支持 `regId` 发送 |

- 图片限制：单张 ≤ 100 KB、必须 HTTPS、宽高比 1.78(16:9)–1(1:1)、单个通知 ≤ 10 张、下载全流程 ≤ 180 s。
- **注意**：`filterWhenNoPermission` 默认 `false`，即权限被关时通知退化为普通通知继续显示——这与本项目「低版本退化为普通常驻进度通知」的降级策略天然一致，可以放心使用默认值。
- `islandProperty=1`（信息展示为主）适合本项目；`islandTimeout` 需要按课堂时长设置，否则岛会提前消失。

### 3.4 荣耀 MagicOS 灵动胶囊

- 平台入口：`developer.honor.com` → **服务分发 → 荣耀灵动胶囊**（独立一级菜单，另有「智慧服务」下的快捷服务/快应用卡片线）。
- 生态路径需签署《荣耀智慧服务平台合作协议》（管理中心 > 生态服务 > 智慧服务平台）。
- 有公开信息表明荣耀「接入安卓实时通知规范」，即走 **Android 16 原生实时通知**（路线 A），与 OPPO 的处理方式同类。**此条为部分证实**：来源为荣耀论坛帖（`club.honor.com/cn/thread-30163837`），非官方开发者文档正文；本次检索未取到「灵动胶囊」的字段级开发文档页，需真机 + 开发者账号复核。
- 推送侧另有硬约束：荣耀推送服务按消息分类管理，**资讯营销类消息每日上限最高 5 条**，服务与通讯类无限制（需申请自分类权益）。课程提醒应归入**服务与通讯类**，否则课前提醒会被配额掐掉。

### 3.5 vivo OriginOS 原子岛

- 能力沿革：原子通知（OriginOS Ocean）→ 升级为**原子岛**（OriginOS 5，与前置摄像头融合，可展示通知、音乐播控、快捷操作、应用进度）。
- 官方文档：`dev.vivo.com.cn` 的《原子通知规范》（`documentCenter/doc/895`）、《vivo 通知栏 规范介绍》（`doc/577`）。
- **需申请「原子岛接入权限」**（第三方推送文档明确写出这一步为前置）。因此这条路是**路线 C**，不能自助开通。
- 产品定位（官方口径）：接收事件的实时变化信息、有明确预期、通常为用户主动行为触发——「正在上课」符合，「下节课」不符合，与 §3.1 的适用性判断一致。
- 另有 `意图框架` / `蓝心智能开放平台` 作为智慧服务入口，属更远期的生态位。

### 3.6 华为 HarmonyOS 实况窗（Live View Kit）

- 模块：`liveViewManager`（创建/更新/结束实况窗、获取实况窗、检查实况窗开关），另有 `LiveViewCardExtensionAbility`、`LiveViewLockScreenExtensionAbility` / `LiveViewLockScreenExtensionContext`。
- 版本基线：`Phone 5.0.0(12)+`、`PC/2in1 5.0.1(13)+`、`Tablet 5.0.0(12)+`。
- **硬约束**：单个实况窗生命周期**最长不超过 8 小时**，超过后系统视为通知结束。一节 45-100 分钟的课不受影响。
- **最大障碍**：通过 Push Kit **远程创建**实况窗，当前仅支持 `FLIGHT / TAXI / TRAIN / DELIVERY / QUEUE / RENT / EXPRESS / CHECK_IN / TRADE` 等场景类目，**教育/课程不在其列**；且存在「实况窗场景准入原则」「通用约束限制」「基于地理位置的约束」多组准入规则。端侧创建是否同样受限，需以官方《开发实况窗场景》与《受限说明》章节为准，本次未取到正文。
- **技术栈不同**：ArkTS/HarmonyOS，本项目是 Android/Kotlin，**不能复用现有代码**。若要覆盖纯血鸿蒙，需要另立 ArkTS 工程（与原技术方案 §7「卡片需 HarmonyOS / 原子化规范」判断一致）。

### 3.7 Apple iOS Live Activities

虽然本次不实现，但它的**约束模型值得直接借鉴到 Android 侧的设计里**：

- 声明：`Info.plist` 加 `NSSupportsLiveActivities = YES`；追求高频更新再加 `NSSupportsLiveActivitiesFrequentUpdates`。
- 工程：必须建 Widget Extension（勾选 Include Live Activity），UI 用 SwiftUI。
- 形态：Lock Screen；Dynamic Island 的 `compact`（leading + trailing 两个独立元素）、`minimal`、`expanded`（长按展开）；另有 StandBy、CarPlay、配对 Mac/Apple Watch。
- **时长上限**：活跃期最长 **8 小时**，之后系统自动结束并**立即从灵动岛移除**；锁屏上再保留最多 **4 小时**，即锁屏总计最长 **12 小时**。
- 沙箱隔离：Live Activity **不能访问网络、不能接收位置更新**；动态数据只能由 App 内 ActivityKit 或 ActivityKit 推送驱动。
- 资源限制：图片分辨率不得大于展示尺寸，否则可能导致启动失败。
- **推送预算模型**（可用于反推 Android 侧节流思路）：
  - 系统每小时给一定量的 ActivityKit 推送预算，超限会被 **throttle**；
  - `apns-priority: 5` 的推送**不计入**预算，`10` 计入；**混用 5 和 10 通常可以避免被节流**；
  - 需要更高预算要开 `ActivityAuthorizationInfo().frequentPushesEnabled`；
  - payload 含 `aps.timestamp`、`aps.event`（`start`/`update`/`end`）、`aps.relevance-score`（决定在多活动并存时的排序）、`content-state`。

---

## 4. 统一适配层（已实现）

> **实施状态（2026-10-28）**：本章设计已落地为代码，不再是纸面方案。
> 实际文件：`LiveSurface.kt`（内容模型 + 能力探测）、`LiveSurfaceRenderer.kt`（渲染器接口 +
> 原生实现 + 小米超级岛实现 + 注册表）、`CapsuleDismissTracker.kt`（划卡记账）。
> 改造后的 `CapsuleNotifier` 只做「状态机 → LiveContent → 原生骨架」，
> 厂商差异全部下沉到渲染器。

### 4.1 设计原则

1. **状态机不动**。`CapsulePlan` 与 `CapsuleCoordinator` 保持纯数据/纯调度，厂商适配只发生在渲染层。
2. **先原生，后私有**。原生提升永远作为第一优先级尝试；私有通道是叠加而非替换。
3. **一次内容，多路渲染**。从同一个 `LiveContent` 出发生成原生通知 + 各厂商扩展参数，避免各厂商分支各自算一遍文案。
4. **失败必须可见**。每家渲染器的失败都要落诊断，不能静默。

### 4.2 领域模型

```kotlin
/** 厂商无关的实时活动内容（由 CapsulePlan 投影而来） */
data class LiveContent(
    /** 服务图标资源 */
    val iconRes: Int,
    /** 状态栏胶囊/气泡短文本，如「剩 32 分」 */
    val chip: String,
    /** 标题行，如「正在上课 · 大学英语」 */
    val title: String,
    /** 副标题行，如「第7-8节 · 教305 · 14:00-15:40 · 张老师」 */
    val subtitle: String?,
    /** 辅助信息，如「15:40 下课 · 还剩 32 分钟」 */
    val detail: String?,
    /** 进度 0-100，倒着消耗 */
    val progress: Int?,
    /** 进度条颜色（课程色） */
    val progressColor: Int,
    /** 倒计时目标时刻，null 表示无倒计时 */
    val countdownToMillis: Long?,
    /** 本次内容指纹，用于去重 */
    val key: String,
    /** 当前是否为实时态（决定是否值得请求提升） */
    val live: Boolean,
)
```

### 4.3 能力探测与厂商识别

```kotlin
enum class LiveSurface { NONE, AOSP_PROMOTED, COLOROS_FLUID_CLOUD, HYPEROS_ISLAND, MAGICOS_CAPSULE, ORIGINOS_ATOM, HARMONY_LIVE_VIEW }

object LiveSurfaceSupport {
    /** 系统是否支持查询提升资格；null = 不支持查询（低于 API 36） */
    fun canPromote(context: Context): Boolean? = /* 现有 canPromote() */

    /** 是否达到 promoted 形态的实际门槛（API 36.1），用于设置页文案 */
    fun supportsPromotedTreatment(): Boolean = Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT >= 36.1

    /** 厂商识别：只做「是否需要注入私有参数」的判断，不影响原生路径 */
    fun vendor(context: Context): LiveSurface { /* 读 Build.MANUFACTURER + ro.build.version.oplusrom 等 */ }
}
```

### 4.4 接口与实现

```kotlin
/** 一家厂商的实时通知渲染器 */
interface LiveSurfaceRenderer {
    val surface: LiveSurface

    /** 是否值得在本机启用（厂商 + 系统版本双判断） */
    fun isApplicable(context: Context): Boolean

    /** 在已构建的 Notification.Builder 上叠加本厂商的私有参数 */
    fun decorate(context: Context, builder: Notification.Builder, content: LiveContent)

    /** 系统级兜底退场时刻；null 表示不设 */
    fun timeoutAfter(content: LiveContent, nowMillis: Long): Long?
}

object AospPromotedRenderer : LiveSurfaceRenderer {
    override val surface = LiveSurface.AOSP_PROMOTED
    override fun decorate(context: Context, builder: Notification.Builder, content: LiveContent) {
        if (Build.VERSION.SDK_INT < 36) return
        if (content.chip.isNotEmpty()) builder.setShortCriticalText(content.chip)
        // P2 修复：提升路径不着色，颜色只留给进度段
        builder.addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
    }
    override fun timeoutAfter(content: LiveContent, nowMillis: Long): Long? = /* 现有 countdownCleanupDelayMillis */
}

object HyperOsIslandRenderer : LiveSurfaceRenderer {
    override val surface = LiveSurface.HYPEROS_ISLAND
    override fun isApplicable(context: Context) = LiveSurfaceSupport.vendor(context) == surface
    override fun decorate(context: Context, builder: Notification.Builder, content: LiveContent) {
        val params = buildString {
            append("{\"param_v2\":{")
            append("\"business\":\"course\",")
            append("\"islandFirstFloat\":true,")
            append("\"enableFloat\":false,")
            append("\"islandTimeout\":") ; append(ISLAND_TIMEOUT_SEC) ; append(",")
            append("\"param_island\":{")
            append("\"islandProperty\":1,")
            append("\"highlightColor\":\"#").append(String.format("%06X", 0xFFFFFF and content.progressColor)).append("\",")
            append("\"bigIslandArea\":{").append(bigArea(content)).append("},")
            append("\"smallIslandArea\":{").append(smallArea(content)).append("}")
            append("}}}")
        }
        builder.extras.putString("miui.focus.param", params)
    }
    override fun timeoutAfter(content: LiveContent, nowMillis: Long) = null // 岛超时由 islandTimeout 管
}
```

`CapsuleNotifier.post()` 的改造点只有一处：把内联的 `promote` 分支替换成「遍历适用的 renderer 依次 decorate」：

```kotlin
private val renderers: List<LiveSurfaceRenderer> = listOf(
    AospPromotedRenderer,          // 永远第一
    HyperOsIslandRenderer,
    ColorOsFluidCloudRenderer,     // 泛在服务到位后启用，原生路径仍保留
    MagicOsCapsuleRenderer,
)
// promote 时：
renderers.asSequence()
    .filter { it.isApplicable(context) }
    .forEach { it.decorate(context, builder, content) }
```

**关键约束**：多家 renderer 可以叠加，但**原生提升是保底**——任何厂商私有参数注入失败都不能让通知本身发不出去。所以 `decorate` 必须整体 `runCatching` 包裹，失败只记诊断。

### 4.5 降级阶梯

| 层级 | 条件 | 用户看到 |
| --- | --- | --- |
| L0 原生提升 + 厂商私有 | API 36.1+ 且用户放行 | 状态栏胶囊（流体云 / 灵动胶囊）+ 私有岛形态 |
| L1 原生 ProgressStyle + chip | API 36 | 通知栏内进度条 + 状态栏 chip，**不提升** |
| L2 普通常驻进度通知 | < API 36 | `setProgress(100, progress, false)` |
| L3 纯文本常驻通知 | 无进度（`Upcoming`/`Holiday`/`DayDone`） | 文本状态卡 |
| L4 骨架兜底 | 实时卡片被系统撤下 | FGS 骨架通知 + 30s 自愈重建 |

L4 的「卡片缺失自愈重建」逻辑已在 `LiveCapsuleService` 实现（检测 `activeNotifications` 里没有 `LIVE_CARD_ID` 就强制重建重发），这是跨厂商保留的核心可靠性资产。

---

## 5. 课程场景 → 各厂商字段映射

以「正在上课 · 大学英语 / 第7-8节 / 教305 / 14:00-15:40 / 张老师 / 剩余 32 分 / 进度 42%」为例：

| 语义 | 原生（Android 16） | OPPO 流体云（通用模板） | 小米超级岛 | 华为实况窗 | Apple |
| --- | --- | --- | --- | --- | --- |
| 服务图标 | `setSmallIcon(ic_stat_notify_used2)` | `A*` 服务图标 | `aodPic` | 实况窗图标 | `compact.leading` |
| 短状态 | `setShortCriticalText("剩 32 分")` | `B*` 核心信息 | `smallIslandArea` 文本 | 实况窗主文本 | `compact.trailing` |
| 标题 | `setContentTitle("正在上课 · 大学英语")` | `B1*` 核心信息 | `bigIslandArea` 标题 | 标题 | `expanded` 标题 |
| 服务名 | — | `C1` 服务名称（配 `A1*` 成 `G1`） | `bigIslandArea` 副文本 | — | `expanded` 副标题 |
| 教室/节次 | `setSubText("第7-8节 · 教305 · 14:00-15:40 · 张老师")` | `C2`/`C3` 辅助信息 | `bigIslandArea` 详情行 | 副文本 | `expanded` 详情 |
| 剩余时间 | `setUsesChronometer(true)` + `setChronometerCountDown(true)` + `setWhen(endAt)` | `C4`/`D2` 标签组合 `G3` | `highlightColor` 强调文本 | 倒计时文本 | `Text(timerInterval:)` |
| 进度 | `ProgressStyle.setProgress(42)` + `addProgressSegment(Segment(100).setColor(courseColor))` | `D4` `<progress step color bg-color percent>` + `D5` 位置图标 | 大岛内容区 | — | `ProgressView` |
| 操作 | `addAction("打开今日课表")` | `E3` `<button type="capsule">` | `shareData` | 跳转按钮 | `Link` |
| 背景 | — | `F` `bg-image`/`bg-video` | — | — | SwiftUI 容器 |
| 语音 | — | `Z` `<voice>` | — | — | — |
| 撤卡 | `timeoutAfter` + `nm.cancel(LIVE_CARD_ID)` | `actionStatus=2` | `dismissIsland` / `cancel` | `liveViewManager` 结束接口 | `Activity.end` |

**注意 OPPO 的入口差异**：状态栏胶囊只吃 `A*+B*`，所以 `B*`（核心信息）必须能独立表达清楚「剩 32 分」——不能把关键信息只放在 `C2` 里，那在胶囊形态下看不到。这与现有代码把 `chip` 单独抽出来是同一个思路，扩展时要保持。

---

## 6. 落地路线与优先级

| 阶段 | 内容 | 前置 | 预计 |
| --- | --- | --- | --- |
| W1 | 修 P1-P4：36.1 门槛判断、去掉提升路径着色、加提升开关引导、加 `hasPromotableCharacteristics` 诊断 | 无 | 0.5-1 人日 |
| W2 | 抽出 `LiveContent` + `LiveSurfaceRenderer`，原生渲染器行为不变（纯重构，靠现有单测守回归） | W1 | 1-2 人日 |
| W3 | 小米超级岛渲染器 + 真机验证（大岛/小岛/AOD/息屏/拖拽分享） | W2 | 1-2 人日 |
| W4 | 荣耀灵动胶囊：走原生规范，真机验证 + 确认推送分类申请（服务与通讯类） | W2 | 1 人日 + 平台审批 |
| W5 | vivo 原子岛：申请接入权限，拿到规范后实现 | 权限 | 1-3 人日 + 审批 |
| W6 | OPPO 泛在服务 `.upk` + SeedlingSupportSDK（对称模板更适合课程） | 企业主体 + 商务 | 1-2 周 + 商务 |
| W7 | 华为实况窗：另立 ArkTS 工程 | ArkTS 技术栈 | 独立立项 |
| W8 | iOS Live Activity：另立 Swift + Widget Extension 工程 | Swift 技术栈 | 独立立项 |

**优先级理由**：W1-W3 全部零资质、零商务、可自测闭环，投入产出比最高；W4 走原生规范所以接近零成本；W5 开始进入审批依赖；W6 之后是生态级投入。

---

## 7. 风险与坑

| # | 风险 | 影响 | 对策 |
| --- | --- | --- | --- |
| R1 | 把「即将上课」也提升为实时活动，可能被判定为「日历事件」类不当用途 | 失去提升资格，严重时影响通知权限 | 保持「实时态才提升」的边界；在代码注释里写明理由 |
| R2 | 提升路径着色导致 AOSP 失格 | 非 ColorOS 机型上岛失败 | 修 P2 |
| R3 | 厂商私有参数注入异常导致通知发不出去 | 用户完全看不到状态 | `decorate` 整体 `runCatching`，失败不影响原生通知发布 |
| R4 | 小米 `islandTimeout` 默认 3600s，长课（>60 分钟）会提前消失 | 岛中途消失 | 按 `plan` 的剩余时长动态设置 |
| R5 | 荣耀推送把课程提醒归为资讯营销类 | 每日最多 5 条，课前提醒被掐 | 申请自分类权益，归入服务与通讯类 |
| R6 | 华为 Push 创建实况窗的场景白名单不含教育 | 远程创建被拒 | 先确认端侧创建路径，或与华为商务申请垂域 |
| R7 | 泛在服务依赖 UMS 分发与云端 `.upk` | 审核周期不可控 | 保留原生路径作为永久保底，泛在服务只做增强 |
| R8 | 多厂商通道各自限频，叠加后高频刷新被节流 | 倒计时/进度停更 | 沿用现有 30s 循环 + 系统自绘倒计时；私有通道只推状态切换节点，不推每秒刷新 |
| R9 | 单次内容指纹去重按「厂商无关」key 做，切换厂商时可能漏刷 | 换卡形态后内容陈旧 | key 里掺入适用的 surface 集合 |

---

## 8. 实施记录与验收（2026-10-28）

### 8.1 已落地的改动

| 文件 | 性质 | 内容 |
| --- | --- | --- |
| `core/capsule/LiveSurface.kt` | 新增 | `LiveContent` 厂商无关内容模型；`LiveSurface` 投放面枚举；`LiveSurfaceSupport` 能力探测（提升门槛 / 厂商识别 / 小米协议版本） |
| `core/capsule/LiveSurfaceRenderer.kt` | 新增 | 渲染器接口；`AospPromotedRenderer`（原生提升）；`HyperOsIslandRenderer`（小米超级岛 `param_v2` 协议）；`LiveSurfaceRenderers` 注册表 |
| `core/capsule/CapsuleDismissTracker.kt` | 新增 | 用户划卡记账（SharedPreferences，按场次 key） |
| `core/capsule/CapsuleNotifier.kt` | 改造 | `post()` 改用 `LiveContent`；`baseBuilder` 移除 `setColor`；加 `setDeleteIntent`；进度样式接内容模型；删除冗余 `progressOf`/`skeletonTexts`/`countdownTargetOf` |
| `core/capsule/CapsuleTransitionReceiver.kt` | 改造 | 新增 `ACTION_CARD_DISMISSED` 分支：用户划卡后只记账、不重发 |
| `core/capsule/LiveCapsuleService.kt` | 注释 | 说明自愈重建与「尊重用户划卡」的边界关系 |
| `app/src/test/.../HyperOsIslandParamTest.kt` | 新增 | 6 个协议单测：根节点必填字段、岛超时夹取、字节上限、转义、裁剪、颜色格式 |

### 8.2 验证结果

```
./gradlew :app:compileDebugKotlin      -> BUILD SUCCESSFUL
./gradlew :app:testDebugUnitTest       -> BUILD SUCCESSFUL（全量单测通过，含新增 6 个）
./gradlew detekt                       -> BUILD SUCCESSFUL
./gradlew ktlintMainSourceSetCheck     -> BUILD SUCCESSFUL
./gradlew :app:assembleRelease         -> BUILD SUCCESSFUL（R8 混淆 + 签名，APK 68.9 MB）
```

### 8.3 小米超级岛的协议要点（实现依据）

参数整串结构：

```json
{"param_v2":{
  "protocol":1,
  "business":"course",
  "enableFloat":false,
  "updatable":true,
  "ticker":"剩 32 分",
  "aodTitle":"剩 32 分",
  "timeout":720,
  "param_island":{
    "islandProperty":1,
    "islandTimeout":1920,
    "highlightColor":"#3A7BD5",
    "bigIslandArea":{
      "imageTextInfoLeft":{"type":1,"picInfo":{"type":1,"pic":"miui.focus.pic_island"},
        "textInfo":{"title":"…","content":"…","useHighLight":true}},
      "textInfoRight":{"type":2,"textInfo":{"title":"剩 32 分"}}
    },
    "smallIslandArea":{"picInfo":{"type":1,"pic":"miui.focus.pic_island"}}
  },
  "baseInfo":{"type":2,"title":"…","content":"…","colorTitle":"#3A7BD5"}
}}
```

实现中处理的三个易错点：

1. **两种超时单位不同**：`timeout` 是**分钟**（通知卡片，默认 720），`islandTimeout` 是**秒**（岛，默认 3600）。写错单位会让岛提前几个小时消失。
2. **图片不内联**：图标走 `miui.focus.pics` Bundle（key → Icon），JSON 里只写 key 字符串。直接把二进制塞进参数字符串会让整串超限。
3. **3072 字节硬上限**：超限时降级成只带标题的最小参数——宁可信息不全，也不能让整串被丢弃。

### 8.4 未落地部分

| 项 | 原因 | 后续 |
| --- | --- | --- |
| OPPO 泛在服务 `.upk` | 需企业主体 + 商务对接（`intentName`/`serviceId`/垂域码值均由 OPPO 分配） | 拿到资质后按 §3.2 实现 |
| 荣耀灵动胶囊 | 走原生规范，本体无需改代码；但需真机验证 + 申请推送「服务与通讯类」分类权益 | 见 §3.4 |
| vivo 原子岛 | 需邮件申请准入（7-10 工作日），且官方场景枚举**无课程类目** | 见 §3.5，先谈场景 |
| 华为实况窗 | 需 ArkTS 工程；且官方 `TIMER` 场景明确排除「课程提醒」 | 见 §3.6，属另一个工程 |
| Apple Live Activity | 需 Swift + Widget Extension 工程 | 独立立项 |
| P4 `hasPromotableCharacteristics()` 诊断 | 优先级低（不影响功能） | 后续补 |

---

## 9. 待验证清单

1. [ ] ColorOS 16 实测：`setColor` 是否会取消提升资格（当前项目实为「能上岛」，需确认是版本宽松还是另有条件）。
2. [ ] 荣耀「接入安卓实时通知规范」的正式开发者文档页与字段级差异（本次仅取到论坛线索）。
3. [ ] vivo 原子岛接入权限的申请入口与审核周期。
4. [ ] 华为实况窗**端侧**创建是否也受场景白名单限制。
5. [ ] 小米超级岛在**非小米设备**上注入 `miui.focus.param` 是否产生副作用（应当被忽略，需测）。
6. [ ] API 36.0 与 36.1 设备上 `canPostPromotedNotifications()` 的实际返回差异（社区有报告长期返回 false 的案例）。
7. [ ] 三星 Now Bar 是否也吃原生提升（文档提到 Live Updates 会被路由到 Now Bar）。
