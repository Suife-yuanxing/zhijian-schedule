package com.zhijian.schedule.core.jw.script

import com.zhijian.schedule.core.jw.SectionTimeDraft
import com.zhijian.schedule.core.jw.SectionTimes
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
            /** 脚本可选返回的作息时间；空表示该教务页没有时间信息 */
            val sectionTimes: List<SectionTimeDraft> = emptyList(),
        ) : ParseResult

        data class Invalid(
            val reason: String,
        ) : ParseResult
    }

    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_HOUR = 23
    private const val MAX_MINUTE = 59

    /** 分钟允许一位：脚本作者常写 "9:5"，等同于 "09:05" */
    private val CLOCK = Regex("""^(\d{1,2})\s*[:：]\s*(\d{1,2})$""")

    /** 课程名里的连续空白压成单个空格 */
    private val WHITESPACE = Regex("""\s+""")

    fun parse(
        raw: String,
        totalWeeks: Int,
    ): ParseResult {
        val root =
            runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                ?: return ParseResult.Invalid("脚本返回内容不是合法 JSON")
        val coursesEl =
            root["courses"] as? JsonArray
                ?: return ParseResult.Invalid("脚本未返回 courses 列表")
        if (coursesEl.size > 500) return ParseResult.Invalid("脚本返回课程数异常（${coursesEl.size} 条），已拒绝")
        if (coursesEl.isEmpty()) return ParseResult.Invalid("解析到 0 门课，请确认已打开课表页面再运行脚本")

        val warnings = mutableListOf<String>()
        (root["warnings"] as? JsonArray)?.forEach { el ->
            (el as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { warnings += it }
        }
        val drafts = mutableListOf<CourseDraft>()
        coursesEl.forEachIndexed { i, el ->
            val obj =
                el as? JsonObject ?: run {
                    warnings += "第 ${i + 1} 条不是对象，已跳过"
                    return@forEachIndexed
                }
            val draft = toDraft(obj, totalWeeks) { warnings += "第 ${i + 1} 条：$it，已跳过" }
            if (draft != null) drafts += draft
        }
        if (drafts.isEmpty()) return ParseResult.Invalid("解析到 0 门课，请确认已打开课表页面再运行脚本")

        (root["totalWeeks"] as? JsonPrimitive)?.intOrNull?.let { tw ->
            if (tw !in 1..30) warnings += "脚本返回的总周数 $tw 超出范围，已忽略"
        }
        return ParseResult.Ok(
            drafts = ZhengFangNewAdapter.mergeDuplicates(drafts),
            totalWeeks = root.totalWeeksOrNull(),
            warnings = warnings,
            sectionTimes = sectionTimesOf(root, warnings),
        )
    }

    /**
     * 脚本可选字段 `sectionTimes: [{section, start, end}]`：逐条校验后使用，
     * 坏条目丢弃并计 warning；全坏等于「脚本没给作息」，不改用户现有设置。
     */
    private fun sectionTimesOf(
        root: JsonObject,
        warnings: MutableList<String>,
    ): List<SectionTimeDraft> {
        val array = root["sectionTimes"] as? JsonArray ?: return emptyList()
        val out = mutableListOf<SectionTimeDraft>()
        array.forEachIndexed { i, el ->
            val slot = slotOf(el, i + 1, warnings)
            if (slot != null) out += slot
        }
        return out.sortedBy { it.sectionNo }
    }

    /** 单条作息（序号 + 起止）；任一项不合法返回 null 并记 warning */
    private fun slotOf(
        el: JsonElement,
        ordinal: Int,
        warnings: MutableList<String>,
    ): SectionTimeDraft? {
        val obj = el as? JsonObject
        val no = obj?.let { it.intAt("section") ?: it.intAt("sectionNo") }
        val start = obj?.let { normalizeClock(it.str("start").ifBlank { it.str("startTime") }) }
        val end = obj?.let { normalizeClock(it.str("end").ifBlank { it.str("endTime") }) }
        val valid =
            no != null &&
                start != null &&
                end != null &&
                no in 1..SectionTimes.MAX_SECTIONS &&
                start < end
        if (!valid) {
            warnings += "作息第 $ordinal 条格式不对，已跳过"
            return null
        }
        return SectionTimeDraft(no, start, end)
    }

    /** `8:5` / `08：05` 统一成 `08:05`；非法返回 null */
    private fun normalizeClock(raw: String): String? {
        val m = CLOCK.find(raw.trim()) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..MAX_HOUR || min !in 0..MAX_MINUTE) return null
        return "%02d:%02d".format(h, min)
    }

    private fun JsonObject.totalWeeksOrNull(): Int? =
        (this["totalWeeks"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..30 }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.intAt(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }

    /** 返回 null = 条目被丢弃，reason 进 warnings */
    private fun toDraft(
        obj: JsonObject,
        totalWeeks: Int,
        warn: (String) -> Unit,
    ): CourseDraft? {
        val name = obj.str("name").replace(WHITESPACE, " ").trim()
        val day = obj.intAt("day")
        val start = obj.intAt("startSection") ?: 1
        val end = obj.intAt("endSection") ?: start
        // 三类丢弃原因一次性判完：缺名 / 星期非法 / 节次非法。守卫子句只留这一个出口。
        val problem =
            when {
                name.isEmpty() -> "缺课程名"
                day == null -> "「$name」星期值非法"
                day !in 1..7 -> "「$name」星期值 $day 越界"
                start !in 1..15 || end !in 1..15 || end < start -> "「$name」节次 $start-$end 非法"
                else -> null
            }
        if (problem != null || day == null) {
            problem?.let(warn)
            return null
        }
        return CourseDraft(
            name = name.take(30),
            teacher = obj.str("teacher").trim().take(20),
            room = obj.str("room").trim().take(30),
            dayOfWeek = day,
            startSection = start.coerceIn(1, 12),
            endSection = end.coerceIn(1, 12),
            weekMask = weekMaskOf(obj, totalWeeks),
        )
    }

    /** weeks 既可以是数字数组，也可以是 "1-16单周" 这样的表达式；都给不出时按全学期 */
    private fun weekMaskOf(
        obj: JsonObject,
        totalWeeks: Int,
    ): String {
        val raw = obj["weeks"]
        if (raw is JsonArray) {
            val picked =
                raw.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.filter { it in 1..totalWeeks }
            return WeekMask.fromWeeks(totalWeeks, picked)
        }
        return WeekMask.parseExpression(totalWeeks, obj.str("weeks")) ?: WeekMask.of(totalWeeks)
    }
}
