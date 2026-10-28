# 多厂商通知适配调研：Apple iOS 与 Android 原生

调研时间基准：2026-10-27。本文所有结论均来自实际访问的官方文档页面，URL 逐条附在结论之后。二手来源（CSDN/掘金/知乎/Medium/StackOverflow/厂商博客）一律标注「二手」。凡未检索到官方文档的条目，均单列并给出实际尝试过的 URL。

访问通道说明：developer.apple.com 与 developer.android.google.cn 可直接抓取；developer.android.com 主站在本次任务中抓取失败（返回空内容），因此 Android 侧以官方中国镜像域 developer.android.google.cn 为准（同一官方文档源），个别条目用 Wayback Machine 存档页交叉验证。此外，本机装有 Android SDK Platform 36（D:/Android/platforms/android-36/android.jar，Pkg.Revision=2，ExtensionLevel=17），下文标「本机 android.jar 校验」的符号均由 javap 直接读取该 jar 得到，用于验证官方在线 reference 页面的 API 名称。

---

## 一、Apple iOS（ActivityKit / Live Activities / Dynamic Island / WidgetKit / ActivityKit 推送）

### 1. 能力名称（官方叫法）与文档 URL

- 官方能力名：**Live Activities**（框架 **ActivityKit**，UI 用 **WidgetKit** + **SwiftUI** 承载）
  - https://developer.apple.com/documentation/activitykit
  - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities
- 官方设计规范：**Human Interface Guidelines > Live Activities**
  - https://developer.apple.com/design/human-interface-guidelines/live-activities
- 官方推送文档：**Starting and updating Live Activities with ActivityKit push notifications**
  - https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications
- 自定义视图文档：**Creating custom views for Live Activities**
  - https://developer.apple.com/documentation/activitykit/creating-custom-views-for-live-activities
- 版本支持：ActivityKit 自 iOS 16.1 / iPadOS 16.1 / Mac Catalyst 16.1 起（Activity 类与 ActivityUIDismissalPolicy 均标注 iOS 16.1+，update(_:) 标注 iOS 16.2+）。
  - https://developer.apple.com/documentation/activitykit/activity
  - https://developer.apple.com/documentation/activitykit/activityuidismissalpolicy
  - https://developer.apple.com/documentation/activitykit/activity/update(_:)

### 2. 接入方式：依赖库 / SDK、权限、工程声明、核心类或 API 名、承载形态

- 依赖与工程结构：在 Xcode 中新建或复用 **widget extension**（勾选 “Include Live Activity”）；Live Activity 的界面代码属于 widget extension，不属于主 app target。
  - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities
- Info.plist 声明（对应 Android 的 Manifest 声明）：
  - 应用级：**NSSupportsLiveActivities**（Boolean，YES）— 「Supports Live Activities」
    - https://developer.apple.com/documentation/bundleresources/information-property-list/nssupportsliveactivities
  - 高频更新级：**NSSupportsLiveActivitiesFrequentUpdates**（Boolean，true，iOS 16.2+ / iPadOS 16.2+）— 「Supports Live Activities Frequent Updates」
    - https://developer.apple.com/documentation/bundleresources/information-property-list/nssupportsliveactivitiesfrequentupdates
  - 推送能力：Xcode 中为 app target 添加 **Push Notifications capability**；官方明确说明**不能**用 UserNotifications 框架给 Live Activity 注册推送，必须用 ActivityKit 取 token。
    - https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications
- 核心类与 API（均为官方页面出现的真实名称）：
  - Activity<Attributes>：request(attributes:content:pushType:)、request(attributes:content:pushType:style:)、request(attributes:content:pushType:style:alertConfiguration:start:)、update(_:)、update(_:alertConfiguration:)、end(_:dismissalPolicy:)
    - https://developer.apple.com/documentation/activitykit/activity
  - ActivityAttributes（协议）与嵌套 Activity.ContentState（typealias）
    - https://developer.apple.com/documentation/activitykit/activityattributes
    - https://developer.apple.com/documentation/activitykit/activity/contentstate-swift.typealias
  - ActivityContent（含 staleDate、relevanceScore）、AlertConfiguration、PushType（.token / .channel(channelID)）、ActivityStyle.transient、ActivityAuthorizationInfo（areActivitiesEnabled、frequentPushesEnabled、activityEnablementUpdates、frequentPushEnablementUpdates）
    - https://developer.apple.com/documentation/activitykit/activitycontent
    - https://developer.apple.com/documentation/activitykit/activitystyle/transient
    - https://developer.apple.com/documentation/activitykit/activityauthorizationinfo
  - 观察流：activityUpdates、activityStateUpdates、contentUpdates、pushTokenUpdates、pushToStartTokenUpdates
    - https://developer.apple.com/documentation/activitykit/activity
    - https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications
  - WidgetKit 侧：ActivityConfiguration(for:)、DynamicIsland { } compactLeading: { } compactTrailing: { } minimal: { }、DynamicIslandExpandedRegion（.leading / .trailing / .center / .bottom 四种位置 + priority 参数）、WidgetBundle
    - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities
  - 无 Android 式「服务类型」概念；系统为每个 Live Activity 单独开沙盒，且**不能联网、不能接收定位更新**，数据只能靠 ActivityKit 本地更新或 ActivityKit 推送更新。
    - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities

### 3. 通知内容协议：需要提供哪些字段或自定义 View 类型

- 数据模型（官方示例，ActivityAttributes 声明静态数据 + 内嵌 ContentState 声明动态数据）：

```swift
import ActivityKit

struct AdventureAttributes: ActivityAttributes {
    struct ContentState: Codable & Hashable {
        let currentHealthLevel: Double
        let eventDescription: String
    }
    let hero: EmojiRanger
}
```

  - ContentState 必须 Codable；官方要求**不要使用自定义 JSON 编码策略**，系统一律按默认编码策略解码，自定义编码会导致更新失败。
  - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities
  - https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications
- 本地启动示例（官方原文代码）：

```swift
let activity = try Activity.request(
    attributes: adventure,
    content: .init(state: initialState, staleDate: nil),
    pushType: .token
)
```

  - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities
- 推送 payload 协议（HTTP 头 + aps 字段，均为官方原文）：
  - 头：apns-push-type: liveactivity；apns-topic: <bundleID>.push-type.liveactivity；apns-priority: 5 或 10；频道更新另加 apns-channel-id
  - aps 字段：timestamp（1970 秒），event（start / update / end），content-state（必须与自定义 ContentState 属性一一对应），可选的 stale-date、relevance-score、alert、dismissal-date
  - 启动专用：attributes-type、attributes、必须带 alert；iOS 18+ 可加 input-push-channel（频道 ID）或 input-push-token: 1

