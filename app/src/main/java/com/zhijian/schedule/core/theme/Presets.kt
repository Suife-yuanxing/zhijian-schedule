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

    /** 3. 清爽极简：iOS 风纯白 + 系统蓝 + 大圆角 */
    val minimal = ThemePreset(
        id = ThemeIds.MINIMAL,
        name = "清爽极简",
        light = CourseColors(
            bg = Color(0xFFFFFFFF),
            surface = Color(0xFFF4F5F7),
            surfaceVariant = Color(0xFFEFEFF4),
            text = Color(0xFF1C1C1E),
            textMuted = Color(0xFF8E8E93),
            border = Color(0xFFE5E5EA),
            accent = Color(0xFF007AFF),
            onAccent = Color(0xFFFFFFFF),
        ),
        dark = CourseColors(
            bg = Color(0xFF1C1C1E),
            surface = Color(0xFF2C2C2E),
            surfaceVariant = Color(0xFF3A3A3C),
            text = Color(0xFFFFFFFF),
            textMuted = Color(0xFF8E8E93),
            border = Color(0xFF3A3A3C),
            accent = Color(0xFF0A84FF),
            onAccent = Color(0xFFFFFFFF),
        ),
        coursePalette = listOf(
            Color(0xFF007AFF), Color(0xFF34C759), Color(0xFFFF9500), Color(0xFFFF3B30),
            Color(0xFFAF52DE), Color(0xFF5AC8FA), Color(0xFFFF2D55), Color(0xFF8E8E93),
        ),
        blockStyle = BlockStyle(fillAlphaLight = 0.12f, fillAlphaDark = 0.22f, barWidthDp = 3f),
        typography = TypePreset(serifTitle = false),
        radius = RadiusPreset(blockDp = 12f, cardDp = 20f, sheetDp = 28f),
    )

    /** 4. 墨夜纯黑：AMOLED 省电，高对比 */
    val amoled = ThemePreset(
        id = ThemeIds.AMOLED,
        name = "墨夜纯黑",
        light = CourseColors(
            bg = Color(0xFFF7F7F8),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFEFEFF1),
            text = Color(0xFF111111),
            textMuted = Color(0xFF74747A),
            border = Color(0xFFE3E3E6),
            accent = Color(0xFF4C8DFF),
            onAccent = Color(0xFFFFFFFF),
        ),
        dark = CourseColors(
            bg = Color(0xFF000000),
            surface = Color(0xFF0F0F10),
            surfaceVariant = Color(0xFF1A1A1C),
            text = Color(0xFFFFFFFF),
            textMuted = Color(0xFF9A9AA0),
            border = Color(0xFF262628),
            accent = Color(0xFF6BA0FF),
            onAccent = Color(0xFF00285E),
        ),
        coursePalette = listOf(
            Color(0xFF4C8DFF), Color(0xFF37C871), Color(0xFFFFB020), Color(0xFFFF5C5C),
            Color(0xFFA97BFF), Color(0xFF2ECED0), Color(0xFFFF8FB1), Color(0xFF9AA5B1),
        ),
        blockStyle = BlockStyle(fillAlphaLight = 0.16f, fillAlphaDark = 0.28f, barWidthDp = 3f),
        typography = TypePreset(serifTitle = false),
        radius = RadiusPreset(blockDp = 8f, cardDp = 14f, sheetDp = 18f),
    )

    /** 5. 多彩活泼：高饱和糖果色 */
    val candy = ThemePreset(
        id = ThemeIds.CANDY,
        name = "多彩活泼",
        light = CourseColors(
            bg = Color(0xFFF7F8FC),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFEEF1F9),
            text = Color(0xFF1F2430),
            textMuted = Color(0xFF7A8296),
            border = Color(0xFFE4E8F2),
            accent = Color(0xFF3A7DFF),
            onAccent = Color(0xFFFFFFFF),
        ),
        dark = CourseColors(
            bg = Color(0xFF12141A),
            surface = Color(0xFF1B1E27),
            surfaceVariant = Color(0xFF232734),
            text = Color(0xFFEEF1F8),
            textMuted = Color(0xFF98A0B3),
            border = Color(0xFF2A2F3D),
            accent = Color(0xFF5B93FF),
            onAccent = Color(0xFFFFFFFF),
        ),
        coursePalette = listOf(
            Color(0xFF3A7DFF), Color(0xFF2BB673), Color(0xFFF59E0B), Color(0xFFEF4444),
            Color(0xFF8B5CF6), Color(0xFF06B6D4), Color(0xFFEC4899), Color(0xFF10B981),
        ),
        blockStyle = BlockStyle(fillAlphaLight = 0.22f, fillAlphaDark = 0.34f, barWidthDp = 4f),
        typography = TypePreset(serifTitle = false),
        radius = RadiusPreset(blockDp = 16f, cardDp = 20f, sheetDp = 24f),
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
