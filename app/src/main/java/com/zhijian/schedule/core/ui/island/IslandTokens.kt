package com.zhijian.schedule.core.ui.island

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.zhijian.schedule.core.theme.CourseColors
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.theme.LocalIsDark
import com.zhijian.schedule.core.theme.MetalSpec

/**
 * 浮岛专用配色 Token。
 *
 * 与 [CourseColors] 的关系：浮岛是**悬浮层**，压的底比正文更亮（surface / 玻璃底层），
 * 直接借用页面 token 会掉档（实测 11 组里 7 组低于 WCAG AA）。所以这里独立求解，
 * 只作用于浮岛，不污染正文。
 */
data class IslandPalette(
    /** 未选中图标与标签：由 textMuted 向 text 混到刚好 ≥4.5 为止 */
    val muted: Color,
    /** 选中图标与标签：玻璃主题取 onAccent，其余取 text */
    val selected: Color,
    /** 选中胶囊 / 液滴的底色（已按对比度压深或提亮） */
    val chip: Color,
    /** 选中胶囊 / 液滴的填充浓度 */
    val chipAlpha: Float,
    /** 求解时假定的底层（用于单测复算） */
    val under: Color,
    /**
     * 胶囊描边浓度：淡胶囊与浮岛底同色系时边界会消失，靠这条 hairline 定义轮廓。
     * 玻璃档本体已由折射彩边定义边界，不需要再描（返回 0）。
     */
    val chipEdgeAlpha: Float,
)

object IslandTokens {
    /**
     * 非玻璃主题的选中胶囊填充浓度。
     *
     * 为什么不提到「一眼可见」的 0.39~0.49：M3 官方指示器（#E8DEF8 压 #F3EDF7）
     * 与容器的对比也只有 1.13:1，说明淡色指示器是业界共识——它是背景，不是主体。
     * 把浓度提上去会把每屏常驻的导航件变成色块，抢内容。可见度改由 [chipEdgeAlpha]
     * 的描边解决，而不是靠填充加压。
     */
    const val CHIP_ALPHA = 0.13f

    /** 金属档选中胶囊的不透明度：金属珠是不透的（不透明度只影响与片底的融合边缘） */
    const val METAL_CHIP_ALPHA = 1f

    /** 非玻璃主题的胶囊描边浓度：够定义边界、又不会读成「框住了一块色」 */
    const val CHIP_EDGE_ALPHA = 0.42f

    /**
     * 填充的纵向渐变系数：上缘更透、下缘更实（光从上面来）。
     *
     * 取 0.77 / 1.23，中点正好回到 1.0 —— 文字与图标压在胶囊中段，实测浓度仍是
     * [CHIP_ALPHA]，所以 `IslandContrastTest` 里按单一浓度复算的对比度结论继续成立。
     * 幅度刻意小：这是给淡色指示器加一层体积，不是把它变成色块。
     */
    const val CHIP_FILL_TOP = 0.77f
    const val CHIP_FILL_BOTTOM = 1.23f

    /**
     * 描边的纵向渐变系数：上缘受光更强、下缘收柔。
     *
     * 均值 ≈ 1.0，守住 [chipEdgeAlpha]「刚好定义边界」的浓度前提；只是把均匀的一圈
     * 拆成「顶亮底柔」，读起来是一颗有厚度的水滴，而不是贴上去的一个描边框。
     */
    const val CHIP_EDGE_TOP = 1.24f
    const val CHIP_EDGE_BOTTOM = 0.71f

    /**
     * 果冻拉伸：宽度的最大增幅（相对胶囊自身，不是绝对 dp）。
     *
     * 为什么是比例：胶囊宽度随 tab 数与屏宽变，固定 dp 会让三 tab 手机上拉过头、
     * 平板上几乎看不出。+16% 时 88dp 胶囊长到 102dp，还压得住一格。
     */
    const val PILL_GROW = 0.16f

    /** 果冻拉伸：高度的最大压缩。宽了就矮，太多会读成扁条，太少看不出被拉长 */
    const val PILL_SQUASH = 0.11f

    /** 拉伸量分给「前缘」的比例：鼻尖先出去、尾巴拖在后面，才读作被拽着走 */
    const val PILL_LEAD = 0.62f

    /** 正文与图形共用的门槛：11sp 标签按 WCAG AA 正文算 */
    const val MIN_TEXT_CONTRAST = 4.5

