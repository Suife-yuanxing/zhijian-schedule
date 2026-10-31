# 周次选择「一体周历」重做 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按已批准的方案 A，把设置弹窗、选周面板、课程编辑器三处周次选择统一到一套「周格」语言：日历选开学日 + 步进/档位 + 学期时间轴；选周面板月分界线；课程编辑器拖动连选 + 实时表达式。

**Architecture:** 新建共享 `WeekCellKit`（core/ui/week，从 feature/schedule 迁入并参数化）；纯逻辑 `SemesterTimelinePlan`（feature/settings）、`WeekPickerRows`（feature/schedule）、`WeekMask.describe`（core/util）各配 JVM 单测。数据库与 `TimetableEditDialog.onSave` 签名零改动。

**Tech Stack:** Kotlin + Jetpack Compose (BOM 2026.03.01, material3 stable DatePicker)、JUnit4、`tools/run_unit_tests.py`（绕过 Gradle worker 的 JUnit 直跑器）。

**规格:** `docs/specs/2026-10-31-week-selection-redesign-design.md`

**注意（仓库环境）:** 仓库路径含中文，Gradle 测试 worker 会因编码崩坏——单测一律用 `python tools/run_unit_tests.py <全限定类名>`；编译检查用 `gradlew.bat :app:compileDebugKotlin`。提交时只 add 本计划涉及的具体文件（工作区有他人/前序未提交改动，勿裹挟）。

---

### Task 1: `WeekMask.describe` — 周次掩码的人话描述

**Files:**
- Modify: `app/src/main/java/com/zhijian/schedule/core/util/WeekMask.kt`
- Test: `app/src/test/java/com/zhijian/schedule/core/util/WeekMaskTest.kt`（追加用例）

- [ ] **Step 1: 写失败测试**（追加到 `WeekMaskTest` 类体内）

```kotlin
    // ---------- describe：单双周折叠 ----------

    @Test
    fun `describe empty mask as dash`() {
        assertEquals("—", WeekMask.describe("0000"))
    }

    @Test
    fun `describe full mask as whole week`() {
        assertEquals("全周", WeekMask.describe("1111"))
    }

    @Test
    fun `describe single week keeps expression form`() {
        assertEquals("3周", WeekMask.describe("00100"))
    }

    @Test
    fun `describe odd step2 weeks collapses to range with odd tag`() {
        val mask = WeekMask.parity(totalWeeks = 16, odd = true)
        assertEquals("1-15 单周", WeekMask.describe(mask))
    }

    @Test
    fun `describe even step2 weeks collapses to range with even tag`() {
        val mask = WeekMask.parity(totalWeeks = 16, odd = false)
        assertEquals("2-16 双周", WeekMask.describe(mask))
    }

    @Test
    fun `describe mixed weeks falls back to expression`() {
        assertEquals("1-3周", WeekMask.describe("11100"))
    }

    @Test
    fun `describe sparse odd weeks does not fake a range`() {
        // 1 和 5：间隔不是 2，不能写成「1-5 单周」
        assertEquals("1,5周", WeekMask.describe(WeekMask.fromWeeks(8, listOf(1, 5))))
    }
```

- [ ] **Step 2: 跑测试确认失败**

Run: `python tools/run_unit_tests.py com.zhijian.schedule.core.util.WeekMaskTest`
Expected: 编译错误 `unresolved reference: describe`（收集失败输出即可，此时整个测试类编译不过属预期）。

- [ ] **Step 3: 实现**（加在 `WeekMask.kt` 的 `toExpression` 之后）

```kotlin
    /**
     * 掩码转「人话」：等差为 2 的纯单周/纯双周折叠成「x-y 单/双周」，
     * 其余一律走 [toExpression]（全选→「全周」、空→「—」）。
     * 供课程编辑器的实时周次预览；不改动 [toExpression] 的既有行为。
     */
    fun describe(mask: String): String {
        val ws = weeks(mask)
        if (ws.isEmpty()) return "—"
        if (ws.size == mask.length) return "全周"
        if (ws.size == 1) return toExpression(mask)
        val step2 = ws.zipWithNext().all { (a, b) -> b - a == 2 }
        val odd = ws.first() % 2 == 1
        return when {
            step2 && odd -> "${ws.first()}-${ws.last()} 单周"
            step2 && !odd -> "${ws.first()}-${ws.last()} 双周"
            else -> toExpression(mask)
        }
    }
```

- [ ] **Step 4: 跑测试确认通过**

