# 教务脚本引擎 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 页面上下文注入式 JS 脚本引擎，让任意学校的教务课表导入靠一份 .js 脚本即可工作（内置两份范本 + 本地导入 + 远程仓库热更新）。

**Architecture:** 脚本在用户已登录的教务 WebView 页面内执行（读 DOM / 同源 fetch），经 `@JavascriptInterface` bridge 回传 JSON 信封；宿主严格校验后转成 `CourseDraft`，送进现有 `ImportPreview/ImportGuard` 管线。契约 = manifest 头（`PAPER_SCRIPT_MANIFEST` JSON）+ `globalThis.__paperScheduleScript.fetchSchedule(ctx)`。分发三件套：assets 内置 / 本地导入 / index.json + SHA-256。

**Tech Stack:** Kotlin、kotlinx.serialization、OkHttp、Android WebView、DataStore、JUnit4（无 Robolectric）。编译检查 `cmd /c ".tmp\build-release.bat :app:compileDebugKotlin --console=plain -q"`；测试 `cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests <FQCN> --console=plain"`；release `cmd /c ".tmp\build-release.bat :app:assembleRelease --console=plain -q"`。

**约定：** 永不 `git add -A`；`app/src/main/java/com/zhijian/schedule/navigation/AppRoot.kt` 是用户未提交 WIP，Task 9 只做最小追加、**不提交**该文件。文件结构：

```
core/jw/script/
  JwScriptManifest.kt   （纯 JVM：manifest 解析校验）
  JwScriptOutput.kt     （纯 JVM：信封校验 → CourseDraft）
  ScriptStore.kt        （纯 JVM + 文件 IO：本地安装登记）
  ScriptIndex.kt        （纯 JVM：index 解析 / sha256 / 版本比较）
  ScriptDownloader.kt   （Android：OkHttp 拉取）
  ScriptCatalog.kt      （Android：内置 + 已安装的统一目录）
  JwScriptRunner.kt     （Android：WebView 注入执行）
feature/jw/
  ScriptManageScreen.kt + ScriptManageViewModel.kt
  JwImportScreen.kt / JwImportViewModel.kt （集成）
app/src/main/assets/jw/scripts/  qiangzhi-jsxsd.js / zhengfang-new.js
scripts/                        index.json + 脚本成品 + README（未来公开仓库直接拷走）
tools/mock-jw/server.mjs        补强智课表页
```

---

### Task 1: JwScriptManifest（manifest 解析与校验）

**Files:**
- Create: `app/src/main/java/com/zhijian/schedule/core/jw/script/JwScriptManifest.kt`
- Test: `app/src/test/java/com/zhijian/schedule/core/jw/script/JwScriptManifestTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.zhijian.schedule.core.jw.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JwScriptManifestTest {

    private val okScript = """
        /**
         * PAPER_SCRIPT_MANIFEST
         * {
         *   "id": "qiangzhi-jsxsd",
         *   "name": "强智教务（通用）",
         *   "version": 1,
         *   "apiVersion": 1,
         *   "hosts": ["jw.example.edu.cn"],
         *   "entryHint": "打开学生课表页面"
         * }
         */
        globalThis.__paperScheduleScript = { fetchSchedule: async () => ({ courses: [] }) }
    """.trimIndent()

    @Test
    fun parsesManifestFromHeaderComment() {
        val r = JwScriptManifest.parse(okScript)
        assertTrue(r is ManifestResult.Ok)
        r as ManifestResult.Ok
        assertEquals("qiangzhi-jsxsd", r.manifest.id)
        assertEquals("强智教务（通用）", r.manifest.name)
        assertEquals(listOf("jw.example.edu.cn"), r.manifest.hosts)
    }

    @Test
    fun fillsDefaults() {
        val r = JwScriptManifest.parse("/** PAPER_SCRIPT_MANIFEST {\"id\":\"a\",\"name\":\"B\"} */ var x = 1")
        assertTrue(r is ManifestResult.Ok)
        assertEquals(1, (r as ManifestResult.Ok).manifest.version)
        assertEquals(1, r.manifest.apiVersion)
        assertEquals(emptyList<String>(), r.manifest.hosts)
    }

    @Test
    fun rejectsMissingMarker() {
        val r = JwScriptManifest.parse("var x = 1")
        assertTrue(r is ManifestResult.Invalid)
        assertTrue((r as ManifestResult.Invalid).reason.contains("PAPER_SCRIPT_MANIFEST"))
    }

    @Test
    fun rejectsBadIdAndFutureApi() {
        assertTrue(JwScriptManifest.parse("/** PAPER_SCRIPT_MANIFEST {\"id\":\"A!\",\"name\":\"B\"} */") is ManifestResult.Invalid)
        val r = JwScriptManifest.parse("/** PAPER_SCRIPT_MANIFEST {\"id\":\"a\",\"name\":\"B\",\"apiVersion\":99} */")
        assertTrue(r is ManifestResult.Invalid)
        assertTrue((r as ManifestResult.Invalid).reason.contains("更新版本"))
    }

    @Test
    fun normalizesHostsToLowercase() {
        val r = JwScriptManifest.parse("/** PAPER_SCRIPT_MANIFEST {\"id\":\"a\",\"name\":\"B\",\"hosts\":[\"JW.Example.EDU.cn\"]} */")
        assertEquals(listOf("jw.example.edu.cn"), (r as ManifestResult.Ok).manifest.hosts)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.core.jw.script.JwScriptManifestTest --console=plain"`
Expected: 编译失败（JwScriptManifest 未定义）

- [ ] **Step 3: 实现**

