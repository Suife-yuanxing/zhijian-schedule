# 应用内更新下载（点击即下载 + 进度可见）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 APK 下载从「跳浏览器自己找」改成「应用内点击即下载」，卡片与通知栏双处显示进度，失败/取消后可从断点续传，校验通过后由用户手动点安装。

**Architecture:** 状态唯一来源是 `@Singleton ApkDownloader`（暴露 `StateFlow<DownloadState>`），下载协程跑在 `ApkDownloadService`（dataSync 前台服务）的 scope 里，服务只负责保活与渲染通知；文件布局与元数据读写收在 `UpdateStore`；所有可单测的判定（Range 决策、校验、速率、清理规则、通知节流）集中在纯逻辑文件 `ResumableDownload.kt`，不依赖任何 Android API。

**Tech Stack:** Kotlin 2.2.21 / Jetpack Compose / Hilt / OkHttp 4.12.0 / kotlinx.serialization 1.9.0 / JUnit4（无 Robolectric）/ minSdk 26, targetSdk 36

**Spec:** [docs/specs/2026-10-15-in-app-update-download-design.md](../specs/2026-10-15-in-app-update-download-design.md)

---

## 开工前检查（preflight）

工作区根目录：`D:\课程表`（路径含中文，构建脚本已处理编码问题）。

- [ ] **P1: 确认基线单测全绿**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --console=plain"
```

预期：`BUILD SUCCESSFUL`，10 个测试类 / 77 个用例，0 失败。若不是，先停下来报告，不要继续。

- [ ] **P2: 确认工作区干净且 `docs/` 仍未被跟踪**

```bat
git -C "D:\课程表" status --short
```

预期：只有 `?? docs/`。本计划**所有提交都只 `git add` 明确列出的代码文件，永远不用 `git add -A`** —— 用户明确要求 `docs/` 留在本地不提交。

- [ ] **P3: 记住两条构建命令**

```bat
rem 单测（可加 --tests 过滤，类名里没有空格所以不用引号）
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.ResumableDownloadTest --console=plain"

rem 编译主源码（快速语法检查）
cmd /c ".tmp\run-tests.bat :app:compileDebugKotlin --console=plain"

rem release 构建（验证 R8 混淆不炸）
cmd /c ".tmp\build-release.bat :app:assembleRelease --console=plain"
```

---

## 文件结构

**新建（主源码）**

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/zhijian/schedule/update/ResumableDownload.kt` | **纯逻辑**：Range 决策、Content-Range 解析、进度分母计算、SHA-256 校验、字节/ETA 格式化、清理规则、速率估算、通知节流。零 Android 依赖，可直接 JUnit 跑 |
| `app/src/main/java/com/zhijian/schedule/update/UpdateStore.kt` | `filesDir/updates/` 的目录布局、`.part`/`.meta` 读写、缓存统计与清理。只碰文件系统与 JSON |
| `app/src/main/java/com/zhijian/schedule/update/ApkDownloader.kt` | `@Singleton`：`DownloadState` 唯一事实来源 + 断点续传下载体 + 取消/超时/恢复入口 |
| `app/src/main/java/com/zhijian/schedule/update/UpdateNotification.kt` | `update` 通知渠道、进度通知（带「取消下载」action）、完成通知 |
| `app/src/main/java/com/zhijian/schedule/update/ApkDownloadService.kt` | dataSync 前台服务：持有下载协程、渲染通知、处理取消 action 与 `onTimeout` |
| `app/src/main/java/com/zhijian/schedule/update/ApkInstaller.kt` | FileProvider URI + 安装 Intent + 「安装未知应用」授权引导 |
| `app/src/main/java/com/zhijian/schedule/di/DownloadClient.kt` | `@DownloadClient` 限定符（把下载专用 OkHttpClient 与通用客户端分开） |
| `app/src/main/res/xml/file_paths.xml` | FileProvider 路径白名单 |

**新建（测试）**

| 文件 | 覆盖 |
|---|---|
| `app/src/test/java/com/zhijian/schedule/ResumableDownloadTest.kt` | Range 决策、Content-Range、进度分母、元数据往返、SHA-256 校验、字节/ETA 格式化、清理规则 |
| `app/src/test/java/com/zhijian/schedule/DownloadProgressTest.kt` | 速率滑窗与 ETA、通知节流双条件 |

**修改**

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/zhijian/schedule/update/UpdateChecker.kt` | `UpdateInfo` 增 `apkSize`/`apkDigest`，从同一个 asset 一起解析 |
| `app/src/main/java/com/zhijian/schedule/di/AppModule.kt` | 追加 `@DownloadClient` 的长超时 OkHttpClient |
| `app/src/main/res/values/strings.xml` | 新增 4 条字符串 |
| `app/src/main/AndroidManifest.xml` | 2 个权限 + 1 个 service + 1 个 FileProvider |
| `app/src/main/java/com/zhijian/schedule/ZhiJianApp.kt` | 启动时清理旧版本残留 |
| `app/src/main/java/com/zhijian/schedule/feature/settings/SettingsViewModel.kt` | 下载/取消/安装/清理接线 + 观察下载状态 |
| `app/src/main/java/com/zhijian/schedule/feature/settings/SettingsScreen.kt` | 更新卡片重写为 11 态 + 清理缓存入口 + 修掉硬编码版本号 |

### 与设计文档的 5 处差异（有意为之）

1. **新增 `UpdateStore.kt`**：设计把目录与元数据职责写在 `ApkDownloader` 里，拆出来后两个文件各自聚焦（约 150 行 / 110 行），且 `UpdateStore` 是唯一碰 `filesDir` 的地方。
2. **新增 `ApkDownloader.restoreFromDisk()`**：设计未涉及进程重启。若不恢复，用户重启后再点「继续下载」会把已下好的 46 MB 从头再下一遍。检查到新版本后按磁盘现状恢复 `Ready`/`Paused`。
3. **`cancel()` 与 `timeout()` 分开**：设计 §8 只说超时「收尾并转 Failed」。取消是用户主动中止 → `Paused`（卡片文案「已取消」，给「继续下载」）；超时是系统掐断 → `Failed(canResume=true)`（给「重试」）。两者分片都保留。
4. **请求带 `Accept-Encoding: identity`**：设计未提。不加的话 OkHttp 会透明解压 gzip，`Content-Length` 变成**压缩后**大小，进度分母与长度校验全错。
5. **检查更新的失败反馈从顶部横幅移到卡片状态行**：设计 §4 表格要求卡片显示「检查更新失败：<原因>」，所以 `checkUpdate()` 不再写 `uiState.message`，改写入新的 `updateError`。

---

### Task 1: 响应判定与进度分母的纯逻辑

**Files:**
- Create: `app/src/test/java/com/zhijian/schedule/ResumableDownloadTest.kt`
- Create: `app/src/main/java/com/zhijian/schedule/update/ResumableDownload.kt`

- [ ] **Step 1: 写失败的测试**

新建 `app/src/test/java/com/zhijian/schedule/ResumableDownloadTest.kt`：

```kotlin
package com.zhijian.schedule

import com.zhijian.schedule.core.update.ContentRange
import com.zhijian.schedule.core.update.PartMeta
import com.zhijian.schedule.core.update.RangeDecision
import com.zhijian.schedule.core.update.ResumableDownload
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumableDownloadTest {

    // ---------- Content-Range ----------

    @Test
    fun contentRangeParsesStartEndAndTotal() {
        val range = ResumableDownload.parseContentRange("bytes 1234-46920381/46920382")
        assertEquals(ContentRange(1234L, 46920381L, 46920382L), range)
    }

    @Test
    fun contentRangeAcceptsUnknownTotal() {
        val range = ResumableDownload.parseContentRange("bytes 0-99/*")
        assertEquals(ContentRange(0L, 99L, null), range)
    }

    @Test
    fun contentRangeReturnsNullWhenMalformedOrAbsent() {
        assertNull(ResumableDownload.parseContentRange(null))
        assertNull(ResumableDownload.parseContentRange(""))
        assertNull(ResumableDownload.parseContentRange("bytes 1234-"))
        assertNull(ResumableDownload.parseContentRange("bytes -99/100"))
        assertNull(ResumableDownload.parseContentRange("items 1-2/3"))
    }

    // ---------- 进度分母 ----------

    @Test
    fun totalPrefersContentRangeOverEverything() {
        val range = ContentRange(1000L, 1999L, 5000L)
        assertEquals(5000L, ResumableDownload.resolveTotalBytes(1000L, range, 4000L))
    }

    @Test
    fun totalIgnoresRemainingContentLengthOn206() {
        // 206 的 Content-Length 只是剩余字节，必须靠 Content-Range 的 total
        val range = ContentRange(1000L, 4999L, 5000L)
        assertEquals(5000L, ResumableDownload.resolveTotalBytes(4000L, range, null))
    }

    @Test
    fun totalFallsBackToKnownSizeWhenRangeTotalIsStar() {
        val range = ContentRange(1000L, 4999L, null)
        assertEquals(5000L, ResumableDownload.resolveTotalBytes(4000L, range, 5000L))
    }

    @Test
    fun totalUsesContentLengthOnFullResponse() {
        assertEquals(46920382L, ResumableDownload.resolveTotalBytes(46920382L, null, null))
    }

    @Test
    fun totalFallsBackToKnownSizeWhenNoLengthHeader() {
        assertEquals(46920382L, ResumableDownload.resolveTotalBytes(null, null, 46920382L))
        assertEquals(null, ResumableDownload.resolveTotalBytes(null, null, null))
        assertEquals(null, ResumableDownload.resolveTotalBytes(0L, null, 0L))
    }

    // ---------- Range 决策 ----------

    @Test
    fun partialContentAppendsFromExistingBytes() {
        assertEquals(RangeDecision.Append(1000L), ResumableDownload.decideRange(206, 1000L, 5000L))
    }

    @Test
    fun okResponseRestartsFromScratch() {
        assertEquals(RangeDecision.Restart, ResumableDownload.decideRange(200, 1000L, 5000L))
    }

    @Test
    fun rangeNotSatisfiableWithFullBytesIsAlreadyComplete() {
        assertEquals(RangeDecision.AlreadyComplete, ResumableDownload.decideRange(416, 5000L, 5000L))
    }

    @Test
    fun rangeNotSatisfiableWithMismatchedBytesIsCorrupt() {
        assertEquals(RangeDecision.Corrupt, ResumableDownload.decideRange(416, 6000L, 5000L))
        assertEquals(RangeDecision.Corrupt, ResumableDownload.decideRange(416, 0L, null))
    }

    @Test
    fun clientErrorDoesNotOfferResume() {
        val decision = ResumableDownload.decideRange(404, 1000L, 5000L)
        assertTrue(decision is RangeDecision.Fail)
        assertEquals(false, (decision as RangeDecision.Fail).canResume)
        assertTrue(decision.reason.contains("404"))
    }

    @Test
    fun serverErrorOffersResume() {
        val decision = ResumableDownload.decideRange(502, 1000L, 5000L)
        assertEquals(true, (decision as RangeDecision.Fail).canResume)
    }

    // ---------- 断点元数据 ----------

    @Test
    fun partMetaRoundTripsThroughJson() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val meta = PartMeta(
            version = "1.0.3",
            url = "https://example.com/a.apk",
            etag = "\"abc\"",
            lastModified = "Wed, 15 Oct 2026 10:00:00 GMT",
            totalBytes = 46920382L,
            digest = "sha256:d3dba925",
        )
        assertEquals(meta, json.decodeFromString<PartMeta>(json.encodeToString(meta)))
    }

    @Test
    fun partMetaKeepsOptionalsNullOnRoundTrip() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val meta = PartMeta(version = "1.0.3", url = "https://example.com/a.apk")
        val decoded = json.decodeFromString<PartMeta>(json.encodeToString(meta))
        assertEquals(meta, decoded)
        assertNull(decoded.etag)
        assertEquals(0L, decoded.totalBytes)
    }

    @Test
    fun partMetaToleratesUnknownKeysAndMissingOptionals() {
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<PartMeta>(
            """{"version":"1.0.3","url":"https://example.com/a.apk","futureField":42}"""
        )
        assertEquals("1.0.3", decoded.version)
        assertNull(decoded.etag)
        assertEquals(0L, decoded.totalBytes)
    }
}
```

- [ ] **Step 2: 跑测试确认它编译不过（目标符号还不存在）**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.ResumableDownloadTest --console=plain"
```

