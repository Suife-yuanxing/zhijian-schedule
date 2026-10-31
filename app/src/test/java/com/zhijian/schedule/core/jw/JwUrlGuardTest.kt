package com.zhijian.schedule.core.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 教务请求 URL 安全闸门：只放行 http(s) 公网主机（SSRF 基本面） */
class JwUrlGuardTest {
    @Test
    fun publicHostsPass() {
        assertEquals(
            "https://zhxy.gxgcedu.com/timetable",
            JwUrlGuard.safeHttpUrl("https://zhxy.gxgcedu.com/timetable")?.toString(),
        )
        assertEquals(
            "http://jw.example.edu.cn/jsxsd/",
            JwUrlGuard.safeHttpUrl("http://jw.example.edu.cn/jsxsd/")?.toString(),
        )
        // 公网 IPv4（路径原样保留）
        assertEquals(
            "https://1.2.3.4/jwglxt/",
            JwUrlGuard.safeHttpUrl("https://1.2.3.4/jwglxt/")?.toString(),
        )
        // 全球单播 IPv6（2000::/3）
        assertEquals(
            "https://[2606:4700:4700::1111]/",
            JwUrlGuard.safeHttpUrl("https://[2606:4700:4700::1111]/")?.toString(),
        )
    }

    @Test
    fun nonHttpSchemesAreRejected() {
        assertNull(JwUrlGuard.safeHttpUrl("ftp://zhxy.gxgcedu.com/"))
        assertNull(JwUrlGuard.safeHttpUrl("file:///etc/passwd"))
    }

    @Test
    fun loopbackAndLocalhostAreRejected() {
        assertNull(JwUrlGuard.safeHttpUrl("http://localhost:8080/login"))
        assertNull(JwUrlGuard.safeHttpUrl("http://sub.localhost/"))
        assertNull(JwUrlGuard.safeHttpUrl("http://127.0.0.1:8080/"))
        assertNull(JwUrlGuard.safeHttpUrl("http://127.255.1.2/"))
        assertNull(JwUrlGuard.safeHttpUrl("http://[::1]/"))
        assertNull(JwUrlGuard.safeHttpUrl("http://0.0.0.0/"))
    }

    @Test
    fun privateAndReservedRangesAreRejected() {
        assertNull(JwUrlGuard.safeHttpUrl("http://10.0.0.5/jsxsd/"))
        assertNull(JwUrlGuard.safeHttpUrl("http://172.16.1.1/"))
        assertNull(JwUrlGuard.safeHttpUrl("http://172.31.255.255/"))
        assertNull(JwUrlGuard.safeHttpUrl("http://192.168.1.1/"))
        assertNull(JwUrlGuard.safeHttpUrl("http://169.254.1.1/"))
        assertNull(JwUrlGuard.safeHttpUrl("http://100.64.0.1/"))
        assertNull(JwUrlGuard.safeHttpUrl("http://224.0.0.1/"))
        assertNull(JwUrlGuard.safeHttpUrl("http://250.1.2.3/"))
        // 环回/唯一本地 IPv6
        assertNull(JwUrlGuard.safeHttpUrl("http://[fe80::1]/"))
        assertNull(JwUrlGuard.safeHttpUrl("http://[fc00::1]/"))
    }

    @Test
    fun boundaryAddressesStillPass() {
        // 172.15 / 172.32 在私网段外；100.63 / 100.128 在 CGNAT 外
        assertEquals("http://172.15.0.1/", JwUrlGuard.safeHttpUrl("http://172.15.0.1/")?.toString())
        assertEquals("http://172.32.0.1/", JwUrlGuard.safeHttpUrl("http://172.32.0.1/")?.toString())
        assertEquals("http://100.63.0.1/", JwUrlGuard.safeHttpUrl("http://100.63.0.1/")?.toString())
        assertEquals("http://100.128.0.1/", JwUrlGuard.safeHttpUrl("http://100.128.0.1/")?.toString())
    }

    @Test
    fun garbageIsRejected() {
        assertNull(JwUrlGuard.safeHttpUrl(""))
        assertNull(JwUrlGuard.safeHttpUrl("not a url"))
    }
}
