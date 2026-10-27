# vivo（OriginOS 原子通知 / 原子岛 / 小V建议）与华为（HarmonyOS 实况窗 Live View）通知适配调研

检索时间：2026-10-27（Asia/Shanghai）。仅使用官方域名优先的来源；二手来源单独标注「二手」。每条关键结论后附实际访问过的 URL；未查到官方文档的条目明确写「未检索到官方文档」并列出实际尝试过的 URL。

---

# 一、vivo（dev.vivo.com.cn）

## 1.1 能力名称（官方叫法）与文档 URL

| 官方名字 | 作用 | 文档 URL |
| --- | --- | --- |
| 原子通知 | 实时任务态通知，可出现在熄屏、锁屏、状态栏、通知中心、桌面组件 | https://dev.vivo.com.cn/documentCenter/doc/896 |
| 原子岛 | OriginOS 5 起的全局信息窗口（左岛/右岛），承载原子通知胶囊、原子随身听、小V建议 | https://dev.vivo.com.cn/documentCenter/doc/895 |
| 原子通知接入指导 | 准入申请流程 | https://dev.vivo.com.cn/documentCenter/doc/894 |
| 原子通知规范 | 产品形态、交互、样式与视觉规范 | https://dev.vivo.com.cn/documentCenter/doc/895 |
| 原子通知技术规范 | 数据协议、字段、云端更新接口 | https://dev.vivo.com.cn/documentCenter/doc/896 |
| OriginOS 通知样式规范 | 普通通知基线（回退目标） | https://dev.vivo.com.cn/documentCenter/doc/577 |
| 自定义通知适配规范 | 自定义 RemoteViews 约束 | https://dev.vivo.com.cn/documentCenter/doc/901 |
| 小V建议技术规范 | 小V建议（Jovi 建议）服务接入，可复用原子通知卡片形态 | https://dev.vivo.com.cn/documentCenter/doc/919 |

说明：dev.vivo.com.cn 文档中心「vivo系统适配指南 > 原子通知」栏目下正好是「原子通知接入指导 / 原子通知规范 / 原子通知技术规范」三页，分别对应 doc/894、doc/895、doc/896，栏目导航在 https://dev.vivo.com.cn/documentCenter/doc/896 页面左树可见。

## 1.2 接入方式

- 依赖库 / SDK：无专用原子通知 SDK。官方架构为「整体使用系统通知方案（本地发送通知 或 push 更新通知）」；应用客户端若需要云端更新，需接入 VPush SDK 获取设备 token（regId）。
  来源：https://dev.vivo.com.cn/documentCenter/doc/896 （一、架构设计）；VPush regId 获取指引 https://dev.vivo.com.cn/documentCenter/doc/365
- 权限：官方技术规范未要求声明原子通知专用 Android 权限；未出现原子通知专属 permission 名。约束点在于通知渠道：必须「先有 NotificationChannel 再发原子通知」，否则「发送失败、不能出卡」。
  来源：https://dev.vivo.com.cn/documentCenter/doc/896 （五、重要说明 第 8 条、第 11 条）
- AndroidManifest 声明：未检索到官方文档要求任何原子通知专用 manifest 节点。文件内只涉及通知渠道与推送 SDK 的常规配置。
  尝试过的 URL：https://dev.vivo.com.cn/documentCenter/doc/896 、https://dev.vivo.com.cn/documentCenter/doc/894 、https://dev.vivo.com.cn/documentCenter/doc/895
- 核心类 / API：
  - android.app.NotificationManager 的 notify / cancel
  - Bundle 承载 notification.superx.* 专有字段，通过 Notification 的 extras 下发
  - 云端更新：VPush 接口 POST https://api-push.vivo.com.cn/livemessage/send
  - 鉴权：POST https://api-push.vivo.com.cn/message/auth
  来源：https://dev.vivo.com.cn/documentCenter/doc/896 （三、Demo代码；四、云端更新）
- 系统给出的反射接口（官方原文，位于 android.app.NotificationManager）：
  - public boolean getSceneStatus(String pkgName, String sceneName) —— 返回该场景开关是否打开，可用于判断是否发送原子通知
  - public boolean isSupportCustomFun(String pkgName, String sceneName) —— 返回当前版本是否支持该模块
  来源：https://dev.vivo.com.cn/documentCenter/doc/896 （五、重要说明 第 6 条、第 9 条）
- 服务类型：系统通知（Notification）+ 厂商推送（VPush）云端更新。

## 1.3 通知内容协议（真实字段名）

核心参数塞在 Bundle 里，Key 前缀统一为 notification.superx.。

| 字段 | 必填 | 类型 | 说明 |
| --- | --- | --- | --- |
| notification.superx.operation | Y | int | 生命周期：0 创建 / 1 更新 / 2 结束 |
| notification.superx.showNotify | N | boolean | 原子通知展示失败时是否降级为普通通知，默认 true |
| notification.superx.template | Y | int | 模板：1 强调信息 / 2 进度可视化 / 3 左右信息对称 / 4 基础 / 5 导航 |
| notification.superx.baseInfos | Y | Bundle | 基础信息区 |
| notification.superx.infos | Y | Bundle | 对应模板的扩展区 |
| notification.superx.shortInfos | Y | Bundle | 小卡和 OriginB 锁屏模板 |
| notification.superx.capsule | N | Bundle | 状态栏普通胶囊（不支持岛的设备取此值） |
| notification.superx.island | N | Bundle | 原子岛参数（支持岛设备取此值） |
| notification.superx.clickResp | Y | PendingIntent | 大卡/小卡/胶囊点击事件 |
| notification.superx.scene | Y | String | 场景枚举 |
| notification.superx.keepDuration | N | int | 结束后存档时长，单位秒，最长 1 小时 |

场景枚举实测取值（官方原文，含官方拼写）：MOVIE 电影、HEALTH_REGISTER 挂号、TAXI 打车、TAKEOUT 外卖、DELIEVERY 快递、NAVIGATION 导航、CAR_STATE 车机状态、METTING 会议日。
来源：https://dev.vivo.com.cn/documentCenter/doc/896 （2.1 核心参数）

状态栏胶囊（notification.superx.capsule）：state（0 不展示 / 1 展示）、icon、content、contentColor、bgColor、showTime、clickResp。
来源：https://dev.vivo.com.cn/documentCenter/doc/896 （2.2 状态栏胶囊模版）

原子岛（notification.superx.island）：leftTemplate（1 图片+文本）、leftInfo（icon、content）、rightTemplate（1 律动动画 / 2 进度动画 / 3 加载中动画 / 4 文本+图片 / 5 图片+文本 / 6 胶囊文本）、rightInfo、islandClick（0 出卡 / 1 跳落地页 / 2 不支持跳转）、clickResp、showTime、template、baseInfos、infos。
右岛资源：waveColor、waveState、icon、content、capsuleContent、capsuleBgColor、clickResp、progressValue、progressColor、progressBgColor、progressState（-1 失败 / 0 更新中 / 1 成功）、loadingColor。
来源：https://dev.vivo.com.cn/documentCenter/doc/896 （2.3 原子岛[左岛/右岛]模版）

基础模板（notification.superx.baseInfos）：icon、title、content、subInfo（0 不展示 / 1 文本 / 2 胶囊文本 / 3 图片 / 4 多图片（最多三个，>1 张必须每张配点击事件）/ 5 进度 / 6 加载中）、subText、subTextColor、subCapsuleBgColor、subImage、subInfoClickResp、subImageList、subInfoClickRespList、subProgress、subProgressColor、subProgressBgColor、progressState、progressContent、loadingColor。
来源：https://dev.vivo.com.cn/documentCenter/doc/896 （2.4 基础模版）

强调信息模板（notification.superx.infos）：describe、coreInfo、image、imageClickResp、subReverse。
进度可视化模板（notification.superx.infos）：nodeIcon（2~5 个，其他情况异常；4~5 个时系统强制不显示指示器）、progress（0~100）、indicatorIcon、indicatorLoc（1 覆盖在进度线上方（默认）/ 2 显示在进度线上方）、progressColor、BgColor。
左右信息对称模板（notification.superx.infos）：leftMain、leftSub、midTopMsg、midMainType（1 图片 / 2 文本）、midMainIcon、midMainMsg、midSubType、midSubIcon、midSubMsg、rightMain、rightMainExtra、rightSub。
小卡与 OriginB 锁屏（notification.superx.shortInfos）：icon、image、imageClickResp、OriginBImage、describeShort、coreInfoShort。
导航模板（notification.superx.infos）：navIcon、navMsg（最多两行，两行用 # 分隔）。
来源：https://dev.vivo.com.cn/documentCenter/doc/896 （2.5 ~ 2.9）