    /** 图标在收敛态的最小门槛（图形 3.0），单测用来兜住「收起来也别看不见」 */
    const val MIN_ICON_CONTRAST = 3.0

    /**
     * 进度条**轨道**（没走到的部分）与浮岛面的最小对比度。
     *
     * ## 为什么轨道也要设门槛
     * 进度条说的是「还剩多少」，而这个「还剩」只能由**已走 / 未走两段的差**表达。
     * 轨道看不见时，条就退化成一根自己随机变长的色块——真机实测 12 组预设里，
     * 轨道压在胶囊面上只有 1.22~1.55:1（最低是「纸本暖白」暗档 1.22、
     * 「水银镜面」暗档 1.28），连「这根管子有多长」都读不出来。
     *
     * ## 为什么是 1.5 而不是图形的 3.0
     * 轨道是**参照物**，不是要读的图形。推到 3.0 它会和已走段争，
     * 读成两条并排的进度条；1.5 是「看得见但退在后面」的最低限度。
     */
    const val MIN_TRACK_CONTRAST = 1.5

    /**
     * 危险色：失败态那个红的**起点**。
     *
     * 与页面正文用的 #E5484D 同源（本工程删课、删除等破坏性操作都用它），
     * 但它压在浮岛面上未必够醒目——所以真正上屏的是 [dangerOn] 求解后的结果。
     */
    val DANGER_BASE = Color(0xFFE5484D)

    /**
     * 金属档浮岛面的实测亮度（与 [pillUnder] 同源，这里只做常量备查）。
     *
     * 危险色求解需要知道「压在哪」，而金属面既不是 surface 也不是 bg，
     * 是着色器算出来的，只能取样。
     */
    private val METAL_UNDER_DARK = Color(0xFF22262F)
    private val METAL_UNDER_LIGHT = Color(0xFFC3CAD5)

    /**
     * 浮岛面在求解时假定的**底层色**。
     *
     * ## 为什么必须集中在这里
     * 「语气色够不够醒目」「轨道看不看得见」「文字读不读得出」三个问题都要先回答
     * 「压在哪」。此前每个求解函数各写一份 `when (metal != null)`，于是出现了
     * 一处用着色器取样值、另一处用 surface 的分歧——单测跟着实现的分歧走，
     * 在金属档上验的是错的底（见 `IslandHintTest`）。
     *
     * 现在只有这一个答案，求解与断言取同一个数，漂移不可能再发生。
     *
     * 金属档的底不是 surface 也不是 bg，而是**着色器算出来的金属面**：
     * 暗色下实测落在 #1A1E26 附近（球冠中心更亮、边缘更暗）。取 surface 会算偏松，
     * 未选中的 tab 文字在真机上直接读不出来（截图确认过）。
     */
    internal fun pillUnder(
        colors: CourseColors,
        isDark: Boolean,
        metal: MetalSpec?,
    ): Color =
        if (metal != null) {
            if (isDark) METAL_UNDER_DARK else METAL_UNDER_LIGHT
        } else {
            colors.surface
        }

    /**
     * 危险色在浮岛面上的取值：以 [DANGER_BASE] 为起点，推到与底色达到图形门槛（3.0）为止。
     *
     * ## 为什么不能直接写死 #E5484D
     * 那是页面正文压 [CourseColors.surface] 的取值。而浮岛是悬浮层——六套预设里
     * 墨夜与玻璃暗档的浮岛面比正文底色更亮，同一个红压上去只剩 3.1:1，
     * 叉号与进度条都会糊进背景。失败提示最不能出的问题就是「看不清」。
     *
     * 用图形门槛 3.0 而不是正文门槛 4.5：红只用于**图标与进度条**，
     * 文字一律用 [CourseColors.text]；把一个颜色通道推到 4.5 会让它亮得像荧光棒。
     */
    fun dangerOn(
        colors: CourseColors,
        isDark: Boolean,
        metal: MetalSpec?,
    ): Color = Contrast.visibleOn(DANGER_BASE, pillUnder(colors, isDark, metal), MIN_ICON_CONTRAST)

