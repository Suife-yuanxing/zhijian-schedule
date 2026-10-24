# 考试安排与成绩统计（学业中心一期）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 手动 + 强智教务抓取的考试管理（列表/编辑/合并/两档考前闹钟），今日页倒计时卡，胶囊"伪课程"联动，成绩页最新学期口径 + 学期绩点趋势。

**Architecture:** 数据层在既有 `ExamEntity`/`ExamDao` 上补字段与 CRUD（Room v2→v3 单列迁移）；抓取照 `GradeFetcher` 模式（WebView Cookie 重放 + SSO 静默恢复）；合并决策抽成纯函数 `ExamMergePlan`（可单测）；提醒走独立 `ExamAlarmScheduler`（requestCode 号段与课程隔离）；胶囊复用现有状态机，考试当天映射为伪课程。UI 按 `SecondaryTopBar` + ProfileEntryPage 共享容器既有模式。

**Tech Stack:** Kotlin 2.2.21 / Compose / Hilt / Room 2.8.5 / OkHttp / Jsoup / kotlinx.serialization / JUnit4（无 Robolectric）

**Spec:** [docs/superpowers/specs/2026-10-24-exam-academic-center-design.md](../specs/2026-10-24-exam-academic-center-design.md)

**与规格的命名偏差：** 规格里的 `GradeStats.kt` 与既有 `GradeParser.kt` 中的 `data class GradeStats` 同名冲突，实现命名为 `GradeTrend`（core/jw/GradeTrend.kt），职责不变。

---

## 开工前检查（preflight）

工作区根目录：`D:\课程表`（路径含中文；单测必须走 `.tmp\run-tests.bat`，它解决用户目录单引号 + GBK argfile 两个问题）。

- [ ] **P1: 基线单测全绿**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --console=plain"
```

预期 `BUILD SUCCESSFUL`，23 suite / 233 tests、0 失败。若失败先停下报告。

- [ ] **P2: 工作区状态确认**

```bat
git -C "D:\课程表" status --short
```

预期只有三个 UI 文件的既有改动（ScheduleScreen/WeekGrid/AppRoot）。本计划所有提交都 `git add` 明确列出的文件，**永远不用 `git add -A`**——不卷入用户未提交的改动。

- [ ] **P3: 记住三条命令**

```bat
rem 单测（可 --tests 过滤）
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.ExamParserTest --console=plain"
rem 编译主源码（快速语法检查，不跑测试）
cmd /c ".tmp\run-tests.bat :app:compileDebugKotlin --console=plain"
rem 提交（示例）
git -C "D:\课程表" add <files> && git -C "D:\课程表" commit -m "..."
```

---

## 文件结构

**新建（主源码）**

| 文件 | 职责 |
|---|---|
| `core/jw/ExamParser.kt` | 纯解析：考试安排 HTML → `ExamRecord`；含 `ExamRecord` 与 epoch 换算 |
| `core/jw/ExamFetcher.kt` | 强智考试抓取：候选端点 + 会话恢复（网络薄壳，无单测） |
| `core/repository/ExamMergePlan.kt` | 纯决策：既有考试 × 教务列表 → 增/改/删/保留手动 |
| `core/jw/GradeTrend.kt` | 纯统计：按学期绩点/学分（排除补考重修与无绩点行） |
| `alarm/ExamAlarms.kt` | 纯逻辑：两档提醒时刻 + requestCode/notificationId 号段 |
| `alarm/ExamAlarmScheduler.kt` | 精确闹钟登记/取消（照 AlarmScheduler 模式） |
| `alarm/ExamReminderReceiver.kt` | 触发时发考试提醒通知 |
| `alarm/ExamReminderCoordinator.kt` | observe activeExams → 全量重排（照 ReminderCoordinator 模式） |
| `feature/exam/ExamCountdown.kt` | 纯文案：倒计时徽标 / 卡片标题 / 日期行 |
| `feature/exam/ExamViewModel.kt` | 列表状态 + 手动保存/删除 + 教务导入 |
| `feature/exam/ExamScreen.kt` | 考试列表页 + 编辑/删除弹窗 + 空态 |

**修改（主源码）**

| 文件 | 改动 |
|---|---|
| `core/model/Models.kt` | 加 `ExamDraft` |
| `core/data/db/Entities.kt` | `ExamEntity` 加 `manuallyEdited` |
| `core/data/db/Daos.kt` | `ExamDao` 加 `getByTable/getById/deleteById` |
| `core/data/db/AppDatabase.kt` | version 2→3 |
| `core/data/db/Migrations.kt` | `MIGRATION_2_3` |
| `di/AppModule.kt` | 注册迁移 |
| `core/repository/ScheduleRepository.kt` | `saveExam/deleteExam/applyExamMerge` |
| `alarm/Notifications.kt` | `showExamReminder`（渠道 CH_EXAM 已存在） |
| `alarm/BootReceiver.kt` | 重启恢复考试闹钟 |
| `capsule/CapsulePlan.kt` | `buildCapsulePlan` 加 `exams` 参数 + 伪课程 + nextText 含考试 |
| `capsule/CapsuleCoordinator.kt` | 传入 activeExams |
| `core/jw/GradesScreen.kt`（feature/grades） | 最新学期行 + 学期趋势卡 |
| `feature/exam/ExamScreen.kt` 等 | 见上 |
| `feature/profile/ProfileEntryTransition.kt` | `ProfileEntryKeys.EXAMS` |
| `feature/profile/ProfileViewModel.kt` | 考试预览副文案 |
| `feature/profile/ProfileScreen.kt` | 「考试安排」入口行 |
| `feature/today/TodayViewModel.kt` | `nextExam`（14 天窗口） |
| `feature/today/TodayScreen.kt` | 考试倒计时卡 |
| `navigation/AppRoot.kt` | `PROFILE_EXAMS` 路由 + 两处回调接线 |
| `app/src/main/AndroidManifest.xml` | 声明 `ExamReminderReceiver` |
| `tools/mock-jw/server.mjs` | 考试安排页 |

**新建（测试）**：`ExamParserTest` / `ExamMergeTest` / `ExamAlarmsTest` / `ExamCountdownTest` / `GradeTrendTest` / `CapsulePlanTest`（增补）

---

### Task 1: 数据层——ExamEntity 字段 + Room v3 迁移 + DAO

**Files:** Modify `core/data/db/Entities.kt`、`core/data/db/Daos.kt`、`core/data/db/AppDatabase.kt`、`core/data/db/Migrations.kt`、`di/AppModule.kt`；Modify `core/model/Models.kt`

- [ ] **Step 1: Models.kt 加 ExamDraft**（追加在 `CourseDraft` 之后）

```kotlin
/** 教务考试安排的中转模型（未落库） */
@Serializable
data class ExamDraft(
    val name: String,
    val room: String = "",
    val seat: String = "",
    /** 开考时刻 epoch millis */
    val examTime: Long,
    val note: String = "",
)
```

- [ ] **Step 2: ExamEntity 加字段**（`note` 之后、`createdAt` 之前）

```kotlin
    /** 用户手动创建/编辑过的考试，教务同步永不覆盖（与 CourseEntity 同语义） */
    val manuallyEdited: Boolean = false,
```

- [ ] **Step 3: ExamDao 补方法**

```kotlin
    @Query("SELECT * FROM exam WHERE tableId = :tableId ORDER BY examTime ASC")
    suspend fun getByTable(tableId: Long): List<ExamEntity>

    @Query("SELECT * FROM exam WHERE id = :id")
    suspend fun getById(id: Long): ExamEntity?

    @Query("DELETE FROM exam WHERE id = :id")
    suspend fun deleteById(id: Long)