Run: `python tools/run_unit_tests.py com.zhijian.schedule.core.util.WeekMaskTest`
Expected: PASS（原有用例 + 7 条新用例全绿）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/zhijian/schedule/core/util/WeekMask.kt app/src/test/java/com/zhijian/schedule/core/util/WeekMaskTest.kt
git commit -m "feat(core): WeekMask.describe 单双周折叠描述，供编辑器实时预览"
```

---

### Task 2: `SemesterTimelinePlan` — 学期时间轴纯逻辑

**Files:**
- Create: `app/src/main/java/com/zhijian/schedule/feature/settings/SemesterTimelinePlan.kt`
- Test: `app/src/test/java/com/zhijian/schedule/feature/settings/SemesterTimelinePlanTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.zhijian.schedule.feature.settings

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SemesterTimelinePlanTest {
    private val start = LocalDate.parse("2026-08-31")

    @Test
    fun `mid semester marks past now future`() {
        val model = SemesterTimelinePlan.build(start, 20, LocalDate.parse("2026-10-31"))
        assertEquals(SemesterTimelinePlan.Status.ACTIVE, model.status)
        assertEquals(9, model.currentWeek)
        assertEquals(SemesterTimelinePlan.WeekState.PAST, model.weeks[7].state)
        assertEquals(SemesterTimelinePlan.WeekState.NOW, model.weeks[8].state)
        assertEquals(SemesterTimelinePlan.WeekState.FUTURE, model.weeks[9].state)
        assertEquals(LocalDate.parse("2027-01-17"), model.endDate)
    }

    @Test
    fun `before semester starts`() {
        val model = SemesterTimelinePlan.build(start, 20, LocalDate.parse("2026-08-30"))
        assertEquals(SemesterTimelinePlan.Status.NOT_STARTED, model.status)
        assertNull(model.currentWeek)
        assertTrue(model.weeks.all { it.state == SemesterTimelinePlan.WeekState.FUTURE })
    }

    @Test
    fun `last day of semester is still active`() {
        val model = SemesterTimelinePlan.build(start, 20, LocalDate.parse("2027-01-17"))
        assertEquals(SemesterTimelinePlan.Status.ACTIVE, model.status)
        assertEquals(20, model.currentWeek)
    }

    @Test
    fun `after semester ends`() {
        val model = SemesterTimelinePlan.build(start, 20, LocalDate.parse("2027-01-18"))
        assertEquals(SemesterTimelinePlan.Status.FINISHED, model.status)
        assertNull(model.currentWeek)
        assertTrue(model.weeks.all { it.state == SemesterTimelinePlan.WeekState.PAST })
    }

    @Test
    fun `null or non positive inputs yield empty model`() {
        assertTrue(SemesterTimelinePlan.build(null, 20, LocalDate.parse("2026-10-31")).weeks.isEmpty())
        assertTrue(SemesterTimelinePlan.build(start, 0, LocalDate.parse("2026-10-31")).weeks.isEmpty())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `python tools/run_unit_tests.py com.zhijian.schedule.feature.settings.SemesterTimelinePlanTest`
Expected: FAIL（类不存在）。

- [ ] **Step 3: 实现**

```kotlin
package com.zhijian.schedule.feature.settings

import com.zhijian.schedule.core.util.TimeUtils
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 设置弹窗「学期时间轴」的纯逻辑：把开学日 + 总周数换算成每周三态、结束日期与学期进度。
 *
 * 「本周」用自然周序列（floorDiv），学期外不算错——今天是第 0 周即未开学、
 * 超过总周数即已结课，时间轴永远能画出来。
 *
 * 纯逻辑、不碰 Android API（项目约定，见 CONTRIBUTING 第 3 节）。
 */
internal object SemesterTimelinePlan {
    enum class Status { ACTIVE, NOT_STARTED, FINISHED }

    enum class WeekState { PAST, NOW, FUTURE }

    data class Week(
        val no: Int,
        val state: WeekState,
    )

    data class Model(
        val weeks: List<Week>,
        /** 自然当前周，仅在学期内时非空 */
        val currentWeek: Int?,
        val endDate: LocalDate?,
        val status: Status,
    )

    fun build(
        startDate: LocalDate?,
        totalWeeks: Int,
        today: LocalDate,
    ): Model {
        val empty = Model(emptyList(), null, null, Status.NOT_STARTED)
        val sd = startDate ?: return empty
        if (totalWeeks <= 0) return empty
        // 自然周序号：开学前为 0/负数，结课后可超总周数
        val rawWeek = Math.floorDiv(ChronoUnit.DAYS.between(sd, today), 7).toInt() + 1
        val status =
            when {
                rawWeek < 1 -> Status.NOT_STARTED
                rawWeek > totalWeeks -> Status.FINISHED
                else -> Status.ACTIVE
            }
        val weeks =
            (1..totalWeeks).map { no ->
                Week(
                    no = no,
                    state =
                        when {
                            no < rawWeek -> WeekState.PAST
                            no == rawWeek -> WeekState.NOW
                            else -> WeekState.FUTURE
                        },
                )
            }
        return Model(
            weeks = weeks,
            currentWeek = rawWeek.takeIf { it in 1..totalWeeks },
            endDate = TimeUtils.dateOfWeekDay(sd, totalWeeks, 7),
            status = status,
        )
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `python tools/run_unit_tests.py com.zhijian.schedule.feature.settings.SemesterTimelinePlanTest`
Expected: PASS（5 条）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/zhijian/schedule/feature/settings/SemesterTimelinePlan.kt app/src/test/java/com/zhijian/schedule/feature/settings/SemesterTimelinePlanTest.kt
git commit -m "feat(settings): 学期时间轴纯逻辑（三态周格/结束日/未开学已结课）"
```

---

### Task 3: `WeekPickerRows` — 选周面板分行 + 月分界点

**Files:**
- Create: `app/src/main/java/com/zhijian/schedule/feature/schedule/WeekPickerRows.kt`
- Test: `app/src/test/java/com/zhijian/schedule/feature/schedule/WeekPickerRowsTest.kt`

- [ ] **Step 1: 写失败测试**

```kotlin
package com.zhijian.schedule.feature.schedule

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeekPickerRowsTest {
    private val start = LocalDate.parse("2026-08-31")

    @Test
    fun `chunks into four columns in order`() {
        val rows = WeekPickerRows.build(20, start)
        assertEquals(5, rows.size)
        assertEquals(listOf(1, 2, 3, 4), rows[0].weeks)
        assertEquals(listOf(17, 18, 19, 20), rows[4].weeks)
    }

    @Test
    fun `month labels anchor each new month at row start`() {
        val rows = WeekPickerRows.build(20, start)
        assertNull(rows[0].monthLabel)
        assertEquals(9, rows[1].monthLabel)
        assertEquals(10, rows[2].monthLabel)
        assertEquals(11, rows[3].monthLabel)
        assertEquals(12, rows[4].monthLabel)
    }

    @Test
    fun `no labels without start date or empty weeks`() {
        assertTrue(WeekPickerRows.build(20, null).all { it.monthLabel == null })
        assertTrue(WeekPickerRows.build(0, start).isEmpty())
    }

    @Test
    fun `remainder weeks land on last row`() {
        val rows = WeekPickerRows.build(18, start)
        assertEquals(listOf(17, 18), rows[4].weeks)
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

Run: `python tools/run_unit_tests.py com.zhijian.schedule.feature.schedule.WeekPickerRowsTest`
Expected: FAIL（类不存在）。

- [ ] **Step 3: 实现**

```kotlin
package com.zhijian.schedule.feature.schedule

import com.zhijian.schedule.core.util.TimeUtils
import java.time.LocalDate

/**
 * 选周面板的分行结构：每行 [columns] 个周格，行上方可带「N 月」分界小标。
 *
 * 分界规则：本行行首周的周一月份与上一行行首周的周一月份不同时，把新月份标在行首。
 * 4 列的行进节奏下标签逐行推进，等于给每一行一个月份锚点。
 *
 * 纯逻辑、无 Compose 依赖（同 [WeekAnchorPolicy] 的取舍），便于 JVM 单测。
 */
internal object WeekPickerRows {
    /** @param monthLabel 非 null 表示该行上方要画「N 月」分界 */
    data class Row(
        val weeks: List<Int>,
        val monthLabel: Int?,
    )

    fun build(
        totalWeeks: Int,
        startDate: LocalDate?,
        columns: Int = 4,
    ): List<Row> {
        if (totalWeeks <= 0) return emptyList()
        val rows = (1..totalWeeks).chunked(columns)
        return rows.mapIndexed { index, weeks ->
            val label =
                startDate?.let { sd ->
                    val month = TimeUtils.dateOfWeekDay(sd, weeks.first(), 1).monthValue
                    val previous =
                        (index - 1)
                            .takeIf { it >= 0 }
                            ?.let { TimeUtils.dateOfWeekDay(sd, rows[it].first(), 1).monthValue }
                    if (previous != null && month != previous) month else null
                }
            Row(weeks, label)
        }
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

Run: `python tools/run_unit_tests.py com.zhijian.schedule.feature.schedule.WeekPickerRowsTest`
Expected: PASS（4 条）。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/zhijian/schedule/feature/schedule/WeekPickerRows.kt app/src/test/java/com/zhijian/schedule/feature/schedule/WeekPickerRowsTest.kt
git commit -m "feat(schedule): 选周面板分行纯逻辑（4 列分行 + 月分界锚点）"
```

---

### Task 4: `WeekCellKit` 迁移共享化（视觉零变化）

**Files:**
- Create: `app/src/main/java/com/zhijian/schedule/core/ui/week/WeekCellKit.kt`
- Delete: `app/src/main/java/com/zhijian/schedule/feature/schedule/WeekCell.kt`
- Modify: `app/src/main/java/com/zhijian/schedule/feature/schedule/WeekPickerSheet.kt`（改 import 与调用）

- [ ] **Step 1: 建 `core/ui/week/WeekCellKit.kt`**

内容 = 现 `WeekCell.kt` 的四通道 transition 动画与配色函数**原样迁移**，仅做三处参数化改造：
包名 `com.zhijian.schedule.core.ui.week`；`WeekCellVisual` 改 public 顶级枚举并新增公开归并函数；`WeekCell` 签名改为 `(title, subtitle, visual, modifier, onClick)`。

```kotlin
package com.zhijian.schedule.core.ui.week

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.CourseColors
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.pressScale

/**
 * 周格视觉身份：全 App 三处周次选择的统一语言。
 * 选中 = 实心强调色 + 弹跳；本周 = 主色描边 + 浅底；过去 = 淡化；普通 = 收边细描边。
 */
enum class WeekCellVisual { SELECTED, CURRENT, PAST, NORMAL }

/** 优先级归并：选中 > 本周 > 过去 > 普通（与旧 WeekCell 的 when 一致） */
fun resolveWeekCellVisual(
    selected: Boolean,
    current: Boolean,
    past: Boolean,
): WeekCellVisual =
    when {
        selected -> WeekCellVisual.SELECTED
        current -> WeekCellVisual.CURRENT
        past -> WeekCellVisual.PAST
        else -> WeekCellVisual.NORMAL
    }

/**
 * 周格。四条颜色通道（底/描边/标题/日期）挂在同一个 transition 上：一份进度，
 * 物理上不可能不同步——任何一处时长写岔都不会出现「描边没了底色还在渐入」的半帧错位。
 *
 * @param title 主文案（「第 3 周」或紧凑格的「3」）
 * @param subtitle 副文案（日期范围）；null 时渲染为紧凑数字格（课程编辑器用）
 */
@Composable
fun WeekCell(
    title: String,
    subtitle: String?,
    visual: WeekCellVisual,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val colors = LocalCourseColors.current
    val pressSource = remember { MutableInteractionSource() }
    val transition = updateTransition(visual, label = "weekCell")
    val background by transition.animateColor(
        transitionSpec = { tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut) },
        label = "weekCellBg",
    ) { target -> weekCellBackground(target, colors) }
    val ring by transition.animateColor(
        transitionSpec = { tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut) },
        label = "weekCellRing",
    ) { target -> weekCellRing(target, colors) }
    val titleColor by transition.animateColor(
        transitionSpec = { tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut) },
        label = "weekCellTitle",
    ) { target -> weekCellTitle(target, colors) }
    val dateColor by transition.animateColor(
        transitionSpec = { tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut) },
        label = "weekCellDate",
    ) { target -> weekCellDate(target, colors) }
    // 缩放走弹簧（点中的触觉替代，要过冲）；颜色是状态迁移（要可预测），两条时钟并存是刻意的
    val popScale by transition.animateFloat(
        transitionSpec = { Motion.PopSpring },
        label = "weekCellPop",
    ) { target -> if (target == WeekCellVisual.SELECTED) SELECTED_POP_SCALE else 1f }
    val shape = RoundedCornerShape(13.dp)

    Column(
        modifier
            .pressScale(pressSource, pressedScale = 0.94f)
            .graphicsLayer {
                scaleX = popScale
                scaleY = popScale
            }.clip(shape)
            .background(background)
            .border(1.5.dp, ring, shape)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = pressSource,
                        indication = LocalIndication.current,
                        onClick = onClick,
                    )
                } else {
                    Modifier
                },
            ).padding(
                vertical = if (subtitle == null) 9.dp else 10.dp,
                horizontal = 2.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            fontSize = if (subtitle == null) 13.sp else 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = titleColor,
        )
        if (subtitle != null) {
            Spacer(Modifier.height(3.dp))
            Text(
                text = subtitle,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.3.sp,
                color = dateColor,
            )
        }
    }
}

