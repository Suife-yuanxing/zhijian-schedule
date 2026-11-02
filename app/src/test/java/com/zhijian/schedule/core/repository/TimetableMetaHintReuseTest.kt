package com.zhijian.schedule.core.repository

import com.zhijian.schedule.core.jw.JwMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「记忆补上来的开学日期」在预览里必须说清来源。
 *
 * 这条管的是**用户能否发现学校换了校历**：预览里写着「课表信息将随抓取同步：
 * 第 1 周 2026-08-31」，用户会理所当然地认为这是教务给的；而它其实来自上一次
 * 自己手填的值。学校若调整过校历，这条提示是他唯一的线索——不说来源，他没有任何
 * 理由去怀疑那个日期。
 *
 * 判断本身仍然是纯函数（[TimetableMetaHint]），这里只钉「补全后不该再说缺」。
 */
class TimetableMetaHintReuseTest {
    private val local =
        TimetableMetaHint.LocalMeta(
            semester = "2026-2027学年第一学期",
            startDate = "2026-11-02",
            totalWeeks = 20,
        )

    /** 记忆补上之后，来源声明的日期就非空了，此时不该再说「开学日期未同步」 */
    @Test
    fun filledStartDateSilencesMissingHint() {
        val meta = JwMeta(semester = "2026-2027学年第一学期", totalWeeks = 20)
        assertTrue("前提：未补全时确实会报缺日期", TimetableMetaHint.missingHint(meta) != null)

        val resolved = SemesterStartDateMemory.resolve(meta, mapOf("2026-2027学年第一学期" to "2026-08-31"))
        assertEquals("2026-08-31", resolved.startDate)
        assertNull("补全之后还在喊缺日期，等于让用户白填一遍", TimetableMetaHint.missingHint(resolved))
    }

    /**
     * 补全**不能**把「日期会变」这件事一起抹掉。
     *
     * 本地那个 startDate 来自建表当天（2026-11-02），记忆里是 2026-08-31：
     * 两者不同，预览必须照旧列出「第 1 周 2026-08-31」。若因为"日期是记忆补的"
     * 就把这条改动藏起来，用户会在应用之后发现周次整体平移，却找不到原因。
     */
    @Test
    fun filledStartDateStillReportsTheChange() {
        val meta = JwMeta(semester = "2026-2027学年第一学期", totalWeeks = 20)
        val resolved = SemesterStartDateMemory.resolve(meta, mapOf("2026-2027学年第一学期" to "2026-08-31"))
        val planned =
            com.zhijian.schedule.core.data.db.TimetableEntity(
                id = 1,
                name = "我的课表",
                semester = local.semester,
                startDate = local.startDate,
                totalWeeks = local.totalWeeks,
            )
        val after = TimetableMetaPlan.apply(planned, resolved)
        val labels =
            TimetableMetaHint.changedLabels(
                before = local,
                after = TimetableMetaHint.LocalMeta(after.semester, after.startDate, after.totalWeeks),
            )
        assertEquals(listOf("第 1 周 2026-08-31"), labels)
    }
}