```json
{
  "aps": {
    "timestamp": 1685952000,
    "event": "update",
    "content-state": {
      "currentHealthLevel": 0.0,
      "eventDescription": "Power Panda has been knocked down!"
    },
    "alert": {
      "title": { "loc-key": "%@ is knocked down!", "loc-args": ["Power Panda"] },
      "body":  { "loc-key": "Use a potion to heal %@!", "loc-args": ["Power Panda"] },
      "sound": "HeroDown.mp4"
    }
  }
}
```

  - https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications
- 自定义视图类型与真实修饰符：DynamicIsland（expanded）+ compactLeading + compactTrailing + minimal；contentMargins(_:_:for:)、keylineTint(_:)（仅 Dark Mode 下的灵动岛边框着色）、activityBackgroundTint(_:)（锁屏背景）、activitySystemActionForegroundColor(_:)（锁屏上系统「结束」按钮文字色）、supplementalActivityFamilies([.small, .medium]) + 环境值 activityFamily
  - https://developer.apple.com/documentation/activitykit/creating-custom-views-for-live-activities
- 交互：按钮/开关必须基于 **App Intents**（Button / Toggle 的 App Intent 初始化器）；后台启动 Live Activity 需要符合 LiveActivityIntent 的 App Intent。
  - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities
  - https://developer.apple.com/documentation/activitykit/activity

### 4. 系统级约束：更新频率限制、倒计时与进度表达、卡片生命周期上限、主动撤卡

- **每帧大小上限**：ActivityContent.state 不得超过 **4KB**（官方参数说明原文：The size of its state property can't exceed 4KB in size）。
  - https://developer.apple.com/documentation/activitykit/activity/update(_:)
- **推送频率预算（官方口径，未给出具体数值）**：系统对 ActivityKit 推送按小时给预算；不指定 apns-priority 时默认按 10 立即投递并计入预算；超出预算会被节流。设 apns-priority: 5 的低优先级更新**不计入预算**（机会式投递、不保证即时）；官方建议用 5 和 10 混合来避免节流。开启 NSSupportsLiveActivitiesFrequentUpdates 可获得更高预算、更不容易被节流。
  - https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications
  - https://developer.apple.com/videos/play/wwdc2023/10185/ （官方 WWDC 视频：低优先级「there is no limit on how many updates you can send」；高优先级「the system imposes a budget depending on the device condition… will throttle your push updates」）
- **本地更新频率限制**：**未检索到官方明确的数值化限制**。官方文档只说明 update(_:) 可在前台或后台（如 Background Tasks）调用，以及「只在内容真的变化时才更新」（HIG 原文：Update a Live Activity only when new content is available）；具体节流阈值与每帧预算未在公开文档中量化。实际尝试过的 URL：
  - https://developer.apple.com/documentation/activitykit/activity/update(_:) （无频率数值）
  - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities （无频率数值）
  - https://developer.apple.com/design/human-interface-guidelines/live-activities （无频率数值）
  - https://developer.apple.com/videos/play/wwdc2023/10184/ （全文未出现 budget/throttle/frequency 字样）
  - https://developer.apple.com/documentation/updates/activitykit （仅列 2024/2025 变更，无频率）
  - https://developer.apple.com/forums/thread/827808 （抽取仅返回 206 字节，本机取不到正文）
- 二手补充（**二手**）：社区反复报告 iOS 18 起本地刷新被进一步收紧、有人按「约每分钟一次」作为可持续节奏；在第三方论坛帖中可见系统日志会打印 liveactivitiesd … Reduced budget for <bundleID>::pushToStart to: 7，以及 Push-to-start budget exceeded … not starting activity（非官方文档）：
  - https://developer.apple.com/forums/tags/activitykit?page=5&sortBy=newest （开发者论坛帖，二手证据：Reduced budget … to: 7）
  - https://developer.apple.com/forums/thread/799505 （论坛帖标题「Live Activity budget exceeded」，二手）
  - https://stackoverflow.com/questions/74616702/update-live-activity-once-per-second-swift （**二手**）
  - https://www.pushwoosh.com/blog/ios-live-activities/ （**二手**，称「Live Activities now refresh every 5–15 seconds, instead of once per second as in iOS 17」）
  - https://www.gravexa.com/insights/live-activities-in-production-rate-limits （**二手**，建议按每分钟一次规划）
- 倒计时与进度：系统不提供 Android 那样独立的进度条样式类；UI 全部由 WidgetKit/SwiftUI 视图自绘（例如官方示例用 ProgressView(value:total:)），时间可用 SwiftUI 计时文本能力表达。补间动画只能用系统给定的过渡（opacity、move(edge:)、slide、push(from:)、numericText(countsDown:)），自定义 withAnimation(_:_:) / animation(_:value:) 会被系统忽略。
  - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities
  - https://developer.apple.com/design/human-interface-guidelines/live-activities （官方：Live Activity 动画最大时长 **2 秒**，且 Always-On 降亮度屏不播放动画）
- 卡片生命周期上限（官方数值）：
  - 活跃状态最长 **8 小时**；到点后系统自动结束并立即从灵动岛移除。
  - 结束后在锁屏最多再留 **4 小时**（或用户手动移除，取先到者）。
  - 因此锁屏可见总上限 **12 小时**。
  - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities
- 主动撤卡（结束与移除）：
  - local：await activity.end(ActivityContent(state: finalContent, staleDate: nil), dismissalPolicy: dismissalPolicy)
  - ActivityUIDismissalPolicy：.default（结束后最长 4 小时由系统移除）、.immediate（立即移除）、.after(Date)（指定时间，但受 4 小时窗口上限约束）
  - 推送侧等价字段：aps 里的 "dismissal-date"（给过去的时间 = 立即移除；给未来时间 = 4 小时窗口内自定义移除）
  - 用户可在锁屏自行移除；官方强调「移除 Live Activity 不等于取消用户启动的那件事」（例如撤掉外卖 Live Activity 不会取消订单）
  - https://developer.apple.com/documentation/activitykit/activityuidismissalpolicy
  - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities
  - https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications
- 过期（stale）机制：ActivityContent.staleDate / 推送 stale-date；到点后 activityState 变为 ActivityState.stale、视图上下文 isStale 变 true；建议每次更新顺带把 staleDate 往后推。
  - https://developer.apple.com/documentation/activitykit/activitycontent
  - https://developer.apple.com/documentation/activitykit/activity/activitystate

### 5. 视觉规范：图标尺寸与单色/彩色要求、动态取色、标题与副标题长度限制