```kotlin
package com.zhijian.schedule.core.jw.script

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 教务脚本 manifest：脚本头部块注释中的 JSON（marker: PAPER_SCRIPT_MANIFEST）。
 * 详见 docs/superpowers/specs/2026-10-25-jw-script-engine-design.md §1.1。
 */
@Serializable
data class JwScriptManifest(
    val id: String,
    val name: String,
    val version: Int = 1,
    @SerialName("apiVersion") val apiVersion: Int = 1,
    val hosts: List<String> = emptyList(),
    val entryHint: String = "",
) {
    companion object {
        const val SUPPORTED_API_VERSION = 1
        private const val MARKER = "PAPER_SCRIPT_MANIFEST"
        private val json = Json { ignoreUnknownKeys = true }
        private val ID = Regex("""[a-z0-9][a-z0-9-]{0,39}""")
        private val HOST = Regex("""[a-z0-9]([a-z0-9.-]*[a-z0-9])?""")

        sealed interface ParseResult {
            data class Ok(val manifest: JwScriptManifest) : ParseResult
            data class Invalid(val reason: String) : ParseResult
        }

        /** 解析失败一律给用户可读的中文原因；未知字段忽略（向前兼容） */
        fun parse(source: String): ParseResult {
            val block = manifestComment(source)
                ?: return ParseResult.Invalid("脚本缺少 $MARKER 头部说明，无法识别")
            val start = block.indexOf('{')
            val end = block.lastIndexOf('}')
            if (start < 0 || end <= start) return ParseResult.Invalid("脚本 manifest 不是合法 JSON")
            val m = runCatching { json.decodeFromString<JwScriptManifest>(block.substring(start, end + 1)) }
                .getOrElse { return ParseResult.Invalid("脚本 manifest 解析失败：${it.message}") }
            if (!ID.matches(m.id)) return ParseResult.Invalid("脚本 id 非法（只允许小写字母数字与连字符）：${m.id}")
            val name = m.name.trim()
            if (name.isEmpty() || name.length > 30) return ParseResult.Invalid("脚本名称须为 1~30 字")
            if (m.version < 1) return ParseResult.Invalid("脚本版本号非法")
            when {
                m.apiVersion > SUPPORTED_API_VERSION ->
                    return ParseResult.Invalid("该脚本需要更新版本的 App（apiVersion=${m.apiVersion}）")
                m.apiVersion < 1 -> return ParseResult.Invalid("脚本 apiVersion 非法")
            }
            m.hosts.forEach { h ->
                if (!HOST.matches(h)) return ParseResult.Invalid("脚本 hosts 含非法域名：$h")
            }
            return ParseResult.Ok(m.copy(name = name, hosts = m.hosts.map { it.lowercase() }))
        }

        /** 取第一个含 marker 的块注释；无结尾 */ 的残缺块按到文件尾处理 */
        private fun manifestComment(source: String): String? {
            var i = 0
            while (i < source.length) {
                val start = source.indexOf("/*", i)
                if (start < 0) return null
                val end = source.indexOf("*/", start + 2)
                val block = if (end < 0) source.substring(start + 2) else source.substring(start + 2, end)
                if (block.contains(MARKER)) return block
                if (end < 0) return null
                i = end + 2
            }
            return null
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.core.jw.script.JwScriptManifestTest --console=plain"`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/zhijian/schedule/core/jw/script/JwScriptManifest.kt app/src/test/java/com/zhijian/schedule/core/jw/script/JwScriptManifestTest.kt
git commit -m "feat(script): 脚本 manifest 头部解析与校验"
```

---

### Task 2: JwScriptOutput（信封校验 → CourseDraft）

**Files:**
- Create: `app/src/main/java/com/zhijian/schedule/core/jw/script/JwScriptOutput.kt`
- Modify: `app/src/main/java/com/zhijian/schedule/core/jw/ZhengFangNewAdapter.kt`（`mergeDuplicates` private → internal）
- Test: `app/src/test/java/com/zhijian/schedule/core/jw/script/JwScriptOutputTest.kt`

- [ ] **Step 1: 把 `ZhengFangNewAdapter.mergeDuplicates` 改 internal**

`app/src/main/java/com/zhijian/schedule/core/jw/ZhengFangNewAdapter.kt:207`：`private fun mergeDuplicates(` → `internal fun mergeDuplicates(`。

- [ ] **Step 2: 写失败测试**

```kotlin
package com.zhijian.schedule.core.jw.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JwScriptOutputTest {

    @Test
    fun parsesCoursesWithWeeksString() {
        val json = """
            {"courses":[
              {"name":"高等数学","day":1,"startSection":1,"endSection":2,"weeks":"1-8,10-16周","teacher":"张三","room":"实B-301"}
            ]}
        """.trimIndent()
        val r = JwScriptOutput.parse(json, totalWeeks = 20)
        assertTrue(r is OutputResult.Ok)
        r as OutputResult.Ok
        assertEquals(1, r.drafts.size)
        val d = r.drafts.first()
        assertEquals("高等数学", d.name)
        assertEquals(1, d.dayOfWeek)
        assertEquals(1, d.startSection)
        assertEquals(2, d.endSection)
        assertEquals(15, d.weekMask.count { it == '1' })
        assertEquals("张三", d.teacher)
    }

    @Test
    fun parsesWeeksAsIntArrayAndCoercesSections() {
        val json = """
            {"courses":[{"name":"英语","day":"3","startSection":20,"endSection":21,"weeks":[1,2,17]}],
             "totalWeeks":20,"warnings":["第 2 条格式不对，已跳过"]}
        """.trimIndent()
        val r = JwScriptOutput.parse(json, totalWeeks = 20)
        assertTrue(r is OutputResult.Ok)
        r as OutputResult.Ok
        assertEquals(1, r.drafts.size)
        assertEquals(3, r.drafts.first().dayOfWeek)
        assertEquals(12, r.drafts.first().endSection)
        assertEquals("1-2周", com.zhijian.schedule.core.util.WeekMask.toExpression(r.drafts.first().weekMask))
        assertEquals(listOf("第 2 条格式不对，已跳过"), r.warnings)
        assertEquals(20, r.totalWeeks)
    }

    @Test
    fun dropsInvalidEntriesWithWarnings() {
        val json = """
            {"courses":[
              {"name":"","day":1,"startSection":1,"endSection":2},
              {"name":"坏天","day":9,"startSection":1,"endSection":2},
              {"name":"好课","day":2,"startSection":3,"endSection":4}
            ]}
        """.trimIndent()
        val r = JwScriptOutput.parse(json, totalWeeks = 20)
        assertTrue(r is OutputResult.Ok)
        r as OutputResult.Ok
        assertEquals(1, r.drafts.size)
        assertEquals("好课", r.drafts.first().name)
        assertEquals(2, r.warnings.size)
    }

    @Test
    fun rejectsEmptyAndOversizedAndBadEnvelope() {
        assertTrue(JwScriptOutput.parse("{\"courses\":[]}", 20) is OutputResult.Invalid)
        assertTrue(
            JwScriptOutput.parse(
                "{\"courses\":" + List(501) { "{\"name\":\"x\",\"day\":1}" }.joinToString(",", "[", "]") + "}",
                20,
            ) is OutputResult.Invalid,
        )
        assertTrue(JwScriptOutput.parse("not json", 20) is OutputResult.Invalid)
    }

    @Test
    fun mergesSplitRowsBySameKey() {
        val json = """
            {"courses":[
              {"name":"体育","day":4,"startSection":3,"endSection":4,"weeks":"1-8周","teacher":"李","room":" gym"},
              {"name":"体育","day":4,"startSection":3,"endSection":4,"weeks":"9-16周","teacher":"李","room":" gym"}
            ]}
        """.trimIndent()
        val r = JwScriptOutput.parse(json, 20) as OutputResult.Ok
        assertEquals(1, r.drafts.size)
        assertEquals(16, r.drafts.first().weekMask.count { it == '1' })
    }
}
```

- [ ] **Step 3: 跑测试确认失败**

Run: `cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.core.jw.script.JwScriptOutputTest --console=plain"`
Expected: 编译失败（JwScriptOutput 未定义）

- [ ] **Step 4: 实现**

```kotlin
package com.zhijian.schedule.core.jw.script

import com.zhijian.schedule.core.jw.ZhengFangNewAdapter
import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.util.WeekMask
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 脚本输出信封校验（spec §1.3）：逐条校验、非法条目丢弃并计 warning；
 * weeks 兼容 int[]（对齐小爱）与字符串表达式（复用 WeekMask 语法）。
 */
object JwScriptOutput {

    sealed interface ParseResult {
        data class Ok(
            val drafts: List<CourseDraft>,
            val totalWeeks: Int?,
            val warnings: List<String>,
        ) : ParseResult

        data class Invalid(val reason: String) : ParseResult
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(raw: String, totalWeeks: Int): ParseResult {
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
            ?: return ParseResult.Invalid("脚本返回内容不是合法 JSON")
        val coursesEl = root["courses"] as? JsonArray
            ?: return ParseResult.Invalid("脚本未返回 courses 列表")
        if (coursesEl.size > 500) return ParseResult.Invalid("脚本返回课程数异常（${coursesEl.size} 条），已拒绝")
        if (coursesEl.isEmpty()) return ParseResult.Invalid("解析到 0 门课，请确认已打开课表页面再运行脚本")

        val warnings = mutableListOf<String>()
        val drafts = mutableListOf<CourseDraft>()
        coursesEl.forEachIndexed { i, el ->
            val obj = el as? JsonObject ?: run { warnings += "第 ${i + 1} 条不是对象，已跳过"; return@forEachIndexed }
            val draft = toDraft(obj, totalWeeks) { warnings += "第 ${i + 1} 条：$it，已跳过" }
            if (draft != null) drafts += draft
        }
        if (drafts.isEmpty()) return ParseResult.Invalid("解析到 0 门课，请确认已打开课表页面再运行脚本")

        var weeks = totalWeeks
        (root["totalWeeks"] as? JsonPrimitive)?.intOrNull?.let { tw ->
            if (tw in 1..30) weeks = tw else warnings += "脚本返回的总周数 $tw 超出范围，已忽略"
        }
        return ParseResult.Ok(ZhengFangNewAdapter.mergeDuplicates(drafts), root.totalWeeksOrNull(), warnings)
    }

    private fun JsonObject.totalWeeksOrNull(): Int? =
        (this["totalWeeks"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..30 }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    /** 返回 null = 条目被丢弃，reason 进 warnings */
    private fun toDraft(obj: JsonObject, totalWeeks: Int, warn: (String) -> Unit): CourseDraft? {
        val name = obj.str("name").replace(Regex("\\s+"), " ").trim()
        if (name.isEmpty()) { warn("缺课程名"); return null }
        val day = obj.str("day").toIntOrNull() ?: (obj["day"] as? JsonPrimitive)?.intOrNull ?: run {
            warn("「$name」星期值非法"); return null
        }
        if (day !in 1..7) { warn("「$name」星期值 $day 越界"); return null }
        val start = obj.str("startSection").toIntOrNull() ?: (obj["startSection"] as? JsonPrimitive)?.intOrNull ?: 1
        val end = obj.str("endSection").toIntOrNull() ?: (obj["endSection"] as? JsonPrimitive)?.intOrNull ?: start
        if (start !in 1..15 || end !in 1..15 || end < start) { warn("「$name」节次 $start-$end 非法"); return null }

        val mask = when (val w = obj["weeks"]) {
            is JsonArray -> WeekMask.fromWeeks(
                totalWeeks,
                w.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.filter { it in 1..totalWeeks },
            )
            else -> WeekMask.parseExpression(totalWeeks, obj.str("weeks")) ?: WeekMask.of(totalWeeks)
        }
        return CourseDraft(
            name = name.take(30),
            teacher = obj.str("teacher").trim().take(20),
            room = obj.str("room").trim().take(30),
            dayOfWeek = day,
            startSection = start.coerceIn(1, 12),
            endSection = end.coerceIn(1, 12),
            weekMask = mask,
        )
    }
}
```

- [ ] **Step 5: 跑全量脚本包测试确认通过**

Run: `cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.core.jw.script --console=plain"`
Expected: PASS（Task 1 + Task 2 全部用例）

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/zhijian/schedule/core/jw/script/JwScriptOutput.kt app/src/main/java/com/zhijian/schedule/core/jw/ZhengFangNewAdapter.kt app/src/test/java/com/zhijian/schedule/core/jw/script/JwScriptOutputTest.kt
git commit -m "feat(script): 脚本输出信封校验与 CourseDraft 归一化"
```

---

### Task 3: ScriptStore（本地安装登记）

**Files:**
- Create: `app/src/main/java/com/zhijian/schedule/core/jw/script/ScriptStore.kt`
- Test: `app/src/test/java/com/zhijian/schedule/core/jw/script/ScriptStoreTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.zhijian.schedule.core.jw.script

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ScriptStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val script = """
        /** PAPER_SCRIPT_MANIFEST {"id":"demo","name":"演示","version":2,"hosts":["a.edu.cn"]} */
        globalThis.__paperScheduleScript = {}
    """.trimIndent()

    @Test
    fun installListsReadsAndPersists() {
        val store = ScriptStore(tmp.root)
        val r = store.install(script, origin = "local")
        assertTrue(r is ScriptStore.InstallResult.Ok)
        assertEquals(listOf("demo"), store.list().map { it.id })
        assertEquals("演示", store.list().first().name)
        assertEquals(2, store.list().first().version)
        assertTrue(store.read("demo")!!.contains("__paperScheduleScript"))
        // 重新实例化 → 从 scripts.json 恢复
        val reloaded = ScriptStore(tmp.root)
        assertEquals(listOf("demo"), reloaded.list().map { it.id })
    }

    @Test
    fun installSameIdHigherVersionReplaces() {
        val store = ScriptStore(tmp.root)
        store.install(script, "local")
        val newer = script.replace("\"version\":2", "\"version\":3")
        val r = store.install(newer, "remote", onlyIfNewer = true)
        assertTrue(r is ScriptStore.InstallResult.Ok)
        assertEquals(3, store.list().first().version)
        assertTrue(store.read("demo")!!.contains("\"version\":3"))
    }

    @Test
    fun onlyIfNewerRejectsOldOrEqual() {
        val store = ScriptStore(tmp.root)
        store.install(script, "remote")
        val r = store.install(script, "remote", onlyIfNewer = true)
        assertTrue(r is ScriptStore.InstallResult.Rejected)
        assertTrue((r as ScriptStore.InstallResult.Rejected).reason.contains("已是最新"))
    }

    @Test
    fun removeAndSetEnabled() {
        val store = ScriptStore(tmp.root)
        store.install(script, "local")
        store.setEnabled("demo", false)
        assertFalse(store.list().first().enabled)
        store.remove("demo")
        assertEquals(0, store.list().size)
        assertNull(store.read("demo"))
        assertFalse(File(tmp.root, "demo.js").exists())
    }

    @Test
    fun brokenRegistrySelfHeals() {
        tmp.newFile("scripts.json").writeText("{broken")
        val store = ScriptStore(tmp.root)
        assertEquals(0, store.list().size)
        assertTrue(store.install(script, "local") is ScriptStore.InstallResult.Ok)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.core.jw.script.ScriptStoreTest --console=plain"`
Expected: 编译失败

- [ ] **Step 3: 实现**

```kotlin
package com.zhijian.schedule.core.jw.script

import com.zhijian.schedule.core.jw.script.JwScriptManifest as ManifestParser
import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 用户安装脚本的落盘登记（spec §2）：`<dir>/<id>.js` + `<dir>/scripts.json`。
 * 内置脚本不经此存储，由 [ScriptCatalog] 直接从 assets 读取。
 */
class ScriptStore(private val dir: File) {

    @Serializable
    data class InstalledScript(
        val id: String,
        val name: String,
        val version: Int,
        val apiVersion: Int,
        val hosts: List<String>,
        val entryHint: String,
        /** local / remote */
        val origin: String,
        val sha256: String = "",
        val enabled: Boolean = true,
        val installedAt: Long,
    )

    sealed interface InstallResult {
        data class Ok(val script: InstalledScript, val replaced: Boolean) : InstallResult
        data class Rejected(val reason: String) : InstallResult
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val registryFile: File get() = File(dir, "scripts.json")

    @Synchronized
    fun list(): List<InstalledScript> = runCatching {
        if (!registryFile.exists()) return emptyList()
        json.decodeFromString<List<InstalledScript>>(registryFile.readText())
    }.getOrDefault(emptyList())

    @Synchronized
    fun read(id: String): String? =
        File(dir, "$id.js").takeIf { it.exists() }?.readText()

    @Synchronized
    fun install(sourceText: String, origin: String, sha256: String = "", onlyIfNewer: Boolean = false): InstallResult {
        val manifest = when (val r = ManifestParser.parse(sourceText)) {
            is ManifestParser.ParseResult.Ok -> r.manifest
            is ManifestParser.ParseResult.Invalid -> return InstallResult.Rejected(r.reason)
        }
        val existing = list().firstOrNull { it.id == manifest.id }
        if (onlyIfNewer && existing != null && manifest.version <= existing.version) {
            return InstallResult.Rejected("「${existing.name}」已是最新（v${existing.version}）")
        }
        dir.mkdirs()
        File(dir, "${manifest.id}.js").writeText(sourceText)
        val entry = InstalledScript(
            id = manifest.id,
            name = manifest.name,
            version = manifest.version,
            apiVersion = manifest.apiVersion,
            hosts = manifest.hosts,
            entryHint = manifest.entryHint,
            origin = origin,
            sha256 = sha256,
            enabled = existing?.enabled ?: true,
            installedAt = System.currentTimeMillis(),
        )
        save(list().filter { it.id != manifest.id } + entry)
        return InstallResult.Ok(entry, replaced = existing != null)
    }

    @Synchronized
    fun remove(id: String) {
        save(list().filter { it.id != id })
        File(dir, "$id.js").delete()
    }

    @Synchronized
    fun setEnabled(id: String, enabled: Boolean) {
        save(list().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    private fun save(entries: List<InstalledScript>) {
        dir.mkdirs()
        registryFile.writeText(json.encodeToString(entries))
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.core.jw.script.ScriptStoreTest --console=plain"`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/zhijian/schedule/core/jw/script/ScriptStore.kt app/src/test/java/com/zhijian/schedule/core/jw/script/ScriptStoreTest.kt
git commit -m "feat(script): 脚本本地安装登记 ScriptStore"
```

---

### Task 4: ScriptIndex（远程清单解析 / sha256 / 版本比较）

**Files:**
- Create: `app/src/main/java/com/zhijian/schedule/core/jw/script/ScriptIndex.kt`
- Test: `app/src/test/java/com/zhijian/schedule/core/jw/script/ScriptIndexTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.zhijian.schedule.core.jw.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptIndexTest {

    private val index = """
        {"indexVersion":1,"scripts":[
          {"id":"qiangzhi-jsxsd","name":"强智教务（通用）","version":2,
           "url":"https://example.com/qiangzhi-jsxsd.js","sha256":"${"a".repeat(64)}",
           "hosts":[],"minAppVersion":"1.0.10","entryHint":"打开课表页"}
        ]}
    """.trimIndent()

    @Test
    fun parsesIndex() {
        val r = ScriptIndexFns.parse(index)
        assertTrue(r.isSuccess)
        val entry = r.getOrThrow().scripts.single()
        assertEquals("qiangzhi-jsxsd", entry.id)
        assertEquals(2, entry.version)
        assertEquals("a".repeat(64), entry.sha256)
    }

    @Test
    fun rejectsInsecureUrlOrBadSha() {
        val bad = index.replace("https://", "http://")
        assertTrue(ScriptIndexFns.parse(bad).isFailure)
        assertTrue(ScriptIndexFns.parse(index.replace("a".repeat(64), "zz")).isFailure)
    }

    @Test
    fun sha256HexMatchesKnownVector() {
        // sha256("abc") = ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ScriptIndexFns.sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun versionComparison() {
        assertTrue(ScriptIndexFns.atLeast("1.0.11", "1.0.10"))
        assertTrue(ScriptIndexFns.atLeast("1.1.0", "1.0.11"))
        assertFalse(ScriptIndexFns.atLeast("1.0.11", "1.1.0"))
        assertFalse(ScriptIndexFns.atLeast("1.0.9", "1.0.10"))
        assertTrue(ScriptIndexFns.atLeast("2.0", "1.99.99"))
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.core.jw.script.ScriptIndexTest --console=plain"`
Expected: 编译失败

- [ ] **Step 3: 实现**

```kotlin
package com.zhijian.schedule.core.jw.script

import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 远程脚本仓库清单（spec §3.1）；url 必须 https、sha256 必须 64 位 hex */
@Serializable
data class ScriptIndex(
    val indexVersion: Int = 1,
    val scripts: List<ScriptIndexEntry> = emptyList(),
)

@Serializable
data class ScriptIndexEntry(
    val id: String,
    val name: String,
    val version: Int,
    val url: String,
    val sha256: String,
    val hosts: List<String> = emptyList(),
    val minAppVersion: String = "",
    val entryHint: String = "",
)

object ScriptIndexFns {

    private val json = Json { ignoreUnknownKeys = true }
    private val HEX64 = Regex("""[0-9a-f]{64}""")

    fun parse(raw: String): Result<ScriptIndex> = runCatching {
        val index = json.decodeFromString<ScriptIndex>(raw)
        index.scripts.forEach { e ->
            require(e.url.startsWith("https://")) { "${e.id}: 脚本地址必须为 https" }
            require(HEX64.matches(e.sha256)) { "${e.id}: sha256 非法" }
            require(e.version >= 1) { "${e.id}: version 非法" }
        }
        index
    }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 语义化版本逐段比较：current 是否 >= min；缺失段按 0 */
    fun atLeast(current: String, min: String): Boolean {
        val a = current.split(".").map { it.trim().toIntOrNull() ?: 0 }
        val b = min.split(".").map { it.trim().toIntOrNull() ?: 0 }
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return true
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.core.jw.script.ScriptIndexTest --console=plain"`
Expected: 4 tests PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/zhijian/schedule/core/jw/script/ScriptIndex.kt app/src/test/java/com/zhijian/schedule/core/jw/script/ScriptIndexTest.kt
git commit -m "feat(script): 远程清单解析、sha256 与版本比较"
```

---

### Task 5: SettingsStore 增加 jwScriptIndexUrl

**Files:**
- Modify: `app/src/main/java/com/zhijian/schedule/core/data/prefs/SettingsStore.kt`

- [ ] **Step 1: 加字段**

`AppSettings` 中 `val gradesFetchedAt: Long = 0L,` 之后加：

```kotlin
    /** 课程脚本远程仓库地址（index.json）；空 = 未配置 */
    val jwScriptIndexUrl: String = "",
```

`Keys` 中加：`val JW_SCRIPT_INDEX = stringPreferencesKey("jw_script_index_url")`；`settings` map 中加 `jwScriptIndexUrl = p[Keys.JW_SCRIPT_INDEX] ?: "",`；加 setter（与其他 setter 并列）：

```kotlin
    suspend fun setJwScriptIndexUrl(url: String) =
        dataStore.edit { it[Keys.JW_SCRIPT_INDEX] = url.trim() }
```

- [ ] **Step 2: 编译验证**

Run: `cmd /c ".tmp\build-release.bat :app:compileDebugKotlin --console=plain -q"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/zhijian/schedule/core/data/prefs/SettingsStore.kt
git commit -m "feat(script): 脚本仓库地址设置项"
```

---

### Task 6: ScriptCatalog + ScriptDownloader + Hilt 装配

**Files:**
- Create: `app/src/main/java/com/zhijian/schedule/core/jw/script/ScriptCatalog.kt`
- Create: `app/src/main/java/com/zhijian/schedule/core/jw/script/ScriptDownloader.kt`
- Modify: `app/src/main/java/com/zhijian/schedule/di/AppModule.kt`（提供 ScriptStore）

- [ ] **Step 1: ScriptCatalog**

```kotlin
package com.zhijian.schedule.core.jw.script

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 展示用脚本条目：内置与已安装合并视图 */
data class ScriptOption(
    val id: String,
    val name: String,
    val version: Int,
    val hosts: List<String>,
    val entryHint: String,
    /** builtin / local / remote */
    val origin: String,
    val enabled: Boolean,
    /** 当前页面 host 是否命中 hosts（hosts 为空的通用脚本 = false，排序居中） */
    val matched: Boolean,
    /** 是否可运行：已启用 + hosts 命中或为空 */
    val runnable: Boolean,
    val manifest: JwScriptManifest,
) {
    /** 列表里展示的一行说明 */
    fun subtitle(): String = buildString {
        append("v$version · ")
        append(
            when (origin) {
                "builtin" -> "内置"
                "remote" -> "远程"
                else -> "本地"
            },
        )
        if (hosts.isNotEmpty()) append(" · 仅 ${hosts.joinToString("、")}")
    }
}

/**
 * 脚本目录：内置（assets/jw/scripts/，文件名硬编码于 [BUILTIN_FILES]）+ 本地安装。
 * 同 id 时已安装版本优先（可覆盖内置）。
 */
@Singleton
class ScriptCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ScriptStore,
) {

    suspend fun all(pageHost: String?): List<ScriptOption> {
        val installed = store.list()
        val options = mutableListOf<ScriptOption>()
        builtinFiles().forEach { asset ->
            val source = readAsset(asset) ?: return@forEach
            when (val r = JwScriptManifest.parse(source)) {
                is JwScriptManifest.ParseResult.Ok ->
                    if (installed.none { it.id == r.manifest.id }) {
                        options += r.manifest.toOption(origin = "builtin", enabled = true)
                    }
                is JwScriptManifest.ParseResult.Invalid -> Unit // 内置损坏直接跳过
            }
        }
        installed.forEach {
            options += JwScriptManifest(
                id = it.id, name = it.name, version = it.version,
                apiVersion = it.apiVersion, hosts = it.hosts, entryHint = it.entryHint,
            ).toOption(origin = it.origin, enabled = it.enabled)
        }
        return options.sortedWith(
            compareByDescending<ScriptOption> { it.matchedHost(pageHost) }
                .thenBy { if (it.hosts.isEmpty()) 1 else 0 }
                .thenBy { it.name },
        ).map { it.copy(matched = it.matchedHost(pageHost), runnable = it.enabled && it.matchedHost(pageHost)) }
    }

    suspend fun readSource(id: String): String? {
        store.read(id)?.let { return it }
        return builtinFiles().firstOrNull { it.substringBeforeLast('.') == id }
            ?.let { readAsset(it) }
    }

    private fun JwScriptManifest.toOption(origin: String, enabled: Boolean) =
        ScriptOption(
            id = id, name = name, version = version, hosts = hosts, entryHint = entryHint,
            origin = origin, enabled = enabled, matched = false, runnable = false, manifest = this,
        )

    private fun ScriptOption.matchedHost(pageHost: String?): Boolean {
        if (pageHost.isNullOrBlank()) return false
        if (hosts.isEmpty()) return false
        return pageHost.lowercase() in hosts
    }

    private fun builtinFiles(): List<String> = BUILTIN_FILES

    private fun readAsset(name: String): String? = runCatching {
        context.assets.open("jw/scripts/$name").bufferedReader().use { it.readText() }
    }.getOrNull()

    private companion object {
        val BUILTIN_FILES = listOf("qiangzhi-jsxsd.js", "zhengfang-new.js")
    }
}
```

- [ ] **Step 2: ScriptDownloader**

```kotlin
package com.zhijian.schedule.core.jw.script

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** 远程脚本仓库拉取（spec §3.1）；SHA-256 校验通过才把脚本文本交给调用方 */
@Singleton
class ScriptDownloader @Inject constructor(private val client: OkHttpClient) {

    sealed interface Result {
        data class Ok(val text: String) : Result
        data class Failed(val message: String) : Result
    }

    suspend fun fetchText(url: String, sha256: String? = null, maxBytes: Int = 2_000_000): Result =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use Result.Failed("下载失败（HTTP ${response.code}）")
                    val bytes = response.body?.bytes() ?: return@use Result.Failed("下载内容为空")
                    if (bytes.size > maxBytes) return@use Result.Failed("下载内容超出大小限制")
                    if (sha256 != null) {
                        val actual = ScriptIndexFns.sha256Hex(bytes)
                        if (!actual.equals(sha256, ignoreCase = true)) {
                            return@use Result.Failed("SHA-256 校验失败，脚本可能被篡改，已放弃安装")
                        }
                    }
                    Result.Ok(bytes.toString(Charsets.UTF_8))
                }
            } catch (e: Exception) {
                Result.Failed("下载失败：${e.message ?: "网络异常"}")
            }
        }
}
```

- [ ] **Step 3: AppModule 提供 ScriptStore**

`app/src/main/java/com/zhijian/schedule/di/AppModule.kt` 追加（import `com.zhijian.schedule.core.jw.script.ScriptStore`、`java.io.File`、`dagger.hilt.android.qualifiers.ApplicationContext`、`android.content.Context` 若缺）：

```kotlin
    @Provides
    @Singleton
    fun provideScriptStore(@ApplicationContext context: Context): ScriptStore =
        ScriptStore(File(context.filesDir, "jw-scripts"))
```

- [ ] **Step 4: 编译验证（注意清除旧 Hilt 生成码）**

Run: 删除 `app\build\generated\hilt` 后 `cmd /c ".tmp\build-release.bat :app:compileDebugKotlin --console=plain -q"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/zhijian/schedule/core/jw/script/ScriptCatalog.kt app/src/main/java/com/zhijian/schedule/core/jw/script/ScriptDownloader.kt app/src/main/java/com/zhijian/schedule/di/AppModule.kt
git commit -m "feat(script): 脚本目录/下载器与 Hilt 装配"
```

---

### Task 7: JwScriptRunner（WebView 注入执行）

**Files:**
- Create: `app/src/main/java/com/zhijian/schedule/core/jw/script/JwScriptRunner.kt`

（WebView 行为无法 JVM 单测，正确性由 Task 11 的 mock-jw 真机 E2E 覆盖。）

- [ ] **Step 1: 实现**

```kotlin
package com.zhijian.schedule.core.jw.script

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.net.URI
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 页面上下文脚本执行（spec §2）：把脚本注入当前教务 WebView 的主 frame，
 * 脚本读 DOM / 同源 fetch，经 bridge 回传 JSON 信封。20s 超时；执行完清理全局残留。
 */
object JwScriptRunner {

    enum class FailureKind { HOST_MISMATCH, NO_ENTRY, TIMEOUT, SCRIPT_ERROR }

    sealed interface RunResult {
        data class Ok(val envelopeJson: String) : RunResult
        data class Failed(val message: String, val kind: FailureKind) : RunResult
    }

    private const val BRIDGE_NAME = "PaperBridge"
    private const val DEFAULT_TIMEOUT_MS = 20_000L

    suspend fun run(
        webView: WebView,
        sourceText: String,
        manifest: JwScriptManifest,
        pageUrl: String,
        totalWeeks: Int,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): RunResult = withContext(Dispatchers.Main) {
        if (manifest.hosts.isNotEmpty()) {
            val host = runCatching { URI(pageUrl).host?.lowercase() }.getOrNull().orEmpty()
            if (host !in manifest.hosts) {
                return@withContext RunResult.Failed(
                    "该脚本仅适用于 ${manifest.hosts.joinToString("、")}，当前页面不属于它们",
                    FailureKind.HOST_MISMATCH,
                )
            }
        }
        val deferred = CompletableDeferred<String>()
        val bridge = Bridge(deferred)
        webView.addJavascriptInterface(bridge, BRIDGE_NAME)
        try {
            val loader = buildLoader(manifest, sourceText, totalWeeks)
            webView.evaluateJavascript(loader, null)
            val json = withTimeoutOrNull(timeoutMs) { deferred.await() }
            when {
                json != null -> RunResult.Ok(json)
                else -> RunResult.Failed("脚本执行超时（${timeoutMs / 1000} 秒），请确认停留在课表页面", FailureKind.TIMEOUT)
            }
        } finally {
            runCatching { webView.removeJavascriptInterface(BRIDGE_NAME) }
            // 清理全局引用，避免下一个脚本读到上一个的残留
            runCatching { webView.evaluateJavascript("try { delete globalThis.__paperScheduleScript } catch (e) {}", null) }
        }
    }

    private class Bridge(private val deferred: CompletableDeferred<String>) {
        private val done = AtomicReference(false)

        private fun complete(block: () -> Unit) {
            if (done.compareAndSet(false, true)) block()
        }

        @JavascriptInterface
        fun onResult(json: String?) {
            complete { deferred.complete(json ?: "null") }
        }

        @JavascriptInterface
        fun onError(kind: String?, message: String?) {
            complete {
                val reason = when (kind) {
                    "no_entry" -> "脚本未定义 fetchSchedule 入口"
                    else -> "脚本执行出错：${message ?: "未知错误"}"
                }
                deferred.cancel(reason.let { java.util.concurrent.CancellationException(it) })
            }
        }
    }

    private fun buildLoader(manifest: JwScriptManifest, sourceText: String, totalWeeks: Int): String {
        val source = JSONObject.quote(sourceText)
        val weeks = totalWeeks.coerceIn(1, 30)
        return """
            (function () {
              try {
                delete globalThis.__paperScheduleScript;
                var src = $source;
                (0, eval)(src);
                var script = globalThis.__paperScheduleScript;
                if (!script || typeof script.fetchSchedule !== 'function') {
                  $BRIDGE_NAME.onError('no_entry', 'fetchSchedule 未定义');
                  return;
                }
                Promise.resolve()
                  .then(function () { return script.fetchSchedule({ url: location.href, totalWeeks: $weeks }); })
                  .then(function (r) { $BRIDGE_NAME.onResult(JSON.stringify(r === undefined ? null : r)); })
                  .catch(function (e) { $BRIDGE_NAME.onError('script_error', String((e && e.message) || e)); });
              } catch (e) {
                try { $BRIDGE_NAME.onError('script_error', String((e && e.message) || e)); } catch (e2) {}
              }
            })()
        """.trimIndent()
    }
}
```

注意：bridge 线程（WebView 的 JavaBridge 线程）上 `deferred.cancel(reason)` 用 `CancellationException` 传消息，`await()` 侧以 `CancellationException` 抛出——`run` 里在 `withTimeoutOrNull { deferred.await() }` 外包一层：

```kotlin
            val json = try {
                withTimeoutOrNull(timeoutMs) { deferred.await() }
            } catch (e: java.util.concurrent.CancellationException) {
                if (e.message != null) throw IllegalStateException(e.message) else throw e
            }
```

（即失败时把脚本错误消息带出来；`run` 的调用方 catch `IllegalStateException` → `RunResult.Failed(message, SCRIPT_ERROR)`。完整实现时把这段并进 `when` 前的取值逻辑，`Handler(Looper.getMainLooper())` 不需要——evaluateJavascript 必须在 Main 调用，`withContext(Dispatchers.Main)` 已保证。）

- [ ] **Step 2: 编译验证**

Run: `cmd /c ".tmp\build-release.bat :app:compileDebugKotlin --console=plain -q"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/zhijian/schedule/core/jw/script/JwScriptRunner.kt
git commit -m "feat(script): WebView 页面上下文脚本执行器"
```

---

### Task 8: JwImportViewModel 脚本路径

**Files:**
- Modify: `app/src/main/java/com/zhijian/schedule/feature/jw/JwImportViewModel.kt`

- [ ] **Step 1: 注入与状态**

构造器追加 `private val catalog: ScriptCatalog`（import `com.zhijian.schedule.core.jw.script.JwScriptManifest`、`JwScriptOutput`、`ScriptCatalog`、`ScriptOption`、`kotlinx.coroutines.flow.first` 已有）。状态区追加：

```kotlin
    var scriptRunning by mutableStateOf(false)
        private set
    var scriptNotice by mutableStateOf<String?>(null)
        private set
    /** 首次运行前展示的引导文案（entryHint） */
    var scriptHint by mutableStateOf<String?>(null)
        private set
```

- [ ] **Step 2: 方法**

```kotlin
    // ---------- 脚本导入（spec §5：结果进同一 ImportPreview 管线） ----------

    fun scriptOptions(pageUrl: String): suspend () -> List<ScriptOption> {
        val host = runCatching { java.net.URI(pageUrl).host }.getOrNull()
        return { catalog.all(host) }
    }

    suspend fun scriptSource(id: String): String? = catalog.readSource(id)

    suspend fun currentTotalWeeks(): Int =
        repository.activeTimetable.first()?.totalWeeks ?: 20

    fun setScriptRunning(running: Boolean) {
        scriptRunning = running
    }

    fun showScriptHint(hint: String?) {
        scriptHint = hint
    }

    fun dismissScriptNotice() {
        scriptNotice = null
    }

    fun onScriptEnvelope(envelopeJson: String) {
        viewModelScope.launch {
            val weeks = currentTotalWeeks()
            when (val out = JwScriptOutput.parse(envelopeJson, weeks)) {
                is JwScriptOutput.ParseResult.Invalid -> errorMessage = out.reason
                is JwScriptOutput.ParseResult.Ok -> {
                    scriptNotice = out.warnings.takeIf { it.isNotEmpty() }?.joinToString("；")
                    preview = repository.buildPreview(out.drafts, ImportSource.JW, syncMode = false)
                }
            }
        }
    }

    fun onScriptFailed(message: String) {
        errorMessage = message
    }
```

import 追加 `com.zhijian.schedule.core.model.ImportSource`。

- [ ] **Step 3: 编译验证**

Run: `cmd /c ".tmp\build-release.bat :app:compileDebugKotlin --console=plain -q"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/zhijian/schedule/feature/jw/JwImportViewModel.kt
git commit -m "feat(script): 教务导入页脚本路径状态与方法"
```

---

### Task 9: JwImportScreen 集成 + ScriptManageScreen + 路由

**Files:**
- Modify: `app/src/main/java/com/zhijian/schedule/feature/jw/JwImportScreen.kt`
- Create: `app/src/main/java/com/zhijian/schedule/feature/jw/ScriptManageScreen.kt`（含 ViewModel）
- Modify: `app/src/main/java/com/zhijian/schedule/navigation/AppRoot.kt`（**用户 WIP 文件：只追加、不提交**）

- [ ] **Step 1: JwImportScreen 提升 WebView 引用**

`WebViewBox` 加参数 `onWebView: (WebView?) -> Unit = {}`，`factory` 内 `WebView(context).apply{...}` 末尾加 `.also(onWebView)`。调用处传 `onWebView = { webView = it }`；Composable 顶部：

```kotlin
    var webView by remember { mutableStateOf<android.webkit.WebView?>(null) }
    var showScriptPicker by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
```

- [ ] **Step 2: 顶栏脚本入口 + 选择器对话框 + 提示条**

`SecondaryTopBar` 加 `actions`：

```kotlin
            actions = {
                TapIconButton(onClick = { showScriptPicker = true }) {
                    Icon(Icons.Filled.Build, contentDescription = "课程脚本", tint = colors.text)
                }
            },
```

（import `androidx.compose.material.icons.Icons`、`androidx.compose.material.icons.filled.Build`、`TapIconButton`；`LoadingOverlay` 的 visible 改为 `viewModel.loading || viewModel.scriptRunning`。）

`scriptNotice` 展示（放在 `viewModel.errorMessage` 块前，accent 色）：

```kotlin
            viewModel.scriptNotice?.let { message ->
                Text(
                    text = message,
                    color = colors.accent,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                )
            }
```

文件末尾加选择器与运行逻辑（Composable 内、`preview?.let` 之前）：

```kotlin
    if (showScriptPicker) {
        ScriptPickerDialog(
            pageUrl = viewModel.pageUrl,
            optionsProvider = viewModel.scriptOptions(viewModel.pageUrl),
            onDismiss = { showScriptPicker = false },
            onManage = {
                showScriptPicker = false
                onOpenScripts()
            },
            onRun = { option ->
                showScriptPicker = false
                runScript(option)
            },
        )
    }
```

```kotlin
    fun runScript(option: ScriptOption) {
        val wv = webView
        if (wv == null) {
            viewModel.onScriptFailed("网页尚未就绪，请稍候重试")
            return
        }
        viewModel.showScriptHint(option.entryHint.ifBlank { null })
        scope.launch {
            viewModel.setScriptRunning(true)
            try {
                val source = viewModel.scriptSource(option.id)
                if (source == null) {
                    viewModel.onScriptFailed("脚本内容缺失，请重新安装")
                    return@launch
                }
                val weeks = viewModel.currentTotalWeeks()
                val run = JwScriptRunner.run(wv, source, option.manifest, viewModel.pageUrl, weeks)
                when (run) {
                    is JwScriptRunner.RunResult.Ok -> viewModel.onScriptEnvelope(run.envelopeJson)
                    is JwScriptRunner.RunResult.Failed -> viewModel.onScriptFailed(run.message)
                }
            } catch (e: IllegalStateException) {
                viewModel.onScriptFailed(e.message ?: "脚本执行出错")
            } finally {
                viewModel.setScriptRunning(false)
            }
        }
    }
```

（`runScript` 定义为 `JwImportScreen` 内的局部函数；`scope.launch` 需要 import `kotlinx.coroutines.launch`；`JwScriptRunner`、`ScriptOption` import 自 `core.jw.script`。`ScriptPickerDialog` 为新私有 Composable：AlertDialog + LazyColumn(360dp) 逐行显示 `name`、`subtitle()`、命中站点时加「适用于当前站点」accent 小字、不可运行时置灰并显示原因（未启用/域不符），底部一行 TapTextButton「管理脚本…」。）

- [ ] **Step 3: ScriptManageScreen（新建，含 ViewModel）**

```kotlin
package com.zhijian.schedule.feature.jw

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.BuildConfig
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.jw.script.ScriptCatalog
import com.zhijian.schedule.core.jw.script.ScriptDownloader
import com.zhijian.schedule.core.jw.script.ScriptIndexFns
import com.zhijian.schedule.core.jw.script.ScriptIndexFns.atLeast
import com.zhijian.schedule.core.jw.script.ScriptOption
import com.zhijian.schedule.core.jw.script.ScriptStore
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.SecondaryTopBar
import com.zhijian.schedule.core.ui.TapIconButton
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.JellySwitch
import com.zhijian.schedule.core.ui.buttonFeedback
import com.zhijian.schedule.core.ui.selectedPop
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScriptManageUiState(
    val options: List<ScriptOption> = emptyList(),
    val indexUrl: String = "",
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class ScriptManageViewModel @Inject constructor(
    private val catalog: ScriptCatalog,
    private val store: ScriptStore,
    private val downloader: ScriptDownloader,
    private val settingsStore: SettingsStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(ScriptManageUiState())
    val state: StateFlow<ScriptManageUiState> = _state

    init {
        refresh()
        viewModelScope.launch {
            _state.update { it.copy(indexUrl = settingsStore.settings.first().jwScriptIndexUrl) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(options = catalog.all(null)) }
        }
    }

    fun setIndexUrl(url: String) {
        viewModelScope.launch {
            settingsStore.setJwScriptIndexUrl(url)
            _state.update { it.copy(indexUrl = url.trim(), message = if (url.isBlank()) "已清空脚本仓库地址" else "脚本仓库已保存") }
        }
    }

    fun toggle(id: String, enabled: Boolean) {
        store.setEnabled(id, enabled)
        refresh()
    }

    fun remove(id: String) {
        store.remove(id)
        refresh()
    }

    /** 本地导入 .js（spec §3.2）：信任来源 = 用户主动选择，无哈希要求 */
    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            val result = text?.let { store.install(it, origin = "local") }
                ?: ScriptStore.InstallResult.Rejected("无法读取所选文件")
            val message = when (result) {
                is ScriptStore.InstallResult.Ok ->
                    if (result.replaced) "已替换为「${result.script.name}」v${result.script.version}"
                    else "已安装「${result.script.name}」v${result.script.version}"
                is ScriptStore.InstallResult.Rejected -> result.reason
            }
            _state.update { it.copy(busy = false, message = message) }
            refresh()
        }
    }

    /** 检查更新（spec §3.1）：拉 index → 按 version/minAppVersion 过滤 → 下载 + SHA-256 → 安装 */
    fun checkUpdates() {
        viewModelScope.launch {
            val indexUrl = _state.value.indexUrl
            if (indexUrl.isBlank()) {
                _state.update { it.copy(message = "请先填写脚本仓库地址（index.json）") }
                return@launch
            }
            _state.update { it.copy(busy = true, message = null) }
            val indexJson = when (val r = downloader.fetchText(indexUrl)) {
                is ScriptDownloader.Result.Ok -> r.text
                is ScriptDownloader.Result.Failed -> {
                    _state.update { it.copy(busy = false, message = r.message) }
                    return@launch
                }
            }
            val index = ScriptIndexFns.parse(indexJson).getOrElse {
                _state.update { s -> s.copy(busy = false, message = "脚本清单格式错误：${it.message}") }
                return@launch
            }
            val installed = store.list().associateBy { it.id }
            var updated = 0
            val skipped = mutableListOf<String>()
            index.scripts.forEach { entry ->
                if (!atLeast(BuildConfig.VERSION_NAME, entry.minAppVersion)) {
                    skipped += "${entry.name}（需要 App ${entry.minAppVersion}）"
                    return@forEach
                }
                val current = installed[entry.id]
                if (current != null && entry.version <= current.version) return@forEach
                when (val r = downloader.fetchText(entry.url, entry.sha256)) {
                    is ScriptDownloader.Result.Ok -> {
                        val install = store.install(r.text, origin = "remote", sha256 = entry.sha256, onlyIfNewer = current != null)
                        if (install is ScriptStore.InstallResult.Ok) updated++
                        if (install is ScriptStore.InstallResult.Rejected) skipped += "${entry.name}（${install.reason}）"
                    }
                    is ScriptDownloader.Result.Failed -> skipped += "${entry.name}（${r.message}）"
                }
            }
            val parts = mutableListOf<String>()
            parts += if (updated > 0) "安装/更新 $updated 个脚本" else "已是最新"
            if (skipped.isNotEmpty()) parts += "跳过：${skipped.joinToString("、")}"
            _state.update { it.copy(busy = false, message = parts.joinToString("；")) }
            refresh()
        }
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }
}
```

UI（同文件）：

```kotlin
@Composable
fun ScriptManageScreen(onBack: () -> Unit, viewModel: ScriptManageViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = LocalCourseColors.current

    Column(Modifier.fillMaxSize()) {
        SecondaryTopBar(title = "课程脚本", onBack = onBack)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TapTextButton(onClick = { viewModel.checkUpdates() }, enabled = !state.busy) { Text("检查更新", fontSize = 13.sp) }
            TapTextButton(onClick = { pickerLauncher(() -> Unit) {} }, enabled = false) { Text("") }
        }
    }
}
```

——上面 UI 骨架不完整，**完整 UI 按 JwImportScreen 既有风格写**：文件选择用 `rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let(viewModel::importFromUri) }`，launcher `GetContent().apply { input = "*/*" }`；布局为 LazyColumn 脚本行（名称 + `subtitle()` + 提示行 `entryHint`，行尾 JellySwitch 启用开关 + 内置行显示「内置」徽章、Delete 图标仅对已安装行显示），底部固定区：`OutlinedTextField`（仓库地址，值 = state.indexUrl，onValueChange 本地暂存）+「保存地址」TapTextButton +「导入 .js」TapTextButton；`state.message` 用 NoticeBanner 风格 Text 展示。ViewModel 已完备，UI 只是状态映射。

- [ ] **Step 4: AppRoot 路由（用户 WIP 文件——只追加不提交）**

`Routes` 对象加 `const val PROFILE_JW_SCRIPTS = "profile_jw_scripts"`；`NavHost` 内 `composable(Routes.PROFILE_EXAMS)` 块后追加：

```kotlin
            composable(Routes.PROFILE_JW_SCRIPTS) {
                ScriptManageScreen(onBack = handleBack)
            }