本地创建示例（官方 Demo，节选真实 Key）：

    Bundle b = new Bundle();
    b.putInt("notification.superx.operation", 0);
    b.putBoolean("notification.superx.showNotify", true);
    b.putInt("notification.superx.template", 1);
    b.putParcelable("notification.superx.clickResp", mPendingIntent);
    b.putString("notification.superx.scene", "HEALTH_REGISTER");
    Bundle baseInfo = new Bundle();
    baseInfo.putParcelable("notification.superx.baseInfos.icon", icon);
    baseInfo.putCharSequence("notification.superx.baseInfos.title", title);
    baseInfo.putCharSequence("notification.superx.baseInfos.content", content);
    b.putBundle("notification.superx.baseInfos", baseInfo);
    Notification notification = new NotificationCompat.Builder(mContext, CHANNEL_NORMAL)
        .setContentTitle(...).setContentText(...).setSmallIcon(...).setExtras(b).build();
    mManager.notify(notificationId, notification);

Demo 包下载：https://swsdl.vivo.com.cn/appstore/developer/uploadFile/20250903/8vms6x/superx_demo.rar
来源：https://dev.vivo.com.cn/documentCenter/doc/896 （三、Demo代码 3.1）

云端更新协议（VPush，接口 POST /livemessage/send）：liveMessage 对象含 operation（1 更新 / 2 结束）、scene、keepDuration（默认不存档，最长 1h）、showNotify、templateType（1 强调信息 / 2 进度可视化 / 4 基础模板）、changeRecord（乱序保护：小于当前值的更新丢弃）、templateData、capsuleData、customSuperx、displays（触点位掩码：0x001 通知 / 0x010 锁屏 / 0x100 状态栏 / 0x1000 桌面组件 / 0x10000 熄屏 / 0x100000 魔盒 / 0x1000000 小V建议）。
图片来源对象 Image：path（必填，本地资源）、imgUri（网络图片，需申请权限后支持，无权限则回落 path）。
回执类型：300 原子通知送达；301 下发失败（31001 参数校验不合法、31002 内容审核不通过、31003 设备未订阅、31004 regId 不存在、31005 设备超 14 天不活跃、31006 设备不支持原子通知远程更新）；302 客户端展示失败（32001 用户关闭该应用原子通知、32002 原子通知不存在、32003 系统版本不支持、32004 图片资源不存在、32005 应用被卸载、32006 跳转页面不合法、32007 消息超过频控限制、32008 当前应用或场景未开通原子通知权限、32009 原子通知展示失败转普通通知）。
来源：https://dev.vivo.com.cn/documentCenter/doc/896 （四、云端更新 4.1/4.2/4.3）

## 1.4 系统级约束

- 更新频率：单个活动最多 10s/次刷新，超过的丢弃不下发。
- 空闲超时：单个活动超过 2 小时不更新，此条原子通知被系统清除。
- 生命周期上限：单个活动最长显示 8 小时，超过后被系统清除。
- 倒计时与进度表达：进度可视化模板用 nodeIcon + progress(0~100) + indicatorIcon/indicatorLoc；原子岛右岛用 progressValue / progressColor / progressBgColor / progressState 表达进度环；胶囊与文案支持计时器文本（island.superx.leftInfo.content 原文写「支持计时器文本」）。胶囊也支持 showTime 控制显示秒数。
- 撤卡方式：
  - 本地结束：operation 置 2 后 notify，可配 notification.superx.keepDuration 存档后继续显示一段时间（最长 1 小时）；
  - 直接取消：NotificationManager.cancel(tag, id)，需传固定 tag "VIVO_SUPERX_TAG"；
  - 云端结束：liveMessage.operation = 2，keepDuration 可选。
  来源：https://dev.vivo.com.cn/documentCenter/doc/896 （3.3 本地结束；4.2 单推）
- 用户删除行为：用户手动删除通知中心/锁屏的原子通知后，本次活动不再显示原子通知；删除状态栏原子通知，不影响其他触点的显示。
- 进程被杀：应用进程被杀后，系统默认不清除原子通知，由业务方自行决定是否清除。
- 前台一致性：应用前台不显示状态栏原子通知，退出页面后重新显示。
来源：https://dev.vivo.com.cn/documentCenter/doc/895 （一、4 注意事项；二、2 显示逻辑）

## 1.5 视觉规范

- 图标：端侧发送的图片 Icon 宽高不大于 1000*1000（官方原文）。原子通知 Icon 资源无更新可不传，复用之前的资源。
  来源：https://dev.vivo.com.cn/documentCenter/doc/896 （五、重要说明 第 15、2 条）
- 单色 / 彩色：官方规范未出现「必须单色」的限制，胶囊 icon、强调信息 image、小卡 image、岛 leftInfo.icon 等均直接取业务方图片资源。胶囊背景默认白色（有描边），文字默认黑色，都可自定义；原子岛胶囊在「支持原子岛的机型」显示。
  来源：https://dev.vivo.com.cn/documentCenter/doc/895 、https://dev.vivo.com.cn/documentCenter/doc/896
- 动态取色：未检索到官方文档提供系统级动态取色（壁纸取色）能力；官方只要求「颜色自定义时保证文本可读性」「颜色默认值可写 0x0（alpha 为 0，视为异常值）」。颜色格式要求 #RGB 或 #ARGB。
  尝试过的 URL：https://dev.vivo.com.cn/documentCenter/doc/895 、https://dev.vivo.com.cn/documentCenter/doc/896
- 取向 / 方向：原子岛以摄像头为基准分左右两侧，左侧 2 种（图片、图片+文本），右侧 5 种（图片、文本、图片+文本、文本+图片、胶囊文本）；为保证平衡，左右两侧需都有内容。左侧图片一般是应用/功能图标，右侧一般是状态图标。
- 尺寸：官方「原子通知规范」正文未给出像素级图标规格，只给规范示意图（图片资源规范以图示为准，正文无 px 文字描述）。相关 px 数值仅见于「自定义通知适配规范」：图标按钮 24x24dp、最多 3 个、按钮间隔 20dp；自定义按钮模版自定义内容最多显示两行。
  来源：https://dev.vivo.com.cn/documentCenter/doc/901
- 文本长度：胶囊文本「仅显示单行文本，字符数需控制在可容纳范围内」；强调信息区「只显示一行，文本过长打点」；辅助描述「只显示一行」；基础信息区主文本「可显示两行，文本过长打点」，副文本「可显示一行」；小卡基础信息/强调信息「只显示一行」；Origin 锁屏基础信息「最多可显示三行」。官方未给出精确字符数上限。
- 跑马灯：原子岛纯文本「文本过长时跑马灯显示，仅在初次曝光时跑 1 次，单次跑马灯最长时长 20 秒；跑马灯结束后模糊截断显示」。
- 动态图标：官方公共动态图标可直接调用 —— 加载图标、进度图标、声波图标、完成图标、失败图标、异常图标、进度停止。进度起点及方向为 12 点方向顺时针；进度停止图标支持独立点击（停止操作）。这些公共动态图标作为状态图标「仅显示在摄像头右侧」。
来源：https://dev.vivo.com.cn/documentCenter/doc/895 （二、3 显示样式 3.1 ~ 3.5）
- 深浅模式：大卡在支持原子岛的机型上悬浮大卡为黑色背景；小卡在熄屏上卡片背景为黑色，上传图片资源时要保证清晰可见。要求「上传图片资源时要保证清晰可见」。
来源：https://dev.vivo.com.cn/documentCenter/doc/895 （3.3 大卡、3.4 小卡）
- UI 资源：官方提供原子通知 UI 模板与字体包（zip），见 doc/895 第三节「三、UI设计资源」。

## 1.6 权限与开关

- 用户侧开关：设置 > 通知与状态栏 > 原子岛（可改「拖放直达」「小V建议」等）；另有 设置 > AI > 小V建议 相关开关。
  来源：https://www.vivo.com.cn/service/questions/all?categoryId=170&questionId=1751（vivo 官方支持页）
