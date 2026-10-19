package com.zhijian.schedule.capsule

import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.core.util.WeekMask
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.roundToInt

/** 实时胶囊的状态快照（纯数据，便于单测） */
sealed interface CapsulePlan {
    /** 状态栏 chip 上的短文字（shortCriticalText） */
    val chip: String

    /** 正在上课 */
    data class Ongoing(
        val course: CapsuleCourse,
        /** 剩余占比（100→0 倒着消耗）：进度条随倒计时一起减少 */
        val progress: Int,
        val remainingMinutes: Long,
        /** 下课时刻（epoch 毫秒）：交系统 chronometer 每秒自绘，进程被冻结也照常走秒 */
        val endAtMillis: Long,
        /** 命中的提醒节点（剩余分钟）；非提醒态为 null */
        val reminderMinutes: Long? = null,
        /** 提醒态结束时刻（epoch 毫秒）；非提醒态为 null */
        val reminderUntilMillis: Long? = null,
        /** 下一提醒节点时刻（epoch 毫秒）；没有后续节点为 null */
        val nextReminderAtMillis: Long? = null,
    ) : CapsulePlan {
        override val chip: String
            get() = when {
                reminderMinutes != null -> "距下课${reminderMinutes}分"
                remainingMinutes <= 0 -> "即将下课"
                else -> "剩 $remainingMinutes 分"
            }
    }

    /** 即将上课：已进入课前提醒窗口，画倒数进度条（联动「课前提醒」设置） */
    data class AboutToStart(
        val course: CapsuleCourse,
        val minutesUntil: Long,
        /** 剩余占比（100→0 倒着消耗）：进度条随倒计时一起减少 */
        val progress: Int,
        /** 上课时刻（epoch 毫秒）：交系统 chronometer 每秒自绘 */
        val startAtMillis: Long,
        override val chip: String = when {
            minutesUntil <= 0 -> "马上上课"
            else -> "还有 $minutesUntil 分"
        },
    ) : CapsulePlan

    /** 下节课（还没到课前提醒窗口）：不画进度条、不提升为流体云 */
    data class Upcoming(
        val course: CapsuleCourse,
        val minutesUntil: Long,
        /** 进入课前提醒窗口的时刻（epoch 毫秒）；提醒关闭时为 null */
        val aboutToStartAtMillis: Long? = null,
        override val chip: String = "下节课",
    ) : CapsulePlan

    /** 法定节假日（休）：当日课表课程按停课处理，胶囊按假期展示 */
    data class Holiday(
        val name: String,
        /** 假后最近一节课，如「明天 08:20 · 包装装潢设计」 */
        val nextText: String?,
        override val chip: String = "假期",
    ) : CapsulePlan

    /** 今天的课已上完（或今天没课） */
    data class DayDone(
        val hadClassesToday: Boolean,
        /** 如「明天 08:20 · 包装材料与检测」，未来 7 天都没有则为 null */
        val nextText: String?,
    ) : CapsulePlan {
        override val chip: String get() = if (hadClassesToday) "已结束" else "无课"
    }

    /** 还没有课表 */
    data object NoTable : CapsulePlan {
        override val chip: String = "纸间课表"
    }
}

data class CapsuleCourse(
    val name: String,
    val room: String,
    val teacher: String,
    val color: Int,
    val start: LocalTime,
    val end: LocalTime,
    /** 节次描述（如「第7-8节」），通知副标题用 */
    val sectionText: String? = null,
)

/**
 * 上课中的提醒节点（剩余分钟，降序）。
 * 主节点取课堂最后 1/3（10–60 分钟，按 5 分钟取整），最后 5 分钟再提醒一次；
 * 过短课程若主节点会落在课堂前半段，则只保留最后 5 分钟节点。
 */
fun reminderMilestones(totalMinutes: Long): List<Long> {
    if (totalMinutes <= 5) return emptyList()
    val primary = (((totalMinutes / 3.0) / 5).roundToInt() * 5).coerceIn(10, 60)
    return buildList {
        if (primary.toLong() * 2 <= totalMinutes) add(primary.toLong())
        add(5L)
    }.filter { it in 1L until totalMinutes }.distinct().sortedDescending()
}

/** 节点提醒文案停留时长（分钟），之后回到实时倒计时 */
const val REMINDER_HIGHLIGHT_MINUTES = 2L

