# OPPO / 小米 / 荣耀 胶囊与实况通知官方文档证据档（2026-10-27）

> 用途：为课表 App（包名 `com.zhijian.schedule`）的「多厂商通知适配」提供可复核原文出处。
> 取证方式：Anysearch 检索 + 官方文档页正文提取（`mcp__anysearch__search` / `mcp__anysearch__extract`）。
> 全程未登录任何厂商后台，未改动 App 源码，未提交 git。
> 证据分级：【官网原文】官方文档站正文可直接支撑｜【二手】第三方来源，仅作线索｜【未检索到】未取得正文，列出实际尝试过的 URL。
> 说明：本机 `web_search` 后端返回 HTTP 401（检索端点未配置），本次全部检索改走 Anysearch 通道完成。

---

## 0. 三家与 Android 16 Live Updates（Notification.ProgressStyle）的关系

### 0.1 原生侧基准（三家适配的前提）

【官网原文】<https://developer.android.com/develop/ui/views/notifications/live-update>（正文已取，Last updated 2026-10-23）
【官网原文】<https://developer.android.com/about/versions/16/features/progress-centric-notifications>（正文已取，Last updated 2026-03-03）
【官网原文】<https://developer.android.google.cn/reference/android/app/Notification.ProgressStyle?hl=en>（API reference，Added in API level 36）

原生资格硬条件（原文逐条）：

- 通知 style 必须是 `Standard` / `BigTextStyle` / `CallStyle` / `ProgressStyle` / `MetricStyle` 之一。
- 必须在 manifest 申请非运行时权限 `android.permission.POST_PROMOTED_NOTIFICATIONS`。
- 必须用 `EXTRA_REQUEST_PROMOTED_ONGOING` 或 `NotificationCompat.Builder#setRequestPromotedOngoing` 请求提升。
- 必须 ongoing（`FLAG_ONGOING_EVENT`）。
- 必须设置 `contentTitle`。
- 必须不设置 `customContentView`（不用 RemoteViews）。
- 必须不是 `setGroupSummary` 的分组摘要。
- 必须不 `setColorized(true)`。
- 通知 channel 不能是 `IMPORTANCE_MIN`。

关键原文（决定了厂商私有能力与原生能力的关系）：

> "Note: Original Equipment Manufacturers (OEMs) can enforce additional criteria for Live update eligibility. Check their respective documentation for details."

其他原文要点：

- 系统查询 API：`Notification.FLAG_PROMOTED_ONGOING`、`Notification.hasPromotableCharacteristics()`、`NotificationManager.canPostPromotedNotifications()`、`Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS`。
- 提升后表现：通知抽屉顶部、锁屏醒目位置、抽屉收起时以 status chip 出现在状态栏；卡片默认展开且不可折叠。
- status chip 最大宽度 96dp；文本少于 7 个字符整体显示，不足一半则只显示图标。
- `ProgressStyle` 构件：`Notification.ProgressStyle.Point`、`Notification.ProgressStyle.Segment`，以及 `setProgress(int)`、`setStyledByProgress(boolean)`、`setProgressTrackerIcon(Icon)`、`setProgressSegments(List)`、`setProgressPoints(List)`。
- 官方明确不适合提升：广告、推广、聊天消息、提醒、即将到来的日历事件、App 功能快捷方式。

### 0.2 三家对照结论

| 厂商 | 官方叫法 | 与原生 ProgressStyle 的关系 | 证据强度 |
| --- | --- | --- | --- |
| OPPO | 流体云（泛在服务 / 小布建议） | 文档层面给出的是**私有意图共享 API**；同时 OPPO 官方口径称 ColorOS 16 完整接入 Android 16 Live Updates API，遵循谷歌实时活动规范可直接适配流体云 | 私有 API 为【官网原文】；原生直通为【二手转述 OPPO 设计总监原话】 |
| 小米 | 小米超级岛（OS3）/ 焦点通知 Focus Notification（OS2） | 文档通篇是 **`miui.focus.param` extras 私有协议**，全文未提及 `ProgressStyle`、`POST_PROMOTED_NOTIFICATIONS` 或 Live Updates | 【官网原文】（私有 API 明确） |
| 荣耀 | 灵动胶囊 | **未检索到官方字段级接入文档**；二手线索指向「接入 Android 16 实时通知规范」 | 一级菜单入口为【官网原文】；接入方式为【二手】 |

---

## 1. OPPO ColorOS 流体云 / 泛在服务（open.oppomobile.com）

### 1.1 能力名称与文档 URL

官方叫法：**流体云**（ColorOS 14 起，属「泛在服务 / 泛在卡片」框架）；同体系还有 **小布建议**（桌面卡、负一屏）。

| 页面 | URL | 本次取到的内容 |
| --- | --- | --- |
| 服务接入指南 · 接入准备 | <https://open.oppomobile.com/documentation/page/info?id=13572> | 服务形态、需向 OPPO 提供的信息、方案选型、卡片模板清单 |
| 意图共享（端侧） | <https://open.oppomobile.com/documentation/page/info?id=13558> | `ContentProviderClient` 调用细节、错误码表、示例代码 |
| 意图共享数据结构 | <https://open.oppomobile.com/documentation/page/info?id=13565> | 页面正文未取到（返回导航壳），字段表见同站模板页 |
| 进度可视化模板 | <https://open.oppomobile.com/documentation/page/info?id=13567> | `IntelligentIntent` 完整字段表 + 数据示例（本次取证核心） |
| 垂域里程碑 | <https://open.oppomobile.com/documentation/page/info?id=13568> | 各 `entityName` 的 code/text/销卡延时/强提醒/耳机播报全表 |
| 销卡 | <https://open.oppomobile.com/documentation/page/info?id=13578> | 四种销卡途径、云侧 `deleteIntent` 接口详情、端侧调用示例 |
| 环境搭建 | <https://open.oppomobile.com/documentation/page/info?id=13590> | 调测 APK 清单、系统应用、环境切换与授权步骤 |
| 联系我们 | <https://open.oppomobile.com/documentation/page/info?id=13560> | 对接邮箱 `fwst@oppo.com` |
| 流体云模板 2.0 | <https://open.oppomobile.com/documentation/page/info?id=12658> | oml 模板声明、`category` 分类、compact/expanded 容器、level 常量、多入口信息流转表 |
| SeedlingSupportSDK 接入指南 | <https://open.oppomobile.com/new/developmentDoc/info?id=12719> | Maven/AAR 集成、变体、`SeedlingCardWidgetProvider` 回调 |
| config.json | <https://open.oppomobile.com/documentation/page/info?id=12645> | `identifier`/`intent`/`runtime`/`meta-data` 配置；icons 尺寸要求 |

### 1.2 接入方式

OPPO 有**两条互不相同的接入线路**，本次均取到原文：

**线路 A：SeedlingSupportSDK（泛在卡片 / UPK 路线）**

- 依赖库（原文，版本 3.0.7），三选一：
  - `com.oplus.pantanal.card:seedling-support-lite`（252.7 KB，只支持泛在服务）
  - `com.oplus.pantanal.card:seedling-support-liteQuick`（283 KB，支持快应用轻卡 + 泛在部分功能）
  - `com.oplus.pantanal.card:seedling-support-external`（574 KB，同时支持泛在与速览接入）
