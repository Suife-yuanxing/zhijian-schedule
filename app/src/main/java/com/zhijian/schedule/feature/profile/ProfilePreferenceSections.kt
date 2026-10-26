package com.zhijian.schedule.feature.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.GridStyles
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.ThemePresets
import com.zhijian.schedule.core.ui.JellySwitch
import com.zhijian.schedule.core.ui.RevealState

/**
 * 「我的」页的偏好设置分组：主题风格 / 深色模式 / 网格风格 / 课表显示 / 上课提醒。
 *
 * 每个分组只接数据与回调，落库与换肤由 ProfileScreen 接线；分组内共用
 * [ProfileChip]（选项胶囊）、[SectionTitle]（分组标题）与 settingSwitchRow（开关行）。
 */

/** 主题风格：胶囊点击时以自身为圆心发起主题扩散，扩散被受理才落库 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileThemeSection(
    selectedThemeId: String,
    reveal: RevealState,
    onSelectTheme: (String) -> Unit,
) {
    val colors = LocalCourseColors.current
    Column {
        SectionTitle("主题风格")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemePresets.all.forEach { preset ->
                // 胶囊自带选中弹跳（缩放），圆心取外包 Box 的屏幕坐标才是稳定的定位点：
                // 点击时以它为圆心发起圆形扩散，新主题从这颗胶囊铺开。
                val anchor = remember(preset.id) { ThemeChipAnchor() }
                Box(
                    Modifier.onGloballyPositioned {
                        anchor.bounds = it.boundsInRoot()
                    }
                ) {
                    ProfileChip(
                        label = preset.name,
                        selected = selectedThemeId == preset.id,
                        onClick = {
                            val accepted = reveal.requestTheme(
                                toId = preset.id,
                                fromId = selectedThemeId,
                                chipBoundsInRoot = anchor.bounds,
                                fromBg = colors.bg,
                            )
                            // 只有「点的就是正在渲染/正在去的那颗」才不落库；
                            // 扩散进行中再点别的主题会被受理：定格当前帧，从新胶囊重新起播
                            if (accepted) onSelectTheme(preset.id)
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileDarkModeSection(darkMode: Int, onSelect: (Int) -> Unit) {
    Column {
        SectionTitle("深色模式")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("跟随系统" to 0, "始终浅色" to 1, "始终深色" to 2).forEach { (label, mode) ->
                ProfileChip(
                    label = label,
                    selected = darkMode == mode,
                    onClick = { onSelect(mode) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileGridStyleSection(gridStyle: String, onSelect: (String) -> Unit) {
    val colors = LocalCourseColors.current
    Column {
        SectionTitle("网格风格")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GridStyles.all.forEach { (id, label) ->
                ProfileChip(
                    label = label,
                    selected = gridStyle == id,
                    onClick = { onSelect(id) },
                )
            }
        }
        Text(
            text = when (gridStyle) {
                GridStyles.BLOCK -> "去掉全部网格线：只留三段时段底纹与色块自身分层，适合信息密度高的课表"
                else -> "上午 / 下午 / 晚上三段标题 + 间距分层，段内只留一条 hairline，课块用同色系容器"
            },
            fontSize = 11.sp,
            color = colors.textMuted,
            modifier = Modifier.padding(start = 2.dp, top = 6.dp),
        )
    }
}

@Composable
internal fun ProfileDisplaySection(
    showWeekend: Boolean,
    onShowWeekendChange: (Boolean) -> Unit,
) {
    Column {
        SectionTitle("课表显示")
        SettingSwitchRow(
            title = "显示周末",
            subtitle = "在周视图中显示周六、周日两列",
            checked = showWeekend,
            onCheckedChange = onShowWeekendChange,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ProfileReminderSection(
    reminderEnabled: Boolean,
    reminderMinutes: Int,
    onToggle: (Boolean) -> Unit,
    onPickMinutes: (Int) -> Unit,
    onCustomClick: () -> Unit,
) {
    Column {
        SectionTitle("上课提醒")
        SettingSwitchRow(
            title = "课前提醒",
            subtitle = "提前 $reminderMinutes 分钟提醒（精确闹钟）· 胶囊同步倒计时",
            checked = reminderEnabled,
            onCheckedChange = onToggle,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val presets = listOf(10, 30)
            presets.forEach { m ->
                ProfileChip(
                    label = "$m 分钟",
                    selected = reminderEnabled && reminderMinutes == m,
                    onClick = { onPickMinutes(m) },
                )
            }
            ProfileChip(
                label = "自定义",
                selected = reminderEnabled && reminderMinutes !in presets,
                onClick = onCustomClick,
            )
        }
    }
}

/** 主题胶囊在根坐标系中的位置（px）：点击时作为扩散圆心，宽高折半作为起始半径 */
private class ThemeChipAnchor {
    var bounds: Rect = Rect.Zero
}

/** 分组内的开关行：左侧标题 + 副标题，右侧果冻开关 */
@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = LocalCourseColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = colors.textMuted, fontSize = 11.sp)
        }
        JellySwitch(
            checked = checked,
            onCheckedChange = {
                onCheckedChange(it)
            },
        )
    }
}