预期：`BUILD FAILED`，`Unresolved reference: update`（或 `ResumableDownload`）。

- [ ] **Step 3: 写最小实现**

新建 `app/src/main/java/com/zhijian/schedule/update/ResumableDownload.kt`：

```kotlin
package com.zhijian.schedule.core.update

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * 断点续传与下载结果的**纯逻辑**：不依赖任何 Android API，可直接用 JUnit 跑（项目没有 Robolectric）。
 * 碰 Context / 网络 / 状态机的部分在 UpdateStore、ApkDownloader 与 ApkDownloadService。
 */

/** 断点元数据，与 `<version>.apk.part` 同目录共存（写成 `<version>.apk.meta`，JSON） */
@Serializable
data class PartMeta(
    val version: String,
    val url: String,
    val etag: String? = null,
    val lastModified: String? = null,
    val totalBytes: Long = 0L,
    val digest: String? = null,
)

/** `Content-Range: bytes 1234-46920381/46920382` 的解析结果；total 为 `*` 时是 null */
data class ContentRange(val start: Long, val endInclusive: Long, val total: Long?)

/** 服务端响应 → 该怎么写 `.part` */
sealed interface RangeDecision {
    /** 206：服务端支持续传且资源未变，从 offset 追加 */
    data class Append(val offset: Long) : RangeDecision

    /** 200：服务端忽略了 Range，或 If-Range 判定资源已变，清空 `.part` 从头写 */
    data object Restart : RangeDecision

    /** 416 且已有分片正好等于目标长度：已经下完整了，直接进校验 */
    data object AlreadyComplete : RangeDecision

    /** 416 但长度对不上：分片损坏，清空重下 */
    data object Corrupt : RangeDecision

    /** 直接失败；canResume 决定卡片给「重试（续传）」还是「重新下载」 */
    data class Fail(val reason: String, val canResume: Boolean) : RangeDecision
}

object ResumableDownload {

    /** `.apk[.part|.meta]` 后缀；长的必须排在前面（判定要覆盖 `.apk.part` 而非 `.apk`） */
    private val FILE_SUFFIXES = listOf(".apk.part", ".apk.meta", ".apk")

    // ---------- 响应判定 ----------

    /** 解析 `bytes <start>-<end>/<total>`；格式不认识一律返回 null，调用方按「无 Content-Range」处理 */
    fun parseContentRange(header: String?): ContentRange? {
        val text = header?.trim().orEmpty()
        if (text.length <= 6 || !text.regionMatches(0, "bytes ", 0, 6, ignoreCase = true)) return null
        val spec = text.substring(6).trim()
        val slash = spec.indexOf('/')
        if (slash <= 0) return null
        val range = spec.substring(0, slash).trim()
        val totalText = spec.substring(slash + 1).trim()
        val dash = range.indexOf('-')
        if (dash <= 0) return null
        val start = range.substring(0, dash).trim().toLongOrNull() ?: return null
        val end = range.substring(dash + 1).trim().toLongOrNull() ?: return null
        val total = if (totalText == "*") null else totalText.toLongOrNull() ?: return null
        return ContentRange(start, end, total)
    }

    /**
     * 进度分母。优先级：Content-Range 的 total（206 的权威值）> Content-Length（只有完整响应才等于整份大小）
     * > 已知大小（GitHub API 的 `size` 或元数据）。
     * 206 的 Content-Length 只是**剩余**字节，绝不能当分母。
     */
    fun resolveTotalBytes(contentLength: Long?, contentRange: ContentRange?, knownTotal: Long?): Long? {
        contentRange?.total?.takeIf { it > 0 }?.let { return it }
        // 206 但 total 是 `*`（少见）：Content-Length 不可用，退回已知大小
        if (contentRange != null) return knownTotal?.takeIf { it > 0 }
        contentLength?.takeIf { it > 0 }?.let { return it }
        return knownTotal?.takeIf { it > 0 }
    }

    /** 按响应码与已有分片大小决定写入方式 */
    fun decideRange(code: Int, partBytes: Long, totalBytes: Long?): RangeDecision = when {
        code == 206 -> RangeDecision.Append(partBytes)
        code == 200 -> RangeDecision.Restart
        code == 416 -> if (totalBytes != null && partBytes == totalBytes) {
            RangeDecision.AlreadyComplete
        } else {
            RangeDecision.Corrupt
        }
        code in 400..499 -> RangeDecision.Fail("下载失败（HTTP $code），请重新下载", canResume = false)
        code in 500..599 -> RangeDecision.Fail("服务端错误（HTTP $code），可重试", canResume = true)
        else -> RangeDecision.Fail("下载失败（HTTP $code），可重试", canResume = true)
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.ResumableDownloadTest --console=plain"
```

预期：`BUILD SUCCESSFUL`，17 个用例全过。

- [ ] **Step 5: 提交**

```bat
git -C "D:\课程表" add app/src/main/java/com/zhijian/schedule/update/ResumableDownload.kt app/src/test/java/com/zhijian/schedule/ResumableDownloadTest.kt
git -C "D:\课程表" commit -m "新增更新下载的断点续传判定与元数据（纯逻辑 + 单测）"
```

---

### Task 2: 校验与格式化的纯逻辑

**Files:**
- Modify: `app/src/test/java/com/zhijian/schedule/ResumableDownloadTest.kt`（追加测试）
- Modify: `app/src/main/java/com/zhijian/schedule/update/ResumableDownload.kt`（追加实现）

- [ ] **Step 1: 写失败的测试**

在 `ResumableDownloadTest.kt` 的最后一个 `}` 之前追加：

```kotlin
    // ---------- 摘要与校验 ----------

    @Test
    fun digestNormalizesSha256PrefixAndCase() {
        assertEquals(SHA256_ABC, ResumableDownload.normalizeDigest("sha256:$SHA256_ABC"))
        assertEquals(SHA256_ABC, ResumableDownload.normalizeDigest("SHA256:${SHA256_ABC.uppercase()}"))
    }

    @Test
    fun digestReturnsNullWhenAbsentOrUnusable() {
        assertNull(ResumableDownload.normalizeDigest(null))
        assertNull(ResumableDownload.normalizeDigest(""))
        assertNull(ResumableDownload.normalizeDigest("md5:$SHA256_ABC"))
        assertNull(ResumableDownload.normalizeDigest("sha256:abc"))          // 长度不对
        assertNull(ResumableDownload.normalizeDigest("sha256:" + "z".repeat(64))) // 非十六进制
    }

    @Test
    fun sha256MatchesKnownVector() {
        assertEquals(SHA256_ABC, ResumableDownload.sha256Hex("abc".toByteArray().inputStream()))
    }

    @Test
    fun sha256HandlesEmptyInput() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ResumableDownload.sha256Hex(ByteArray(0).inputStream()),
        )
    }

    @Test
    fun verifyAcceptsMatchingFile() {
        val file = tempFile("abc")
        assertEquals(VerifyResult.Ok, ResumableDownload.verifyFile(file, 3L, "sha256:$SHA256_ABC"))
    }

    @Test
    fun verifyRejectsWrongLength() {
        assertEquals(
            VerifyResult.LengthMismatch,
            ResumableDownload.verifyFile(tempFile("abc"), 4L, null),
        )
    }

    @Test
    fun verifyRejectsWrongDigest() {
        assertEquals(
            VerifyResult.DigestMismatch,
            ResumableDownload.verifyFile(tempFile("abc"), 3L, "sha256:" + "0".repeat(64)),
        )
    }

    @Test
    fun verifySkipsHashWhenDigestMissingOrUnusable() {
        val file = tempFile("abc")
        assertEquals(VerifyResult.Ok, ResumableDownload.verifyFile(file, 3L, null))
        assertEquals(VerifyResult.Ok, ResumableDownload.verifyFile(file, null, "not-a-digest"))
    }

    @Test
    fun verifySkipsLengthWhenExpectedBytesUnknown() {
        assertEquals(
            VerifyResult.Ok,
            ResumableDownload.verifyFile(tempFile("abc"), null, "sha256:$SHA256_ABC"),
        )
    }

    // ---------- 展示 ----------

    @Test
    fun formatBytesUsesBinarySteps() {
        assertEquals("0 B", ResumableDownload.formatBytes(0L))
        assertEquals("1023 B", ResumableDownload.formatBytes(1023L))
        assertEquals("1.0 KB", ResumableDownload.formatBytes(1024L))
        assertEquals("44.7 MB", ResumableDownload.formatBytes(46920382L))
        assertEquals("1.0 GB", ResumableDownload.formatBytes(1024L * 1024L * 1024L))
        assertEquals("0 B", ResumableDownload.formatBytes(-5L))
    }

    @Test
    fun etaTextFormatsAndGuards() {
        assertNull(ResumableDownload.etaText(null))
        assertEquals("约剩 0 秒", ResumableDownload.etaText(0L))
        assertEquals("约剩 30 秒", ResumableDownload.etaText(30L))
        assertEquals("约剩 1 分", ResumableDownload.etaText(60L))
        assertEquals("约剩 2 分", ResumableDownload.etaText(90L))
        assertEquals("约剩 1 小时", ResumableDownload.etaText(3600L))
    }

    private fun tempFile(content: String): File {
        val file = File.createTempFile("part", ".bin")
        file.writeBytes(content.toByteArray(Charsets.UTF_8))
        file.deleteOnExit()
        return file
    }

    private companion object {
        const val SHA256_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
```

同时在文件顶部的 import 区补上：

```kotlin
import com.zhijian.schedule.core.update.VerifyResult
import java.io.File
```