- 应用侧场景开关：可通过反射 NotificationManager.getSceneStatus(pkgName, sceneName) 查询场景开关。
  来源：https://dev.vivo.com.cn/documentCenter/doc/896 （五、重要说明 第 6 条）
- 但官方同时明确「不要判断通知开关状态！」（原文第 16 条），实际接入需自查：这两条一处给接口、一处给禁令，建议以真机实测为准。
  来源：https://dev.vivo.com.cn/documentCenter/doc/896 （五、重要说明 第 16 条）
- 白名单 / 审核 / 签约：需要。接入流程为：
  1. 应用必须已上架 vivo 应用商店；
  2. 发送接入资料到 oosyztz@vivo.com 申请接入权限，vivo 原子通知团队审核准入（7-10 个工作日）；
  3. 完成需求定义与 UI 设计，双方评审并定稿；
  4. vivo 开通权限，开发者开发，双方联调测试；
  5. 上线（功能上线为机型逐步放量）；
  6. 定期体验巡检。
  申请邮件必须包含：应用名称、应用包名、开发者联系电话/微信、接入的具体服务场景（如外卖配送、打车进度、演出提醒）、需求定义文档、服务示意图。
  来源：https://dev.vivo.com.cn/documentCenter/doc/894
- 禁止准入内容：广告营销内容、违法违规内容、隐私信息内容（若必须展示建议脱敏）。
  来源：https://dev.vivo.com.cn/documentCenter/doc/895 （1.2 适用场景）

## 1.6 补充：小V建议（Jovi 建议）的接入方式

- 两种接入路径：（1）依赖账号授权绑定（vivo 端引导用户绑定），（2）不依赖账号绑定直接对接服务内容。
- 服务端以 openapi 推送：接口 {域名}/openapi/event/notify，环境为 open-platform-api-dev/test/pre/prd.vivo.com.cn；鉴权用 clientId + clientSecret + timestamp + traceCode + sign；eventCode 支持 bind / unbind / update / pushSwitch。
- 端侧通路提供 IntentionKit SDK，Maven 仓库 https://repos.vivo.com.cn/maven/repository/external-lib/；需向对接人提供 APK 包名 + 签名证书 SHA256 指纹做鉴权。
- 跳转支持：快应用 / 安卓 app（包名 + deeplink + 最小支持版本）/ H5 / 微信小程序。
  来源：https://dev.vivo.com.cn/documentCenter/doc/919 （1、2、3 章节）
- 原子通知可同时在「小V建议」组件卡片展示，字段见 customSuperX，需向 vivo 商务侧了解。
  来源：https://dev.vivo.com.cn/documentCenter/doc/896 （五、重要说明 第 17 条）

## 1.7 回退行为

- 官方字段级回退：notification.superx.showNotify（默认 true）。原子通知展示失败时，true 会展示成普通通知，false 不会。
  来源：https://dev.vivo.com.cn/documentCenter/doc/896 （2.1 核心参数）
- 云端回执可识别降级：302/32009「原子通知展示失败，转普通通知」；32003「系统版本不支持原子通知」；32008「当前应用或场景未开通原子通知权限」。
  来源：https://dev.vivo.com.cn/documentCenter/doc/896 （4.3 回执）
- 官方明确要求：针对同一信息，若以原子通知形式发送，则不要再以普通通知形式发送；当原子通知发送异常或系统不支持时，业务方可选择是否转为普通通知显示。
  来源：https://dev.vivo.com.cn/documentCenter/doc/895 （一、4 注意事项 第 1 条）
- 普通通知应走 OriginOS 标准样式（普通/大文本/进度条/媒体播放/对话），不推荐自定义样式。普通通知单应用显示上限 50 条，通知中心上限 100 条。
  来源：https://dev.vivo.com.cn/documentCenter/doc/577 （五、通知样式；八、FAQ Q1）

## 1.8 已知坑与实测注意点

1. 必须先把 NotificationChannel 建好再发原子通知，「无渠道会造成发送失败，不能出卡」；如果删除渠道，原子通知也会被清除。来源：doc/896 五、第 8、11 条。
2. 谨慎使用 Notification.Builder.setAutoCancel(true)：官方明确「会造成从通知栏点击原子通知后，原子通知被系统 cancel 掉」（文档第 7 条与第 10 条重复强调）。来源：doc/896。
3. 原子岛数据与状态栏胶囊数据都要发：存在 Flip 这类机型，外屏仍显示胶囊。来源：doc/896 五、第 1 条。
4. 结束且无存档期时，只需传 operation 结束标识，其他数据可不传。来源：doc/896 五、第 4 条。
5. 颜色异常默认值可写 0x0（alpha 为 0，视为异常值）。来源：doc/896 五、第 5 条。
6. 多语言：建议 app 将本地语言环境上报服务器，至少适配中英文。来源：doc/896 五、第 3 条。
7. 一键换机场景会出现「新机器下单、仍向旧机器发 push」：需升级 push sdk 至 500 级及以上，且每次冷启动调用一次 turnonpush，再用 getregid 取本轮 regId 并同步服务端，避免持久化旧 regId 造成映射错误。来源：doc/896 五、第 13 条。
8. timeToLive 建议用默认 24 小时，自定义至少 30 分钟以上。来源：doc/896 五、第 14 条。
9. 鉴权接口 /message/auth 频控为单 app 10 次/s，超频返回错误码 10093；authToken 有效期 1 天（文档同时写「24 小时过期」与「有效期默认为 1 天」），业务侧建议中心缓存并 1-2 小时更新一次。来源：doc/896 4.1。
10. 自定义通知的兼容坑（OriginOS 6.0 起渲染机制改为遵循原生 Android 规范）：图标重复（不要再自行绘制 APP icon）、文字截断、非黑色字体可读性差、加背景色块与半透明卡片冲突、内容挤压重叠、TargetSDK<31 的完全自定义布局默认折叠、自定义区域不会自动反色需自行适配深色模式。vivo 会定期检测，存在问题的自定义通知默认显示为收起状态。来源：doc/901。
11. 官方提供的 UI 模板与字体包是 sketch 之外的 zip 资源，纯文字规格（如各触点图标 px）在正文缺失，需要下载模板核对或真机验收。来源：doc/895 第三节。

---

# 二、华为

华为侧存在两条并行的官方接入线路，必须先分清：

- 线路 A（原生鸿蒙 / ArkTS）：Live View Kit（实况窗服务），@kit.LiveViewKit。
- 线路 B（Android 兼容 / HMS Core 推送）：实况窗通知扩展参数（notification.live.*），走 HMS Core 推送服务的 Android 文档。

## 2.1 能力名称（官方叫法）与文档 URL

线路 A：Live View Kit（实况窗服务）

| 页面 | URL |
| --- | --- |
| Live View Kit 简介 | https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-introduction |
| ArkTS API liveViewManager | https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-liveviewmanager |
| ArkTS API 错误码 | https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-error-code |
| LiveViewLockScreenExtensionAbility | https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-lock-screen-ability |
| LiveViewCardExtensionAbility | https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-card-ability |
| 实况窗设计规范（开发侧） | https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-design-formula |
| 实况窗 UX 设计指南 | https://developer.huawei.com/consumer/cn/doc/design-guides/system-features-live-view-0000001955186861 |
| 开发准备 / 开通权益 | https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-rights |
| 接入联调测试（调测设备管理） | https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-joint-commission-test |
| 申请实况窗正式权限 | https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-formal-authority |
| 构建本地实况窗 | https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-create-locally |
| 通过 Push Kit 更新实况窗 | https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-update-by-push |
| 推送实况窗消息（Push Kit 指南） | https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/push-update-liveview |
| Live View Kit 与 Push Kit 字段关联表 | https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-api-map |
| FAQ | https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-faq（子页 liveview-faq-1 ~ liveview-faq-5） |
| 消费者侧支持机型与已适配应用 | https://consumer.huawei.com/cn/support/content/zh-cn16019387/ |
| 消费者侧 HarmonyOS 4 适配名单 | https://consumer.huawei.com/cn/support/content/zh-cn15978591/ |

说明：这一系列页面存在两个可访问前缀 doccenter-capabilities/liveview-* 与 harmonyos-guides/liveview-*，指向同一内容，实测均可打开。

线路 B：实况窗通知（HMS Core 推送服务 Android 文档）

