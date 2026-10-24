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
