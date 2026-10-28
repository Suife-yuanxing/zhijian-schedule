package com.zhijian.schedule.feature.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阶段判定与文案的单测。
 *
 * 这几条断言钉住的是**改造前后必须完全一致的口径**：进度条把原来三行文字压成
 * 「进度点 + 两行提示」，一旦判定顺序或文案被改动，用户看到的「下一步做什么」
 * 就会悄悄换成另一个说法——而这类回归在真机上很难发现（界面依旧能跑）。
 *
 * 纯字符串 / 枚举逻辑，不碰 Compose、不碰 WebView，走本机 JVM 单测。
 */
class JwImportStageTest {
    /** 已进入教务系统：优先级最高，必须先于登录页判定 */
    @Test
    fun `logged in page wins over login page`() {
        val stage =
            jwStageOf(
                onLoginPage = true,
                onAuthserverPage = false,
                onLoggedInPage = true,
            )
        assertEquals(JwStage.FETCH, stage)
        assertEquals(2, stage.stepIndex)
    }

    /** 统一认证页排在登录页之前：sso 页 URL 里通常也带 login 字样 */
    @Test
    fun `authserver wins over login page`() {
        val stage =
            jwStageOf(
                onLoginPage = true,
                onAuthserverPage = true,
                onLoggedInPage = false,
            )
        assertEquals(JwStage.SSO, stage)
        assertEquals(0, stage.stepIndex)
    }

    @Test
    fun `login page is the first step`() {
        val stage =
            jwStageOf(
                onLoginPage = true,
                onAuthserverPage = false,
                onLoggedInPage = false,
            )
        assertEquals(JwStage.LOGIN, stage)
        assertEquals(0, stage.stepIndex)
    }

    /** 登录页判定为假、也没进主框架：停在「还没到课表页」，亮第二步 */
    @Test
    fun `unknown page points at timetable step`() {
        val stage =
            jwStageOf(
                onLoginPage = false,
                onAuthserverPage = false,
                onLoggedInPage = false,
            )
        assertEquals(JwStage.TIMETABLE, stage)
        assertEquals(1, stage.stepIndex)
    }

    /** 进度条的三个标签固定两字：长菜单名只出现在状态行里，不撑宽进度条 */
    @Test
    fun `step labels stay two characters`() {
        assertEquals(3, JwStepLabels.size)
        assertTrue(JwStepLabels.all { it.length == 2 })
    }

    /** 状态行永远是「先做什么」，条件句不能与主句重复 */
    @Test
    fun `status line always has a primary sentence`() {
        JwStage.entries.forEach { stage ->
            val line = jwStatusText(stage, "学生课表")
            assertTrue("stage=" + stage, line.primary.isNotBlank())
            assertTrue("stage=" + stage, line.secondary != line.primary)
        }
    }

    /** 课表页名来自适配器：强智是「学生课表」，未识别时退回通用叫法 */
    @Test
    fun `timetable page name is used in the hint`() {
        val custom = jwStatusText(JwStage.TIMETABLE, "本学期课表")
        assertTrue(custom.primary.contains("本学期课表"))
        assertTrue(custom.secondary.contains("抓取课表"))
    }

    /** 已就绪那一档要告诉用户动作在底部，因为主行动在底部工具条 */
    @Test
    fun `fetch stage points to the bottom action`() {
        val line = jwStatusText(JwStage.FETCH, "学生课表")
        assertTrue(line.secondary.contains("抓取课表"))
    }

    /** 未识别教务时不再罗列适配器清单，只说明「仍可直接抓取」 */
    @Test
    fun `adapter label is friendly when unknown`() {
        assertEquals("未识别教务类型，仍可登录后直接抓取", adapterLabel(null))
        assertEquals("识别为 强智 jsxsd", adapterLabel("强智 jsxsd"))
    }
}