/** 选中态放大比例：1.06 肉眼可辨但不会把相邻格子挤开 */
private const val SELECTED_POP_SCALE = 1.06f

// ---------- 四种视觉身份 → 四条通道的取值（自旧 WeekCell 原样迁移） ----------

private fun weekCellBackground(
    visual: WeekCellVisual,
    colors: CourseColors,
): Color =
    when (visual) {
        WeekCellVisual.SELECTED -> colors.accent
        WeekCellVisual.CURRENT -> colors.accent.copy(alpha = CURRENT_TINT_ALPHA)
        WeekCellVisual.PAST, WeekCellVisual.NORMAL -> colors.surfaceVariant
    }

private fun weekCellRing(
    visual: WeekCellVisual,
    colors: CourseColors,
): Color =
    when (visual) {
        WeekCellVisual.SELECTED -> Color.Transparent
        WeekCellVisual.CURRENT -> colors.accent
        WeekCellVisual.PAST, WeekCellVisual.NORMAL -> colors.border.copy(alpha = IDLE_RING_ALPHA)
    }

private fun weekCellTitle(
    visual: WeekCellVisual,
    colors: CourseColors,
): Color =
    when (visual) {
        WeekCellVisual.SELECTED -> colors.onAccent
        WeekCellVisual.CURRENT -> colors.accent
        WeekCellVisual.PAST -> colors.textMuted
        WeekCellVisual.NORMAL -> colors.text
    }