    /**
     * 成功档的**上屏色**：以页面强调色为起点，推到与浮岛面达到图形门槛（3.0）为止。
     *
     * ## 为什么不能直接用 colors.accent
     * 强调色是**页面**的语义色，它保证的是「压在页面底色上」够醒目。浮岛是悬浮层，
     * 底换成了 surface 或金属面——真机实测水银镜面亮档上，蓝色强调色压在
     * #C3CAD5 的金属面上只有 2.89:1，低于图形门槛 3.0，对勾与进度条会发灰。
     *
     * ## 为什么与 [dangerOn] 走同一条公式
     * 这一条才是「视觉统一」真正的落点：两档语气若各用各的取色规则，它们在同一块
     * 胶囊面上的**醒目程度**就会不一样——成功偏淡、失败偏艳，用户会以为后者更严重。
     * 两档都解到同一个门槛，才谈得上"同一件事的两种结局"。
     *
     * 已经达标时原样返回：多推一步会让成功提示变成荧光色，而它每次只出现五秒。
     */
    fun doneOn(
        colors: CourseColors,
        isDark: Boolean,
        metal: MetalSpec?,
    ): Color = Contrast.visibleOn(colors.accent, pillUnder(colors, isDark, metal), MIN_ICON_CONTRAST)

    /**
     * 进度条**轨道**在浮岛面上的取值：以 [CourseColors.border] 为起点往正文色混，
     * 混到与浮岛面达到 [MIN_TRACK_CONTRAST] 为止。
     *
     * ## 为什么不用 colors.border
     * border 是「描边」用的：它只需要在**同色系里划一条线**，本来就该淡
     * （12 组实测 1.22~1.55:1）。而进度条轨道要回答的是「总共有多少」——
     * 淡到看不见时，用户看到的是一根无缘无故变长的色条，不是一颗正在倒计时的仪表。
     *
     * ## 为什么混向正文色而不是纯白 / 纯黑
     * 往 text 混会同时移动明度与色相，压在任何一套预设的浮岛面上都保持同一套纸感；
     * 混向白或黑会让暖色主题的轨道泛灰、冷色主题的轨道发紫。
     *
     * 达标即停（[Contrast.mixUntil] 的纪律）：多混一步轨道就会浮上来和已走段打架。
     */
    fun trackOn(
        colors: CourseColors,
        isDark: Boolean,
        metal: MetalSpec?,
    ): Color = Contrast.mixUntil(colors.border, colors.text, pillUnder(colors, isDark, metal), MIN_TRACK_CONTRAST)

    /**
     * 求解一套浮岛配色。
     *
     * [under] 取法：玻璃档本体近清水（bodyAlpha 0.16~0.20），透出的就是页面底色 bg；
     * 其余档是不透明 surface。取错会让求解偏松或偏紧，所以按档位分别取
     * （判据见 [pillUnder]，与语气色的求解共用同一个答案）。
     */
    fun solve(
        colors: CourseColors,
        isDark: Boolean,
        metal: MetalSpec? = null,
    ): IslandPalette {
        val under = pillUnder(colors, isDark, metal)
        // muted 求解：金属面上的未选中文字必须提到 4.5:1 以上。
        // 混到 text 只是起点——暗金属面吸光，实测还要再亮一档才「一眼看得见」，
        // 所以金属档额外朝 text 多混 12%（超过 AA 门槛后，可读性优先于"低调"）。
        val mutedBase = Contrast.mixUntil(colors.textMuted, colors.text, under, MIN_TEXT_CONTRAST)
        val muted =
            if (metal !=
                null
            ) {
                Contrast.mixUntil(mutedBase, colors.text, under, MIN_TEXT_CONTRAST * 1.15f)
            } else {
                mutedBase
            }
        val chipAlpha =
            metal?.let { METAL_CHIP_ALPHA } ?: CHIP_ALPHA
        // 金属档：选中胶囊是一枚**亮面水银珠**（着色器整体亮度高，实测浅色下接近 #D8DDE6）。
        // 浅色文字压上去等于消失——这条在真机上抓到过（选中 tab 文字被亮球吃掉）。
        // 所以文字改为深色：与亮金属形成 8:1 以上的对比，同时读作"刻在金属上的字"。
        val selected =
            when {
                // 深墨而非纯黑：纯黑在金属面上会有轻微的"贴上去"感；#05070B 足够黑又保留一点金属亲和
                metal != null -> Color(0xFF05070B)
                else -> colors.text
            }
        val chip =
            when {
                // 金属档的"珠色"不取 accent（那是页面的语义色），取一个近钢的冷灰做 F0 底，
                // 靠着色器的球冠明暗读出体积；accent 留给页面里的强调元素，不抢导航件。
                // 珠色取中明度钢灰：太亮会把深色文字吃掉，太暗则与浮岛本体的暗金属融成一片。
                // 真机取样：暗色 #6E7889、亮色 #97A3B6 时，深色文字对比 ≈ 7:1，珠子轮廓仍清晰。
                // 珠色亮度必须撑住深色文字：实测 4.17 → 需要再亮一档才过 4.5
                metal != null -> if (isDark) Color(0xFF9AA5B8) else Color(0xFFA8B4C6)
                else -> Contrast.adjustUntil(colors.accent, under, colors.onAccent, chipAlpha, MIN_TEXT_CONTRAST)
            }
        return IslandPalette(
            muted = muted,
            selected = selected,
            chip = chip,
            chipAlpha = chipAlpha,
            under = under,
            chipEdgeAlpha = if (metal != null) 0f else CHIP_EDGE_ALPHA,
        )
    }
}