| 页面 | URL |
| --- | --- |
| 业务介绍 | https://developer.huawei.com/consumer/cn/doc/HMSCore-Guides/android-live-intro-0000001563098012 |
| 开通服务（权限申请） | https://developer.huawei.com/consumer/cn/doc/HMSCore-Guides/android-live-guide-right-0000001562619196 |
| 设计规范 | https://developer.huawei.com/consumer/cn/doc/HMSCore-Guides/android-live-design-0000001613258245 |
| 构建本地实况窗通知 | https://developer.huawei.com/consumer/cn/doc/HMSCore-Guides/android-live-guide-local-0000001613377893 |
| 实况窗通知扩展参数 API | https://developer.huawei.com/consumer/cn/doc/HMSCore-References/android-live-0000001613100205 |

注意：线路 B 的这几个页面右上角更新时间均为 2024-04-23，正文写明「实况窗目前为 Beta 测试阶段，优先邀请适配 HarmonyOS NEXT 且满足场景准入原则的应用接入。Beta 阶段首批受邀应用已陆续接入，后续邀请暂未开放，敬请期待。」
来源：https://developer.huawei.com/consumer/cn/doc/HMSCore-Guides/android-live-intro-0000001563098012 、https://developer.huawei.com/consumer/cn/doc/HMSCore-Guides/android-live-guide-right-0000001562619196

## 2.2 接入方式

### 线路 A（HarmonyOS NEXT 原生，ArkTS 侧）

- 依赖库 / SDK：系统 Kit。导入方式为
    import { liveViewManager } from '@kit.LiveViewKit';
  来源：https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-liveviewmanager（导入模块）
- 核心类 / API（模块 liveViewManager）：
  - isLiveViewEnabled(): Promise<boolean>
  - startLiveView(liveView: LiveView): Promise<LiveViewResult>
  - updateLiveView(liveView: LiveView): Promise<LiveViewResult>
  - stopLiveView(liveView: LiveView): Promise<LiveViewResult>
  - getActiveLiveView(...)
  - isGeofenceTriggerEnabled()、startLiveViewByTrigger(liveView, trigger)、stopLiveViewByTrigger(liveView, trigger)
  起始版本：4.1.0(11)；系统能力：SystemCapability.LiveView.LiveViewService；仅 Stage 模型；设备行为差异：Phone、Tablet 可正常调用，其他设备无效果。
  来源：https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-liveviewmanager
- ExtensionAbility（自定义扩展区 / 锁屏沉浸）：
  - LiveViewLockScreenExtensionAbility（锁屏沉浸实况窗可视化区），继承 UIExtensionAbility，起始版本 5.0.0(12)。
  - LiveViewCardExtensionAbility（实况窗卡片自定义扩展区），起始版本 26.0.0，需实现 onRender(param: Record<string,string>): CardInfo。
  来源：https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-lock-screen-ability 、https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-card-ability
- 权限：未检索到需要应用声明 ohos.permission.SYSTEM_FLOAT_WINDOW 的官方文档。官方给出的运行期校验是 isLiveViewEnabled() 与错误码 1003500004（实况窗开关关闭）、1003500005（实况窗权益未申请），并明确错误码 1003500004 的处理步骤为「在设置 > 应用和元服务中，选择应用开启实况窗开关」。
  来源：https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-error-code
  二手来源（标注「二手」，且与官方文档不一致）：有二手帖子称「HarmonyOS NEXT 中实况窗需要申请 ohos.permission.SYSTEM_FLOAT_WINDOW 权限」，https://bbs.itying.com/topic/68c324232cb460013cc1590c 。该说法未在华为官方文档的实况窗页面（liveview-introduction / liveview-preparations / liveview-base / liveview-error-code / liveview-liveviewmanager）中出现，本次调研判定为不可采信，建议以官方错误码 1003500004 的开关校验为准。
- AndroidManifest 声明：ArkTS 侧不适用。官方「基本准备工作」页只要求先完成「应用开发准备」，未列实况窗专用 manifest/module.json5 权限声明。
  来源：https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-base
- 服务类型：系统服务能力（Live View 服务）+ 华为推送服务 Push Kit（云端创建/更新/结束）。官方推荐「使用 Push Kit 实时更新实况窗状态」，因为本地更新依赖应用进程存活。
  来源：https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-update-by-push
- 三方开发框架：官方 FAQ 给出 React Native（https://atomgit.com/org/CPF-rn）、Flutter（https://atomgit.com/CPF-Flutter）、Uni-app（https://uniapp.dcloud.net.cn/tutorial/harmony/intro.html）的接入指引。
  来源：https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-faq-2

### 线路 B（Android 兼容侧，HMS Core 推送）

- 依赖库 / SDK：无独立实况窗 SDK，走「原生发送通知的流程」，在 Notification 的 extras 里附加实况窗扩展参数；在支持实况窗的设备上系统按实况窗样式显示。
  来源：https://developer.huawei.com/consumer/cn/doc/HMSCore-Guides/android-live-guide-local-0000001613377893（简介）
- 核心 API：NotificationManager / NotificationChannel / Notification.Builder().addExtras(bundle) / notify(id, notification) / cancel(id)。
- 服务类型：系统通知 + 华为推送服务（远程更新和结束）。

## 2.3 通知内容协议（真实字段名）

### 线路 A（ArkTS 结构体）

LiveView 对象关键字段：id（开发者生成的实况窗 ID）、event（场景枚举）、sequence、isMute、lifeCycleMode、shareUrl、timer、liveViewData。
liveViewData 结构（官方原文的类结构图）：

    LiveView
      ├── id
      ├── event
      ├── 其他参数......
      └── LiveViewData
            ├── PrimaryData 卡片形态内容
            │     ├── LayoutData 扩展区数据
            │     │     ├── ProgressLayout 进度可视化模板扩展区参数
            │     │     ├── FlightLayout 左右文本模板扩展区参数
            │     │     ├── ScoreLayout 赛事比分模板扩展区参数
            │     │     ├── PickupLayout 强调文本模板扩展区参数
            │     │     ├── NavigationLayout 导航模板扩展区参数
            │     │     └── CustomLayout 自定义模板扩展区参数
            │     └── ExtensionData 辅助区内容
            ├── CapsuleData 实况胶囊形态内容
            │     ├── TextCapsule 文本胶囊参数
            │     ├── TimerCapsule 计时器胶囊参数
            │     └── ProgressCapsule 进度胶囊参数
            └── ExternalData 外屏形态模板参数

来源：https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-liveviewmanager（关键 Class/Interface 介绍）

PrimaryData 关键字段（来自官方示例与错误码文档）：title、content（数组，元素含 text 与 textColor）、keepTime（结束后通知中心存档时长）、aliveTime（6.1.1(24) 起，实况窗最长存活时间）、clickAction（WantAgent）、extensionData、layoutData、liveViewLockScreenPicture、liveViewLockScreenAbilityName、backgroundType。
ExtensionType 枚举：EXTENSION_TYPE_DEFAULT / EXTENSION_TYPE_COMMON_TEXT / EXTENSION_TYPE_CAPSULE_TEXT / EXTENSION_TYPE_PIC / EXTENSION_TYPE_ICON / EXTENSION_TYPE_PROGRESS。
LayoutType 枚举：LAYOUT_TYPE_DEFAULT / LAYOUT_TYPE_PROGRESS / LAYOUT_TYPE_PICKUP / LAYOUT_TYPE_FLIGHT / LAYOUT_TYPE_SCORE / LAYOUT_TYPE_NAVIGATION / LAYOUT_TYPE_CUSTOM。
CapsuleType 枚举：CAPSULE_TYPE_TEXT / CAPSULE_TYPE_TIMER / CAPSULE_TYPE_PROGRESS。
ProgressLayout 字段：progress、color、backgroundColor、indicatorType（INDICATOR_TYPE_OVERLAY 等）、indicatorIcon、lineType、nodeIcons。
PickupLayout 字段：title、content、underlineColor、descPic。
FlightLayout 字段：style（含 FlightLayoutStyle、SpaceType）、firstTitle、firstContent、lastTitle、lastContent、spaceIcon、currentNavigationIcon。
CustomLayout 字段：abilityParameters（传给 LiveViewCardExtensionAbility.onRender 的参数）。
官方示例（取餐场景，真实字段）：

    let liveView: liveViewManager.LiveView = {
      id: 123,
      event: 'PICK_UP',
      sequence: 1,
      isMute: false,
      liveViewData: {
        primary: {
          title: '餐品已备好',
          content: [ { text: '请前往' }, { text: '一号窗口', textColor: '#FFFF0000' } ],
          keepTime: 1,
          clickAction: await buildWantAgent(),
          extensionData: { text: '待取餐', type: liveViewManager.ExtensionType.EXTENSION_TYPE_COMMON_TEXT },
          layoutData: { layoutType: liveViewManager.LayoutType.LAYOUT_TYPE_PICKUP, title: '取餐码', content: '72988', underlineColor: '#FFFF0000', descPic: 'coffee.jpg' }
        },
        capsule: { type: liveViewManager.CapsuleType.CAPSULE_TYPE_TEXT, status: 1, icon: 'coffee.jpg', title: '待取餐', content: '取餐码：72988', backgroundColor: '#FF308977' }
      }
    };