private fun weekCellDate(
    visual: WeekCellVisual,
    colors: CourseColors,
): Color =
    when (visual) {
        WeekCellVisual.SELECTED -> colors.onAccent.copy(alpha = SELECTED_DATE_ALPHA)
        WeekCellVisual.CURRENT -> colors.accent.copy(alpha = CURRENT_DATE_ALPHA)
        WeekCellVisual.PAST -> colors.textMuted.copy(alpha = PAST_DATE_ALPHA)
        WeekCellVisual.NORMAL -> colors.textMuted
    }

private const val CURRENT_TINT_ALPHA = 0.12f
private const val IDLE_RING_ALPHA = 0.55f
private const val SELECTED_DATE_ALPHA = 0.92f
private const val CURRENT_DATE_ALPHA = 0.8f
private const val PAST_DATE_ALPHA = 0.7f
```

- [ ] **Step 2: 改 `WeekPickerSheet.kt`**

- import 区：删去不再使用的本包 `WeekCell` 引用（原本同包无需 import，删文件后自动失效），新增 `com.zhijian.schedule.core.ui.week.WeekCell`、`com.zhijian.schedule.core.ui.week.resolveWeekCellVisual`。
- 格子渲染替换为：

```kotlin
rowWeeks.forEach { week ->
    WeekCell(
        title = "第 $week 周",
        subtitle = weekRangeText(ui.dateOf(week, 1), ui.dateOf(week, 7)),
        visual =
            resolveWeekCellVisual(
                selected = week == (pinnedWeek ?: ui.selectedWeek),
                current = week == ui.currentWeek,
                past = ui.currentWeek != null && week < ui.currentWeek,
            ),
        modifier = Modifier.weight(1f),
        onClick = { pick(week) },
    )
}
```

- [ ] **Step 3: 删除旧文件**

```bash
git rm app/src/main/java/com/zhijian/schedule/feature/schedule/WeekCell.kt
```

- [ ] **Step 4: 编译确认（视觉零变化的重构）**

Run: `gradlew.bat :app:compileDebugKotlin --console=plain -q`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/zhijian/schedule/core/ui/week/WeekCellKit.kt app/src/main/java/com/zhijian/schedule/feature/schedule/WeekPickerSheet.kt
git commit -m "refactor(ui): WeekCell 抽入 core/ui/week 参数化共享，选周面板改用新签名"
```