private const val REMINDER_HIGHLIGHT_MS = REMINDER_HIGHLIGHT_MINUTES * 60_000L

/** 「即将上课 / 上课中」需要实时刷新，也是持有 wake lock 的时段 */
val CapsulePlan.isLive: Boolean
    get() = this is CapsulePlan.Ongoing || this is CapsulePlan.AboutToStart

fun buildCapsulePlan(
    timetable: TimetableEntity?,
    courses: List<CourseEntity>,
    sections: List<SectionTimeEntity>,
    now: LocalDateTime,
    /** 课前提醒提前量（分钟）；<=0（课前提醒关闭）时不产生「即将上课」状态 */
    reminderMinutes: Int,
    /** 今天是法定假日（休）时的节日名；调休补班日与平时传 null */
    holidayName: String? = null,
): CapsulePlan {
    val table = timetable ?: return CapsulePlan.NoTable
    val startDate = TimeUtils.parseDate(table.startDate) ?: return CapsulePlan.NoTable

    val today = now.toLocalDate()
    val nowTime = now.toLocalTime()

    // 法定假日：课表课程不执行，直接进入假期状态（不做上课/倒数判断）
    if (holidayName != null) {
        return CapsulePlan.Holiday(
            name = holidayName,
            nextText = nextOccurrenceText(startDate, table.totalWeeks, courses, sections, today),
        )
    }

    val week = TimeUtils.weekOfSemester(startDate, table.totalWeeks, today)
    val dow = TimeUtils.dayOfWeek(today)
    val todayCourses = if (week == null) emptyList() else {
        courses.asSequence()
            .filter { it.dayOfWeek == dow && WeekMask.contains(it.weekMask, week) }
            .sortedBy { it.startSection }
            .mapNotNull { entity -> courseOf(entity, sections) }
            .toList()
    }

    todayCourses
        .firstOrNull { !nowTime.isBefore(it.start) && nowTime.isBefore(it.end) }
        ?.let { c ->
            val total = secondsBetween(c.start, c.end)
            val elapsed = secondsBetween(c.start, nowTime).coerceIn(0, total)
            val endAtMillis = epochMillisOf(now, c.end)
            val nowMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val nodes = reminderMilestones(Duration.between(c.start, c.end).toMinutes())
            val activeNode = nodes.firstOrNull { minutes ->
                val at = endAtMillis - minutes * 60_000L
                nowMillis >= at && nowMillis < at + REMINDER_HIGHLIGHT_MS
            }
            val nextNode = nodes.firstOrNull { minutes ->
                endAtMillis - minutes * 60_000L > nowMillis
            }
            return CapsulePlan.Ongoing(
                course = c,
                // 剩余占比：开课满格、随课堂消耗到 0，与系统倒计时同向
                progress = (((total - elapsed) * 100) / total).toInt(),
                remainingMinutes = Duration.between(nowTime, c.end).toMinutes().coerceAtLeast(0),
                endAtMillis = endAtMillis,
                reminderMinutes = activeNode,
                reminderUntilMillis = activeNode?.let {
                    endAtMillis - it * 60_000L + REMINDER_HIGHLIGHT_MS
                },
                nextReminderAtMillis = nextNode?.let { endAtMillis - it * 60_000L },
            )
        }

    todayCourses
        .firstOrNull { it.start.isAfter(nowTime) }
        ?.let { c ->
            val minutesUntil = Duration.between(nowTime, c.start).toMinutes().coerceAtLeast(0)
            // 课前提醒窗口：从 max(上一节课下课, 开课前 reminderMinutes) 起倒数
            val windowStart = if (reminderMinutes > 0) {
                val remindAt = c.start.minusMinutes(reminderMinutes.toLong())
                val prevEnd = todayCourses.lastOrNull { !it.end.isAfter(nowTime) }?.end
                if (prevEnd != null && prevEnd.isAfter(remindAt)) prevEnd else remindAt
            } else null
            if (windowStart != null && !nowTime.isBefore(windowStart)) {
                val span = secondsBetween(windowStart, c.start).coerceAtLeast(1)
                val done = secondsBetween(windowStart, nowTime).coerceAtMost(span)
                return CapsulePlan.AboutToStart(
                    course = c,
                    minutesUntil = minutesUntil,
                    // 剩余占比：进入倒数窗口满格，到上课时刻消耗到 0
                    progress = (((span - done) * 100) / span).toInt(),
                    startAtMillis = epochMillisOf(now, c.start),
                )
            }
            return CapsulePlan.Upcoming(
                course = c,
                minutesUntil = minutesUntil,
                aboutToStartAtMillis = windowStart?.let { epochMillisOf(now, it) },
            )
        }

    return CapsulePlan.DayDone(
        hadClassesToday = todayCourses.isNotEmpty(),
        nextText = nextOccurrenceText(startDate, table.totalWeeks, courses, sections, today),
    )
}

