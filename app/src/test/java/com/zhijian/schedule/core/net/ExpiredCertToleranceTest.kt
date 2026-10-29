package com.zhijian.schedule.core.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 校方证书过期后的**受限放行**策略。
 *
 * 这一组用例钉住的不是"能不能放行"，而是"放行被限制到什么程度"：
 * 主机名单精确、只认过期不认其它错误、过期天数上限 30 天。
 */
class ExpiredCertToleranceTest {
    private companion object {
        const val DAY = 24L * 60 * 60 * 1000L

        /** 广西工程职业学院门户证书的 notAfter（2026-10-29 抓包实测） */
        const val EXPIRED_AT = 1_793_199_938_000L

        /** 证书刚过期约一分钟：本次修复要覆盖的真实时刻 */
        const val NOW = 1_793_200_000_000L

        const val PORTAL = "https://zhxy.gxgcedu.com/login"
    }

    @Test
    fun `only the school portal is tolerated`() {
        assertTrue(ExpiredCertTolerance.isToleratedHost("zhxy.gxgcedu.com"))
        // 裸域不是同一台主机：宁可失败，也不把放行面扩到全校域名
        assertFalse(ExpiredCertTolerance.isToleratedHost("gxgcedu.com"))
        assertFalse(ExpiredCertTolerance.isToleratedHost("jw.example.edu.cn"))
        assertFalse(ExpiredCertTolerance.isToleratedHost(null))
        assertFalse(ExpiredCertTolerance.isToleratedHost(""))
    }

    @Test
    fun `host matching ignores case and trims`() {
        assertTrue(ExpiredCertTolerance.isToleratedHost("ZHXY.GXGCEDU.COM"))
        assertTrue(ExpiredCertTolerance.isToleratedHost("  zhxy.gxgcedu.com  "))
    }

    @Test
    fun `host comes from the url authority not the path`() {
        assertEquals("zhxy.gxgcedu.com", ExpiredCertTolerance.hostOf(PORTAL))
        assertNull(ExpiredCertTolerance.hostOf("zhxy.gxgcedu.com/login"))
        assertNull(ExpiredCertTolerance.hostOf(null))
        // 路径里出现主机名不算：只看 authority
        assertFalse(
            ExpiredCertTolerance.shouldProceedOnSslError(
                "https://jw.example.edu.cn/zhxy.gxgcedu.com",
                EXPIRED_AT,
                NOW,
            ),
        )
    }

    @Test
    fun `just expired is inside the grace window`() {
        assertTrue(ExpiredCertTolerance.withinGrace(EXPIRED_AT, NOW))
        assertTrue(ExpiredCertTolerance.withinGrace(EXPIRED_AT, EXPIRED_AT + 29 * DAY))
    }

    @Test
    fun `grace ends thirty days after expiry`() {
        assertTrue(ExpiredCertTolerance.withinGrace(EXPIRED_AT, EXPIRED_AT + 30 * DAY))
        assertFalse(ExpiredCertTolerance.withinGrace(EXPIRED_AT, EXPIRED_AT + 30 * DAY + 1))
        assertFalse(ExpiredCertTolerance.withinGrace(EXPIRED_AT, EXPIRED_AT + 365 * DAY))
    }

    /** 还没到期的证书不归"过期"管，绝不顺带放行 */
    @Test
    fun `not yet expired is never tolerated`() {
        assertFalse(ExpiredCertTolerance.withinGrace(EXPIRED_AT, EXPIRED_AT - DAY))
        assertFalse(ExpiredCertTolerance.withinGrace(EXPIRED_AT, EXPIRED_AT))
    }

    @Test
    fun `expired days rounds up`() {
        assertEquals(0, ExpiredCertTolerance.expiredDays(EXPIRED_AT, EXPIRED_AT))
        assertEquals(1, ExpiredCertTolerance.expiredDays(EXPIRED_AT, EXPIRED_AT + 1))
        assertEquals(1, ExpiredCertTolerance.expiredDays(EXPIRED_AT, EXPIRED_AT + DAY))
        assertEquals(2, ExpiredCertTolerance.expiredDays(EXPIRED_AT, EXPIRED_AT + DAY + 1))
    }

