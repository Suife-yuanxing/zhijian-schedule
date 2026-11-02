package com.zhijian.schedule.core.jw

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * 从「教务页面写着当前是第 N 周」反推第 1 周周一。
 *
 * ## 为什么需要这条退路
 * 各家课表接口几乎都只给「第 N 周上什么课」，不给第 1 周是哪一天：强智要到
 * `jxzl/jxzl_query` 单独的周历页去取，正方 / 青果 / URP / 树维 / 金智的课表接口
 * 期里根本没有日期字段。而本地 `startDate` 来自建表当天，于是「学期第 10 周」
 * 会被算成第 1 周。
 *
 * 但这些系统的课表页**都写着「当前第 N 周」**——它本来就是为了高亮今天那列。
 * 今天是已知的，N 是已知的，第 1 周周一就是唯一解：
 *
 * ```
 * 本周周一 = 今天 - ((今天.星期值 - 1) 天)
 * 第 1 周周一 = 本周周一 - (N - 1) * 7 天
 * ```
 *
 * ## 什么时候不能用
 * 这条推导只在「今天确实落在这个学期内」时成立，所以四道闸门缺一不可（见 [infer]）：
 * 周次必须为正、不能超过总周数、推出来的起止区间必须真的包住今天、
 * 且第 1 周周一的星期必须是周一（否则说明页面上的 N 与今天不同源）。
 *
 * 纯逻辑、不碰网络与 Android API，便于 JVM 单测。
 */
object JwCurrentWeekInference {
    /** 与 `TimetableMetaPlan.MAX_WEEKS` 同口径 */
    private const val MAX_WEEKS = 30
    private const val DAYS_PER_WEEK = 7L

    /**
     * @param currentWeek 页面上写着的「当前第 N 周」
     * @param totalWeeks 学期总周数；非正数表示未知（此时只校验周次本身合理）
     * @param today 判定基准日，注入是为了可测
     * @return 第 1 周周一的 ISO 字符串；推不出可靠结果时返回 null（调用方保留本地值）
     */
    fun infer(
        currentWeek: Int,
        totalWeeks: Int,
        today: LocalDate = LocalDate.now(),
    ): String? {
        val usable = currentWeek in 1..MAX_WEEKS && (totalWeeks <= 0 || currentWeek <= totalWeeks)
        if (!usable) return null
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val start = monday.minusDays((currentWeek - 1) * DAYS_PER_WEEK)
        // 两道收口合成一个判据：起点必须是周一，且真的把今天包在学期内——
        // 否则说明页面上的 N 与「今天」不同源（跨学期切换、页面缓存），不能采信
        val elapsed = ChronoUnit.DAYS.between(start, today)
        val weeks = if (totalWeeks > 0) totalWeeks else MAX_WEEKS
        val consistent = start.dayOfWeek == DayOfWeek.MONDAY && elapsed in 0 until weeks * DAYS_PER_WEEK
        return start.toString().takeIf { consistent }
    }
}
