# 变更日志

「纸间课表」所有值得记录的变更都写在这里。

格式遵循 [Keep a Changelog 1.1.0](https://keepachangelog.com/zh-CN/1.1.0/)，版本号遵循 [语义化版本 SemVer](https://semver.org/lang/zh-CN/)。Android 的 `versionCode` 与 SemVer 的补丁位一一对应（见 `app/build.gradle.kts`）。

> **数据来源**：本文件在项目开发中途补齐，条目**按提交历史回溯整理**：版本切分以仓库里的 `v*` git tag 为准，发布日期取该 tag 的创建日期，条目文本取自对应的提交信息，不包含提交以外的推测内容。
>
> **归类规则**：带 Conventional 类型前缀的提交按前缀映射（`feat:` → Added、`fix:` → Fixed、`docs:` / `test:` / `chore:` / `style:` → Changed）；v1.0.9 之前的历史提交没有统一前缀，按正文动词就近归类；纯发版动作（"版本号提升至 …"）归 Changed。
>
> 每个版本段下的 *提交区间* 给出该版的起止 tag 与提交数，可用 `git log --oneline <起>..<止>` 复核。

## [Unreleased]

## [1.0.13] - 2026-10-27

*提交区间：v1.0.12..v1.0.13（3 个提交）— 代码规范化：包结构重整、超长文件拆分、静态检查与 CI 基建*

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
| 1.0.12 | 13 | v1.0.12（当前 versionName / versionCode，见 `app/build.gradle.kts`） |

发版流程见 [CONTRIBUTING.md](CONTRIBUTING.md#7-发版)。

[Unreleased]: https://github.com/Suife-yuanxing/zhijian-schedule/compare/v1.0.12...HEAD
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
