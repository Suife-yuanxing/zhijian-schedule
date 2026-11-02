package com.zhijian.schedule.core.jw

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 回归护栏：MockWebServer 的 hostName 由机器 hostname 决定，本机与 CI 不同。
 *
 * 症状（CI run 41398446196）：4 个成绩全链路用例 expected:<3> but was:<0>，
 * 因为抓取入口先过 [JwUrlGuard]，而守卫按 hostname 判定 —— 本机 hostname 恰好解析成
 * 公网域名（www.bandisoft.com）于是通过，CI runner 上不是，于是被拦成空列表。
 *
 * 这个用例把「不同 hostname 下守卫的行为」钉死，避免同类问题再靠运气通过。
 */
class JwUrlGuardHostnameIndependenceTest {
    @After
    fun clearAllowance() {
        JwUrlGuard.testAllowedHosts.clear()
    }

    @Test
    fun `loopback is rejected without explicit allowance`() {
        JwUrlGuard.testAllowedHosts.clear()
        assertNull(JwUrlGuard.safeHttpUrl("http://127.0.0.1:8080/login"))
        assertNull(JwUrlGuard.safeHttpUrl("http://localhost:8080/login"))
    }

    @Test
    fun `loopback passes once the test explicitly allows it`() {
        JwUrlGuard.testAllowedHosts += setOf("127.0.0.1", "localhost", "::1")
        assertNotNull(JwUrlGuard.safeHttpUrl("http://127.0.0.1:8080/login"))
        assertNotNull(JwUrlGuard.safeHttpUrl("http://localhost:8080/login"))
    }

    @Test
    fun `allowance does not leak to other hosts`() {
        JwUrlGuard.testAllowedHosts += setOf("127.0.0.1")
        // 私有网段仍被拦（放行集只针对显式列出的主机名）
        assertNull(JwUrlGuard.safeHttpUrl("http://192.168.1.1/jwglxt/"))
        assertNull(JwUrlGuard.safeHttpUrl("http://10.0.0.5/jsxsd/"))
        // 公网照常通过
        assertEquals(
            "https://zhxy.gxgcedu.com/timetable",
            JwUrlGuard.safeHttpUrl("https://zhxy.gxgcedu.com/timetable")?.toString(),
        )
    }
}
