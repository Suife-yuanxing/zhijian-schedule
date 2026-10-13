package com.zhijian.schedule.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.zhijian.schedule.MainActivity

class TodayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadTodayData(context)
        provideContent { TodayContent(data) }
    }
}

private @Composable
fun TodayContent(data: WidgetTodayData) {
    val dark = isSystemDark(LocalContext.current)
    val palette = presetOf(data.themeId)
    val colors = if (dark) palette.dark else palette.light
    val bg = ColorProvider(colors.bg)
    val text = ColorProvider(colors.text)
    val muted = ColorProvider(colors.textMuted)

    Column(
        GlanceModifier
            .fillMaxSize()
            .background(bg)
            .padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
            Text(
                text = "今天 · ${data.dayName}",
                style = TextStyle(color = text, fontSize = 14.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(GlanceModifier.width(8.dp))
            Text(
                text = data.week?.let { "第 $it 周" } ?: "假期",
                style = TextStyle(color = muted, fontSize = 12.sp),
            )
        }
        Spacer(GlanceModifier.height(8.dp))
        if (data.courses.isEmpty()) {
            Text(
                text = if (data.week == null) "不在学期周次内" else "今天没有课",
                style = TextStyle(color = muted, fontSize = 13.sp),
            )
        } else {
            data.courses.take(4).forEach { course ->
                Row(
                    GlanceModifier.fillMaxWidth().padding(vertical = 2.dp),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                ) {
                    Spacer(
                        GlanceModifier
                            .size(8.dp)
                            .background(ColorProvider(Color(course.color)))
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        text = course.startTime,
                        style = TextStyle(color = muted, fontSize = 12.sp),
                    )
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        text = course.name,
                        style = TextStyle(
                            color = if (course.isCurrent) ColorProvider(colors.accent) else text,
                            fontSize = 13.sp,
                            fontWeight = if (course.isCurrent) FontWeight.Bold else FontWeight.Medium,
                        ),
                        maxLines = 1,
                    )
                    if (course.room.isNotBlank()) {
                        Spacer(GlanceModifier.width(6.dp))
                        Text(
                            text = course.room,
                            style = TextStyle(color = muted, fontSize = 11.sp),
                            maxLines = 1,
                        )
                    }
                }
            }
            if (data.courses.size > 4) {
                Text(
                    text = "还有 ${data.courses.size - 4} 门…",
                    style = TextStyle(color = muted, fontSize = 11.sp),
                )
            }
        }
    }
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