- [ ] **Step 2: 跑测试确认失败**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.ResumableDownloadTest --console=plain"
```

预期：`BUILD FAILED`，`Unresolved reference: VerifyResult`。

- [ ] **Step 3: 写最小实现**

在 `ResumableDownload.kt` 的 `decideRange` 之后、object 的收尾 `}` 之前插入：

```kotlin
    // ---------- 校验 ----------

    /** `sha256:abc…` → 小写十六进制；非 SHA-256、缺失或不是 64 位十六进制时返回 null（当作没有摘要） */
    fun normalizeDigest(digest: String?): String? {
        val text = digest?.trim().orEmpty()
        if (!text.startsWith("sha256:", ignoreCase = true)) return null
        val hex = text.substring(7).trim().lowercase(Locale.US)
        if (hex.length != 64) return null
        if (hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return hex
    }

    /** 流式 SHA-256（64 KiB 缓冲），返回 64 位小写十六进制。46 MB 约 0.2 秒 */
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        val bytes = digest.digest()
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    /**
     * 校验下载结果：长度必须等于 [expectedBytes]（为 null 时跳过）；
     * [expectedDigest] 是合法 `sha256:` 时流式计算 SHA-256 并比对。
     * 哈希是纵深防御：长度对但内容坏（CDN 分片损坏、中间篡改）只有哈希能发现。
     */
    fun verifyFile(file: File, expectedBytes: Long?, expectedDigest: String?): VerifyResult {
        if (expectedBytes != null && file.length() != expectedBytes) return VerifyResult.LengthMismatch
        val expected = normalizeDigest(expectedDigest) ?: return VerifyResult.Ok
        val actual = file.inputStream().use { sha256Hex(it) }
        return if (actual == expected) VerifyResult.Ok else VerifyResult.DigestMismatch
    }

    // ---------- 展示 ----------

    /** 1024 进位、一位小数；不足 1 KB 显示整数 B（46920382 → "44.7 MB"） */
    fun formatBytes(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L)
        return when {
            value < 1024L -> "$value B"
            value < 1024L * 1024L -> oneDecimal(value / 1024.0, "KB")
            value < 1024L * 1024L * 1024L -> oneDecimal(value / (1024.0 * 1024.0), "MB")
            else -> oneDecimal(value / (1024.0 * 1024.0 * 1024.0), "GB")
        }
    }

    private fun oneDecimal(value: Double, unit: String): String =
        String.format(Locale.US, "%.1f %s", value, unit)

    /** "约剩 30 秒"；无法估算（null 或负数）时返回 null，调用方直接不显示 */
    fun etaText(seconds: Long?): String? {
        val value = seconds?.takeIf { it >= 0 } ?: return null
        return when {
            value < 60 -> "约剩 $value 秒"
            value < 3600 -> "约剩 ${(value + 59) / 60} 分"
            else -> "约剩 ${(value + 3599) / 3600} 小时"
        }
    }
```

再在 object 内的 `FILE_SUFFIXES` 下面补常量：

```kotlin
    private const val HEX = "0123456789abcdef"
```

最后在 object 收尾 `}` 之后追加 `VerifyResult` 声明：

```kotlin
/** 下载结果校验结论 */
sealed interface VerifyResult {
    data object Ok : VerifyResult

    /** 字节数与预期不符：分片大概没写完，保留后可续传 */
    data object LengthMismatch : VerifyResult

    /** 字节数对但摘要不符：内容被换过或损坏，必须重下 */
    data object DigestMismatch : VerifyResult
}
```

- [ ] **Step 4: 跑测试确认通过**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.ResumableDownloadTest --console=plain"
```

预期：`BUILD SUCCESSFUL`，28 个用例全过。

- [ ] **Step 5: 提交**

```bat
git -C "D:\课程表" add app/src/main/java/com/zhijian/schedule/update/ResumableDownload.kt app/src/test/java/com/zhijian/schedule/ResumableDownloadTest.kt
git -C "D:\课程表" commit -m "新增下载校验与展示的纯逻辑：SHA-256 比对、字节与剩余时间格式化"
```

---

### Task 3: 清理规则与通知节流的纯逻辑

**Files:**
- Create: `app/src/test/java/com/zhijian/schedule/DownloadProgressTest.kt`
- Modify: `app/src/test/java/com/zhijian/schedule/ResumableDownloadTest.kt`（追加清理规则测试）
- Modify: `app/src/main/java/com/zhijian/schedule/update/ResumableDownload.kt`（追加实现）

- [ ] **Step 1: 写失败的测试**

在 `ResumableDownloadTest.kt` 的 `private fun tempFile` 之前追加：

```kotlin
    // ---------- 清理规则 ----------

    @Test
    fun versionOfStripsKnownSuffixes() {
        assertEquals("1.0.2", ResumableDownload.versionOf("1.0.2.apk"))
        assertEquals("1.0.2", ResumableDownload.versionOf("1.0.2.apk.part"))
        assertEquals("1.0.2", ResumableDownload.versionOf("1.0.2.apk.meta"))
        assertEquals("1.0", ResumableDownload.versionOf("1.0.apk"))
    }

    @Test
    fun versionOfRejectsUnknownOrNonNumericNames() {
        assertNull(ResumableDownload.versionOf("readme.txt"))
        assertNull(ResumableDownload.versionOf(".apk"))
        assertNull(ResumableDownload.versionOf("backup.apk.bak"))
        assertNull(ResumableDownload.versionOf("1.0.2-beta.apk"))
        assertNull(ResumableDownload.versionOf("v1.0.2.apk"))
    }

    @Test
    fun compareNumericVersionComparesNumericallyNotLexically() {
        assertEquals(0, ResumableDownload.compareNumericVersion("1.0.2", "1.0.2"))
        assertEquals(0, ResumableDownload.compareNumericVersion("1.0", "1.0.0"))
        assertEquals(-1, ResumableDownload.compareNumericVersion("1.0.9", "1.0.10"))
        assertEquals(1, ResumableDownload.compareNumericVersion("2.0.0", "1.9.9"))
        assertEquals(1, ResumableDownload.compareNumericVersion("1.1", "1.0.9"))
    }

    @Test
    fun shouldCleanRemovesAlreadyInstalledVersions() {
        assertTrue(ResumableDownload.shouldClean("1.0.2.apk", "1.0.2"))
        assertTrue(ResumableDownload.shouldClean("1.0.1.apk.part", "1.0.2"))
        assertTrue(ResumableDownload.shouldClean("1.0.1.apk.meta", "1.0.2"))
        assertTrue(ResumableDownload.shouldClean("1.0.9.apk", "1.0.10"))
    }

    @Test
    fun shouldCleanKeepsNewerAndUnknownFiles() {
        assertEquals(false, ResumableDownload.shouldClean("1.0.3.apk", "1.0.2"))
        assertEquals(false, ResumableDownload.shouldClean("1.0.3.apk.part", "1.0.2"))
        assertEquals(false, ResumableDownload.shouldClean("2.0.0.apk", "1.9.9"))
        assertEquals(false, ResumableDownload.shouldClean("random.txt", "1.0.2"))
        assertEquals(false, ResumableDownload.shouldClean("1.0.2-beta.apk", "1.0.2"))
    }
```

新建 `app/src/test/java/com/zhijian/schedule/DownloadProgressTest.kt`：

```kotlin
package com.zhijian.schedule

import com.zhijian.schedule.core.update.ProgressThrottle
import com.zhijian.schedule.core.update.SpeedMeter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadProgressTest {

    @Test
    fun speedIsNullUntilTwoSamples() {
        val meter = SpeedMeter()
        assertNull(meter.bytesPerSecond())
        meter.record(0L, 0L)
        assertNull(meter.bytesPerSecond())
    }

    @Test
    fun speedIsNullWhenSamplesShareTimestamp() {
        val meter = SpeedMeter()
        meter.record(1_000L, 0L)
        meter.record(1_000L, 500L)
        assertNull(meter.bytesPerSecond())
    }

    @Test
    fun speedUsesOnlySamplesInsideWindow() {
        val meter = SpeedMeter(windowMillis = 2_000L)
        meter.record(0L, 0L)
        meter.record(1_000L, 1_000L)
        meter.record(3_000L, 3_000L) // 窗口下限 1000，最早那个点被挤出去
        // 窗口内只剩 (1000,1000) 与 (3000,3000)：2 秒 2000 字节
        assertEquals(1_000L, meter.bytesPerSecond())
    }

    @Test
    fun speedNeverDividesByZero() {
        val meter = SpeedMeter()
        meter.record(5_000L, 100L)
        meter.record(5_000L, 100L)
        assertNull(meter.bytesPerSecond())
    }

    @Test
    fun etaIsNullWithoutTotalOrSpeed() {
        val meter = SpeedMeter()
        meter.record(0L, 0L)
        meter.record(1_000L, 1_000L)
        assertNull(meter.etaSeconds(1_000L, null))
        assertNull(SpeedMeter().etaSeconds(0L, 5_000L))
    }

    @Test
    fun etaRoundsUpAndClampsAtZero() {
        val meter = SpeedMeter()
        meter.record(0L, 0L)
        meter.record(1_000L, 1_000L) // 1000 B/s
        assertEquals(4L, meter.etaSeconds(1_000L, 5_000L))
        assertEquals(1L, meter.etaSeconds(500L, 1_001L))
        assertEquals(0L, meter.etaSeconds(9_000L, 5_000L))
    }

    @Test
    fun etaIsNullWhenSpeedIsZero() {
        val meter = SpeedMeter()
        meter.record(0L, 0L)
        meter.record(1_000L, 0L)
        assertNull(meter.etaSeconds(0L, 5_000L))
    }

    @Test
    fun resetClearsSamples() {
        val meter = SpeedMeter()
        meter.record(0L, 0L)
        meter.record(1_000L, 1_000L)
        meter.reset()
        assertNull(meter.bytesPerSecond())
    }

    @Test
    fun throttleAllowsFirstUpdate() {
        assertTrue(ProgressThrottle().shouldRefresh(0L, 1))
    }

    @Test
    fun throttleSkipsUnchangedPercent() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldRefresh(0L, 1))
        assertEquals(false, throttle.shouldRefresh(10_000L, 1))
    }

    @Test
    fun throttleSkipsUpdatesInsideInterval() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldRefresh(0L, 1))
        assertEquals(false, throttle.shouldRefresh(100L, 2))
        assertTrue(throttle.shouldRefresh(500L, 2))
    }

    @Test
    fun throttleRefreshesIndeterminateProgressOnTimeOnly() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldRefresh(0L, null))
        assertEquals(false, throttle.shouldRefresh(200L, null))
        assertTrue(throttle.shouldRefresh(500L, null))
    }

    @Test
    fun throttleForceBypassesBothConditions() {
        val throttle = ProgressThrottle()
        assertTrue(throttle.shouldRefresh(0L, 1))
        assertTrue(throttle.shouldRefresh(1L, 1, force = true))
        assertEquals(false, throttle.shouldRefresh(2L, 1))
        assertTrue(throttle.shouldRefresh(502L, 2))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.DownloadProgressTest --console=plain"
```

预期：`BUILD FAILED`，`Unresolved reference: ProgressThrottle`。

- [ ] **Step 3: 写最小实现**

在 `ResumableDownload.kt` 里，`etaText` 之后、object 收尾 `}` 之前插入：

```kotlin
    // ---------- 文件清理 ----------

    /** `<version>.apk[.part|.meta]` 里的 `<version>`；后缀不认识或版本段不是纯数字时返回 null */
    fun versionOf(fileName: String): String? {
        val suffix = FILE_SUFFIXES.firstOrNull { fileName.endsWith(it) } ?: return null
        val version = fileName.removeSuffix(suffix)
        if (version.isEmpty()) return null
        if (version.split(".").any { it.isEmpty() || it.toIntOrNull() == null }) return null
        return version
    }

    /** 纯数字逐段比较（缺失段视为 0）：a<b 返回 -1，相等返回 0，a>b 返回 1 */
    fun compareNumericVersion(a: String, b: String): Int {
        val left = a.split(".").map { it.toIntOrNull() ?: 0 }
        val right = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrElse(index) { 0 }
            val y = right.getOrElse(index) { 0 }
            if (x != y) return if (x < y) -1 else 1
        }
        return 0
    }

    /**
     * 启动清理判据：文件名是本应用写下的更新文件，且版本 <= 当前已安装版本（已经装过，留着没用）。
     * 认不出来的文件名一律返回 false —— 宁可留着也不误删用户的东西。
     */
    fun shouldClean(fileName: String, currentVersion: String): Boolean {
        val version = versionOf(fileName) ?: return false
        return compareNumericVersion(version, currentVersion) <= 0
    }
```

在文件末尾（`VerifyResult` 之后）追加：