private fun courseOf(entity: CourseEntity, sections: List<SectionTimeEntity>): CapsuleCourse? {
    val start = sections.getOrNull(entity.startSection - 1)?.startTime?.let { TimeUtils.parseTime(it) }
    val end = sections.getOrNull(entity.endSection - 1)?.endTime?.let { TimeUtils.parseTime(it) }
    if (start == null || end == null || !start.isBefore(end)) return null
    return CapsuleCourse(
        name = entity.name,
        room = entity.room,
        teacher = entity.teacher,
        color = entity.color,
        start = start,
        end = end,
        sectionText = if (entity.startSection == entity.endSection) {
            "第${entity.startSection}节"
        } else {
            "第${entity.startSection}-${entity.endSection}节"
        },
    )
}

/** from→to 的秒数；跨度可能为 0 的场景（进度边界）需自行 coerce */
private fun secondsBetween(from: LocalTime, to: LocalTime): Long =
    Duration.between(from, to).seconds

/** now 所在日期下 time 对应的 epoch 毫秒（课程时段不跨天） */
private fun epochMillisOf(now: LocalDateTime, time: LocalTime): Long =
    now.toLocalDate().atTime(time).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/**
 * 下一次胶囊状态会变化的时刻（epoch 毫秒），null 表示无需重算。
 * 供 CapsuleCoordinator 登记精确闹钟：ColorOS 冻结后台进程时服务刷新循环停摆，
 * 只有系统闹钟能按时唤醒 App 切换到「即将上课 / 提醒节点 / 上课中」等新状态。
 */
fun nextTransitionAtMillis(plan: CapsulePlan, now: LocalDateTime): Long? = when (plan) {
    // 提醒态先到点回落；否则等下一提醒节点，最后才等下课
    is CapsulePlan.Ongoing ->
        plan.reminderUntilMillis ?: plan.nextReminderAtMillis ?: plan.endAtMillis
    is CapsulePlan.AboutToStart -> plan.startAtMillis
    is CapsulePlan.Upcoming -> plan.aboutToStartAtMillis ?: epochMillisOf(now, plan.course.start)
    is CapsulePlan.Holiday, is CapsulePlan.DayDone -> nextMidnightMillis(now)
    CapsulePlan.NoTable -> null
}

/**
 * 心跳闹钟时刻（epoch 毫秒）：切换闹钟之外的第二道保障，兼作胶囊保鲜。
 * -「即将上课 / 上课中」：min(切换 + 1min, now + 6min) —— 切换或提醒节点闹钟被系统
 *   丢弃、单次渲染失败时 1 分钟内自愈（真机事故 2026-10-16：14:20 上课切换丢失，
 *   胶囊停在「即将上课 00:00」直到打开 App）；课堂期间每 6 分钟重渲染一次，
 *   被冻结时 chip「剩 N 分」与进度条也不会陈旧过久
 * - 所有课程态在**切换前 15 分钟**额外唤醒一次：这次唤醒让 Coordinator 有机会
 *   把切换/提醒节点闹钟升级成 setAlarmClock（ColorOS 会把普通精确闹钟降级成 +1h 窗口）
 * - 假期 / 已结束：切换 + 2min —— 切换丢失时 2 分钟内自愈，其余时间不打扰
 * 返回 null 表示无需登记（没有下一次切换）。
 */
fun nextRefreshAtMillis(plan: CapsulePlan, now: LocalDateTime): Long? {
    val next = nextTransitionAtMillis(plan, now) ?: return null
    val nowMillis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    val preTransition = if (plan is CapsulePlan.Ongoing ||
        plan is CapsulePlan.AboutToStart ||
        plan is CapsulePlan.Upcoming
    ) {
        (next - PRE_TRANSITION_WAKE_MS).takeIf { it > nowMillis }
    } else {
        null
    }
    val regular = if (plan is CapsulePlan.Ongoing || plan is CapsulePlan.AboutToStart) {
        val heartbeat = now.plusMinutes(6).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        minOf(next + BEAT_AFTER_TRANSITION_MS, heartbeat)
    } else {
        next + BEAT_AFTER_TRANSITION_IDLE_MS
    }
    return if (preTransition == null) regular else minOf(regular, preTransition)
}

