package com.zhijian.schedule.core.capsule

import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.ExamEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.util.WeekMask
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 胶囊状态机的共用测试夹具。
 *
 * 抽成基类的原因：断言拆在两个文件里（[CapsulePlanTest] 管「现在是哪个状态」，
 * [CapsuleScheduleTest] 管「下一次什么时候刷新」），夹具必须完全一致，
 * 否则同一份输入在两个文件里会得到不同的时间轴。
 *
 * 时间基准：2026-08-31 是周一，2026-10-13 是第 7 周（单周）的周二。
 */
abstract class CapsulePlanFixtures {
    protected fun timetable() =
        TimetableEntity(
            id = 1,
            name = "测试课表",
            startDate = "2026-08-31",
            totalWeeks = 20,
        )

    protected fun sections(): List<SectionTimeEntity> {
        val times =
            listOf(
                Triple(1, "08:20", "09:05"),
                Triple(2, "09:10", "09:55"),
                Triple(3, "10:10", "10:55"),
                Triple(4, "11:00", "11:45"),
                Triple(5, "14:20", "15:05"),
                Triple(6, "15:10", "15:55"),
                Triple(7, "16:05", "16:50"),
                Triple(8, "16:55", "17:40"),
                Triple(9, "18:20", "19:05"),
                Triple(10, "19:10", "19:55"),
                Triple(11, "20:00", "20:45"),
                Triple(12, "20:50", "21:35"),
            )
        return times.map { (no, s, e) ->
            SectionTimeEntity(
                modeId = 1,
                sectionNo = no,
                periodNo = (no + 1) / 2,
                session = if (no <= 4) "AM" else "PM",
                startTime = s,
                endTime = e,
            )
        }
    }

    protected fun course(
        day: Int,
        startSection: Int,
        endSection: Int,
        weeks: Collection<Int> = (1..20).toList(),
        name: String = "课程",
        room: String = "8A206",
        teacher: String = "张老师",
        color: Int = 0xFF4285F4.toInt(),
    ) = CourseEntity(
        id = 0,
        tableId = 1,
        name = name,
        room = room,
        teacher = teacher,
        dayOfWeek = day,
        startSection = startSection,
        endSection = endSection,
        weekMask = WeekMask.fromWeeks(20, weeks),
        color = color,
    )

    protected fun tuesday(
        h: Int,
        m: Int,
    ) = LocalDateTime.of(2026, 10, 13, h, m)

    protected fun exam(
        at: LocalDateTime,
        name: String = "高等数学",
        room: String = "教305",
        seat: String = "12",
    ) = ExamEntity(
        id = 9,
        tableId = 1,
        name = name,
        room = room,
        seat = seat,
        examTime = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
    )

    /** 2026-10-13（周二）h:m 的 epoch 毫秒 */
    protected fun epochOf(
        h: Int,
        m: Int,
    ) = LocalDateTime
        .of(2026, 10, 13, h, m)
        .atZone(ZoneId.systemDefault())
        .toInstant()
        .toEpochMilli()
}