- 官方尺寸表（HIG 原文，单位 pt，覆盖 compact / minimal / expanded 三形态）：
  - iOS 430x932 屏：Compact（leading 与 trailing 各）62.33x36.67；Minimal 36.67–45 x 36.67；Expanded 408 x 84–160；Lock Screen 408 x 84–160；Limited width 28x28
  - iOS 393x852 屏：Compact 52.33x36.67；Minimal 36.67–45 x 36.67；Expanded 371 x 84–160；Lock Screen 371 x 84–160；Limited width 28x28
  - iPadOS：Lock Screen 500x84–160 / 425x84–160（按机型）
  - macOS 沿用 iOS 尺寸
  - watchOS Smart Stack：40mm 152x69.5、41mm 165x72.5、42mm 176x72.5、44mm 173x76.5、45mm 184x80.5、46mm 194x80.5、49mm(Ultra 3,4) 198x81.5、49mm(Ultra 1,2) 191x81.5
  - CarPlay：240x78、240x100、170x78
  - https://developer.apple.com/design/human-interface-guidelines/live-activities
- 有限宽度（横屏等）约束：compact 与 minimal 要能塞进 **28pt 直径圆**，用短缩写或图形替代整句文字；若 leading/trailing 都做不出窄版，可只做 leading、留空 trailing（系统自动隐藏 trailing）。对应环境值 isDynamicIslandLimitedInWidth。
  - https://developer.apple.com/design/human-interface-guidelines/live-activities
  - https://developer.apple.com/documentation/swiftui/environmentvalues/isdynamicislandlimitedinwidth
- 三种形态的布局约束（HIG 逐形态原文要点）：
  - Compact：只用最关键、实时、易读的信息；leading 与 trailing 被 TrueDepth 摄像头分隔但必须读起来是**同一条信息**，配色与字体一致；内容尽量窄并紧贴摄像头，不要在摄像头周围加 padding；两侧宽度要平衡。点击打开 app，两侧必须跳到同一屏。
  - Minimal：多个 Live Activity 同时活跃时使用（最多三个进入灵动岛，一个贴合、其余两侧脱离）；必须让人一眼认出是哪个 app，能显示动态信息就不要只放 logo。
  - Expanded：是 compact/minimal 的放大版，元素相对位置要可预期地展开；内容要紧贴 TrueDepth 摄像头（布局区域为 center / leading / trailing / bottom，可用 priority 让某块占满整宽）；可以随信息量动态改变高度。
  - https://developer.apple.com/design/human-interface-guidelines/live-activities
- 背景与取色：
  - 灵动岛内 Live Activity 固定 **黑底**（官方：use a black opaque background）；Lock Screen 默认 Light Mode 白底 / Dark Mode 黑底，可用 activityBackgroundTint(_:) 自定义，用 Color.opacity(_:) 调透明度。compact / minimal / expanded **不允许自定义背景色**。
  - Dark Mode 下灵动岛外圈 key line 可用 keylineTint(_:) 着色，应与卡片内元素颜色一致。
  - 圆角对齐建议用 ContainerRelativeShape，不要硬编码圆角值。
  - 锁屏标准边距为 **14 点**（官方原文：The standard layout margin for Live Activities on the Lock Screen is 14 points）。
  - 自定义背景时要保证 Always-On 降亮度场景的对比度。
  - https://developer.apple.com/design/human-interface-guidelines/live-activities
  - https://developer.apple.com/documentation/activitykit/creating-custom-views-for-live-activities
- 文字规范：使用中等及以上字重（medium weight or higher）的大号文字；标题/副标题的**字符数硬上限官方未给出**（HIG 只给字体大小/字重/可读性原则，无长度数值）。
  - https://developer.apple.com/design/human-interface-guidelines/live-activities
- 资源约束（容易踩）：图片资源的解析度必须不大于对应呈现形态的尺寸，**超过可能导致 Live Activity 启动失败**（官方原文：the system might fail to start the Live Activity）。
  - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities
- 品牌与图标：可以放 logo mark 但**不要加容器、不要直接用 app 图标**，不要放广告/推广内容，不要展示敏感信息。
  - https://developer.apple.com/design/human-interface-guidelines/live-activities

### 6. 权限与开关：用户侧开关名称、是否需要白名单/审核/签约

- 用户侧开关：系统「设置」中可对单个 app 关闭 Live Activities（官方：people can choose to deactivate Live Activities for an app in the Settings app）；**频繁推送是独立开关**（官方：Users can disable frequent updates independently of Live Activities in Settings）。
  - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities
  - https://developer.apple.com/videos/play/wwdc2023/10185/
- 开发侧探测 API：ActivityAuthorizationInfo().areActivitiesEnabled（同步）、activityEnablementUpdates（异步流）、ActivityAuthorizationInfo().frequentPushesEnabled、frequentPushEnablementUpdates。
  - https://developer.apple.com/documentation/activitykit/activityauthorizationinfo
- 重要细节（官方 WWDC 口径）：频繁更新开关只在 activity 启动后检查一次即可；**该值变化时系统会结束所有正在进行的 activity**，所以服务端不必担心中途被切换。
  - https://developer.apple.com/videos/play/wwdc2023/10185/
- 审核/签约：**未检索到官方文档**要求 Live Activities 单独申请白名单、审批或签约。实际尝试过的 URL：
  - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities （正文只提 Info.plist 开关与用户设置，无申请流程）
  - https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications （同上）
  - https://developer.apple.com/documentation/bundleresources/information-property-list/nssupportsliveactivities （同上）
  - 注：广播推送（channel/broadcast）需要项目具备 broadcast capability，官方指向 https://developer.apple.com/documentation/usernotifications/setting-up-broadcast-push-notifications

### 7. 回退行为：系统不支持时如何降级为普通通知

- 官方提供的降级路径（原文要点）：
  - 不带灵动岛的机型上，锁屏形态会以 banner 形式出现在已解锁设备的屏幕顶部，但**只有当更新带了 AlertConfiguration** 才作为 banner 提醒。即：想在不支持灵动岛的机器上获得提醒效果，update 必须传 alertConfiguration。
  - request 时若带 alertConfiguration 且未到 start date，系统会先通过普通通知告知用户 Live Activity 即将开始。
  - 关闭了 Live Activities 的用户：areActivitiesEnabled == false，app 应不提供启动入口。
  - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities
  - https://developer.apple.com/documentation/activitykit/alertconfiguration
- 事件结束/设备不支持时的普通通知回退：官方文档未给出「自动转为普通推送通知」的机制，Live Activity 结束后只是锁屏残留 + 到期移除；要另行发普通通知需走 UserNotifications/APNs 普通通道。
  - https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications

### 8. 已知坑与实测注意点（每条尽量给官方出处）

1. pushToken 不能同步取：request 后 token 异步生成，必须订阅 pushTokenUpdates；token 在 activity 生命周期内会变，拿到新 token 时要作废旧 token，否则后续更新发不出去。
   - https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications
2. iOS/iPadOS 17.1 及更早**不能用推送启动** Live Activity，只能更新和结束；推送启动需要 iOS 18 起（pushToStartTokenUpdates、input-push-channel / input-push-token）。
   - https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications
3. 不能用 UserNotifications 给 Live Activity 注册推送。
   - https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications
4. content-state 一旦用自定义 JSON 编码策略，服务端推送会被系统解码失败。
   - https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications
5. 推送丢失、以及「activity 已结束后到达的推送被系统忽略」都会导致数据陈旧；官方建议推送之外再用本地 update 兜底。
   - https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications
6. Live Activity 沙盒**无网络、无定位**，不能像 widget 那样靠 Timeline 更新。
   - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities
7. 后台只能「更新/结束」，**启动**必须在 app 前台（或用 LiveActivityIntent 的 App Intent 启动）。
   - https://developer.apple.com/documentation/activitykit/activity
8. 多 activity 时用 relevanceScore 决定谁进灵动岛（不设或相同 = 自己启动的第一个进灵动岛），官方建议重要更新给 100、次要给 50 这类相对值。
   - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities
9. 自定义动画修饰符会被系统忽略，只能用系统过渡；动画最长 2 秒；Always-On 降亮度不播动画。
   - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities
   - https://developer.apple.com/design/human-interface-guidelines/live-activities
10. 图片资源解析度大于呈现框可能直接导致启动失败；StandBy 会把锁屏视图放大 2 倍，资源要按更大尺寸准备。
   - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities
11. 用户手动移除等于结束 activity，但**不会**取消业务（官方用外卖订单举例）。
   - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities
12. 服务端调频：应把 frequentPushesEnabled 的值同步到服务端再开推；变化时系统会结束所有 activity，所以只需检查一次。
   - https://developer.apple.com/videos/play/wwdc2023/10185/

---

## 二、Android 原生（Android 16 Live Updates / Notification.ProgressStyle / Notification.Builder setStyle）

### 1. 能力名称（官方叫法）与文档 URL

- 官方英文叫法：**Live Updates**（**promoted ongoing notifications**），样式类为 **Notification.ProgressStyle**，总称 **progress-centric notifications**。
- 官方文档（本次实际访问，均返回正文）：
  - https://developer.android.google.cn/about/versions/16/features/progress-centric-notifications （Progress-centric notifications，最后更新 2026-03-03）
  - https://developer.android.google.cn/develop/ui/views/notifications/live-update （创建实时更新通知，「实时动态」指南，最后更新 2026-10-24）
  - https://developer.android.google.cn/develop/ui/compose/notifications/live-update （同一内容，Compose 入口版）
  - https://developer.android.google.cn/reference/android/app/Notification.ProgressStyle （API reference）
  - https://developer.android.google.cn/reference/android/app/Notification （EXTRA_REQUEST_PROMOTED_ONGOING、FLAG_PROMOTED_ONGOING、hasPromotableCharacteristics()）
  - https://developer.android.google.cn/reference/android/app/Notification.Builder （setRequestPromotedOngoing(boolean)、setShortCriticalText(String)、setWhen(long)）
  - https://developer.android.google.cn/about/versions/16/features （Android 16 特性汇总，含以进度为中心的通知条目）
  - https://developer.android.google.cn/jetpack/androidx/releases/core （androidx.core 1.17.0-alpha01 新增 NotificationCompat.ProgressStyle 与 NotificationCompat.Builder.setRequestPromotedOngoing()）
  - https://developer.android.google.cn/training/wearables/notifications/live-updates （Wear OS 7 起支持）
- 页面规范页（设计侧）：https://developer.android.google.cn/design/ui/mobile/guides/home-screen/live-updates
- Wayback 交叉验证（官方主站存档）：https://web.archive.org/web/20260710123040/https://developer.android.com/about/versions/16/features/progress-centric-notifications
- ~~未能访问的 URL（主站抽取失败，返回空正文）~~ **2026-10-28 复核：这批 URL 的内容全部拿得到**，只是须换同源的官方中国镜像：
  - https://developer.android.google.cn/about/versions/16/features/progress-centric-notifications （2,624 字，`ProgressStyle` 命中 17 次）
  - https://developer.android.google.cn/develop/ui/compose/notifications/live-update （3,823 字，与 Views 版为同一篇）
  - https://developer.android.google.cn/reference/android/app/Notification.ProgressStyle （182,351 字，`setProgressSegments` 等全在）
  - https://developer.android.google.cn/develop/ui/views/notifications/live-update （3,919 字）
  - 判定依据见 [`2026-10-28-vendor-live-notification-source-audit.md`](2026-10-28-vendor-live-notification-source-audit.md)：**`extract` 失败 ≠ 页面拿不到**，`curl` + 去标签 + 标识符校验这条路是通的；`developer.android.com` 主站本身连接超时（curl 28），镜像域正常。

### 2. 接入方式：依赖库 / SDK、权限、AndroidManifest 声明、核心类或 API 名、服务类型

- 依赖库：
  - 平台 API：android.app.Notification.ProgressStyle（API 36，Android 16）
  - AndroidX 兼容层：androidx.core:core 从 **1.17.0-alpha01** 起提供 NotificationCompat.ProgressStyle 与 NotificationCompat.Builder.setRequestPromotedOngoing()
  - https://developer.android.google.cn/jetpack/androidx/releases/core
- 权限：
  - 官方指南要求：在 manifest 中声明**非运行时权限** android.permission.POST_PROMOTED_NOTIFICATIONS
    - https://developer.android.google.cn/develop/ui/views/notifications/live-update
  - 本机 android.jar 校验（android-36, revision 2）：javap -constants android.Manifest$permission 中**只列出** POST_NOTIFICATIONS，**未列出** POST_PROMOTED_NOTIFICATIONS；javap -constants android.app.Notification 列出 FLAG_PROMOTED_ONGOING = 262144。说明该权限名在当前 SDK Platform 36 的常量表中不可直接引用，工程里应按字面量字符串声明（android.permission.POST_PROMOTED_NOTIFICATIONS）。