来源：https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-liveviewmanager 、https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-create-locally

云端等价字段（Push Kit REST）：payload.activityId（对应端侧 id）、payload.operation（0 创建 / 1 更新 / 2 结束 / 4 startLiveViewByTrigger / 6 stopLiveViewByTrigger）、payload.event、payload.status、payload.version（对应 sequence）、payload.mute（对应 isMute）、payload.activityData.notificationData.{type, contentTitle, contentText, clickAction, extend, richProgress, singleTextBlock, descPic, descPicUrl, style, firstTextBlock, lockScreen, backgroundType, keepTime, weather, keywords}、payload.activityData.capsuleData。
来源：https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-api-map
Push 创建实况窗请求示例（官方，节选）：

    POST "https://push-api.cloud.huawei.com/v3/[projectId]/messages:send"
    Content-Type: application/json
    Authorization: Bearer <JWT>
    push-type: 7
    {
      "payload": {
        "activityId": 1, "operation": 0, "event": "TAXI", "status": "DRIVER_ON_THE_WAY",
        "activityData": { "notificationData": { "type": 3, "contentTitle": "{{status}}",
          "contentText": [ ... ], "richProgress": { "nodeIcons": ["icon1.png"], "indicatorIcon": "taxi.png", "progress": 40, "indicatorType": 1, "color": "#FF317AF7", "bgColor": "#19000000" },
          "extend": { "type": 3, "image": "phone.png", "imageUrl": "https://..." } },
          "capsuleData": { "type": 1, "status": 1, "icon": "icon.png", "bgColor": "#FF317AF7", "remind": "EXPAND", "title": "接驾中", "content": "预计5分钟" } },
      },
      "target": { "token": [ "..." ] }
    }

来源：https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/push-update-liveview

场景 EVENT 取值（官方全集）：TAXI 出行打车、DELIVERY 即时配送、FLIGHT 航班、TRAIN 高铁/火车、QUEUE 排队、PICK_UP 取餐、SCORE 赛事比分、RENT 共享租赁、TIMER 计时、SUBSCRIBE_TIMER 订阅计时、WORKOUT 运动锻炼、NAVIGATION 导航、CHECK_IN 打卡、EXPRESS 快递、PROGRESS 进度类型、TRADE 金融交易。
来源：https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-introduction（支持的范围与场景）
其中「计时 TIMER」官方标注：仅限于工具类应用申请，且「仅支持通过端侧创建与更新」；不适用场景明确包含「课程提醒」。同页亦列 SUBSCRIBE_TIMER 的适用范围与不适用场景。

### 线路 B（Android Bundle Key，HMS Core）

外层 Key（放进 Bundle 后 Notification.Builder().addExtras(bundle)）：

- notification.live.operation：0 创建 / 1 更新 / 2 结束
- notification.live.event：TAXI、DELIVERY、FLIGHT、TRAIN、QUEUE、PICK_UP、SCORE、RENT、TIMER
- notification.live.type：模板类型（基础/进度/强调文本等）

内层 Bundle：

- feature（notification.live.feature.*）：progressType、nodeIcon（ArrayList<Parcelable>）、indicatorIcon、indicatorType、progress、progressColor、progressBgColor、extendType、extendImage、titleText、contentText
- capsule（notification.live.capsule*）：capsuleStatus、capsuleType、capsuleIcon、capsuleBgColor、capsuleTitle、capsuleContent、capsuleRemind
- 小折叠外屏：externalEnable、externalTitle、externalBody、externalType、externalBgImage、externalBgColor

官方示例（强调文本模板 type=4）：

    Bundle liveNotificationData = new Bundle();
    liveNotificationData.putInt("notification.live.operation", 0);
    liveNotificationData.putString("notification.live.event", "PICK_UP");
    liveNotificationData.putInt("notification.live.type", 4);
    Bundle feature = new Bundle();
    feature.putString("notification.live.feature.titleText", "取餐码");
    feature.putString("notification.live.feature.contentText", "750");
    liveNotificationData.putBundle("notification.live.feature", feature);

官方示例（带胶囊）：

    Bundle capsule = new Bundle();
    capsule.putInt("notification.live.capsuleStatus", 1);
    capsule.putInt("notification.live.capsuleType", 1);
    capsule.putParcelable("notification.live.capsuleIcon", Icon.createWithResource(context, R.drawable.xxx));
    capsule.putInt("notification.live.capsuleBgColor", Color.parseColor("#FFFF0000"));
    capsule.putString("notification.live.capsuleTitle", "胶囊标题");
    capsule.putString("notification.live.capsuleContent", "胶囊扩展内容");
    liveNotificationData.putBundle("notification.live.capsule", capsule);

来源：https://developer.huawei.com/consumer/cn/doc/HMSCore-Guides/android-live-guide-local-0000001613377893
参数完整清单见：https://developer.huawei.com/consumer/cn/doc/HMSCore-References/android-live-0000001613100205

## 2.4 系统级约束

线路 A（ArkTS / HarmonyOS NEXT）：

- 生命周期：单个实况窗最长不超过 8 小时，超过 8 小时系统认为结束。
- 空闲超时：超过 2 小时未更新 —— 状态栏胶囊与锁屏胶囊被隐藏，仅保留通知中心展示；超过 4 小时未更新 —— 系统判定结束并从所有展示入口清除。
- 流控：
  - 系统级（所有应用）：创建每秒最多 15 次，更新每秒最多 30 次；
  - 应用级（单个应用）：创建每秒最多 10 次，更新每秒最多 20 次；
  - 超频部分被丢弃不下发；端侧触发时对应错误码 1003500008「实况窗频度超过限制」，其可能原因描述与上表一致。
- Push Kit 更新频控：单个实况窗消息，出行打车（TAXI）与赛事比分（SCORE）每设备每 5 分钟最多 30 次、每小时最多 180 次；其余场景每设备每 5 分钟最多 10 次、每小时最多 60 次。
- Push Kit 创建限制：当前仅支持 FLIGHT、TAXI、TRAIN、DELIVERY、QUEUE、RENT、EXPRESS、CHECK_IN、TRADE、SUBSCRIBE_TIMER 场景通过 Push Kit 创建；其余场景必须端侧创建。更新与结束所有场景均支持。
- 数量与列表：通知中心滑动最多展示 24 条实况窗；点击胶囊弹出的实况窗列表不可滑动，只能展示 5 条。
- id 约束：同一 id 同一时刻只能创建一个实况窗；结束后 Live View Kit 可立即用该 id 再创建，Push Kit 在 12 小时内不允许用该 id 创建。实况窗被 notificationManager.cancel 或 cancelAll 清除后，Live View Kit 与 Push Kit 都无法再通过该 id 更新或结束。
- 地理围栏提醒限制（6.1.0(23) 起）：单设备单应用每日最多注册 5 次；注册有效期 48 小时，超时未触发自动删除；触发一次后系统自动删除该条件；WORKOUT 与 NAVIGATION 场景不支持；依赖 GNSS，仅室外开阔区域准确；实况窗总开关或「个性化提醒 > 基于地理位置的实况窗提醒」关闭时，系统自动删除已注册未触发的提醒。
- 图片资源：本地创建/更新场景，从 API 版本 26.0.0 开始传入 PixelMap 不大于 192KB；26.0.0 之前不大于 30KB，需做版本分支适配。Push 网络图片（26.0.0 起）：不大于 512KB，格式 jpg/jpeg/png/bmp/webp，仅支持 HTTPS。
- 主动撤卡：stopLiveView()；希望结束后不留存则把 PrimaryData.keepTime 置 0。26.0.0 起可在创建时设置 LiveView.lifeCycleMode = AUTO_STOP_WHEN_APP_TERMINATE，应用进程结束后自动关闭。
来源：https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-introduction（受限说明、Push 约束、地理围栏约束）、https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-faq-1 、liveview-faq-3 、liveview-faq-5 、https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-error-code 、https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-update-by-push

