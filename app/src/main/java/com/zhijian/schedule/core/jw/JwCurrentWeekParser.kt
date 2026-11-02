package com.zhijian.schedule.core.jw

import org.jsoup.Jsoup

/**
 * 从教务课表页 HTML 里读「当前第 N 周」。
 *
 * ## 为什么这件事值得单独做
 * 正方 / 青果 / URP / 树维 / 金智的课表接口都不含日期（已逐家核对过返回字段）。
 * 但它们的课表页**几乎都有一个周次下拉**——那是给学生切周看的，当前选中项就是本周。
 * 拿到 N，配合今天的日期就能反推第 1 周周一（见 [JwCurrentWeekInference]）。
 *
 * ## 只认「明确写着」的形态
 * 宁可不取也不能取错：这个数字一旦错了，反推出的开学日期会让整学期的周次整体平移，
 * 而界面上看不出任何异常。所以这里只匹配两种有明确语义的位置：
 * - `<select>` 里带 `selected` 的周次项（下拉的当前值）；
 * - 独立元素里的「当前第 N 周」/「第 N 周」文案。
 *
 * 刻意**不**去猜「页面里最大的周次数字就是当前周」这类启发式——
 * 那会把课程周次（1-16 周）误当成当前周。
 *
 * 纯逻辑、不碰网络，便于 JVM 单测。
 */
object JwCurrentWeekParser {
    /** 与 `TimetableMetaPlan.MAX_WEEKS` 同口径 */
    private const val MAX_WEEKS = 30

    /**
     * 周次下拉的候选 id / name，按各厂商实际写法排列。
     *
     * 只用 `option[selected]` 属性选择器：Jsoup 不认 `:checked`（会抛
     * SelectorParseException），而服务端返回的 HTML 里当前项本来就是以
     * `selected` 属性落盘的。
     */
    private val WEEK_SELECTS =
        listOf(
            "select#zc option[selected]",
            "select[name=zc] option[selected]",
            "select#currentWeek option[selected]",
            "select[name=currentWeek] option[selected]",
        )

    /** 「当前第 N 周」「本周为第 N 周」这类文案；数字前后必须真的是「第…周」 */
    private val CURRENT_WEEK_TEXT = Regex("""(?:当前|本周|现在)[^0-9]{0,6}第\s*(\d{1,2})\s*周""")

    /**
     * @return 1..30 之间的周次；页面上没有明确写法时返回 null（调用方按「没有线索」处理）
     */
    fun parse(html: String): Int? {
        if (html.isBlank()) return null
        val doc = Jsoup.parse(html)
        WEEK_SELECTS.forEach { selector ->
            val picked = doc.selectFirst(selector) ?: return@forEach
            val value = picked.attr("value").trim().ifEmpty { picked.text().trim() }
            value.toIntOrNull()?.takeIf { it in 1..MAX_WEEKS }?.let { return it }
        }
        val text = doc.text()
        val m = CURRENT_WEEK_TEXT.find(text) ?: return null
        return m.groupValues[1].toIntOrNull()?.takeIf { it in 1..MAX_WEEKS }
    }
}