---

### Task 5: 设置弹窗重做（日历选开学日 + 档位/步进 + 学期时间轴）

**Files:**
- Modify: `app/src/main/java/com/zhijian/schedule/feature/settings/SettingsDialogs.kt`（`TimetableEditDialog` 整体重写 + 新增 4 个私有组件；`SectionEditDialog` 不动）

- [ ] **Step 1: 重写 `TimetableEditDialog` 及其配套私有组件**

完整替换 `TimetableEditDialog` 区块（保留文件头注释与 `SectionEditDialog`；删除 `WEEK_PRESETS` 旧定义与 `custom`/`customText` 状态）。imports 需要新增：`androidx.compose.foundation.LocalIndication`、`background`、`border`、`clickable`、`combinedClickable`（不用，忽略）、`interaction.MutableInteractionSource`、`interaction.collectIsPressedAsState`、`layout.Box`、`layout.Row`、`layout.fillMaxWidth`、`layout.size`、`layout.width`、`shape.RoundedCornerShape`、`material3.DatePicker`、`material3.DatePickerDialog`、`material3.ExperimentalMaterial3Api`、`material3.rememberDatePickerState`、`runtime.LaunchedEffect`、`ui.Alignment`、`ui.draw.clip`、`ui.graphics.Color`、`ui.text.font.FontWeight`、`ui.text.style.TextAlign`、`com.zhijian.schedule.core.theme.LocalCourseColors`、`com.zhijian.schedule.core.ui.pressScale`、`com.zhijian.schedule.core.util.TimeUtils`、`kotlinx.coroutines.delay`、`java.time.Instant`、`java.time.LocalDate`、`java.time.ZoneOffset`。

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TimetableEditDialog(
    initialName: String,
    initialSemester: String,
    initialStartDate: String,
    initialWeeks: Int,
    onDismiss: () -> Unit,
    onSave: (String, String, String, Int) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var semester by remember { mutableStateOf(initialSemester) }
    var startDate by remember { mutableStateOf(initialStartDate) }
    var weeks by remember { mutableStateOf(initialWeeks.coerceIn(1, TimetableMetaPlan.MAX_WEEKS)) }
    var showDatePicker by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("课表信息") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("课表名称") },
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    colors = MetalFields.textFieldColors() ?: OutlinedTextFieldDefaults.colors(),
                    value = semester,
                    onValueChange = { semester = it },
                    label = { Text("学期（如 2026 秋季）") },
                    singleLine = true,
                )
                Spacer(Modifier.height(10.dp))
                SemesterDateField(
                    date = TimeUtils.parseDate(startDate),
                    onClick = { showDatePicker = true },
                )
                Spacer(Modifier.height(12.dp))
                Text("总周数", fontSize = 13.sp, color = LocalCourseColors.current.textMuted)
                Spacer(Modifier.height(6.dp))
                WeekPresetsRow(weeks = weeks, onSelect = { weeks = it })
                Spacer(Modifier.height(8.dp))
                WeekStepper(weeks = weeks, onChange = { weeks = it })
                SemesterTimeline(startDate = TimeUtils.parseDate(startDate), totalWeeks = weeks)
            }
        },
        confirmButton = {
            TapTextButton(
                hapticType = HapticFeedbackType.Confirm,
                onClick = { onSave(name, semester, startDate, weeks) },
            ) { Text("保存") }
        },
        dismissButton = { TapTextButton(onClick = onDismiss) { Text("取消") } },
    )

    if (showDatePicker) {
        WeekMondayPicker(
            initial = TimeUtils.parseDate(startDate),
            onPick = { picked ->
                startDate = TimeUtils.formatDate(picked)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false },
        )
    }
}

/** 开学日字段按钮：显示「yyyy年M月d日 · 周一」，点开日历；未设置时引导点选 */
@Composable
private fun SemesterDateField(
    date: LocalDate?,
    onClick: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val source = remember { MutableInteractionSource() }
    Column(
        Modifier
            .fillMaxWidth()
            .pressScale(source, pressedScale = 0.98f)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surfaceVariant)
            .border(1.dp, colors.border, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                onClick = onClick,
            ).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text("第 1 周周一", fontSize = 12.sp, color = colors.textMuted)
        Spacer(Modifier.height(2.dp))
        Text(
            text =
                date?.let {
                    "${it.year}年${it.monthValue}月${it.dayOfMonth}日 · ${TimeUtils.dayName(it.dayOfWeek.value)}"
                } ?: "点选开学日期",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (date == null) colors.textMuted else colors.text,
        )
    }
}