- 也可 AAR 集成：`implementation fileTree(dir: 'libs', includes: ['*.aar'])`。
- 三个变体**均为自动初始化**，无需额外 init。原文提示：若要在子进程集成，需咨询 OPPO。
- 核心类：必须继承实现 `SeedlingCardWidgetProvider`，回调 `onCardCreate` / `onShow` / `onHide` / `onDestroy` / `onSubscribed` / `onUnSubscribed` / `onCardObserve` / `onUpdateData` / `onSizeChanged`；更新数据调 `updateData(card, bundle)`。回调在**子线程**，不可处理 UI。
- AndroidManifest 声明（原文）：

```xml
<provider
    android:name="com.ums.sdk.card.DemoSeedlingCardProvider"
    android:authorities="com.xxx.xxx"
    android:enabled="true"
    android:exported="true">
    <intent-filter>
        <action android:name="com.oplus.seedling.action.SEEDLING_CARD" />
    </intent-filter>
</provider>
```

- 尺寸常量（原文注释）：状态栏入口下长按胶囊弹出面板，size 由 `Constants.FluidCloudSize.NOTIFICATION_SM` 变为 `Constants.FluidCloudSize.NOTIFICATION_LG`，收起时反向变化。
- UI 用 oml 模板声明：`<card-template entry="notification" category="...">`，`category` 取值 `modular`（组合模板）/ `general`（通用模板）/ `symmetry`（对称模板）/ `media`（音乐模板）。声明容器 `compact`（胶囊态，含 `leading`、`trailing`）与 `expanded`（展开态，含 `leading`、`center`、`trailing`）。

**线路 B：意图共享（服务接入指南路线）**

- **端侧**：通过 `ContentProviderClient` 直接调用系统 `IntelligentIntentProvider`。原文调用声明：

| 参数 | 值 |
| --- | --- |
| authority | `IntelligentIntent` |
| method | `shareIntent`（出卡/刷新）/ `deleteIntent`（销卡） |
| arg | null |
| extras | `putString("intentData", "{...}")`，intentData 为 JSON String |

返回结果 `ShareResult` 通过 `Bundle.getString("result")` 取 JSON String，字段 `code`（0 成功）/ `message` / `data`。

- **云侧**：HTTP 接口。原文给出：
  - 测试环境：`https://oop-openapi-cn.wanyol.com/intent/v1/deleteIntent`
  - 正式环境：`https://openapi.heytapmobi.com/intent/v1/deleteIntent`
  - 请求方法 POST；请求头 `Content-Type: application/json`、`Authorization`（access_token）、`X-Client-Send-Utc-Ms`（毫秒时间戳，误差允许 15 分钟）。
- 权限侧：`IntentProvider` 由系统持有，应用侧无独立运行时权限声明；调用返回码即权限判定（见 1.8）。

### 1.3 通知内容协议（真实字段名）

基础数据单元 `IntelligentIntent`（原文摘自进度可视化模板页）：

| 字段 | 描述 | 类型 | 必填 |
| --- | --- | --- | --- |
| `intentName` | 意图名称，调测环境传固定值如 `Example.Progress`，正式环境由 OPPO 在联调时提供 | String | 是 |
| `identifier` | 单次意图共享唯一标识（时间戳或 UUID） | String | 是 |
| `timestamp` | 意图共享发生的系统时间戳（毫秒） | Long | 是 |
| `serviceId` | 卡片 ID，每个 serviceId 对应一张卡片，JSON，如 `{ "fluidCloud": "999900001", "launcher": "999800001" }`，正式值由 OPPO 提供 | JSON | 是 |
| `intentAction.actionStatus` | 卡片行为：`0` 创卡、`1` 刷新、`2` 销卡 | Int | 是 |
| `intentEntity.entityName` | 垂域名称 | String | 是 |
| `intentEntity.entityId` | 意图实体唯一标识（订单号、航班号等） | String | 是 |
| `intentEntity.milestone.code` | 里程碑状态码 | Int | 是 |
| `intentEntity.milestone.text` | 里程碑描述 | String | 是 |
| `intentEntity.capsule.leftImg` / `rightImg` | 胶囊左右图标 | String | 单侧图标与文本至少传一个 |
| `intentEntity.capsule.leftText` / `rightText` | 胶囊左右文本；默认字体传 String，定制颜色传 JsonArray `[{"text":"备货中","color":"#FF0A59F7","darkColor":"#FF0A59F7"}]`，**仅支持 1 个元素** | String 或 JsonArray | 同上 |
| `intentEntity.capsule.legacyImg` / `legacyText` | OS14 需额外传的图标与文本 | String / JsonArray | OS14 场景 |
| `intentEntity.primary.title` | 主文本，JsonArray 最**多 5 个元素** | String 或 JsonArray | 否 |
| `intentEntity.primary.content` | 副文本，JsonArray 最多 5 个元素 | String 或 JsonArray | 否 |
| `intentEntity.primary.topLeftImg` / `topLeftText` / `topRightImg` / `topRightText` | 顶部左/右图标与文本 | String | 否 |
| `intentEntity.primary.leftImg` / `miniImg` / `rightImg` | 左侧大图、右侧小图、右侧大图 | String | 否 |
| `intentEntity.primary.clickAction` | 卡片整体点击跳转，String 或 List；List **最多支持三个跳链**，优先第一个，失败依次下沉，全失败跳软件商店对应应用页 | String 或 List<String> | 否 |
| `intentEntity.primary.leftButton` / `rightButton` | 左右图标按钮，成对出现 `{ "icon": "xxxx", "link": "xxxx" }` | JsonObject | 否 |
| `intentEntity.primary.topButton` | 顶部按钮 `{ "text": "按钮", "icon": "xxxx", "link": "xxxx" }` | JsonObject | 否 |
| `intentEntity.secondaryData.type` | 模板类型，固定值 `"PROGRESS"` | String | 展示进度条时必填 |
| `intentEntity.secondaryData.progress` | 进度条百分比，取值 0-100 | Int | 同上 |
| `intentEntity.secondaryData.indicatorImg` | 指示器图标 | String | 同上 |
| `intentEntity.secondaryData.style` | `"top"`（进度条上方）/ `"inside"`（进度条内部） | String | 同上 |
| `intentEntity.secondaryData.nodeLabels` | 节点文本，**仅支持 2-5 个元素** | JSONArray | 同上 |

原文约束：通用模板的左/右侧按钮、顶部按钮、小图、大图**同时只能显示一种**。

模板层 level 常量（组合模板原文）：`A*` / `A0`（服务图标，可 `<image><lottie><widget>`）、`B*` / `B0`（文本，`<text>`）、`A1*`（服务图标，必填，可隐性展示，用于 AOD/手表/气泡兜底）、`C10`/`C11`（辅助文本）、`D8`/`D9`（图片/图标/动图/小组件）、`E20*`/`E21`（开关或圆形按钮）、`F1*`（整体背景，支持 `bg-color` / `border-color` / `bg-image`）、`Z`（语音播报，`<voice>`）。

### 1.4 系统级约束

**销卡四途径**（原文，销卡页）：

1. **延时自动销卡**：数据带 `milestone`，每种状态对应一个销卡延时（OPPO 侧按垂域配置）。同一意图超时无新推送则销卡；期间收到更新推送会**刷新计时**；进入新里程碑则按新里程碑延时重新计时。
2. **结束状态手动销卡**：`actionStatus = 2` 立刻销卡（需手动配置）。
3. **删除意图接口手动销卡**：云侧或端侧 `deleteIntent` 立刻销卡，用于异常状态处理。
4. **应用卸载自动销卡**：OPPO 侧监听卸载，移除该应用下所有意图实体。