```

`JwImportScreen(...)` 调用处追加 `onOpenScripts = { navController.navigate(Routes.PROFILE_JW_SCRIPTS) { launchSingleTop = true } }`。import `com.zhijian.schedule.feature.jw.ScriptManageScreen`。

- [ ] **Step 5: 编译验证**

Run: `cmd /c ".tmp\build-release.bat :app:compileDebugKotlin --console=plain -q"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit（不含 AppRoot）**

```bash
git add app/src/main/java/com/zhijian/schedule/feature/jw/JwImportScreen.kt app/src/main/java/com/zhijian/schedule/feature/jw/ScriptManageScreen.kt
git commit -m "feat(script): 教务导入页脚本入口、选择器与脚本管理页"
```

---

### Task 10: 内置脚本（assets 两份范本）

**Files:**
- Create: `app/src/main/assets/jw/scripts/qiangzhi-jsxsd.js`
- Create: `app/src/main/assets/jw/scripts/zhengfang-new.js`

- [ ] **Step 1: qiangzhi-jsxsd.js**（`QiangZhiAdapter.parse` 的页面上下文忠实移植；表头 th/td 星期映射、`div.kbcontent`/旧版整格、`<hr>`/`-----`/`</div>` 分隔、`[01-02-03-04节]` 权威节次、`第一大节` 行标签、大行/单行两种 fallback——完整代码：

