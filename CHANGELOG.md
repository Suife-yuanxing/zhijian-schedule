# 变更日志

「纸间课表」所有值得记录的变更都写在这里。

格式遵循 [Keep a Changelog 1.1.0](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本 SemVer](https://semver.org/lang/zh-CN/)。Android 的 `versionCode` 与 SemVer 的补丁位一一对应（见 `app/build.gradle.kts`）。

> **数据来源**：本文件在项目开发中途补齐，条目**按提交历史回溯整理**：版本切分以仓库里的 `v*` git tag 为准，发布日期取该 tag 的创建日期，条目文本取自对应的提交信息，不包含提交以外的推测内容。
>
> **归类规则**：带 Conventional 类型前缀的提交按前缀映射（`feat:` → Added、`fix:` → Fixed、`docs:` / `test:` / `chore:` / `style:` → Changed）；v1.0.9 之前的历史提交没有统一前缀，按正文动词就近归类；纯发版动作（"版本号提升至 …"）归 Changed。
>
> 每个版本段下的 *提交区间* 给出该版的起止 tag 与提交数，可用 `git log --oneline <起>..<止>` 复核。

## [Unreleased]

## [1.0.15] - 2026-11-01

*提交区间：v1.0.14..v1.0.15（40 个提交）— 水银镜面主题、根页切换重构、教务成绩抓取与五家新教务适配器*

### Added

- feat(theme): 液态玻璃换成**水银镜面**（`chrome`）——玻璃的辨识特征是「透 + 边缘折射」，金属是「不透 + 反射方向 + 各向异性拉丝高光」，两者物理模型互斥，所以这不是换色而是整套材质模型重写：`core/ui/glass/` 六个文件下线、`core/ui/metal/` 十一个文件接上。光路从球冠法线改成「平板 + 倒角」（平面区恒定朝前保住本色，边沿 0–7px 外翻成镜面斜面，最外 2.2px 一道刃光，贴边压暗 42% 做接触阴影），环境加一盏条形柔光箱给金属方向感，浮岛走屏幕空间反射真实映出下方滚动的页面，页面底是极暗房间 + 两团随重力漂移的柔光
- test(theme): 新增 `MetalThemeTest`（5 例）；`IslandContrastTest` 跟着金属浮岛重算对比度（选中珠压到 `chromeOverride = 0.55` + 深墨字，未选中字提亮）——两处都是真机上抓到「字被珠盖住 / 暗金属上看不清」之后定下的硬指标
- docs(spec): 新增 `docs/specs/2026-10-29-metal-glass-theme.md`——水银镜面的光路模型、合成链路公式，以及三个真机确认过的坑：AGSL 里写中文注释会让 `RuntimeShader` **静默**构造失败（元件直接走降档分支，看起来像没生效）、拉丝频率必须按像素算（`sin(q.y * 420.0)` 在高分屏混叠成布纹）、选中珠太亮会吃掉文字
- feat(ui): 新增 `RippleLoader`（四层同心圆共用 2s 周期、相位差 0.1 的重叠涟漪）与 `GradientCheckMark`（勾是逐段画上去的，描边按底亮度反解），替换六处 `CircularProgressIndicator`——都是「用户会盯着等好几秒」的位置（教务解析、全屏阻塞遮罩、成绩首屏、二维码生成、备份导出、快速导入完成）；系统转圈匀速无节拍，几秒后读作卡住
- test(ui): 新增 `GradientCheckMarkTest`（5 例）钉住两次真机事故：未选中态的「白色 24%」半透明底压在纸色页面上与底色几乎同色（截图里整枚标记等于不存在），以及白勾压在提亮后的强调色上只有约 2.2:1（远看是一枚空圆）
- chore(build): debug 构建加 `applicationIdSuffix = ".debug"`——debug 与正式版签名不同、同 applicationId 装不上也覆盖不了，加后缀后两包并排（`com.zhijian.schedule.debug` / `com.zhijian.schedule`），调试不再要在「装 debug」和「保住正式版数据」之间二选一
- refactor(schedule): 撤掉课表顶栏的齿轮入口，设置只从「我的」进——两个入口意味着两处都要维护「切到我的 tab 再 push」的跳转，而这条跳转正是根页入场重播的触发点（先切 tab 再导航，目的地会被当成一次重新进入）
- feat(jw): 抓取后提示核对课表信息（学期为空 / 换了学校或校区）——`TimetableInfoAudit` 纯逻辑 + `ImportPreview.infoHint` 透到预览弹层；课表信息仍不跟随抓取改写，只提醒不动手
- test(jw): 新增 `TimetableInfoAuditTest`（6 例）与 `ImportPreviewInfoHintTest`（3 例）
- feat(jw): 单列广西工程职业学院（`gxgc`）——智慧校园门户 `zhxy.gxgcedu.com` 的主机识别 + 校外端口拦截提示，解析复用 URP；校方 2022 年通知里的 `http://edu.gxgcedu.com/` 已 NXDOMAIN，条目写进 `SchoolCatalog` 内置表以免被 `tools/gen_school_catalog.py` 重新生成时覆盖
- test(jw): 新增 `GxgcSchoolProfileTest`（6 例）与 `GuangXiGcAdapterTest`（14 例）
- fix(jw): 广西工程职业学院门户证书过期导致无法登录——校方 Let's Encrypt 证书有效期只到 2026-10-28，过期后 WebView 报 `SSL_DATE_INVALID`、OkHttp 抛 `CertificateExpiredException`，学生连登录页都打不开。新增 `ExpiredCertTolerance`（纯逻辑）+ `ExpiredCertTrustManager` + `ToleratedTls`：只对 `zhxy.gxgcedu.com` 这一台主机、只对"证书过期"这一种失败、只在过期后 30 天内放行；主机名不匹配 / 自签名 / 签发者未知 / 过期太久一律照旧拦下。WebView 与 OkHttp 共用同一套判定，登录和抓取一起恢复；放行时在导入页给一句实话（"证书已过期 N 天（学校未续期），本次已放行"），学校续期后提示自动消失
- fix(jw): 教务导入页白屏——WebView 在量到尺寸之前就提交了第一次导航，Chromium 把视口高度锁成 0，此后任何重排都不再更新；依赖 100vh / 百分比高度的页面（广西工程门户、百度首页同样复现）整页塌成 0 高。新增 `loadWhenSized`（等布局量到尺寸再 loadUrl，同一 WebView 只保留最后一个待加载请求）+ `VIEWPORT_HEIGHT_REPAIR_JS` 兜底（仅在 100vh 量出来接近 0 时给 html 一个像素高度，量得正常则一个字节都不改）；`shouldLoadWhenSized` 抽成纯函数进单测
- test(jw): 新增 `JwWebViewViewportTest`（6 例）——尺寸未量到必须等、兜底脚本只在视口真的坏掉时动手、写回高度带 `!important`、只碰 html/body 不碰内容节点
- test(net): 新增 `ExpiredCertToleranceTest`（16 例）——三闸门逐个钉住：主机名单精确匹配、只认过期不认其它错误、30 天窗口边界、SAN 通配符只跨一层、WebView 与 OkHttp 两条入口判据一致
- fix(ui): 切根页不再「从右上方打开、刷新一遍」——三个根页原先各自一套入场闸门，今日与「我的」的语义是「本进程第一次进入时放行一次」，而「第一次进入」恰好就是第一次切过去，于是**每次冷启动后的第一次切 tab 必然播一次整套分段入场**（叠在右滑入的位移上，读作整页展开刷新），之后正常，表现成「偶发」。现在收成一个闸门（`RootEntrancePolicy` 纯逻辑 + `RootEntranceGate` 状态，一处持有三页共读），语义只有「冷启动首帧放行一次」，并用 `rememberSaveable` 持有以免 Activity 重建后重播
- fix(schedule): 切页不再覆盖用户手选的周次——旧实现把「定位到当前周」的副作用挂在课表元数据上，页面每次重新进入组合（切 tab、从编辑页返回、重建）都重跑一次，翻到第 10 周再切回来就变回第 9 周。新增 `WeekAnchorPolicy`：只有锚点（课表 id / 开学日期 / 总周数）变化才重新定位，锚点状态存在 ViewModel
- perf(schedule): 「现在」呼吸灯从逐帧无限动画改为定频采样（`PulseMath` + `rememberBreathingAlpha`，20Hz）——课表页静置 10 秒的实测产帧从 606 帧降到 192 帧；旧写法让这一页只要含「现在线」就恒定 ~61fps 重绘，切页时与整屏滑动抢同一条渲染管线
- test(ui): 新增 `RootEntrancePolicyTest`（7 例）、`WeekAnchorPolicyTest`（9 例）、`PulseMathTest`（8 例）——钉住「关闸后三页全否」「二级页永不播」「同锚点不重定位」「采样台阶 < 5% 且 20Hz」
- docs(spec): 新增 `docs/specs/2026-10-30-根页切换规范.md`——把根页切换的话题固化成硬约定（切 tab 只做位移 / 入场只播一次 / 定位只认锚点），含根因认定、真机实测对照、验证命令、单测清单、反模式条目与**已知遗留**（纵向滚动位置仍会回到顶部，本次三条路线均未通过真机复验，已全部撤回不留半成品）
- feat(jw): **成绩跟随课表抓取覆盖入库**——`JwAdapter.fetchGrades` 能力化（返回空 = 未适配，空结果/失败静默跳过，绝不覆盖旧成绩），`JwSyncManager` 在课表抓取成功后 best-effort 同步，成绩页手动刷新同链路；七家既有适配器各自实现（强智 HTML 表、正方新 `xs_kccj_query.do` 嵌套解析、青果/URP/树维候选轮询 + 通用表头解析、gxgc CIP 三步一循环 `CipGradeFetcher`——CSRF 强制、403 视为令牌轮换重试一次）。新增 `JwUrlGuard` 安全闸门：新抓取路径拼 URL 前只放行 http(s) 公网主机，拒绝 localhost / 环回 / 私有 / 保留地址
- feat(jw): **新增五家教务适配器**，教务类型 7 → 12，学校目录 1602 → 1935 条（`gen_school_catalog.py` 扩协议映射，上游社区目录零丢失）：超星 `*.jw.chaoxing.com` 租户域（encodeId 会话 + `sdpkkbList` 整学期 JSON + jqGrid 成绩）；金智 `/eams/`（与树维靠 `courseTableStudent` 端点指纹区分，新增 `EamsTaskParser` 解析页内嵌 TaskActivity JS——unitCount / 周次位图下标 0 占位 / index=D×unitCount+P 0 基 / 停课标记跳过）；乘方新旧两代（`week.page` 学期下拉 + businessHours 作息 + `getCalendarWeekDatas`，旧版内嵌 `var kbxx` 且 JS 反斜杠房间名先补无效转义再解析）；正方旧版（`default2.aspx` 指纹 + `xh=` 会话参数，全链路 GBK 解码，`fetchViaCandidates` 增加 charset 参数）；联奕（`lyuapServer` CAS 指纹，React 课表页仅 SSR 可解析、SPA 壳给明确「改用截图导入」提示）。端点证据来自 GitHub 爬虫仓库与拾光课程表逐校适配脚本的多源交叉验证
- test(jw): 新增 34 例——五家适配器解析 fixture（连堂合并 / 教室分周 / TaskActivity 逗号课名 / GBK 成绩表全链路 MockWebServer）、`JwUrlGuardTest`（5 例）、`JwAssetCacheTest`（6 例），全量 708 例全绿

### Fixed

- fix(jw): 输入法收起时底部「每日自动同步」整条抬起挡住 WebView 页面与输入框——footer 去掉 `imePadding` 改随导航栏内边距
- fix(jw): gxgc 的 `getTimetableRuleTime` 恒返回空却每次白发一个请求——空过一次即记忆化跳过，教务哪天真配了钟点重启一次 App 即可拿到

### Changed

- perf(jw): 教务导入提速——识别到课表页即后台预取（点「抓取」直接出预览），WebView 静态资源磁盘缓存（`JwAssetCache`：js/css/图片按 7 天 TTL 落盘，元信息四行 + 正文长度校验防半截文件，8MB 目标 / 16MB 上限自动清理，登录页路径一律不缓存），教务二次打开明显变快
- feat(jw): 教务导入优先落统一认证入口（`ssoEntry`）——本地登录页常被 WAF 拦或甩校内地址；统一认证打不开时给「改用本地登录」的双向回退按钮

- chore(repo): 仓库规范化——补 `.gitattributes`（只钉死 `gradlew`/`gradlew.bat` 的换行符 + 二进制标记，不做全仓 renormalize）；`.editorconfig` 补 Room schema 生成物的尾换行豁免（生成器不写尾换行，之前规则与工具互相打架）
- docs: 把散在各处的陈旧统计按实测重算并写清统计口径（`.kt` 300 个 / 42924 行 / 超 120 列 32 行；提交 135 个、`git log --merges` 为空；截图 12 张），`CHANGELOG` 的 v1.0.13 / v1.0.14 段补回缺失的发布日期、提交区间与版本号表条目
- test(jw): 去掉 `JwScriptOutputTest.kt` 的 UTF-8 BOM（`.editorconfig` 声明的是 `charset = utf-8`，BOM 会让首个 token 带不可见前缀）
- chore(quality): 重新生成 `config/ktlint/baseline.xml`（3551 → 3383 条）——`c3c1488` 改过 `SettingsStore` / `TimetableImportService` 等文件后旧记录的行号整体漂移，`AppModule` / `JwImportWebView` 的既有违例也因本次编辑而错位；重新生成只是把同类违例的定位跟上新代码，没有新增豁免，新写的 `ExpiredCertTolerance` / `ExpiredCertTrustManager` / `ToleratedTls` 全部零违例、不在 baseline 里

## [1.0.14] - 2026-10-28

*提交区间：v1.0.13..v1.0.14（23 个提交）— 作息时间跟随课表、多厂商实时通知适配、底部胶囊果冻与深度阶梯*

### Added

- feat(jw): 接入青果 KINGOSOFT 教务（`/jwweb/`、`/qzhgjw/`）——WebView 登录后重放课表页，支持框架页下钻与登录态判定
- feat(jw): 接入 URP 综合教务——新 URP 走 `dateList` JSON（`classWeek` 位图 / `classSessions`+`continuingSession` 换算节次），老 URP 走 `showTimetable.do` HTML 表格；已停在课表页时直接复用当前页地址
- feat(jw): 接入树维经典 EAMS（`/eams/`、`courseTableForStd!courseTable.action`），与 eams5 三段式 JSON 明确区分
- feat(jw): 接入树维 eams5（`/eams5-student/`）——`print-data` 为主、`get-data` → `ws/schedule-table/datum` 兜底；只认「响应里带 HTML 课表表格」的形态，不猜 JSON 字段
- feat(jw): 新增 `HtmlTableScheduleParser` 通用「星期 × 节次」表格解析器：rowspan/colspan 展开、合并表头、纯数字节次行标签、周次/节次/教师/教室识别，青果 / URP 老版 / 树维 classic 共用
- feat(jw): 「选校自动填 URL」——内置 1144 条学校目录（`assets/jw/schools.tsv`，随构建打包），支持按校名 / 拼音缩写 / 拼音全拼 / 域名检索、首字母分组，并区分「已验证」与「未实测」两类条目
- feat(jw): 抓取指引按厂商定制（`JwAdapter.steps`）——引导条第二段与失败提示改由适配器提供，强智「学生课表」、URP「个人管理 → 本学期课表」、eams5「我的课表」各有专属文案与注意事项，未识别时退回通用文案
- feat(tools): 新增 `tools/gen_school_catalog.py`，从学校目录快照重新生成 `schools.tsv`（可复现，不把快照本身放进仓库）
- test(jw): 新增 `QingGuoParseTest`（4 例）、`UrpParseTest`（6 例）、`ShuWeiClassicParseTest`（6 例）、`Eams5ParseTest`（6 例）、`SchoolCatalogTest`（10 例）、`AdapterStepsTest`（10 例）
- docs(testing): 新增真机实测记录 `docs/testing/2026-10-27-real-device-jw.md`——6 个适配器的识别链路在 OnePlus PKG110 / Android 16 上全部通过，强智（东莞职院）另跑通了「抓取 → 解析 → 预览」全链路，附 12 张截图（`docs/testing/shots/jw-real-device/`）
- feat(capsule): 抽出厂商无关的实时通知适配层——`LiveContent` 内容投影 + `LiveSurfaceRenderer` 渲染器接口 + `LiveSurfaceRenderers` 注册表，`CapsuleNotifier` 只保留「状态机 → 内容 → 原生骨架」，后续接 vivo / 华为 / OPPO 泛在服务不必再改核心文件
- feat(capsule): 接入小米超级岛（HyperOS OS3）——按官方 `param_v2` 协议挂 `miui.focus.param` 扩展参数，无需 SDK 与额外权限；处理了 `timeout`（分钟）与 `islandTimeout`（秒）单位不同、图标走 `miui.focus.pics` Bundle 不内联、整串 3072 字节超限降级三个易错点
- feat(capsule): 新增 `LiveSurfaceSupport` 能力探测——把「能否请求提升」（API 36 即可）与「能否真正被提升」（需 Android 16 QPR1 / API 36.1）拆成两个判断，避免 API 36.0 用户把「胶囊不出现」误当 bug
- test(capsule): 新增 `HyperOsIslandParamTest`（6 例）——校验超级岛参数根节点必填字段、岛超时上下限夹取、3072 字节上限、引号转义、文案裁剪与颜色格式
- feat(ui): 底部浮岛选中胶囊的果冻改成**移动途中先拉长、然后回收**——形变由位移进度驱动（`islandJellyWave`：前半程 `sin²` 瓣拉长 +16%、中点归位、后半程反向瓣回收 −8.8%），抵达那一刻形状已归位；不再有「滑过去 → 站定 → 原地蹦两下」的第二段时间线
- feat(ui): 果冻几何支持**带符号**拉伸——回收相能把胶囊压到比静止更窄（88→80.3dp）同时变高（48→50.9dp），体积守恒由同一个 `squash` 系数在宽度与高度两轴反向实现；此前拉伸量被夹在 0..1，结构上只能变宽
- refactor(ui): 行程与形变收进 `rememberJellyTravel`——进度由 `slide` 实时值在 `from..to` 之间反算，方向由 `sign(to−from)` 给出（不读 velocity，停稳时符号会丢），结构上只可能有一条时间线
- refactor(ui): 浮岛指示器拆出 `IslandIndicatorSkin`（玻璃水滴 / 纸面渐变胶囊两种外观互斥）与 `IslandBarMetrics`（逐帧不变的量测），`IslandIndicator` 与 `islandIndicatorRect` 的参数回到「只收逐帧变动的那几个」
- test(ui): 新增 `IslandJellyWaveTest`（9 例）——钉住两端为 0、接缝斜率连续、拉长在前回收在后、两峰都在行程内、拉得比收得重、带符号几何真的能窄过静止、方向镜像、边界不越舱
- test(ui): `IslandIndicatorLayoutTest` 扩到 12 例——补齐「行程四分之一处最宽、四分之三处最窄、抵达即归位」「方向决定哪条边多探」「越界 slide 不超演」「无跨度行程渲染为原形」四条端到端不变量
- docs(research): 新增 `docs/research/2026-10-28-jelly-capsule-motion.md`——六条外部规范、波形推导的逐值表，以及**真机逐帧实测**（一加 PKG110 / Android 16：宽度 312→357→282→309，峰谷都落在位移途中，实测 +15.5%/−8.7% vs 推导 +16%/−8.8%）
- docs(spec): 新增 `docs/specs/2026-10-27-multi-vendor-live-notification-design.md`——把流体云胶囊抽象成统一适配层，给出原生提升 / 通知扩展参数 / 厂商生态卡片三档路线的成本判断与逐厂商接入契约
- docs(research): 新增四份实时通知厂商文档证据档——Android 16 与 Apple 原生、OPPO / 小米 / 荣耀、vivo / 华为分册，按【官网原文】/【二手】/【未取到】分级并逐条附 URL
- feat(jw): **作息时间跟随导入的课表，不再硬编码**——设置页原本写死 12 节（08:20 起、每节 45 分钟），换学校换学期都不变；现在抓取教务时会一并读出课表页的节次时间并写入，课程用到的节次也跟着补齐。手动抓取只在用户点「确认导入」时落库，取消则完全不变；自动同步随课表一起生效。预览页顶部会提示「作息时间将同步更新（第 N 节 HH:mm 起，共 M 节）」
- feat(jw): 作息解析覆盖 6 个后端的页面形态——强智 jsxsd 读表头行首的大节时段、正方新版与青果 / 树维 classic / URP 读「星期 × 节次」表格的时间列（含 `08:20<br>09:55` 这种分行写法）、树维 eams5 在 `print-data` 与 `get-data` 兜底两路都取；行首写「第一大节 08:20-09:55」与纯节次数字两种标签都能认，出现时间倒退则整批丢弃、退回本地值
- feat(jw): 截图 / 文本 / 脚本导入也带作息——识别出的时段文字走同一条管线，脚本解析结果新增可选字段 `sectionTimes`；时段头「上午 08:20 - 11:45」按表头丢弃，大节再切成 4 小节
- refactor(jw): 出厂的 12 节时间表从 `ScheduleRepository.defaultSections()` 删除，改为 `SectionTimes.defaults()` 由 6 个大节区间派生，全仓库只剩一处时间来源
- test(jw): 新增 `SectionTimesTest`、`SectionTimesLinesTest`、`SectionTimePlanTest`、`QiangZhiFetchTest`、`QingGuoFetchTest`、`OcrSectionTimesTest`、`JwScriptSectionTimesTest`、`ImportPreviewSectionHintTest`、`ScreenshotSectionTimesAcceptanceTest`、`ScreenshotToSettingsEndToEndTest`——含按真实课表截图与设置页截图逐行比对的端到端验收

### Changed

- docs(research): 四份实时通知证据档就地勘误——把 10-27 会话期「抓不到」的工具状态与「页面拿不到」的页面事实分开记录，主档 §9 改为逐页正文可取状态总表（13 条中 9 条确认可取、3 条确认不可取、1 条属工具边界）；更正三处会误导实现的错误：`Settings.ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS` 实为 `ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS`（android-36 常量表双向确认）、`MetricStyle` 属 API 37 而非与 `ProgressStyle` 同期、华为实况窗正文实际在 `liveview-introduction` 而非 `liveview-scenes`；被更正的原文一律用删除线留痕
- feat(jw): 教务类型提示与未识别文案改为按 `JwAdapterRegistry` 动态生成，不再硬编码厂商名单
- refactor(jw): 协议识别打分集中到 `JwMatchScore` 常量，适配器不再散落魔法数字
- refactor(jw): 学校目录拆出 `SchoolCatalogAssets.kt`——assets 读取走 `ensureLoaded` 扩展，`SchoolCatalog` 保持纯 JVM，单测直接喂目录文本，符合「纯逻辑文件不 import android.*」的项目约定
- fix(jw): 强智 / 正方新版的学校目录兜底改为「仅当目录标注的协议与自身一致」才命中，避免 URP / 树维 / 青果学校被误判成强智
- fix(jw): 抓取优先使用 WebView 当前停留的课表页（同 host 时），URP / 树维 带学期与类型参数的页面不再因地址栏只填基路径而取不到课表
- fix(jw): 「用脚本导入」弹层快速上甩时不再反复回弹——`NestedScrollConnection` 就地消费向上方向的剩余拖动与甩动速度，弹层本体收不到增量即无从过顶回弹；列表内部滚动与下拉关闭手势均保留（Material3 已知嵌套滚动缺陷的针对性修复）
- chore(quality): 重新生成 `config/ktlint/baseline.xml`（既有文件行号随本次改动漂移）

### Fixed

- fix(capsule): 用户划掉的实时卡片不再自动重发——原实现每 30s 检测 `activeNotifications`，卡片不在就无条件重建，无法区分「系统 `timeoutAfter` 正常退场」与「用户手动划掉」；官方明确要求不得重新发布用户已关闭的动态，否则可能被撤销整个应用的通知权限。改用 `setDeleteIntent` 区分两者，并新增 `CapsuleDismissTracker` 按场次记账，同一节课内不再重发、离开实时态自动清账
- fix(capsule): 实时卡片移除遗留的 `setColor` 调用——它并非失格项（真正的一票否决是 `setColorized`），但与「提升通知不着色」的语义冲突，课程色统一只走进度段 `Segment.setColor`；注释中写明两者区别防止后人误加 `setColorized`

## [1.0.13] - 2026-10-26

*提交区间：v1.0.12..v1.0.13（4 个提交）— 代码规范化：包结构重整、超长文件拆分、静态检查与 CI 基建*

### Added

- feat(ui): 今日页课程卡与课表色块共用色调，并让倾动透视真正生效
- chore(quality): 接入 ktlint 14.2.0 + detekt 1.23.8 与仓库根 `.editorconfig`（存量违例冻结在 `config/*/baseline.xml`，增量违规即失败）
- ci: 新增 GitHub Actions 工作流（JDK 21 + Gradle 缓存，跑 `assembleDebug testDebugUnitTest ktlintCheck detekt`）
- docs: 补 `docs/README.md` 索引、`CHANGELOG.md`（Keep a Changelog 1.1.0）、`CONTRIBUTING.md`、`docs/adr/` 四篇架构决策记录

### Changed

- refactor: 包结构规范化——`alarm`/`calendar`/`capsule`/`sync`/`update`/`widget` 六个系统集成包迁入 `core/`（`notifications`/`calendar`/`capsule`/`sync`/`update`/`widget`），`AndroidManifest.xml` 类名同步
- refactor: 测试目录镜像主源码包结构（42 个测试文件按被测类所在包归位）
- refactor: 拆分超长文件（`Reveal` 596→3 文件、`CardTilt` 547→4、`Motion` 492→4、`ApkDownloader` 1159→多文件、`ProfileScreen` 930→343、`SettingsScreen` 585→230、`AppRoot` 908→258、`TodayScreen` 860→203、`WeekGrid` 678→185、`JwImportScreen` 887→317、`OcrTimetableParser` 607→354 等）
- refactor: `ScheduleRepository` 470 行拆为门面 + `TimetableImportService` + `CourseMapping`（导入流水线与映射逻辑分离）
- refactor: 合并重复实现——两套系统动画开关（`MotionPrefs`）合并为单一真源；重复的 `smoothstep`/`lerpFloat`/`lerpRect` 收进 `core/util/MathUtils`
- docs: `docs/superpowers/{plans,specs}` 重整为 `docs/plans`、`docs/specs`，技术总纲移入 `docs/specs/`，全仓库引用同步更新

### Fixed

- fix(ui): 入口卡展开圆角在进度越过 1 时可能算出微小负值（`coerceAtMost` → `coerceIn(0f, 短边一半)`）
- fix(ui): `RevealMath` 里 `revealRadius` 的 KDoc 误挂在 `smoothstep` 上，文档归位
- fix(test): `ProfileEntryGeometryTest` 起手圆角期望未考虑短边夹紧、`IslandIndicatorLayoutTest` 前缘/后缘基准误用网格中心线，两处断言与生产行为对齐

## [1.0.12] - 2026-10-26

*提交区间：v1.0.11..v1.0.12（38 个提交）— 学业中心一期（考试 / 成绩）+ 教务脚本引擎*

### Added

- feat(ui): 开关统一换成速度驱动的果冻开关，并按 M3 补上手柄尺寸分档
- feat(exam): 考试表加 manuallyEdited 标记并迁移到 Room v3
- feat(exam): 强智考试安排 HTML 解析器（含 fixture 单测）
- feat(exam): 考试合并决策（手动项保护）与仓库层 CRUD
- feat(exam): 强智考试安排抓取（会话复用 + 静默恢复）
- feat(exam): 考前两档精确提醒（24h/1h，号段与课程隔离）
- feat(capsule): 考试当天映射伪课程，交卷倒计时复用提醒节点
- feat(grades): 最新学期绩点口径与按学期趋势卡（排除补考重修）
- feat(exam): 考试列表页与编辑（手动添加 / 教务导入 / 删除确认）
- feat(exam): 「我的」页考试入口（复用共享容器转场）
- feat(today): 今日页考试倒计时卡（14 天窗口）
- feat(script): 脚本 manifest 头部解析与校验
- feat(script): 脚本输出信封校验与 CourseDraft 归一化
- feat(script): 脚本本地安装登记 ScriptStore
- feat(script): 远程清单解析、sha256 与版本比较
- feat(script): 脚本仓库地址设置项
- feat(script): 脚本目录/下载器与 Hilt 装配
- feat(script): WebView 页面上下文脚本执行器
- feat(script): 教务导入页脚本路径状态与方法
- feat(script): 教务导入页脚本入口、选择器与脚本管理页
- feat(script): 内置强智/正方脚本范本（页面上下文版）
- feat(script): 脚本仓库种子、mock 强智课表页与契约文档
- feat(ui): 底栏切页整页等距滑动、级联入场只播一次；补齐考试/脚本页路由
- feat(script): 新增旧正方 / 金智 / 通用表格三份内置脚本

### Fixed

- fix(today): 无课日也显示考试倒计时卡
- fix(alarm): 删除考试后撤销其残留闹钟，Receiver 加存在性守卫
- fix(exam): 考试行支持长按删除，已结束考试也可删
- fix(script): 通用脚本（hosts 为空）被误判为不可运行
- fix(script): 桥永久注入 + 选择器改底部弹层

### Changed

- 设计文档：考试安排与成绩统计（学业中心一期）
- 实施计划：考试安排与成绩统计（学业中心一期）
- chore(db): 提交 Room v3 导出 schema
- test(mock-jw): 强智 jsxsd 考试安排页，打通考试导入 E2E
- docs(spec): 教务脚本引擎设计（页面上下文注入 + 最小契约 + 三件套分发）
- docs(plan): 教务脚本引擎 12 任务实施计划
- docs(mock-jw): 脚本导入 E2E 步骤
- 版本号提升至 v1.0.12（versionCode 13）准备发布构建
- test(mock-jw): 旧正方 / 金智 / 通用课表页与脚本 E2E 文档

## [1.0.11] - 2026-10-23

*提交区间：v1.0.10..v1.0.11（7 个提交）— 以卡片触感与转场动效为主*

### Added

- feat(ui): 我的课表卡片跟随触摸做 3D 透视倾动与径向流光
- feat(ui): 主题切换与加号进出改为从点击处圆形扩散
- feat(ui): 我的课表卡片改指腹阻尼跟随，并补齐业界对照的抬升/视差/背光
- feat(ui): 今日页「正在上课」大卡复刻「我的课表」卡的指腹交互

### Fixed

- fix(update): 修复点击下载时页面卡顿与取消/继续后进度不一致
- fix(ui): 修返回收回时背景课表的硬切闪烁，加号张页改用加速曲线

### Changed

- 版本号提升至 v1.0.11（versionCode 12）准备发布构建

## [1.0.10] - 2026-10-21

*提交区间：v1.0.9..v1.0.10（6 个提交）— 教务兼容性与触觉反馈收敛*

### Added

- feat(jw): 支持正方新版并修正强智适配器识别
- feat(ui): 课表课块与今日课程行点击时给一次轻震
- feat(ui): 重构「我的」页层级并修复二级页中途返回
- feat(jw): 教务本地登录 5xx 时提供统一认证兜底

### Changed

- style(ui): 收敛全局触觉反馈
- 版本号提升至 v1.0.10（versionCode 11）准备发布构建

## [1.0.9] - 2026-10-19

*提交区间：v1.0.8..v1.0.9（2 个提交）*

### Changed

- 今日/我的页视觉改版与统一按钮反馈，修复设置页数据回滚
- 版本号提升至 v1.0.9（versionCode 10）准备发布构建

## [1.0.8] - 2026-10-19

*提交区间：v1.0.7..v1.0.8（6 个提交）— 课表网格双风格与胶囊分段提醒*

### Added

- 课表网格双风格：编辑式 / 色块制 + 同色系课程块与字体缩放适配
- 流体云分段提醒：保留秒级倒计时与进度，上课中按课程总时长在剩余 1/3 和最后 5 分钟提示「距下课 N 分」，2 分钟后回落实时显示

### Fixed

- 修复安装包清理后无法重下，并重做检查更新窗口

### Changed

- 课程交互优化与连堂归并：详情面板全展开改版，编辑页按钮反馈与空表单默认值调整
- 更新 README.md
- 版本号提升至 v1.0.8（versionCode 9）准备发布构建

## [1.0.7] - 2026-10-17

*提交区间：v1.0.6..v1.0.7（2 个提交）*

### Fixed

- 修复胶囊状态切换与更新下载，打通日历权限授权

### Changed

- 版本号提升至 v1.0.7（versionCode 8）准备发布构建

## [1.0.6] - 2026-10-16

*提交区间：v1.0.4..v1.0.6（7 个提交；**v1.0.5 未发布**，打 tag 时跳号）*

### Added

- 教务会话后台静默恢复 + 同步失败通知直达导入页
- 课表变更自动重刷系统日历（内容指纹护栏 + 互斥锁）
- 二维码互传全套：扫码导入 / 生成分享码 / 相册选码 / 分享图片

### Fixed

- 流体云胶囊可靠性修复：切换丢失自愈心跳 + 倒计时结束系统级撤卡
- 应用内更新完善：下载失效根因修复、检查更新独立弹窗、启动自动检查

### Changed

- 补充设计文档：液态玻璃主题、应用内更新下载（含加速方案）
- 版本号提升至 v1.0.6（versionCode 7）准备发布构建

## [1.0.4] - 2026-10-16

*提交区间：v1.0.3..v1.0.4（1 个提交）*

### Fixed

- 修复取消下载时的真机闪退（HTTP/2 流重置异常逃逸）并提升版本至 v1.0.4

## [1.0.3] - 2026-10-16

*提交区间：v1.0.2..v1.0.3（26 个提交）— 应用内下载全链路 + 并发分片 + 镜像加速*

### Added

- 新增第 6 套主题「液态玻璃」：底部清水玻璃浮岛（零模糊实时折射）
- 新增更新下载的断点续传判定与元数据（纯逻辑 + 单测）
- 新增下载校验与展示的纯逻辑：SHA-256 比对、字节与剩余时间格式化
- 新增更新残留清理规则与通知节流（纯逻辑 + 单测）
- 新增 UpdateStore：更新安装包的目录布局、断点元数据与占用统计
- 新增 ApkDownloader：下载状态唯一来源与断点续传下载体
- 新增下载通知与 dataSync 前台服务（进度通知带取消动作、超时收尾）
- 接入 FileProvider 与「安装未知应用」引导，交付安装包给系统安装器
- 新增加载分片规划：固定 4MiB 切片与末片取短（纯逻辑 + 单测）
- 新增分片进度聚合、续传点判定与 .meta v1→v2 兼容解析（纯逻辑 + 单测）
- 新增分片响应的降级判据：200 退回单连接，500/IO 不降级（纯逻辑 + 单测）
- 新增下载源链候选构造：直连优先、无 digest 强制禁用镜像（纯逻辑 + 单测）
- 新增「镜像加速」设置项（默认关闭）

### Changed

- 流体云胶囊与今日页改进：进度倒着消耗、状态栏 chip 显示剩余分钟
- 更新检查补上安装包大小与官方摘要字段（校验与进度分母用）
- 为更新下载提供独立的 OkHttpClient（不限总时长、放宽读超时）
- 应用启动时清理已装版本的更新残留文件
- 设置页接线应用内下载：开始/取消/安装/清理缓存与状态观察
- 设置页更新卡片改为应用内下载并显示进度，顺手修掉硬编码的版本号
- UpdateStore 支持 .meta v2 兼容解析与不删元数据的分片清空
- 下载器改为 8 路并发分片 + 源链换源，v1 单连接路径保留为 Range 降级路径
- 下载通知带上来来源标签，直连时不显示
- 设置页接线镜像开关与无摘要时的强制禁用
- 设置页新增镜像加速开关与下载来源标签
- 版本号提升至 v1.0.3（versionCode 4）准备发布构建

### Fixed

- 修复下载中「清理下载缓存」的两个缺陷：入口隐藏 + 拦住确认框与下载并发

## [1.0.2] - 2026-10-15

*提交区间：v1.0.1..v1.0.2（3 个提交）*

### Fixed

- 教务导入修复：会话失效死路页（强智本地登录表单/裸 JSON）自动改跳统一认证，学校外壳页 Frame0 高度塌缩导致的主内容区空白补上高度，关于页版本号改动态读取

### Changed

- 流体云胶囊状态切换改精确闹钟驱动（进程冻结也准时；重启/应用更新后自动恢复）
- 版本号提升至 v1.0.2（versionCode 3）准备发布构建

## [1.0.1] - 2026-10-14

*提交区间：v1.0.0..v1.0.1（7 个提交）— 底栏与动效改版*

### Added

- release 构建支持正式签名
- 课程点击动效重设计、周选择面板、图标重构与流体云放行引导
- 底部导航图标果冻弹跳与课程方块级联入场动效

### Fixed

- 修复流体云剩余时间停更（倒计时改系统自绘）与「我的」页入口重构
- 修复周选择面板打开瞬间选中格上下被裁（入场透明层裁剪溢出绘制）
- 底栏改灵动岛式纸感浮岛、二级页隐藏过渡与周面板选中格错渲染修复

### Changed

- 版本号提升至 v1.0.1（versionCode 2）准备发布构建

## [1.0.0] - 2026-10-13

*首个发布版本（3 个提交，含初始提交）*

### Added

- 初始提交：纸间课表 Android 应用（MVP + 实时胶囊）
- 新增成绩查询（加权绩点）与节假日/调休标注

### Changed

- 检查更新指向实际发布仓库 Suife-yuanxing/zhijian-schedule

---

## 版本号约定

Android 侧 `versionName` 与 SemVer 对齐，`versionCode` 单调递增且与补丁号同步：

| versionName | versionCode | 发布 tag |
| --- | --- | --- |
| 1.0.0 | 1 | v1.0.0 |
| 1.0.1 | 2 | v1.0.1 |
| 1.0.2 | 3 | v1.0.2 |
| 1.0.3 | 4 | v1.0.3 |
| 1.0.4 | 5 | v1.0.4 |
| 1.0.6 | 7 | v1.0.6（v1.0.5 / versionCode 6 未发布） |
| 1.0.7 | 8 | v1.0.7 |
| 1.0.8 | 9 | v1.0.8 |
| 1.0.9 | 10 | v1.0.9 |
| 1.0.10 | 11 | v1.0.10 |
| 1.0.11 | 12 | v1.0.11 |
| 1.0.12 | 13 | v1.0.12 |
| 1.0.13 | 14 | v1.0.13 |
| 1.0.14 | 15 | v1.0.14 |
| 1.0.15 | 16 | v1.0.15（当前 versionName / versionCode，见 `app/build.gradle.kts`） |

发版流程见 [CONTRIBUTING.md](CONTRIBUTING.md#7-发版)。

[Unreleased]: https://github.com/Suife-yuanxing/zhijian-schedule/compare/v1.0.15...HEAD
[1.0.15]: https://github.com/Suife-yuanxing/zhijian-schedule/compare/v1.0.14...v1.0.15
[1.0.14]: https://github.com/Suife-yuanxing/zhijian-schedule/compare/v1.0.13...v1.0.14
[1.0.13]: https://github.com/Suife-yuanxing/zhijian-schedule/compare/v1.0.12...v1.0.13
[1.0.12]: https://github.com/Suife-yuanxing/zhijian-schedule/compare/v1.0.11...v1.0.12
[1.0.11]: https://github.com/Suife-yuanxing/zhijian-schedule/compare/v1.0.10...v1.0.11
[1.0.10]: https://github.com/Suife-yuanxing/zhijian-schedule/compare/v1.0.9...v1.0.10
[1.0.9]: https://github.com/Suife-yuanxing/zhijian-schedule/compare/v1.0.8...v1.0.9
[1.0.8]: https://github.com/Suife-yuanxing/zhijian-schedule/compare/v1.0.7...v1.0.8
[1.0.7]: https://github.com/Suife-yuanxing/zhijian-schedule/compare/v1.0.6...v1.0.7
[1.0.6]: https://github.com/Suife-yuanxing/zhijian-schedule/compare/v1.0.4...v1.0.6
[1.0.4]: https://github.com/Suife-yuanxing/zhijian-schedule/compare/v1.0.3...v1.0.4
[1.0.3]: https://github.com/Suife-yuanxing/zhijian-schedule/compare/v1.0.2...v1.0.3
[1.0.2]: https://github.com/Suife-yuanxing/zhijian-schedule/compare/v1.0.1...v1.0.2
[1.0.1]: https://github.com/Suife-yuanxing/zhijian-schedule/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/Suife-yuanxing/zhijian-schedule/releases/tag/v1.0.0