前两种**不删除意图实体**，销卡后同 entityId 再推会重新出卡；后两种**会删除意图实体**，同 entityId 再推不会再出卡。

**胶囊展示时长硬上限（原文，垂域里程碑页）**：

> "流体云胶囊受系统限制，在 OS15 最长显示 5 分钟，该时长不受销卡延时控制；部分垂域胶囊具有单独的展示时长限制"

**垂域销卡延时实测值（节选，原文全表更长）**：

| entityName | 里程碑 | code | text | 入口 | 销卡延时 |
| --- | --- | --- | --- | --- | --- |
| TAXI | 接驾中 | 30 | `vehicle_coming` | 所有入口 | 15 分钟 |
| TAXI | 行驶中 | 50 | `in_progress` | 所有入口 | 30 分钟 |
| TAXI | 订单完成 | 80 | `finished` | 所有入口 | 5 秒 |
| DELIVERY（外卖） | 送货中 | 60 | `delivering` | 所有入口 | 1 小时 |
| DELIVERY（外卖） | 已送达 | 70 | `delivered` | 所有入口 | 5 秒 |
| NAVIGATION | 导航中 | 10 | `navigating` | 流体云 | 5 分钟 |
| NAVIGATION | 导航结束 | 20 | `finished` | 流体云 | 5 秒 |
| TASK | 任务开始 | 10 | `task_start` | 流体云 | 5 分钟 |
| TASK | 处理中 | 20 | `in_progress` | 流体云 | 5 分钟 |
| TASK | 任务完成 | 30 | `finished` | 流体云 | 5 秒 |
| TASK | 任务完成-长时间 | 32 | `finished_long` | — | 胶囊 5 秒，其他 5 分钟 |
| TRAIN | 出发前提醒-2 小时以上 | 30 | `trip_reminder_early` | **桌面卡** | 24 小时 |
| TRAIN | 出发前提醒-2 小时及以内 | 35 | `trip_reminder_imminent` | 所有入口 | 胶囊 1 分钟，其他 2 小时 |
| TRAIN | 行程中 | 50 | `trip_in_progress` | **除胶囊外所有入口** | 60 小时 |
| TRAIN | 即将到达 | 60 | `arrive_soon` | 所有入口 | 胶囊 1 分钟，其他 2 小时 |
| FLIGHT | 出发前提醒-4 小时以上 | 40 | `trip_reminder_early` | 桌面卡 | 24 小时 |
| FLIGHT | 出发前提醒-4 小时及以内 | 45 | `trip_reminder_imminent` | 所有入口 | 胶囊 1 分钟，其他 4 小时 |
| FLIGHT | 行程中 | 60 | `trip_in_progress` | 除胶囊外所有入口 | 24 小时 |
| CHARGE | 充电开始 / 充电中 | 10 / 20 | `charge_start` / `charging` | 流体云 | 30 分钟 |
| MATCH | 进行中 | 20 | `in_progress` | 所有入口 | 1 小时 |
| SMART_DEVICE | 运行中 | 20 | `in_progress` | 所有入口 | 胶囊 10 分钟，其他 15 分钟 |
| HOTEL | 入住提醒 | 30 | `check_in_reminder` | 所有入口 | 胶囊 5 分钟，其他 8 小时 |

原文另有**强提醒**（胶囊自动展开为卡片并持续一段时间后回到胶囊）与**耳机播报**（佩戴耳机时播报文本）说明，例如 DELIVERY 已送达触发强提醒 + 耳机播报「订单已送达」、TAXI 车辆已到达触发强提醒 + 耳机播报。

**进度表达方式**：`secondaryData.type = "PROGRESS"` + `progress`(0-100) + `style`(`top`/`inside`) + `indicatorImg` + `nodeLabels`(2-5 节点)；模板层对应 `<progress level="3-4:D4" step="2" color="#FFCC24" bg-color="rgba(255,255,255,0.2)" percent="{{ percent }}">`。

**主动撤卡方式**：`deleteIntent`（端侧 Provider call / 云侧 HTTP），参数含 `intentName`、`entityIds`、`serviceId`；云侧还需 `requestId`、`target{targetType:"user", targetIds, idType: sid/regId/phoneNum}`。

### 1.5 视觉规范

【官网原文】文件级要求（config.json 页）：

- 泛在服务图标 `icons`：尺寸 **144*144 px**、格式 PNG、颜色空间 sRGB、形状正方形、路径只支持英文且长度最大 64、文件大小最大 **50KB**。

【官网原文】组合模板胶囊态要求（流体云模板 2.0 页）：

- 胶囊左侧和右侧必须各有一个元素（锁头场景除外，允许左侧显示一个）。
- 胶囊单侧不可有相同元素；同一应用下信息扭转时**胶囊样式不支持样式变化**。
- `A`/`A0` 为服务图标/场景图标，`B`/`B0` 为文本信息，元素左右两侧展现不做具体限制。
- 原流体云 1.0 的超长文本在 2.0 需精简至范围内（**最多 5 个字符**）；未精简直接兼容至 2.0 时胶囊拉伸变长，优先显示完整，达到胶囊最大宽度后以「…」显示。
- **胶囊背景为黑色，不允许开发者自定义**。
- 图形元素需使用透明无色背景。
- 气泡态兼容规则：非居中挖孔时，气泡从 `A`→`A0`→`A1*` 依次兜底取图标；若为图片切换到防烧屏气泡需裁切成圆形；若为动图或小组件则显示 `A1*`。
- 展开态顶部信息 `category` 支持 `mirror`（左右图文对称）与 `switches`（右侧按钮开关，**最多 2 个最少 1 个**）。
- 彩卡规则：若状态栏卡为彩卡（黑色背景卡属彩卡），锁屏与通知中心均使用相同质感及颜色彩卡，**不区分亮暗模式**；未设计彩卡则使用流体云 1.0 的亮色/暗色模式卡。彩色卡片需要描边处理，描边支持自定义颜色。

**未检索到官方文档**：OPPO 开放平台一级导航存在「流体云 → 设计规范」独立章节，本次**未取得该章节的字段级正文**。实际尝试过的 URL：<https://open.oppomobile.com/new/wiki>（导航壳）、<https://open.oppomobile.com/wiki/doc>（导航壳）、<https://open.oppomobile.com/documentation/page/info?id=11709>（设计原则，检索命中但未取到流体云专项内容）。

### 1.6 权限与开关

- **企业认证**：原文「如果是首次接入，需注册 OPPO 开放平台账号并完成企业认证」。
- **商务对接**：原文「开始接入前，开发者需明确以下需求信息，并联系 OPPO 进行确认」；对接邮箱 <fwst@oppo.com>。需提供 QPS 峰值、QPS 均值、单日请求总量、单次履约请求量、请求频率、应用包名、开发者 ID（主账号）、AppID。
- **环境 APK 白名单**：需 adb 覆盖安装 OPPO 提供的 APK，涉及「智慧数据增强服务」「智慧决策服务」「智慧建议服务」「快应用服务框架（可选）」；需清除数据、切换智慧建议服务到测试环境、快应用服务框架勾选「绕过权限校验」、完成小布建议授权。
- **用户侧开关**：ColorOS 用户可在「设置 - 通知与控制中心 - 流体云」查看/管理支持流体云的应用（【二手】原文来自 <https://www.coloros.com/article/A00000075/>，为 OPPO 官网 coloros.com 域名，属官方产品页）。
- **意图名 / 服务 ID / 垂域码值**：均由 OPPO 在联调过程中分配或提供，非自助申请。