```js
/**
 * PAPER_SCRIPT_MANIFEST
 * {
 *   "id": "qiangzhi-jsxsd",
 *   "name": "强智教务（通用）",
 *   "version": 1,
 *   "apiVersion": 1,
 *   "hosts": [],
 *   "entryHint": "登录教务后打开「学生课表」页面（xskb_list.do），再点导入"
 * }
 *
 * 纸间课表脚本范本：与内置强智解析（QiangZhiAdapter）同一套规则的页面上下文实现。
 * 给新学校写脚本可复制本文件改 manifest 与选择器。
 */
globalThis.__paperScheduleScript = {
  fetchSchedule: async function (ctx) {
    var warnings = [];
    var table = document.getElementById('kbtable') || document.querySelector('table.kbtable');
    if (!table) throw new Error('未找到课表表格（#kbtable），请先打开「学生课表」页面');
    var rows = [];
    Array.prototype.forEach.call(table.querySelectorAll('tr'), function (tr) {
      if (tr.querySelector('th,td')) rows.push(tr);
    });
    if (!rows.length) throw new Error('课表表格为空');

    function parseDay(text) {
      var t = (text || '').trim();
      if (!t || t.length > 8) return 0;
      if (t.indexOf('星期') >= 0 || t.indexOf('周') >= 0) {
        var zh = ['一', '二', '三', '四', '五', '六', '日', '天'];
        for (var i = 0; i < zh.length; i++) {
          if (t.indexOf(zh[i]) >= 0) return Math.min(i + 1, 7);
        }
      }
      return 0;
    }
    function cnNum(s) {
      var map = { '一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9, '十': 10 };
      if (map[s] !== undefined) return map[s];
      var n = parseInt(s, 10);
      return isNaN(n) ? 0 : n;
    }
    function parseSectionLabel(text) {
      var t = (text || '').trim();
      if (!t) return null;
      if (/\d{1,2}\s*[:：]\s*\d{2}/.test(t)) return null;
      var big = /第([一二三四五六七八九十\d]+)大节/.exec(t);
      if (big) {
        var n = cnNum(big[1]);
        return n ? [n * 2 - 1, n * 2] : null;
      }
      var nums = (t.match(/\d+/g) || []).map(Number);
      if (nums.length === 1) return [nums[0], nums[0]];
      if (nums.length >= 2) return [Math.min(nums[0], nums[1]), Math.max(nums[0], nums[1])];
      return null;
    }
    function textOf(fragment) {
      var doc = new DOMParser().parseFromString(fragment, 'text/html');
      return (doc.body.textContent || '').replace(/\u00A0/g, ' ').trim();
    }
    function bracketSections(line) {
      var m = /\[([\d\-\s]+)节]/.exec(line || '');
      if (!m) return null;
      var nums = (m[1].match(/\d+/g) || []).map(Number);
      if (!nums.length) return null;
      return [Math.min.apply(null, nums), Math.max.apply(null, nums)];
    }
    function fallbackRange(ordinal, bigRowLayout) {
      if (bigRowLayout) {
        var period = Math.min(Math.max(ordinal + 1, 1), 6);
        return [period * 2 - 1, period * 2];
      }
      var section = Math.min(Math.max(ordinal + 1, 1), 12);
      return [section, section];
    }
    function mapLines(lines, day, rowRange, ordinal, bigRowLayout) {
      if (!lines.length) return null;
      var name = lines[0].trim().replace(/[*（(]+$/, '');
      if (!name || name.length > 30) return null;
      var weekLine = null;
      for (var i = 0; i < lines.length; i++) {
        var it = lines[i];
        if (it.indexOf('周') >= 0 && it.indexOf('星期') < 0 && (/\d/.test(it) || it.indexOf('全') >= 0)) { weekLine = it; break; }
      }
      if (!weekLine) {
        for (var j = 0; j < lines.length; j++) {
          if (/^[\d,\-()（）单双]+$/.test(lines[j]) && /\d/.test(lines[j])) { weekLine = lines[j]; break; }
        }
      }
      var rest = lines.slice(1).filter(function (x) { return x !== weekLine; });
      var teacher = rest[0] && rest[0].length <= 20 ? rest[0] : '';
      var room = rest[1] && rest[1].length <= 30 ? rest[1] : '';
      var weekExpr = weekLine ? weekLine.split('[')[0] : '';
      var range = bracketSections(weekLine) || rowRange || fallbackRange(ordinal, bigRowLayout);
      return {
        name: name, teacher: teacher, room: room, day: day,
        startSection: range[0], endSection: range[1], weeks: weekExpr
      };
    }

    var headerDays = null, headerIndex = -1, dayValues = [];
    for (var r = 0; r < Math.min(3, rows.length); r++) {
      var headCells = rows[r].querySelectorAll('th,td');
      var mapping = {};
      Array.prototype.forEach.call(headCells, function (c, idx) {
        var d = parseDay(c.textContent);
        if (d) mapping[idx] = d;
      });
      var keys = Object.keys(mapping);
      if (keys.length >= 3) {
        headerDays = mapping; headerIndex = r;
        keys.sort(function (a, b) { return Number(a) - Number(b); })
          .forEach(function (k) { dayValues.push(mapping[k]); });
        break;
      }
    }
    var dataRows = rows.filter(function (_, i) { return i !== headerIndex; });
    var bigRowLayout = dataRows.length <= 7;
    var courses = [];
    dataRows.forEach(function (tr, ordinal) {
      var cells = Array.prototype.slice.call(tr.querySelectorAll('th,td'));
      if (!cells.length) return;
      if (headerDays && cells.length < Object.keys(headerDays).length) return;
      var rowRange = parseSectionLabel(cells[0].textContent);
      var courseCells = headerDays
        ? cells.slice(cells.length - Object.keys(headerDays).length)
        : cells;
      courseCells.forEach(function (td, colIdx) {
        var day = headerDays ? dayValues[colIdx] : (colIdx >= 1 && colIdx <= 7 ? colIdx : 0);
        if (!day) return;
        var blocks = td.querySelectorAll('div.kbcontent');
        var sources = blocks.length
          ? Array.prototype.map.call(blocks, function (b) { return b.innerHTML; })
          : [td.innerHTML];
        sources.forEach(function (raw) {
          raw.split(/<hr[^>]*\/?>|-{6,}|<\/div>/i).forEach(function (fragment) {
            var lines = fragment.split(/<br\s*\/?>/i).map(textOf).filter(Boolean);
            var c = mapLines(lines, day, rowRange, ordinal, bigRowLayout);
            if (c) courses.push(c);
          });
        });
      });
    });
    return { courses: courses, warnings: warnings };
  }
};
```