    @Test
    fun `expired chain outside the grace window is blocked`() {
        assertFalse(
            ExpiredCertTolerance.shouldProceedOnSslError(
                PORTAL,
                EXPIRED_AT,
                EXPIRED_AT + 31 * DAY,
            ),
        )
    }

    @Test
    fun `unknown expiry date is blocked`() {
        assertFalse(ExpiredCertTolerance.shouldProceedOnSslError(PORTAL, null, NOW))
    }

    @Test
    fun `a valid certificate never takes the tolerance path`() {
        assertFalse(ExpiredCertTolerance.shouldProceedOnSslError(PORTAL, NOW + 30 * DAY, NOW))
    }

    @Test
    fun `notice names the school and says it was let through`() {
        val notice = ExpiredCertTolerance.sslNotice(EXPIRED_AT, NOW)
        assertTrue(notice.contains("过期"))
        assertTrue(notice.contains("已放行"))
        assertTrue(notice.contains("zhxy.gxgcedu.com"))
    }

    /** 证书主体名必须是这台主机：链上别的证书、别的学校都不算 */
    @Test
    fun `san matching accepts the exact dns name`() {
        assertTrue(ExpiredCertTolerance.sanCoversHost(listOf(listOf(2, "zhxy.gxgcedu.com")), "zhxy.gxgcedu.com"))
        assertTrue(
            ExpiredCertTolerance.sanCoversHost(
                listOf(listOf(2, "other.example.cn"), listOf(2, "zhxy.gxgcedu.com")),
                "zhxy.gxgcedu.com",
            ),
        )
    }

    @Test
    fun `san matching accepts a single label wildcard`() {
        assertTrue(ExpiredCertTolerance.sanCoversHost(listOf(listOf(2, "*.gxgcedu.com")), "zhxy.gxgcedu.com"))
        assertFalse(
            ExpiredCertTolerance.sanCoversHost(
                listOf(listOf(2, "*.gxgcedu.com")),
                "a.zhxy.gxgcedu.com",
            ),
        )
    }

    @Test
    fun `san matching rejects ip entries and unrelated names`() {
        assertFalse(ExpiredCertTolerance.sanCoversHost(listOf(listOf(7, "1.2.3.4")), "zhxy.gxgcedu.com"))
        assertFalse(ExpiredCertTolerance.sanCoversHost(listOf(listOf(2, "other.example.cn")), "zhxy.gxgcedu.com"))
        assertFalse(ExpiredCertTolerance.sanCoversHost(null, "zhxy.gxgcedu.com"))
        assertFalse(ExpiredCertTolerance.sanCoversHost(emptyList(), "zhxy.gxgcedu.com"))
    }

    /** OkHttp 侧只认「叶子证书的 SAN 就是本校门户」这一种情形 */
    @Test
    fun `tolerated san is detected from the leaf`() {
        assertTrue(ExpiredCertTolerance.namesToleratedHost(listOf(listOf(2, "zhxy.gxgcedu.com"))))
        assertFalse(ExpiredCertTolerance.namesToleratedHost(listOf(listOf(2, "jw.example.edu.cn"))))
        assertFalse(ExpiredCertTolerance.namesToleratedHost(null))
    }

    /** OkHttp 侧判定与 WebView 侧同源：三闸门一个都不少 */
    @Test
    fun `chain failure needs san, expiry and window`() {
        val san = listOf(listOf(2, "zhxy.gxgcedu.com"))
        assertTrue(ExpiredCertTolerance.shouldProceedOnChainFailure(san, EXPIRED_AT, NOW))
        // 缺 SAN
        assertFalse(ExpiredCertTolerance.shouldProceedOnChainFailure(null, EXPIRED_AT, NOW))
        // 缺过期时刻
        assertFalse(ExpiredCertTolerance.shouldProceedOnChainFailure(san, null, NOW))
        // 出窗口
        assertFalse(
            ExpiredCertTolerance.shouldProceedOnChainFailure(san, EXPIRED_AT, EXPIRED_AT + 31 * DAY),
        )
        // 别的学校的 SAN
        assertFalse(
            ExpiredCertTolerance.shouldProceedOnChainFailure(
                listOf(listOf(2, "jw.example.edu.cn")),
                EXPIRED_AT,
                NOW,
            ),
        )
    }
}