### 1.7 回退行为

**未检索到官方文档**：已取的 OPPO 服务接入指南与 SeedlingSupportSDK 文档中，**未出现关于「系统不支持时降级为普通通知」的明确条款**。实际尝试过的 URL：id=13572、id=13558、id=13578、id=13590、id=12719、id=12658。

文档中可见的相邻机制：OS14 需额外传 `legacyImg` / `legacyText` 以兼容低版本入口；`clickAction` 全失败时回落软件商店应用页。

### 1.8 已知坑与实测注意点

- **胶囊 5 分钟硬上限**：OS15 流体云胶囊最长显示 5 分钟，且不受销卡延时控制 —— 课表场景若期望「一节课 45 分钟常驻胶囊」，按已取文档不可行。
- **垂域是封闭集合**：已取原文列出的 `entityName` 为 TAXI / DELIVERY / PICKUP / MATCH / NAVIGATION / TASK / TRAIN / FLIGHT / HOTEL / CHARGE / SMART_DEVICE。**不存在教育/课表类垂域**；TASK 类里程碑（`task_start` / `in_progress` / `finished`）是最接近可用的通用入口，但需 OPPO 侧配置意图名与 serviceId。
- **错误码即权限判定**（原文）：`10101001` 应用无该意图共享权限、`10101002` 权限已过期、`10102001` 参数错误、`10103001` 数据大小超限、`10103002` **频次超限**、`10103003` 意图共享开关已关闭、`10103004` 实体数量超限、`10103005` 版本不支持；删除意图侧 `10201001` / `10202001` / `10203001`。
- **颜色必须成对传**：`color`（亮色）与 `darkColor`（暗色），不传 `darkColor` 会回落到 `color`。
- **元素数量硬限制**：胶囊 `leftText`/`rightText` 仅 1 个元素；`title`/`content` 最多 5 个元素；`nodeLabels` 仅 2-5 个；`clickAction` List 最多 3 条跳链。
- **回调在子线程**：`SeedlingCardWidgetProvider` 所有生命周期回调都在子线程，不可处理 UI。
- **进程内集成限制**：若要在子进程中集成泛在接入 SDK 或实现 `SeedlingCardWidgetProvider`，原文要求先咨询 OPPO。

---

## 2. 小米 HyperOS 小米超级岛 / 焦点通知（dev.mi.com）

### 2.1 能力名称与文档 URL

官方叫法：**小米超级岛**（Xiaomi HyperOS 3 起，官方英文站点标识 `XiaomiHyperIsland`）；其基础是 **焦点通知（Focus Notification）**（HyperOS 2）。

| 页面 | URL | 本次取到的内容 |
| --- | --- | --- |
| 小米超级岛 · 业务介绍 | <https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2140> | 交互框架、产品优势、**岛通知准入原则**、场景支持参考 |
| 小米超级岛 · 版本信息 | <https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2141> | OS2 焦点通知 vs OS3 超级岛的形态差异与兼容方式 |
| 小米超级岛 · 开发指南 | <https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2131> | 两种接入方式、基础数据字段表、AOD、状态栏、模板接入完整示例 |
| 小米超级岛 · 展开态设计规范 | <https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2142> | 22 套展开态模板与图片像素/圆角要求 |
| 小米超级岛 · 摘要态设计规范 | <https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2143> | 大岛 A/B 区 9 种组件组合、小岛 3 种模板、像素与字数规范 |
| 小米超级岛 · 方案提报说明 | <https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2144> | 展示逻辑与兜底时长（岛 1 小时 / 卡片 12 小时） |
| 小米超级岛 · 接入流程 | <https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2132> | 开发者认证、服务开通、场景预审/正式审核、设备白名单、上线验证、灰度 |
| 小米超级岛 · 常见 Q&A | <https://dev.mi.com/xiaomihyperos/documentation/detail?pId=2146> | 权限查询代码、payload 上限、timeout/islandTimeout 差异、图片要求 |
| 能力入口页 | <https://dev.mi.com/xiaomihyperos/ability/XiaomiHyperIsland> | 定位、典型场景、合作伙伴 |
| 澎湃 OS 站点 | <https://hyperos.mi.com/> | 系统侧宣传口径（超级岛服务数、设置页） |

### 2.2 接入方式

原文给出**两种方式**：

**方式一：客户端实现**

按发送原生通知的流程构建，然后在 extras 注入岛参数：

```java
String channelId = "test_channel_id";
NotificationChannel channel = new NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT);
notificationManager.createNotificationChannel(channel);

Notification notification = new Notification.Builder(context, channelId)
    .setContentTitle(testTitle)
    .setContentText(testText)
    .setSmallIcon(smallIconResId)
    .build();

notification.extras.putString("miui.focus.param", islandParams);
notificationManager.notify(1, notification);
```

- **无独立 SDK 依赖**：客户端路线不引入小米专有库，纯用 Android `Notification` + extras。
- **无自定义 View 类型要求**：岛结构用 JSON 字符串放进 extras，**不是 RemoteViews**。（状态栏另有可选的自定义 RemoteViews 通道，见 2.3。）

**方式二：MIPUSH 服务实现**

- 沿用现有 MIPUSH 接口，通过扩展参数支持，**目前仅支持 regId 发送**。
- 扩展 extra key：`miui.focus.param`、`miui.focus.pic_XXX`。
- 原文代码：`Sender sender = new Sender(APP_SECRET);` 然后用 `Message.Builder().extra("miui.focus.param", params).extra("miui.focus.pic_xxx", url)...` 构造，`sender.send(message, regId, 3)`。
- 场景方案确认后开通焦点通知权限，可用 API 接口 `vip.api.xmpush.xiaomi.com`，JAVA SDK 方式为 `new Sender(APP_SECRET, true)`。

**AndroidManifest 声明**（原文，接入流程页）：

```xml
<meta-data android:name="com.xiaomi.xms.APP_ID" android:value="your app id here" />
<meta-data android:name="com.xiaomi.xms.BUILD_TYPE_DEBUG" android:value="true or false" />
```

原文说明：`com.xiaomi.xms.APP_ID` 服务开通时生成，用于鉴权，**必须配置**；`com.xiaomi.xms.BUILD_TYPE_DEBUG` 用测试签名调试时应为 true，正式签名时 false。

### 2.3 通知内容协议（真实字段名）

岛参数结构：`{ "param_v2": { 交互能力数据 / 大岛内容数据 / 小岛内容数据 / 通知内容数据 } }`，通过 `notification.extras.putString("miui.focus.param", islandParams)` 注入。

**通知属性数据**（原文表）：

