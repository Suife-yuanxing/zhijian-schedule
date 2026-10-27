package com.zhijian.schedule.core.jw

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SchoolCatalogTest {
    private val tsv =
        """
        甲大学	jiadaxue	https://jw.jia.edu.cn/jwglxt/xtgl/login_slogin.html	zhengfang_new	1
        乙学院	yixueyuan	https://jw.yi.edu.cn/eams/login.action	shuwei_classic	0
        丙职业技术学院	bingzhiyejishuxueyuan	http://jw.bing.edu.cn/jsxsd/	qiangzhi	1
        """.trimIndent()

    @Before
    fun setUp() {
        SchoolCatalog.loadFrom(tsv)
    }

    @After
    fun tearDown() {
        SchoolCatalog.resetForTest()
    }

    @Test
    fun `catalog parses name pinyin url adapter and verified flag`() {
        val jia = SchoolCatalog.byHost("jw.jia.edu.cn")
        assertNotNull(jia)
        assertEquals("甲大学", jia?.name)
        assertEquals("zhengfang_new", jia?.adapterId)
        assertEquals("/jwglxt/", jia?.basePath)
        assertTrue(jia?.verified == true)
        assertEquals("https://jw.jia.edu.cn/jwglxt/xtgl/login_slogin.html", jia?.entry())
    }

    @Test
    fun `unverified entry is kept but marked`() {
        val yi = SchoolCatalog.byHost("JW.YI.EDU.CN")
        assertNotNull(yi)
        assertFalse(yi?.verified == true)
        assertEquals("shuwei_classic", yi?.adapterId)
    }

    @Test
    fun `search matches pinyin prefix first`() {
        val hits = SchoolCatalog.search("jia")
        assertEquals("甲大学", hits.first().name)
        assertTrue(hits.first().verified)
    }

    @Test
    fun `search matches chinese name`() {
        val hits = SchoolCatalog.search("乙学院")
        assertEquals(1, hits.size)
        assertEquals("yixueyuan", hits.first().pinyin)
    }

    @Test
    fun `search matches host`() {
        val hits = SchoolCatalog.search("bing.edu.cn")
        assertEquals("丙职业技术学院", hits.first().name)
    }

    @Test
    fun `groups use pinyin initial`() {
        val initials = SchoolCatalog.groups().map { it.first }
        assertTrue(initials.contains('J'))
        assertTrue(initials.contains('Y'))
        assertTrue(initials.contains('B'))
    }

    @Test
    fun `builtin school survives catalog merge`() {
        assertNotNull(SchoolCatalog.byHost("jw.dgpt.edu.cn"))
    }

    @Test
    fun `adapter registry uses catalog adapter id`() {
        // 与目录协议一致：乙学院是树维，不应被强智的 80 分兜底抢走
        assertEquals("shuwei_classic", JwAdapterRegistry.forUrl("https://jw.yi.edu.cn/")?.id)
        // 甲大学目录标 zhengfang_new，但路径里没有 jwglxt 指纹时仍按目录识别
        assertEquals("zhengfang_new", JwAdapterRegistry.forUrl("https://jw.jia.edu.cn/")?.id)
    }

    @Test
    fun `unknown host stays unrecognized`() {
        assertNull(JwAdapterRegistry.forUrl("https://jw.unknown.edu.cn/"))
        assertNull(SchoolCatalog.byHost("jw.unknown.edu.cn"))
    }

    @Test
    fun `reset clears loaded catalog`() {
        SchoolCatalog.resetForTest()
        assertNull(SchoolCatalog.byHost("jw.jia.edu.cn"))
        assertNotNull(SchoolCatalog.byHost("jw.dgpt.edu.cn"))
    }
}