线路 B（Android / HMS Core，2024-04-23 文档）：

- 生命周期：单个实况窗通知最长不超过 8+1 小时；超过 8 小时系统认为结束并做存档处理，存档期最长 1 小时。
- 不刷新超时：最长不刷新时间 2 小时，超过 2 小时未更新即认为通知结束。
- 频控：通过 Push Kit 更新时，单个活动每 5 分钟最多刷新 10 次，每小时最多 60 次，超频丢弃。
- 撤卡：notification.live.operation = 2 结束（存档期字段生效，继续在通知中心留存）；NotificationManager.cancel 直接删除（存档期字段无效，不会继续留存）。
来源：https://developer.huawei.com/consumer/cn/doc/HMSCore-Guides/android-live-intro-0000001563098012（受限说明）、https://developer.huawei.com/consumer/cn/doc/HMSCore-Guides/android-live-guide-local-0000001613377893（更新和结束实况窗通知）、https://developer.huawei.com/consumer/cn/doc/HMSCore-Guides/android-live-design-0000001613258245

## 2.5 视觉规范

线路 A（官方 UX 设计指南 https://developer.huawei.com/consumer/cn/doc/design-guides/system-features-live-view-0000001955186861）：

- 形态：卡片态、胶囊态、锁屏沉浸态。
- 卡片结构：固定区（必选）、辅助区（可选）、扩展区（可选）。固定区含应用图标（系统直接展示应用桌面图标）、主文本、副文本。
- 辅助区尺寸：卡片态 44*44vp；锁屏态 36*36vp；文本大小推荐 8-12vp（默认 10vp）；拓展按钮位宽度 44vp*70vp（卡片态）/ 36vp~70vp（锁屏态），要求按钮在区域内右对齐，系统默认 10vp 字体（可选 8-12vp）、胶囊高度 22vp、纯色背景。
- 锁屏沉浸态自定义图标：需上传 36*36vp，「避免被圆形遮罩裁切」。
- 进度指示器：indicatorType = INDICATOR_TYPE_OVERLAY 时图标区域为 64*56vp，图片较宽会按比例缩放，需要应用自行调整图片大小；官方推荐圆形底板（用系统圆形底板需 svg 格式图标，自定义底板用 png），不推荐无底板样式。已完成进度默认系统高亮蓝色 #317AF7，未完成进度为系统黑白样式（10% 黑/白）。
- 胶囊：背景由系统提供默认黑色微渐变 / 纯黑透明度样式（#000000），根据场景自适应；图标由业务传入并由系统裁切形成同心圆效果，矢量图标需预留裁切安全边距，应用图标不预留额外边距；文本颜色固定 #FFFFFF，不支持定制。
- 胶囊文本长度：推荐主文本 1-3 个中文字符，副文本 1-6 个中文字符；超长时渐隐，支持跑马灯且仅支持胶囊右侧文本跑马灯，副文本超长跑马灯两遍。
- 跑马灯总规则（卡片）：不支持跑马灯，不支持换行，超过 1 行以 "…" 截断。
- 卡片固定区字数（官方精确值）：
  - 无辅助区：主文本最多 23 个英文字符或 16 个中文字，副文本最多 28 个英文字符或 19 个中文字；
  - 有辅助区：主文本最多 18 个英文字符或 13 个中文字，副文本最多 22 个英文字符或 15 个中文字。
- 卡片扩展区字数：强调文本模板 —— 辅助文本最多 20 个英文字符或 13 个中文字，强调文本最多 7 个英文字符或 9 个数字；左右文本模板 —— 主文本显示时间 HH:MM，辅助文本最多 8 个英文字符或 5 个中文字；赛事比分模板 —— 辅助文本最多 8 个英文字符或 5 个中文字。
- 锁屏模板字数：锁屏胶囊态无辅助区主文本 15 个英文字符/10 个中文字、辅助文本 18 个英文字符/12 个中文字，有辅助区主文本 11 个英文字符/8 个中文字、辅助文本 14 个英文字符/9 个中文字，支持跑马灯，超过后渐隐截断；锁屏沉浸态固定区无辅助区主文本 19 个英文字符/14 个中文字、副文本 26 个英文字符/17 个中文字，有辅助区主文本 15 个英文字符/11 个中文字、副文本 21 个英文字符/14 个中文字。
- 动态取色：官方没有「跟随壁纸/系统取色」的机制，而是要求「统一强调色值，推荐使用应用的品牌色」「默认将传入的品牌色生成圆形底板置于图标下方」「兼容深浅模式，图片与强调色需考虑深色和浅色两种模式」，并且「应用色」由应用侧上传。
- 左上角图标：除导航模板可通过 currentNavigationIcon 设置外，其他模板不支持修改实况窗左上角图标，默认展示应用 Logo。
- 图标单色/彩色：官方未强制单色；但胶囊图标由系统同心圆裁切，并默认用品牌色生成圆形底板。
来源：https://developer.huawei.com/consumer/cn/doc/design-guides/system-features-live-view-0000001955186861 、https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-faq-4 、https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-design-formula
官方设计模板资源（.sketch）：HarmonyOS+实况通知样式模板.zip，链接见 https://developer.huawei.com/consumer/cn/doc/design-guides/system-features-live-view-0000001955186861 页末「设计模版和资源」。

线路 B：另有 Android 侧设计规范页 https://developer.huawei.com/consumer/cn/doc/HMSCore-Guides/android-live-design-0000001613258245 ，内容为卡片模板/胶囊模板/小折叠外屏模板/文本超长显示规则与场景节点参考，资源包为「HarmonyOS 实况通知样式模板.zip」。具体 px 规格以该页与 UX 指南为准。

## 2.6 权限与开关

- 用户侧开关：设置 > 通知和状态栏 > 实况窗（可单独开关某应用）；应用实况窗场景首次触发时，卡片下方会询问「是否继续接收此应用的实况窗」，用户拒绝后该应用实况窗关闭，可在上述路径重新打开。当前仅部分应用支持关闭实况窗。
  来源：https://consumer.huawei.com/cn/support/content/zh-cn16019387/
- 应用侧：必须先通过 AGC 申请权益，未拿到权益时 startLiveView 会返回错误码 1003500005（实况窗权益未申请）；开关关闭时返回 1003500004。
  来源：https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-error-code
- 申请流程（线路 A，官方）：
  1. 登录 AppGallery Connect，选择「开发与服务」；
  2. 项目设置 > 开放能力管理，点击「实况窗服务」的「申请」；
  3. 填写申请原因（场景类型、场景描述、附件），提交；5 个工作日内出结果；
  4. 权益通过后，在「证书、APP ID 和 Profile」重新生成 Profile 并打包进应用包。
  来源：https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-rights
- 调测：通过 AGC「增长 > 推送服务 > 配置 > 实况窗-调测设备管理」按 Push Token 添加调测设备，每个应用上限 100 台；数据 24 小时内生效，调测权限有效期与 Push Token 有效期一致；调测名单中的设备不做本地构建实况窗权限的校验。
  来源：https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-joint-commission-test
- 正式权限：需先联调测试、确认方案与体验符合《实况窗设计规范》；在 AGC「增长 > 推送服务 > 配置」点击实况窗的「申请」后进入场景列表，「若开发者的应用月活数大于等于 1000 且为已上架应用，可点击各场景的申请按钮」；需提交场景描述（接入场景/展示时机/展示节点）、接入方案截图（≤3M）与自检清单；7 个工作日内出评审结果；整个流程约 15 个工作日。权益会被收回（违规或不符合设计规范时）。
  来源：https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-formal-authority
- 线路 B（Android 侧）申请差异：同样在 AGC 申请，但文档写明「实况窗目前为 Beta 测试阶段，优先邀请适配 HarmonyOS NEXT 且满足场景准入原则的应用接入」「后续邀请暂未开放」；月活 ≥1000 且已上架的应用可进正式权限申请页，单次最多申请 3 个场景、同个应用最多申请 8 个场景；月活 <1000 点击开通会「直接开通实况窗测试权限，调试权限有效期为 60 天」，到期前可申请延期 60 天。
  来源：https://developer.huawei.com/consumer/cn/doc/HMSCore-Guides/android-live-guide-right-0000001562619196