| 参数 | 必选 | 类型 | 含义 |
| --- | --- | --- | --- |
| `business` | 是 | String | 运营场景（数据统计），如打车为 `taxi` |
| `islandFirstFloat` | 否 | boolean | 通知首次出现是否自动展开，默认 `true` |
| `enableFloat` | 否 | boolean | 通知更新时是否自动展开，默认 `false` |
| `timeout` | 否 | int | 通知默认消失时间，**单位 min**，默认 **720** |
| `cancel` | 否 | boolean | 是否直接结束通知；MIPUSH 下为 true 时触发移除通知 |
| `updatable` | 否 | boolean | 是否为持续性通知，默认 `false` |
| `reopen` | 否 | String | 同 id 通知被 cancel 后再次发送是否显示（前提 `update=true`）；`reopen` / `close`，默认 `close` |
| `filterWhenNoPermission` | 否 | boolean | 焦点通知权限关闭时：`false` 通知正常显示不被过滤；`true` 通知后被过滤不显示；默认 `false` |
| `orderId` | 否 | String | 订单号（MIPUSH 字段） |
| `sequence` | 否 | long | 本订单第几次更新（MIPUSH 实时更新类**必传**） |
| `param_island` | 是 | Object | 岛相关数据 |
| `carType` / `carColor` | 否 | String | 汽车类型 / 颜色 |

**岛属性数据**（`param_island`，原文表）：

| 参数 | 必选 | 类型 | 含义 |
| --- | --- | --- | --- |
| `islandProperty` | 否 | int | `1` 信息展示为主 / `2` 操作为主，默认 `1` |
| `islandOrder` | 否 | boolean | 通知更新且摘要态隐藏时是否更新岛排序，默认 `false` |
| `islandTimeout` | 否 | int | 岛自动消失时间，**单位 s**，默认 **3600** |
| `dismissIsland` | 否 | boolean | 摘要态消失 |
| `highlightColor` | 否 | String | 文字强调色 |
| `bigIslandArea` | 是 | Object | 大岛内容 |
| `smallIslandArea` | 是 | Object | 小岛内容 |
| `shareData` | 否 | Object | 拖拽分享参数：`pic` / `title` / `content` / `shareContent` |

**岛内容组件字段**（原文示例中真实出现）：`imageTextInfoLeft` / `imageTextInfoRight`（含 `type`、`picInfo.type`、`picInfo.pic`、`miui.focus.paramtextInfo` → `frontTitle` / `title` / `content` / `useHighLight`）、`picInfo`、`progress`（摘要态进度条）。摘要态设计规范原文给出大岛 A 区 2 种组件、B 区 9 种组件（含空）共 9 种排列组合，其中「模版 5」为「图文组件 1 + 进度文本组件」。

**焦点通知数据**（原文示例）：`baseInfo` → `title` / `content` / `colorTitle` / `type`；`hintInfo` → `type` / `title` / `actionInfo.action`。

**息屏显示数据**：`aodTitle`（必填，息屏焦点通知显示文案）、`aodPic`（否，息屏模式图标，不传默认应用图标，自定义需与 `miui.focus.pics` 对应）。

**状态栏焦点信息数据**（OS2 ROM）：

- 方式一（推荐）：参数 `ticker`（必填，OS2 焦点通知状态栏显示文案）、`tickerPic`（否）、`tickerPicDark`（否，暗黑模式下图标）。
- 方式二：自定义 RemoteViews，key 为 `miui.focus.rvBar`（Light 模式）与 `miui.focus.rvBarNight`（Dark 模式），均为 `RemoteViews` 类型，经 `notification.extras.putParcelable` 传入。
- 注意：**`ticker` 必传**。Q&A 原文：「如果你们已经适配 OS2，本次使用 OS3 这套给 OS2 设备发消息不需要携带 ticker 这个字段……如果适配了 OS3，没有传入 ticker 这个字段，状态栏不会展示。」

**图片资源通道**：`Bundle pics` → `pics.putParcelable("miui.focus.pic_start", Icon.createWithResource(...))`、`miui.focus.pic_end`、`miui.focus.pic_ticker`、`miui.focus.pic_aod`、`miui.focus.pic_imageText`、`miui.focus.pic_highlight`；`bundle.putBundle("miui.focus.pics", pics)`。

**Action 通道**：`Bundle actions` → `actions.putParcelable("miui.focus.action_test", action)`；`bundle.putBundle("miui.focus.actions", actions)`；`Notification.Action.Builder(Icon, "xxx", pendingIntent)`，PendingIntent 需 `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`。

### 2.4 系统级约束

- **通知超时**：`timeout` 默认 720 min。Q&A 原文：传 0 使用默认值 720 min 后消失；**可以传 -1，5 秒后消失**。
- **岛超时**：`islandTimeout` 默认 3600 s。Q&A 原文："timeout 的单位是 min，为焦点通知的消失时间；islandTimeout 的单位是 s，为岛的消失时间。"
- **兜底时长**（方案提报说明原文）："岛展开态默认展开 5s 后收起；若未设置消失逻辑，则触发兜底逻辑：**岛默认 1 小时消失；通知卡片默认 12 小时消失**"。
- **准入时长上限**（业务介绍原文，岛通知准入原则）："服务进程必须有明确的开始触发条件和结束条件，且整个进程的持续时间**不超过 12 小时**"；"需持续展示超过 12 小时的进程"属禁止通过场景。
- **数量**：通知栏焦点通知卡片无数量限制，平铺展示无折叠。
- **排序**：上岛场景按触发时间先后排序，新通知优先于老通知。
- **主动撤卡方式**（开发指南原文）："调用 android 原生接口，cancel 通知即可"（`NotificationManager` cancel）。Q&A 原文确认：`cancel` 传 true「会移除掉焦点通知和超级岛」。
- **payload 上限**（Q&A 原文）：`miui.focus.param` 大小**不超过 3072 字节**。
- **自动消失**：Q&A 原文"焦点通知降级成 OS1 的 PUSH 后，支持自动消失么？——暂不支持，建议使用 timeout 进行消失"。
- **进度表达方式**：摘要态模板 5 为「进度文本组件」，原文"当前进度：当前进度参数值；可自定义已完成进度条和未完成进度条的颜色"；展开态有独立的「进度组件 1 / 进度组件 2 / 进度组件 3」，原文"当前进度：已完成进度条在全部进度条上的相对位置；进度条颜色：支持自定义纯色、渐变色，推荐使用应用色"。

### 2.5 视觉规范

**摘要态（大岛）**（原文，pId=2143）：

- A 区图文组件图标：「可上传静态图标、应用图标或动态图标，兜底取应用图标；正方形，建议像素**不小于 88*88px**」。
- B 区更宽图形：「最大图形宽高比约为 **1.45:1**，建议像素不少于 **128*88px**」。
- B 区大图组件：「最大图形宽高比约为 **2.2:1**，建议像素不少于 **192*88px**」。
- 字体规范（反复出现）："大字为主要文本，前置、后置小字为辅助文本；保持文本简短……建议**总字数不超过 4 个中文字**"；"超长效果：大字变成小字，若仍超长会裁切，无滚动"；"窄字体：支持数字、字母，需要传相关参数"；"**强调色：可自定义颜色，大小字颜色一致**"。
- 定宽数字组件：「该区域最多支持显示 **3 位数**，最少 1 位数；超长后压缩显示为"..."」。
- 模版 9 A 区整体定宽文本：「支持最多 **3 个数字**，或者 **2 个中文文字**」；小字「最多支持 2 个中文文字」。
- 小岛：图标「必须为正方形，建议像素不少于 88*88px」；内容优先级原文"优先取业务上传的图标组件；若业务无上传，取大岛 A 区左侧的图标；若条件 1、2 都不满足，取应用图标"。