```kotlin
/**
 * 滑动窗口速率估算：只保留窗口内的采样点。
 * 样本不足（不足 2 个点，或两点时间相同）时返回 null —— UI 显示不确定进度，不显示假速度。
 */
class SpeedMeter(private val windowMillis: Long = 5_000L) {

    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun reset() = samples.clear()

    fun record(timestampMillis: Long, totalBytes: Long) {
        samples.addLast(timestampMillis to totalBytes)
        val floor = timestampMillis - windowMillis
        while (samples.size > 2 && samples.first().first < floor) samples.removeFirst()
    }

    fun bytesPerSecond(): Long? {
        if (samples.size < 2) return null
        val (firstAt, firstBytes) = samples.first()
        val (lastAt, lastBytes) = samples.last()
        val elapsed = lastAt - firstAt
        if (elapsed <= 0L) return null
        return ((lastBytes - firstBytes) * 1000L) / elapsed
    }

    /** 剩余秒数（向上取整）；total 为 null 或速度不可用时返回 null */
    fun etaSeconds(downloadedBytes: Long, totalBytes: Long?): Long? {
        val total = totalBytes ?: return null
        val speed = bytesPerSecond()?.takeIf { it > 0 } ?: return null
        val remaining = (total - downloadedBytes).coerceAtLeast(0L)
        return (remaining + speed - 1) / speed
    }
}

/**
 * 通知栏进度节流：距上次刷新 >= [minIntervalMillis] **且**整数百分比变化时才返回 true。
 * [percent] 为 null（响应没有长度信息）时只按时间节流；[force] 用于结束时无条件刷新最终态。
 */
class ProgressThrottle(private val minIntervalMillis: Long = 500L) {

    private var lastAtMillis = Long.MIN_VALUE
    private var lastPercent: Int? = null

    fun shouldRefresh(timestampMillis: Long, percent: Int?, force: Boolean = false): Boolean {
        if (force) {
            lastAtMillis = timestampMillis
            lastPercent = percent
            return true
        }
        if (percent != null && percent == lastPercent) return false
        if (lastAtMillis != Long.MIN_VALUE && timestampMillis - lastAtMillis < minIntervalMillis) return false
        lastAtMillis = timestampMillis
        lastPercent = percent
        return true
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.ResumableDownloadTest --console=plain"
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.DownloadProgressTest --console=plain"
```

预期：两条都 `BUILD SUCCESSFUL`；ResumableDownloadTest 33 个用例、DownloadProgressTest 13 个用例全过。

- [ ] **Step 5: 提交**

```bat
git -C "D:\课程表" add app/src/main/java/com/zhijian/schedule/update/ResumableDownload.kt app/src/test/java/com/zhijian/schedule/ResumableDownloadTest.kt app/src/test/java/com/zhijian/schedule/DownloadProgressTest.kt
git -C "D:\课程表" commit -m "新增更新残留清理规则与通知节流（纯逻辑 + 单测）"
```

---

### Task 4: 更新检查补上安装包大小与官方摘要

**Files:**
- Modify: `app/src/main/java/com/zhijian/schedule/update/UpdateChecker.kt`

- [ ] **Step 1: 扩展数据模型**

把 `UpdateChecker.kt` 的 `data class UpdateInfo`（23-28 行）替换为：

```kotlin
    data class UpdateInfo(
        val version: String,
        val pageUrl: String,
        val apkUrl: String?,
        /** assets[].size：下载前展示大小，同时作为进度分母的兜底 */
        val apkSize: Long?,
        /** assets[].digest（"sha256:..."）：下载完成后的完整性校验；旧 Release 可能没有 */
        val apkDigest: String?,
        val notes: String,
    )
```

- [ ] **Step 2: 从同一个 asset 一起解析**

把 `check()` 里解析 `apkUrl` 的那段（47-55 行）替换为：

```kotlin
                val apkAsset = (root["assets"] as? JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    ?.firstOrNull { asset ->
                        asset["name"]?.jsonPrimitive?.content?.endsWith(".apk", ignoreCase = true) == true
                    }
                val apkUrl = apkAsset?.get("browser_download_url")?.jsonPrimitive?.content
                val apkSize = apkAsset?.get("size")?.jsonPrimitive?.longOrNull
                val apkDigest = apkAsset?.get("digest")?.jsonPrimitive?.contentOrNull
                if (isNewer(version, BuildConfig.VERSION_NAME)) {
                    UpdateInfo(
                        version = version,
                        pageUrl = pageUrl,
                        apkUrl = apkUrl,
                        apkSize = apkSize,
                        apkDigest = apkDigest,
                        notes = notes,
                    )
                } else null
```

并在 import 区补：

```kotlin
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
```

> 实测 v1.0.2 的返回值：`assets[0].size = 46920382`、`assets[0].digest = "sha256:d3dba9255114fb43cbf8933e3ab2fcf060f5d19edd667bb50d9481ed33193214"`。`digest` 在旧 Release 上可能缺失，所以按可空处理。

- [ ] **Step 3: 编译确认**

```bat
cmd /c ".tmp\run-tests.bat :app:compileDebugKotlin --console=plain"
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 4: 跑全量单测确认没回归**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --console=plain"
```

预期：`BUILD SUCCESSFUL`，12 个测试类 / 123 个用例，0 失败。

- [ ] **Step 5: 提交**

```bat
git -C "D:\课程表" add app/src/main/java/com/zhijian/schedule/update/UpdateChecker.kt
git -C "D:\课程表" commit -m "更新检查补上安装包大小与官方摘要字段（校验与进度分母用）"
```

---

### Task 5: 下载专用的 OkHttpClient

**Files:**
- Create: `app/src/main/java/com/zhijian/schedule/di/DownloadClient.kt`
- Modify: `app/src/main/java/com/zhijian/schedule/di/AppModule.kt`

- [ ] **Step 1: 定义限定符**

新建 `app/src/main/java/com/zhijian/schedule/di/DownloadClient.kt`：

```kotlin
package com.zhijian.schedule.di

import javax.inject.Qualifier

/**
 * 标记「下载用」OkHttpClient：总时长不设上限、读写超时放宽、不共享 WebView cookie。
 * 与未限定的通用客户端（15s/20s，用于教务抓取与 API 请求）区分开。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DownloadClient
```

- [ ] **Step 2: 提供实例**

在 `AppModule.kt` 的 `provideOkHttpClient()`（80-87 行）之后追加：

```kotlin
    /**
     * 下载专用客户端。通用客户端的 readTimeout=20s 对 46 MB 的安装包不够用；
     * 且下载不该共享 WebView 的 cookie jar。
     * readTimeout 是「单次读取的间隔上限」，不是整个下载时长。
     */
    @Provides
    @Singleton
    @DownloadClient
    fun provideDownloadClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()
```

- [ ] **Step 3: 编译确认**

```bat
cmd /c ".tmp\run-tests.bat :app:compileDebugKotlin --console=plain"
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 4: 提交**

```bat
git -C "D:\课程表" add app/src/main/java/com/zhijian/schedule/di/DownloadClient.kt app/src/main/java/com/zhijian/schedule/di/AppModule.kt
git -C "D:\课程表" commit -m "为更新下载提供独立的 OkHttpClient（不限总时长、放宽读超时）"
```

---

### Task 6: 更新文件的目录布局与元数据读写

**Files:**
- Create: `app/src/main/java/com/zhijian/schedule/update/UpdateStore.kt`

- [ ] **Step 1: 写实现**

新建 `app/src/main/java/com/zhijian/schedule/update/UpdateStore.kt`：

```kotlin
package com.zhijian.schedule.core.update

import android.content.Context
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * `filesDir/updates/` 的目录布局、元数据读写与占用统计：
 *
 * ```
 * <version>.apk.part   下载中的数据（用 RandomAccessFile 追加）
 * <version>.apk.meta   断点元数据（PartMeta 的 JSON）
 * <version>.apk        校验通过、待安装
 * ```
 *
 * 全部落在内部存储，不需要任何存储权限。只做文件系统与 JSON，不碰网络与状态。
 */
@Singleton
class UpdateStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {

    /** 更新文件目录；首次访问时创建 */
    val dir: File
        get() = File(context.filesDir, DIR).apply { if (!isDirectory) mkdirs() }

    fun partFile(version: String): File = File(dir, version + SUFFIX_PART)

    fun metaFile(version: String): File = File(dir, version + SUFFIX_META)

    fun apkFile(version: String): File = File(dir, version + SUFFIX_APK)

    /** 已下载的分片字节数；没有分片时返回 0 */
    fun partBytes(version: String): Long = partFile(version).let { if (it.isFile) it.length() else 0L }

    /**
     * 读断点元数据。文件缺失、JSON 损坏、版本对不上时返回 null，调用方一律按「没有断点」处理，
     * 也就是不带 Range 重新下 —— 绝不因为一个坏 meta 就卡住用户。
     */
    fun readMeta(version: String): PartMeta? {
        val file = metaFile(version)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<PartMeta>(file.readText()) }
            .getOrNull()
            ?.takeIf { it.version == version }
    }

    /** 写断点元数据；写失败只当没有断点，不影响这次下载继续跑 */
    fun writeMeta(meta: PartMeta) {
        runCatching { metaFile(meta.version).writeText(json.encodeToString(meta)) }
    }

    fun deleteMeta(version: String) {
        metaFile(version).delete()
    }

    /** 删除某个版本的分片与元数据（待安装的 `.apk` 不动） */
    fun deleteAll(version: String) {
        partFile(version).delete()
        metaFile(version).delete()
    }

    /** `updates/` 当前占用的总字节数（含待安装安装包与断点） */
    fun cacheBytes(): Long = dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    /** 清空 `updates/` 的全部内容（保留目录），返回删除的文件数 */
    fun clearCache(): Int {
        var deleted = 0
        dir.listFiles()?.forEach { if (it.delete()) deleted++ }
        return deleted
    }

    /** 应用启动时清理「版本 <= 当前已安装版本」的残留，返回删除的文件数 */
    fun cleanupOutdated(currentVersion: String): Int {
        var deleted = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile &&
                ResumableDownload.shouldClean(file.name, currentVersion) &&
                file.delete()
            ) {
                deleted++
            }
        }
        return deleted
    }

    /** 剩余可用空间（字节）。查询失败时返回 Long.MAX_VALUE，避免把查询失败误判成「空间不足」 */
    fun freeSpaceBytes(): Long =
        runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)

    private companion object {
        const val DIR = "updates"
        const val SUFFIX_PART = ".apk.part"
        const val SUFFIX_META = ".apk.meta"
        const val SUFFIX_APK = ".apk"
    }
}
```

- [ ] **Step 2: 编译确认**

```bat
cmd /c ".tmp\run-tests.bat :app:compileDebugKotlin --console=plain"
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 3: 提交**

```bat
git -C "D:\课程表" add app/src/main/java/com/zhijian/schedule/update/UpdateStore.kt
git -C "D:\课程表" commit -m "新增 UpdateStore：更新安装包的目录布局、断点元数据与占用统计"
```

---

### Task 7: 下载状态与断点续传下载体

**Files:**
- Create: `app/src/main/java/com/zhijian/schedule/update/ApkDownloader.kt`

- [ ] **Step 1: 写实现**

新建 `app/src/main/java/com/zhijian/schedule/update/ApkDownloader.kt`：

