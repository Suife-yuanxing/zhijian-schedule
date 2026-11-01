package com.zhijian.schedule.core.jw.credential

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** origin 归一化：凭据按 origin 存、按 origin 取，这里是「不认错自己的密码」的唯一依据 */
class JwCredentialOriginTest {
    @Test
    fun keepsSchemeHostAndPort() {
        assertEquals("https://jw.example.edu.cn", JwCredentialOrigin.of("https://jw.example.edu.cn/login?x=1"))
        assertEquals("http://10.1.1.8:8080", JwCredentialOrigin.of("http://10.1.1.8:8080/jsxsd/"))
    }

    @Test
    fun dropsPathQueryAndFragment() {
        assertEquals(
            "https://jw.example.edu.cn",
            JwCredentialOrigin.of("https://jw.example.edu.cn/a/b/c?service=http%3A%2F%2Fevil#frag"),
        )
    }

    @Test
    fun lowercasesSchemeAndHost() {
        assertEquals("https://jw.example.edu.cn", JwCredentialOrigin.of("HTTPS://JW.Example.EDU.CN/Login"))
    }

    @Test
    fun stripsUserInfo() {
        assertEquals("https://jw.example.edu.cn", JwCredentialOrigin.of("https://user:pw@jw.example.edu.cn/x"))
    }

    @Test
    fun rejectsBareHost() {
        // 用户随手输入的裸域名还没被 normalizeJwUrl 规范化，此时存凭据等于存错 key
        assertNull(JwCredentialOrigin.of("jw.example.edu.cn"))
        assertNull(JwCredentialOrigin.of(""))
        assertNull(JwCredentialOrigin.of("about:blank"))
    }

    @Test
    fun differentHostIsDifferentOrigin() {
        val a = JwCredentialOrigin.of("https://jw.example.edu.cn/login")
        val b = JwCredentialOrigin.of("https://authserver.example.edu.cn/login")
        // 统一认证与教务不是同一个 origin：这是「回填只填自己那一页」的依据
        assertEquals("https://jw.example.edu.cn", a)
        assertEquals("https://authserver.example.edu.cn", b)
    }

    // ---------- 提交页是否可信（真机 2026-11-01 东莞职院逼出来的一条） ----------

    @Test
    fun sameHostIsTrusted() {
        assertTrue(JwCredentialOrigin.isTrustedSubmit("https://jw.dgpt.edu.cn", "https://jw.dgpt.edu.cn"))
        // scheme 不同但同 host：学校 http 门户跳到 https，不该因此丢掉候选
        assertTrue(JwCredentialOrigin.isTrustedSubmit("http://jw.dgpt.edu.cn", "https://jw.dgpt.edu.cn"))
    }

    @Test
    fun siblingSubdomainUnderEduCnIsTrusted() {
        // 真机实测的那一组：配置 jw.dgpt.edu.cn，学校 302 到 authserver.dgpt.edu.cn，
        // 密码是在后者那页提交的。严格比 host 会让统一认证学校的候选全部被丢弃
        assertTrue(
            JwCredentialOrigin.isTrustedSubmit(
                "https://authserver.dgpt.edu.cn",
                "https://jw.dgpt.edu.cn",
            ),
        )
    }

    @Test
    fun differentRegistrableDomainIsRejected() {
        // 真正的跨站：注册域不同就一律不认，哪怕子域名长得像
        assertFalse(JwCredentialOrigin.isTrustedSubmit("https://jw.evil.cn", "https://jw.dgpt.edu.cn"))
        assertFalse(JwCredentialOrigin.isTrustedSubmit("https://dgpt.edu.cn.evil.com", "https://jw.dgpt.edu.cn"))
        assertFalse(JwCredentialOrigin.isTrustedSubmit("https://evil.com", "https://dgpt.edu.cn"))
    }

    @Test
    fun barePortHostComparesExactly() {
        // 纯 IP 没有注册域概念：只认完全相等
        assertTrue(JwCredentialOrigin.isTrustedSubmit("http://10.1.1.8:8080", "http://10.1.1.8:8080"))
        assertFalse(JwCredentialOrigin.isTrustedSubmit("http://10.1.1.9:8080", "http://10.1.1.8:8080"))
    }

    @Test
    fun malformedOriginIsRejected() {
        assertFalse(JwCredentialOrigin.isTrustedSubmit("", "https://jw.dgpt.edu.cn"))
        assertFalse(JwCredentialOrigin.isTrustedSubmit("jw.dgpt.edu.cn", "https://jw.dgpt.edu.cn"))
        assertFalse(JwCredentialOrigin.isTrustedSubmit("about:blank", "https://jw.dgpt.edu.cn"))
    }

    @Test
    fun comCnKeepsThreeLabels() {
        // xxx.com.cn 也是「后三段才是注册域」，与 edu.cn 同理
        assertTrue(JwCredentialOrigin.isTrustedSubmit("https://a.school.com.cn", "https://b.school.com.cn"))
        assertFalse(JwCredentialOrigin.isTrustedSubmit("https://a.other.com.cn", "https://b.school.com.cn"))
    }
}