**展开态**（原文，pId=2142）：

- 头像图：正方形，建议像素**不小于 224*224px**；圆角取图片尺寸 1/4（如 48*48dp 取 12dp）。
- 识别图形组件：系统默认取桌面图标；支持自定义，正方形，建议像素不小于 **96*96px**；圆角取尺寸 1/4。
- 功能图标：正方形，建议像素不小于 **80*80px**。
- 封面图片：建议像素不小于 **224*288px**；圆角取短边尺寸 1/4。
- 倒计时带图组件图片：建议像素不小于 **96*96px**，需上传深浅模式两套图片。
- 富文本：「支持部分文字变色，可自定义颜色」；特殊标签：「可自定义文本颜色和背景色，**需要上传适配深浅模式的两种颜色**」。
- 进度条绘制规范（附录 2）：前进图形素材尺寸 60*47dp，导出尺寸不小于 4x（240*188px），图片大小**小于 100kb**；中间节点/目标节点素材尺寸 30*47dp，导出不小于 4x（120*188px），图片小于 100kb；节点到达前推荐灰色大气泡、到达后推荐主题色小气泡并上移加节点圆点。
- 模版选择是强约束：**"模版由多个组件构成，暂不支持自行拼接"**；**"OS3 模版 OS2 无法使用，如需在 OS2 上展示需选择 OS2 模版进行单独适配"**；"所有提交方案均需通过设计规范审核，未达标应用将无法获得正式上线权限"。

**系统字符限制**（Q&A 原文）："正常岛上文案左右各 **4 个字**（A 区、B 区），假如"骑手正在送货"这种，可以使用图文组件，最后两个汉字可缩小展示。"

**动态取色**：原文提供 `highlightColor`（文字本强调色）、`colorTitle`（焦点通知标题色）、`useHighLight`（`paramtextInfo` 内开关）；进度条颜色支持自定义纯色与渐变色。**未出现"从壁纸/系统主题动态取色"的条款**。

**超级岛背景色**（Q&A 原文）："摘要态点击展开的卡片，背景色只能是**深色**。通知栏可以浅色或深色。"

### 2.6 权限与开关

- **企业开发者认证**：原文要求"参照《开发者账号注册标准》和《企业开发者账号注册认证操作指南》进行注册认证"，经"企业管理员认证、信息采集"并"提交认证材料，进行人工审核"，"在 **1-3 个工作日**内完成审核"。
- **应用创建与审核**：创建应用、完善应用资料并上传包体，审核同样 1-3 个工作日；"由于国内和海外的开平相互独立，海外应用暂不支持接入国内开平的能力"。
- **开通 MIpush 推送服务**：仅 MIPUSH 路线需要；"若开发者仅需在本地通过客户端创建、更新和结束小米超级岛，请直接开通小米超级岛服务"。
- **开通小米超级岛服务**：管理中心选择在架应用 → 开发菜单 → 开放服务 → 选择小米超级岛服务 → 启用 → 填写联系信息 → **配置指纹证书**。
- **场景与方案提报（两级审核）**：① 上岛场景预审（描述业务上岛场景与核心信息，含运营活动、促销、借款等不符合产品定位的内容可能被驳回）；② 正式方案审核（补充完整方案：通知开始到结束的所有变化节点、节点对应信息、呈现效果图）。
- **设备白名单**（原文）："设备白名单**有效期为 30 天**，过期后自动失效，需重新添加"；"需根据 **OAID** 添加白名单设备，**每个应用可添加的设备上限为 10**"；"添加到白名单中的设备，**不做本地构建超级岛权限的校验**"。
- **签名与 App ID 鉴权**：需在平台同时添加正式证书指纹（Release 签名）和测试证书指纹（Debug 签名）；`com.xiaomi.xms.APP_ID` 必须配置。
- **OS2 焦点通知开发测试包申请**：发邮件至 `mipush-permission@xiaomi.com`，主题格式「【申请小米 OS2 焦点通知开发测试包】 -yymmdd-「APP名称」」，正文含应用信息、联系方式、所申请权限、场景审核通过截图与测试机版本截图（附件必传）。
- **焦点通知权限申请流程**（Q&A 原文）：邮件至 `mipush-permission@xiaomi.com`，主题「【申请开通小米焦点通知使用权限】 -yymmdd-「APP名称」」，正文含应用信息（应用名称、包名、appid）、channel 信息（`channel_id`、channel 名称、channel 描述）、公司主体、push 负责人/对接人、联系方式、所申请的通知权限、申请内容（接入场景详细说明、产品方案）；长期使用统一申请至当年 12 月 31 日，到期前 2 周重新发起申请。
- **上线验证与正式权限**：需提交已通过测试的应用**正式环境 apk**及场景验证方法（复杂场景需提供已加业务白名单的测试账号）；验证通过后获得正式权限。原文："如果应用同时接入了 OS2 焦点通知和 OS3 小米超级岛，**需要将代码合并到 APK 后统一进行验证**。"
- **灰度上线**：原文"初次获得小米超级岛正式权限的应用，小米超级岛会进行灰度逐步放量，待应用上线后约 **7~15 天**时间，全量到 100%"。
- **场景扩充需重走流程**：原文"应用新增场景接入小米超级岛，需要重新提交新场景的方案经过预审、正式审核、上线验证流程。**未经审核的场景上线，会导致应用的小米超级岛服务停用**，如果造成严重后果，可能导致应用下架"。
- **用户侧开关**（Q&A 原文）："应用通知权限默认关闭，用户首次开启应用会询问是否开启通知权限，如果用户开启通知权限，则**焦点通知权限也会默认开启**。你们开发完成后，平台会配置权限，权限开通之后，就能正常发送焦点通知了。"
- **应用内查询权限的官方代码**（Q&A 原文）：

```java
public static boolean hasFocusPermission(Context ctx) {
    boolean canShowFocus = false;
    try {
        Uri uri = Uri.parse("content://miui.statusbar.notification.public");
        Bundle extras = new Bundle();
        extras.putString("package", ctx.getPackageName());
        Bundle bundle = ctx.getContentResolver().call(uri, "canShowFocus", null, extras);
        canShowFocus = bundle.getBoolean("canShowFocus", false);
    } catch (Exception e) { }
    return canShowFocus;
}
```

- **版本探测 API**（开发指南原文）：

```java
// 返回值含义: 1: OS1 版本; 2: OS2 版本; 3: OS3 版本（小米超级岛通知模板）
int focusProtocolVersion = Settings.System.getInt(
    context.getContentResolver(), "notification_focus_protocol", 0);
```

- **费用**：Q&A 原文"焦点通知旨在提高用户体验，**暂不涉及收费**"。

### 2.7 回退行为

- 设备不支持时：开发指南原文"在**支持岛通知的设备**上，将以岛通知的形式展示发送的通知"——即同一份通知在不支持设备上按普通通知展示。
- 权限关闭时的行为由 `filterWhenNoPermission` 控制：`false`（默认）通知正常显示不被过滤；`true` 通知后被过滤不显示。
- 版本回退：OS2 上是焦点通知形态（状态栏、通知中心、锁屏、横幅、小折叠外屏），OS3 上升级为岛形态（含岛摘要态、岛展开态、焦点通知除状态栏外各个位置）。原文给出三种兼容方式：选用同时适用于 OS3 和 OS2 的展开态模版；或选两套展开态模版；或按 OS 版本下发不同数据。
- **未被提升的表现（Android 16 原生侧对照，【官网原文】）**：不满足提升条件时通知仍会就地更新，只是不被系统提升；用户也可主动降级。

