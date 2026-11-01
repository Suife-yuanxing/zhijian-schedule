package com.zhijian.schedule.feature.jw

import androidx.compose.ui.graphics.Color
import com.zhijian.schedule.core.theme.ThemePresets
import com.zhijian.schedule.core.ui.island.Contrast
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「求解用的底」必须就是「绘制时的底」。
 *
 * ## 为什么值得单独一组
 * 这一页所有次要文字都由同一个求解器按 WCAG 解到 4.5。但求解时**声明压在哪**
 * 与**实际画在哪**是两件事，工程里最常见的可读性缺陷就出在这两者不一致：
 * 求解器说 4.5 达标，用户看到的却是 3.7。它不抛异常、不在截图里露馅，
 * 只让某些主题下的提示读不出来。
 *
 * 改造前实测三处：
 * - 「记住这次登录？」气泡：按 `bg` 解，画在 `surface` 上（金属亮档 5.19 → 3.73）；
 * - 地址输入条占位文字：同样的错；
 * - 「网页加载失败」浮层：按 `bg` 解，画在 `bg @ 90%` 与网页的合成色上
 *   （纸本暖白亮档 4.55 → 最坏 3.63）。
 *
 * ## 这组测的是「两个底真的不一样」
 * 直接断言「按正确的底求解 → 达标」是没有信息量的：那是求解器自己的性质，
 * 换个参数照样绿。有意义的是反过来——**按错误的底求解，然后在真实的底上量**，
 * 必须量出低于门槛的结果。那才证明"当初那处 bug 是真的、修它是有用的"，
 * 也才挡得住将来有人把三个调用点重新统一成 `jwMuted(colors)`。
 */
class JwSurfaceContrastTest {
    /**
     * 气泡 / 输入条：文字压在**不透明卡面**上。
     *
     * 金属档最容易出事：它的卡面不是配色表里的 surface，而是着色器算出来的亮灰
     * （亮档实测 #C3CAD5 附近）。按页面底色解出来的颜色压上去会掉一档。
     */
    @Test
    fun solvingAgainstPageBgWouldFailOnTheCardSurface() {
        var failures = 0
        for (preset in ThemePresets.all) {
            for (isDark in listOf(false, true)) {
                val colors = if (isDark) preset.dark else preset.light
                // 错误的底：按页面底色求解（改造前的写法）
                val wrong = Contrast.mixUntil(colors.textMuted, colors.text, colors.bg, MIN_BODY)
                // 真实的底：这张卡自己的面
                val actual = cardFace(colors, isDark, preset.metal != null)
                if (Contrast.ratio(wrong, actual) < MIN_BODY) failures++
            }
        }
        assertTrue(
            "按页面底色求解、再压到卡面上，必须至少有一组预设掉到 $MIN_BODY 以下——" +
                "否则说明这两个底已经相等，本文件对应的修复就失去意义了（实测 0 组失败）",
            failures > 0,
        )
    }

    /** 按**真实的底**求解时，每一组都必须达标——这才是调用点该做的事。 */
    @Test
    fun solvingAgainstTheCardSurfaceMeetsAaOnEveryPreset() {
        for (preset in ThemePresets.all) {
            for (isDark in listOf(false, true)) {
                val colors = if (isDark) preset.dark else preset.light
                val actual = cardFace(colors, isDark, preset.metal != null)
                val solved = Contrast.mixUntil(colors.textMuted, colors.text, actual, MIN_BODY)
                val ratio = Contrast.ratio(solved, actual)
                val label = preset.id + if (isDark) "/dark" else "/light"
                assertTrue("$label 的卡面次要文字只有 $ratio:1，门槛 $MIN_BODY", ratio >= MIN_BODY)
            }
        }
    }

    /**
     * 「网页加载失败」浮层：遮罩半透明，底下是**任意网页**。
     *
     * 判据取最坏情况：浅色主题算"底下是纯黑网页"，深色主题算"底下是纯白网页"
     * ——遮罩合成之后分别是各自最暗 / 最亮的底，对比都是最低的那一端。
     */
    @Test
    fun scrimIsSolvedAgainstItsWorstCaseComposite() {
        var failures = 0
        for (preset in ThemePresets.all) {
            for (isDark in listOf(false, true)) {
                val colors = if (isDark) preset.dark else preset.light
                val wrong = Contrast.mixUntil(colors.textMuted, colors.text, colors.bg, MIN_BODY)
                val worst = worstCaseScrim(colors.bg, isDark)
                if (Contrast.ratio(wrong, worst) < MIN_BODY) failures++
            }
        }
        assertTrue(
            "按页面底色求解、再压到半透明遮罩上，必须至少有一组掉到 $MIN_BODY 以下（实测 0 组失败）",
            failures > 0,
        )
    }

    /** 用最坏情况合成色求解时，每一组都必须达标。 */
    @Test
    fun scrimSolvedAgainstWorstCaseMeetsAaOnEveryPreset() {
        for (preset in ThemePresets.all) {
            for (isDark in listOf(false, true)) {
                val colors = if (isDark) preset.dark else preset.light
                val worst = worstCaseScrim(colors.bg, isDark)
                val solved = Contrast.mixUntil(colors.textMuted, colors.text, worst, MIN_BODY)
                val ratio = Contrast.ratio(solved, worst)
                val label = preset.id + if (isDark) "/dark" else "/light"
                assertTrue("$label 的浮层说明只有 $ratio:1，门槛 $MIN_BODY", ratio >= MIN_BODY)
            }
        }
    }

    // ---------- 辅助 ----------

    /**
     * 卡面的实际底色。
     *
     * 金属档取近似取样色：金属面由着色器算出，配色表里读不到。
     * 改着色器时这里要跟着动——它是本文件唯一一个"抄"来的常量。
     */
    private fun cardFace(
        colors: com.zhijian.schedule.core.theme.CourseColors,
        isDark: Boolean,
        metal: Boolean,
    ): Color =
        if (metal) {
            // 与 IslandTokens.pillUnder 的取样同源
            if (isDark) Color(0xFF22262F) else Color(0xFFC3CAD5)
        } else {
            colors.surface
        }

    /** 遮罩压在最坏网页上的合成色：浅色主题算纯黑网页，深色主题算纯白网页。 */
    private fun worstCaseScrim(
        bg: Color,
        isDark: Boolean,
    ): Color = Contrast.composite(bg, if (isDark) Color.White else Color.Black, PAGE_ERROR_SCRIM)

    private companion object {
        /** 与 JwImportStatusViews 的 MIN_BODY_CONTRAST 同值（私有常量，此处复述一遍） */
        const val MIN_BODY = 4.5

        /** 与 JwImportStatusViews 的 PAGE_ERROR_SCRIM_ALPHA 同值 */
        const val PAGE_ERROR_SCRIM = 0.9f
    }
}