- AndroidManifest 声明要素：<uses-permission android:name="android.permission.POST_PROMOTED_NOTIFICATIONS"/>；实践上还需 POST_NOTIFICATIONS（Android 13+ 运行时通知权限）；若要长期存活，需前台服务声明（<service android:foregroundServiceType="...">）与对应 FGS 类型权限。
  - https://developer.android.google.cn/develop/ui/views/notifications/live-update
  - https://developer.android.google.cn/about/versions/14/changes/fgs-types-required （FGS 类型必需，含 API 36 权限要求）
- 核心类与 API（均为官方文档真实名称）：
  - Notification.ProgressStyle：setStyledByProgress(boolean)、setProgress(int)、setProgressIndeterminate(boolean)、setProgressTrackerIcon(Icon)、setProgressStartIcon(Icon)、setProgressEndIcon(Icon)、setProgressSegments(List<Segment>) / addProgressSegment(Segment)、setProgressPoints(List<Point>) / addProgressPoint(Point)；读取侧 getProgressMax()、getProgressSegments()、getProgressPoints()
    - https://developer.android.google.cn/reference/android/app/Notification.ProgressStyle
  - Notification.ProgressStyle.Segment(int length) + setColor(int) / setId(int)
  - Notification.ProgressStyle.Point(int position) + setColor(int) / setId(int)
    - https://developer.android.google.cn/reference/android/app/Notification.ProgressStyle
  - 本机 android.jar 校验（与官方页面一致）：ProgressStyle 全部上述方法存在；Segment(int)、Point(int) 构造器与 setColor(int) 存在。
  - 挂载方式：new Notification.Builder(context).setStyle(new Notification.ProgressStyle()…)（官方示例原文）
    - https://developer.android.google.cn/reference/android/app/Notification.ProgressStyle
  - 升级请求：Notification.Builder.setRequestPromotedOngoing(boolean) 或 extras 里的 Notification.EXTRA_REQUEST_PROMOTED_ONGOING；读取状态用 Notification.FLAG_PROMOTED_ONGOING、Notification.hasPromotableCharacteristics()、NotificationManager.canPostPromotedNotifications()
    - https://developer.android.google.cn/develop/ui/views/notifications/live-update
    - https://developer.android.google.cn/reference/android/app/Notification.Builder
  - 跳设置页：~~Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS~~ → **10-28 更正为 `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS`**
    - 官方指南页两处均写作 `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS`（"是 intent action，可让应用把用户转到设置"）
    - 本机 android.jar（android-36, rev 2）校验：`javap -constants android.provider.Settings` 里**只有** `ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS = "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS"`；`ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS` 在常量表与 class 字符串表中均不存在
    - 官方 reference 原文：「Activity Action: Show the permission screen for allowing apps to post promoted notifications.」Added in API 36
    - https://developer.android.google.cn/develop/ui/views/notifications/live-update
    - https://developer.android.google.cn/reference/android/provider/Settings
  - 服务类型：官方 Live Update 文档未规定专用 foregroundServiceType；因 Live Update 必须是 ongoing 通知，实践中通常由 startForeground() 承载，FGS 类型与权限按 Android 14+ 规则（android.permission.FOREGROUND_SERVICE_*）配置。
    - https://developer.android.google.cn/about/versions/14/changes/fgs-types-required

### 3. 通知内容协议：需要提供哪些字段或自定义 View 类型

- 官方列出的「成为 Live Update」硬性条件（逐条原文，可视为内容协议）：
  1. 样式必须是标准样式、BigTextStyle、CallStyle、ProgressStyle 或 MetricStyle 之一；
  2. manifest 中声明非运行时权限 android.permission.POST_PROMOTED_NOTIFICATIONS；
  3. 必须通过 EXTRA_REQUEST_PROMOTED_ONGOING 或 NotificationCompat.Builder#setRequestPromotedOngoing 请求升级；
  4. 必须是 ongoing（FLAG_ONGOING_EVENT）；
  5. 必须设置 contentTitle；
  6. **不得**设置 customContentView（即不允许 RemoteViews 自定义布局）；
  7. **不得**是 setGroupSummary 的分组摘要；
  8. **不得** setColorized(true)；
  9. 通知渠道**不得**是 IMPORTANCE_MIN。
  - https://developer.android.google.cn/develop/ui/views/notifications/live-update
- 卡片各区域的字段映射（官方 anatomy 表）：
  - 头部副标题 → Notification.Builder.setSubText()
  - 头部时间 → Notification.Builder.setWhen()
  - 内容标题 → Notification.Builder.setContentTitle()
  - 内容正文 → Notification.Builder.setContentText()
  - 进度条 → Notification.ProgressStyle
  - 动作按钮 → Notification.Builder.addAction()
  - 状态条 chip 文本 → Notification.Builder.setShortCriticalText(String)
  - https://developer.android.google.cn/about/versions/16/features/progress-centric-notifications
  - https://developer.android.google.cn/develop/ui/views/notifications/live-update
  - https://developer.android.google.cn/reference/android/app/Notification.Builder
- 官方 ProgressStyle 代码示例（原文，网约车场景）：

```kotlin
var ps =
    Notification.ProgressStyle()
        .setStyledByProgress(false)
        .setProgress(456)
        .setProgressTrackerIcon(Icon.createWithResource(appContext, R.drawable.ic_car_red))
        .setProgressSegments(
            listOf(
                Notification.ProgressStyle.Segment(41).setColor(Color.BLACK),
                Notification.ProgressStyle.Segment(552).setColor(Color.YELLOW),
                Notification.ProgressStyle.Segment(253).setColor(Color.WHITE),
                Notification.ProgressStyle.Segment(94).setColor(Color.BLUE)
            )
        )
        .setProgressPoints(
            listOf(
                Notification.ProgressStyle.Point(60).setColor(Color.RED),
                Notification.ProgressStyle.Point(560).setColor(Color.GREEN)
            )
        )
```

  - https://developer.android.google.cn/about/versions/16/features/progress-centric-notifications
- 大图标用于「车辆/骑手」等 tracker 视觉：官方 best practice 建议用 Notification.setLargeIcon()（文档链接指向 NotificationCompat.Builder#setLargeIcon(Icon)）。
  - https://developer.android.google.cn/about/versions/16/features/progress-centric-notifications
- 明确不支持自定义布局：官方 FAQ 原文「为什么实时动态不支持自定义通知？——自定义通知的行为在不同 Android 版本和设备制造商之间存在显著差异……避免使用 RemoteViews 自定义通知」。
  - https://developer.android.google.cn/develop/ui/views/notifications/live-update
- 官方示例工程：https://github.com/android/platform-samples/tree/main/samples/user-interface/live-updates

### 4. 系统级约束：更新频率限制、倒计时与进度表达方式、卡片生命周期上限、主动撤卡方式

