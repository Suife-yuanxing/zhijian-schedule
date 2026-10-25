package com.zhijian.schedule.feature.schedule.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.theme.GridStyles
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.theme.LocalGridStyle
import com.zhijian.schedule.core.theme.LocalIsDark
import com.zhijian.schedule.core.theme.courseTone
import com.zhijian.schedule.core.ui.HolidayColors
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.pressScale
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.feature.schedule.ScheduleUiState
import com.zhijian.schedule.navigation.IslandContentPadding
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** 课块级联入场节拍：每列 +26ms、每行 +30ms，总延迟封顶 340ms —— 与 tab 切页淡入(260ms)叠成一拍 */
private const val ENTER_COL_STEP_MS = 26L
private const val ENTER_ROW_STEP_MS = 30L
private const val ENTER_DELAY_CAP_MS = 340L

private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** 等宽数字：时间与序号在换值时宽度不跳，纵向能对齐成一条线 */
private val TabularFigure = androidx.compose.ui.text.TextStyle(fontFeatureSettings = "tnum")

/**
 * 9~11sp 的小字（周几、时段区间、开始时间）：textMuted 在纸本底色上只有 3.53:1，
 * 正好掉在 AA 线下；往正文色掺 30% 拉到 5:1 以上，视觉上仍然是「次要」。
 */
private fun microText(colors: com.zhijian.schedule.core.theme.CourseColors): Color =
    lerp(colors.textMuted, colors.text, 0.3f)

