package com.zhijian.schedule.feature.today
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.LocalClock
import com.zhijian.schedule.core.ui.StaggerIn
import com.zhijian.schedule.feature.schedule.CourseDetailSheet
import com.zhijian.schedule.core.ui.island.IslandCollapseState
import com.zhijian.schedule.core.ui.island.IslandScrollToTop
import com.zhijian.schedule.core.ui.island.islandScrollObserver
import com.zhijian.schedule.navigation.IslandContentPadding

@Composable
fun TodayScreen(
    onEditCourse: (Long) -> Unit = {},
    onOpenExams: () -> Unit = {},
    islandCollapse: IslandCollapseState? = null,
    scrollToTopTick: Int = 0,
    /**
     * 是否播放整页的分段入场。
     *
     * tab 切页已经是一整屏横滑；若每次切回今日再叠一层 0~708ms 的 fade + 上浮，
     * 页面在滑动过程里是半透明的（内容一边滑一边长出来），而"看起来快好了"的那
     * 200ms 正好是三段动画同时收尾的窗口——这时再切走，图层连同页面一起被销毁重建，
     * 观感就是闪一下。这里与「我的」页的 entranceEpoch 同一套节奏：切 tab 只做位移，
     * 入场只在本次进程第一次进入时播。（课表页不一样：它按需求每次进入都重播级联，
     * 因为课块本身是逐格涌入的，重播读起来是「又摊开一次」而不是整页半透明。）
     */
    playEntrance: Boolean = true,
    /** 首次入场已经开始：宿主据此记住「已经播过」，下次切回不再重播 */
    onEntranceShown: () -> Unit = {},
    viewModel: TodayViewModel = hiltViewModel(),
) {
    val ui by viewModel.uiState.collectAsStateWithLifecycle()
    // 冻结成本次组合的常量：回调置位后入参会变成 false，但这一轮的入场已经起播，不能中途改
    val entranceOnce = remember { playEntrance }
    LaunchedEffect(Unit) { if (entranceOnce) onEntranceShown() }
    val colors = LocalCourseColors.current
    var selectedCourse by remember { mutableStateOf<CourseEntity?>(null) }
    // 面板目标可见性：关闭动作一开始即置回 false，让行高亮与面板滑出并行恢复
    var detailSheetVisible by remember { mutableStateOf(false) }

    // 「现在」来自 AppRoot 的那根整分时钟。这里**不读 .value**：读了整页就会每分钟
    // 重组一次（改前每 15s 一次，实测静置 35s 一帧都没有，纯白烧 CPU）。
    // 只有当前课 / 下一节课的判定走 derivedStateOf——结果是课程对象，只有上/下课这种
    // 边界才会变；真正要跟着分钟变的四处（两张卡、行状态徽章、考试倒计时）自己订阅。
    val clock = LocalClock.current
    val current by remember(ui.courses) {
        derivedStateOf {
            val now = clock.value.toLocalTime()
            ui.courses.firstOrNull { item ->
                val s = item.startTime; val e = item.endTime
                s != null && e != null && !now.isBefore(s) && !now.isAfter(e)
            }
        }
    }
    val next by remember(ui.courses) {
        derivedStateOf {
            val now = clock.value.toLocalTime()
            ui.courses.firstOrNull { item -> item.startTime != null && now.isBefore(item.startTime) }
        }
    }

    val scrollState = rememberScrollState()
    // 复点「今日」回顶：由 AppRoot 的 tick 触发
    IslandScrollToTop(state = islandCollapse, scroll = scrollState, signal = scrollToTopTick)
    Column(
        Modifier
            .fillMaxSize()
            .islandScrollObserver(state = islandCollapse, scroll = scrollState)
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        TodayHeader(ui)

        Spacer(Modifier.height(18.dp))

        ui.todayHoliday?.takeIf { it.isOffDay && ui.courses.isNotEmpty() }?.let { h ->
            StaggerIn(index = 0, skip = !entranceOnce) {
                HolidayNoticeCard(h)
            }
            Spacer(Modifier.height(14.dp))
        }

        when {
            ui.courses.isEmpty() -> {
                StaggerIn(index = 0, skip = !entranceOnce) { EmptyToday(ui) }
                Spacer(Modifier.height(14.dp))
                ui.nextExam?.let { exam ->
                    StaggerIn(index = 1, skip = !entranceOnce) {
                        ExamCountdownCard(exam, clock, onClick = onOpenExams)
                    }
                    Spacer(Modifier.height(14.dp))
                }
            }
            else -> {
                current?.let { item ->
                    StaggerIn(index = 0, skip = !entranceOnce) {
                        CurrentClassCard(item, clock, sweepOnce = entranceOnce)
                    }
                    Spacer(Modifier.height(14.dp))
                }
                next?.let { item ->
                    StaggerIn(index = 1, skip = !entranceOnce) {
                        NextClassCard(item, clock)
                    }
                    Spacer(Modifier.height(18.dp))
                }
                ui.nextExam?.let { exam ->
                    StaggerIn(index = 1, skip = !entranceOnce) {
                        ExamCountdownCard(exam, clock, onClick = onOpenExams)
                    }
                    Spacer(Modifier.height(14.dp))
                }
                val groups = DayPart.entries.mapNotNull { part ->
                    val entries = ui.courses.withIndex()
                        .filter { dayPartOf(it.value.startTime) == part }
                    if (entries.isEmpty()) null else part to entries
                }
                var staggerIndex = 2
                StaggerIn(index = staggerIndex++, skip = !entranceOnce) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "今日课程",
                            style = MaterialTheme.typography.titleSmall,
                            color = colors.textMuted,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "${ui.courses.size} 节",
                            fontSize = 11.sp,
                            color = colors.textMuted.copy(alpha = 0.8f),
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
                groups.forEach { (part, entries) ->
                    StaggerIn(index = staggerIndex++, skip = !entranceOnce) {
                        DayPartHeader(part, entries.size)
                    }
                    entries.forEach { (_, item) ->
                        StaggerIn(index = staggerIndex++, skip = !entranceOnce) {
                            CourseRow(
                                item = item,
                                clock = clock,
                                isCurrent = item == current,
                                selected = detailSheetVisible && item.course.id == selectedCourse?.id,
                                onClick = {
                                    selectedCourse = item.course
                                    detailSheetVisible = true
                                },
                            )
                        }
                    }
                }
            }
        }
        // 恒定留白（同 WeekGrid）：不在组合期读收敛值，避免浮岛每帧动画都让整页重新测量
        Spacer(Modifier.height(IslandContentPadding))
    }

    selectedCourse?.let { course ->
        CourseDetailSheet(
            course = course,
            onTargetVisibilityChange = { detailSheetVisible = it },
            onDismiss = { selectedCourse = null },
            onEdit = {
                selectedCourse = null
                onEditCourse(course.id)
            },
            onDelete = {
                viewModel.deleteCourse(course.id)
                selectedCourse = null
            },
        )
    }
}
