package com.zhijian.schedule.feature.schedule

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.data.db.CourseEntity
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.theme.LocalIsDark
import com.zhijian.schedule.core.theme.courseTone
import com.zhijian.schedule.core.ui.RecedingBottomSheet
import com.zhijian.schedule.core.ui.SheetChoreography
import com.zhijian.schedule.core.ui.rememberSheetArrival
import com.zhijian.schedule.core.ui.rememberSheetExitGate
import com.zhijian.schedule.core.ui.sheetDepart
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop

/**
 * 课程详情面板：点课块弹出，展示这门课的时间 / 周次 / 教室 / 教师，并提供编辑、删除。
 *
 * # 入场编排（本次改动的主体）
 *
 * 顺序是「头卡 -> 色点 -> 课程名 -> 两枚 pill -> 信息卡 -> 逐行 -> 按钮」，全部挂在**同一条时间轴**上，
 * 起播点对齐面板开始显示的那一帧（节拍与理由见 SheetChoreography，驱动器见 SheetArrival）。
 *
 * 旧写法是给每块套一个 StaggerIn（各自 delay + 各自 tween），有两个代价：
 * 一是起播点是「内容首次组合」，而 M3 面板是先组合再 show()，内容会提前跑掉一两百毫秒，
 * 于是面板滑上来时内容早就摆好了，错峰等于没做；
 * 二是各块各自起协程，低端机上偶发次序颠倒。
 * 现在位移 / 缩放 / 透明度 / 过冲都在绘制阶段算，一次入场不产生任何重组。
 *
 * # 出场（点编辑 / 删除）
 *
 * 走 SheetExitGate：内容先收（130ms 缩 3% + 淡 42%），收完才真正换屏。
 * 宿主把面板从组合里摘掉时 M3 不播 hide 动画，直接让位就是「点一下屏幕被切走」；
 * 先收一下再走，读作「这份内容被收起来了」。
 *
 * # 表现细节
 *
 * - 头卡顶部叠了一道同色系高光（不用白色：白光会把课程色相洗成塑料片），
 *   大块纯色因此有「受光面」，不再是一张平纸；
 * - 色点加了同色描边，与课块选中态的描边同源——「这块颜色就是课表里那一块」；
 * - 信息行之间加了极淡分割线：三行原来只靠留白分隔，读起来像一段文字，
 *   有线之后才读作「一张卡里的三个字段」；
 * - 头卡与信息卡只缩放、不上浮：它们要读作「从面板底板上长出来」，
 *   而不是「从下面飞进来、身后留一块空底板」；文字与 pill 才给上浮。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailSheet(
    course: CourseEntity,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    /**
     * 面板「目标可见性」变化：点遮罩 / 返回 / 下滑越阈值让面板开始滑出的瞬间即为 false。
     * onDismissRequest 要等滑出动画播完才回调，不能用于提前恢复选中高亮，改盯 targetValue
     * （与 M3 淡出遮罩同一时机）。
     */
    onTargetVisibilityChange: (Boolean) -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isDark = LocalIsDark.current
    val preset = LocalCoursePreset.current
    val tone =
        remember(course.color, isDark, preset.toneBias) {
            courseTone(Color(course.color), isDark, preset.toneBias)
        }
    val detailRows = remember(course.room, course.teacher, course.note) { detailRowsOf(course) }
    val editSource = remember { MutableInteractionSource() }
    val deleteSource = remember { MutableInteractionSource() }
    // 换屏闸门：编辑 / 删除都要等内容收完再走
    val gate = rememberSheetExitGate()

    LaunchedEffect(sheetState) {
        snapshotFlow { sheetState.targetValue != SheetValue.Hidden }
            .drop(1) // 忽略订阅瞬间的初始值（此时内部 show() 还没执行）
            .distinctUntilChanged()
            .collect(onTargetVisibilityChange)
    }

    // 面板层用 RecedingBottomSheet：它替掉 M3 的静态把手，并在下拉 / 点遮罩 / 按返回时
    // 让内容同步退开（见 SheetRecede）。这一层与下面的入场编排互不冲突：
    // 入场动的是每块内容的 graphicsLayer，退开动的是包住全部内容的那一层，
    // 两者相乘得到「正在进来」与「正在离开」的连续过渡。
    RecedingBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // 拖柄用课程色：这是「课块 → 面板」唯一不靠动画的连续性手段。
        // 面板是独立窗口，真正的容器变换（课块长成面板）在 Compose 里做不了；
        // 把同一个色相放在面板最上沿，眼睛会把两块读成同一个东西的两端。
        handleTint = Color(course.color).copy(alpha = 0.85f),
    ) {
        // 一条时间轴驱动全部内容；最后一拍随「有没有信息卡」变化，
        // 没有信息卡时按钮提前跟上，不留一段空等（见 SheetChoreography.actionsSlot）
        val arrival =
            rememberSheetArrival(
                sheetState = sheetState,
                lastSlot = SheetChoreography.actionsSlot(detailRows.isNotEmpty()),
            )
        // 收起套在最外层：整块内容一起退，而不是只有某个子块在缩
        Box(Modifier.fillMaxWidth().sheetDepart(gate)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
            ) {
                SheetHeroCard(course = course, tone = tone, isDark = isDark, arrival = arrival)
                if (detailRows.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    SheetInfoCard(rows = detailRows, tone = tone, arrival = arrival)
                }
                Spacer(Modifier.height(if (detailRows.isEmpty()) 16.dp else 20.dp))
                SheetActionRow(
                    arrival = arrival,
                    hasDetails = detailRows.isNotEmpty(),
                    editSource = editSource,
                    deleteSource = deleteSource,
                    onEdit = { gate.submit(onEdit) },
                    onDelete = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        gate.submit(onDelete)
                    },
                )
            }
        }
    }
}