/** 当前主题下的浮岛配色（主题 / 亮暗 / 玻璃档变化时重新求解） */
@Composable
fun rememberIslandPalette(): IslandPalette {
    val colors = LocalCourseColors.current
    val metal = LocalCoursePreset.current.metal
    val isDark = LocalIsDark.current
    return remember(colors, metal, isDark) { IslandTokens.solve(colors, isDark, metal) }
}

/**
 * 当前主题下、某档语气的上屏色。
 *
 * 与 [rememberIslandPalette] 同一套依赖：主题 / 亮暗 / 金属档任一变化都要重新求解，
 * 否则换皮肤之后那个色还停在上一套底色的解上。
 *
 * ## 为什么用一个函数而不是两个
 * 两档语气必须走**同一条**求解路径，这是「视觉统一」的硬前提。若拆成
 * [IslandTokens.doneOn] 与 [IslandTokens.dangerOn] 两个 @Composable 入口，
 * 调用处就会出现 `if (tone == ...) rememberA() else rememberB()`——
 * 条件调用 Composable 会打乱槽位；更要紧的是两档从此各有一条组装路径，
 * 将来任何一侧单独加个 `remember` 键，两者就会在同一帧里算出不同基准的色。
 */
@Composable
fun rememberIslandTone(tone: IslandHintTone): Color {
    val colors = LocalCourseColors.current
    val metal = LocalCoursePreset.current.metal
    val isDark = LocalIsDark.current
    return remember(tone, colors, metal, isDark) {
        when (tone) {
            // 待办与成功共用同一支强调色：它说的是「顺路去做一下」，不是「出事了」。
            // 给它单独一支颜色会让同一屏上出现第三种语义色，而三者的轻重差别
            // 读不出来（见 IslandHintTone 的类注释）
            IslandHintTone.Done, IslandHintTone.Pending -> IslandTokens.doneOn(colors, isDark, metal)
            IslandHintTone.Failed -> IslandTokens.dangerOn(colors, isDark, metal)
        }
    }
}

/**
 * 当前主题下的进度条轨道色。
 *
 * 与 [rememberIslandTone] 同源：轨道与已走段压在**同一块面**上，
 * 二者的可见度下限若来自两套基准，进度条就会在某一套预设上读成"没有轨道"。
 */
@Composable
fun rememberIslandTrack(): Color {
    val colors = LocalCourseColors.current
    val metal = LocalCoursePreset.current.metal
    val isDark = LocalIsDark.current
    return remember(colors, metal, isDark) { IslandTokens.trackOn(colors, isDark, metal) }
}

/**
 * 当前主题下浮岛面的实际底色（求解与绘制共用的那一个答案）。
 *
 * 存在的理由只有一个：挖空图标要用"胶囊自己的面"去画字形。
 * 若这里另写一份 `colors.surface`，金属档上字形就压在错误的底色上——
 * 它会在亮金属面上变成一个发白的小点，而不是一道被挖出来的缝。
 */
@Composable
fun rememberIslandFace(): Color {
    val colors = LocalCourseColors.current
    val metal = LocalCoursePreset.current.metal
    val isDark = LocalIsDark.current
    return remember(colors, metal, isDark) { IslandTokens.pillUnder(colors, isDark, metal) }
}