- [ ] **Step 2: zhengfang-new.js**

```js
/**
 * PAPER_SCRIPT_MANIFEST
 * {
 *   "id": "zhengfang-new",
 *   "name": "正方新版教务（通用）",
 *   "version": 1,
 *   "apiVersion": 1,
 *   "hosts": [],
 *   "entryHint": "登录正方教务（地址含 /jwglxt/）后任意页面即可点导入"
 * }
 */
globalThis.__paperScheduleScript = {
  fetchSchedule: async function (ctx) {
    var m = /\/jwglxt\//.exec(location.pathname);
    if (!m) throw new Error('请先打开正方教务（地址需包含 /jwglxt/）');
    var base = location.origin + location.pathname.slice(0, m.index + '/jwglxt/'.length);

    function selected(id) {
      var el = document.querySelector('select#' + id + ' option[selected]');
      return el ? (el.getAttribute('value') || '').trim() : '';
    }
    var candidates = [];
    var xnm = selected('xnm'), xqm = selected('xqm');
    if (xnm && xqm) candidates.push(xnm + '|' + xqm);
    var now = new Date();
    var month = now.getMonth() + 1;
    var fy = month >= 9 ? now.getFullYear() : now.getFullYear() - 1;
    var ay = fy + '-' + (fy + 1);
    if (month >= 2 && month <= 8) candidates.push(ay + '|12', ay + '|3');
    else candidates.push(ay + '|3', ay + '|12');
    candidates = candidates.filter(function (c, i) { return candidates.indexOf(c) === i; });

    var lastErr = '学期参数不匹配或页面结构已改';
    for (var i = 0; i < candidates.length; i++) {
      var parts = candidates[i].split('|');
      var res;
      try {
        res = await fetch(base + 'kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151', {
          method: 'POST',
          credentials: 'same-origin',
          headers: {
            'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
            'X-Requested-With': 'XMLHttpRequest'
          },
          body: 'xnm=' + encodeURIComponent(parts[0]) + '&xqm=' + encodeURIComponent(parts[1]) + '&kzlx=ck'
        });
      } catch (e) { lastErr = '网络异常'; continue; }
      if (!res.ok) { lastErr = 'HTTP ' + res.status; continue; }
      var text = await res.text();
      if (/login_slogin/.test(text) || /请登录/.test(text)) {
        throw new Error('登录已过期，请重新登录后再导入');
      }
      var data;
      try { data = JSON.parse(text); } catch (e) { lastErr = '返回内容不是 JSON'; continue; }
      var list = data && data.kbList;
      if (!list || !list.length) continue;
      var courses = [];
      list.forEach(function (k) {
        var name = String(k.kcmc || '').replace(/\s+/g, ' ').trim();
        if (!name) return;
        var day = parseInt(k.xqj, 10);
        if (isNaN(day) || day < 1 || day > 7) return;
        var nums = String(k.jc || k.jcs || '').match(/\d+/g) || [];
        if (!nums.length) return;
        var sections = nums.map(Number);
        courses.push({
          name: name,
          teacher: String(k.xm || '').trim(),
          room: String(k.cdmc || k.xqmc || '').trim(),
          day: day,
          startSection: Math.min.apply(null, sections),
          endSection: Math.max.apply(null, sections),
          weeks: String(k.zcd || k.zcmc || '')
        });
      });
      if (courses.length) return { courses: courses };
    }
    throw new Error('正方新版未返回课表：' + lastErr);
  }
};
```

