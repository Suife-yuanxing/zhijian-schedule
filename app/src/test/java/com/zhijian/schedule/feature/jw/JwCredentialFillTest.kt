package com.zhijian.schedule.feature.jw

import com.zhijian.schedule.core.jw.credential.JwCredentialScripts
import com.zhijian.schedule.core.ui.island.IslandHintTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
     * 成功提示的文案：说清「填的是哪个号」，**绝不**暗示密码已经存好了。
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
     * 失败提示必须说清**下一步做什么**，不能只宣布失败。
     *
     * 这条提示只活五秒、还带着震动把人叫过来——叫过来之后如果只说「失败了」，
     * 用户除了困惑什么也得不到。两种失败各自要对上一种可执行的行动。
     */
    @Test
    fun fillFailedTextTellsWhatToDoNext() {
        val noInput = credentialFillFailedText(JwCredentialFillFailure.NoInput)
        val rejected = credentialFillFailedText(JwCredentialFillFailure.WriteRejected)
        assertTrue("必须点明没有填入", noInput.startsWith("没能自动填入"))
        assertTrue("找不到登录框时要给出可执行的下一步", noInput.contains("手动输入") || noInput.contains("重新打开"))
        assertTrue("写入被拒时必须明确要求手输，别让人白等", rejected.contains("手动输入"))
        assertNotEquals("两种原因的文案不能一样，否则等于没分", noInput, rejected)
    }

    /** 保存失败的三种原因各自给一条，且都不含密码 */
    @Test
    fun saveFailedTextCoversEveryReason() {
        val all = JwCredentialSaveFailure.entries.map { credentialSaveFailedText(it) }
        assertEquals("每种原因都必须有文案", JwCredentialSaveFailure.entries.size, all.size)
        assertTrue("文案不能重复，否则用户分不清发生了什么", all.toSet().size == all.size)
        all.forEach { text ->
            assertTrue("每条都要以「没能记住」开头，与成功提示共用同一句式", text.startsWith("没能记住"))
            assertFalse("密码不该出现在任何 UI 文案里", text.contains("密码"))
        }
    }

    /**
     * 浮岛提示的**重置令牌**必须能区分「又发生了一次」与「还挂着上一轮」。
     *
     * 计时效应挂在 at 上（见 IslandHint）：两个令牌相等时进度条不会重走。
     * 若这里退化成布尔值或常量，用户清掉 cookie 重开登录页、我们真的又填了一次，
     * 提示却不会重新弹出——功能等于对第二次之后的所有回填失效。
     */
    @Test
    fun fillNoticeTimestampChangesPerEvent() {
        val first = JwCredentialFillNotice(IslandHintTone.Done, "已填入", at = 1L)
        val second = first.copy(at = 2L)
        assertFalse("两次事件必须得到两个不同的令牌，进度条才会重走", first == second)
    }

    /**
     * 语气只有成功 / 失败两档，且失败档**不**等于成功档。
     *
     * 这条看着像废话，钉的是「两者共用同一套骨架、只差语气」这个约定：
     * 若将来有人给失败单独造一个组件，这两档就不会再被放在一起比较，
     * 用户也要重新学一遍这条提示会自己消失吗。
     */
    @Test
    fun noticeCarriesToneDistinctFromSuccess() {
        val ok = JwCredentialFillNotice(IslandHintTone.Done, "已填入", at = 1L)
        val fail = ok.copy(tone = IslandHintTone.Failed, message = "没能自动填入")
        assertNotEquals("失败必须与成功可区分", ok.tone, fail.tone)
        assertFalse("提示对象不带密码字段", fail.toString().contains("p@ss"))
    }

    /**
     * 探测不到密码框**不能**一次就报失败。
     *
     * 这条防的是一次真机实测过的误报：onPageFinished 是「主文档加载完」，
     * 统一认证与多数教务的登录表单是之后由 JS 拼出来的（真机 300~900ms）。
     * 一次探不到就弹红叉，用户会在看到「没能填入」之后不到一秒，
     * 又眼睁睁看着账密被填进去——那比不提示更让人不信任这个功能。
     *
     * 断言写的是源码里的常量关系而不是行为（没有 WebView 可跑）：
     * 次数必须大于 1，且重试间隔要够覆盖上面那段延迟。
     */
    @Test
    fun probeRetriesBeforeDeclaringFailure() {
        val src = readSource("app/src/main/java/com/zhijian/schedule/feature/jw/JwImportWebView.kt")
        assertTrue("探测必须允许重试，否则 JS 渲染的登录表单会被误判为失败", src.contains("PROBE_MAX_ATTEMPTS"))
        assertTrue("重试次数必须大于 1", src.contains("PROBE_MAX_ATTEMPTS = 3"))
        assertTrue("必须有重试间隔", src.contains("PROBE_RETRY_DELAY_MS"))
        // 3 次 x 700ms ≈ 1.4s：覆盖真机上测到的 300~900ms 渲染延迟
        assertTrue(
            "重试窗口不足以覆盖 JS 渲染延迟",
            src.contains("PROBE_RETRY_DELAY_MS = 700L"),
        )
    }

    /**
     * 失败判定必须挂在「停在登录页」这个前提上。
     *
     * 课表页、门户首页本来就没有密码框。如果不管在哪一页都报 NoInput，
     * 用户每进一次课表页就会被红叉加震动训一次——而那里的账密早就填好了。
     */
    @Test
    fun noInputFailureOnlyOnLoginPage() {
        val src = readSource("app/src/main/java/com/zhijian/schedule/feature/jw/JwImportWebView.kt")
        assertTrue(
            "NoInput 必须先确认还停在登录页",
            src.contains("if (isJwLoginPage(url)) onFillFailed(JwCredentialFillFailure.NoInput)"),
        )
    }

    /** 读取仓库内文本，供"源码里必须存在的约束"这类断言使用 */
    private fun readSource(relativePath: String): String {
        val f = java.io.File(relativePath)
        // 测试工作目录是模块根目录（app/），仓库根还要往上一级
        val candidates = listOf(f, java.io.File("..", relativePath))
        val hit = candidates.firstOrNull { it.isFile } ?: return ""
        return hit.readText()
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

    /**
     * 捕获脚本与登录探针必须**无条件**注入，不能被「有没有存过凭据」挡在前面。
     *
     * 这条钉的是 97c68c9 引入、在 main 上活了一天多的自举死锁：
     * `if (pending == null || !shouldAutoFill(url, pending)) return` 写在两行注入之前，
     * 于是新装用户（fill 恒为 null）永远装不上捕获 → 候选永远为空 →
     * 登录成功也弹不出气泡 → 存不下凭据。不报错、不崩溃、界面毫无反应。
     *
     * 断言用**位置**而不是"包含"：包含关系在 bug 版本里同样成立（两行都还在文件里，
     * 只是永远执行不到），只有"出现在第一个 return 之前"才能区分开。
     * 这正是上一版 20 条单测全部通过却没拦住这个 bug 的原因——它们钉的是脚本内容，
     * 没有一条钉调用点。
     */
    @Test
    fun captureScriptsAreInjectedBeforeAnyEarlyReturn() {
        val src = readSource("app/src/main/java/com/zhijian/schedule/feature/jw/JwImportWebView.kt")
        assertTrue("读不到 JwImportWebView.kt，源码断言失效", src.isNotEmpty())

        val bodyStart = src.indexOf("private fun applyCredentialScripts(")
        assertTrue("找不到 applyCredentialScripts，函数被改名或移动了", bodyStart >= 0)
        // 函数体到下一个顶层注释块（PROBE_RETRY_DELAY_MS 的 KDoc）为止
        val bodyEnd = src.indexOf("PROBE_RETRY_DELAY_MS", bodyStart)
        assertTrue("找不到 applyCredentialScripts 的结束锚点", bodyEnd > bodyStart)
        val body = src.substring(bodyStart, bodyEnd)

        // 锚点是「凭据闸门」而不是"第一个 return"：函数开头的 url 空 / origin 解析失败
        // 是正当的守卫，捕获注入本来就该在它们之后。真正不能越过的是这一道——
        // 它把「有没有存过凭据」变成了捕获的前提。
        val gateAt = body.indexOf("val pending = fill")
        assertTrue("找不到凭据闸门（val pending = fill），锚点失效", gateAt >= 0)
        val captureAt = body.indexOf("JwCredentialScripts.captureJs")
        val probeAt = body.indexOf("JwCredentialScripts.loginProbeJs")

        assertTrue("捕获脚本根本没被注入", captureAt >= 0)
        assertTrue("登录探针根本没被注入", probeAt >= 0)
        assertTrue(
            "captureJs 注入点位于凭据闸门之后，新装用户永远装不上捕获（自举死锁）",
            captureAt < gateAt,
        )
        assertTrue(
            "loginProbeJs 注入点位于凭据闸门之后，登录成功判定永远不触发",
            probeAt < gateAt,
        )
    }

    /**
     * 回填的闸门不能被顺手放宽：捕获放开之后，回填仍必须严格同 origin。
     *
     * 两条约束在同一个函数里，改一处很容易顺手改另一处。捕获放开的是"要不要听页面说话"，
     * 回填放开的是"要不要把明文密码交给这一页"——后者永远不能松。
     */
    @Test
    fun autofillStillGatedBehindStrictOrigin() {
        val src = readSource("app/src/main/java/com/zhijian/schedule/feature/jw/JwImportWebView.kt")
        val bodyStart = src.indexOf("private fun applyCredentialScripts(")
        val bodyEnd = src.indexOf("PROBE_RETRY_DELAY_MS", bodyStart)
        val body = src.substring(bodyStart, bodyEnd)
        val fillGate = body.indexOf("shouldAutoFill(url, pending)")
        assertTrue("回填前的 shouldAutoFill 闸门不见了", fillGate >= 0)
        assertTrue(
            "回填闸门必须位于 captureJs 注入之后（先听页面，再决定要不要填）",
            fillGate > body.indexOf("JwCredentialScripts.captureJs"),
        )
    }
}