```kotlin
package com.zhijian.schedule.core.update

import android.content.Context
import com.zhijian.schedule.BuildConfig
import com.zhijian.schedule.di.DownloadClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** 一次下载请求：设置页构造，前台服务通过 Intent extras 传递 */
data class DownloadRequest(
    val version: String,
    val url: String,
    val sizeBytes: Long?,
    val digest: String?,
)

/** 更新下载的状态；唯一事实来源是 [ApkDownloader] */
sealed interface DownloadState {
    data object Idle : DownloadState

    data class Running(
        val version: String,
        val downloadedBytes: Long,
        val totalBytes: Long?,
        val bytesPerSecond: Long,
        /** null = 样本不足或没有总长，UI 不显示剩余时间 */
        val etaSeconds: Long?,
    ) : DownloadState

    /** 用户取消或服务被回收：分片保留，可续传 */
    data class Paused(
        val version: String,
        val downloadedBytes: Long,
        val totalBytes: Long?,
    ) : DownloadState

    /** 校验通过、待安装 */
    data class Ready(val version: String, val file: File, val sizeBytes: Long) : DownloadState

    /** 失败；canResume 为 true 时卡片给「重试」（从分片续传），否则给「重新下载」 */
    data class Failed(val version: String, val reason: String, val canResume: Boolean) : DownloadState
}

/**
 * 更新下载的唯一事实来源与下载协程体。
 * 状态放在 Singleton 里（UI 与服务共用同一个实例），但**下载协程由前台服务启动**，
 * 所以下载的存活期 = 服务的存活期；用户离开设置页不影响下载。
 */
@Singleton
class ApkDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    @DownloadClient private val client: OkHttpClient,
    private val store: UpdateStore,
) {

    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private val cancelRequested = AtomicBoolean(false)

    @Volatile
    private var activeCall: Call? = null

    /**
     * 开始或继续下载：自动读 `.part` 与 `.meta` 决定是否带 `Range`/`If-Range` 续传。
     * 必须在调用方（前台服务）的 scope 中执行。
     */
    suspend fun download(request: DownloadRequest) {
        cancelRequested.set(false)
        val result = withContext(Dispatchers.IO) {
            try {
                runDownload(request)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                if (cancelRequested.get()) paused(request.version)
                else DownloadState.Failed(request.version, "网络中断（${e.message ?: "IO 错误"}）", canResume = true)
            } catch (t: Throwable) {
                DownloadState.Failed(request.version, "下载出错（${t.message ?: t::class.simpleName}）", canResume = true)
            }
        }
        _state.value = result
    }

    /** 用户取消或服务被回收：中止网络读取并把状态落到 Paused，分片保留以便续传 */
    fun cancel() {
        cancelRequested.set(true)
        runCatching { activeCall?.cancel() }
        val current = _state.value
        if (current is DownloadState.Running) _state.value = paused(current.version)
    }

    /** 前台服务超时（Android 15+ 的 dataSync 累计上限）：中止并落到 Failed，分片仍然可续传 */
    fun timeout() {
        cancelRequested.set(true)
        runCatching { activeCall?.cancel() }
        val current = _state.value
        if (current is DownloadState.Running) {
            _state.value = DownloadState.Failed(
                current.version,
                "系统结束下载（前台服务超时），可继续下载",
                canResume = true,
            )
        }
    }

    /** 某个版本已落盘的分片字节数 */
    fun downloadedBytes(version: String): Long = store.partBytes(version)

    /** `filesDir/updates/` 当前占用字节数 */
    fun cacheBytes(): Long = store.cacheBytes()

    /** 清空下载缓存，返回删除的文件数 */
    fun clearCache(): Int = store.clearCache()

    /** 应用启动时清理「版本 <= 当前已安装版本」的残留，返回删除的文件数 */
    fun cleanupOutdated(currentVersion: String): Int = store.cleanupOutdated(currentVersion)

    /**
     * 检查到新版本后，用磁盘上已有的东西覆盖状态：已校验的 `.apk` → Ready，未完成的分片 → Paused。
     * 没有这一步的话，进程重启后点「继续下载」会把已经下好的 46 MB 又从头下一遍。下载进行中不覆盖。
     */
    fun restoreFromDisk(version: String, sizeBytes: Long?) {
        if (_state.value is DownloadState.Running) return
        val apk = store.apkFile(version)
        if (apk.isFile && apk.length() > 0L && (sizeBytes == null || apk.length() == sizeBytes)) {
            _state.value = DownloadState.Ready(version, apk, apk.length())
            return
        }
        val bytes = store.partBytes(version)
        if (bytes > 0L) {
            _state.value = DownloadState.Paused(
                version = version,
                downloadedBytes = bytes,
                totalBytes = sizeBytes ?: store.readMeta(version)?.totalBytes?.takeIf { it > 0 },
            )
        }
    }

    // ---------- 下载体 ----------

    private sealed interface Outcome {
        data class Done(val state: DownloadState) : Outcome

        /** 需要丢掉分片、从头再来一次（416 损坏，或 206 的起点与现有分片对不上） */
        data object RetryFromScratch : Outcome
    }

    private fun paused(version: String): DownloadState = DownloadState.Paused(
        version = version,
        downloadedBytes = store.partBytes(version),
        totalBytes = store.readMeta(version)?.totalBytes?.takeIf { it > 0 },
    )

    private suspend fun runDownload(request: DownloadRequest): DownloadState {
        var restarted = false
        while (true) {
            coroutineContext.ensureActive()
            if (cancelRequested.get()) return paused(request.version)

            val part = store.partFile(request.version)
            val meta = store.readMeta(request.version)
            val partBytes = if (part.isFile) part.length() else 0L
            val knownTotal = request.sizeBytes?.takeIf { it > 0 }
                ?: meta?.totalBytes?.takeIf { it > 0 }

            // 磁盘预检：只按「还差多少」判断，续传时不再要求一整份的空闲空间
            val missing = (knownTotal ?: partBytes) - partBytes
            if (missing > 0 && store.freeSpaceBytes() < missing) {
                return DownloadState.Failed(
                    request.version,
                    "存储空间不足，还需 ${ResumableDownload.formatBytes(missing)}",
                    canResume = false,
                )
            }

            val builder = Request.Builder()
                .url(request.url)
                // 不加这行的话 OkHttp 会透明解压，Content-Length 变成压缩后大小，进度分母就错了
                .header("Accept-Encoding", "identity")
                .header("User-Agent", USER_AGENT)
            if (partBytes > 0L && meta != null && meta.url == request.url) {
                builder.header("Range", "bytes=$partBytes-")
                // 交给服务端判断「资源没变才给 206」，能挡住资产被替换导致的新旧分片拼接损坏
                meta.etag?.let { builder.header("If-Range", it) }
            }

            val call = client.newCall(builder.build())
            activeCall = call
            val response = try {
                call.execute()
            } catch (e: IOException) {
                activeCall = null
                return if (cancelRequested.get()) paused(request.version)
                else DownloadState.Failed(request.version, "网络中断（${e.message ?: "IO 错误"}）", canResume = true)
            }

            val outcome = try {
                response.use { handle(request, it, part, partBytes, knownTotal) }
            } finally {
                activeCall = null
            }

            when (outcome) {
                is Outcome.Done -> return outcome.state
                Outcome.RetryFromScratch -> {
                    if (restarted) {
                        return DownloadState.Failed(request.version, "下载文件损坏，请重新下载", canResume = false)
                    }
                    restarted = true
                    store.deleteAll(request.version)
                }
            }
        }
    }

    private fun handle(
        request: DownloadRequest,
        response: Response,
        part: File,
        partBytes: Long,
        knownTotal: Long?,
    ): Outcome {
        val contentRange = ResumableDownload.parseContentRange(response.header("Content-Range"))
        val decision = ResumableDownload.decideRange(response.code, partBytes, contentRange?.total ?: knownTotal)

        when (decision) {
            is RangeDecision.Fail -> return Outcome.Done(
                DownloadState.Failed(request.version, decision.reason, decision.canResume)
            )
            RangeDecision.AlreadyComplete -> return Outcome.Done(verifyAndFinish(request, part))
            RangeDecision.Corrupt -> return Outcome.RetryFromScratch
            else -> Unit
        }

        // 206 的起点必须与已有分片对齐，否则拼出来的 APK 是坏的
        if (decision is RangeDecision.Append && contentRange != null && contentRange.start != partBytes) {
            return Outcome.RetryFromScratch
        }

        val appendFrom = (decision as? RangeDecision.Append)?.offset ?: 0L
        if (decision is RangeDecision.Restart) {
            // 服务端忽略了 Range，或 If-Range 判定资源已变：丢掉分片从头写这一次响应
            store.deleteMeta(request.version)
            part.delete()
        }

        val body = response.body
            ?: return Outcome.Done(DownloadState.Failed(request.version, "响应无内容，请重试", canResume = true))
        val total = ResumableDownload.resolveTotalBytes(body.contentLength(), contentRange, knownTotal)

        // 先落元数据再写数据：中途被杀也能靠 .meta 续传
        store.writeMeta(
            PartMeta(
                version = request.version,
                url = request.url,
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
                totalBytes = total ?: 0L,
                digest = request.digest,
            )
        )

        val meter = SpeedMeter()
        var written = appendFrom
        meter.record(System.currentTimeMillis(), written)
        _state.value = DownloadState.Running(request.version, written, total, 0L, null)

        RandomAccessFile(part, "rw").use { file ->
            if (appendFrom == 0L) file.setLength(0L)
            file.seek(appendFrom)
            val buffer = ByteArray(BUFFER_BYTES)
            body.byteStream().use { input ->
                while (true) {
                    if (cancelRequested.get()) return Outcome.Done(paused(request.version))
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    file.write(buffer, 0, read)
                    written += read
                    meter.record(System.currentTimeMillis(), written)
                    _state.value = DownloadState.Running(
                        version = request.version,
                        downloadedBytes = written,
                        totalBytes = total,
                        bytesPerSecond = meter.bytesPerSecond() ?: 0L,
                        etaSeconds = meter.etaSeconds(written, total),
                    )
                }
            }
            file.fd.sync()
        }

        if (total != null && written != total) {
            return Outcome.Done(
                DownloadState.Failed(
                    request.version,
                    "下载未完成（已收到 ${ResumableDownload.formatBytes(written)}），可继续下载",
                    canResume = true,
                )
            )
        }
        return Outcome.Done(verifyAndFinish(request, part))
    }

    /** 校验通过（长度 + 可选哈希）才重命名成 `.apk` 并删除 `.meta`；任何一步不过都不改名 */
    private fun verifyAndFinish(request: DownloadRequest, part: File): DownloadState {
        val expectedBytes = request.sizeBytes?.takeIf { it > 0 }
            ?: store.readMeta(request.version)?.totalBytes?.takeIf { it > 0 }
        return when (ResumableDownload.verifyFile(part, expectedBytes, request.digest)) {
            VerifyResult.Ok -> {
                val apk = store.apkFile(request.version)
                apk.delete()
                if (part.renameTo(apk)) {
                    store.deleteMeta(request.version)
                    DownloadState.Ready(request.version, apk, apk.length())
                } else {
                    store.deleteAll(request.version)
                    DownloadState.Failed(request.version, "安装包保存失败，请重试", canResume = false)
                }
            }
            // 长度不符多半是分片没写完，保留分片让用户续传
            VerifyResult.LengthMismatch -> DownloadState.Failed(
                request.version,
                "下载未完成（已收到 ${ResumableDownload.formatBytes(part.length())}），可继续下载",
                canResume = true,
            )
            VerifyResult.DigestMismatch -> {
                store.deleteAll(request.version)
                DownloadState.Failed(request.version, "安装包校验失败，请重新下载", canResume = false)
            }
        }
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024
        val USER_AGENT = "ZhiJianSchedule/" + BuildConfig.VERSION_NAME
    }
}
```

- [ ] **Step 2: 编译确认**

