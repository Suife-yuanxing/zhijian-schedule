# 贡献指南 · 纸间课表

面向在本仓库提交代码或文档的人。所有命令都取自本仓库的真实配置与实测记录，不是通用模板。

## 0. 先读这些

- [README](README.md) — 功能清单与项目定位
- [docs/README.md](docs/README.md) — 文档索引，按 research / plans / specs / testing / adr 分类
- [docs/specs/课程表APP技术方案.md](docs/specs/课程表APP技术方案.md) — 项目技术总纲（10 章），新同学从这里建立全局认识

## 1. 开发环境

| 组件 | 要求 | 说明 |
| --- | --- | --- |
| JDK | **17+，实测 JDK 21** | 源码与目标字节码都是 JVM 17（`compileOptions` / `kotlin.jvmTarget`），但构建链在 JDK 21 上实测通过 |
| Android SDK | **platform 36 + build-tools 36** | `compileSdk = 36` / `targetSdk = 36` / `minSdk = 26` |
| Gradle | 8.14.3（用 wrapper，无需自装） | `gradle/wrapper/gradle-wrapper.properties` 走腾讯镜像 `mirrors.cloud.tencent.com` |
| 设备 | Android 8.0（API 26）及以上 | 液态玻璃主题的真折射档需 Android 13+ |

首次克隆后配置 SDK 路径（`local.properties` 已在 `.gitignore` 里，不会提交）：

```properties
sdk.dir=D\:\\Android
```

写 release 签名需要的 `keystore.properties` 同样不入库；**文件不存在时 release 构建会回落到 debug 签名**（见 `app/build.gradle.kts`），因此本地验证 release 产物无需私钥。

技术栈：AGP 8.13.2 · Kotlin 2.2.21 · KSP · Compose BOM 2026.03 · Room 2.8.5 · Hilt 2.57.2 · Glance 1.2 · WorkManager 2.10 · OkHttp · kotlinx.serialization。

## 2. 构建

```bash
# 只做编译检查（改完 Kotlin 先跑这个，比整包快得多）
./gradlew compileDebugKotlin

# Debug APK → app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Release APK（无 keystore.properties 时用 debug 签名）
./gradlew assembleRelease
```

Windows 下把 `./gradlew` 换成 `gradlew.bat`（或在 Git Bash 里保持 `./gradlew`）。

两个已知的构建期行为，不是故障：

- **release 不跑 lintVital**：`lint { checkReleaseBuilds = false }`。原因是它要从 `dl.google.com` 拉 lint-gradle，国内常连接超时导致 release 构建失败。需要 lint 时另行手动运行。
- **项目路径含中文时 AGP 需要关闭 ASCII 路径检查**：`android.overridePathCheck=true` 已在 `gradle.properties` 配好（本项目不使用 NDK）。

## 3. 运行单元测试（Windows 中文路径绕坑）

```bash
# 全量
./gradlew testDebugUnitTest

# 单个测试类
./gradlew testDebugUnitTest --tests "com.zhijian.schedule.core.jw.ExamParserTest"
```

项目路径是 `D:\课程表` 时，Gradle **测试 Worker 读 @argfile 用的是本机原生编码（GBK）**；守护进程若按 UTF-8 写 argfile，`D:\课程表\app\build\...` 这类类路径会变成乱码，JUnit 报 `ClassNotFoundException` / `initializationError`。**只影响单元测试，不影响打包。**

仓库里已经有两层处理，正常情况直接就能跑：

1. `gradle.properties`：`org.gradle.jvmargs=... -Dfile.encoding=COMPAT`
2. `app/build.gradle.kts`：`tasks.withType<Test> { jvmArgs("-Dfile.encoding=UTF-8", "-Dsun.jnu.encoding=UTF-8") }`

若仍然失败，用**纯 ASCII 盘符**彻底绕开（README 记录的实测方案）：

```bash
subst Z: "D:\课程表"
cd /z && ./gradlew testDebugUnitTest
subst Z: /D          # 用完解除映射
```

或者干脆把仓库放在纯英文路径下，两种绕法都不需要。

**测试栈约定：JUnit4，不引入 Robolectric。** `app/build.gradle.kts` 里 `unitTests.isReturnDefaultValues = true` 让 `android.util.Log` 这类桩返回默认值。因此——**凡是需要被测试的判定逻辑，必须抽成不依赖 Android API 的纯逻辑文件**，现有例子：`core/update/ResumableDownload.kt`（分片规划 / 进度聚合 / 校验 / 清理规则）、`core/jw` 下的解析器与合并决策。这是本项目的硬性架构习惯，写新功能时照做。

