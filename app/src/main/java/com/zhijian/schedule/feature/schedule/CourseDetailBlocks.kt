package com.zhijian.schedule.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.theme.CourseTone
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.EnterSpec
import com.zhijian.schedule.core.ui.SheetArrival
import com.zhijian.schedule.core.ui.SheetChoreography
import com.zhijian.schedule.core.ui.pressScale
import com.zhijian.schedule.core.ui.sheetArrive
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.core.util.WeekMask

/** 删除动作的红色：与「危险」这一档语义唯一对应，只在此处定义 */
private val DangerRed = Color(0xFFE5484D)

/**
 * 头卡：课程色铺底 + 顶部同色高光 + 色点 + 课程名 + 两枚胶囊。
 *
 * 这一段是面板的脸面，入场排在最前（[SheetChoreography.HERO]）。
 * 高光用 [CourseTone.highlight]（同色相的浅调）而不是白色：纯白在 Screen 混合下会把
 * 色相洗平，浅绿课会褪成灰白、被读成过曝，那正是这个项目早先踩过的「一团白斑」。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SheetHeroCard(
    course: CourseEntity,
    tone: CourseTone,
    isDark: Boolean,
    arrival: SheetArrival,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .sheetArrive(arrival, SheetChoreography.HERO, EnterSpec.Hero)
            .clip(shape)
            .background(tone.container)
            .background(
                Brush.verticalGradient(
                    colors =
                        listOf(
                            tone.highlight.copy(alpha = if (isDark) 0.10f else 0.55f),
                            Color.Transparent,
                        ),
                    endY = 96f,
                ),
            ).border(1.dp, tone.line, shape)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .sheetArrive(arrival, SheetChoreography.CHIP, EnterSpec.Chip)
                    .size(18.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(course.color))
                    // 描边与课块选中态同源：让「这块颜色 = 课表里那一块」在细节上也成立
                    .border(1.dp, tone.ink.copy(alpha = 0.28f), RoundedCornerShape(6.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = course.name,
                style = MaterialTheme.typography.titleLarge,
                color = tone.ink,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.sheetArrive(arrival, SheetChoreography.TITLE, EnterSpec.Text),
            )
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            HeroPill(
                text =
                    TimeUtils.dayName(course.dayOfWeek) +
                        " · 第 ${course.startSection}-${course.endSection} 节",
                tone = tone,
                modifier = Modifier.sheetArrive(arrival, SheetChoreography.PILL, EnterSpec.Pill),
            )
            HeroPill(
                text = WeekMask.toExpression(course.weekMask),
                tone = tone,
                modifier =
                    Modifier.sheetArrive(
                        arrival,
                        SheetChoreography.PILL + SheetChoreography.PILL_STEP,
                        EnterSpec.Pill,
                    ),
            )
        }
    }
}

/**
 * 信息卡：教室 / 教师 / 备注逐行列出，行间一道极淡分割线。
 *
 * 容器整体排一拍（[SheetChoreography.DETAILS]），每一行再各占一拍——
 * 容器先落位、行再依次浮上来，读作「一张卡正在被填满」；
 * 若把行和容器排在同一拍，三行会同时出现，等于没有次序。
 */
@Composable
internal fun SheetInfoCard(
    rows: List<DetailRow>,
    tone: CourseTone,
    arrival: SheetArrival,
) {
    val colors = LocalCourseColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .sheetArrive(arrival, SheetChoreography.DETAILS, EnterSpec.Card)
            .clip(RoundedCornerShape(16.dp))
            .background(colors.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        rows.forEachIndexed { index, row ->
            // 行与行之间一道极淡的线：三行原来只靠留白分隔，读作一段文字
            if (index > 0) {
                HorizontalDivider(
                    color = colors.border.copy(alpha = 0.45f),
                    thickness = 1.dp,
                )
            }
            InfoRow(
                icon = row.icon,
                label = row.label,
                value = row.value,
                tone = tone,
                modifier =
                    Modifier.sheetArrive(
                        arrival,
                        SheetChoreography.ROW + SheetChoreography.ROW_STEP * index,
                        EnterSpec.Row,
                    ),
            )
        }
    }
}

/**
 * 按钮行：编辑（实心强调色）+ 删除（文字按钮，红色）。
 *
 * 排在最后一拍（[SheetChoreography.ACTIONS]）。没有信息卡时提前跟上——
 * 否则面板会先停住、按钮才冒出来，读作「卡了一下」。
 */
@Composable
internal fun SheetActionRow(
    arrival: SheetArrival,
    hasDetails: Boolean,
    editSource: MutableInteractionSource,
    deleteSource: MutableInteractionSource,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalCourseColors.current
    Row(
        Modifier.sheetArrive(
            arrival,
            SheetChoreography.actionsSlot(hasDetails),
            EnterSpec.Action,
        ),
    ) {
        Button(
            onClick = onEdit,
            interactionSource = editSource,
            modifier = Modifier.weight(1f).pressScale(editSource, pressedScale = 0.96f),
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.onAccent,
                ),
        ) {
            Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text("编辑", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.width(12.dp))
        TextButton(
            onClick = onDelete,
            interactionSource = deleteSource,
            modifier = Modifier.weight(1f).pressScale(deleteSource, pressedScale = 0.96f),
        ) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = null,
                tint = DangerRed,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("删除", color = DangerRed, fontWeight = FontWeight.Medium)
        }
    }
}
