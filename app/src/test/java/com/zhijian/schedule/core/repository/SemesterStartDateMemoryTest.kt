package com.zhijian.schedule.core.repository

import com.zhijian.schedule.core.jw.JwMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按学期记住开学日期的四条边界：查得到、记得住、过期淘汰、坏数据不扩散。
 *
 * 这张表存在的理由只有一个：**十二家教务里只有强智能读到校历**，其余只能给
 * 「第 N 周上什么课」，而本地 startDate 来自建表当天。既然取不到就让用户填一次，
 * 而「填一次」要成立，必须能回答三件事：这个学期的日期记过没有、记的是什么、
 * 换学期后会不会串。三条都在这里逐格枚举。
 */
class SemesterStartDateMemoryTest {
    @Test
    fun lookupFindsRememberedDate() {
        val table = mapOf("2026-2027学年第一学期" to "2026-08-31")
        assertEquals("2026-08-31", SemesterStartDateMemory.lookup("2026-2027学年第一学期", table))
    }

    /** 学期名在页面上偶尔带多余空格；键必须归一化，否则同一个学期会被记成两条 */
    @Test
    fun lookupNormalizesWhitespaceOnKey() {
        val table = mapOf("2026-2027学年第一学期" to "2026-08-31")
        assertEquals(
            "2026-08-31",
            SemesterStartDateMemory.lookup("  2026-2027学年第一学期  ", table),
        )
        assertEquals(
            "2026-08-31",
            SemesterStartDateMemory.lookup("2026-2027学年  第一学期", table),
        )
        // 全角空格与折行同病：学期名从页面上抄下来时这两种都可能混进来
        assertEquals(
            "2026-08-31",
            SemesterStartDateMemory.lookup("2026-2027学年\u3000第一学期", table),
        )
    }

    @Test
    fun lookupReturnsNullForUnknownSemester() {
        val table = mapOf("2026-2027学年第一学期" to "2026-08-31")
        assertNull(SemesterStartDateMemory.lookup("2026-2027学年第二学期", table))
    }

    /**
     * 空学期名不查表。
     *
     * 学期名为空是「还没填」的常态（新装用户、换学校后遗留状态）。拿空串当键
     * 会把所有这些状态查到同一条记录上，于是任何一所学校的日期都会灌进别的学校。
     */
    @Test
    fun lookupRejectsBlankSemester() {
        assertNull(SemesterStartDateMemory.lookup("", mapOf("" to "2026-08-31")))
        assertNull(SemesterStartDateMemory.lookup("   ", mapOf("" to "2026-08-31")))
    }

    /** 存着非法日期的记录视为「没记过」：一张有"2026-13-45"的表会让整学期周次算成 null */
    @Test
    fun lookupRejectsCorruptDate() {
        val table = mapOf("2026-2027学年第一学期" to "2026-13-45")
        assertNull(SemesterStartDateMemory.lookup("2026-2027学年第一学期", table))
    }

    @Test
    fun rememberAddsEntry() {
        val next = SemesterStartDateMemory.remember("2026-2027学年第一学期", "2026-08-31", emptyMap())
        assertEquals(mapOf("2026-2027学年第一学期" to "2026-08-31"), next)
    }

    @Test
    fun rememberOverwritesSameSemester() {
        val before = mapOf("2026-2027学年第一学期" to "2026-08-31")
        val next = SemesterStartDateMemory.remember("2026-2027学年第一学期", "2026-09-07", before)
        assertEquals(1, next.size)
        assertEquals("2026-09-07", next["2026-2027学年第一学期"])
    }

    @Test
    fun rememberIgnoresUnusableInput() {
        val before = mapOf("2026-2027学年第一学期" to "2026-08-31")
        assertEquals(before, SemesterStartDateMemory.remember("", "2026-09-07", before))
        assertEquals(before, SemesterStartDateMemory.remember("另一个学期", "not-a-date", before))
    }