- 更新频率：**官方文档未给出数值化的刷新频率/次数上限**。官方只要求「频繁且准确地更新（Update the progress experience frequently and accurately……）」并按实际行程同步。实际尝试过的 URL：
  - https://developer.android.google.cn/develop/ui/views/notifications/live-update （无频率数值）
  - https://developer.android.google.cn/about/versions/16/features/progress-centric-notifications （无频率数值）
  - https://developer.android.google.cn/design/ui/mobile/guides/home-screen/live-updates （设计指南，无频率数值）
  - https://developer.android.google.cn/training/wearables/notifications/live-updates （Wear OS 侧只提「频繁更新可能导致数据被隐藏以省电」，无手机侧数值）
- Wear OS 侧已知约束（官方原文）：实时动态可从手机桥接但**不保证**（OEM 可自定策略）；**频繁更新可能导致数据被隐藏以省电**。
  - https://developer.android.google.cn/training/wearables/notifications/live-updates
- 倒计时与进度表达（官方规则，很具体）：
  - when 时间会驱动通知有效期倒计时，除非被关闭或更新：
    - when 至少比当前时间晚 2 分钟：例如当前 10:05、when=10:10，chip 显示 **5min**
    - when 已过：不显示文本
    - 计时器：setUsesChronometer(true) + setChronometerCountDown(true)，只要计时器时间为正数就显示在 chip 中
    - 不想显示时间：setShowWhen(false)
  - 状态 chip 规则：始终含图标，可选文本；**最大宽度 96dp**；规则为「少于 7 字符 → 完整显示；能显示的文字不足一半 → 只显示图标；能显示一半以上 → 尽量多显示」
  - 进度条语义：用离散步骤时清楚标注阶段；用标准进度条时填充量要与剩余时间或距离相符；进度条末尾白点是「旅程结束」的无障碍可视化指示
  - 空状态禁忌：ETA 计算中不要显示空状态，用「正在思考…」「正在重新规划路线…」这类动态占位文本（官方第 4 图）
  - 卡片与 chip 的时间戳格式必须一致
  - https://developer.android.google.cn/develop/ui/views/notifications/live-update
  - https://developer.android.google.cn/design/ui/mobile/guides/home-screen/live-updates
- 卡片生命周期上限：**官方文档未给出固定时长上限**。官方给出的生命周期控制点是「用户可临时关闭/降级」「不要重复发布用户已关闭的动态」；被系统限制的是结束方式，不存在声明式「X 小时后自动撤卡」的官方字段。实际尝试过的 URL：
  - https://developer.android.google.cn/develop/ui/views/notifications/live-update （只说用户可 dismiss/demote，无时长）
  - https://developer.android.google.cn/reference/android/app/Notification.ProgressStyle （无时长字段）
  - https://developer.android.google.cn/training/wearables/notifications/live-updates （无时长）
  - 二手说法（**二手**，未在官方文档确认）：曾流传「promoted 状态最长约 4 小时」；官方页面未出现该数值，勿写入产品需求。
    - https://www.reddit.com/r/Android/comments/1lq1jgr/android_16_qpr1_adds_full_support_for_live/ （**二手**）
- 主动撤卡/取消：
  - 取消通知本身 = NotificationManager.cancel(id)（平台通用行为；本文档未给出 Live Update 专用撤卡 API）
  - 探测「用户关闭了这张卡」：官方明确要求用 setDeleteIntent 检测被关闭的更新，并且**不要重新发布用户已关闭的实时动态**
  - 用户可在通知栏对该通知做「临时关闭 / 降级为标准通知」；用户也可能对整个 app 关掉实时动态
  - https://developer.android.google.cn/develop/ui/views/notifications/live-update
  - https://developer.android.google.cn/design/ui/mobile/guides/home-screen/live-updates

### 5. 视觉规范：图标尺寸与单色/彩色要求、动态取色、标题与副标题长度限制

- 图标与彩色：
  - tracker 图标：用 setProgressTrackerIcon(Icon)，可**动态更新**；官方建议用能直观呈现状态的自定义 tracker 图标
  - 起止图标：setProgressStartIcon(Icon)、setProgressEndIcon(Icon)，需为方形图标
  - 大图标：setLargeIcon(Icon)（vehicle/骑手图，建议取车辆真实颜色）
  - 颜色：Segment/Point 各自 setColor(int)，可表达路况分段；setStyledByProgress(true) 时进度前后按进度做不同着色
  - **注意冲突**：Live Update 要求**不得** setColorized(true)，但 API reference 的 ProgressStyle 代码示例里出现了 .setColorized(true) —— 采用 Live Update 呈现时以 Live Update 文档为准（不要 colorize）。
    - https://developer.android.google.cn/reference/android/app/Notification.ProgressStyle
    - https://developer.android.google.cn/develop/ui/views/notifications/live-update
- 动态取色：官方文档**未提及** Material You / 动态取色对 Live Update 的适配规则；颜色由开发者通过 setColor(int) 显式指定。实际尝试过的 URL：https://developer.android.google.cn/develop/ui/views/notifications/live-update 、https://developer.android.google.cn/design/ui/mobile/guides/home-screen/live-updates （均无动态取色条目）
- 长度限制：
  - 状态 chip 文本：**最大宽度 96dp**，且按「7 字符 / 一半字符」规则裁决（见上一节）
  - contentTitle：必填，但官方未给字符数上限
  - 标题/副标题的**字符数硬上限官方未给出**；只有「简洁清晰（concise and clear language）」的设计要求
  - https://developer.android.google.cn/develop/ui/views/notifications/live-update
  - https://developer.android.google.cn/design/ui/mobile/guides/home-screen/live-updates
- 不可做的事（视觉层）：不允许 RemoteViews 自定义；不允许 colorize；不允许是一条没有明确结束时间的通知；不适合「信息来自多个 app」「面向建议/推荐」「需要定制动画或独特数据结构」的场景。
  - https://developer.android.google.cn/develop/ui/views/notifications/live-update
  - https://developer.android.google.cn/design/ui/mobile/guides/home-screen/live-updates

### 6. 权限与开关：用户侧开关名称、是否需要白名单/审核/签约

- 用户侧开关：
  - 可对单条通知「临时关闭 / 降级为标准通知」（dismiss / demote）
  - 可对单个 app 整体关闭实时动态（promotion）
  - 对应的设置入口 intent：~~Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS~~ → `Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS`（10-28 更正，本机 android.jar 与官方 reference 双向确认；配 `Settings.EXTRA_APP_PACKAGE`，启动前需 `resolveActivity`）
  - 状态查询：NotificationManager.canPostPromotedNotifications()（返回 false 代表用户在设置里关掉了 promotion）；Notification.hasPromotableCharacteristics()（只判通知自身是否具备可提升特征，**不**考虑用户设置）
  - 通知渠道不得为 IMPORTANCE_MIN，否则无法提升
  - https://developer.android.google.cn/develop/ui/views/notifications/live-update
