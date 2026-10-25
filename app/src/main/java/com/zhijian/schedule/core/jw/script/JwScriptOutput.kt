package com.zhijian.schedule.core.jw.script

import com.zhijian.schedule.core.jw.ZhengFangNewAdapter
import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.util.WeekMask
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
        (root["warnings"] as? JsonArray)?.forEach { el ->
            (el as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { warnings += it }
        }
        val drafts = mutableListOf<CourseDraft>()
        coursesEl.forEachIndexed { i, el ->
            val obj = el as? JsonObject ?: run { warnings += "第 ${i + 1} 条不是对象，已跳过"; return@forEachIndexed }
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
        )
    }

    private fun JsonObject.totalWeeksOrNull(): Int? =
        (this["totalWeeks"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..30 }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()

    private fun JsonObject.intAt(key: String): Int? =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.trim()?.toIntOrNull() }

    /** 返回 null = 条目被丢弃，reason 进 warnings */
    private fun toDraft(obj: JsonObject, totalWeeks: Int, warn: (String) -> Unit): CourseDraft? {
        val name = obj.str("name").replace(Regex("\\s+"), " ").trim()
        if (name.isEmpty()) { warn("缺课程名"); return null }
        val day = obj.intAt("day") ?: run { warn("「$name」星期值非法"); return null }
        if (day !in 1..7) { warn("「$name」星期值 $day 越界"); return null }
        val start = obj.intAt("startSection") ?: 1
        val end = obj.intAt("endSection") ?: start
        if (start !in 1..15 || end !in 1..15 || end < start) {
            warn("「$name」节次 $start-$end 非法")
            return null
        }
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