- [ ] **Step 3: 编译 + 现有测试回归（确保 assets 不破坏构建）**

Run: `cmd /c ".tmp\build-release.bat :app:compileDebugKotlin --console=plain -q"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/assets/jw/scripts/qiangzhi-jsxsd.js app/src/main/assets/jw/scripts/zhengfang-new.js
git commit -m "feat(script): 内置强智/正方脚本范本（页面上下文版）"
```

---

### Task 11: scripts/ 仓库成品 + mock-jw 强智课表页 + 文档

**Files:**
- Create: `scripts/index.json`、`scripts/README.md`（内容=两份 assets 脚本的拷贝，`certutil -hashfile <file> SHA256` 算出真 sha256 填入 index）
- Modify: `tools/mock-jw/server.mjs`（新增 `/jsxsd/xskb/xskb_list.do` 返回 kbtable 课表页）
- Modify: `docs/testing/mock-jw.md`（新增「§7 脚本导入 E2E」）
- Create: `docs/research/2026-10-25-script-engine-notes.md`（契约文档：给脚本作者的一页说明）

- [ ] **Step 1: mock-jw 加强智课表页**

`server.mjs` 的 qzLoginPage 常量后新增：

```js
const qzCoursePage = `<!doctype html>
<html><head><meta charset="utf-8"><title>学生课表</title></head>
<body>
<table id="kbtable">
  <tr><th>第一节</th><th>星期一</th><th>星期二</th><th>星期三</th><th>星期四</th><th>星期五</th></tr>
  <tr>
    <th>第一大节 08:20-09:55</th>
    <td><div class="kbcontent">高等数学<br/>张三<br/>实B-301<br/>1-16周(第1-16周)<br/>[01-02节]</div></td>
    <td><div class="kbcontent"></div></td>
    <td><div class="kbcontent">大学英语<br/>李四<br/>教305<br/>1-16周(第1-16周)<br/>[01-02节]</div></td>
    <td><div class="kbcontent"></div></td>
    <td><div class="kbcontent"></div></td>
  </tr>
  <tr>
    <th>第二大节 10:10-11:45</th>
    <td><div class="kbcontent"></div></td>
    <td><div class="kbcontent">数据结构<br/>王五<br/>实A-201<br/>1-8,10-16周(第1-16周)<br/>[03-04节]</div></td>
    <td><div class="kbcontent"></div></td>
    <td><div class="kbcontent">体育<br/>赵六<br/>田径场<br/>1-16周(单周)<br/>[03-04节]</div></td>
    <td><div class="kbcontent"></div></td>
  </tr>