/** 选周一日历：任意日期对齐到所在周周一；DatePicker 以 UTC 计日，转换需走 UTC */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeekMondayPicker(
    initial: LocalDate?,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val state =
        rememberDatePickerState(
            initialSelectedDateMillis =
                initial?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TapTextButton(onClick = {
                val millis = state.selectedDateMillis ?: return@TapTextButton
                val picked = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
                onPick(picked.minusDays((picked.dayOfWeek.value - 1).toLong()))
            }) { Text("确定") }
        },
        dismissButton = { TapTextButton(onClick = onDismiss) { Text("取消") } },
    ) {
        DatePicker(state = state)
    }
}

/** 总周数预设档：单选分段（与周格同一选中语言：主色描边 + 浅底） */
private val WEEK_PRESETS = listOf(16, 18, 20, 22)

@Composable
private fun WeekPresetsRow(
    weeks: Int,
    onSelect: (Int) -> Unit,
) {
    val colors = LocalCourseColors.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        WEEK_PRESETS.forEach { preset ->
            val selected = weeks == preset
            val source = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .weight(1f)
                    .pressScale(source, pressedScale = 0.94f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(
                        if (selected) colors.accent.copy(alpha = 0.16f) else colors.surfaceVariant,
                    ).border(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) colors.accent else colors.border,
                        shape = RoundedCornerShape(11.dp),
                    ).clickable(
                        interactionSource = source,
                        indication = LocalIndication.current,
                    ) { onSelect(preset) }.padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "$preset",
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) colors.accent else colors.text,
                )
            }
        }
    }
}

/** 周数步进器：单击 ±1，长按连发（先 420ms 停顿再 85ms 加速） */
@Composable
private fun WeekStepper(
    weeks: Int,
    onChange: (Int) -> Unit,
) {
    val colors = LocalCourseColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(13.dp))
            .background(colors.surfaceVariant)
            .border(1.dp, colors.border, RoundedCornerShape(13.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(
            symbol = "−",
            label = "减少一周",
            enabled = weeks > 1,
        ) { onChange(weeks - 1) }
        Text(
            text = "$weeks 周",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        StepButton(
            symbol = "＋",
            label = "增加一周",
            enabled = weeks < TimetableMetaPlan.MAX_WEEKS,
        ) { onChange(weeks + 1) }
    }
}

@Composable
private fun StepButton(
    symbol: String,
    label: String,
    enabled: Boolean,
    onStep: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val source = remember { MutableInteractionSource() }
    val held by source.collectIsPressedAsState()
    // 连发唯一驱动是这里的 LaunchedEffect：按下立即一步、停顿后加速；
    // clickable 的 onClick 故意留空，避免「按下已计步、松手又 +1」的双重触发。
    LaunchedEffect(held) {
        if (held && enabled) {
            onStep()
            delay(420)
            while (held && enabled) {
                onStep()
                delay(85)
            }
        }
    }
    Box(
        Modifier
            .size(width = 46.dp, height = 38.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (enabled) colors.accent.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                enabled = enabled,
                onClick = { /* 计步全部由上方 LaunchedEffect 负责 */ },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (enabled) colors.text else colors.textMuted.copy(alpha = 0.5f),
        )
    }
}

/** 学期时间轴：N 段周格实时预览（过去淡主色 / 本周实心 / 未来浅底）+ 进度说明 */
@Composable
private fun SemesterTimeline(
    startDate: LocalDate?,
    totalWeeks: Int,
) {
    val colors = LocalCourseColors.current
    val model =
        remember(startDate, totalWeeks) {
            SemesterTimelinePlan.build(startDate, totalWeeks, LocalDate.now())
        }
    if (model.weeks.isEmpty()) return
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            model.weeks.forEach { week ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(
                            when (week.state) {
                                SemesterTimelinePlan.WeekState.PAST -> colors.accent.copy(alpha = 0.30f)
                                SemesterTimelinePlan.WeekState.NOW -> colors.accent
                                SemesterTimelinePlan.WeekState.FUTURE -> colors.surfaceVariant
                            },
                        ),
                )
            }
        }
        Spacer(Modifier.height(5.dp))
        val endText =
            model.endDate?.let { "${it.year}年${it.monthValue}月${it.dayOfMonth}日" } ?: "—"
        Text(
            text =
                when (model.status) {
                    SemesterTimelinePlan.Status.ACTIVE -> "第 ${model.currentWeek} 周进行中 · 至 $endText"
                    SemesterTimelinePlan.Status.NOT_STARTED -> "未开学 · 至 $endText"
                    SemesterTimelinePlan.Status.FINISHED -> "已结课 · 至 $endText"
                },
            fontSize = 11.sp,
            color = colors.textMuted,
        )
    }
}
```

注意 `StepButton` 里 `onChange(weeks - 1)` 的边界由调用方 `WeekStepper` 的 `enabled` 保证（`1..MAX_WEEKS` 内），步进回调无需再 coerce。

- [ ] **Step 2: 编译确认**

Run: `gradlew.bat :app:compileDebugKotlin --console=plain -q`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/zhijian/schedule/feature/settings/SettingsDialogs.kt
git commit -m "feat(settings): 课表信息弹窗重做——日历选开学日、周数档位+步进、学期时间轴"
```