### 2.8 已知坑与实测注意点

- **白名单是硬门槛**：GitHub 开源项目 HyperNotification 原文（【二手】<https://github.com/xzakota/HyperNotification>）："对于焦点通知有**白名单应用限制**，测试/使用前请先安装无视白名单的 Xposed 模块。"——与本喵取到的官方"场景审核 + 上线验证后获得正式权限"流程一致。
- **OS3 模板不能向下兼容 OS2**：必须单独适配 OS2 模版，否则 OS2 设备无展开态。
- **`ticker` 缺失导致状态栏不展示**（Q&A 原文明确）。
- **自定义图标必须与 `miui.focus.pics` 键一一对应**：`aodPic` 需与 `miui.focus.pics` 参数对应，`tickerPic` 同理。
- **MIPUSH 图片硬限制**（原文）：
  - 单张图片大小**不得超过 100k**，超过则不下载，视为下载失败；
  - 下载全流程最大等待 **180s**，超时失败；
  - 图片链接必须 **HTTPS**，否则按下载失败处理；
  - 宽高比在 **1.78(16:9) - 1(1:1)** 之间；
  - 单个通知图片数量**不得大于 10 张**，若超过 6 张，XMSF 只会按顺序下载 10 张，其他不下载；
  - 系统为通知下载图片消耗的流量会**分发计算到对应应用上**。
- **拖拽分享限制**（Q&A 原文）：支持微信、QQ、短信、浏览器，以卡片、文字、链接形式展示，**暂不支持小程序分享**；目前适合作打车、外卖等场景分享。
- **重复接入禁令**（业务介绍原文）：航班/火车行程"**由出行助手统一承接，第三方应用不得重复接入**"；callkit 语音视频通讯场景"**由 callkit 团队统一承接，第三方应用不得单独接入**"；系统已覆盖的基础功能（如原生闹钟、系统倒计时）也不得接入。
- **禁止通过场景**（业务介绍原文）：长期常驻类场景（无明确结束时间，如"长期天气展示""实时股价"、需持续展示超过 12 小时）；营销/推广类（促销活动通知、广告推送、优惠券发放提醒、借款/理财类营销信息）；无明确价值的静态信息（纯告知类、无进度的纯状态展示）；低价值高频打扰类（社交软件普通消息、应用内常规互动提醒）。
- **模版不可自行拼接**，且必须通过设计规范审核。
- **log 抓取方式**（Q&A 原文）：开发者选项打开时间悬浮窗 + 设置日志最大缓冲区，拨号 `*#*#284#*#*`，2-3 分钟后在 `sdcard/MIUI/debug_log` 生成 zip 日志。
- **与原生 ProgressStyle 的关系**：本喵通读了 pId=2131 / 2140 / 2141 / 2142 / 2143 / 2144 / 2146 全部正文，**未出现 `ProgressStyle`、`POST_PROMOTED_NOTIFICATIONS`、`setRequestPromotedOngoing` 或 Live Updates 的任何字样**。超级岛的进度表达完全走自有的 `bigIslandArea`/`smallIslandArea` 组件与 `progress` 字段。

---

## 3. 荣耀 MagicOS 灵动胶囊（developer.honor.com）

### 3.1 能力名称与文档 URL

官方叫法：**灵动胶囊**。荣耀开发者服务平台一级菜单存在「**服务分发 → 荣耀灵动胶囊**」入口。

| 页面 | URL | 本次取到的内容 |
| --- | --- | --- |
| 文档中心（导航含灵动胶囊一级菜单） | <https://developer.honor.com/cn/doc/guides/100255> | 确认「服务分发 → 荣耀灵动胶囊」入口存在 |
| 文档中心（另一入口页） | <https://developer.honor.com/cn/doc/guides/100119> | 同上一级菜单结构（服务分发 / 智慧服务 / 智能体服务） |
| 文档中心 | <https://developer.honor.com/cn/doc/guides/101571> | 目录同时出现「荣耀灵动胶囊」字样 |
| 智慧服务 · 快捷服务接入指南 · 配置指南 | <https://developer.honor.com/cn/doc/guides/100158> | 管理中心 > 生态服务 > 智慧服务平台；**须签署《荣耀智慧服务平台合作协议》** |
| 智慧服务 · 快捷服务接入指南 · 测试指南 | <https://developer.honor.com/cn/doc/guides/100159> | 真机测试白名单（用荣耀账号注册手机号添加） |
| 用户侧说明 | <https://www.honor.com/cn/support/content/zh-cn15867850/> | 功能定义与开关路径（官网 honor.com，属官方产品支持页） |
| 用户侧说明（备用） | <https://www.honor.com/cn/support/content/zh-cn15858861/> | 同上的另一入口 |

**未检索到官方文档**：本次**未取得灵动胶囊的字段级开发者接入正文**（能力声明方式、参数名、生命周期约束、视觉规范、审核流程细则均无官方正文）。实际尝试过的 URL 列出如下：

- <https://developer.honor.com/cn/doc/guides/100255>（仅为文档中心导航壳，正文为隐私声明相关）
- <https://developer.honor.com/cn/doc/guides/100119>（导航壳）
- <https://developer.honor.com/cn/doc/guides/101571>（导航壳）
- <https://developer.honor.com/cn/doc/guides/100158>（智慧服务配置指南，正文为快捷服务配置，未含胶囊字段）
- <https://developer.honor.com/cn/doc/guides/100159>（智慧服务测试指南，正文为测试流程）
- <https://developer.honor.com/cn/docs/11003/guides/overview>（返回"页面不存在"）
- <https://developer.honor.com/cn/tg/page/tg2022110110400003>（场景化建议服务页，本喵提取失败）
- 站内搜索「site:developer.honor.com 灵动胶囊」与「荣耀灵动胶囊 接入流程 / 接入指南 / 服务申请」多轮检索，均未命中字段级文档页。

### 3.2 接入方式

**已取到的官方事实（仅限平台流程层面）**：

- 智慧服务体系要求：「登录荣耀开发者服务平台，进入**管理中心 > 生态服务 > 智慧服务平台**」；「进入页面后，出现《**荣耀智慧服务平台合作协议**》弹窗，签署协议」。（<https://developer.honor.com/cn/doc/guides/100158>）
- 真机测试白名单机制（<https://developer.honor.com/cn/doc/guides/100159>）：「使用荣耀帐号注册的手机号码，添加真机测试的名单。如果开发者登录本系统的帐号和登录测试手机的荣耀帐号的手机号一致，则不需要单独添加白名单，默认存在白名单中。」

**依赖库 / SDK / 权限 / Manifest 声明 / 核心类 / 服务类型**：**未检索到官方文档**。

### 3.3 通知内容协议

**未检索到官方文档**。官方正文中未出现任何灵动胶囊的字段名、常量名或自定义 View 类型。

【二手线索，未取正文】：

