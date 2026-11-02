package com.zhijian.schedule.core.jw

import org.junit.Assert.assertEquals
import org.junit.Test

/** 学年 + 学期码 → 学期名。两套编码都要认，认不出必须返回空串（不覆盖本地值）。 */
class JwSemesterLabelTest {
    @Test
    fun `zhengfang term code 3 is first semester`() {
        assertEquals("2026-2027学年第一学期", JwSemesterLabel.of("2026-2027", "3"))
    }

    @Test
    fun `zhengfang term code 12 is second semester`() {
        assertEquals("2026-2027学年第二学期", JwSemesterLabel.of("2026-2027", "12"))
    }

    /** 强智 jsxsd 用 1/2，同一所学校两个页面可能给出不同编码 */
    @Test
    fun `qiangzhi term codes 1 and 2 are recognized`() {
        assertEquals("2026-2027学年第一学期", JwSemesterLabel.of("2026-2027", "1"))
        assertEquals("2026-2027学年第二学期", JwSemesterLabel.of("2026-2027", "2"))
    }

    /** 只给起始年时补成完整学年，否则会拼出「2026学年第一学期」这种半截名字 */
    @Test
    fun `bare start year is expanded to a full academic year`() {
        assertEquals("2026-2027学年第一学期", JwSemesterLabel.of("2026", "3"))
    }

    /** 学年跨度写成 2026-2025 属于脏数据，按起始年重算而不是原样传下去 */
    @Test
    fun `invalid year span falls back to start year plus one`() {
        assertEquals("2026-2027学年第一学期", JwSemesterLabel.of("2026-2025", "3"))
    }

    @Test
    fun `unknown term code yields empty label`() {
        assertEquals("", JwSemesterLabel.of("2026-2027", "9"))
        assertEquals("", JwSemesterLabel.of("2026-2027", ""))
        assertEquals("", JwSemesterLabel.of("2026-2027", "第一学期"))
    }

    @Test
    fun `unknown academic year yields empty label`() {
        assertEquals("", JwSemesterLabel.of("", "3"))
        assertEquals("", JwSemesterLabel.of("abc", "3"))
    }

    /** 空白与全角不该让整条同步失败：库里存的就是用户看得见的字符串 */
    @Test
    fun `whitespace and separator variants are tolerated`() {
        assertEquals("2026-2027学年第一学期", JwSemesterLabel.of(" 2026 - 2027 ", " 3 "))
    }

    @Test
    fun `semesterOnly keeps blank name out of meta`() {
        assertEquals(null, JwMeta.semesterOnly(""))
        assertEquals(null, JwMeta.semesterOnly("   "))
        assertEquals("2026-2027学年第一学期", JwMeta.semesterOnly("2026-2027学年第一学期")?.semester)
    }
}