</table>
</body></html>`;
```

路由区（`/jsxsd/ksgl/xsksap_list` 分支旁）追加：

```js
  if (path === '/jsxsd/xskb/xskb_list.do') {
    send(res, 200, 'text/html; charset=utf-8', qzCoursePage, { 'Set-Cookie': 'JSESSIONID=mock-qz-session; Path=/jsxsd' });
    return;
  }
```

- [ ] **Step 2: scripts/ 成品**

`cp app/src/main/assets/jw/scripts/*.js scripts/`；`certutil -hashfile scripts\qiangzhi-jsxsd.js SHA256`、同 zhengfang → 填入 `scripts/index.json`：

```json
{
  "indexVersion": 1,
  "scripts": [
    { "id": "qiangzhi-jsxsd", "name": "强智教务（通用）", "version": 1,
      "url": "https://raw.githubusercontent.com/Suife-yuanxing/zhijian-scripts/main/scripts/qiangzhi-jsxsd.js",
      "sha256": "<certutil 输出>", "hosts": [], "minAppVersion": "1.0.11",
      "entryHint": "登录教务后打开「学生课表」页面，再点导入" },
    { "id": "zhengfang-new", "name": "正方新版教务（通用）", "version": 1,
      "url": "https://raw.githubusercontent.com/Suife-yuanxing/zhijian-scripts/main/scripts/zhengfang-new.js",
      "sha256": "<certutil 输出>", "hosts": [], "minAppVersion": "1.0.11",
      "entryHint": "登录正方教务后任意页面即可点导入" }
  ]
}
```