---

### Task 6: 选周面板接月分界线

**Files:**
- Modify: `app/src/main/java/com/zhijian/schedule/feature/schedule/WeekPickerSheet.kt`

- [ ] **Step 1: 用 `WeekPickerRows` 驱动渲染**

`(1..ui.totalWeeks).toList().chunked(4).forEachIndexed { ... }` 区块整体替换为：

```kotlin
val rows =
    remember(ui.totalWeeks, ui.dateOf(1, 1)) {
        WeekPickerRows.build(ui.totalWeeks, ui.dateOf(1, 1))
    }
rows.forEachIndexed { rowIdx, row ->
    row.monthLabel?.let { month ->
        StaggerIn(
            index = rowIdx + 1,
            contentPadding = PaddingValues(horizontal = 20.dp),
        ) {
            MonthDivider(month)
        }
    }
    StaggerIn(
        index = rowIdx + 1,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 3.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            row.weeks.forEach { week ->
                WeekCell(
                    title = "第 $week 周",
                    subtitle = weekRangeText(ui.dateOf(week, 1), ui.dateOf(week, 7)),
                    visual =
                        resolveWeekCellVisual(
                            selected = week == (pinnedWeek ?: ui.selectedWeek),
                            current = week == ui.currentWeek,
                            past = ui.currentWeek != null && week < ui.currentWeek,
                        ),
                    modifier = Modifier.weight(1f),
                    onClick = { pick(week) },
                )
            }
            repeat(4 - row.weeks.size) {
                Spacer(Modifier.weight(1f))
            }
        }
    }
    Spacer(Modifier.height(7.dp))
}
```

并在文件底部加私有组件（imports 需补：`androidx.compose.foundation.background`、`androidx.compose.foundation.layout.Box`、`androidx.compose.foundation.layout.width`）：

```kotlin
/** 月分界小标：给「第 N 周」一个月份锚点 */
@Composable
private fun MonthDivider(month: Int) {
    val colors = LocalCourseColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$month 月",
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            color = colors.textMuted,
        )
        Spacer(Modifier.width(8.dp))
        Box(
            Modifier
                .weight(1f)
                .height(1.dp)
                .background(colors.border.copy(alpha = 0.6f)),
        )
    }
}
```

- [ ] **Step 2: 编译确认**

Run: `gradlew.bat :app:compileDebugKotlin --console=plain -q`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 提交**

```bash
git add app/src/main/java/com/zhijian/schedule/feature/schedule/WeekPickerSheet.kt
git commit -m "feat(schedule): 选周面板加月分界锚点，渲染改由 WeekPickerRows 驱动"
```

---

### Task 7: 课程编辑器周次区重做（多选周格 + 拖动连选 + 分段快捷档 + 表达式）

**Files:**
- Modify: `app/src/main/java/com/zhijian/schedule/feature/courseeditor/CourseEditorScreen.kt`
  - 替换「周次」区（约 215-255 行）；
  - 删除 `WeekQuickAction`，新增 `PresetSegment`、`WeekMaskGrid`；
  - imports 补：`androidx.compose.foundation.gestures.detectDragGestures`、`androidx.compose.foundation.layout.Box`（已有）、`androidx.compose.runtime.mutableStateMapOf`、`androidx.compose.runtime.rememberUpdatedState`、`androidx.compose.ui.geometry.Offset`、`androidx.compose.ui.geometry.Rect`、`androidx.compose.ui.layout.onGloballyPositioned`、`androidx.compose.ui.layout.positionInWindow`、`com.zhijian.schedule.core.ui.week.WeekCell`、`com.zhijian.schedule.core.ui.week.resolveWeekCellVisual`、`com.zhijian.schedule.core.util.WeekMask`。

- [ ] **Step 1: 替换周次区 UI**

原 `Row(verticalAlignment = Alignment.CenterVertically) { FieldLabel("周次") ... }` + `FlowRow { (1..ui.totalWeeks).forEach { w -> FilterChip(...) } }` 整块替换为：

```kotlin
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel("周次")
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${ui.weeks.size}/${ui.totalWeeks} 周",
                    color = colors.textMuted,
                    fontSize = 12.sp,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("全选", "单周", "双周", "清空").forEach { label ->
                    PresetSegment(
                        text = label,
                        modifier = Modifier.weight(1f),
                    ) {
                        when (label) {
                            "全选" -> viewModel.setAllWeeks()
                            "单周" -> viewModel.setParity(odd = true)
                            "双周" -> viewModel.setParity(odd = false)
                            "清空" -> viewModel.clearWeeks()
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            WeekMaskGrid(
                totalWeeks = ui.totalWeeks,
                weeks = ui.weeks,
                onToggle = viewModel::toggleWeek,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("结果", color = colors.textMuted, fontSize = 11.sp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = WeekMask.describe(WeekMask.fromWeeks(ui.totalWeeks, ui.weeks)),
                    color = colors.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
```

- [ ] **Step 2: 新增 `PresetSegment` 与 `WeekMaskGrid`（放 `SectionDropdown` 之前）**