- 重要差异提示：Push Kit 指南明确「必须要开通实况窗权益才可以通过 Push Kit 推送实况窗消息，无法通过添加白名单设备的方式进行调试」；这与线路 B/联调页描述的「调测设备管理/白名单设备不做权限校验」是不同时期的策略，实际接入时以当前 AGC 后台入口和最新文档为准。
  来源：https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/push-update-liveview 、https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-joint-commission-test
- 违规处罚：实况窗需同时遵守《实况窗设计规范》与 Push Kit 通知内容原则，违规按《违规分类、违规行为及违规处罚标准》处理。
  来源：https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-introduction（违规处罚）

## 2.7 回退行为

- 未检索到官方文档说明「实况窗创建失败自动降级为普通通知」或「系统不支持实况窗时自动回落」的机制。
  尝试过的 URL：https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-introduction 、https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-faq 、-faq-1 至 -faq-5、https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-error-code 、https://developer.huawei.com/consumer/cn/doc/HMSCore-Guides/android-live-intro-0000001563098012
- 官方提供的是「前置检测 + 错误码判定」的组合降级路径：
  1. 调 startLiveView 前先调 isLiveViewEnabled() 判断开关；
  2. 捕获错误码 1003500004（开关关闭）、1003500005（权益未申请）、1003500008（频度超限）等，业务自行改发普通通知/本地提醒；
  3. 老系统上有版本兼容问题（例如 PixelMap 图片大小限制，26.0.0 前后阈值不同），官方要求在代码里做系统版本分支保护。
- 官方另指出：本地实况窗的创建依赖应用进程运行，创建后支持本地更新与 Push Kit 更新两种方式；因此「进程被杀」是常见回退触发点。若在 onDestroy 里没来得及 stopLiveView，实况窗不会消失（官方 FAQ 原话）。

## 2.8 已知坑与实测注意点

1. 环境准入极严：场景准入原则为「用户非常关注、需要反复查看或快捷操作」「有明确开始和结束、总时长较短最长不超过 8 小时」「用户有明确预期、通常为用户主动行为触发」「不可用于营销、广告」；官方还点名了一批「不适用场景案例」（如快递物流运输进度不算即时配送、课程提醒不算计时、模拟导航/游戏导航不算导航、未接入地理围栏的快递推送不算快递）。来源：liveview-introduction。
2. 场景白名单制：即使 API 调通，未开通对应 event 的权益仍会 1003500005；且「请确认 event 字段与开通权益的场景匹配」。来源：liveview-error-code。
3. TIMER（计时）场景只支持端侧创建与更新，不适用于课程提醒；SUBSCRIBE_TIMER 要求用户主动订阅，且「任何应用的赛事订阅开关不得默认选、自动订阅、批量弹窗」。来源：liveview-introduction。
4. 频控有双轨：端侧走每秒级流控，云侧走 5 分钟/小时级流控，两者都丢弃超频消息且不报错（端侧会返回 1003500008），高频更新场景（如秒级进度）在华为侧并不现实。
5. 超过 2 小时不更新会隐藏胶囊、超过 4 小时直接结束，与 vivo 的「2 小时清除」不同（vivo 是直接清除，华为是分级隐藏/清除）。来源：liveview-introduction、doc/895。
6. 图片体积：本地 PixelMap 在 26.0.0 前后阈值分别是 192KB 与 30KB，必须在旧系统上也做分支，否则旧版本上实况窗不显示图片；官方给了兼容性保护的 FAQ 链接。来源：liveview-introduction。
7. 扩展区自定义（CustomLayout / LiveViewCardExtensionAbility）限制很硬：独立子进程、不能跨进程拉起其他 Ability、不允许访问网络、每次运行时长限制 80 毫秒内、不允许调用一批 API（含 Notification Kit 的 notificationManager、窗口、媒体、振动、上传下载、剪贴板、相机、NFC、Telephony 等）。来源：https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-card-ability 、https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-lock-screen-ability
8. 锁屏沉浸扩展同样不能跨进程拉起其他 Ability，且限制模块列表很长。来源：liveview-lock-screen-ability（附录）。
9. 系统会持久化实况窗数据以支持重启后恢复展示，实况窗结束时自动删除数据；因此内容设计需考虑重启场景。来源：https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-design-formula
10. 进程异常终止时无法在 onDestroy 里 stopLiveView，实况窗不会自动消失；26.0.0 起可用 lifeCycleMode = AUTO_STOP_WHEN_APP_TERMINATE 规避。来源：liveview-faq-3。
11. 同一 id 复用有 12 小时静默期（Push Kit 侧）；被 notificationManager.cancel 清除后该 id 再也无法更新/结束。来源：liveview-faq-3、liveview-faq-5。
12. 实况窗列表容量：通知中心最多 24 条，胶囊弹出的列表仅 5 条且不可滑动。来源：liveview-faq-5。
13. 官方 FAQ 明确：INDICATOR_TYPE_OVERLAY 时图标区域 64*56vp、宽图按比例缩放，需要应用自己改图片；除导航模板外，左上角图标不能改（默认应用 Logo）。来源：liveview-faq-4。

---

# 三、两家在 HarmonyOS NEXT（纯血鸿蒙）与 Android 兼容版上的差异

## 3.1 华为

| 维度 | HarmonyOS NEXT / HarmonyOS 5+（原生 ArkTS） | Android 兼容版 / HarmonyOS 4.x（HMS Core 推送线路） |
| --- | --- | --- |
| 官方能力名 | Live View Kit（实况窗服务） | 实况窗通知（HMS Core 推送服务内的实况窗） |
| 接入语言与形态 | ArkTS，Stage 模型，import { liveViewManager } from '@kit.LiveViewKit' | Java/Kotlin，标准 Notification + Bundle 扩展参数 notification.live.* |
| 主要 API | isLiveViewEnabled / startLiveView / updateLiveView / stopLiveView / getActiveLiveView / startLiveViewByTrigger / stopLiveViewByTrigger，以及两个 ExtensionAbility | NotificationManager.notify / cancel，Notification.Builder().addExtras(bundle) |
| 官方文档明确支持的系统版本 | 「实况窗支持 HarmonyOS 5 及以上的操作系统版本」「仅支持 Phone 和 Tablet」「仅支持中国境内」 | 「实况窗支持 HarmonyOS 4.0 及以上的所有机型」，小折叠外屏展示态当前仅支持 HUAWEI Pocket 2 |
| 文档时间 | 更新时间 2026-08-29 / 2026-09-09 / 2026-10-10 等（活跃维护） | 更新时间 2024-04-23（Beta 阶段文档，长期未更新） |
| 开放程度 | 场景准入 + AGC 权益申请（5 工作日）+ 调测设备管理（Push Token，上限 100 台）+ 正式权限评审（要求 MAU ≥ 1000 且已上架，约 15 个工作日） | 「目前为 Beta 测试阶段，优先邀请适配 HarmonyOS NEXT 且满足场景准入原则的应用接入，后续邀请暂未开放」；MAU <1000 只能拿到 60 天测试权限 |
| 生命周期与超时 | 8 小时上限；2 小时不更新隐藏胶囊、4 小时不更新清除 | 8+1 小时（存档最长 1 小时）；2 小时不刷新即认为结束 |
| 频控 | 端侧系统级 15/s 创建、30/s 更新；应用级 10/s 创建、20/s 更新；Push 侧 TAXI/SCORE 5 分钟 30 次、其余 5 分钟 10 次 | 仅记录 Push 侧：每 5 分钟最多 10 次、每小时最多 60 次 |
| 云端创建 | Push Kit 支持部分场景创建 | 文档只写了「通过 Push 更新实况窗通知」的远程刷新 |
| 撤卡 | stopLiveView；keepTime=0 不留存；notificationManager.cancel 后该 id 不可复用 | operation=2 结束（存档生效）；cancel 直接删除（存档无效） |
| 官方支持列表的实证 | consumer 页「实况窗支持的应用和场景」适用版本列为 HarmonyOS 5.0 / 5.1 / 6.0 / 6.1 / 7.0，列出美团、淘宝闪购、12306、滴滴、高德、菜鸟、大麦等 | consumer 页「【HarmonyOS 4 升级变化】」适用版本列为 HarmonyOS 4.0 / 4.2 / 4.3，列出美团、淘宝闪购(饿了么)、盒马、航旅纵横、支付宝、Petal出行(元服务) 等，并给出各自上线时间与应用版本 |

