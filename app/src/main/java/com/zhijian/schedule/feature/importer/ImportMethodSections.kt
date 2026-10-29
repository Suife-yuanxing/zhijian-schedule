package com.zhijian.schedule.feature.importer

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.buttonFeedback

@Composable
internal fun MethodList(
    onJwImport: () -> Unit,
    onPickFile: () -> Unit,
    onPaste: () -> Unit,
    onPickImages: () -> Unit,
    onQrTransfer: () -> Unit,
) {
    val colors = LocalCourseColors.current
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        MethodCard(
            icon = Icons.Filled.Lock,
            title = "教务系统导入",
            subtitle = "直连学校教务（正方等），账号仅在你与学校之间传递",
            highlight = true,
            onClick = onJwImport,
        )
        MethodCard(
            icon = Icons.Filled.Share,
            title = "二维码互传",
            subtitle = "扫同学分享的二维码导入课表，或生成我的分享码",
            onClick = onQrTransfer,
        )
        MethodCard(
            icon = Icons.Filled.List,
            title = "Excel / CSV 文件",
            subtitle = "表头：课程名 / 星期 / 开始节 / 结束节 / 老师 / 地点 / 周数",
            onClick = onPickFile,
        )
        MethodCard(
            icon = Icons.Filled.Edit,
            title = "粘贴课表口令 / 文本",
            subtitle = "分享口令、JSON、从 Excel 复制的表格均可",
            onClick = onPaste,
        )
        MethodCard(
            icon = Icons.Filled.Search,
            title = "截图识别",
            subtitle = "选课表截图自动识别成课程，支持多张",
            onClick = onPickImages,
        )
        MethodCard(
            icon = Icons.Filled.DateRange,
            title = "日历 (.ics) 文件",
            subtitle = "导入其他日历 App 导出的 .ics 课表文件",
            onClick = onPickFile,
        )
    }
}

@Composable
internal fun MethodCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    highlight: Boolean = false,
) {
    val colors = LocalCourseColors.current
    val pressSource = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .buttonFeedback(pressSource, pressedScale = 0.97f)
            .clip(RoundedCornerShape(14.dp))
            .background(colors.surface, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = pressSource,
                indication = LocalIndication.current,
                onClick = onClick,
            ).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (highlight) colors.accent else colors.textMuted,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = colors.text,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = colors.textMuted,
            )
        }
        if (highlight) {
            Text("推荐", color = colors.accent, fontSize = 11.sp)
        }
    }
}
