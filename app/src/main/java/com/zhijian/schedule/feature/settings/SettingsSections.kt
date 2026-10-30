package com.zhijian.schedule.feature.settings

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.buttonFeedback
import com.zhijian.schedule.core.ui.metal.CardTone
import com.zhijian.schedule.core.ui.metal.metalCard

/*
 * 设置页的「本地数据」分组：课表信息、作息时间、备份与恢复。
 *
 * 这里的分组只渲染数据与回调，不碰 ViewModel、权限与系统设置页——
 * 需要和系统打交道的三个分组在 SettingsSystemSections.kt。
 */

/** 分组卡：设置页每一块的外壳 */
@Composable
internal fun SettingsCard(content: @Composable () -> Unit) {
    val colors = LocalCourseColors.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .clip(shape)
            // 金属主题：设置分组是一块哑光金属板（SURFACE 档），倒角圈把它从暗底上切出来
            .metalCard(shape = shape, color = colors.surface, tone = CardTone.SURFACE)
            .padding(16.dp),
    ) {
        content()
    }
}

/** 键值行：左侧定宽标签 + 右侧取值 */
@Composable
internal fun InfoRow(
    label: String,
    value: String,
) {
    val colors = LocalCourseColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, color = colors.textMuted, fontSize = 13.sp, modifier = Modifier.width(80.dp))
        Text(value, color = colors.text, fontSize = 13.sp)
    }
}

/** 课表信息：名称 / 学期 / 开学日期 / 总周数，右侧进入编辑弹窗 */
@Composable
internal fun TimetableInfoSection(
    timetable: TimetableEntity,
    onEdit: () -> Unit,
) {
    val colors = LocalCourseColors.current
    SettingsCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "课表信息",
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TapTextButton(onClick = onEdit) { Text("编辑") }
        }
        InfoRow("名称", timetable.name)
        InfoRow("学期", timetable.semester.ifBlank { "未设置" })
        InfoRow("开学日期", timetable.startDate)
        InfoRow("总周数", "${timetable.totalWeeks} 周")
    }
}

/** 作息时间：逐小节列出起止时间，点按进入该小节的编辑弹窗 */
@Composable
internal fun SectionTimesSection(
    sections: List<SectionTimeEntity>,
    onEdit: (SectionTimeEntity) -> Unit,
) {
    val colors = LocalCourseColors.current
    SettingsCard {
        Text("作息时间", color = colors.text, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        sections.forEach { section ->
            val pressSource = remember { MutableInteractionSource() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .buttonFeedback(pressSource, pressedScale = 0.98f)
                    .clickable(
                        interactionSource = pressSource,
                        indication = LocalIndication.current,
                    ) { onEdit(section) }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "小节 ${section.sectionNo}",
                    color = colors.text,
                    fontSize = 14.sp,
                    modifier = Modifier.width(72.dp),
                )
                Text(
                    "${section.startTime} - ${section.endTime}",
                    color = colors.textMuted,
                    fontSize = 13.sp,
                )
            }
        }
    }
}

/** 备份与恢复：导出为 JSON 文件 / 从 JSON 文件恢复 */
@Composable
internal fun BackupSection(
    onExport: () -> Unit,
    onImport: () -> Unit,
) {
    val colors = LocalCourseColors.current
    SettingsCard {
        Text("备份与恢复", color = colors.text, fontWeight = FontWeight.SemiBold)
        Text(
            "导出为 JSON 文件，可换机恢复（本地优先，方案 3.1）",
            color = colors.textMuted,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val exportSource = remember { MutableInteractionSource() }
            Button(
                onClick = onExport,
                interactionSource = exportSource,
                modifier = Modifier.buttonFeedback(exportSource, pressedScale = 0.96f),
            ) {
                Text("导出备份")
            }
            TapTextButton(onClick = onImport) { Text("导入恢复") }
        }
    }
}