/** 表头：周几 + 日期。无课程（空状态）时不留时间轴空槽，避免左侧空白突兀 */
@Composable
fun WeekHeader(
    ui: ScheduleUiState,
    week: Int,
    visibleDays: List<Int>,
    today: java.time.LocalDate,
    alignToTimeAxis: Boolean = true,
    onDayClick: (Int) -> Unit = {},
    modifier: Modifier = Modifier,
    metrics: GridMetrics = GridMetrics.Default,
) {
    val colors = LocalCourseColors.current
    Row(
        modifier
            .fillMaxWidth()
            .height(metrics.headerHeight)
    ) {
        if (alignToTimeAxis) {
            Spacer(Modifier.width(metrics.timeAxisWidth))
        }
        visibleDays.forEach { day ->
            val date = ui.dateOf(week, day)
            val isToday = date == today
            val holiday = ui.holidays[date.toString()]
            val pressSource = remember { MutableInteractionSource() }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .pressScale(pressSource, pressedScale = 0.94f)
                    .clickable(
                        interactionSource = pressSource,
                        indication = LocalIndication.current,
                    ) { onDayClick(day) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                // 今天不用 accent 上色：accent 对底色的对比只有 2.9:1，承载 11sp 会掉到 AA 线下
                Text(
                    text = TimeUtils.dayName(day),
                    color = if (isToday) colors.text else microText(colors),
                    fontSize = 11.sp,
                    fontWeight = if (isToday) FontWeight.SemiBold else FontWeight.Normal,
                )
                // 今天用一圈 accent 环标记日期，环不承载文字，不必满足文字对比要求
                Box(
                    Modifier
                        .padding(top = 2.dp)
                        .then(if (isToday) Modifier.border(1.2.dp, colors.accent, CircleShape) else Modifier)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                ) {
                    Text(
                        text = "${date.dayOfMonth}",
                        color = colors.text,
                        fontSize = 12.sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Medium,
                    )
                }
                if (holiday != null) {
                    Text(
                        text = if (holiday.isOffDay) "休" else "班",
                        color = if (holiday.isOffDay) HolidayColors.REST else HolidayColors.WORK,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

/**
 * 周视图网格。
 *
 * 纵向结构由 [GridLayout] 决定：上午 / 下午 / 晚上三段标题 + 段间留白，
 * 段内只在「大节」之间留一条 hairline（色块制风格下一条线都不画）。
 * 课块与「现在」标记都画在同一层绝对坐标覆盖层上，因此可以跨时段、跨空隙连续存在。
 */
@Composable
fun WeekGrid(
    ui: ScheduleUiState,
    week: Int,
    visibleDays: List<Int>,
    now: LocalDateTime,
    onCourseClick: (CourseEntity) -> Unit,
    onEmptyTap: (day: Int, section: Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 弹起高亮中的课程（详情打开期间；面板一开始滑出即恢复，见 CourseDetailSheet） */
    selectedCourseId: Long? = null,
    /**
     * 是否播放课块级联入场。tab 切页已由整页横滑承担，切回课表时不再叠一层内容级联，
     * 免得与切到今日/我的不是同一段运动（详见 ScheduleScreen 的同名参数）。
     */
    playEntrance: Boolean = true,
) {
    val sections = ui.sections
    if (sections.isEmpty()) return
    val metrics = rememberGridMetrics()
    val layout = remember(sections, metrics) { GridLayout(sections, metrics) }
    val today = now.toLocalDate()
    val style = LocalGridStyle.current
    val colors = LocalCourseColors.current
    val scroll = rememberScrollState()
    // 只在真的滚起来之后才盖渐隐：静止时盖住的是「上午」标题，会把它洗淡
    val scrolled by remember { derivedStateOf { scroll.value > 4 } }
    val fade by animateFloatAsState(
        targetValue = if (scrolled) 1f else 0f,
        animationSpec = tween(Motion.DurFast),
        label = "scrollFade",
    )

    Box(modifier) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val dayWidth = (maxWidth - metrics.timeAxisWidth) / visibleDays.size

                // 背景层：时段标题 + 时间轴 + 天列
                Column(Modifier.fillMaxWidth()) {
                    layout.bands.forEachIndexed { bandIndex, band ->
                        BandHeader(band, style, metrics)
                        BandBody(
                            band = band,
                            layout = layout,
                            ui = ui,
                            week = week,
                            visibleDays = visibleDays,
                            style = style,
                            metrics = metrics,
                            onEmptyTap = onEmptyTap,
                        )
                        if (bandIndex != layout.bands.lastIndex) {
                            Spacer(Modifier.height(metrics.bandGap))
                        }
                    }
                }

                // 课块层
                Box(Modifier.fillMaxWidth().height(layout.totalHeight)) {
                    CourseOverlay(
                        ui = ui,
                        week = week,
                        visibleDays = visibleDays,
                        layout = layout,
                        dayWidth = dayWidth,
                        now = now,
                        today = today,
                        selectedCourseId = selectedCourseId,
                        metrics = metrics,
                        onCourseClick = onCourseClick,
                        playEntrance = playEntrance,
                    )
                }

                // 现在层
                Box(Modifier.fillMaxWidth().height(layout.totalHeight)) {
                    NowMarker(ui, week, now, layout, visibleDays, metrics)
                }
            }
            Spacer(Modifier.height(IslandContentPadding))
        }
        // 表头下方 22dp 渐隐：课块滚进来时是淡出，而不是被硬切一刀
        if (fade > 0.01f) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .align(Alignment.TopCenter)
                    .graphicsLayer { alpha = fade }
                    .background(
                        Brush.verticalGradient(
                            listOf(colors.bg, colors.bg.copy(alpha = 0f)),
                        )
                    )
            )
        }
    }
}

/** 时段标题：编辑式是「上午 ——— 08:20 – 11:45」，色块制是一条浅底纹条 */
@Composable
private fun BandHeader(band: GridBand, style: String, metrics: GridMetrics) {
    val colors = LocalCourseColors.current
    val guide = lerp(colors.border, colors.text, 0.18f)
    if (style == GridStyles.BLOCK) {
        // 底纹条要「看得见但不抢戏」：surfaceVariant 对纸本底色的对比只有 1.06:1，往 ink 掺 6%
        // 正好落到 1.2:1 左右，和编辑式的 hairline 同一量级
        val strip = lerp(colors.surfaceVariant, colors.text, 0.035f)
        Row(
            Modifier
                .fillMaxWidth()
                .height(metrics.bandHeadHeight)
                .background(
                    strip,
                    RoundedCornerShape(topEnd = 9.dp, bottomEnd = 9.dp),
                )
                .padding(start = 10.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = band.label,
                color = colors.text,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(text = band.timeRange, color = microText(colors), fontSize = 9.sp)
        }
    } else {
        Row(
            Modifier
                .fillMaxWidth()
                .height(metrics.bandHeadHeight)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = band.label,
                color = colors.text,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(guide.copy(alpha = 0.6f))
            )
            Spacer(Modifier.width(8.dp))
            Text(text = band.timeRange, color = microText(colors), fontSize = 9.sp)
        }
    }
}

/** 一个时段的网格体：时间轴 + 天列底纹（课块不在这里，见 CourseOverlay） */
@Composable
private fun BandBody(
    band: GridBand,
    layout: GridLayout,
    ui: ScheduleUiState,
    week: Int,
    visibleDays: List<Int>,
    style: String,
    metrics: GridMetrics,
    onEmptyTap: (day: Int, section: Int) -> Unit,
) {
    val colors = LocalCourseColors.current
    val density = LocalDensity.current
    val guide = lerp(colors.border, colors.text, 0.18f)
    val sectionPx = with(density) { metrics.sectionHeight.toPx() }
    val bandSections = ui.sections.subList(
        band.startIndex,
        (band.startIndex + band.count).coerceAtMost(ui.sections.size),
    )
    val boundaries = remember(band.startIndex, band.count) { layout.periodBoundaries(band) }

    Row(Modifier.fillMaxWidth().height(metrics.sectionHeight * band.count)) {
        // 时间轴：大节序号 + 起止时间（时段标题只给整段范围，单节结束时间必须在这里补齐）
        Column(Modifier.width(metrics.timeAxisWidth).fillMaxHeight()) {
            bandSections.groupBy { it.periodNo }.forEach { (periodNo, secs) ->
                val start = secs.firstOrNull()?.startTime.orEmpty()
                val end = secs.lastOrNull()?.endTime.orEmpty()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .height(metrics.sectionHeight * secs.size),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "$periodNo",
                        color = colors.text,
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        style = TabularFigure,
                    )
                    Text(
                        text = if (end.isNotEmpty()) "$start–$end" else start,
                        color = microText(colors),
                        fontSize = 9.sp,
                        style = TabularFigure,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
        }

        visibleDays.forEach { day ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .drawBehind {
                        // 列分隔：旧实现用的是 1.23:1 的 border，等于没画
                        drawLine(
                            guide.copy(alpha = 0.35f),
                            androidx.compose.ui.geometry.Offset(size.width - 0.5f, 0f),
                            androidx.compose.ui.geometry.Offset(size.width - 0.5f, size.height),
                            strokeWidth = 1f,
                        )
                        // 段内「大节分界」hairline；色块制不画线，用底纹条分层
                        if (style != GridStyles.BLOCK) {
                            boundaries.forEach { index ->
                                val y = index * sectionPx
                                drawLine(
                                    guide.copy(alpha = 0.55f),
                                    androidx.compose.ui.geometry.Offset(0f, y),
                                    androidx.compose.ui.geometry.Offset(size.width, y),
                                    strokeWidth = 1f,
                                )
                            }
                        }
                    }
                    .pointerInput(week, day, band.startIndex, band.count) {
                        detectTapGestures { offset ->
                            val local = (offset.y / sectionPx).toInt().coerceIn(0, band.count - 1)
                            onEmptyTap(day, band.startIndex + local + 1)
                        }
                    }
            )
        }
    }
}

/** 所有课块：按绝对坐标铺在网格之上，跨时段也不会被裁 */
@Composable
private fun CourseOverlay(
    ui: ScheduleUiState,
    week: Int,
    visibleDays: List<Int>,
    layout: GridLayout,
    dayWidth: Dp,
    now: LocalDateTime,
    today: java.time.LocalDate,
    selectedCourseId: Long?,
    metrics: GridMetrics,
    onCourseClick: (CourseEntity) -> Unit,
    playEntrance: Boolean,
) {
    val sections = ui.sections
    val preset = LocalCoursePreset.current
    val isDark = LocalIsDark.current

    visibleDays.forEachIndexed { colIndex, day ->
        val isToday = ui.dateOf(week, day) == today
        ui.coursesAt(week, day).forEach { course ->
            val startIndex = course.startSection.coerceIn(1, sections.size)
            val endIndex = course.endSection.coerceIn(startIndex, sections.size)
            val span = endIndex - startIndex + 1

            // 正在上的课：块底显示「剩 X 分」
            val startTime = TimeUtils.parseTime(sections[startIndex - 1].startTime)
            val endTime = TimeUtils.parseTime(sections[endIndex - 1].endTime)
            val remainingMin = if (
                isToday && startTime != null && endTime != null &&
                !now.toLocalTime().isBefore(startTime) && now.toLocalTime().isBefore(endTime)
            ) {
                Duration.between(now.toLocalTime(), endTime).toMinutes().toInt().coerceAtLeast(1)
            } else {
                null
            }

            Box(
                Modifier
                    .offset(
                        x = metrics.timeAxisWidth + dayWidth * colIndex + 1.dp,
                        y = layout.sectionTop(startIndex - 1) + 1.5.dp,
                    )
                    .width(dayWidth - 2.dp)
                    .height(metrics.sectionHeight * span - 3.dp)
            ) {
                CourseBlock(
                    course = course,
                    preset = preset,
                    isDark = isDark,
                    modifier = Modifier.fillMaxSize(),
                    selected = course.id == selectedCourseId,
                    // < 0 = 不播级联、直接落位（tab 切回课表时走这条）
                    entranceDelayMs = if (playEntrance) {
                        (colIndex * ENTER_COL_STEP_MS + (startIndex - 1) * ENTER_ROW_STEP_MS)
                            .coerceAtMost(ENTER_DELAY_CAP_MS)
                    } else {
                        -1L
                    },
                    remainingMin = remainingMin,
                    metrics = metrics,
                    onClick = { onCourseClick(course) },
                )
            }
        }
    }
}

/**
 * 当前时间标记（仅本周可见时）：主题色的横线 + 端点 + 时间轴里的时间胶囊。
 * 旧实现硬编码 #E5484D，且落在课间时整段消失。
 */
@Composable
private fun NowMarker(
    ui: ScheduleUiState,
    week: Int,
    now: LocalDateTime,
    layout: GridLayout,
    visibleDays: List<Int>,
    metrics: GridMetrics,
) {
    val colors = LocalCourseColors.current
    if (ui.currentWeek != week) return
    val todayDay = TimeUtils.dayOfWeek(now.toLocalDate())
    // 今天那一列没显示（关掉周末 / 调休周）时，画一条横贯全表的线会指向不存在的那天
    if (todayDay !in visibleDays) return
    if (TimeUtils.dateOfWeekDay(ui.startDate, week, todayDay) != now.toLocalDate()) return
    val slot = layout.timeSlot(now.toLocalTime()) ?: return

    // 用 accent 自己派生的「深色调」：accent 原色对底色只有 2.9:1，不能直接写字
    val accentTone = courseTone(colors.accent, LocalIsDark.current)
    val breath = rememberInfiniteTransition(label = "nowMarker")
    val pulse by breath.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "nowMarkerPulse",
    )
    val lineAlpha = (if (slot.inClass) 0.72f else 0.4f) * pulse

    // 时间轴里的时间胶囊：宽度吃满时间轴，高度随网格一起放大（字体放大后 16dp 装不下 9sp 翻倍的字）
    val box = metrics.boxScale
    Box(
        Modifier
            .offset(x = 2.dp, y = slot.y - 8.dp * box)
            .width(metrics.timeAxisWidth)
            .height(16.dp * box)
            .clip(RoundedCornerShape(8.dp * box))
            .background(accentTone.ink),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = now.toLocalTime().format(HHMM),
            color = accentTone.container,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            softWrap = false,
            style = TabularFigure,
        )
    }
    // 端点
    Box(
        Modifier
            .offset(x = metrics.timeAxisWidth - 3.dp * box, y = slot.y - 3.dp * box)
            .size(6.dp * box)
            .clip(CircleShape)
            .background(accentTone.ink.copy(alpha = pulse))
    )
    // 横线
    Box(
        Modifier
            .offset(y = slot.y - 0.75.dp * box)
            .fillMaxWidth()
            .height(1.5.dp * box)
            .padding(start = metrics.timeAxisWidth)
            .background(accentTone.ink.copy(alpha = lineAlpha))
    )
}
