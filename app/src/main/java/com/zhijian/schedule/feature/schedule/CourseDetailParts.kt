package com.zhijian.schedule.feature.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.theme.CourseTone
import com.zhijian.schedule.core.theme.LocalCourseColors

/**
 * 头卡里的小胶囊（星期 · 节次、周次表达式）。
 *
 * [modifier] 由调用点下发入场动效（[com.zhijian.schedule.core.ui.sheetArrive]）：
 * 节拍表要求「两枚 pill 各差一小格」，那个差必须落在**每一枚**的图层上，
 * 所以修饰符只能从外面传进来，不能在内部写死。
 */
@Composable
internal fun HeroPill(
    text: String,
    tone: CourseTone,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCourseColors.current
    val shape = RoundedCornerShape(50)
    Text(
        text = text,
        color = tone.ink,
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        modifier =
            modifier
                .clip(shape)
                .background(colors.surface.copy(alpha = 0.72f))
                .border(1.dp, tone.line.copy(alpha = 0.75f), shape)
                .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * 信息卡里的一行：左侧圆形图标 + 右侧「字段名 / 字段值」。
 *
 * [modifier] 同样由调用点下发：每一行在节拍表上各占一拍，
 * 这样三行会依次浮上来，而不是作为一整块同时出现。
 */
@Composable
internal fun InfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    tone: CourseTone,
    modifier: Modifier = Modifier,
) {
    val colors = LocalCourseColors.current
    Row(
        modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(tone.container)
                .border(1.dp, tone.line.copy(alpha = 0.6f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tone.ink, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = colors.textMuted, fontSize = 11.sp)
            Text(
                text = value,
                color = colors.text,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

/** 信息卡里的一行（数据形态）：图标与文案成对出现，便于按行数排节拍 */
internal data class DetailRow(
    val icon: ImageVector,
    val label: String,
    val value: String,
)

/**
 * 按「教室 → 教师 → 备注」的固定顺序取非空字段。
 *
 * 抽成函数的理由不是复用，而是**先算出行数**：节拍表里每行占一拍，
 * 而按钮排在第几拍又取决于有没有信息卡，行数必须和真正渲染出来的行完全一致。
 * 如果两处各判一遍「isNotBlank」，日后加字段就会悄悄错拍。
 */
internal fun detailRowsOf(course: CourseEntity): List<DetailRow> =
    buildList {
        if (course.room.isNotBlank()) add(DetailRow(Icons.Filled.Place, "教室", course.room))
        if (course.teacher.isNotBlank()) add(DetailRow(Icons.Filled.Person, "教师", course.teacher))
        if (course.note.isNotBlank()) add(DetailRow(Icons.Filled.Info, "备注", course.note))
    }