```bat
cmd /c ".tmp\run-tests.bat :app:compileDebugKotlin --console=plain"
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 3: 提交**

```bat
git -C "D:\课程表" add app/src/main/java/com/zhijian/schedule/update/ApkDownloader.kt
git -C "D:\课程表" commit -m "新增 ApkDownloader：下载状态唯一来源与断点续传下载体"
```

---

### Task 8: 进度通知与 dataSync 前台服务

> 这两个文件通过「通知里的取消 action」互相引用（`UpdateNotification.ACTION_CANCEL`），必须一起落地，否则中间状态编译不过。

**Files:**
- Create: `app/src/main/java/com/zhijian/schedule/update/UpdateNotification.kt`
- Create: `app/src/main/java/com/zhijian/schedule/update/ApkDownloadService.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: 加字符串**

在 `app/src/main/res/values/strings.xml` 的 `<string name="capsule_service_label">课程实时胶囊</string>` 之后插入：

```xml
    <string name="channel_update_name">应用更新</string>
    <string name="channel_update_desc">下载新版本安装包的进度</string>
    <string name="update_action_cancel">取消下载</string>
    <string name="update_service_label">更新下载</string>
```

- [ ] **Step 2: 写通知**

新建 `app/src/main/java/com/zhijian/schedule/update/UpdateNotification.kt`：

```kotlin
package com.zhijian.schedule.core.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zhijian.schedule.MainActivity
import com.zhijian.schedule.R

/**
 * 更新下载的通知：dataSync 前台服务的进度通知（带「取消下载」动作）+ 一条完成通知。
 * 渠道与课程胶囊（capsule）隔离，用户关掉胶囊不会连带丢掉下载进度。
 */
object UpdateNotification {

    const val CH_UPDATE = "update"
    const val NOTIFICATION_ID_PROGRESS = 3101
    const val NOTIFICATION_ID_DONE = 3102
    const val ACTION_CANCEL = "com.zhijian.schedule.core.update.action.CANCEL"

    /** IMPORTANCE_LOW：不响铃不震动，只在通知栏安静走进度 */
    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CH_UPDATE,
                context.getString(R.string.channel_update_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.channel_update_desc) }
        )
    }

    /** 进度通知；percent 为 null 时显示不确定进度（响应没有长度信息） */
    fun buildProgress(
        context: Context,
        version: String,
        downloadedBytes: Long,
        totalBytes: Long?,
        percent: Int?,
        etaSeconds: Long?,
    ): Notification {
        val text = if (totalBytes != null) {
            val tail = ResumableDownload.etaText(etaSeconds)?.let { " · $it" }.orEmpty()
            "${ResumableDownload.formatBytes(downloadedBytes)} / " +
                "${ResumableDownload.formatBytes(totalBytes)}$tail"
        } else {
            "已下载 ${ResumableDownload.formatBytes(downloadedBytes)}"
        }
        return NotificationCompat.Builder(context, CH_UPDATE)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("正在下载更新 v$version")
            .setContentText(text)
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(R.string.update_action_cancel), cancelDownloadIntent(context))
            .build()
    }

    /** 更新前台服务那条通知（同一个 id，重复 notify 会原地替换） */
    fun postProgress(context: Context, notification: Notification) {
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_PROGRESS, notification)
        }
    }

    /**
     * 下载完成、校验通过后的通知。点按只回 App —— 安装前的「未知来源」引导留在设置页卡片里做，
     * 免得在通知里跳系统页体验割裂。
     */
    fun postDone(context: Context, version: String) {
        val notification = NotificationCompat.Builder(context, CH_UPDATE)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("更新已下载")
            .setContentText("点按返回应用完成安装（v$version）")
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID_DONE, notification)
        }
    }

    fun cancelProgress(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_PROGRESS) }
    }

    fun cancelDone(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID_DONE) }
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context,
            NOTIFICATION_ID_PROGRESS,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun cancelDownloadIntent(context: Context): PendingIntent =
        PendingIntent.getService(
            context,
            NOTIFICATION_ID_PROGRESS + 1,
            Intent(context, ApkDownloadService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
```

- [ ] **Step 3: 写前台服务**

新建 `app/src/main/java/com/zhijian/schedule/update/ApkDownloadService.kt`：

```kotlin
package com.zhijian.schedule.core.update

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 更新下载的 dataSync 前台服务：把下载协程的存活期绑在服务上，并在通知栏显示进度。
 * 状态本身在 [ApkDownloader]（Singleton），服务只负责「保活 + 渲染通知」。
 * `START_NOT_STICKY`：不做静默重启，分片已经落盘，用户重试即可。
 */
@AndroidEntryPoint
class ApkDownloadService : Service() {

    @Inject
    lateinit var downloader: ApkDownloader

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val throttle = ProgressThrottle()

    private var job: Job? = null
    private var watcher: Job? = null
    private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        UpdateNotification.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == UpdateNotification.ACTION_CANCEL) {
            downloader.cancel()
            return START_NOT_STICKY
        }
        val request = intent.toDownloadRequest()
        if (request == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) {
            // 同一时刻只允许一个下载：重复启动只补上前台通知，满足前台服务的契约
            runCatching { goForeground(statusNotification(request)) }
            return START_NOT_STICKY
        }

        if (!runCatching { goForeground(statusNotification(request)) }.isSuccess) {
            // Android 12+ 后台启动前台服务受限（例如点按后立刻切走）：直接收尾，状态不变由用户重试
            stopSelf()
            return START_NOT_STICKY
        }

        watcher = scope.launch { downloader.state.collect { renderProgress(it) } }
        job = scope.launch {
            downloader.download(request)
            finish()
        }
        return START_NOT_STICKY
    }

    /** Android 15+ 的 dataSync 前台服务有累计时长上限，到点必须收尾，否则系统回调会抛异常 */
    @RequiresApi(35)
    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        downloader.timeout()
        finish()
    }

    override fun onDestroy() {
        // 系统回收（含用户划掉任务）：把 Running 落回 Paused，分片保留
        if (!finished) downloader.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun renderProgress(state: DownloadState) {
        if (state !is DownloadState.Running) return
        val percent = percentOf(state)
        if (!throttle.shouldRefresh(System.currentTimeMillis(), percent)) return
        UpdateNotification.postProgress(this, notificationFor(state, percent))
    }

    private fun finish() {
        if (finished) return
        finished = true
        (downloader.state.value as? DownloadState.Ready)?.let { UpdateNotification.postDone(this, it.version) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, UpdateNotification.NOTIFICATION_ID_PROGRESS, notification, type)
    }

    private fun statusNotification(request: DownloadRequest): Notification =
        UpdateNotification.buildProgress(
            context = this,
            version = request.version,
            downloadedBytes = downloader.downloadedBytes(request.version),
            totalBytes = request.sizeBytes,
            percent = null,
            etaSeconds = null,
        )

    private fun notificationFor(state: DownloadState.Running, percent: Int?): Notification =
        UpdateNotification.buildProgress(
            context = this,
            version = state.version,
            downloadedBytes = state.downloadedBytes,
            totalBytes = state.totalBytes,
            percent = percent,
            etaSeconds = state.etaSeconds,
        )

    private fun percentOf(state: DownloadState.Running): Int? =
        state.totalBytes?.takeIf { it > 0 }
            ?.let { ((state.downloadedBytes * 100) / it).toInt().coerceIn(0, 100) }

    companion object {
        private const val EXTRA_VERSION = "update_version"
        private const val EXTRA_URL = "update_url"
        private const val EXTRA_SIZE = "update_size"
        private const val EXTRA_DIGEST = "update_digest"

        /** 启动下载。必须在可见界面上调用（Android 12+ 不允许后台启动前台服务） */
        fun start(context: Context, request: DownloadRequest) {
            val intent = Intent(context, ApkDownloadService::class.java)
                .putExtra(EXTRA_VERSION, request.version)
                .putExtra(EXTRA_URL, request.url)
                .putExtra(EXTRA_SIZE, request.sizeBytes ?: -1L)
                .putExtra(EXTRA_DIGEST, request.digest)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 通知栏「取消下载」：服务没在跑时 startService 会被系统拒绝，runCatching 兜住即可 */
        fun cancel(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, ApkDownloadService::class.java).setAction(UpdateNotification.ACTION_CANCEL)
                )
            }
        }

        private fun Intent?.toDownloadRequest(): DownloadRequest? {
            val source = this ?: return null
            val version = source.getStringExtra(EXTRA_VERSION)?.takeIf { it.isNotBlank() } ?: return null
            val url = source.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() } ?: return null
            return DownloadRequest(
                version = version,
                url = url,
                sizeBytes = source.getLongExtra(EXTRA_SIZE, -1L).takeIf { it > 0 },
                digest = source.getStringExtra(EXTRA_DIGEST),
            )
        }
    }
}
```

- [ ] **Step 4: 编译确认**

```bat
cmd /c ".tmp\run-tests.bat :app:compileDebugKotlin --console=plain"
```

预期：`BUILD SUCCESSFUL`。

> 如果 `onTimeout` 报 `Cannot access 'onTimeout'` 或 `Unresolved reference: super`，说明当前 SDK 的 `Service` 没有 1 参数版本，改成只留 `downloader.timeout(); finish()`、删掉 `super.onTimeout(startId)` 这一行即可（其余照旧）。

- [ ] **Step 5: 跑全量单测确认没回归**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --console=plain"
```

预期：`BUILD SUCCESSFUL`，12 个测试类 / 123 个用例，0 失败。

- [ ] **Step 6: 提交**

```bat
git -C "D:\课程表" add app/src/main/java/com/zhijian/schedule/update/UpdateNotification.kt app/src/main/java/com/zhijian/schedule/update/ApkDownloadService.kt app/src/main/res/values/strings.xml
git -C "D:\课程表" commit -m "新增下载通知与 dataSync 前台服务（进度通知带取消动作、超时收尾）"
```

---

### Task 9: 安装链路（FileProvider + 未知来源引导）

**Files:**
- Create: `app/src/main/res/xml/file_paths.xml`
- Create: `app/src/main/java/com/zhijian/schedule/update/ApkInstaller.kt`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: 声明 FileProvider 路径白名单**

新建 `app/src/main/res/xml/file_paths.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <!-- 更新安装包落在内部存储 filesDir/updates/ -->
    <files-path name="updates" path="updates/" />
</paths>
```

- [ ] **Step 2: 写安装器**

新建 `app/src/main/java/com/zhijian/schedule/update/ApkInstaller.kt`：

```kotlin
package com.zhijian.schedule.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把校验通过的 APK 交给系统安装器。
 * minSdk 26：`file://` URI 在 API 24+ 会抛 FileUriExposedException，必须走 FileProvider。
 */
@Singleton
class ApkInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    /** 「安装未知应用」是否已经放行。没放行时安装 Intent 会被系统静默丢掉，所以要先判断 */
    fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    /** 跳「安装未知应用」授权页（带包名，直接落在本应用那一项）；返回后由 ON_RESUME 刷新状态 */
    fun unknownSourcesIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 调起系统安装器；失败信息交给调用方展示 */
    fun install(file: File): Result<Unit> = runCatching {
        require(file.isFile) { "安装包不存在" }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, APK_MIME)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    companion object {
        const val APK_MIME = "application/vnd.android.package-archive"
        const val AUTHORITY_SUFFIX = ".fileprovider"
    }
}
```

- [ ] **Step 3: 改清单**

在 `app/src/main/AndroidManifest.xml` 的 `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />` 之后插入：

```xml
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <!-- 调起系统安装器安装应用内下载的更新包 -->
    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES" />
```

在 `<service android:name=".capsule.LiveCapsuleService" ...>...</service>` 之后插入：

```xml
        <!-- 应用内更新下载（dataSync 前台服务 + 通知栏进度） -->
        <service
            android:name=".update.ApkDownloadService"
            android:exported="false"
            android:foregroundServiceType="dataSync"
            android:label="@string/update_service_label" />

        <!-- 把已校验的 APK 交给系统安装器；minSdk 26 下 file:// 会抛 FileUriExposedException -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>