/** 切换前多久额外唤醒一次（用于把普通精确闹钟升级成系统闹钟兜底） */
private const val PRE_TRANSITION_WAKE_MS = 15 * 60_000L

/** 实时态：切换后 1 分钟的自愈心跳 */
private const val BEAT_AFTER_TRANSITION_MS = 60_000L

/** 非实时态：切换后 2 分钟的自愈心跳 */
private const val BEAT_AFTER_TRANSITION_IDLE_MS = 120_000L

/**
 * 倒计时卡片的系统级兜底**延时**（ms，交给 setTimeoutAfter）：从这个时长之后，
 * 系统自动撤下实时态通知——不依赖 App 被唤醒。
 *
 * 真机事故（2026-10-16 21:35）：下课切换闹钟与心跳闹钟被 ROM 一并吞掉（进程冻结期），
 * 卡片停在「正在上课 · 00:00」47 分钟直到打开 App。倒计时文字由系统 chronometer 自绘
 * 所以准时归零，但状态切换全靠 App 侧触发——这条兜底让「过期卡片」由系统计时保证退场。
 *
 * 「即将上课」宽限更长：进程存活时切换（上课中）通常 30s 内到达，留出窗口避免开场闪断；
 * 「上课中」宽限短：下课就该尽快退场，别继续挂着 00:00。
 */
fun countdownCleanupDelayMillis(plan: CapsulePlan, nowMillis: Long): Long? = when (plan) {
    is CapsulePlan.Ongoing ->
        (plan.endAtMillis - nowMillis).coerceAtLeast(0) + CLEANUP_GRACE_AFTER_END_MS
    is CapsulePlan.AboutToStart ->
        (plan.startAtMillis - nowMillis).coerceAtLeast(0) + CLEANUP_GRACE_AFTER_START_MS
    else -> null
}

private const val CLEANUP_GRACE_AFTER_END_MS = 10_000L
private const val CLEANUP_GRACE_AFTER_START_MS = 40_000L

/**
 * 临近状态切换时是否用 [android.app.AlarmManager.setAlarmClock] 兜底。
 *
 * ColorOS 会冻结后台进程并把第三方精确闹钟降级成 +1h 窗口（真机事故 2026-10-16/17），
 * `setAlarmClock` 是系统优先级最高的闹钟，Doze 中也能准点唤醒，代价是状态栏出现闹钟图标。
 * 因此只对「切换」且已进入 15 分钟窗口时使用，并由设置开关控制（默认开启）。
 */
fun shouldUseAlarmClockFallback(
    nowMillis: Long,
    transitionAtMillis: Long,
    alarmFallbackEnabled: Boolean,
    exactAlarmsAllowed: Boolean,
): Boolean = alarmFallbackEnabled &&
    exactAlarmsAllowed &&
    transitionAtMillis > nowMillis &&
    transitionAtMillis - nowMillis <= ALARM_CLOCK_WINDOW_MS

/** 切换前多久开始用系统闹钟兜底 */
const val ALARM_CLOCK_WINDOW_MS = 15 * 60_000L

private fun nextMidnightMillis(now: LocalDateTime): Long =
    now.toLocalDate().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** 未来 7 天里最近的一节课，如「明天 08:20 · 包装材料与检测」 */
private fun nextOccurrenceText(
    startDate: LocalDate,
    totalWeeks: Int,
    courses: List<CourseEntity>,
    sections: List<SectionTimeEntity>,
    today: LocalDate,
): String? {
    for (offset in 1..7) {
        val date = today.plusDays(offset.toLong())
        val week = TimeUtils.weekOfSemester(startDate, totalWeeks, date) ?: continue
        val dow = TimeUtils.dayOfWeek(date)
        val course = courses
            .filter { it.dayOfWeek == dow && WeekMask.contains(it.weekMask, week) }
            .sortedBy { it.startSection }
            .firstOrNull { courseOf(it, sections) != null } ?: continue
        val start = sections.getOrNull(course.startSection - 1)?.startTime ?: continue
        val label = if (offset == 1) "明天" else TimeUtils.dayName(dow)
        return "$label $start · ${course.name}"
    }
    return null
}
