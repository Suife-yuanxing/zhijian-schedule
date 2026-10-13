# 纸间课表 · Paper Timetable

指尖之间，一纸课表。

面向中国高校学生的**本地优先、无广告、可深度定制**课程表 Android 应用。
技术方案见 [课程表APP技术方案.md](课程表APP技术方案.md)。

## 功能

- **周视图**：单双周 / 任意周次（位掩码）、上午下午分场、当前时间红线、周次横向翻页、周末开关
- **今日页**：正在上课进度、下一节倒计时、今日课程状态
- **多风格主题**：纸本暖白（默认）/ 动态取色 / 清爽极简 / 墨夜纯黑 / 多彩活泼，深色模式
- **课程管理**：增删改、节次区间（12 小节 / 6 大节两级模型）、周次快捷选择（全/单/双/清空）、自定义颜色
- **导入**：教务系统直连（正方，WebView 登录 + HTTP 重放抓取）、Excel(.xlsx) / CSV、JSON、粘贴口令/文本；统一预览（新增/变更/取消/冲突）后确认落库
- **教务自动同步**：每日 WorkManager 抓取，变更自动应用并通知；`manuallyEdited` 课程永不被覆盖
- **提醒**：精确闹钟（AlarmManager setExactAndAllowWhileIdle）、重启恢复、通知「静默」操作
- **桌面小组件**：今日课程（4×2）、下一节（2×2），随主题色板同步
- **同步备份**：全量 JSON 导出 / 恢复；写入系统日历（ColorOS 私有库 `com.coloros.calendar` + 原生库兼容）
- **检查更新**：GitHub Releases（自分发，无需商店与备案）

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

项目位于 `D:\课程表` 时，Gradle 测试 Worker 的 classpath argfile 存在 UTF-8/GBK 编码不一致，
JUnit 会报 `ClassNotFoundException`（仅影响单元测试，不影响打包）。绕过方式：

```bash
subst Z: "D:\课程表"          # 映射纯 ASCII 盘符
cd /z && gradle testDebugUnitTest
```

或把项目放在纯英文路径下即可直接 `gradle test`。

工具链：AGP 8.13.2 · Kotlin 2.2.21 · KSP · Compose BOM 2026.03 · Room 2.8.5 · Hilt 2.57.2 · Glance 1.2 · WorkManager 2.10 · minSdk 26 / targetSdk 36。

## 隐私

- 全部数据本地存储（Room + DataStore），不收集、不上传
- 教务账号仅在用户设备与学校服务器之间传递；不经任何第三方服务器
- 无广告 SDK、无统计 SDK；隐私政策随包内置（「我的 → 隐私政策」）

## 目录结构

```
app/src/main/java/com/zhijian/schedule/
├── MainActivity.kt / ZhiJianApp.kt
├── core/          # model / data(Room·DataStore·Backup) / repository / theme / util / jw / net
├── feature/       # schedule / today / course_editor / importer / jw / settings / profile
├── widget/        # Glance 小组件
├── alarm/         # 精确闹钟 / 通知
├── sync/          # 教务自动同步（WorkManager）
├── calendar/      # 系统日历写入
├── update/        # GitHub Releases 检查更新
└── navigation/    # Navigation Compose
```

## 后续路线（方案第 10 章）

v1.1：WebDAV 同步 · 考试安排 · 自动静音；v1.2：小米/荣耀/vivo 接入、OPPO 生态（需企业主体）；
v2.0：账号云同步、AI 解析、学分/作业、iOS。
