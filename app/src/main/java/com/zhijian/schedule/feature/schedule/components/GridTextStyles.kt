package com.zhijian.schedule.feature.schedule.components
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import java.time.format.DateTimeFormatter

/**
 * 网格的文本令牌：表头 / 时段标题 / 时间轴 / 现在线共用同一份。
 * 令牌随组件一起拆出，是为了让「等宽数字」与「小字对比补偿」只有一处定义。
 */
internal val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 等宽数字：时间与序号在换值时宽度不跳，纵向能对齐成一条线 */
internal val TabularFigure = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")

/**
 * 9~11sp 的小字（周几、时段区间、开始时间）：textMuted 在纸本底色上只有 3.53:1，
 * 正好掉在 AA 线下；往正文色掺 30% 拉到 5:1 以上，视觉上仍然是「次要」。
 */
internal fun microText(colors: com.zhijian.schedule.core.theme.CourseColors): Color =
    lerp(colors.textMuted, colors.text, 0.3f)
