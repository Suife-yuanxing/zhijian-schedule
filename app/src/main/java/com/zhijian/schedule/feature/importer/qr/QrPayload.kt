package com.zhijian.schedule.feature.importer.qr

import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.util.WeekMask
import com.zhijian.schedule.feature.importer.parsers.TableMapping
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * 课程分享二维码的载荷编解码：「ZJKB1:」前缀 + gzip(紧凑数组 JSON)。
 * 每门课 7 元组 [课名, 教师, 教室, 星期, 起始节, 结束节, 周次掩码]——
 * 字段名越省，二维码容量越富余；解码产物直接进既有导入预览管线。
 */
object QrPayload {

    /** QR version 40 / 纠错 L 的二进制容量上限（字节） */
    const val MAX_QR_BYTES = 2953

    private const val PREFIX = "ZJKB1:"
    private val PREFIX_BYTES = PREFIX.toByteArray(Charsets.US_ASCII)

    fun encode(courses: List<CourseEntity>): ByteArray {
        val array = buildJsonArray {
            courses.forEach { c ->
                add(buildJsonArray {
                    add(JsonPrimitive(c.name))
                    add(JsonPrimitive(c.teacher))
                    add(JsonPrimitive(c.room))
                    add(JsonPrimitive(c.dayOfWeek))
                    add(JsonPrimitive(c.startSection))
                    add(JsonPrimitive(c.endSection))
                    add(JsonPrimitive(c.weekMask))
                })
            }
        }
        val text = Json.encodeToString(JsonArray.serializer(), array)
        return PREFIX_BYTES + gzip(text.toByteArray(Charsets.UTF_8))
    }

    fun decode(raw: ByteArray, totalWeeks: Int): TableMapping.Result {
        if (raw.size <= PREFIX_BYTES.size ||
            !raw.copyOfRange(0, PREFIX_BYTES.size).contentEquals(PREFIX_BYTES)
        ) {
            return TableMapping.Result(emptyList(), 0, listOf("这不是「纸间课表」的课程分享码"))
        }
        val text = runCatching { gunzip(raw, PREFIX_BYTES.size).toString(Charsets.UTF_8) }
            .getOrElse {
                return TableMapping.Result(emptyList(), 0, listOf("分享码数据不完整，请对准二维码重试"))
            }
        val root = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull()
            ?: return TableMapping.Result(emptyList(), 0, listOf("分享码内容无法识别"))
        val drafts = mutableListOf<CourseDraft>()
        var skipped = 0
        root.forEach { element ->
            val draft = (element as? JsonArray)?.let { mapCourse(it, totalWeeks) }
            if (draft != null) drafts += draft else skipped++
        }
        if (drafts.isEmpty()) {
            return TableMapping.Result(emptyList(), skipped, listOf("分享码里没有可用的课程"))
        }
        return TableMapping.Result(drafts, skipped)
    }

    private fun mapCourse(arr: JsonArray, totalWeeks: Int): CourseDraft? {
        if (arr.size < 7) return null
        fun str(index: Int): String = (arr[index] as? JsonPrimitive)?.contentOrNull.orEmpty()
        fun int(index: Int): Int? = (arr[index] as? JsonPrimitive)?.intOrNull
        val name = str(0).takeIf { it.isNotBlank() } ?: return null
        val day = int(3) ?: return null
        val start = int(4) ?: return null
        val end = int(5) ?: start
        val mask = str(6).takeIf { it.isNotEmpty() && it.all { ch -> ch == '0' || ch == '1' } }
        return CourseDraft(
            name = name,
            teacher = str(1),
            room = str(2),
            dayOfWeek = day.coerceIn(1, 7),
            startSection = start.coerceIn(1, 12),
            endSection = end.coerceIn(1, 12),
            weekMask = mask ?: WeekMask.of(totalWeeks),
        )
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun gunzip(raw: ByteArray, offset: Int): ByteArray =
        GZIPInputStream(ByteArrayInputStream(raw, offset, raw.size - offset)).use { it.readBytes() }
}