```

- [ ] **Step 4: 迁移**：`AppDatabase` version = 3；`Migrations.kt` 追加：

```kotlin
/** v2→v3：考试表补「手动编辑」标记（教务同步永不覆盖） */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `exam` ADD COLUMN `manuallyEdited` INTEGER NOT NULL DEFAULT 0")
    }
}
```

`AppModule.kt`：import `MIGRATION_2_3`，`.addMigrations(MIGRATION_1_2, MIGRATION_2_3)`。

- [ ] **Step 5: 编译验证** → `compileDebugKotlin` BUILD SUCCESSFUL
- [ ] **Step 6: Commit**

```bat
git -C "D:\课程表" add app/src/main/java/com/zhijian/schedule/core/model/Models.kt app/src/main/java/com/zhijian/schedule/core/data/db/Entities.kt app/src/main/java/com/zhijian/schedule/core/data/db/Daos.kt app/src/main/java/com/zhijian/schedule/core/data/db/AppDatabase.kt app/src/main/java/com/zhijian/schedule/core/data/db/Migrations.kt app/src/main/java/com/zhijian/schedule/di/AppModule.kt
git -C "D:\课程表" commit -m "feat(exam): 考试表加 manuallyEdited 标记并迁移到 Room v3"
```

### Task 2: ExamParser（TDD）

**Files:** Create `core/jw/ExamParser.kt`；Test `app/src/test/java/com/zhijian/schedule/ExamParserTest.kt`

- [ ] **Step 1: 先写失败测试**

```kotlin
package com.zhijian.schedule

import com.zhijian.schedule.core.jw.ExamParser
import org.junit.Assert.assertEquals
import org.junit.Test

class ExamParserTest {

    private val page = """
        <html><body><table id="dataList">
        <tr><th>序号</th><th>课程名称</th><th>考试时间</th><th>考场</th><th>座位号</th><th>备注</th></tr>
        <tr><td>1</td><td>高等数学</td><td>2026-11-02 14:00~15:50</td><td>教305</td><td>12</td><td>闭卷</td></tr>
        <tr><td>2</td><td>大学英语</td><td>2026-11-05 08:20-10:00</td><td>教102</td><td>3</td><td></td></tr>
        </table></body></html>
    """.trimIndent()

    @Test fun `解析课程名 考场 座位 与开考时刻`() {
        val list = ExamParser.parse(page)
        assertEquals(2, list.size)
        val first = list[0]
        assertEquals("高等数学", first.name)
        assertEquals("教305", first.room)
        assertEquals("12", first.seat)
        assertEquals("闭卷", first.note)
        assertEquals(2026, first.examTime.year)
        assertEquals(11, first.examTime.monthValue)
        assertEquals(2, first.examTime.dayOfMonth)
        assertEquals(14, first.examTime.hour)
        assertEquals(0, first.examTime.minute)
    }

    @Test fun `无法定位时间的行被跳过`() {
        val html = page.replace("<td>2026-11-05 08:20-10:00</td>", "<td>待定</td>")
        val list = ExamParser.parse(html)
        assertEquals(1, list.size)
        assertEquals("高等数学", list[0].name)
    }