```

- [ ] **Step 4: 编译确认（含资源与清单合并）**

```bat
cmd /c ".tmp\run-tests.bat :app:assembleDebug --console=plain"
```

预期：`BUILD SUCCESSFUL`。这一步同时验证 `file_paths.xml`、`@string/update_service_label` 与清单语法。

- [ ] **Step 5: 提交**

```bat
git -C "D:\课程表" add app/src/main/res/xml/file_paths.xml app/src/main/java/com/zhijian/schedule/update/ApkInstaller.kt app/src/main/AndroidManifest.xml
git -C "D:\课程表" commit -m "接入 FileProvider 与「安装未知应用」引导，交付安装包给系统安装器"
```

---

### Task 10: 启动时清理旧版本残留

**Files:**
- Modify: `app/src/main/java/com/zhijian/schedule/ZhiJianApp.kt`

- [ ] **Step 1: 注入并调用**

在 `ZhiJianApp.kt` 的 `@Inject lateinit var reminderCoordinator: ReminderCoordinator` 之后追加：

```kotlin
    @Inject
    lateinit var apkDownloader: ApkDownloader
```

并补 import：

```kotlin
import com.zhijian.schedule.core.update.ApkDownloader
```

把 `onCreate` 里的 `appScope.launch { ... }` 改成：

```kotlin
        appScope.launch {
            // 「版本 <= 当前已安装版本」的更新残留（含待安装 APK 与断点）已经没用了
            runCatching { apkDownloader.cleanupOutdated(BuildConfig.VERSION_NAME) }
            repository.ensureInitialized()
            Notifications.ensureChannels(this@ZhiJianApp)
            val settings = settingsStore.settings.first()
            if (settings.autoSyncEnabled && settings.jwUrl.isNotBlank()) {
                syncScheduler.enableDaily()
                syncScheduler.runCatchUpIfNeeded(settings.lastSyncTime)
            }
        }
```

- [ ] **Step 2: 编译确认**

```bat
cmd /c ".tmp\run-tests.bat :app:compileDebugKotlin --console=plain"
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 3: 提交**

```bat
git -C "D:\课程表" add app/src/main/java/com/zhijian/schedule/ZhiJianApp.kt
git -C "D:\课程表" commit -m "应用启动时清理已装版本的更新残留文件"
```

---

### Task 11: 设置页 ViewModel 接线

**Files:**
- Modify: `app/src/main/java/com/zhijian/schedule/feature/settings/SettingsViewModel.kt`

- [ ] **Step 1: 扩 UI 状态**

把 `data class SettingsUiState`（32-44 行）替换为：

```kotlin
data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val timetable: TimetableEntity? = null,
    val sections: List<SectionTimeEntity> = emptyList(),
    val exactAlarmOk: Boolean = true,
    val calendarAvailable: Boolean = false,
    /** 系统是否放行实时活动（流体云）；null = 系统不支持查询 */
    val capsulePromoteAllowed: Boolean? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val updateInfo: UpdateChecker.UpdateInfo? = null,
    val updateChecked: Boolean = false,
    /** 检查更新的失败原因；非空时卡片显示它，不再用顶部横幅 */
    val updateError: String? = null,
    /** 下载状态（唯一事实来源在 ApkDownloader） */
    val download: DownloadState = DownloadState.Idle,
    /** `filesDir/updates/` 当前占用字节数；> 0 时卡片出现「清理下载缓存」 */
    val updateCacheBytes: Long = 0L,
    /** 系统是否放行「安装未知应用」 */
    val canInstallApk: Boolean = true,
)
```

- [ ] **Step 2: 注入新依赖**

把构造函数（47-56 行）替换为：

```kotlin
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    private val settingsStore: SettingsStore,
    private val backupManager: BackupManager,
    private val calendarSyncManager: CalendarSyncManager,
    private val updateChecker: UpdateChecker,
    private val alarmScheduler: AlarmScheduler,
    private val capsuleCoordinator: CapsuleCoordinator,
    private val downloader: ApkDownloader,
    private val apkInstaller: ApkInstaller,
    @ApplicationContext private val context: Context,
) : ViewModel() {
```

补 import：

```kotlin
import com.zhijian.schedule.core.update.ApkDownloadService
import com.zhijian.schedule.core.update.ApkDownloader
import com.zhijian.schedule.core.update.ApkInstaller
import com.zhijian.schedule.core.update.DownloadRequest
import com.zhijian.schedule.core.update.DownloadState
```

- [ ] **Step 3: 观察下载状态**

在 `init` 里第一个 `viewModelScope.launch { ... }` 之后追加：

```kotlin
        viewModelScope.launch {
            downloader.state.collect { state ->
                uiState = uiState.copy(
                    download = state,
                    canInstallApk = apkInstaller.canInstall(),
                )
                // 只在终态重算占盘，避免下载中每个分片都去列目录
                if (state !is DownloadState.Running) refreshUpdateCache()
            }
        }
```

- [ ] **Step 4: 加权限刷新与缓存刷新**

在 `refreshAlarmPermission()` 之后追加：

```kotlin
    /** 从「安装未知应用」授权页返回后刷新放行状态 */
    fun refreshInstallPermission() {
        uiState = uiState.copy(canInstallApk = apkInstaller.canInstall())
    }

    private suspend fun refreshUpdateCache() {
        val bytes = withContext(Dispatchers.IO) { downloader.cacheBytes() }
        uiState = uiState.copy(updateCacheBytes = bytes)
    }
```

- [ ] **Step 5: 重写检查更新并接上下载动作**

把 `checkUpdate()`（202-216 行）替换为：

```kotlin
    fun checkUpdate() {
        viewModelScope.launch {
            uiState = uiState.copy(busy = true, updateChecked = false, updateError = null)
            val result = updateChecker.check()
            val info = result.getOrNull()
            uiState = uiState.copy(
                busy = false,
                updateChecked = true,
                updateInfo = info,
                updateError = result.exceptionOrNull()
                    ?.let { it.message ?: it::class.simpleName ?: "检查更新失败" },
                // 检查结果改用卡片状态行呈现，不再复用顶部横幅
                message = null,
            )
            // 磁盘上要是已经有下好的安装包或断点，直接把状态恢复出来，别又下一遍
            if (info?.apkUrl != null) {
                val version = info.version
                val size = info.apkSize
                withContext(Dispatchers.IO) { downloader.restoreFromDisk(version, size) }
            }
            refreshUpdateCache()
        }
    }

    // ---------- 应用内下载 ----------

    fun downloadUpdate() {
        val info = uiState.updateInfo
        val url = info?.apkUrl
        if (info == null || url.isNullOrBlank()) {
            uiState = uiState.copy(message = "请先检查更新")
            return
        }
        ApkDownloadService.start(
            context,
            DownloadRequest(
                version = info.version,
                url = url,
                sizeBytes = info.apkSize,
                digest = info.apkDigest,
            ),
        )
    }

    fun cancelDownload() {
        ApkDownloadService.cancel(context)
    }

    fun installUpdate() {
        val ready = uiState.download as? DownloadState.Ready ?: return
        if (!apkInstaller.canInstall()) {
            uiState = uiState.copy(message = "请先允许「安装未知应用」，返回后再点安装")
            runCatching { context.startActivity(apkInstaller.unknownSourcesIntent()) }
                .onFailure { uiState = uiState.copy(message = "无法打开系统授权页：${it.message}") }
            return
        }
        apkInstaller.install(ready.file)
            .onFailure { uiState = uiState.copy(message = "无法启动安装：${it.message}") }
    }

    fun clearUpdateCache() {
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) { downloader.clearCache() }
            refreshUpdateCache()
            uiState = uiState.copy(message = if (deleted > 0) "已清理下载缓存" else "没有可清理的缓存")
        }
    }
```

- [ ] **Step 6: 编译确认**

```bat
cmd /c ".tmp\run-tests.bat :app:compileDebugKotlin --console=plain"
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 7: 提交**

```bat
git -C "D:\课程表" add app/src/main/java/com/zhijian/schedule/feature/settings/SettingsViewModel.kt
git -C "D:\课程表" commit -m "设置页接线应用内下载：开始/取消/安装/清理缓存与状态观察"
```

---

### Task 12: 更新卡片 UI（11 态 + 清理入口 + 修掉硬编码版本号）

**Files:**
- Modify: `app/src/main/java/com/zhijian/schedule/feature/settings/SettingsScreen.kt`

- [ ] **Step 1: 补 import**

在 `SettingsScreen.kt` 的 import 区追加（保持现有分组顺序）：

```kotlin
import androidx.compose.material3.LinearProgressIndicator
import com.zhijian.schedule.BuildConfig
import com.zhijian.schedule.core.update.DownloadState
import com.zhijian.schedule.core.update.ResumableDownload
import com.zhijian.schedule.core.update.UpdateChecker
```

- [ ] **Step 2: 返回设置页时一并刷新安装权限**

把 `LifecycleEventEffect(Lifecycle.Event.ON_RESUME)` 的 lambda（76-78 行）改为：

```kotlin
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshCapsulePromote()
        viewModel.refreshInstallPermission()
    }
```

- [ ] **Step 3: 替换卡片为一行调用**

把「检查更新（方案 1.4）」那段 `StaggerIn(index = 6) { Card { ... } }`（321-349 行）整段替换为：

```kotlin
            // 检查更新（方案 1.4）
            StaggerIn(index = 6) {
                UpdateSection(ui = ui, viewModel = viewModel, context = context)
            }
```

- [ ] **Step 4: 写卡片与辅助函数**

在 `SettingsScreen.kt` 里 `@Composable private fun Card(content: @Composable () -> Unit)` 之前插入：

```kotlin
/** 更新卡片：未检查/检查中/已最新/检查失败/有新版本/无安装包/下载中/已取消/下载失败/待安装/校验失败 */
@Composable
private fun UpdateSection(
    ui: SettingsUiState,
    viewModel: SettingsViewModel,
    context: android.content.Context,
) {
    val colors = LocalCourseColors.current
    var confirmClear by remember { mutableStateOf(false) }

    Card {
        Text("检查更新", color = colors.text, fontWeight = FontWeight.SemiBold)
        UpdateStatusLine(ui)
        Spacer(Modifier.height(8.dp))

        val download = ui.download
        if (download is DownloadState.Running) {
            if (download.totalBytes != null) {
                LinearProgressIndicator(
                    progress = {
                        (download.downloadedBytes.toFloat() / download.totalBytes).coerceIn(0f, 1f)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accent,
                    trackColor = colors.border,
                )
            } else {
                // 响应没有长度信息：不确定进度，不显示假百分比
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.accent,
                    trackColor = colors.border,
                )
            }
            Spacer(Modifier.height(8.dp))
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            when (download) {
                is DownloadState.Running -> ActionButton("取消") { viewModel.cancelDownload() }
                is DownloadState.Paused -> ActionButton("继续下载") { viewModel.downloadUpdate() }
                is DownloadState.Ready -> ActionButton("安装") { viewModel.installUpdate() }
                is DownloadState.Failed -> ActionButton(if (download.canResume) "重试" else "重新下载") {
                    viewModel.downloadUpdate()
                }
                DownloadState.Idle -> if (ui.updateInfo?.apkUrl != null) {
                    ActionButton("下载") { viewModel.downloadUpdate() }
                } else {
                    ActionButton(checkLabel(ui), enabled = !ui.busy) { viewModel.checkUpdate() }
                }
            }

            val pageUrl = ui.updateInfo?.pageUrl
            if (!pageUrl.isNullOrBlank()) {
                Spacer(Modifier.width(10.dp))
                TextButton(onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pageUrl))) }
                }) { Text("打开发布页") }
            }
        }

        if (ui.updateCacheBytes > 0L) {
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { confirmClear = true }) {
                Text(
                    "清理下载缓存（${ResumableDownload.formatBytes(ui.updateCacheBytes)}）",
                    color = colors.textMuted,
                    fontSize = 12.sp,
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("清理下载缓存") },
            text = {
                Text(
                    "将删除已下载的安装包与未完成的下载进度" +
                        "（共 ${ResumableDownload.formatBytes(ui.updateCacheBytes)}）。清理后需要重新下载才能安装。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearUpdateCache()
                }) { Text("清理") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } },
        )
    }
}