## 4. 代码风格

已接入（2026-10）：仓库根 `.editorconfig`（`root = true`，基准为 ktlint 官方风格：4 空格缩进、`max_line_length = 120`）+ **ktlint 14.2.0** + **detekt 1.23.8**。插件直接 apply 在 `:app` 上，配置见 `app/build.gradle.kts` 的「代码质量基建」段落与 `config/detekt/detekt.yml`。

```bash
# 提交前必跑（= CI 跑的那条）
./gradlew assembleDebug testDebugUnitTest ktlintCheck detekt

# 只查风格 / 只查静态问题
./gradlew ktlintCheck
./gradlew detekt

# 自动修格式
./gradlew ktlintFormat
```

三条必须知道的机制（读配置即可确认，不要当成 bug）：

1. **存量放行、增量拦截**：历史违例冻结在 `config/ktlint/baseline.xml` 与 `config/detekt/baseline.xml` 里；**新增或被修改的文件一旦违规，任务立即失败**。所以不要往 baseline 里加豁免，那等于把违规合法化。要整体收紧规则时重新生成 baseline：`./gradlew ktlintGenerateBaseline` / `./gradlew detektBaseline`。
2. **`max_line_length = 120`**：与 ktlint 官方风格默认值一致（2026-10-29 按字符数重测：300 个 `.kt` / 42944 行里，超 120 列只剩 31 行、涉及 19 个文件；超 100 列 367 行）。统计要用 `len(line)` 按字符数数，`grep -P '^.{121,}$'` 是**字节**计数，会把 40 个汉字的注释行误判成超长。
3. **`.editorconfig` 故意不设 `end_of_line`**：本仓库 `core.autocrlf=true`，工作区换行符混存（`git ls-files --eol` 可见 w/crlf 与 w/lf 并存）而 index 侧全是 LF，属于正常状态；写死该值会让编辑器整体重写文件，产出海量无意义 diff。仓库根的 `.gitattributes` 只钉死 `gradlew`（lf）与 `gradlew.bat`（crlf）这两个壳脚本，**没有** `* text=auto`——原因同上。

从现有代码可以验证的项目约定（与工具无关，长期有效）：

1. **包结构固定为 `com.zhijian.schedule.{core, feature, di, navigation}`**
   - `core/` 放跨功能的基础设施：`model` / `data`(db · prefs) / `repository` / `net` / `jw` / `notifications` / `calendar` / `capsule` / `sync` / `update` / `widget` / `theme` / `ui` / `util` / `holiday`
   - `feature/<功能>/` 放该功能的页面与 ViewModel：`schedule` / `today` / `course_editor` / `importer` / `jw` / `exam` / `grades` / `profile` / `settings` / `update`
   - **新增代码按此归位，不要新建顶层功能包**（早期顶层 `alarm`/`calendar`/`capsule`/`sync`/`update`/`widget` 已迁入 `core/`）。
2. **可测逻辑与 Android 解耦**：见上一节。纯逻辑文件顶部不要 `import android.*`。
3. **注释与文档用中文**，公开 API 写 KDoc。
4. **Room schema 变更必须提交导出**：KSP 已配 `room.schemaLocation = app/schemas`，改表后 `app/schemas/com.zhijian.schedule.core.data.db.AppDatabase/` 下会多出新版本 JSON，连同迁移代码一起提交（历史提交 `chore(db): 提交 Room v3 导出 schema` 就是这条约定）。
5. **不要把 `docs/` 与新代码混在一个提交里**，文档改动单独成提交（`docs(plan):` / `docs(spec):` / `docs(mock-jw):`）。

## 5. 分支与提交信息

### 分支

现状：单人开发，135 个提交**全部直接在 `main` 上**，历史线性、无 merge 提交（`git log --merges` 为空）。

需要多步特性或多人协作时，开短分支并把历史保持线性：

```bash
git switch -c feat/xxx      # 或 fix/xxx
# 完成后 rebase 到 main 再合并，不要制造 merge 提交
```

### 提交信息：Conventional Commits（中文正文）

```
<type>(<scope>): <中文描述>
```

- `type` 取 `feat` / `fix` / `docs` / `test` / `chore` / `style` / `refactor`
- `scope` 用模块名，历史中实际出现过（按频次）：`ui` · `script` · `exam` · `jw` · `changelog` · `spec` · `research` · `mock-jw` · `today` · `capsule` · `grades` · `update` · `db` · `alarm` · `plan` · `quality`

