package com.zhijian.schedule.core.theme

import androidx.compose.ui.graphics.Color

/**
 * 一套风格内的完整色彩 Token。
 */
data class CourseColors(
    val bg: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val text: Color,
    val textMuted: Color,
    val border: Color,
    val accent: Color,
    val onAccent: Color,
)

/**
 * 课块渲染样式：**已废弃**，仅保留字段以免旧预设字面量编译不过。
 *
 * 历史：课块早期是「透明填充 + 左色条」，后来改成 [CourseTone] 的同色系容器 + 1dp 同色描边
 * （见 `feature/schedule/components/CourseBlock.kt` 的类注释「去掉左色条」）。
 * 从此这三个参数不再有任何渲染消费点，主题之间的视觉差异改由 [CourseToneBias] 承担。
 * 新主题不要在这里调参数——调了不会有任何效果。
 */
data class BlockStyle(
    val fillAlphaLight: Float,
    val fillAlphaDark: Float,
    val barWidthDp: Float,
)

/**
 * 课程色的**主题级偏置**：同一门课在不同主题下应该有不同的「面色」。
 *
 * 为什么需要它：`courseTone(source, isDark)` 只按源色相派生明度/彩度，与主题无关——
 * 于是三套主题的课块长得一模一样，主题切换只换得了页面底色，换不了课块性格。
 * 把明度与彩度做成主题参数之后，同一门课在「素墨」是淡到几乎融纸的墨，
 * 在「灯下」是灯照出的一小片暖，在「文具盒」是高彩度的便利贴。
 *
 * 全部是**系数**而不是绝对值：色相与彩度关系仍由 [courseTone] 从课程源色推导，
 * 这里只整体平移明度、缩放彩度，不会把八门课压成同一个颜色。
 *
 * 默认值等于改版前的硬编码常量，所以不传 = 行为完全不变（纸本暖白/动态取色/液态玻璃零风险）。
 */
data class CourseToneBias(
    /**
     * 亮色卡面明度（OKLCH L）。基准 0.945，越高越接近纸。
     *
     * **这是彩度的总闸门，不是装饰参数。** sRGB 在 L 0.95 处的可用彩度只剩 ~0.03，
     * 无论 [lightChroma] 乘多大都会被 [tonal] 的降彩度重试截断；想让课块"有颜色"，
     * 唯一的手段是把明度让下来。实测上限：L 0.930 → 最暗 0.793（[CourseToneTest]
     * 的 0.75 门槛余量 0.043）；再降到 0.920 余量只剩 0.016，一个色相跑偏就破线。
     */
    val lightL: Float = 0.945f,
    /**
     * 亮色卡面彩度系数。基准 1.0，越低越像淡墨。
     *
     * 与 [lightL] 联动：明度不降，这个值调大基本没有视觉效果（会被色域裁掉）。
     * 另外它也会放大**近中性色**的染色量——#8A8F98 这类冷灰在 OKLCH 里 chroma 0.0146，
     * 走的是彩色分支，系数越大越泛蓝，这是正常行为而不是 bug。
     */
    val lightChroma: Float = 1f,
    /** 暗色卡面明度。基准 0.285，越低越沉 */
    val darkL: Float = 0.285f,
    /** 暗色卡面彩度系数。基准 1.0，越高越像被灯光照亮 */
    val darkChroma: Float = 1f,
) {
    companion object {
        /** 改版前的硬编码值，行为等价 */
        val Default = CourseToneBias()
    }
}

data class TypePreset(
    val serifTitle: Boolean,
)

data class RadiusPreset(
    val blockDp: Float,
    val cardDp: Float,
    val sheetDp: Float,
)

/**
 * 清水玻璃材质参数（仅「液态玻璃」预设非空）：高透明主体 + 流动光带 + 边缘高光。
 */
data class GlassSpec(
    /** 玻璃底色（亮/暗）：水一样通透的浅着色 */
    val tintLight: Color,
    val tintDark: Color,
    /** 玻璃主体 alpha（亮/暗），越大越不透明 */
    val bodyAlphaLight: Float,
    val bodyAlphaDark: Float,
    /** 选中液滴填充浓度（亮/暗）：越高越接近实心 accent，与浮岛水膜的对比越强 */
    val chipAlphaLight: Float,
    val chipAlphaDark: Float,
    /** 边缘高光强度 */
    val specular: Float,
    /** 流动光带强度（0~1） */
    val flow: Float,
    /** 边缘透镜位移强度（相对浮岛高度的比例，0 = 无折射） */
    val lens: Float,
    /** 边缘镜像反射混合强度（0~1）：贴边区域把周围背景「收」进反光的程度 */
    val mirror: Float,
    /** 倾角镜面高光强度（0~1）：反光随重力方向滑动的程度 */
    val tiltSpec: Float,
)

/**
 * 设计 Token 体系：一套代码 + N 套风格（方案 5.2 ①）。
 */
data class ThemePreset(
    val id: String,
    val name: String,
    val light: CourseColors,
    val dark: CourseColors,
    val coursePalette: List<Color>,
    val blockStyle: BlockStyle,
    val typography: TypePreset,
    val radius: RadiusPreset,
    val isDynamic: Boolean = false,
    /** 非空 = 启用玻璃材质渲染（仅 glass 预设） */
    val glass: GlassSpec? = null,
    /** 课块「面色」的主题级偏置；默认等价于改版前行为 */
    val toneBias: CourseToneBias = CourseToneBias.Default,
)

object ThemeIds {
    const val PAPER = "paper"
    const val DYNAMIC = "dynamic"
    const val MINIMAL = "minimal"
    const val AMOLED = "amoled"
    const val CANDY = "candy"
    const val GLASS = "glass"
}

/**
 * 网格语言（与主题皮肤正交：任何一套风格都能选任意一种网格画法）。
 *
 * - [EDITORIAL] 编辑式：上午/下午/晚上三段标题 + 间距分层，段内只留一条 hairline；
 * - [BLOCK] 色块制：去掉全部网格线，用时段底纹条与色块自身分层。
 */
object GridStyles {
    const val EDITORIAL = "editorial"
    const val BLOCK = "block"

    val all: List<Pair<String, String>> = listOf(EDITORIAL to "编辑式", BLOCK to "色块制")
}