`scripts/README.md`：说明该目录是公开脚本仓库（建议 `Suife-yuanxing/zhijian-scripts`）的种子内容，发布步骤 = 拷贝到公开仓库 → 换 index 里的 raw 地址 → 重算 sha256 填入。

- [ ] **Step 3: 契约文档 `docs/research/2026-10-25-script-engine-notes.md`**

给脚本作者的一页：manifest 字段表、`fetchSchedule(ctx)` 签名、课程对象字段与校验规则（直接引用 spec §1.3 表格）、两个范本的关键差异（DOM 解析 vs 同源 POST）、调试方法（Chrome devtools 不可用时用 `warnings` 字段带回中间信息）。

- [ ] **Step 4: docs/testing/mock-jw.md §7**

写「脚本导入 E2E」：启动 mock → 导入课表 → 地址填 `http://10.0.2.2:18099/jsxsd/`（真机用 PC 局域网 IP）→ 登录 → 打开 `xskb_list.do` → 顶栏脚本图标 → 选「强智教务（通用）」→ 预览出现 4 门课 → 应用。

- [ ] **Step 5: Commit**

```bash
git add scripts docs/testing/mock-jw.md docs/research/2026-10-25-script-engine-notes.md tools/mock-jw/server.mjs
git commit -m "feat(script): 脚本仓库种子、mock 强智课表页与契约文档"
```

---

### Task 12: 全量测试 + release 构建 + 真机 E2E

- [ ] **Step 1: 全量单测**

Run: `cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --console=plain"`
Expected: 原有 35 suites / 322 tests 全绿 + 新增 JwScriptManifestTest(5)、JwScriptOutputTest(5)、ScriptStoreTest(5)、ScriptIndexTest(4)

- [ ] **Step 2: release 构建**

Run: `cmd /c ".tmp\build-release.bat :app:assembleRelease --console=plain -q"`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 真机 E2E（用 `.tmp\build-release.bat :app:assembleRelease` 产物覆盖安装）**

1. `adb install -r app-release.apk`；启动 mock-jw（单节点，PORT 18099）。
2. 导入课表 → 地址 `http://<PC-IP>:18099/jsxsd/` → 打开 → （mock 直接是已登录态）→ 打开 `xskb_list.do`。
3. 顶栏脚本图标 → 选择器应显示「强智教务（通用）」并标注可运行 → 运行 → 预览 4 门课（高数 1-2 节周一 1-16 周、英语 1-2 节周三、数据结构 3-4 节周二 1-8/10-16、体育 3-4 节周四单周）→ 应用。
4. 正方路径：地址 `/jwglxt/xtgl/login_slogin.html` → 登录（mock 任意表单提交即可）→ 脚本导入 → 预览 kbList 课程。
5. 管理页：检查更新（index 指向本地 mock 静态 index.json）→ 安装/跳过提示正确；删除/停用即时生效；`dumpsys alarm` 不受影响（脚本路径无闹钟）。
6. 全部截图存 `.tmp/verify/script-*`。

- [ ] **Step 4: 收尾提交（如 E2E 期间有修正）**

```bash
git add -u -- app/src/main/java/com/zhijian/schedule app/src/test
git commit -m "fix(script): 真机 E2E 修正"
```

（仅当有修正；`git add -u` 限定路径，不碰 ScheduleScreen/WeekGrid 等用户 WIP。）

---

## Self-Review 结论

- **Spec 覆盖**：§1 契约→Task 1/2；§2 运行时/存储/内置→Task 3/6/7/10；§3 分发→Task 4/5/6/11；§4 UI→Task 9；§5 管线对接→Task 8；§6 测试→Task 1-4 + Task 11/12；§7 范围外未引入。无缺口。
- **占位符**：Task 9 的 ScriptManageScreen UI 部分明确给出状态映射规则与既有风格参照（ViewModel 完整、布局按 JwImportScreen 风格），属于「参照现有风格实现」而非缺实现——执行时若不确定以 ExamScreen/GradesScreen 的行样式为准。
- **类型一致性**：`ScriptOption.manifest`（Task 6 定义、Task 8/9 消费）、`ScriptStore.install(source, origin, sha256, onlyIfNewer)`（Task 3 定义、Task 9 调用）、`JwScriptRunner.RunResult`（Task 7 定义、Task 9 消费）、`JwScriptOutput.ParseResult`（Task 2 定义、Task 8 消费）均已对齐。`ZhengFangNewAdapter.mergeDuplicates` 改 internal 后 Task 2 才能复用。