- 荣耀俱乐部帖 <https://club.honor.com/cn/thread-30163837-1-1.html>，标题「灵动胶囊接入安卓实时通知规范」——指向「灵动胶囊通过 Android 实时通知规范接入」的判断。
- <https://linux.do/t/topic/3064474>，标题「个人开发者如何把应用送上荣耀灵动胶囊？Android 16 实时通知 Demo 指南」——该页正文含针对 AI 抓取的拒绝声明，**本次未采用其正文内容**，仅记录标题与 URL 作为线索。
- <https://club.honor.com/cn/thread-28534734-1-1.html> 一类帖子列出当前支持应用（高德地图、百度地图、滴滴出行、航旅纵横、国铁吉讯、快递100、京东等），可反映现状范围，非接入规范。

### 3.4 系统级约束

**未检索到官方文档**（更新频率、倒计时与进度表达、卡片生命周期上限、主动撤卡方式均无官方正文）。

官网用户侧支持页可见相关描述（【官网原文】honor.com 域名）：灵动胶囊「位于屏幕顶部，以"胶囊"形态显示，展示进行中的任务，便于查看实时状态、执行快捷操作或快速进入应用」；「可收缩为"小球"形态，并在一段时间后，重新……」；「灵动胶囊的显示时机、显示时长、交互方式等因任务类型不同」而有差异。

### 3.5 视觉规范

**未检索到官方文档**（图标尺寸、单色/彩色要求、动态取色、标题与副标题长度限制均无官方正文）。

### 3.6 权限与开关

- **合作协议**：「《荣耀智慧服务平台合作协议》」需在管理中心签署（<https://developer.honor.com/cn/doc/guides/100158>）。是否必须企业开发者身份：**未检索到官方文档**明确条款。
- **测试白名单**：真机测试需用荣耀账号注册手机号加入名单（<https://developer.honor.com/cn/doc/guides/100159>）。
- **用户侧开关**（【官网原文】<https://www.honor.com/cn/support/content/zh-cn15867850/>）：
  - 开启/关闭单个服务的胶囊：进入 **设置 > 智慧助手 > YOYO 建议**，查找并点击服务，在「服务提供方式」下勾选或取消勾选「**通知、胶囊**」。
  - 关闭系统服务应用的胶囊：进入 **设置 > 通知和状态栏 > 灵动胶囊**。
  - 官网原文提示：「三方应用和服务支持情况，请参考设置 > 智慧助手 > YOYO 建议」。

### 3.7 回退行为

**未检索到官方文档**。

### 3.8 已知坑与实测注意点

- **官方开发者文档缺口是本次最大的证据短板**：荣耀开发者服务平台虽有「荣耀灵动胶囊」一级入口，但本喵多轮检索未能定位到字段级接入正文页。若后续需要落地，建议直接走 <https://developer.honor.com/cn/> 的在线提单/商务合作通道（页面公示合作邮箱 `Developers_HKBD@honor.com`、客服 `Developers_HKSupport@honor.com`）索取接入文档。
- **胶囊开关挂在 YOYO 建议的服务粒度上**，不是全局开关，用户可对单个服务单独关闭——适配时需把「用户关掉了本服务的胶囊」当作正常分支处理。
- **与原生 Android 16 实时通知的关系**：官方正文未取到；二手线索（见 3.3）指向「按 Android 实时通知规范接入」。
- **荣耀平台另有「开放能力 → 场景化建议服务（Suggestions Kit）」与「开放能力 → 推送服务」两条独立线路**，页面结构上与「服务分发 → 荣耀灵动胶囊」并列；检索中出现的 <https://developer.honor.com/cn/docs/11003/reference/com.hihonor.android.magicx.intelligence.suggestion.model/DateInfo> 属于场景化建议服务的 API reference，**不等同于灵动胶囊的接入文档**。
- 推送侧约束（【官网原文】摘要，来自站内导航命中页）：<https://developer.honor.com/cn/docs/11002/guides/notification-push-standards> 与 <https://developer.honor.com/cn/docs/11002/guides/notification-class> 属「通知规范」体系，涉及消息分类与推送数量管理；这是普通通知侧的约束，与灵动胶囊不是同一份规范。

---

## 4. 三家接入门槛对照

| 维度 | OPPO 流体云 | 小米超级岛 / 焦点通知 | 荣耀灵动胶囊 |
| --- | --- | --- | --- |
| 企业开发者认证 | **必须**（原文"首次接入需注册 OPPO 开放平台账号并完成企业认证"） | **必须**（企业开发者认证，1-3 工作日审核；另有应用审核 1-3 工作日） | **未检索到官方文档**（但需签《荣耀智慧服务平台合作协议》） |
| 是否需签合作协议 | 未在已取文档中明示，但需联系 OPPO 商务确认 | 非"协议"表述，但需两级场景/方案审核 | **是**，《荣耀智慧服务平台合作协议》 |
| 是否仅限系统白名单 | **实质上是**：意图名、serviceId、垂域码值均由 OPPO 分配；需 OPPO 提供调测 APK | **是**：场景预审 + 正式方案审核 + 上线验证后获正式权限；调试期设备白名单（10 台 / 30 天 / 按 OAID） | 未见官方条款；用户侧按 YOYO 建议服务粒度可控开关 |
| 是否需商务对接 | **是**，邮箱 `fwst@oppo.com` | 走平台自助流程（管理中心开服务 + 提报），另有 `mipush-permission@xiaomi.com` 走权限邮件 | 未取到官方流程页；平台公示 `Developers_HKBD@honor.com` |
| 自助程度 | 低（强商务依赖） | 中（有明确的自助审核链路与平台入口） | 未知 |
| 灰度/放量 | 未见条款 | **有**：初次获权限后约 7~15 天放量到 100% | 未见条款 |
| 费用 | 未见条款 | 原文"**暂不涉及收费**" | 未见条款 |
| 教育/课表类场景 | 已取垂域表中**无教育类**；TASK 通用里程碑最接近 | 禁止通过清单中未点名教育，但"无明确价值的静态信息""长期常驻类"会命中风险 | 未知 |

---

## 5. 给课表 App 的直接结论

1. **三家都不能靠"发一条普通通知就自动上岛"**。OPPO 走 `IntelligentIntent`（意图共享）+ OPPO 分配的 serviceId；小米走 `miui.focus.param` extras + 前置审核；荣耀无公开字段级文档。
2. **原生 `Notification.ProgressStyle` 不是万能钥匙**。Google 官方原文明确"OEM 可以追加额外条件"，三家文档中只有 OPPO 有官方口径承认承接原生 Live Updates API（且为二手转述）；小米官方文档通篇只有私有 extras 协议。
3. **课表场景的最大结构性障碍是场景准入，不是技术实现**。OPPO 已取垂域表无教育类；小米明确禁止"长期常驻类场景""无明确价值的静态信息"；小米还有 12 小时进程时长上限。课表"一节课 45 分钟"在小米侧可落，OPPO 侧受胶囊 5 分钟硬上限限制（需改用卡片/桌面卡入口）。
4. **可落地的最短路径**：优先做**普通通知 + 状态栏进度**的基线，再按厂商分别接私有通道；小米侧可先在设备白名单内自测（不需等正式权限），OPPO 侧必须先拿到 OPPO 分配的 `intentName` / `serviceId` / 垂域码值。
5. **荣耀需先补证据**：在拿到官方接入文档前，不建议按二手线索直接实现。
