package com.zhijian.schedule.core.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

val LocalCourseColors = staticCompositionLocalOf { ThemePresets.paper.light }
val LocalCoursePreset = staticCompositionLocalOf { ThemePresets.paper }
val LocalIsDark = staticCompositionLocalOf { false }
val LocalGridStyle = staticCompositionLocalOf { GridStyles.EDITORIAL }

/** 0=跟随系统 1=亮 2=暗 */
@Composable
fun CourseTheme(
    presetId: String,
    darkMode: Int,
    gridStyle: String = GridStyles.EDITORIAL,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val isDark =
        when (darkMode) {
            1 -> false
            2 -> true
            else -> isSystemInDarkTheme()
        }
    val preset = remember(presetId) { ThemePresets.byId(presetId) }

    val dynamicScheme: ColorScheme? =
        if (preset.isDynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            null
        }

    val colors =
        when {
            dynamicScheme != null -> dynamicScheme.toCourseColors()
            isDark -> preset.dark
            else -> preset.light
        }
    val effectivePreset =
        remember(preset, dynamicScheme) {
            if (dynamicScheme != null) {
                preset.copy(
                    coursePalette = dynamicPalette(dynamicScheme),
                )
            } else {
                preset
            }
        }

    val scheme = dynamicScheme ?: colors.toColorScheme(isDark)
    val shapes =
        Shapes(
            extraSmall = RoundedCornerShape(preset.radius.blockDp.dp),
            small = RoundedCornerShape(preset.radius.blockDp.dp),
            medium = RoundedCornerShape(preset.radius.cardDp.dp),
            large = RoundedCornerShape(preset.radius.cardDp.dp),
            extraLarge = RoundedCornerShape(preset.radius.sheetDp.dp),
        )
    val typography =
        remember(effectivePreset.typography.serifTitle) {
            buildTypography(effectivePreset.typography.serifTitle)
        }

    CompositionLocalProvider(
        LocalCourseColors provides colors,
        LocalCoursePreset provides effectivePreset,
        LocalIsDark provides isDark,
        LocalGridStyle provides gridStyle,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            shapes = shapes,
            typography = typography,
            content = content,
        )
    }
}

private fun CourseColors.toColorScheme(isDark: Boolean): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        background = bg,
        onBackground = text,
        surface = surface,
        onSurface = text,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = textMuted,
        outline = border,
        outlineVariant = border,
        secondaryContainer = surfaceVariant,
        onSecondaryContainer = text,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surfaceVariant,
        inverseSurface = text,
        inverseOnSurface = bg,
    )
}

private fun ColorScheme.toCourseColors(): CourseColors =
    CourseColors(
        bg = background,
        surface = surfaceContainerLow,
        surfaceVariant = surfaceVariant,
        text = onBackground,
        textMuted = onSurfaceVariant,
        border = outlineVariant,
        accent = primary,
        onAccent = onPrimary,
    )

/** 动态取色：从 Material You 角色色派生 8 色课表色板 */
private fun dynamicPalette(scheme: ColorScheme): List<Color> {
    val roles =
        listOf(
            scheme.primary,
            scheme.tertiary,
            scheme.secondary,
            scheme.error,
        )
    val derived = roles.map { adjustLightness(it, if (scheme.isLight()) 0.12f else 0.18f) }
    return (roles + derived).distinct().take(8)
}

private fun ColorScheme.isLight(): Boolean =
    background.toArgb().let { argb ->
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        (r * 299 + g * 587 + b * 114) / 1000 > 150
    }

/** 调整亮度：delta 为正变亮、为负变暗，保持色相 */
fun adjustLightness(
    color: Color,
    delta: Float,
): Color {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val l = (max + min) / 2f
    val s =
        if (max == min) {
            0f
        } else {
            val d = max - min
            if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        }
    val h =
        when (max) {
            r -> ((g - b) / (max - min) + if (g < b) 6f else 0f) / 6f
            g -> ((b - r) / (max - min) + 2f) / 6f
            else -> ((r - g) / (max - min) + 4f) / 6f
        }.takeIf { max != min } ?: 0f

    val newL = (l + delta).coerceIn(0.1f, 0.9f)
    return if (s == 0f) Color(newL, newL, newL, color.alpha) else hslToColor(h, s, newL, color.alpha)
}

private fun hslToColor(
    h: Float,
    s: Float,
    l: Float,
    alpha: Float,
): Color {
    fun hue2rgb(
        p: Float,
        q: Float,
        t0: Float,
    ): Float {
        var t = t0
        if (t < 0f) t += 1f
        if (t > 1f) t -= 1f
        return when {
            t < 1f / 6f -> p + (q - p) * 6f * t
            t < 1f / 2f -> q
            t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
            else -> p
        }
    }
    if (s == 0f) return Color(l, l, l, alpha)
    val q = if (l < 0.5f) l * (1 + s) else l + s - l * s
    val p = 2 * l - q
    return Color(
        hue2rgb(p, q, h + 1f / 3f),
        hue2rgb(p, q, h),
        hue2rgb(p, q, h - 1f / 3f),
        alpha,
    )
}

private fun buildTypography(serif: Boolean): Typography {
    val base = Typography()
    if (!serif) return base
    val serifFamily = FontFamily.Serif
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = serifFamily),
        displayMedium = base.displayMedium.copy(fontFamily = serifFamily),
        displaySmall = base.displaySmall.copy(fontFamily = serifFamily),
        headlineLarge = base.headlineLarge.copy(fontFamily = serifFamily),
        headlineMedium = base.headlineMedium.copy(fontFamily = serifFamily),
        headlineSmall = base.headlineSmall.copy(fontFamily = serifFamily),
        titleLarge = base.titleLarge.copy(fontFamily = serifFamily, fontWeight = FontWeight.SemiBold),
    )
}