    @Test fun `空表与登录页返回空列表`() {
        assertEquals(0, ExamParser.parse("<html><body></body></html>").size)
        assertEquals(0, ExamParser.parse("type=\"password\"").size)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**（`ExamParser` 未定义，编译失败即预期）
- [ ] **Step 3: 实现**

```kotlin
package com.zhijian.schedule.core.jw

import java.time.LocalDateTime
import java.time.ZoneId
import org.jsoup.Jsoup

/** 一条考试安排（强智 jsxsd 考试安排页） */
data class ExamRecord(
    val name: String,
    val room: String,
    val seat: String,
    /** 开考时刻（本地时区） */
    val examTime: LocalDateTime,
    val note: String,
)

object ExamParser {

    /** "2026-11-02 14:00~15:50" 取起点；兼容 / 与 . 分隔 */
    private val TIME = Regex("""(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\s+(\d{1,2}):(\d{2})""")

    /** 列头模糊定位，容忍列序与措辞差异（同 GradeParser 思路） */
    fun parse(html: String): List<ExamRecord> {
        if (isSessionDeadPage(html)) return emptyList()
        val doc = Jsoup.parse(html)
        val table = doc.getElementById("dataList") ?: doc.selectFirst("table") ?: return emptyList()
        val rows = table.select("tr")
        if (rows.size < 2) return emptyList()
        val header = rows.first().select("th,td").map { it.text().trim() }
        fun col(vararg labels: String) = header.indexOfFirst { h -> labels.any { h.contains(it) } }
        val iName = col("课程名称", "课程")
        val iTime = col("考试时间", "时间", "日期")
        val iRoom = col("考场", "考试地点", "地点")
        val iSeat = col("座位号", "座位")
        val iNote = col("备注")
        if (iName < 0 || iTime < 0) return emptyList()
        fun cell(cells: List<String>, idx: Int) = if (idx in cells.indices) cells[idx] else ""
        return rows.drop(1).mapNotNull { tr ->
            val cells = tr.select("td").map { it.text().replace(Regex("\\s+"), " ").trim() }
            if (cells.size <= maxOf(iName, iTime)) return@mapNotNull null
            val name = cell(cells, iName)
            val m = TIME.find(cell(cells, iTime)) ?: return@mapNotNull null
            ExamRecord(
                name = name,
                room = cell(cells, iRoom),
                seat = cell(cells, iSeat),
                examTime = LocalDateTime.of(
                    m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(),
                    m.groupValues[4].toInt(), m.groupValues[5].toInt(),
                ),
                note = cell(cells, iNote),
            )
        }.filter { it.name.isNotBlank() }.distinctBy { it.name to it.examTime }
    }

    fun epochMillis(t: LocalDateTime): Long =
        t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
```

- [ ] **Step 4: 跑测试通过**（3 用例 PASS；全量 `testDebugUnitTest` 不回归）
- [ ] **Step 5: Commit** `feat(exam): 强智考试安排 HTML 解析器（含 fixture 单测）`，add 两个文件

### Task 3: ExamMergePlan（TDD）+ Repository 接线

**Files:** Create `core/repository/ExamMergePlan.kt`；Modify `core/repository/ScheduleRepository.kt`；Test `ExamMergeTest.kt`

- [ ] **Step 1: 先写失败测试**

```kotlin
package com.zhijian.schedule

import com.zhijian.schedule.core.data.db.ExamEntity
import com.zhijian.schedule.core.model.ExamDraft
import com.zhijian.schedule.core.repository.ExamMergePlan
import org.junit.Assert.assertEquals
import org.junit.Test

class ExamMergeTest {
    private val t1 = 1_760_000_000_000L
    private val t2 = 1_760_100_000_000L

    private fun old(id: Long, name: String, time: Long, room: String = "旧考场", manual: Boolean = false) =
        ExamEntity(id = id, tableId = 1, name = name, room = room, examTime = time, manuallyEdited = manual)

    @Test fun `教务新增_变更_取消分别归入对应操作`() {
        val existing = listOf(old(1, "高等数学", t1), old(2, "大学英语", t2))
        val fetched = listOf(
            ExamDraft(name = "高等数学", room = "教305", examTime = t1), // 变更
            ExamDraft(name = "数据结构", examTime = t2),                 // 新增
            // 大学英语消失 → 取消
        )
        val r = ExamMergePlan.plan(existing, fetched)
        assertEquals(listOf("数据结构"), r.toInsert.map { it.name })
        assertEquals(listOf(1L), r.toUpdate.map { it.id })
        assertEquals("教305", r.toUpdate.single().room)
        assertEquals(listOf(2L), r.toDeleteIds)
        assertEquals(0, r.keptManual)
    }

    @Test fun `手动编辑的考试永不覆盖也不删除`() {
        val existing = listOf(old(1, "高等数学", t1, manual = true), old(2, "大学英语", t2))
        val fetched = listOf(
            ExamDraft(name = "高等数学", room = "教888", examTime = t1),
            ExamDraft(name = "数据结构", examTime = t2),
        )
        val r = ExamMergePlan.plan(existing, fetched)
        assertEquals(0, r.toUpdate.size)
        assertEquals(0, r.toDeleteIds.size)
        assertEquals(1, r.keptManual)
    }

    @Test fun `无变化时changed为false且摘要为无变化`() {
        val existing = listOf(old(1, "高等数学", t1, room = "教305"))
        val fetched = listOf(ExamDraft(name = "高等数学", room = "教305", examTime = t1))
        val r = ExamMergePlan.plan(existing, fetched)
        assertEquals(false, r.changed)
        assertEquals("考试安排无变化", r.summaryText())
    }
}
```

- [ ] **Step 2: 确认失败**（`ExamMergePlan` 未定义）
- [ ] **Step 3: 实现**

```kotlin
package com.zhijian.schedule.core.repository

import com.zhijian.schedule.core.data.db.ExamEntity
import com.zhijian.schedule.core.model.ExamDraft

/**
 * 教务考试合并决策（纯逻辑，可单测）。匹配键 = 课程名 + 开考时刻。
 * 手动编辑（manuallyEdited）的考试永不更新、永不删除，只计数进摘要。
 */
object ExamMergePlan {

    data class Result(
        val toInsert: List<ExamEntity> = emptyList(),
        val toUpdate: List<ExamEntity> = emptyList(),
        val toDeleteIds: List<Long> = emptyList(),
        /** 手动编辑被保留原样的场数 */
        val keptManual: Int = 0,
    ) {
        val changed: Boolean
            get() = toInsert.isNotEmpty() || toUpdate.isNotEmpty() || toDeleteIds.isNotEmpty()

        fun summaryText(): String = buildList {
            if (toInsert.isNotEmpty()) add("新增 ${toInsert.size}")
            if (toUpdate.isNotEmpty()) add("更新 ${toUpdate.size}")
            if (toDeleteIds.isNotEmpty()) add("取消 ${toDeleteIds.size}")
            if (keptManual > 0) add("手动 $keptManual 场保留")
        }.joinToString(" · ").ifEmpty { "考试安排无变化" }
    }

    fun plan(existing: List<ExamEntity>, fetched: List<ExamDraft>): Result {
        val fetchedByKey = fetched.associateBy { it.name to it.examTime }
        val existingByKey = existing.associateBy { it.name to it.examTime }
        val toInsert = mutableListOf<ExamEntity>()
        val toUpdate = mutableListOf<ExamEntity>()
        var keptManual = 0
        fetchedByKey.forEach { (key, draft) ->
            val old = existingByKey[key]
            when {
                old == null -> toInsert += ExamEntity(
                    tableId = 0, // 落库时由 Repository 补 tableId
                    name = draft.name,
                    room = draft.room,
                    seat = draft.seat,
                    examTime = draft.examTime,
                    note = draft.note,
                )
                old.manuallyEdited -> keptManual++
                old.room != draft.room || old.seat != draft.seat || old.note != draft.note ->
                    toUpdate += old.copy(room = draft.room, seat = draft.seat, note = draft.note)
            }
        }
        val toDelete = existing
            .filter { !it.manuallyEdited && (it.name to it.examTime) !in fetchedByKey }
            .map { it.id }
        return Result(toInsert, toUpdate, toDelete, keptManual)
    }
}
```

- [ ] **Step 4: ScheduleRepository 加考试块**（放在「课程 CRUD」块之后）

```kotlin
    // ---------- 考试 ----------

    /** 手动保存考试；新建或编辑均视为手动，教务同步永不覆盖 */
    suspend fun saveExam(
        name: String,
        room: String,
        seat: String,
        examTime: Long,
        note: String,
        examId: Long? = null,
    ): Long {
        val tableId = currentTableId() ?: return -1
        return if (examId == null) {
            db.examDao().insert(
                ExamEntity(
                    tableId = tableId, name = name, room = room, seat = seat,
                    examTime = examTime, note = note, manuallyEdited = true,
                )
            )
        } else {
            val old = db.examDao().getById(examId) ?: return -1
            db.examDao().update(
                old.copy(
                    name = name, room = room, seat = seat,
                    examTime = examTime, note = note, manuallyEdited = true,
                )
            )
            examId
        }
    }

    suspend fun deleteExam(id: Long) = db.examDao().deleteById(id)

    /**
     * 应用教务考试合并（键 = 课程名 + 开考时刻，见 ExamMergePlan）。
     * @return null 表示没有活动课表
     */
    suspend fun applyExamMerge(fetched: List<ExamDraft>): ExamMergePlan.Result? {
        val tableId = currentTableId() ?: return null
        val result = ExamMergePlan.plan(db.examDao().getByTable(tableId), fetched)
        if (!result.changed) return result
        db.withTransaction {
            result.toInsert.forEach { db.examDao().insert(it.copy(tableId = tableId)) }
            result.toUpdate.forEach { db.examDao().update(it) }
            result.toDeleteIds.forEach { db.examDao().deleteById(it) }
        }
        return result
    }
```

import 增加 `ExamEntity`（已 import）与 `ExamMergePlan`、`ExamDraft`。`saveExam` 手动路径的提醒重排由 Task 5 的 Coordinator 自动响应。

- [ ] **Step 5: 测试通过 + 编译通过**
- [ ] **Step 6: Commit** `feat(exam): 考试合并决策（手动项保护）与仓库层 CRUD`

### Task 4: ExamFetcher

**Files:** Create `core/jw/ExamFetcher.kt`

- [ ] **Step 1: 实现**（照 GradeFetcher 模式；无网络单测，逻辑已在 Parser/Merge 层测尽）

```kotlin
package com.zhijian.schedule.core.jw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 考试安排抓取：强智 jsxsd，与课表/成绩共用 WebView 会话 Cookie（HTTP 重放）。
 * 各校部署路径不一，候选端点逐个尝试；页面结构差异由 ExamParser 容错。
 * 真实学校端点确认见 spec §2 开放项——先在 tools/mock-jw 跑通 E2E。
 */
object ExamFetcher {

    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    suspend fun fetch(client: OkHttpClient, baseUrl: String): List<ExamRecord> =
        withContext(Dispatchers.IO) {
            try {
                fetchRecords(client, baseUrl)
            } catch (e: JwException.NeedsLogin) {
                if (!JwSessionRecovery.trySilentRecover(client, QiangZhiAdapter, baseUrl)) throw e
                fetchRecords(client, baseUrl)
            }
        }

    private suspend fun fetchRecords(client: OkHttpClient, baseUrl: String): List<ExamRecord> =
        withContext(Dispatchers.IO) {
            val base = QiangZhiAdapter.normalizeBase(baseUrl)
            val referer = "${base}framework/xsMain.jsp"
            val candidates = listOf(
                "${base}ksgl/xsksap_list",
                "${base}ksgl/xskksap_list",
                "${base}ksgl/xskksap.do",
            )
            var lastError: String? = null
            for (url in candidates) {
                val html = try {
                    get(client, url, referer)
                } catch (e: JwException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e.message
                    continue
                }
                if (isSessionDeadPage(html)) throw JwException.NeedsLogin()
                val records = ExamParser.parse(html)
                if (records.isNotEmpty()) return@withContext records
                lastError = "未在页面中找到考试安排"
            }
            throw JwException.ParseFailed("抓取失败：${lastError ?: "页面结构不匹配"}")
        }

    private fun get(client: OkHttpClient, url: String, referer: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .header("Referer", referer)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpFailure(response.code)
            return response.body?.string().orEmpty()
        }
    }
}
```

- [ ] **Step 2: 编译通过**
- [ ] **Step 3: Commit** `feat(exam): 强智考试安排抓取（会话复用 + 静默恢复）`

### Task 5: 提醒链路——时刻计算(TDD) + 调度器 + 通知 + 接收器 + Coordinator + 装配

**Files:** Create `alarm/ExamAlarms.kt`、`alarm/ExamAlarmScheduler.kt`、`alarm/ExamReminderReceiver.kt`、`alarm/ExamReminderCoordinator.kt`；Modify `alarm/Notifications.kt`、`alarm/BootReceiver.kt`、`ZhiJianApp.kt`、`AndroidManifest.xml`；Test `ExamAlarmsTest.kt`

- [ ] **Step 1: 先写失败测试**

```kotlin
package com.zhijian.schedule

import com.zhijian.schedule.alarm.ExamAlarms
import com.zhijian.schedule.core.data.db.ExamEntity
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExamAlarmsTest {
    private val now: LocalDateTime = LocalDateTime.of(2026, 11, 1, 12, 0)

    private fun examAt(at: LocalDateTime) = ExamEntity(
        id = 7, tableId = 1, name = "高等数学",
        examTime = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    )

    @Test fun `未来考试给出两档提醒`() {
        val slots = ExamAlarms.slots(examAt(now.plusHours(30)), now)
        assertEquals(2, slots.size)
        assertEquals(24 * 60, slots[0].leadMinutes)
        assertEquals(60, slots[1].leadMinutes)
        assertEquals(now.plusHours(6), slots[0].at) // 30h - 24h
    }

    @Test fun `已过档位被剔除`() {
        val slots = ExamAlarms.slots(examAt(now.plusHours(10)), now)
        assertEquals(1, slots.size)
        assertEquals(60, slots.single().leadMinutes)
    }

    @Test fun `已开考的考试没有提醒`() {
        assertTrue(ExamAlarms.slots(examAt(now.minusMinutes(1)), now).isEmpty())
    }

    @Test fun `requestCode与课程号段隔离`() {
        val rc = ExamAlarms.requestCode(5, 1)
        assertTrue(rc >= ExamAlarms.RC_BASE)
        assertEquals(ExamAlarms.requestCode(5, 0) + 1, rc)
    }
}
```

- [ ] **Step 2: 确认失败**（`ExamAlarms` 未定义）
- [ ] **Step 3: 实现 ExamAlarms**

```kotlin
package com.zhijian.schedule.alarm

import com.zhijian.schedule.core.data.db.ExamEntity
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** 考前两档提醒的时刻与请求码计算（纯逻辑，可单测） */
object ExamAlarms {

    /** 提前量（分钟）：考前一天 + 考前一小时 */
    val LEAD_MINUTES = listOf(24 * 60, 60)

    data class Slot(val at: LocalDateTime, val leadMinutes: Int)

    /** 一场考试未过期的提醒档位（按提前量从大到小） */
    fun slots(exam: ExamEntity, now: LocalDateTime): List<Slot> {
        val examAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(exam.examTime), ZoneId.systemDefault())
        return LEAD_MINUTES.mapNotNull { lead ->
            val at = examAt.minusMinutes(lead.toLong())
            if (at.isAfter(now)) Slot(at, lead) else null
        }
    }

    /**
     * requestCode 号段。课程闹钟直接用 courseId（小整数）当 requestCode，
     * 胶囊占用 3002-3004；考试从 1_000_000 起，examId × 2 + 槽位。
     */
    const val RC_BASE = 1_000_000

    fun requestCode(examId: Long, slotIndex: Int): Int =
        (RC_BASE + examId * 2 + slotIndex).toInt()

    /** 通知 id：与同步通知（2001/2002）、课程提醒（courseId）隔离 */
    fun notificationId(examId: Long): Int = (2_000_000 + examId).toInt()
}
```

- [ ] **Step 4: 跑测试通过**

- [ ] **Step 5: ExamAlarmScheduler**

```kotlin
package com.zhijian.schedule.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.zhijian.schedule.core.data.db.ExamEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 考前精确提醒（两档，见 ExamAlarms）：考试固定时刻、无需滚动续排，
 * 全量重排即可；考试删除/修改由 ExamReminderCoordinator 监听触发。
 */
@Singleton
class ExamAlarmScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun rescheduleAll(exams: List<ExamEntity>, now: LocalDateTime = LocalDateTime.now()) {
        val exactAllowed = canScheduleExact()
        exams.forEach { cancel(it.id) }
        exams.forEach { exam ->
            ExamAlarms.slots(exam, now).forEachIndexed { index, slot ->
                schedule(exam, slot, index, exactAllowed)
            }
        }
    }

    fun cancel(examId: Long) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        (ExamAlarms.LEAD_MINUTES.indices).forEach { slot ->
            val pi = PendingIntent.getBroadcast(
                context,
                ExamAlarms.requestCode(examId, slot),
                Intent(context, ExamReminderReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            pi?.let { manager.cancel(it) }
        }
    }

    private fun schedule(exam: ExamEntity, slot: ExamAlarms.Slot, slotIndex: Int, exactAllowed: Boolean) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = slot.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val intent = Intent(context, ExamReminderReceiver::class.java).apply {
            putExtra(ExamReminderReceiver.EXTRA_EXAM_ID, exam.id)
            putExtra(ExamReminderReceiver.EXTRA_NAME, exam.name)
            putExtra(ExamReminderReceiver.EXTRA_ROOM, exam.room)
            putExtra(ExamReminderReceiver.EXTRA_SEAT, exam.seat)
            putExtra(ExamReminderReceiver.EXTRA_LEAD, slot.leadMinutes)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            ExamAlarms.requestCode(exam.id, slotIndex),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (exactAllowed) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun canScheduleExact(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
    }
}
```

- [ ] **Step 6: Notifications.showExamReminder**（追加到 Notifications.kt 末尾；渠道 CH_EXAM 已存在）

```kotlin
    fun showExamReminder(
        context: Context,
        examId: Long,
        name: String,
        room: String,
        seat: String,
        leadMinutes: Int,
    ) {
        if (!canNotify(context)) return
        val where = listOf(room.ifBlank { null }, seat.takeIf { it.isNotBlank() }?.let { "$it 号座" })
            .filterNotNull().joinToString(" · ")
        val whenText = if (leadMinutes >= 60) "考试在 ${leadMinutes / 60} 小时后开始" else "考试在 $leadMinutes 分钟后开始"
        val notification = NotificationCompat.Builder(context, CH_EXAM)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("$name · 考试提醒")
            .setContentText(listOf(whenText, where).filter { it.isNotBlank() }.joinToString(" · "))
            .setSubText("纸间课表")
            .setAutoCancel(true)
            .setContentIntent(activityIntent(context, requestCode = 5100))
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(ExamAlarms.notificationId(examId), notification)
        }
    }
```

- [ ] **Step 7: ExamReminderReceiver**

```kotlin
package com.zhijian.schedule.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.AndroidEntryPoint

/** 考前提醒触发：数据随 intent 携带，无需回查数据库（考试时刻固定，无需续排） */
@AndroidEntryPoint
class ExamReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val examId = intent.getLongExtra(EXTRA_EXAM_ID, -1L)
        if (examId <= 0) return
        Notifications.showExamReminder(
            context = context,
            examId = examId,
            name = intent.getStringExtra(EXTRA_NAME).orEmpty(),
            room = intent.getStringExtra(EXTRA_ROOM).orEmpty(),
            seat = intent.getStringExtra(EXTRA_SEAT).orEmpty(),
            leadMinutes = intent.getIntExtra(EXTRA_LEAD, 60),
        )
    }

    companion object {
        const val EXTRA_EXAM_ID = "exam_id"
        const val EXTRA_NAME = "name"
        const val EXTRA_ROOM = "room"
        const val EXTRA_SEAT = "seat"
        const val EXTRA_LEAD = "lead"
    }
}
```

- [ ] **Step 8: ExamReminderCoordinator**

```kotlin
package com.zhijian.schedule.alarm

import com.zhijian.schedule.core.repository.ScheduleRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 监听考试数据变化，全量重排考前提醒（增删改/导入后均生效） */
@Singleton
class ExamReminderCoordinator @Inject constructor(
    private val repository: ScheduleRepository,
    private val examAlarmScheduler: ExamAlarmScheduler,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    @OptIn(FlowPreview::class)
    fun start() {
        if (started) return
        started = true
        scope.launch {
            repository.activeExams
                .distinctUntilChanged()
                .debounce(800)
                .collect { exams -> examAlarmScheduler.rescheduleAll(exams) }
        }
    }

    /** 设备重启 / 应用更新后闹钟被清空，主动触发一次收集 */
    fun refresh() = start()
}
```

- [ ] **Step 9: 装配**：`AndroidManifest.xml` 在 `ReminderReceiver` 声明旁加

```xml
        <receiver
            android:name=".alarm.ExamReminderReceiver"
            android:exported="false" />
```

`BootReceiver.onReceive` 的 BOOT 分支加 `examReminderCoordinator.refresh()`（注入 `@Inject lateinit var examReminderCoordinator: ExamReminderCoordinator`）；`ZhiJianApp.onCreate` 注入 `examReminderCoordinator` 并在 `reminderCoordinator.start()` 后调用 `examReminderCoordinator.start()`。

- [ ] **Step 10: 编译 + 全量测试通过**
- [ ] **Step 11: Commit** `feat(exam): 考前两档精确提醒（24h/1h，号段与课程隔离）`

### Task 6: 胶囊伪课程（TDD）

**Files:** Modify `capsule/CapsulePlan.kt`、`capsule/CapsuleCoordinator.kt`；Test 增补 `CapsulePlanTest.kt`

- [ ] **Step 1: 先写失败测试**（加到既有 CapsulePlanTest）

```kotlin
    @Test fun `考试当天映射为伪课程且与课程同槽时优先`() {
        // exam 当天 14:00 开考；course 同日 14:20 第一节
        // 期望 14:10 时 plan 为 Ongoing 且 course.name 以「 · 考试」结尾
    }

    @Test fun `考试进入DayDone的nextText`() {
        // exam 在明天，无课程 → plan 为 DayDone 且 nextText 含「考试」
    }
```

（按现有测试文件的 timetable/courses/sections 构造辅助函数补全具体断言；时间取开考后 10 分钟。）

- [ ] **Step 2: 确认失败**
- [ ] **Step 3: CapsulePlan 改造**

1. `buildCapsulePlan` 签名加 `exams: List<ExamEntity> = emptyList()`（最后一个参数）。
2. 在 `todayCourses` 计算后、状态机判断前插入：

```kotlin
    // 考试当天的考试映射为伪课程排在最前：与真实课程同槽时考试优先（考试日通常停课）
    val examCourses = exams.asSequence()
        .mapNotNull { e ->
            val start = LocalDateTime.ofInstant(Instant.ofEpochMilli(e.examTime), ZoneId.systemDefault())
            if (start.toLocalDate() != today) return@mapNotNull null
            val endTime = start.toLocalTime().plusMinutes(EXAM_PSEUDO_DURATION_MINUTES)
            CapsuleCourse(
                name = "${e.name} · 考试",
                room = listOf(
                    e.room.takeIf { it.isNotBlank() },
                    e.seat.takeIf { it.isNotBlank() }?.let { "$it 号座" },
                ).filterNotNull().joinToString(" · "),
                teacher = "",
                color = EXAM_COLOR,
                start = start.toLocalTime(),
                end = if (endTime <= start.toLocalTime()) LocalTime.MAX else endTime, // 跨零点截断
                sectionText = "开考 ${start.toLocalTime()}",
            )
        }
        .toList()
    val allToday = examCourses + todayCourses
```

3. 后续所有 `todayCourses` 引用（进行中判断、下一节判断、DayDone）改用 `allToday`。
4. `nextOccurrenceText` 增加 `exams` 参数：每一天先取课程首节、再取当天最早的考试，二者取较早者拼 `nextText`（考试显示 `明天 14:00 · 高等数学考试`）。
5. 文件内加常量：

```kotlin
/** 考试伪课程的展示时长：ExamEntity 无时长字段，按 120 分钟计（已知限制，见 spec §11） */
private const val EXAM_PSEUDO_DURATION_MINUTES = 120L

/** 考试伪课程色：陶土红（纯数据层无主题访问，用固定色值） */
private const val EXAM_COLOR = 0xFFC4704F.toInt()
```

- [ ] **Step 4: CapsuleCoordinator.renderNow 传入** `exams = repository.activeExams.first()`；import `kotlinx.coroutines.flow.first` 已有。
- [ ] **Step 5: 测试通过 + 编译通过**
- [ ] **Step 6: Commit** `feat(capsule): 考试当天映射伪课程，交卷倒计时复用提醒节点`

### Task 7: 成绩最新学期口径 + 学期趋势（TDD）

**Files:** Create `core/jw/GradeTrend.kt`；Modify `feature/grades/GradesScreen.kt`；Test `GradeTrendTest.kt`

- [ ] **Step 1: 先写失败测试**

```kotlin
package com.zhijian.schedule

import com.zhijian.schedule.core.jw.GradeRecord
import com.zhijian.schedule.core.jw.GradeTrend
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GradeTrendTest {
    private fun rec(term: String, name: String, credit: Double, gpa: Double?, retake: String = "") =
        GradeRecord(term, "C$name", name, "85", "", credit, null, gpa, retake, "", "", "", "")

    @Test fun `按学分加权且只计有绩点课程`() {
        val s = GradeTrend.perTerm(
            listOf(rec("2025-2026-1", "A", 3.0, 4.0), rec("2025-2026-1", "B", 2.0, 3.0)),
        ).single()
        assertEquals(3.6, s.gpa!!, 1e-9) // (3*4 + 2*3) / 5
        assertEquals(5.0, s.credits, 1e-9)
        assertEquals(2, s.courseCount)
    }

    @Test fun `补考重修与无绩点行排除`() {
        val s = GradeTrend.latest(
            listOf(
                rec("2025-2026-1", "A", 3.0, 4.0),
                rec("2025-2026-1", "B", 2.0, 1.0, retake = "2025-2026-2"),
                rec("2025-2026-1", "C", 2.0, null),
            ),
        )!!
        assertEquals(1, s.courseCount)
        assertEquals(4.0, s.gpa!!, 1e-9)
    }

    @Test fun `latest取学期名最大的一期`() {
        val latest = GradeTrend.latest(
            listOf(rec("2024-2025-2", "A", 3.0, 3.0), rec("2025-2026-1", "B", 3.0, 4.0)),
        )!!
        assertEquals("2025-2026-1", latest.term)
    }

    @Test fun `空数据返回null`() {
        assertNull(GradeTrend.latest(emptyList()))
    }
}
```

- [ ] **Step 2: 确认失败**
- [ ] **Step 3: 实现 GradeTrend**

```kotlin
package com.zhijian.schedule.core.jw

/**
 * 学期维度的绩点统计（与 GradeParser.stats 的全量口径分开）：
 * 只计「有绩点且非补考重修」的课程——等级制课程与补考记录不参与 GPA 与学分。
 */
object GradeTrend {

    data class TermSummary(
        val term: String,
        /** 加权绩点；该学期无可用绩点时 null */
        val gpa: Double?,
        /** 参与统计的学分合计 */
        val credits: Double,
        val courseCount: Int,
    )

    fun perTerm(records: List<GradeRecord>): List<TermSummary> =
        records.asSequence()
            .filter { it.gpa != null && it.retakeTerm.isBlank() }
            .groupBy { it.term }
            .map { (term, rows) ->
                TermSummary(
                    term = term,
                    gpa = weighted(rows),
                    credits = rows.sumOf { it.credit },
                    courseCount = rows.size,
                )
            }
            .sortedByDescending { it.term }

    /** 最新学期（学期名字典序最大）；无数据时 null */
    fun latest(records: List<GradeRecord>): TermSummary? = perTerm(records).firstOrNull()

    private fun weighted(rows: List<GradeRecord>): Double? {
        var weighted = 0.0
        var credits = 0.0
        rows.forEach { r ->
            weighted += r.credit * (r.gpa ?: 0.0)
            credits += r.credit
        }
        return if (credits > 0) weighted / credits else null
    }
}
```

- [ ] **Step 4: GradesScreen 接入**

1. import `GradeTrend`。`GradesScreen` 内 `stats` 计算处追加：

```kotlin
    val trend = remember(state.records) { GradeTrend.perTerm(state.records) }
    val latestTerm = remember(state.records) { GradeTrend.latest(state.records) }
```

2. `SummaryCard(stats, term, fetchedAt, failed)` 调用改为多传 `latestTerm`；`SummaryCard` 内 `weightedAvg` 行之前加：

```kotlin
        if (term == null && latestTerm != null) {
            Text(
                text = "最新学期 ${latestTerm.term}：绩点 ${format2(latestTerm.gpa)} · " +
                    "${format1(latestTerm.credits)} 学分",
                color = colors.textMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(4.dp))
        }
```

3. `item(key = "summary")` 后新增：

```kotlin
                if (trend.isNotEmpty()) {
                    item(key = "trend") { TermTrendCard(trend) }
                }
```

4. 新增组合函数（SummaryCard 后）：

```kotlin
@Composable
private fun TermTrendCard(summaries: List<GradeTrend.TermSummary>) {
    val colors = LocalCourseColors.current
    val maxGpa = summaries.mapNotNull { it.gpa }.maxOrNull() ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Text("学期绩点趋势", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "只计有绩点课程 · 补考重修不参与",
            color = colors.textMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(10.dp))
        summaries.forEach { s ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    s.term,
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.width(88.dp),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.border.copy(alpha = 0.4f)),
                ) {
                    val fraction = ((s.gpa ?: 0.0) / maxGpa.coerceAtLeast(0.01)).toFloat()
                    Box(
                        Modifier
                            .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(colors.accent),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    s.gpa?.let { format2(it) } ?: "—",
                    color = colors.text,
                    fontSize = 11.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(34.dp),
                )
            }
        }
    }
}
```

需要补 import：`androidx.compose.foundation.layout.fillMaxHeight`、`androidx.compose.ui.text.style.TextAlign`（其余多半已在）。

- [ ] **Step 5: 测试通过 + 编译通过**
- [ ] **Step 6: Commit** `feat(grades): 最新学期绩点口径与按学期趋势卡（排除补考重修）`

### Task 8: 考试页 UI（Countdown 工具 TDD + ViewModel + Screen）

**Files:** Create `feature/exam/ExamCountdown.kt`、`feature/exam/ExamViewModel.kt`、`feature/exam/ExamScreen.kt`；Test `ExamCountdownTest.kt`

- [ ] **Step 1: 先写失败测试**

```kotlin
package com.zhijian.schedule

import com.zhijian.schedule.feature.exam.ExamCountdown
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Test

class ExamCountdownTest {
    private val now: LocalDateTime = LocalDateTime.of(2026, 11, 1, 12, 0)

    private fun millis(at: LocalDateTime) =
        at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun `倒计时徽标分档`() {
        assertEquals("3 天", ExamCountdown.badge(millis(now.plusDays(3).plusHours(1)), now))
        assertEquals("5 小时", ExamCountdown.badge(millis(now.plusHours(5)), now))
        assertEquals("30 分钟", ExamCountdown.badge(millis(now.plusMinutes(30)), now))
        assertEquals("已开考", ExamCountdown.badge(millis(now.minusMinutes(1)), now))
    }

    @Test fun `当天卡片标题带时刻_否则按天`() {
        assertEquals(
            "「高等数学」今天 14:00 开考",
            ExamCountdown.cardTitle("高等数学", millis(now.withHour(14)), now),
        )
        assertEquals(
            "距「高等数学」还有 3 天",
            ExamCountdown.cardTitle("高等数学", millis(now.plusDays(3)), now),
        )
    }
}
```

- [ ] **Step 2: 确认失败**
- [ ] **Step 3: 实现 ExamCountdown**

```kotlin
package com.zhijian.schedule.feature.exam

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 考试倒计时与日期文案（纯逻辑，可单测） */
object ExamCountdown {

    private val DATE_LINE = DateTimeFormatter.ofPattern("MM-dd E HH:mm", Locale.CHINA)
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm")

    fun local(examTimeMillis: Long): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(examTimeMillis), ZoneId.systemDefault())

    /** 行内徽标：3 天 / 5 小时 / 30 分钟；已开始 → 已开考 */
    fun badge(examTimeMillis: Long, now: LocalDateTime): String {
        val d = Duration.between(now, local(examTimeMillis))
        return when {
            d.isNegative -> "已开考"
            d.toDays() >= 1 -> "${d.toDays()} 天"
            d.toHours() >= 1 -> "${d.toHours()} 小时"
            else -> "${d.toMinutes().coerceAtLeast(1)} 分钟"
        }
    }

    /** 今日页卡片主标题：当天带时刻，其余按天 */
    fun cardTitle(name: String, examTimeMillis: Long, now: LocalDateTime): String {
        val at = local(examTimeMillis)
        return if (at.toLocalDate() == now.toLocalDate()) {
            "「$name」今天 ${at.format(CLOCK)} 开考"
        } else {
            "距「$name」还有 ${Duration.between(now, at).toDays()} 天"
        }
    }

    /** 详情行：11-02 周一 14:00 */
    fun detailLine(examTimeMillis: Long): String = local(examTimeMillis).format(DATE_LINE)
}
```

- [ ] **Step 4: 测试通过**

- [ ] **Step 5: ExamViewModel**

```kotlin
package com.zhijian.schedule.feature.exam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.core.data.db.ExamEntity
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.jw.ExamFetcher
import com.zhijian.schedule.core.jw.ExamParser
import com.zhijian.schedule.core.jw.JwAdapterRegistry
import com.zhijian.schedule.core.jw.JwException
import com.zhijian.schedule.core.jw.QiangZhiAdapter
import com.zhijian.schedule.core.model.ExamDraft
import com.zhijian.schedule.core.repository.ScheduleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

@HiltViewModel
class ExamViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    private val client: OkHttpClient,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    data class UiState(
        val exams: List<ExamEntity> = emptyList(),
        val importing: Boolean = false,
        val message: String? = null,
        val needsLogin: Boolean = false,
        /** 当前教务为强智（考试抓取仅强智可用） */
        val jwAvailable: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            repository.activeExams.collect { exams -> _state.update { it.copy(exams = exams) } }
        }
        viewModelScope.launch {
            val url = settingsStore.settings.first().jwUrl
            _state.update {
                it.copy(
                    jwAvailable = url.isNotBlank() &&
                        JwAdapterRegistry.forUrl(url)?.id == QiangZhiAdapter.id,
                )
            }
        }
    }

    fun save(name: String, room: String, seat: String, examTime: Long, note: String, examId: Long?) {
        viewModelScope.launch { repository.saveExam(name, room, seat, examTime, note, examId) }
    }

    fun delete(examId: Long) {
        viewModelScope.launch { repository.deleteExam(examId) }
    }

    fun importFromJw() {
        if (_state.value.importing) return
        viewModelScope.launch {
            _state.update { it.copy(importing = true, message = null, needsLogin = false) }
            val url = settingsStore.settings.first().jwUrl
            if (url.isBlank()) {
                _state.update { it.copy(importing = false, needsLogin = true) }
                return@launch
            }
            runCatching { ExamFetcher.fetch(client, url) }
                .onSuccess { records ->
                    val drafts = records.map {
                        ExamDraft(
                            name = it.name, room = it.room, seat = it.seat,
                            examTime = ExamParser.epochMillis(it.examTime), note = it.note,
                        )
                    }
                    val result = repository.applyExamMerge(drafts)
                    _state.update {
                        it.copy(importing = false, message = result?.summaryText() ?: "没有活动课表")
                    }
                }
                .onFailure { e ->
                    val needsLogin = e is JwException.NeedsLogin
                    _state.update {
                        it.copy(
                            importing = false,
                            needsLogin = needsLogin,
                            message = if (needsLogin) null else (e.message ?: "抓取失败"),
                        )
                    }
                }
        }
    }

    fun consumeMessage() = _state.update { it.copy(message = null, needsLogin = false) }
}
```

- [ ] **Step 6: ExamScreen**（列表 + 编辑/删除弹窗 + 教务导入；结构照 GradesScreen：SecondaryTopBar + Column(verticalScroll)）

```kotlin
package com.zhijian.schedule.feature.exam

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhijian.schedule.core.data.db.ExamEntity
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.SecondaryTopBar
import com.zhijian.schedule.core.ui.TapIconButton
import com.zhijian.schedule.core.ui.TapTextButton
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Composable
fun ExamScreen(
    onBack: () -> Unit,
    onNeedsLogin: () -> Unit = {},
    viewModel: ExamViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = LocalCourseColors.current
    val now = remember { java.time.LocalDateTime.now() }
    var editing by remember { mutableStateOf<ExamEntity?>(null) }
    var showEditor by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<ExamEntity?>(null) }

    Box(Modifier.fillMaxSize().background(colors.bg)) {
        Column(Modifier.fillMaxSize()) {
            SecondaryTopBar(
                title = "考试安排",
                onBack = onBack,
                actions = {
                    if (state.jwAvailable) {
                        TapIconButton(onClick = viewModel::importFromJw) {
                            if (state.importing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent,
                                )
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = "从教务导入", tint = colors.text)
                            }
                        }
                    }
                },
            )
            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            ) {
                state.message?.let { msg ->
                    Spacer(Modifier.height(10.dp))
                    NoticeBanner(msg, colors.accent, onDismiss = viewModel::consumeMessage)
                }
                if (state.needsLogin) {
                    Spacer(Modifier.height(10.dp))
                    NoticeBanner("登录会话已过期，请先到教务导入页重新登录", colors.accent) { onNeedsLogin() }
                }
                if (state.exams.isEmpty() && !state.importing) {
                    Spacer(Modifier.height(60.dp))
                    Text(
                        "还没有考试安排",
                        fontSize = 15.sp, color = colors.textMuted,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Text(
                        "点右下角 + 手动添加，或用右上角从教务导入",
                        fontSize = 12.sp, color = colors.textMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                } else {
                    val (upcoming, past) = state.exams.partition { it.examTime >= System.currentTimeMillis() }
                    if (upcoming.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        GroupLabel("即将到来")
                        upcoming.forEach { exam ->
                            ExamRow(exam, now, past = false) {
                                editing = exam
                                showEditor = true
                            }
                        }
                    }
                    if (past.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        GroupLabel("已结束")
                        past.forEach { exam -> ExamRow(exam, now, past = true) {} }
                    }
                }
                Spacer(Modifier.height(96.dp))
            }
        }
        FloatingActionButton(
            onClick = {
                editing = null
                showEditor = true
            },
            containerColor = colors.accent,
            contentColor = colors.bg,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(24.dp),
        ) {
            Icon(Icons.Filled.Add, contentDescription = "添加考试")
        }
    }

    if (showEditor) {
        ExamEditorDialog(
            initial = editing,
            onDismiss = {
                showEditor = false
                editing = null
            },
            onConfirm = { name, room, seat, millis, note ->
                viewModel.save(name, room, seat, millis, note, editing?.id)
                showEditor = false
                editing = null
            },
        )
    }

    pendingDelete?.let { exam ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除考试") },
            text = { Text("确定删除「${exam.name}」的考试安排吗？") },
            confirmButton = {
                TapTextButton(onClick = {
                    viewModel.delete(exam.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = { TapTextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun GroupLabel(text: String) {
    val colors = LocalCourseColors.current
    Text(
        text,
        color = colors.textMuted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

@Composable
private fun NoticeBanner(text: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val colors = LocalCourseColors.current
    Text(
        text = text,
        color = tint,
        fontSize = 12.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(tint.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    )
}

@Composable
private fun ExamRow(exam: ExamEntity, now: java.time.LocalDateTime, past: Boolean, onClick: () -> Unit) {
    val colors = LocalCourseColors.current
    val alpha = if (past) 0.5f else 1f
    Row(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .clickable(enabled = !past, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                exam.name,
                color = colors.text.copy(alpha = alpha),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
            )
            val where = listOf(
                exam.room.takeIf { it.isNotBlank() },
                exam.seat.takeIf { it.isNotBlank() }?.let { "$it 号座" },
            ).filterNotNull().joinToString(" · ")
            if (where.isNotBlank()) {
                Text(where, color = colors.textMuted, fontSize = 11.sp)
            }
            Text(
                ExamCountdown.detailLine(exam.examTime),
                color = colors.textMuted,
                fontSize = 11.sp,
            )
        }
        Text(
            ExamCountdown.badge(exam.examTime, now),
            color = if (past) colors.textMuted else colors.accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(colors.accent.copy(alpha = 0.12f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun ExamEditorDialog(
    initial: ExamEntity?,
    onDismiss: () -> Unit,
    onConfirm: (name: String, room: String, seat: String, examTimeMillis: Long, note: String) -> Unit,
) {
    var name by remember(initial) { mutableStateOf(initial?.name.orEmpty()) }
    var date by remember(initial) {
        mutableStateOf(initial?.examTime?.let {
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
                .format(ExamCountdown.local(it))
        } ?: LocalDate.now().toString())
    }
    var time by remember(initial) {
        mutableStateOf(initial?.examTime?.let {
            java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(ExamCountdown.local(it))
        } ?: "14:00")
    }
    var room by remember(initial) { mutableStateOf(initial?.room.orEmpty()) }
    var seat by remember(initial) { mutableStateOf(initial?.seat.orEmpty()) }
    var note by remember(initial) { mutableStateOf(initial?.note.orEmpty()) }

    val dateOk = runCatching { LocalDate.parse(date) }.isSuccess
    val timeOk = runCatching { LocalTime.parse(time) }.isSuccess
    val valid = name.isNotBlank() && dateOk && timeOk

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "添加考试" else "编辑考试") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { if (it.length <= 30) name = it },
                    label = { Text("课程名称") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(Modifier.padding(top = 8.dp)) {
                    OutlinedTextField(
                        value = date, onValueChange = { date = it },
                        label = { Text("日期 2026-11-02") },
                        isError = !dateOk, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedTextField(
                        value = time, onValueChange = { time = it },
                        label = { Text("时间 14:00") },
                        isError = !timeOk, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                OutlinedTextField(
                    value = room, onValueChange = { if (it.length <= 30) room = it },
                    label = { Text("考场") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = seat, onValueChange = { if (it.length <= 10) seat = it },
                    label = { Text("座位号") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = note, onValueChange = { if (it.length <= 50) note = it },
                    label = { Text("备注（如：闭卷）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TapTextButton(
                enabled = valid,
                onClick = {
                    val millis = LocalDate.parse(date).atTime(LocalTime.parse(time))
                        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    onConfirm(name.trim(), room.trim(), seat.trim(), millis, note.trim())
                },
            ) { Text("保存") }
        },
        dismissButton = { TapTextButton(onClick = onDismiss) { Text("取消") } },
    )
}
```

- [ ] **Step 7: 编译通过**
- [ ] **Step 8: Commit** `feat(exam): 考试列表页与编辑（手动添加 / 教务导入 / 删除确认）`

### Task 9: 导航与入口——Routes + AppRoot + Profile

**Files:** Modify `feature/profile/ProfileEntryTransition.kt`、`feature/profile/ProfileViewModel.kt`、`feature/profile/ProfileScreen.kt`、`navigation/AppRoot.kt`

- [ ] **Step 1: ProfileEntryKeys.EXAMS**

```kotlin
    const val EXAMS = "profile-entry-exams"
```

- [ ] **Step 2: ProfileViewModel**：combine 增加第 5 个流 `repository.activeExams`；`ProfileUiState` 加 `val examPreview: String = ""`；combine 体内：

```kotlin
        val nowDateTime = java.time.LocalDateTime.now()
        val nextExam = exams
            .filter { it.examTime >= System.currentTimeMillis() }
            .minByOrNull { it.examTime }
        val examPreview = nextExam?.let {
            "${it.name} · ${com.zhijian.schedule.feature.exam.ExamCountdown.badge(it.examTime, nowDateTime)}"
        }.orEmpty()
```

（无考试时 `examPreview = ""`。）

- [ ] **Step 3: ProfileScreen**「常用」区 hero Row 之后追加一张分组卡：

```kotlin
                Spacer(Modifier.height(12.dp))
                GroupedEntryCard {
                    ProfileEntryAnchor(
                        key = ProfileEntryKeys.EXAMS,
                        transitions = entryTransitions,
                    ) {
                        GroupedEntryRow(
                            painter = rememberVectorPainter(Icons.Filled.DateRange),
                            title = "考试安排",
                            subtitle = ui.examPreview.ifBlank { "添加考试 · 考前自动提醒" },
                            onClick = onOpenExams,
                        )
                    }
                }
```

函数签名加 `onOpenExams: () -> Unit = {}`；import `androidx.compose.material.icons.filled.DateRange`（AppRoot 已用过，此文件需补）。

- [ ] **Step 4: AppRoot**：

1. `Routes` 加 `const val PROFILE_EXAMS = "profile_exams"`；
2. `profileEntryRoutes` 集合加 `Routes.PROFILE_EXAMS`；
3. PROFILE_TAB 图内（PROFILE_GRADES composable 之后）加：

```kotlin
                    composable(
                        route = Routes.PROFILE_EXAMS,
                        enterTransition = { profileEntryHoldEnter },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        ProfileEntryPage(
                            entryKey = ProfileEntryKeys.EXAMS,
                            transitions = entryTransitions,
                            captureLayer = entryLayer,
                            isTop = currentDestination?.route == Routes.PROFILE_EXAMS,
                            onPop = { navController.popBackStack() },
                        ) { handleBack ->
                            ExamScreen(
                                onBack = handleBack,
                                onNeedsLogin = {
                                    navController.navigate(Routes.PROFILE_JW_IMPORT) { launchSingleTop = true }
                                },
                            )
                        }
                    }
```

import `com.zhijian.schedule.feature.exam.ExamScreen`；`ProfileScreen(...)` 调用加

```kotlin
                                onOpenExams = {
                                    navController.navigate(Routes.PROFILE_EXAMS) { launchSingleTop = true }
                                },
```

4. TodayScreen 接线（TODAY composable 内）：

```kotlin
                            TodayScreen(
                                onEditCourse = { id -> navController.navigate(Routes.todayEditor(courseId = id)) },
                                onOpenExams = {
                                    profileEntranceEpoch++
                                    navController.navigateToTab(Routes.PROFILE_TAB)
                                    navController.navigate(Routes.PROFILE_EXAMS) { launchSingleTop = true }
                                },
                            )
```

（考试页无入口锚点时 ProfileEntryPage 自动 instant-forward，深导航安全。）

- [ ] **Step 5: 编译通过**
- [ ] **Step 6: Commit** `feat(exam): 「我的」页考试入口与导航路由（复用共享容器转场）`

### Task 10: 今日页倒计时卡

**Files:** Modify `feature/today/TodayViewModel.kt`、`feature/today/TodayScreen.kt`

- [ ] **Step 1: TodayViewModel**：combine 增加第 5 个流 `repository.activeExams`（现为 4 流，combine 五参重载可用）；`TodayUiState` 加：

```kotlin
    /** 最近一场 14 天内的考试；无则为 null */
    val nextExam: ExamEntity? = null,
```

combine 体内：

```kotlin
        val nowMillis = System.currentTimeMillis()
        val horizonMillis = today.plusDays(14).atTime(23, 59)
            .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val nextExam = exams
            .filter { it.examTime >= nowMillis && it.examTime <= horizonMillis }
            .minByOrNull { it.examTime }
```

写入 `TodayUiState(..., nextExam = nextExam)`；import `ExamEntity`。

- [ ] **Step 2: TodayScreen**：签名加 `onOpenExams: () -> Unit = {}`；在 `next?.let { ... NextClassCard ... }` 块之后插入：

```kotlin
                ui.nextExam?.let { exam ->
                    StaggerIn(index = 1) {
                        ExamCountdownCard(exam, clock, onClick = onOpenExams)
                    }
                    Spacer(Modifier.height(14.dp))
                }
```

文件底部加私有组合函数：

```kotlin
@Composable
private fun ExamCountdownCard(exam: CourseEntityPackage.ExamEntity, now: LocalDateTime, onClick: () -> Unit) = Unit // 占位见下
```

实际实现（替换上面占位）：

```kotlin
@Composable
private fun ExamCountdownCard(exam: ExamEntity, now: LocalDateTime, onClick: () -> Unit) {
    val colors = LocalCourseColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(colors.accent.copy(alpha = 0.08f))
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(colors.accent.copy(alpha = 0.13f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.DateRange,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier.size(19.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                ExamCountdown.cardTitle(exam.name, exam.examTime, now),
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
            val where = listOf(
                exam.room.takeIf { it.isNotBlank() },
                exam.seat.takeIf { it.isNotBlank() }?.let { "$it 号座" },
            ).filterNotNull().joinToString(" · ")
            Text(
                listOfNotNull(ExamCountdown.detailLine(exam.examTime), where.ifBlank { null })
                    .joinToString(" · "),
                color = colors.textMuted,
                fontSize = 11.sp,
                maxLines = 1,
            )
        }
        Icon(
            imageVector = Icons.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = colors.textMuted.copy(alpha = 0.7f),
            modifier = Modifier.size(20.dp),
        )
    }
}
```

需要补 import：`ExamEntity`、`feature.exam.ExamCountdown`、`Icons.Filled.DateRange`、`Icons.Filled.KeyboardArrowRight`（按文件现有 import 查缺补漏）。

- [ ] **Step 3: 编译通过 + 全量单测不回归**
- [ ] **Step 4: Commit** `feat(today): 今日页考试倒计时卡（14 天窗口）`

### Task 11: mock-jw 考试页 + 测试文档

**Files:** Modify `tools/mock-jw/server.mjs`、`docs/testing/mock-jw.md`

- [ ] **Step 1: 读 server.mjs**，找到现有 `/jsxsd/` 路由表（xskb_list.do、kscj/cjcx_list 等），按同一模式注册考试安排路由：

```
GET /jsxsd/ksgl/xsksap_list
```

返回（与 ExamParser fixture 同构）：

```html
<!DOCTYPE html><html><head><title>考试安排</title></head><body>
<table id="dataList">
<tr><th>序号</th><th>课程名称</th><th>考试时间</th><th>考场</th><th>座位号</th><th>备注</th></tr>
<tr><td>1</td><td>包装材料与检验</td><td>2026-11-10 14:00~15:50</td><td>实B-301</td><td>07</td><td>闭卷</td></tr>
<tr><td>2</td><td>大学英语</td><td>2026-11-14 08:20~10:00</td><td>教305</td><td>12</td><td></td></tr>
</table>
</body></html>
```

- [ ] **Step 2: docs/testing/mock-jw.md** 补一段：考试安排端点与「考试页 → 右上角导入」的手动验证路径。
- [ ] **Step 3: 本地 E2E**：`node tools/mock-jw/server.mjs`，App 内教务导入登录 mock 学校 → 考试页右上角导入 → 出现两条考试 + 摘要横幅；再次点导入 → 摘要为「考试安排无变化」。
- [ ] **Step 4: Commit** `test(mock-jw): 考试安排页，打通考试导入 E2E`

### Task 12: 收尾验证

- [ ] **Step 1: 全量单测**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --console=plain"
```

预期：原 233 用例 + 新增 5 个测试类（ExamParserTest 3、ExamMergeTest 3、ExamAlarmsTest 4、ExamCountdownTest 2、GradeTrendTest 4）+ CapsulePlanTest 增补 ≥2 → 0 失败。

- [ ] **Step 2: release 构建**（R8 混淆不炸）

```bat
cmd /c ".tmp\build-release.bat :app:assembleRelease --console=plain"
```

- [ ] **Step 3: 真机验收清单**（来自 spec §9，需要设备；无法自动化的项列入交付说明）：

1. 手动创建 25 小时后的考试 → 1h 档通知准点；
2. 考试当天胶囊显示「X · 考试」且考试中出现「剩 N 分」；
3. 今日页卡片 14 天边界出现/消失；
4. mock 导入 → 手动加一场 → 再导入 → 手动场保留且摘要计数正确；
5. 成绩趋势卡数字与教务页核对；
6. 课程提醒回归正常（requestCode 隔离）。

- [ ] **Step 4: 汇报**：完成内容、测试结果、真机待验项、已知限制（时长 120 分钟固定 / 仅强智抓取 / 两档提醒不可配置）。
