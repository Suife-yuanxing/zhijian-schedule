package com.zhijian.schedule.feature.courseeditor

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.pressScale
import com.zhijian.schedule.core.ui.week.WeekCellGrid
import com.zhijian.schedule.core.util.WeekMask
import com.zhijian.schedule.core.util.WeekSelection

/**
 * 周次选择区：一行头部 + 5 列全览格阵 + 一行快捷 chips。
 *
 * ## 形态沿革（为什么是格阵）
 * 滑块版：无标签圆点看不出第几周，空态两拇指叠在起点，还要一句 11sp 灰字解释顺序依赖。
 * 横条版：省高度，但 20 周要横滑才能看全，占用点这类全貌信息一眼扫不完。
 * 用户拍板（2026-11-07）：周次是建课的主字段，全览 + 直接操纵的价值大于高度。
 * 旧格阵「页面滚不动」的根因是无条件 consume，轴向判定（见 WeekCellGrid）已消除，
 * 与父级 verticalScroll 不再冲突。
 *
 * ## 三组结构，没有跨组依赖
 * 1. 一行头部：周次 + 结果表达式 + 清空。结果与输入同行，读结果不用视线跳转。
 * 2. 一面格阵：全部周次可见，点击切换、横向拖过即连选。
 *    面（实心=已选）、边（未选=发丝描边）、线（本周=下划线，仅跟 isCurrent）、
 *    点（占用=右下角圆点）各走各的视觉通道。
 * 3. 一行快捷：整学期 / 单周 / 双周，tonal chips。点一次得到结果，不显示选中态——
 *    选中形态由格阵自己表达，再亮一个 chip 等于同一件事说两遍。
 */
@Composable
fun WeekSelectionSection(
    totalWeeks: Int,
    weeks: Set<Int>,
    currentWeek: Int?,
    occupied: Set<Int>,
    actions: WeekSelectionActions,
) {
    val colors = LocalCourseColors.current
    val mode = WeekSelection.mode(weeks)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "周次",
            color = colors.textMuted,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(8.dp))
        // 结果紧贴标签，而不是压在所有控件下方：读结果不用把视线从手指位置挪走
        Text(
            text = WeekMask.describe(WeekMask.fromWeeks(totalWeeks, weeks)),
            color = colors.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
        )
        if (mode == WeekSelection.Mode.CUSTOM) {
            Spacer(Modifier.width(6.dp))
            Text("逐周组合", color = colors.textMuted, fontSize = 11.sp)
        }
        Spacer(Modifier.weight(1f))
        TextAction(text = "清空", enabled = weeks.isNotEmpty(), onClick = actions.onClear)
    }

    Spacer(Modifier.height(8.dp))

    WeekCellGrid(
        totalWeeks = totalWeeks,
        weeks = weeks,
        onToggle = actions.onToggle,
        currentWeek = currentWeek,
        occupied = occupied,
        modifier = Modifier.fillMaxWidth(),
    )

    Spacer(Modifier.height(14.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        QuickChip(text = "整学期", onClick = { actions.onRange(1, totalWeeks) })
        // 单/双周在「已有范围」内取奇偶，所以至少要有两格才谈得上"取"。
        // 只有一格时置灰：否则点下去集合不变，用户会以为按钮坏了。
        QuickChip(text = "单周", enabled = weeks.size >= 2, onClick = { actions.onParity(true) })
        QuickChip(text = "双周", enabled = weeks.size >= 2, onClick = { actions.onParity(false) })
    }
}

/**
 * tonal 快捷 chip：无选中态的一次性动作。surfaceVariant 浅底 + 药丸形，
 * 与格阵的实心选中块拉开层级——快捷动作是「指令」，格阵才是「状态」。
 */
@Composable
private fun RowScope.QuickChip(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val source = remember { MutableInteractionSource() }
    val pill = RoundedCornerShape(50)
    Box(
        modifier =
            Modifier
                .weight(1f)
                .pressScale(source, pressedScale = 0.96f, enabled = enabled)
                .clip(pill)
                .background(colors.surfaceVariant)
                .clickable(
                    interactionSource = source,
                    indication = LocalIndication.current,
                    enabled = enabled,
                    onClick = onClick,
                ).padding(vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 12.5.sp,
            fontWeight = FontWeight.Medium,
            color = if (enabled) colors.text else colors.textMuted.copy(alpha = 0.55f),
        )
    }
}

/**
 * 纯文字动作：无底色、无描边、无圆角，只留给头部行里的「清空」这类轻量入口。
 */
@Composable
private fun TextAction(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val colors = LocalCourseColors.current
    val source = remember { MutableInteractionSource() }
    Text(
        text = text,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        color = if (enabled) colors.accent else colors.textMuted.copy(alpha = 0.5f),
        modifier =
            Modifier
                .pressScale(source, pressedScale = 0.94f, enabled = enabled)
                .clickable(
                    interactionSource = source,
                    indication = LocalIndication.current,
                    enabled = enabled,
                    onClick = onClick,
                ).padding(horizontal = 6.dp, vertical = 6.dp),
    )
}

/**
 * 周次选择的动作集合。
 *
 * 打包成对象而不是散装参数：detekt 的 LongParameterList 门槛是 8，
 * 而这里真正的信息只有「谁能改周次集合」。
 */
data class WeekSelectionActions(
    val onRange: (Int, Int) -> Unit,
    val onParity: (Boolean) -> Unit,
    val onToggle: (Int) -> Unit,
    val onClear: () -> Unit,
)
