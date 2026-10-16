package com.zhijian.schedule

import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.util.WeekMask
import com.zhijian.schedule.feature.importer.qr.QrPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrPayloadTest {

    private fun course(
        name: String,
        teacher: String = "",
        room: String = "",
        day: Int = 1,
        start: Int = 1,
        end: Int = 2,
        mask: String = "1".repeat(20),
    ) = CourseEntity(
        tableId = 1,
        name = name,
        teacher = teacher,
        room = room,
        dayOfWeek = day,
        startSection = start,
        endSection = end,
        weekMask = mask,
        color = 0,
    )

    @Test
    fun `encode 后 decode 保留课程字段`() {
        val courses = listOf(
            course(name = "包装材料与检测", teacher = "王老师", room = "6A308", day = 1, start = 1, end = 4, mask = "11001100110011001100"),
            course(name = "毛泽东思想概论", room = "1-201", day = 2, start = 3, end = 4),
            course(name = "数字媒体技术", teacher = "李四", room = "实验楼305", day = 5, start = 5, end = 8, mask = "10101010101010101010"),
        )
        val result = QrPayload.decode(QrPayload.encode(courses), totalWeeks = 20)
        assertEquals(3, result.drafts.size)
        val first = result.drafts[0]
        assertEquals("包装材料与检测", first.name)
        assertEquals("王老师", first.teacher)
        assertEquals("6A308", first.room)
        assertEquals(1, first.dayOfWeek)
        assertEquals(1, first.startSection)
        assertEquals(4, first.endSection)
        assertEquals("11001100110011001100", first.weekMask)
        // 教师为空时不写字段，解码回落为空串
        assertEquals("", result.drafts[1].teacher)
        assertEquals("1-201", result.drafts[1].room)
        assertEquals("10101010101010101010", result.drafts[2].weekMask)
    }

    @Test
    fun `非本应用二维码给出明确提示`() {
        val result = QrPayload.decode("https://example.com/anything".toByteArray(), totalWeeks = 20)
        assertTrue(result.drafts.isEmpty())
        assertTrue(result.warnings.first().contains("分享码"))
    }

    @Test
    fun `前缀正确但数据损坏时提示重试`() {
        val encoded = QrPayload.encode(listOf(course(name = "测试课")))
        val truncated = encoded.copyOf(encoded.size - 6)
        val result = QrPayload.decode(truncated, totalWeeks = 20)
        assertTrue(result.drafts.isEmpty())
        assertTrue(result.warnings.first().contains("不完整"))
    }

    @Test
    fun `周次掩码不合法时退化为全周`() {
        val encoded = QrPayload.encode(listOf(course(name = "坏掩码课", mask = "abc")))
        val result = QrPayload.decode(encoded, totalWeeks = 20)
        assertEquals(1, result.drafts.size)
        assertEquals(WeekMask.of(20), result.drafts[0].weekMask)
    }

    @Test
    fun `四十门课的密集课表仍装得进单张二维码`() {
        val bases = listOf(
            "数字媒体技术基础", "包装材料与检测", "包装插画设计", "毛泽东思想概论",
            "习近平新时代思想", "劳动教育实践", "大学英语听说写", "高等数学A",
            "计算机程序设计", "体育选项羽毛球",
        )
        val dense = (0 until 40).map { i ->
            course(
                name = bases[i % bases.size] + ('A' + i / bases.size),
                teacher = "王老师李四".take(2 + i % 3),
                room = if (i % 2 == 0) "6A30$i" else "实验楼4-0$i",
                day = 1 + i % 5,
                start = 1 + (i % 6) * 2,
                end = 2 + (i % 6) * 2,
                mask = when (i % 3) {
                    0 -> "1".repeat(20)
                    1 -> "10".repeat(10)
                    else -> "110".repeat(7).take(20)
                },
            )
        }
        val size = QrPayload.encode(dense).size
        assertTrue("载荷 $size 字节，超出单张二维码容量 ${QrPayload.MAX_QR_BYTES}", size <= QrPayload.MAX_QR_BYTES)
        // 解码侧同样完整
        val result = QrPayload.decode(QrPayload.encode(dense), totalWeeks = 20)
        assertEquals(40, result.drafts.size)
    }
}