来源：https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-introduction 、https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-liveviewmanager 、https://developer.huawei.com/consumer/cn/doc/HMSCore-Guides/android-live-intro-0000001563098012 、https://developer.huawei.com/consumer/cn/doc/HMSCore-Guides/android-live-guide-local-0000001613377893 、https://consumer.huawei.com/cn/support/content/zh-cn16019387/ 、https://consumer.huawei.com/cn/support/content/zh-cn15978591/

关于「HarmonyOS NEXT 不兼容 Android APK」：
- 未检索到华为开发者文档中心的一句明确官方声明页。
  尝试过的 URL：https://developer.huawei.com/consumer/cn/develop/ 、https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-introduction 、https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-liveviewmanager
- 官方开发者论坛英文帖中有明确表述：「HarmonyOS NEXT is the next-generation OS from Huawei, built entirely on OpenHarmony with no Android compatibility. It supports only native ...」，https://forums.developer.huawei.com/forumPortal/en/topic/0201200816318476038 （华为官方开发者论坛，非文档中心正文，作为半官方来源）。
- 二手来源（标注「二手」）：https://zhuanlan.zhihu.com/p/2006822955129254456 、https://juejin.cn/post/7538662953773842474 、https://tech.ifeng.com/c/8S2yTHPteO0 、https://bbs.itying.com/topic/67fa4597687c4e0048a9595f 。
- 对课表 App 的直接含义：com.zhijian.schedule 这类 Android APK 在 HarmonyOS NEXT 机型上无法安装运行，因此华为侧的实况窗适配在 NEXT 上走的是「另做一个 ArkTS 原生版 + Live View Kit」，在 HarmonyOS 4.x/兼容版上走的是「同一份 Android 代码 + notification.live.* 扩展参数」。本结论基于下述已核实事实：NEXT 侧接入文档全部是 ArkTS/Stage 模型与 @kit.LiveViewKit；Android 侧接入文档全部是 Java Notification 扩展参数；且 Android 侧文档自身写明优先邀请「适配 HarmonyOS NEXT」的应用接入。

## 3.2 vivo

| 维度 | 说明 |
| --- | --- |
| 是否涉及 HarmonyOS | 不涉及。vivo 原子通知/原子岛/小V建议均为 Android（OriginOS）体系；dev.vivo.com.cn 与 developers.vivo.com 的能力目录里没有 HarmonyOS 相关能力，接入文档全部基于 Android Notification 与 VPush。来源：https://dev.vivo.com.cn/documentCenter/doc/896 、https://developers.vivo.com/ |
| Android 版本差异 | 原子岛形态需要 OriginOS 5 及以上且机型支持；不支持原子岛的机型与上下折叠屏外屏，状态栏显示为「普通胶囊」样式，而不是「原子岛胶囊」样式。官方还规定「原子岛的数据和状态栏胶囊的数据都需要发」，因为 Flip 类机型外屏仍显示胶囊。来源：https://dev.vivo.com.cn/documentCenter/doc/895 、https://dev.vivo.com.cn/documentCenter/doc/896 |
| OriginOS 6.0 起的自定义通知变化 | 系统对自定义通知的渲染机制改为遵循原生 Android 规范：不要再自行绘制 APP icon（会与系统图标重复）、统一使用黑色字体、不要加背景色块、需适配深色模式与响应式宽度、TargetSDK<31 的完全自定义布局默认折叠；不符合规范的会被默认显示为收起状态。来源：https://dev.vivo.com.cn/documentCenter/doc/901 |
| 与鸿蒙生态的衔接 | 未检索到 vivo 官方文档提供鸿蒙侧原子通知接入方案。尝试过的 URL：https://dev.vivo.com.cn/documentCenter/doc/894 、https://dev.vivo.com.cn/documentCenter/doc/895 、https://dev.vivo.com.cn/documentCenter/doc/896 、https://developers.vivo.com/ |

---

# 四、对课表 App（com.zhijian.schedule）的落地结论

vivo 侧（现有 Android 代码可直接复用）
1. 原子通知只能在 Android 通知通道上做，通过 Bundle 塞 notification.superx.* 字段，不需要额外依赖库。
2. 需先发邮件到 oosyztz@vivo.com 申请准入（7-10 个工作日），并提交需求定义文档；应用须已上架 vivo 应用商店。
3. 课表场景在 vivo 官方场景枚举里没有对应项（MOVIE / HEALTH_REGISTER / TAXI / TAKEOUT / DELIEVERY / NAVIGATION / CAR_STATE / METTING）。官方「禁止准入内容」禁止营销广告；是否接受「课程/日程」类场景需以 vivo 原子通知团队评审结论为准，不能假定可以拿到权限。
4. 必须同时发原子岛与胶囊两份数据；务必处理 Flip 外屏。

华为侧
1. HarmonyOS NEXT（原生鸿蒙）必须用 ArkTS 重新实现一份，才能用 Live View Kit；Android APK 在 NEXT 上不可运行。
2. HarmonyOS 4.x / 兼容版走 Android Notification 的 notification.live.* 扩展参数，但没有官方文档说明课表场景可申请；官方「计时 TIMER」场景的不适用案例中明确列了「课程提醒」，SUBSCRIBE_TIMER 又必须用户主动订阅且赛事/售票限定，签到打卡 CHECK_IN 限上下班打卡。也就是说，课表类实时态在华为这两条线路上都没有明确可申请的场景，属于要么谈特殊场景权益、要么放弃的品类。
3. 若确实要接，最小可行路径是：先按设计规范出方案 -> AGC 申请实况窗服务权益 -> 调测设备管理加 Push Token 联调 -> 提交场景描述与截图申请正式权限（要求 MAU ≥1000 且已上架）。
4. 必须做的工程准备：isLiveViewEnabled() 前置判断 + 1003500005 / 1003500004 错误码分支 + 普通通知降级；App 进程被杀时的实况窗清理（onDestroy 里 stopLiveView + keepTime=0，或 26.0.0 起 lifeCycleMode = AUTO_STOP_WHEN_APP_TERMINATE）。

---

# 五、本次未检索到官方文档的条目汇总

1. HarmonyOS NEXT 与 Android 生态兼容性的官方文档中心声明页：未检索到官方文档。尝试过的 URL：https://developer.huawei.com/consumer/cn/develop/ 、https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-introduction 、https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-liveviewmanager 。现有最接近的一手来源是华为官方开发者论坛英文帖 https://forums.developer.huawei.com/forumPortal/en/topic/0201200816318476038 。
2. ohos.permission.SYSTEM_FLOAT_WINDOW 为实况窗必需权限：未检索到官方文档；仅见二手帖子 https://bbs.itying.com/topic/68c324232cb460013cc1590c 。尝试过的 URL：https://developer.huawei.com/consumer/cn/doc/harmonyos-guides/liveview-introduction 、liveview-preparations、liveview-base、liveview-rights、https://developer.huawei.com/consumer/cn/doc/harmonyos-references/liveview-error-code 、liveview-liveviewmanager 。
3. 华为实况窗「创建失败自动降级为普通通知」的官方机制说明：未检索到官方文档。尝试过的 URL：liveview-introduction、liveview-faq 及其 -1 至 -5 子页、liveview-error-code、HMSCore-Guides/android-live-intro-0000001563098012 。
4. 华为实况窗系统级动态取色（跟随壁纸取色）：未检索到官方文档；官方要求的是应用自传品牌色/强调色并适配深浅模式。尝试过的 URL：https://developer.huawei.com/consumer/cn/doc/design-guides/system-features-live-view-0000001955186861 、liveview-design-formula 。
5. vivo 原子通知各触点图标的像素级规格（正文数值）：未检索到官方文档（doc/895 只有规范示意图，正文无 px 文字），仅「自定义通知」页给了 24x24dp 的图标按钮规格。尝试过的 URL：https://dev.vivo.com.cn/documentCenter/doc/895 、doc/896、doc/577、doc/901 。
6. vivo 原子通知是否支持「课程/日程」类场景：未检索到官方文档；场景枚举与准入原则里均无此类目，需线下与 vivo 原子通知团队确认。尝试过的 URL：doc/894、doc/895、doc/896 。
