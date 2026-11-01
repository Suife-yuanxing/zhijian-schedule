package com.zhijian.schedule.feature.jw

import com.zhijian.schedule.core.jw.credential.JwCredentialScripts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回填闸门与脚本生成的硬约束。
 *
 * [shouldAutoFill] 是「会不会把密码送到别的站点」的唯一开关，
 * 它的每一条 false 分支都对应一种事故；脚本生成那部分钉的是「密码里的引号
 * 不能把 JS 拼坏」——这两种 bug 都不会在真机上抛异常，只会安静地做错事。
 */
class JwCredentialFillTest {
    private val fill =
        JwCredentialFill(
            origin = "https://jw.example.edu.cn",
            username = "20230001",
            password = "p@ss word\"quote\"",
        )

    @Test
    fun fillsOnSameOrigin() {
        assertTrue(shouldAutoFill("https://jw.example.edu.cn/jsxsd/login", fill))
        assertTrue(shouldAutoFill("HTTPS://JW.EXAMPLE.EDU.CN/LoginToXk", fill))
    }

    @Test
    fun refusesOtherOrigin() {
        // 换学校 / 跳统一认证 / 被重定向到钓鱼页，一律不回填
        assertFalse(shouldAutoFill("https://authserver.example.edu.cn/login", fill))
        assertFalse(shouldAutoFill("https://jw.example.edu.cn.evil.test/login", fill))
        assertFalse(shouldAutoFill("http://jw.example.edu.cn/login", fill))
    }

    @Test
    fun refusesWhenNothingSaved() {
        assertFalse(shouldAutoFill("https://jw.example.edu.cn/login", null))
    }

    @Test
    fun refusesEmptyPassword() {
        assertFalse(shouldAutoFill("https://jw.example.edu.cn/login", fill.copy(password = "")))
    }

    @Test
    fun fillScriptQuotesValues() {
        val js = JwCredentialScripts.fillJs(fill.origin, fill.username, fill.password)
        // 密码里带引号与空格：转义后必须作为**单个** JS 字面量出现
        assertTrue(js.contains(JwCredentialScripts.jsLiteral(fill.password)))
        assertTrue(js.contains(JwCredentialScripts.jsLiteral(fill.username)))
        // 原文里那对裸引号不该原样出现在脚本中，否则会提前闭合字符串、整段 JS 语法错误
        assertFalse(js.contains("\"quote\""))
    }

    @Test
    fun jsLiteralEscapesLineBreaksAndQuotes() {
        // 换行是 JS 字符串里的非法字符：必须被转义成 \n 而不是原样出现
        assertEquals("\"a\\nb\"", JwCredentialScripts.jsLiteral("a\nb"))
        assertEquals("\"say \\\"hi\\\"\"", JwCredentialScripts.jsLiteral("say \"hi\""))
        // 中文原样保留（不转成 \uXXXX），页面里能直接写字面量
        assertEquals("\"学号\"", JwCredentialScripts.jsLiteral("学号"))
    }

    @Test
    fun fillScriptOnlyTouchesVisiblePasswordInput() {
        val js = JwCredentialScripts.fillJs(fill.origin, fill.username, fill.password)
        assertTrue(js.contains("input[type=password]"))
        assertTrue(js.contains("getBoundingClientRect"))
        // 不自动提交表单：教务登录页普遍带验证码，自动提交会把验证码框变成死状态
        assertFalse(js.contains("form.submit"))
        assertFalse(js.contains(".click()"))
    }

    @Test
    fun captureScriptReportsThroughBridge() {
        val js = JwCredentialScripts.captureJs
        assertTrue(js.contains(JwCredentialScripts.BRIDGE_OBJECT + ".onCandidate"))
        assertTrue(js.contains("__zjCaptureInstalled"))
    }

    @Test
    fun loginProbeReportsLoggedIn() {
        val js = JwCredentialScripts.loginProbeJs
        assertTrue(js.contains(JwCredentialScripts.BRIDGE_OBJECT + ".onLoggedIn"))
    }

    @Test
    fun bridgeNameMatchesScript() {
        // 两处名称写死在不同文件里：改名时这条会立刻红
        assertEquals("PaperCredentialBridge", JwCredentialBridge.NAME)
    }

    @Test
    fun promptCarriesNoPasswordField() {
        // 气泡要回答的是「记的是不是我这个号」。密码摆到屏幕上没有任何收益，
        // 只是多一处会被旁人看到的可能——明文到写盘为止都不出 ViewModel
        val prompt =
            JwCredentialPrompt(
                origin = "https://jw.example.edu.cn",
                maskedUsername = "20***01",
                siteLabel = "jw.example.edu.cn",
                candidateAt = 1_700_000_000_000L,
            )
        assertFalse(prompt.toString().contains("p@ss"))
        assertTrue(prompt.toString().contains("20***01"))
    }

    @Test
    fun promptIdentityIsTheCandidateTimestamp() {
        // 两个内容完全一样、但来自两次不同登录的问句，必须能被区分开：
        // 否则「不理气泡又登了一次」就会把新密码当旧问句存下去
        val a = JwCredentialPrompt("https://x", "u", "x", candidateAt = 1L)
        val b = a.copy(candidateAt = 2L)
        assertFalse(a == b)
        assertEquals(a.copy(), a.copy())
    }
}
