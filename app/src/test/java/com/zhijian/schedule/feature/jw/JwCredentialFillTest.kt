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

    /**
     * 浮岛提示的文案：说清「填的是哪个号」，**绝不**暗示密码已经存好了。
     *
     * 这条提示只活五秒、不会再来一次（见 IslandHint 的进度条），所以它说错话时
     * 用户没有第二次机会澄清。最容易说错的一句是「已为你保存密码」——
     * 回填与保存是两件独立的事，保存要用户按气泡里的「记住」才发生。
     */
    @Test
    fun fillHintNamesTheAccountWithoutClaimingPasswordIsSaved() {
        val text = credentialFillHintText("202530002131")
        assertTrue("提示里必须出现账号，否则用户认不出填的是谁", text.contains("202530002131"))
        assertTrue("提示必须以「已填入」开头，说的是回填发生了", text.startsWith("已填入"))
        // 「保存的账号」是定语（填的是那条已保存的记录），合规；
        // 真正危险的是把它写成谓语——「已保存」「已为你保存」会读成密码已经存好。
        assertFalse("回填不是保存，谓语位置不能出现「已保存」", text.contains("已保存"))
        assertFalse("不能替用户宣告保存完成", text.contains("为你保存"))
        assertFalse("密码不该出现在任何 UI 文案里", text.contains("密码"))
    }

    /**
     * 浮岛提示的**重置令牌**必须能区分「又填了一次」与「还挂着上一轮」。
     *
     * 计时效应挂在 filledAt 上（见 IslandHint）：两个令牌相等时进度条不会重走。
     * 若这里退化成布尔值或常量，用户清掉 cookie 重开登录页、我们真的又填了一次，
     * 提示却不会重新弹出——功能等于对第二次之后的所有回填失效。
     */
    @Test
    fun fillNoticeTimestampChangesPerFill() {
        val first = JwCredentialFillNotice(username = "20230001", filledAt = 1L)
        val second = first.copy(filledAt = 2L)
        assertFalse("两次填入必须得到两个不同的令牌，进度条才会重走", first == second)
        assertEquals("账号相同不构成不同的一次填入", second.username, first.username)
    }

    /** 提示对象不带密码字段：它要回答的只是「填的是哪个号」 */
    @Test
    fun fillNoticeCarriesNoPassword() {
        val notice = JwCredentialFillNotice(username = "20230001", filledAt = 1L)
        assertFalse(notice.toString().contains("p@ss"))
        assertTrue(notice.toString().contains("20230001"))
    }

    /**
     * 桥的构造参数名不能与 @JavascriptInterface 的方法名相同。
     *
     * 这条断言对应一次真机事故（PKG110 / 2026-11-01）：两者同名时，
     * `fun onLoggedIn() { onLoggedIn() }` 里的调用被解析成它自己，
     * 页面每回调一次就 StackOverflowError（连续 297 帧同一行），
     * 而页面侧只看到「Java exception」，气泡永远不出现 —— 不崩溃、不报错，
     * 功能从头到尾没工作过。
     *
     * 用反射读构造参数的**名字**，是因为这个 bug 编译期完全合法、运行期才炸，
     * 常规单测碰不到；而只要名字不同，这个坑就不可能再踩。
     */
    @Test
    fun bridgeCallbacksAreNotNamedLikeJsMethods() {
        val jsMethods = setOf("onCandidate", "onLoggedIn")
        val paramNames =
            JwCredentialBridge::class.java.declaredConstructors
                .flatMap { it.parameters.map { p -> p.name } }
                .toSet()
        assertTrue(
            "构造参数 $paramNames 与 JS 方法名 $jsMethods 冲突，会无限递归",
            paramNames.intersect(jsMethods).isEmpty(),
        )
    }
}