- 审核 / 白名单 / 签约：**未检索到官方文档**要求申请白名单、审核或签约。实际尝试过的 URL：
  - https://developer.android.google.cn/develop/ui/views/notifications/live-update
  - https://developer.android.google.cn/design/ui/mobile/guides/home-screen/live-updates
  - https://developer.android.google.cn/about/versions/16/features/progress-centric-notifications
  - https://developer.android.google.cn/jetpack/androidx/releases/core
  - 相关但非「审核」：出海的 targetSdk 合规要求（Google Play 自 2026-08-31 起新应用/更新须 target API 36+）见 https://developer.android.google.cn/google/play/requirements/target-sdk
- 风险提示（官方原文）：发布不必要的实时动态可能导致**用户撤销该 app 的发布权限**。
  - https://developer.android.google.cn/develop/ui/views/notifications/live-update

### 7. 回退行为：系统不支持时如何降级为普通通知

- 机制是「不提升」而不是「另发一条」：把内容做成标准通知/标准样式（ProgressStyle 在低版本会退化为普通进度通知），FLAG_PROMOTED_ONGOING 不置位，通知照常显示但不获得顶部/锁屏置顶与状态 chip 的强化呈现。
  - https://developer.android.google.cn/reference/android/app/Notification （FLAG_PROMOTED_ONGOING 由系统置位）
  - https://developer.android.google.cn/develop/ui/views/notifications/live-update
- 版本门控：ProgressStyle 为 API 36 引入；低于 36 的机型上进度语义会退化（社区实测「SDK 版本低于 36 时 ProgressStyle 会回退」，注意：**二手**且不同配置表现不一致）。
  - **二手**：https://stackoverflow.com/questions/79746655/progress-notification-not-showing-segmentation-colors-or-progress-icon-on-older
- 兼容写法：用 NotificationCompat.ProgressStyle + NotificationCompat.Builder.setRequestPromotedOngoing()，在低版本走 compat 分支；封装层按 Build.VERSION.SDK_INT 判断 android.app.Notification.ProgressStyle 是否可用。
  - https://developer.android.google.cn/jetpack/androidx/releases/core
- 探测回退时机：用 canPostPromotedNotifications() 决定是否请求提升（用户关闭时不要反复请求）。
  - https://developer.android.google.cn/develop/ui/views/notifications/live-update

### 8. 已知坑与实测注意点

1. 设了 customContentView（RemoteViews）就**拿不到** Live Update 提升；这是官方硬性排除项。
   - https://developer.android.google.cn/develop/ui/views/notifications/live-update
2. setColorized(true) 与提升互斥；但 Notification.ProgressStyle 的官方代码示例里写了 .setColorized(true)，直接抄示例到 Live Update 场景会踩坑。
   - https://developer.android.google.cn/develop/ui/views/notifications/live-update
   - https://developer.android.google.cn/reference/android/app/Notification.ProgressStyle
3. setProgress()（旧 Notification.Builder.setProgress）与 ProgressStyle 冲突：官方 NOTE 明确「Notification.Builder.setProgress 设置的 extras 会在构建时被 ProgressStyle 上的值覆盖，因此该方法和本样式不一起用」；进度最大值由所有 Segment 长度之和决定（getProgressMax()）。
   - https://developer.android.google.cn/reference/android/app/Notification.ProgressStyle
4. 进度条为 RTL 布局会镜像（官方 NOTE）。
   - https://developer.android.google.cn/reference/android/app/Notification.ProgressStyle
5. 权限名在 SDK 常量表里缺失：本机 android-36 (revision 2, ExtensionLevel 17) 的 android.Manifest$permission 常量表**没有** POST_PROMOTED_NOTIFICATIONS，只有 POST_NOTIFICATIONS；manifest 里需按字符串字面量写。若 IDE 报「未知权限」，不要以为是拼写错误。
   - 本机 android.jar 校验；官方指南仍要求声明该权限：https://developer.android.google.cn/develop/ui/views/notifications/live-update
6. hasPromotableCharacteristics() 不等于「已经提升」：前者只看通知自身特征，后者还要用户没关掉该 app 的 Promotion；判断是否真的提升要看 FLAG_PROMOTED_ONGOING。
   - https://developer.android.google.cn/reference/android/app/Notification
   - https://developer.android.google.cn/develop/ui/views/notifications/live-update
7. Wear OS 桥接不保证、频繁更新可能被隐藏省电；MetricStyle 在 Wear 上不被支持（通知仍显示但没有指标）。
   - https://developer.android.google.cn/training/wearables/notifications/live-updates
8. 必须是 ongoing + 有 contentTitle + 非 IMPORTANCE_MIN 渠道，缺一项都提升不了；这几项在真机上一项不满足时是「静默不提升」，很难从日志看出来，建议先跑官方 sample：
   - https://github.com/android/platform-samples/tree/main/samples/user-interface/live-updates
9. 用户关闭过的动态**不要重新发布**；用 setDeleteIntent 捕获关闭事件，否则可能被撤销发通知权限。
   - https://developer.android.google.cn/develop/ui/views/notifications/live-update
10. 状态 chip 只有 96dp，且「文本不足一半就只剩图标」——把最关键信息做成 ≤7 字符的短文本（如 ETA 分钟数），比塞长句可靠。
    - https://developer.android.google.cn/develop/ui/views/notifications/live-update
11. 机型门控：社区反馈 Android 16 QPR1 才带来对 Live Updates 的完整支持，API 36.0 模拟器上 canPostPromotedNotifications() 常返回 false（**二手**，官方 release notes 未直接给出该结论）。
    - https://www.reddit.com/r/Android/comments/1lq1jgr/android_16_qpr1_adds_full_support_for_live/ （**二手**）
    - https://stackoverflow.com/questions/79672535/android-16-live-updates-notifications-canpostpromotednotifications-always-fal （**二手**）

---

## 三、给课程表 App（com.zhijian.schedule）的落地对照