/** 卡片的状态行：下载状态下优先显示下载信息，否则显示检查结果 */
@Composable
private fun UpdateStatusLine(ui: SettingsUiState) {
    val colors = LocalCourseColors.current
    val download = ui.download
    val (text, emphasized) = when {
        download is DownloadState.Running -> runningLine(download) to true
        download is DownloadState.Paused ->
            "已取消 · 已保留 ${ResumableDownload.formatBytes(download.downloadedBytes)} 进度" to false
        download is DownloadState.Ready -> "下载完成，待安装（${ResumableDownload.formatBytes(download.sizeBytes)}）" to true
        download is DownloadState.Failed -> download.reason to false
        ui.updateError != null -> "检查更新失败：${ui.updateError}" to false
        ui.updateInfo != null -> versionLine(ui.updateInfo) to true
        ui.updateChecked -> "已是最新版本 v${BuildConfig.VERSION_NAME}" to false
        else -> "通过 GitHub Releases 分发（当前 v${BuildConfig.VERSION_NAME}）" to false
    }
    Text(text, color = if (emphasized) colors.text else colors.textMuted, fontSize = 12.sp)
}

private fun runningLine(state: DownloadState.Running): String {
    val head = state.totalBytes
        ?.let { "已下载 ${ResumableDownload.formatBytes(state.downloadedBytes)} / ${ResumableDownload.formatBytes(it)}" }
        ?: "已下载 ${ResumableDownload.formatBytes(state.downloadedBytes)}"
    return head + (ResumableDownload.etaText(state.etaSeconds)?.let { " · $it" }.orEmpty())
}

private fun versionLine(info: UpdateChecker.UpdateInfo): String = if (info.apkUrl == null) {
    "发现新版本 v${info.version}（该版本未提供安装包）"
} else {
    "发现新版本 v${info.version}" +
        (info.apkSize?.takeIf { it > 0 }?.let { " · 大小 ${ResumableDownload.formatBytes(it)}" } ?: "")
}

private fun checkLabel(ui: SettingsUiState): String = when {
    ui.busy -> "检查中…"
    ui.updateChecked -> "重新检查"
    else -> "检查更新"
}

/** 卡片里的主操作按钮：统一的按压缩放反馈 */
@Composable
private fun ActionButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled,
        interactionSource = source,
        modifier = Modifier.pressScale(source, pressedScale = 0.96f, enabled = enabled),
    ) {
        Text(label)
    }
}
```

> 注意：原来的 `Text("通过 GitHub Releases 分发（当前 v1.0.0）")` 是**硬编码错误版本号**（实际已是 1.0.2），本步已改成读 `BuildConfig.VERSION_NAME`。

- [ ] **Step 5: 编译并跑全量单测**

```bat
cmd /c ".tmp\run-tests.bat :app:assembleDebug --console=plain"
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --console=plain"
```

预期：两条都 `BUILD SUCCESSFUL`，12 个测试类 / 123 个用例 0 失败。

- [ ] **Step 6: 提交**

```bat
git -C "D:\课程表" add app/src/main/java/com/zhijian/schedule/feature/settings/SettingsScreen.kt
git -C "D:\课程表" commit -m "设置页更新卡片改为应用内下载并显示进度，顺手修掉硬编码的版本号"
```

---

### Task 13: 收尾验证

**Files:** 无新增（只跑验证，发现问题就地修）

- [ ] **Step 1: 全量单测**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --console=plain"
```

预期：`BUILD SUCCESSFUL`，12 个测试类 / 123 个用例，0 失败。把实际数字记下来，写进最终提交说明。

- [ ] **Step 2: release 构建（验证 R8 混淆与资源压缩不炸）**

```bat
cmd /c ".tmp\build-release.bat :app:assembleRelease --console=plain"
```

预期：`BUILD SUCCESSFUL`，产出 `app/build/outputs/apk/release/app-release.apk`。

> proguard 规则已覆盖 `com.zhijian.schedule.**` 的 kotlinx.serialization（`app/proguard-rules.pro:19-25`），`PartMeta` 的序列化器不会被裁掉；`FileProvider` 与 `ApkDownloadService` 由清单引用会被保留。

- [ ] **Step 3: 验证 GitHub 是否真支持 Range 续传（本机网络之前没通，必须补上）**

装到手机上跑一次下载，前 20% 时断网再恢复，看是否从断点继续。若本机能连通 `github.com`，也可以直接验：

```bat
curl.exe -sS -o NUL -D - -H "Range: bytes=0-1023" -L "https://github.com/Suife-yuanxing/zhijian-schedule/releases/download/v1.0.2/PaperTimetable-v1.0.2.apk"
```

预期：状态行出现 `206 Partial Content`，且响应头带 `Content-Range`。
若返回 `200`：功能仍然可用（会自动退化为重新下载，代码把 200 分支实现为 `Restart`），但要在最终报告里说明「续传在 GitHub 上不生效」。
若完全连不上：留给真机验证，并在报告里标注为未验证。

- [ ] **Step 4: 真机验收清单（人工，设计文档 §12）**

逐项走一遍并在报告里给出结论：

1. 完整下载 → 校验 → 点「安装」→ 全链路一次通过；
2. 下载中切飞行模式 → 卡片显示可续传 → 恢复网络 → 点「重试」从断点继续（对照已下载字节数不是从 0 开始）；
3. 下载中点「取消」→ `.part`/`.meta` 留存 → 「继续下载」续传成功；
4. 下载中退到后台/切到别的 App → 通知栏进度持续更新，返回后卡片进度一致；
5. 通知栏「取消下载」按钮生效；
6. 未授予「安装未知应用」时点「安装」→ 跳系统授权页，授权返回后可装；
7. `adb shell dumpsys activity services com.zhijian.schedule` 确认前台服务类型是 `dataSync`；
8. 「清理下载缓存」清空后占用归零，且待安装的 APK 也被清掉（有确认框）。

```bat
adb shell dumpsys activity services com.zhijian.schedule
```

- [ ] **Step 5: 确认工作区状态**

```bat
git -C "D:\课程表" status --short
git -C "D:\课程表" log --oneline -13
```

预期：`git status` 只剩 `?? docs/`（设计文档与计划按用户要求留在本地不提交）；log 显示本计划产出的 12 个提交。

---

## 自检记录（写完计划后回看）

**1. 设计覆盖**

| 设计章节 | 落在哪个任务 |
|---|---|
| §1 目标（点击即下载、双处进度、续传、校验、手装、保留退路） | Task 7/12 |
| §2 `UpdateInfo` 扩字段 / 通用客户端不可复用 / FileProvider / 硬编码版本号 | Task 4/5/9/12 |
| §3 架构与状态归属 / `DownloadState` 五个态 / 不引入 WorkManager | Task 6/7 |
| §4 状态机与 11 态卡片 + 清理缓存入口 | Task 12 |
| §5 续传协议（206/200/416/4xx/5xx、`If-Range`、302 风险） | Task 1/7，302 风险在 Task 13 |
| §6 完整性校验（重命名**之前**） | Task 7 的 `verifyAndFinish` |
| §7 安装链路（FileProvider + 未知来源引导） | Task 9/11 |
| §8 权限、清单、`onTimeout`、`START_NOT_STICKY`、`update` 渠道 | Task 8/9 |
| §9 文件生命周期与启动清理、清空缓存 | Task 6/10/11/12 |
| §10 通知栏（节流、取消 action、完成通知、无通知权限也能下） | Task 3/8 |
| §11 错误与降级七种情形 | Task 1/6/7/11/12 各自的失败分支 |
| §12 测试策略七项 + 真机八项 | Task 1/2/3 + Task 13 |
| §13 明确不做（无 WorkManager、无分片、无自动安装） | 全计划无相关改动 |
| §14 未验证假设 | Task 13 Step 3 + 最终报告 |

**2. 占位符扫描**

已通读全文：没有 TBD / TODO / 「稍后实现」/「加上适当的错误处理」/「类似 Task N」。每个改动步骤都给了可直接粘贴的完整代码，每条命令都给了预期输出。

**3. 类型一致性**

- `DownloadRequest(version, url, sizeBytes, digest)` —— Task 7 定义，Task 8 的 Intent extras 与 Task 11 的构造保持同一顺序与命名。
- `DownloadState` 五个成员（`Idle`/`Running`/`Paused`/`Ready`/`Failed`）在 Task 7 定义；Task 8 的 `renderProgress`/`percentOf`、Task 11 的 `refreshUpdateCache` 判断、Task 12 的 `when` 分支用的都是同一组名字。`Failed.canResume` 在 Task 7 与 Task 12 语义一致（true → 文案「重试」）。
- `ResumableDownload` 的公开成员：`parseContentRange`、`resolveTotalBytes`、`decideRange`、`normalizeDigest`、`sha256Hex`、`verifyFile`、`formatBytes`、`etaText`、`versionOf`、`compareNumericVersion`、`shouldClean`；`SpeedMeter`（`reset`/`record`/`bytesPerSecond`/`etaSeconds`）、`ProgressThrottle.shouldRefresh`。Task 6 用 `shouldClean`，Task 7 用 `parseContentRange`/`resolveTotalBytes`/`decideRange`/`verifyFile`/`formatBytes`，Task 8 用 `formatBytes`/`etaText`/`ProgressThrottle`，Task 12 用 `formatBytes`/`etaText` —— 全部与 Task 1-3 定义的签名一致。
- `UpdateInfo.apkSize`/`apkDigest` 在 Task 4 加，Task 11 的 `DownloadRequest` 与 Task 12 的 `versionLine` 消费同名属性。
- FileProvider authority：Task 9 的清单写 `${applicationId}.fileprovider`，`ApkInstaller` 用 `context.packageName + ".fileprovider"` —— `applicationId = com.zhijian.schedule`（`app/build.gradle.kts:24`），`packageName` 运行时同值，两边一致。
- 通知 id：`UpdateNotification.NOTIFICATION_ID_PROGRESS = 3101` 在 Task 8 定义，服务的前台通知与 `postProgress` 都用它；`NOTIFICATION_ID_DONE = 3102` 只给完成通知用，不会互相覆盖。

**4. 执行期增补（2026-10-15，执行中发现）**

Task 12 Step 1 原先要求 `import com.zhijian.schedule.core.update.etaText` 与 `...formatBytes`，但这两个函数是 `object ResumableDownload` 的**成员**（`ResumableDownload.kt:144`、`:158`），不是顶层函数 —— 那两行 import 解析不了，Step 4 里 9 处未限定调用也过不了编译。执行子代理在 Step 5 编译期抓到这个缺陷，按约定停下且未提交。现已改为 `import com.zhijian.schedule.core.update.ResumableDownload` + 9 处限定调用 `ResumableDownload.formatBytes(...)` / `ResumableDownload.etaText(...)`，与 Task 7（`ApkDownloader.kt`）和 Task 8（`UpdateNotification.kt`）里既有的写法保持一致。
