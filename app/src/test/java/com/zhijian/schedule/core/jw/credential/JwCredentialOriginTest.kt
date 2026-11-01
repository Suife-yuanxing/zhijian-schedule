package com.zhijian.schedule.core.jw.credential

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
        // 统一认证与教务不是同一个 origin：这条差异是「不要把 SSO 密码存成教务密码」的依据
        assertEquals("https://jw.example.edu.cn", a)
        assertEquals("https://authserver.example.edu.cn", b)
    }
}