    /**
     * 容量上限按「最久未用」淘汰，而重写会把该学期移到队尾。
     *
     * 刚填的那个学期不该是第一个被挤掉的：用户刚填完，下一学期又填了一个，
     * 结果把自己刚刚填的那个挤没了——那等于「填一次」这件事本身失效。
     */
    @Test
    fun rememberEvictsOldestFirstAndKeepsFreshOnes() {
        var table = emptyMap<String, String>()
        repeat(SemesterStartDateMemory.MAX_ENTRIES) { i ->
            table = SemesterStartDateMemory.remember("学期$i", "2026-08-31", table)
        }
        assertEquals(SemesterStartDateMemory.MAX_ENTRIES, table.size)

        // 重写最早那一条：它应该被移到队尾，之后新增时才轮到别的被淘汰
        table = SemesterStartDateMemory.remember("学期0", "2026-09-01", table)
        table = SemesterStartDateMemory.remember("新来的", "2026-09-02", table)

        assertEquals(SemesterStartDateMemory.MAX_ENTRIES, table.size)
        assertNotNull("刚重写过的学期被淘汰了", table["学期0"])
        assertNotNull("刚记下的学期被淘汰了", table["新来的"])
        assertNull("最早写入且未再碰过的「学期1」应当被淘汰", table["学期1"])
    }

    /**
     * 来源自己给了日期就以来源为准。
     *
     * 反过来的话，一所换过校历的学校永远同步不到新日期——记忆里的旧值会
     * 一直盖住教务刚刚给出的新值，而用户看不出「这个日期其实是去年填的」。
     */
    @Test
    fun resolvePrefersSourceOverMemory() {
        val table = mapOf("2026-2027学年第一学期" to "2020-01-06")
        val meta = JwMeta(semester = "2026-2027学年第一学期", startDate = "2026-08-31")
        assertEquals("2026-08-31", SemesterStartDateMemory.resolve(meta, table).startDate)
    }

    @Test
    fun resolveFillsMissingStartDateFromMemory() {
        val table = mapOf("2026-2027学年第一学期" to "2026-08-31")
        val meta = JwMeta(semester = "2026-2027学年第一学期", totalWeeks = 20)
        val resolved = SemesterStartDateMemory.resolve(meta, table)
        assertEquals("2026-08-31", resolved.startDate)
        // 其余字段原样保留：补日期不该顺手改掉总周数
        assertEquals(20, resolved.totalWeeks)
    }

    /** 来源连学期名都没给时，退回用本地课表的学期名；否则这条记忆永远查不到 */
    @Test
    fun resolveFallsBackToLocalSemester() {
        val table = mapOf("2026-2027学年第一学期" to "2026-08-31")
        val resolved = SemesterStartDateMemory.resolve(JwMeta(totalWeeks = 20), table, "2026-2027学年第一学期")
        assertEquals("2026-08-31", resolved.startDate)
    }

    @Test
    fun resolveLeavesMetaAloneWhenNothingKnown() {
        val meta = JwMeta(semester = "2026-2027学年第二学期")
        assertEquals(meta, SemesterStartDateMemory.resolve(meta, emptyMap()))
    }

    /**
     * 编码/解码必须无损，且顺手把坏行滤掉而不是整表作废。
     *
     * 一个字符写坏就丢掉整个学期的记忆，代价只是用户再填一次；而整表作废会让
     * 另外十一个学期的记录一起消失——那才是真正的数据丢失。
     */
    @Test
    fun encodeDecodeRoundTrip() {
        val table =
            mapOf(
                "2026-2027学年第一学期" to "2026-08-31",
                "2026-2027学年第二学期" to "2027-02-22",
            )
        assertEquals(table, SemesterStartDateMemory.decode(SemesterStartDateMemory.encode(table)))
    }

