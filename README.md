# 纸间课表 · Paper Timetable

指尖之间，一纸课表。

面向中国高校学生的**本地优先、无广告、可深度定制**课程表 Android 应用。

## 功能

- **周视图**：单双周 / 任意周次（位掩码）、上午下午分场、当前时间红线、周次横向翻页、周末开关
- **今日页**：正在上课进度、下一节倒计时、今日课程状态
- **多风格主题**：纸本暖白（默认）/ 动态取色 / 清爽极简 / 墨夜纯黑 / 多彩活泼，深色模式
- **课程管理**：增删改、节次区间（12 小节 / 6 大节两级模型）、周次快捷选择（全/单/双/清空）、自定义颜色
- **导入**：教务系统直连（内置强智 jsxsd / 正方新版 / 正方旧版 / 金智 ehall / 通用表格脚本，支持本地导入 .js 与远程脚本仓库热更新）、Excel(.xlsx) / CSV、JSON、粘贴口令/文本、二维码互传；统一预览（新增/变更/取消/冲突）后确认落库
- **教务自动同步**：每日 WorkManager 抓取，变更自动应用并通知；`manuallyEdited` 课程永不被覆盖
- **学业中心**：考试安排（手动添加 + 强智教务导入，合并时保护手动项）、考前 24h / 1h 两档精确提醒、今日页考试倒计时卡、成绩查询与按学期绩点趋势
- **提醒**：精确闹钟（AlarmManager setExactAndAllowWhileIdle）、重启恢复、通知「静默」操作
- **桌面小组件**：今日课程（4×2）、下一节（2×2），随主题色板同步
- **同步备份**：全量 JSON 导出 / 恢复；写入系统日历（ColorOS 私有库 `com.coloros.calendar` + 原生库兼容）
- **检查更新**：GitHub Releases 自分发（无需商店与备案）；应用内 8 路并发分片下载、断点续传、镜像加速兜底

## 构建

环境要求：JDK 17+（实测 JDK 21）、Android SDK（platform 36 / build-tools 36）。

```bash
# 1. 配置 SDK 路径（local.properties）
sdk.dir=D\:\\Android

# 2. 构建 Debug APK
./gradlew assembleDebug     # 产物：app/build/outputs/apk/debug/app-debug.apk

# 3. 安装到设备
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 说明：Gradle wrapper 使用腾讯镜像（mirrors.cloud.tencent.com）以保证国内下载速度；
> 项目路径含中文时 AGP 需 `android.overridePathCheck=true`（已在 gradle.properties 配置，本项目不使用 NDK）。

### 运行单元测试（Windows 中文路径注意）

项目位于 `D:\课程表` 这类含中文的路径时，Gradle 测试 Worker 的 classpath argfile 曾出现
UTF-8/GBK 编码不一致，JUnit 报 `ClassNotFoundException`（仅影响单元测试，不影响打包）。

**现已由 `gradle.properties` 的 `-Dfile.encoding=COMPAT` 修复**（JDK 18+ 恢复旧默认编码，
使 worker 读 argfile 与守护进程写 argfile 的编码一致），并已在 `app/build.gradle.kts` 里
为 `Test` 任务显式钉死 UTF-8。因此中文路径下可直接运行：

```bash
./gradlew testDebugUnitTest
```

若在别处仍遇到编码类 `ClassNotFoundException`，回退方案是把项目放在纯 ASCII 路径
（或 `subst` 映射一个盘符）后重跑。

工具链：AGP 8.13.2 · Kotlin 2.2.21 · KSP · Compose BOM 2026.03 · Room 2.8.5 · Hilt 2.57.2 · Glance 1.2 · WorkManager 2.10 · minSdk 26 / targetSdk 36。

## 隐私

- 全部数据本地存储（Room + DataStore），不收集、不上传
- 教务账号仅在用户设备与学校服务器之间传递；不经任何第三方服务器
- 无广告 SDK、无统计 SDK；隐私政策随包内置（「我的 → 隐私政策」）

## 目录结构

```
app/src/main/java/com/zhijian/schedule/
├── MainActivity.kt / ZhiJianApp.kt
├── core/                       # 跨功能的基础设施（不含具体页面）
│   ├── model/                  # 领域模型（课程 / 周次位掩码 / 考试 / 成绩）
│   ├── data/                   # 持久化：db(Room，schema 导出在 app/schemas) / prefs(DataStore) / BackupManager
│   ├── repository/             # 仓库层，UI 只依赖它
│   ├── jw/                     # 教务适配器、解析器与脚本引擎（jw/script）
│   ├── net/                    # 网络基础设施
│   ├── holiday/                # 节假日 / 调休
│   ├── notifications/          # 精确闹钟与通知
│   ├── calendar/               # 系统日历写入
│   ├── capsule/                # 流体云实时胶囊
│   ├── sync/                   # 教务自动同步（WorkManager）
│   ├── update/                 # GitHub Releases 检查更新与应用内下载
│   ├── widget/                 # Glance 桌面小组件
│   ├── theme/ · ui/            # 主题、共享 UI（ui/glass、ui/island）
│   └── util/
├── feature/                    # 各功能页面 + ViewModel
│   ├── schedule/ · today/ · course_editor/
│   ├── importer/               # 导入（文件 / 二维码 qr / OCR / 解析器 parsers）
│   ├── jw/ · exam/ · grades/
│   └── profile/ · settings/ · update/
├── di/                         # Hilt 装配
└── navigation/                 # Navigation Compose
```

## 文档与参与

- **文档索引**：[docs/README.md](docs/README.md) — 按 research / plans / specs / testing / adr 分类
- **技术总纲**：[docs/specs/课程表APP技术方案.md](docs/specs/课程表APP技术方案.md)
- **贡献指南**：[CONTRIBUTING.md](CONTRIBUTING.md) — 环境要求、构建与测试命令、提交规范、提交前检查清单
- **变更日志**：[CHANGELOG.md](CHANGELOG.md) — 按版本回溯整理，遵循 Keep a Changelog
- **架构决策**：[docs/adr/](docs/adr/README.md) — 数据架构、教务脚本引擎、分发渠道、下载策略

## 后续路线（方案第 10 章）

已交付：考试安排与成绩统计（学业中心一期，v1.0.12）、教务脚本引擎（多教务导入，v1.0.12）——详见 [CHANGELOG.md](CHANGELOG.md)。

待做：v1.1 剩余项 WebDAV 同步 · 自动静音；v1.2：小米/荣耀/vivo 接入、OPPO 生态（需企业主体）；
v2.0：账号云同步、AI 解析、学分/作业、iOS。