```kotlin
/** 快捷档分段（动作语义：点一下执行一次，无选中态） */
@Composable
private fun PresetSegment(
    text: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val source = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(source, pressedScale = 0.93f)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.surfaceVariant)
            .border(1.dp, colors.border, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = source,
                indication = LocalIndication.current,
                onClick = onClick,
            ).padding(vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 12.sp, color = colors.text, fontWeight = FontWeight.Medium)
    }
}

/**
 * 周次多选周格：与选周面板同一套 WeekCell 视觉（紧凑态）。
 * 拖动连选是加速器：按在空格上=连加，按在选中格上=连删；单击逐格始终可用。
 */
@Composable
private fun WeekMaskGrid(
    totalWeeks: Int,
    weeks: List<Int>,
    onToggle: (Int) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val rows = remember(totalWeeks) { (1..totalWeeks).chunked(4) }
    // 拖动命中用窗口坐标：格子存 positionInWindow，拖拽的本地坐标加上网格原点换算
    val bounds = remember(totalWeeks) { mutableStateMapOf<Int, Rect>() }
    var gridOrigin by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf(false) }
    var dragAdd by remember { mutableStateOf(true) }
    val currentWeeks by rememberUpdatedState(weeks)
    val currentToggle by rememberUpdatedState(onToggle)

    fun applyAt(pos: Offset) {
        val window = pos + gridOrigin
        val hit = bounds.entries.firstOrNull { it.value.contains(window) }?.key ?: return
        val contains = currentWeeks.contains(hit)
        if (dragAdd != contains) currentToggle(hit)
    }

    Box(
        Modifier
            .onGloballyPositioned { gridOrigin = it.positionInWindow() }
            .pointerInput(totalWeeks) {
                detectDragGestures(
                    onDragStart = { pos ->
                        val window = pos + gridOrigin
                        val hit = bounds.entries.firstOrNull { it.value.contains(window) }?.key
                        if (hit != null) {
                            dragging = true
                            dragAdd = !currentWeeks.contains(hit)
                            currentToggle(hit)
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    },
                    onDrag = { change, _ ->
                        if (dragging) {
                            change.consume()
                            applyAt(change.position)
                        }
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                )
            },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            rows.forEach { rowWeeks ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    rowWeeks.forEach { week ->
                        val selected = currentWeeks.contains(week)
                        WeekCell(
                            title = "$week",
                            subtitle = null,
                            visual = resolveWeekCellVisual(selected, current = false, past = false),
                            modifier =
                                Modifier
                                    .weight(1f)
                                    .onGloballyPositioned { coords ->
                                        bounds[week] =
                                            Rect(coords.positionInWindow(), coords.size.toSize())
                                    },
                            onClick = { currentToggle(week) },
                        )
                    }
                    repeat(4 - rowWeeks.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
```

实现注意：
- `detectDragGestures` 在触发力阈值后才接管，短点仍由子格的 clickable 处理，二者不打架。
- `applyAt` 里的 `+` 是 `Offset` 与窗口坐标相加；`bounds` 用 `positionInWindow()` 存，坐标系一致。
- `dragAdd != contains` 才翻转，保证同一手势内方向一致、来回划不抖动。

- [ ] **Step 3: 删除 `WeekQuickAction`**

整段删除（全选/单周/双周/清空已由 PresetSegment 承接）。

- [ ] **Step 4: 编译确认**

Run: `gradlew.bat :app:compileDebugKotlin --console=plain -q`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: 提交**

```bash
git add app/src/main/java/com/zhijian/schedule/feature/courseeditor/CourseEditorScreen.kt
git commit -m "feat(editor): 周次区重做——共享周格多选、拖动连选、分段快捷档、实时表达式"
```

---

### Task 8: 全量回归与收尾

- [ ] **Step 1: 全量单测**

Run: `python tools/run_unit_tests.py --all`
Expected: 全部 PASS（含新增 WeekMask describe / SemesterTimelinePlan / WeekPickerRows 用例）。

- [ ] **Step 2: 全量编译**

Run: `gradlew.bat :app:compileDebugKotlin --console=plain -q`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: 真机验证清单（交给用户，OPPO/ColorOS）**

1. 设置 → 课表信息：点开学日字段 → 日历选任意日期 → 确认落库为该周周一、字段显示「… · 周一」。
2. 改总周数（点档位 / 步进长按连发）→ 时间轴段数与「至 …」实时变化；开学日改到未来 → 显示「未开学」。
3. 保存后回到课表页：周页锚定正确（改周数/开学日后应重定位，对应 WeekAnchorPolicy 既有行为）。
4. 选周面板：跨月行首出现「N 月」小标；点选/回到本周/滑动动画如常。
5. 编辑课程 → 周次：单击切换、按住拖动连选、单周/双周快捷档；「结果」行实时显示「1-15 单周」类表达式。

---

## Self-Review 记录

- **Spec 覆盖**：统一语言→Task 4；设置三件套→Task 5；月分界→Task 3+6；编辑器连选+表达式→Task 1+7；测试→各 Task + Task 8。无缺口。
- **占位符**：无 TBD/TODO；所有代码步骤给出完整代码。
- **类型一致性**：`WeekCell(title, subtitle, visual, modifier, onClick)` 在 Task 4/5/6/7 调用一致；`SemesterTimelinePlan.build(startDate: LocalDate?, totalWeeks: Int, today: LocalDate)` 与 Task 5 调用一致；`WeekPickerRows.build(totalWeeks, startDate, columns=4)` 与 Task 6 一致；`WeekMask.describe(mask)` 与 Task 7 一致。
