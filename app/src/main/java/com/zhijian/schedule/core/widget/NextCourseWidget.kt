package com.zhijian.schedule.core.widget

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
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.zhijian.schedule.MainActivity

class NextCourseWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadNextData(context)
        provideContent { NextContent(data) }
    }
}

private @Composable
fun NextContent(data: WidgetNextData) {
    val dark = isSystemDark(LocalContext.current)
    val palette = presetOf(data.themeId)
    val colors = if (dark) palette.dark else palette.light
    val course = data.course

    Column(
        GlanceModifier
            .fillMaxSize()
            .background(ColorProvider(colors.surface))
            .padding(14.dp)
            .clickable(actionStartActivity<MainActivity>()),
    ) {
        Text(
            text = "下一节",
            style = TextStyle(color = ColorProvider(colors.textMuted), fontSize = 12.sp),
        )
        Spacer(GlanceModifier.height(6.dp))
        if (course == null) {
            Text(
                text = "暂无课程",
                style = TextStyle(color = ColorProvider(colors.textMuted), fontSize = 13.sp),
            )
        } else {
            Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                Spacer(
                    GlanceModifier
                        .size(10.dp)
                        .background(ColorProvider(Color(course.color)))
                )
                Spacer(GlanceModifier.width(6.dp))
                Text(
                    text = course.startTime,
                    style = TextStyle(
                        color = ColorProvider(colors.text),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
            Spacer(GlanceModifier.height(4.dp))
            Text(
                text = course.name,
                style = TextStyle(
                    color = ColorProvider(colors.text),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                ),
                maxLines = 2,
            )
            if (course.room.isNotBlank()) {
                Text(
                    text = course.room,
                    style = TextStyle(color = ColorProvider(colors.textMuted), fontSize = 12.sp),
                    maxLines = 1,
                )
            }
            if (data.countdownText.isNotBlank()) {
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    text = "${data.countdownText}后开课",
                    style = TextStyle(color = ColorProvider(colors.accent), fontSize = 12.sp),
                )
            }
        }
    }
}

class NextCourseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextCourseWidget()
}
