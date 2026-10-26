package com.zhijian.schedule.feature.importer.parsers

import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.util.WeekMask
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * JSON 课程导入：兼容标准格式与常见字段别名（含中文 key）。
 */
object JsonParser : TimetableParser {

    override val format = TimetableFormat.JSON

    override val channels = setOf(ParserChannel.FILE)

    /** 头部（去 BOM 与前导空白）以 { 或 [ 开头即视为 JSON。 */
    override fun canParse(input: ParserInput): Boolean {
        val head = input.sniffHead()
        return head.startsWith("{") || head.startsWith("[")
    }

    override fun parse(input: ParserInput, context: ParseContext): ParseResult =
        parse(input.text, context.totalWeeks)

    /** 直连入口：JSON 文本 + 总周数。 */
    fun parse(text: String, totalWeeks: Int): ParseResult {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrElse {
            return ParseResult.error("JSON 解析失败：${it.message}")
        }
        val array: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> (root["courses"] as? JsonArray)
                ?: (root["course"] as? JsonArray)
                ?: JsonArray(listOf(root))
            else -> JsonArray(emptyList())
        }
        val drafts = mutableListOf<CourseDraft>()
        var skipped = 0
        array.forEach { element ->
            val draft = (element as? JsonObject)?.let { mapObject(it, totalWeeks) }
            if (draft != null) drafts += draft else skipped++
        }
        return ParseResult(drafts, skipped)
    }

    private fun mapObject(obj: JsonObject, totalWeeks: Int): CourseDraft? {
        fun str(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
            (obj[key] as? JsonPrimitive)?.contentOrNullSafe()
        }

        val name = str("name", "课程名", "课程名称", "课程") ?: return null
        val day = str("dayOfWeek", "day", "星期", "周几")
            ?.let { it.toIntOrNull() ?: TableMapping.parseDay(it) }
            ?: return null
        val start = str("startSection", "start", "开始节", "起始节")
            ?.let { TableMapping.parseSection(it) }
            ?: return null
        val end = str("endSection", "end", "结束节")
            ?.let { TableMapping.parseSection(it) }
            ?: start
        val mask: String = run {
            val rawMask = str("weekMask")
            if (rawMask != null && rawMask.all { it == '0' || it == '1' } && rawMask.isNotEmpty()) {
                WeekMask.normalize(rawMask, totalWeeks)
            } else {
                val weeksArr = obj["weeks"] as? JsonArray
                if (weeksArr != null) {
                    WeekMask.fromWeeks(
                        totalWeeks,
                        weeksArr.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt() },
                    )
                } else {
                    WeekMask.parseExpression(totalWeeks, str("周次", "周数", "weeksText"))
                        ?: WeekMask.of(totalWeeks)
                }
            }
        }
        return CourseDraft(
            name = name,
            teacher = str("teacher", "老师", "教师").orEmpty(),
            room = str("room", "教室", "地点").orEmpty(),
            dayOfWeek = day.coerceIn(1, 7),
            startSection = start.coerceIn(1, 12),
            endSection = end.coerceIn(1, 12),
            weekMask = mask,
        )
    }

    private fun JsonPrimitive.contentOrNullSafe(): String? =
        runCatching { jsonPrimitive.content }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }
}
