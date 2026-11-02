package com.zhijian.schedule.core.repository

import com.zhijian.schedule.core.jw.JwMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抓取页那条「这次没说清学期 / 开学日期」的提示。
 *
 * 这是全流程里最容易误报的一处：判宽了会把**已经拿到日期**的成功抓取报成缺失
 * （用户被要求填屏幕上刚刚显示过的值）；判窄了则什么都提示不出来，用户看着
 * 「第 1 周」以为程序算错了。所以四个分支逐一枚举。
 */
class SemesterStartDateMissingInfoTest {
    private val remembered = mapOf("2026-2027学年第一学期" to "2026-08-31")

    /** 来源自己给了日期：权威，无论学期名与记忆如何都不该提示 */
    @Test
    fun silentWhenFetchDeclaresStartDate() {
        assertNull(
            SemesterStartDateMemory.missingInfoPrompt(
                semester = "2026-2027学年第一学期",
                fetchedStartDate = "2026-08-31",
                entries = emptyMap(),
            ),
        )
        // 连学期名都没有、但给了日期：仍不提示（日期是更硬的那个信息）
        assertNull(
            SemesterStartDateMemory.missingInfoPrompt(
                semester = "",
                fetchedStartDate = "2026-08-31",
                entries = emptyMap(),
            ),
        )
    }

    /**
     * 没取到学期名：必须提示，且要提到「记住」。
     *
     * 学期名是记忆表的键，空着就无法把日期归到任何学期——这是「填一次也不复用」
     * 的唯一成因，用户不填就永远修不好。
     */
    @Test
    fun promptsWhenSemesterIsMissing() {
        val text = SemesterStartDateMemory.missingInfoPrompt("", "", emptyMap())
        assertNotNull(text)
        assertTrue("要说清这次没取到什么", text!!.contains("学期名"))
        assertTrue("要说明为什么值得填：它决定能否按学期记住", text.contains("记住"))
    }

    /** 有学期名、没日期、也没记住过：提示去填，并说明会复用 */
    @Test
    fun promptsWhenStartDateUnknown() {
        val text =
            SemesterStartDateMemory.missingInfoPrompt(
                semester = "2026-2027学年第二学期",
                fetchedStartDate = "",
                entries = remembered,
            )
        assertNotNull(text)
        assertTrue(text!!.contains("2026-2027学年第二学期"))
        assertTrue(text.contains("复用"))
    }

    /** 有学期名、没日期、但记住过：静默（抓取路径的 resolve 已经补进 meta 了） */
    @Test
    fun silentWhenRemembered() {
        assertNull(
            SemesterStartDateMemory.missingInfoPrompt(
                semester = "2026-2027学年第一学期",
                fetchedStartDate = "",
                entries = remembered,
            ),
        )
    }

    /**
     * 三条提示都**不带位置指代**。
     *
     * 它们会出现在抓取页（预览弹层里）与设置页两处。真机截图已经抓到过一次
     * 「去设置页填一次」出现在设置页上的事故（见 SemesterStartDateMemory.prompt 的注释），
     * 这条断言把同一类错法挡在两个入口之外。
     */
    @Test
    fun noPromptCarriesScreenLocation() {
        val prompts =
            listOf(
                SemesterStartDateMemory.missingInfoPrompt("", "", emptyMap()),
                SemesterStartDateMemory.missingInfoPrompt("2026-2027学年第二学期", "", emptyMap()),
            ).filterNotNull()
        assertEquals("两个分支都该给出文案", 2, prompts.size)
        prompts.forEach { text ->
            listOf("去设置页", "设置页", "上面", "下面", "这一页", "上一页").forEach { word ->
                assertFalse("提示里出现了位置指代「$word」：它会被显示在不止一屏上", text.contains(word))
            }
        }
    }

    /**
     * 后果与出路各自只出现一次。
     *
     * 仓储层的 metaHint 会把 TimetableMetaHint（后果：「周次可能不准」）与
     * SemesterStartDateMemory（出路：「在『课表信息』里填一次」）拼在一起。
     * 两边都写「开学日期未同步」时，同一条提示里会出现两句话意思相近的话——
     * 那是真机截图抓到的第二处文案问题。这里钉的是两边的分工不重叠。
     */
    @Test
    fun consequenceAndActionDoNotOverlap() {
        val action = SemesterStartDateMemory.missingPrompt("2026-2027学年第二学期").orEmpty()
        val unknownMeta = JwMeta()
        val consequence = TimetableMetaHint.missingHint(unknownMeta) ?: ""
        assertTrue("后果那一半说周次", consequence.contains("周次"))
        assertFalse("「怎么办」不该由后果那一半给", consequence.contains("填一次"))
        assertTrue("出路那一半给操作", action.contains("填一次"))
        assertFalse("「会出什么事」不该由出路那一半重复", action.contains("周次"))
    }
}