真实例子（可直接 `git log` 复核）：

```
feat(exam): 考前两档精确提醒（24h/1h，号段与课程隔离）
fix(script): 通用脚本（hosts 为空）被误判为不可运行
docs(spec): 教务脚本引擎设计（页面上下文注入 + 最小契约 + 三件套分发）
chore(db): 提交 Room v3 导出 schema
```

规范化程度（截至 v1.0.14，135 个提交）：`feat` 40 · `docs` 16 · `fix` 9 · `test` 2 · `chore` 2 · `style` 1 · `refactor` 1，其余 64 条是规范引入前的历史提交（无前缀）。**新提交一律带前缀**；一个提交只做一件事，提交信息写"做了什么、为什么"，不写"改了一下"。

### 仓库硬约定

- **永远不要用 `git add -A` / `git add .` / `git add docs`**：只 `git add` 明确列出的文件。这条来自 [实施计划里的记录](docs/plans/2026-10-15-update-download-acceleration.md)。
- **移动或重命名文件用 `git mv`**，保留历史；批量移动时一次只动一个目录。
- 不提交 `keystore/`、`*.jks`、`keystore.properties`、`local.properties`（`.gitignore` 已覆盖）。

## 6. 文档约定

文档分类、命名与"怎么加一篇新文档"见 [docs/README.md](docs/README.md)。三条要点：

1. 日期取**首稿完成日**；`docs/plans/X.md` 与 `docs/specs/X-design.md` 成对。
2. 改了架构取舍就补一篇 `docs/adr/`，**不改历史 ADR**。
3. 新增/移动文档后同步 `docs/README.md` 索引，并确认没有失效链接。

## 7. 发版

App 的更新检查直接读 GitHub Releases（`core/update/UpdateChecker.kt` → `api.github.com/repos/Suife-yuanxing/zhijian-schedule/releases/latest`），所以发版节奏由 Release 决定：

1. 同步改 `app/build.gradle.kts` 的 `versionCode` 与 `versionName`（当前是 15 / "1.0.14"）。
2. 提交：`版本号提升至 v1.0.15（versionCode 16）准备发布构建`（照这次的格式，填下一版的号）。
3. 构建：`./gradlew assembleRelease`。
4. 在 `Suife-yuanxing/zhijian-schedule` 建 Release：
   - **tag 必须是 `vX.Y.Z`** —— App 会去掉 `v` 前缀后与本地 `versionName` 做逐位数值比较；
   - **必须上传 `.apk` 资产** —— App 取 **第一个文件名以 `.apk` 结尾**的资产，命名随意但后缀要对。
5. GitHub 会为资产生成 `digest`（`sha256:...`），App 下载完成后用它校验完整性；**没有 digest 的 Release 会被强制禁用镜像加速源**（直连下载仍然可用）。
6. 把 `CHANGELOG.md` 的 Unreleased 段落写实、落到新版本号下。

## 8. 提交前检查清单

- [ ] `./gradlew assembleDebug testDebugUnitTest ktlintCheck detekt` 通过 —— 这正是 `.github/workflows/ci.yml` 在 PR 上跑的命令
- [ ] `./gradlew compileDebugKotlin` 通过（本地快速迭代时的编译检查）
- [ ] `./gradlew testDebugUnitTest` 全绿（中文路径下失败时先按 [第 3 节](#3-运行单元测试windows-中文路径绕坑) 绕坑）
- [ ] 没有往 `config/ktlint/baseline.xml` / `config/detekt/baseline.xml` 里新增豁免
- [ ] 新增/修改的判定逻辑已抽成纯逻辑并有 JUnit 单测
- [ ] Room 实体或 schema 有变更时，已提交 `app/schemas/` 导出与迁移
- [ ] 新代码放进 `core/` 或 `feature/<功能>/`，没有新建顶层功能包
- [ ] 只 `git add` 了明确列出的文件，没有用 `git add -A`
- [ ] 提交信息符合 `<type>(<scope>): 中文描述`
- [ ] 文件移动使用了 `git mv`
- [ ] 改动了 `docs/` 时同步了 `docs/README.md` 索引，且没有失效链接
- [ ] 架构取舍有变更时补了 `docs/adr/` 条目
- [ ] 版本变更时同步了 `CHANGELOG.md`
