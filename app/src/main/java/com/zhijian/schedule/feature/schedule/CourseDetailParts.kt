package com.zhijian.schedule.feature.schedule
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.CourseTone
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.Motion
import kotlinx.coroutines.delay

@Composable
internal fun HeroPill(
    text: String,
    tone: CourseTone,
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
            Modifier
                .clip(shape)
                .background(colors.surface.copy(alpha = 0.72f))
                .border(1.dp, tone.line.copy(alpha = 0.75f), shape)
                .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

@Composable
internal fun InfoRow(
    icon: ImageVector,
    label: String,
    value: String,
    tone: CourseTone,
) {
    val colors = LocalCourseColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
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

/** 弹入落点：先小后大带一点过冲，与课块的按压反馈同源 */
@Composable
internal fun popIn(delayMs: Long = 60): Float {
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(delayMs)
        arrived = true
    }
    val scale by animateFloatAsState(
        targetValue = if (arrived) 1f else 0.3f,
        animationSpec = Motion.PopSpring,
        label = "chipPopIn",
    )
    return scale
}
