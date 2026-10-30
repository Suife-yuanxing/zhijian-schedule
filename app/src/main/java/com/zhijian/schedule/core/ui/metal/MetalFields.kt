package com.zhijian.schedule.core.ui.metal

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.theme.LocalIsDark

/**
 * 表单控件（输入框 / 下拉 / 日期按钮）的金属化外观。
 *
 * 输入框本体的形状与底色由 M3 的 TextFieldColors 控制，无法直接塞进 ShaderBrush，
 * 所以这里做的是**视觉对齐而不是像素级材质**：金属主题下把容器改成「深色凹槽 + 窄亮边」，
 * 观感与卡片的凸起金属板形成一凸一凹的对照——这正是真实金属面板上开孔的样子。
 *
 * 非金属主题：返回 MaterialTheme 默认的配色，一个参数都不改。
 */
object MetalFields {
    /** 输入框圆角：与卡片的 14dp 同一节奏，不再用 M3 默认的 4dp 小圆角 */
    val shape: Shape = RoundedCornerShape(14.dp)

    /**
     * 输入框配色。
     * 金属档：容器取比页面更暗一档的凹面，聚焦时边框换成主题强调色（保留 M3 的可用性语义）。
     */
    @Composable
    fun textFieldColors(): TextFieldColors? {
        val metal = LocalCoursePreset.current.metal ?: return null
        val colors = LocalCourseColors.current
        val dark = LocalIsDark.current
        val well = if (dark) colors.surface.copy(alpha = 0.55f) else colors.surfaceVariant.copy(alpha = 0.55f)
        return OutlinedTextFieldDefaults.colors(
            focusedContainerColor = well,
            unfocusedContainerColor = well,
            disabledContainerColor = well,
            focusedBorderColor = colors.accent,
            unfocusedBorderColor = colors.border,
            focusedTextColor = colors.text,
            unfocusedTextColor = colors.text,
            focusedLabelColor = colors.accent,
            unfocusedLabelColor = colors.textMuted,
            cursorColor = colors.accent,
        )
    }
}
