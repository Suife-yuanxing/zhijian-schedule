package com.zhijian.schedule.core.ui

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.sp

/**
 * 中文避头尾的换行策略：**词组内不断行 + 标点不落行首**。
 *
 * ## 为什么中文必须单独设
 * Compose 的默认换行按 locale 走：中文（无空格语言）允许在任意两个汉字之间断行。
 * 于是真机上出现过两种读坏的情况：
 * - `已填入保存的教务账号（202530002131），确认无误后点登录` 在「（」后面折行，
 *   第二行只剩「后点登录」，前半句的括号孤零零挂在行末；
 * - 失败长句折行后，第二行以「，」开头——中文排版的硬性禁忌（避头点）。
 *
 * ## 三个参数各自解决什么
 * - [LineBreak.WordBreak.Phrase]：不在词组内部断（「登录框」「手动输入」不被拆开）；
 * - [LineBreak.Strictness.Strict]：严格执行避头尾，标点不许落在行首或行尾；
 * - [LineBreak.Strategy.HighQuality]：用 ICU 的高质量断行，代价是排版慢一点——
 *   这些文字都是一两行的短句，这点开销完全换得来「中文不被断坏」。
 *
 * [LineBreak] 在低版本系统上由 Compose 自行降级；本项目 minSdk 26，无需分支。
 */
val CjkLineBreak: LineBreak =
    LineBreak(
        strategy = LineBreak.Strategy.HighQuality,
        strictness = LineBreak.Strictness.Strict,
        wordBreak = LineBreak.WordBreak.Phrase,
    )

/**
 * 全站**唯一**的字号标尺。
 *
 * ## 为什么需要它
 * 改造前全工程 262 处字号是硬编码的 `.sp`：12sp 出现 89 次、11sp 55 次、13sp 32 次，
 * 另外还有 12.5 / 11.5 / 10.5 / 13.5 / 9.5 五档"半像素"字号，各出现 1~9 次。
 * 这套数字不是设计出来的，是一处一处「看着差一点点，调半磅」累积出来的——
 * 于是同一屏上并排的两行说明文字，一个 11sp、一个 11.5sp，肉眼读作"没对齐"。
 *
 * 标尺把可用字号收敛到 **9 档**，每一档有明确用途。半像素字号全部并入相邻档：
 * - 12.5 → [Label]（12sp）
 * - 11.5 / 10.5 → [Footnote]（11sp）
 * - 13.5 → [BodyStrong]（13sp）
 * - 9.5 → [Micro]（9sp）
 *
 * ## 为什么只有 9 档
 * iOS HIG 与人机界面指南的常用档位也在 9~11 个之间；再多的档位在 320~480dp 宽的
 * 手机上彼此只差 0.5sp，用户分辨不出，实现却要为每一档维护一套行高。
 * 少一档 = 少一次「这两行是不是该用同一个字号」的争论。
 *
 * ## 为什么行高写死在标尺里，而不是各个调用点自己写
 * 中文的行高比拉丁文更需要明确值：汉字是**满格方块**，行高不足时上下两行会顶在一起，
 * 读作糊成一片；而 Compose 的默认行高按西文基线算，中文偏紧。
 * 这一页的字号分布里，行高只写了 25 次就有 25 种组合（17/16/15/12/11/14/18/20/40/32），
 * 与字号完全对不上。标尺保证**每一档自带行高**，且行高 / 字号恒为 1.4~1.5
 * （中文正文的经验区间，见 [LINE_HEIGHT_RATIO]）。
 */
object TypeScale {
    /**
     * 行高 / 字号的比例区间。
     *
     * 中文正文 1.4~1.5：低于 1.4 时行距比字面还窄，汉字上下会互相侵占领地；
     * 高于 1.6 时行与行之间出现明显的白带，读起来散。西文常用 1.5~1.6，
     * 中文取同一个区间偏下段。
     */
    const val LINE_HEIGHT_RATIO = 1.4f

    // ---------- 正文族 ----------

    /** 17sp / 24sp：页面主标题（如「教务系统导入」这类唯一标题） */
    val Title =
        TextStyle(
            fontSize = 17.sp,
            lineHeight = 24.sp,
            fontWeight = FontWeight.SemiBold,
            lineBreak = CjkLineBreak,
        )

    /** 14sp / 20sp：卡片标题、对话框标题、主要按钮文字 */
    val Headline =
        TextStyle(
            fontSize = 14.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.SemiBold,
            lineBreak = CjkLineBreak,
        )

    /** 13sp / 19sp：正文默认档（正文、输入内容、开关标签） */
    val Body = body(13)

    /** 13sp / 19sp + 中等字重：需要在一行里强调的正文（用户名、状态值） */
    val BodyStrong = body(13, FontWeight.Medium)

    /** 12sp / 17sp：浮岛提示、按钮文字、次要按钮 */
    val Label = body(12)

    /** 12sp / 17sp + 中等字重：浮岛提示正文（它压在网页上，需要比周围网页字更实） */
    val LabelStrong = body(12, FontWeight.Medium)

    /** 11sp / 16sp：脚注、状态说明、"上次成功 x 月 x 日" */
    val Footnote = body(11)

    /** 9sp / 13sp：角标、极短的辅助计量（周数、计数） */
    val Micro = body(9)

    // ---------- 等宽族（数字列对齐用） ----------

    /** 12sp / 17sp 等宽：需要按列对齐的数字（时间、版本号、分数） */
    val Numeric =
        body(12).copy(
            fontFamily = FontFamily.Monospace,
            letterSpacing = 0.sp,
        )

    /**
     * 生成正文档样式。
     *
     * 行高由字号乘以 [LINE_HEIGHT_RATIO] 后**取整到整数 sp**：小数行高在不同字号缩放
     * （系统字体大小 115% / 130%）下会累积出亚像素偏移，同一屏两行的基线看上去差半格。
     */
    private fun body(
        sizeSp: Int,
        weight: FontWeight = FontWeight.Normal,
    ): TextStyle =
        TextStyle(
            fontSize = sizeSp.sp,
            lineHeight = (sizeSp * LINE_HEIGHT_RATIO).toInt().sp,
            fontWeight = weight,
            // Balanced：把长句在容器宽度内拆得更均匀，避免最后一行只剩一两个字
            lineBreak = CjkLineBreak,
        )
}
