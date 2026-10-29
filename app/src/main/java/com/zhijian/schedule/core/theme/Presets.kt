package com.zhijian.schedule.core.theme

import androidx.compose.ui.graphics.Color

/**
 * 5 套内置风格（方案 5.2 ②）。
 */
object ThemePresets {

    /** 1. 纸本暖白（默认）：米白 + 陶土 + 衬线标题 + hairline */
    val paper = ThemePreset(
        id = ThemeIds.PAPER,
        name = "纸本暖白",
        light = CourseColors(
            bg = Color(0xFFFAF6EF),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFF3ECE0),
            text = Color(0xFF3D3929),
            textMuted = Color(0xFF8A8273),
            border = Color(0xFFE7DFD2),
            accent = Color(0xFFD97757),
            onAccent = Color(0xFFFFFFFF),
        ),
        dark = CourseColors(
            bg = Color(0xFF211D19),
            surface = Color(0xFF2A2620),
            surfaceVariant = Color(0xFF332E27),
            text = Color(0xFFE8E2D5),
            textMuted = Color(0xFFA39B8B),
            border = Color(0xFF3A342C),
            accent = Color(0xFFD97757),
            onAccent = Color(0xFFFFFFFF),
        ),
        coursePalette = listOf(
            Color(0xFFC4704F), Color(0xFFC99A3D), Color(0xFF7D9B5A), Color(0xFF4E9B8B),
            Color(0xFF5B8DB8), Color(0xFF9B7DB8), Color(0xFFC2848D), Color(0xFF8A8F98),
        ),
        blockStyle = BlockStyle(fillAlphaLight = 0.14f, fillAlphaDark = 0.24f, barWidthDp = 3f),
        typography = TypePreset(serifTitle = true),
        radius = RadiusPreset(blockDp = 10f, cardDp = 16f, sheetDp = 20f),
    )

    /** 2. 动态取色：跟随系统壁纸（Android 12+），色板运行时生成 */
    val dynamic = ThemePreset(
        id = ThemeIds.DYNAMIC,
        name = "动态取色",
        light = CourseColors(
            bg = Color(0xFFFDF8FF),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFE8E0EF),
            text = Color(0xFF1C1B1F),
            textMuted = Color(0xFF76747C),
            border = Color(0xFFD8D0DE),
            accent = Color(0xFF6750A4),
            onAccent = Color(0xFFFFFFFF),
        ),
        dark = CourseColors(
            bg = Color(0xFF141218),
            surface = Color(0xFF1D1B20),
            surfaceVariant = Color(0xFF2B2930),
            text = Color(0xFFE6E1E9),
            textMuted = Color(0xFFAAA5AE),
            border = Color(0xFF3B383E),
            accent = Color(0xFFD0BCFF),
            onAccent = Color(0xFF381E72),
        ),
        coursePalette = listOf(
            Color(0xFF6750A4), Color(0xFF7D5260), Color(0xFF146C5B), Color(0xFF8B5000),
            Color(0xFF00658E), Color(0xFF6B5778), Color(0xFF984061), Color(0xFF5A5C7A),
        ),
        blockStyle = BlockStyle(fillAlphaLight = 0.14f, fillAlphaDark = 0.26f, barWidthDp = 3f),
        typography = TypePreset(serifTitle = false),
        radius = RadiusPreset(blockDp = 12f, cardDp = 16f, sheetDp = 24f),
        isDynamic = true,
    )

    /**
     * 3. 清爽极简 · 素墨：冷近白 + 墨灰 + 唯一朱砂，衬线标题，全 app 最细色条。
     *
     * 主张「留白是内容，朱砂是标记」：整套界面只允许一个高饱和色相（朱砂），
     * 且只出现在「今天 / 当前节次」上；课程色一律压成低饱和的淡墨，靠明度差区分。
     * 禁区：不用蓝色系（那是动态取色与液态玻璃的语言）、不用阴影与渐变、课块不实心填充。
     */
    val minimal = ThemePreset(
        id = ThemeIds.MINIMAL,
        name = "清爽极简",
        light = CourseColors(
            bg = Color(0xFFFBFBFC),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFF1F2F4),
            text = Color(0xFF17181A),
            textMuted = Color(0xFF6E7278),
            border = Color(0xFFE3E5E9),
            accent = Color(0xFFC0412C),
            onAccent = Color(0xFFFFFFFF),
        ),
        dark = CourseColors(
            bg = Color(0xFF141517),
            surface = Color(0xFF1B1D20),
            surfaceVariant = Color(0xFF24272B),
            text = Color(0xFFEDEEF0),
            textMuted = Color(0xFF9BA0A7),
            border = Color(0xFF2E3238),
            accent = Color(0xFFE0654C),
            onAccent = Color(0xFF1A0D08),
        ),
        coursePalette = listOf(
            Color(0xFF3A5A7A), Color(0xFF3F6B57), Color(0xFF8A5A3B), Color(0xFF6B4E6E),
            Color(0xFF2F5F6B), Color(0xFF6E6A3A), Color(0xFF56667A), Color(0xFF5A5F66),
        ),
        blockStyle = BlockStyle(fillAlphaLight = 0.10f, fillAlphaDark = 0.20f, barWidthDp = 2.5f),
        typography = TypePreset(serifTitle = true),
        radius = RadiusPreset(blockDp = 10f, cardDp = 14f, sheetDp = 22f),
        // 淡墨：亮色卡面更接近纸（L 0.945→0.965）、彩度砍到 55%；暗色再压一档（0.285→0.245）
        toneBias = CourseToneBias(lightL = 0.965f, lightChroma = 0.55f, darkL = 0.245f, darkChroma = 0.95f),
    )

    /**
     * 4. 墨夜纯黑 · 灯下：真黑 + 暖白 + 唯一琥珀，全部层级靠 hairline 承担。
     *
     * 主张「纯黑不是没有光，是只有一盏灯」：纯黑上投不出阴影，所以不假装有层，
     * 改用「灯照到哪一列」组织注意力 —— 今日列抬到 surfaceVariant 并压一条琥珀左缘线，
     * 其余留在 #000000；色条是唯一允许饱和的地方。
     * 禁区：不用 #FFFFFF 作正文（纯黑上会 halation，故用暖白 #E8E3D9）、
     * 不用阴影或发光模糊做层级、不做霓虹夜店风。
     */
    val amoled = ThemePreset(
        id = ThemeIds.AMOLED,
        name = "墨夜纯黑",
        light = CourseColors(
            bg = Color(0xFFF6F6F4),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFEFEEE9),
            text = Color(0xFF1A1815),
            textMuted = Color(0xFF655F57),
            border = Color(0xFFE2E0D9),
            accent = Color(0xFFA85F12),
            onAccent = Color(0xFFFFFFFF),
        ),
        dark = CourseColors(
            bg = Color(0xFF000000),
            surface = Color(0xFF0A0A0B),
            surfaceVariant = Color(0xFF121214),
            text = Color(0xFFE8E3D9),
            textMuted = Color(0xFF9B9384),
            border = Color(0xFF26262A),
            accent = Color(0xFFF2A93B),
            onAccent = Color(0xFF1A1206),
        ),
        coursePalette = listOf(
            Color(0xFFE0A243), Color(0xFFC97B52), Color(0xFF94A85B), Color(0xFF4FA98A),
            Color(0xFF4E9DB3), Color(0xFFA98BC4), Color(0xFFC9718A), Color(0xFF8C9199),
        ),
        blockStyle = BlockStyle(fillAlphaLight = 0.16f, fillAlphaDark = 0.26f, barWidthDp = 3f),
        typography = TypePreset(serifTitle = false),
        radius = RadiusPreset(blockDp = 6f, cardDp = 12f, sheetDp = 16f),
        // 灯照：暗色卡面压到 0.225（沉进纯黑），彩度反向拉到 125%（被灯照到的那一块必须够暖）
        toneBias = CourseToneBias(lightL = 0.960f, lightChroma = 1.0f, darkL = 0.225f, darkChroma = 1.25f),
    )

    /**
     * 5. 多彩活泼 · 文具盒：奶油纸白 + 8 色编号 + 全 app 最粗色条。
     *
     * 主张「颜色是有编号的，不是撒出来的」：色相均分、明度归一到 L*≈55-62、封顶 8 色、
     * 顺序不可变（第 9 门课回到第 1 色）。色条 4dp 是「荧光笔划重点」的动作，也是本主题的主角。
     * 禁区：不用纯 #FFFFFF 作底（冷白 + 高饱和 = 廉价电商感，故用奶油纸白 #FFFCF4）、
     * 不用运行时随机派生色、不做渐变彩虹背景与彩色光晕。
     */
    val candy = ThemePreset(
        id = ThemeIds.CANDY,
        name = "多彩活泼",
        light = CourseColors(
            bg = Color(0xFFFFFCF4),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFFFF1C9),
            text = Color(0xFF241E1A),
            textMuted = Color(0xFF6F6355),
            border = Color(0xFFEFE2C8),
            accent = Color(0xFFCE3B26),
            onAccent = Color(0xFFFFFFFF),
        ),
        dark = CourseColors(
            bg = Color(0xFF17141A),
            surface = Color(0xFF211D26),
            surfaceVariant = Color(0xFF332B3D),
            text = Color(0xFFF5F0E8),
            textMuted = Color(0xFFB0A6BC),
            border = Color(0xFF3A3342),
            accent = Color(0xFFFF8A6B),
            onAccent = Color(0xFF2A0F06),
        ),
        coursePalette = listOf(
            Color(0xFFC98A00), Color(0xFFF26A2E), Color(0xFFE8465C), Color(0xFFE85C90),
            Color(0xFF7C5CFF), Color(0xFF2F8FEF), Color(0xFF22B37D), Color(0xFF14A8A2),
        ),
        blockStyle = BlockStyle(fillAlphaLight = 0.20f, fillAlphaDark = 0.32f, barWidthDp = 4f),
        typography = TypePreset(serifTitle = false),
        radius = RadiusPreset(blockDp = 14f, cardDp = 18f, sheetDp = 24f),
        // 便利贴：彩度是主角。亮色明度必须让到 0.930 —— L 0.95 处 sRGB 色域只剩 ~0.03 彩度，
        // 系数乘多大都会被色域截断，这正是「三套主题课块长得一样」的真正原因（真机实测过）。
        // 暗色不动：0.300 处色域够宽，彩度已到 0.30，再拉会把卡片推离「沉进暗背景」的区间。
        toneBias = CourseToneBias(lightL = 0.930f, lightChroma = 3.50f, darkL = 0.300f, darkChroma = 1.30f),
    )

    /** 6. 液态玻璃：纯净底色 + 底部清水玻璃浮岛（零模糊，流动实时渲染） */
    val glass = ThemePreset(
        id = ThemeIds.GLASS,
        name = "液态玻璃",
        light = CourseColors(
            bg = Color(0xFFF6F7F9),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFEFF1F5),
            text = Color(0xFF17191F),
            textMuted = Color(0xFF6E7480),
            border = Color(0xFFE4E7EC),
            accent = Color(0xFF2F7BFF),
            onAccent = Color(0xFFFFFFFF),
        ),
        dark = CourseColors(
            bg = Color(0xFF0E0F13),
            surface = Color(0xFF17191F),
            surfaceVariant = Color(0xFF1F222A),
            text = Color(0xFFE9EBF0),
            textMuted = Color(0xFF9AA0AE),
            border = Color(0xFF2A2D36),
            accent = Color(0xFF5AC8FA),
            onAccent = Color(0xFF04263A),
        ),
        coursePalette = listOf(
            Color(0xFF57C7FF), Color(0xFF39D2C0), Color(0xFF8F7BF7), Color(0xFFF178B6),
            Color(0xFF7CE3A9), Color(0xFF9D8CFF), Color(0xFF4FA8E8), Color(0xFFC084FC),
        ),
        blockStyle = BlockStyle(fillAlphaLight = 0.16f, fillAlphaDark = 0.26f, barWidthDp = 3f),
        typography = TypePreset(serifTitle = false),
        radius = RadiusPreset(blockDp = 16f, cardDp = 20f, sheetDp = 26f),
        glass = GlassSpec(
            tintLight = Color(0xFFC9D6E8),
            tintDark = Color(0xFF9FB0C6),
            bodyAlphaLight = 0.16f,
            bodyAlphaDark = 0.20f,
            chipAlphaLight = 0.90f,
            chipAlphaDark = 0.88f,
            specular = 0.78f,
            flow = 0.55f,
            lens = 0.12f,
            mirror = 0.50f,
            tiltSpec = 1.0f,
        ),
    )

    val all: List<ThemePreset> = listOf(paper, dynamic, minimal, amoled, candy, glass)

    fun byId(id: String?): ThemePreset = all.firstOrNull { it.id == id } ?: paper
}