    @Test
    fun decodeSkipsCorruptRowsAndKeepsGoodOnes() {
        val text =
            listOf(
                "2026-2027学年第一学期\t2026-08-31",
                "没有分隔符的一行",
                "学期名\t2026-99-99",
                "\t2026-09-01",
                "2026-2027学年第二学期\t2027-02-22",
            ).joinToString("\n")
        val table = SemesterStartDateMemory.decode(text)
        assertEquals(2, table.size)
        assertEquals("2026-08-31", table["2026-2027学年第一学期"])
        assertEquals("2027-02-22", table["2026-2027学年第二学期"])
    }

    @Test
    fun decodeEmptyInputIsEmptyTable() {
        assertEquals(emptyMap<String, String>(), SemesterStartDateMemory.decode(""))
        assertEquals(emptyMap<String, String>(), SemesterStartDateMemory.decode("   "))
    }

    /**
     * 提示只在「这个学期没有记录」时出现，且把学期名说出来。
     *
     * 只说「开学日期未设置」会让用户无法判断到底该填什么值；说出学期名，
     * 他就知道这是 2026-2027 学年的第一周。
     */
    @Test
    fun promptNamesTheSemesterAndOnlyWhenMissing() {
        val text = SemesterStartDateMemory.prompt("2026-2027学年第一学期", emptyMap())
        assertNotNull(text)
        assertTrue("提示必须点名学期，否则用户不知道该填哪个学期的日期", text!!.contains("2026-2027学年第一学期"))
        assertTrue("提示要点名那块控件，否则用户找不到去哪填", text.contains("课表信息"))
        assertTrue("提示要说明之后会复用，否则用户以为每学期都得填", text.contains("复用"))
    }

    /**
     * 提示里**不能**写「去设置页」这类位置指代。
     *
     * 真机截图当场抓到的：同一句话也出现在设置页上，于是它说「去设置页填一次」，
     * 而用户正站在那一页、那句提示還压着「课表信息」卡片。两句话单独读都通顺，
     * 所以这只能靠断言把位置词挡在门外——只点名控件名（「课表信息」全 App 唯一），
     * 两个屏幕才都成立。
     */
    @Test
    fun promptCarriesNoScreenLocation() {
        val text = SemesterStartDateMemory.prompt("2026-2027学年第一学期", emptyMap()).orEmpty()
        listOf("去设置页", "设置页", "上面", "下面", "这一页", "上一页").forEach { word ->
            assertFalse("提示里出现了位置指代「$word」：它会被显示在不止一屏上", text.contains(word))
        }
    }

    @Test
    fun promptIsSilentOnceRemembered() {
        val table = mapOf("2026-2027学年第一学期" to "2026-08-31")
        assertNull(SemesterStartDateMemory.prompt("2026-2027学年第一学期", table))
    }

    /** 学期名为空时没有可提示的键，也就说不出该填哪个学期——此时不打扰 */
    @Test
    fun promptIsSilentForBlankSemester() {
        assertNull(SemesterStartDateMemory.prompt("", emptyMap()))
        assertNull(SemesterStartDateMemory.missingPrompt(""))
    }

    /** 「已确认没有来源」的那条捷径与查空表等价：调用点在预览已经补全之后用它 */
    @Test
    fun missingPromptEqualsEmptyTableLookup() {
        val semester = "2026-2027学年第一学期"
        assertEquals(
            SemesterStartDateMemory.prompt(semester, emptyMap()),
            SemesterStartDateMemory.missingPrompt(semester),
        )
    }

    /** 提示里不出现任何具体教务厂家的名字：它说的是一件与厂家无关的事 */
    @Test
    fun promptMentionsNoVendor() {
        val text = SemesterStartDateMemory.prompt("2026-2027学年第一学期", emptyMap()).orEmpty()
        listOf("强智", "正方", "青果", "URP", "树维", "金智", "eams", "超星").forEach { vendor ->
            assertFalse("提示里出现了厂家名「$vendor」，而这条与厂家无关", text.contains(vendor))
        }
    }
}