| 维度 | Apple Live Activity | Android Live Update |
|---|---|---|
| 声明开关 | NSSupportsLiveActivities（+ NSSupportsLiveActivitiesFrequentUpdates） | manifest 声明 android.permission.POST_PROMOTED_NOTIFICATIONS |
| 主体类 | Activity<Attributes> + ActivityAttributes/ContentState + ActivityConfiguration | Notification.ProgressStyle + Notification.Builder |
| 挂载方式 | widget extension + WidgetBundle | setStyle() |
| 不可自定义视图 | 灵动岛固定黑底、无自定义背景 | 禁止 RemoteViews 自定义布局 |
| 进度表达 | 自绘 SwiftUI（ProgressView 等） | Segment/Point/TrackerIcon 由系统绘制 |
| 主动撤卡 | end(_:dismissalPolicy:) / aps dismissal-date | NotificationManager.cancel()（通用），用 setDeleteIntent 感知用户关闭 |
| 生命周期上限 | 活跃 8h；结束后锁屏最长 4h；锁屏总计 12h | 官方未给数值上限；用户可随时关闭/降级 |
| 回退 | 无灵动岛机型靠 AlertConfiguration 走 banner；关闭开关则 areActivitiesEnabled=false 不启动 | 不置 FLAG_PROMOTED_ONGOING 即普通通知显示 |

课表场景的注意点（由上述官方约束直接推出）：
1. 课表语义是「学生自己发起的一天课程追踪」——Apple 侧要求「有明确开始与结束、不超过 8 小时」；一天课程跨度长时应拆成单节课或半天，否则到 8 小时会被系统自动结束（https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities）。
2. Android 状态 chip 只有 96dp，把「下一节课还有 12min」压成 ≤7 字符（如 12min）比整句更可靠（https://developer.android.google.cn/develop/ui/views/notifications/live-update）。
3. 两端都不允许自定义布局：Android 明确禁 RemoteViews，Apple 灵动岛三形态尺寸固定（62.33x36.67 / 36.67–45x36.67 / 408x84–160 等），课程表要在这些硬尺子里排版，不能照搬 OPPO 流体云的宽卡片（https://developer.android.google.cn/develop/ui/views/notifications/live-update 、https://developer.apple.com/design/human-interface-guidelines/live-activities）。
4. 倒计时用系统能力而非高频更新：Android 用 setWhen / setUsesChronometer + setChronometerCountDown，Apple 用 SwiftUI 计时文本；官方在两端都强调「内容没变就别更新」/「用 when 指南而不是定期刷新」以省电（https://developer.android.google.cn/develop/ui/views/notifications/live-update 、https://developer.android.google.cn/training/wearables/notifications/live-updates 、https://developer.apple.com/design/human-interface-guidelines/live-activities）。
5. 推送预算：Apple 侧把非关键更新走 apns-priority: 5（不计预算），只有关键提醒走 10，并考虑开启 NSSupportsLiveActivitiesFrequentUpdates；服务端保存 frequentPushesEnabled 值调频（https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications 、https://developer.apple.com/videos/play/wwdc2023/10185/）。
6. 未获官方数值的两件事不要写进需求：Apple 本地更新的具体节流阈值、Android 提升状态的自动过期时长（https://developer.apple.com/documentation/activitykit/activity/update(_:) 、https://developer.android.google.cn/develop/ui/views/notifications/live-update）。

---

## 四、未检索到官方文档的条目汇总（附实际尝试过的 URL）

1. Apple：Live Activity 本地更新的数值化频率/每帧预算阈值
   - https://developer.apple.com/documentation/activitykit/activity/update(_:)
   - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities
   - https://developer.apple.com/design/human-interface-guidelines/live-activities
   - https://developer.apple.com/videos/play/wwdc2023/10184/
   - https://developer.apple.com/documentation/updates/activitykit
   - https://developer.apple.com/forums/thread/827808
2. Apple：ActivityKit 推送预算的具体次数/小时
   - https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications
   - https://developer.apple.com/videos/play/wwdc2023/10185/
3. Apple：Live Activities 是否需要单独申请白名单/审核/签约
   - https://developer.apple.com/documentation/activitykit/displaying-live-data-with-live-activities
   - https://developer.apple.com/documentation/activitykit/starting-and-updating-live-activities-with-activitykit-push-notifications
   - https://developer.apple.com/documentation/bundleresources/information-property-list/nssupportsliveactivities
4. Apple：标题/副标题的字符数硬上限
   - https://developer.apple.com/design/human-interface-guidelines/live-activities
5. Android：Live Update 的更新频率上限
   - https://developer.android.google.cn/develop/ui/views/notifications/live-update
   - https://developer.android.google.cn/about/versions/16/features/progress-centric-notifications
   - https://developer.android.google.cn/design/ui/mobile/guides/home-screen/live-updates
   - https://developer.android.google.cn/training/wearables/notifications/live-updates
6. Android：提升状态（promoted）的自动过期/撤卡时长上限
   - https://developer.android.google.cn/develop/ui/views/notifications/live-update
   - https://developer.android.google.cn/reference/android/app/Notification.ProgressStyle
   - https://developer.android.google.cn/training/wearables/notifications/live-updates
7. Android：是否要求白名单/审核/签约
   - https://developer.android.google.cn/develop/ui/views/notifications/live-update
   - https://developer.android.google.cn/design/ui/mobile/guides/home-screen/live-updates
   - https://developer.android.google.cn/about/versions/16/features/progress-centric-notifications
   - https://developer.android.google.cn/jetpack/androidx/releases/core
8. Android：动态取色（Material You）对 Live Update 的规则
   - https://developer.android.google.cn/develop/ui/views/notifications/live-update
   - https://developer.android.google.cn/design/ui/mobile/guides/home-screen/live-updates
9. Android：标题/副标题的字符数硬上限
   - https://developer.android.google.cn/develop/ui/views/notifications/live-update
   - https://developer.android.google.cn/design/ui/mobile/guides/home-screen/live-updates
10. 两条工程侧限制（本机环境，非文档问题）
   - developer.android.com 主站在本会话抓取失败（返回空正文）；已改用官方中国镜像 developer.android.google.cn 与 Wayback 存档：
     - https://developer.android.com/about/versions/16/features/progress-centric-notifications
     - https://developer.android.com/develop/ui/compose/notifications/live-update
     - https://developer.android.com/reference/android/app/Notification.ProgressStyle
     - https://developer.android.com/develop/ui/views/notifications/live-update.html
     - https://web.archive.org/web/20260710123040/https://developer.android.com/about/versions/16/features/progress-centric-notifications
   - 本会话内置 web_search / web_fetch 返回 HTTP 401（DeepSeek 搜索/抓取端点认证失败），因此搜索与抓取全部改用 anysearch MCP（mcp__anysearch__search / mcp__anysearch__extract）完成；若后续仍需该通道，请在「插件 > 内置插件设置 > Web 搜索」检查端点配置。
