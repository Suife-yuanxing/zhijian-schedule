package com.zhijian.schedule.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 底部面板内容入场的**驱动器**：一条时间轴，所有槽位共用（节拍定义见 [SheetChoreography]）。
 *
 * 三条设计要点，都是实际踩出来的：
 *
 * 1. **起播点挂在「面板开始显示」的那一帧**，不是内容首次组合的那一帧。
 *    M3 的 ModalBottomSheet 先在屏幕外组合内容，再在内部 LaunchedEffect 里 show()。
 *    若按组合时刻起播，内容会在面板还没露头时就跑掉前一两百毫秒，
 *    用户看到的是「面板滑上来，里面早就摆好了」——错峰等于白做。
 *    这里等 sheetState 的目标值离开 Hidden 再开始计时，容器与内容同一拍出发。
 *
 * 2. **进度只在绘制阶段读**。每块内容用 [sheetArrive] 把值读进 graphicsLayer 块，
 *    每帧只失效绘制、不触发重组。若把进度写成普通 State 在组合体里读，
 *    一次面板入场会让整棵内容树重组十几轮，低端机上直接掉帧。
 *
 * 3. **系统关掉动画时一次落位**（[rememberAnimationsDisabled]），不做半截动画。
 *    这既是无障碍要求，也避免「动画被打断后元素停在 alpha=0.4」这种更糟的结果。
 */
@Stable
class SheetArrival internal constructor() {
    /** 已过去的时间（毫秒），0 即起播点 */
    internal val elapsed = mutableFloatStateOf(0f)

    /** 该槽位当前的几何进度 0..1（在绘制阶段调用） */
    internal fun progressOf(slot: Float): Float = SheetChoreography.progressAt(elapsed.floatValue, slot)

    internal fun alphaOf(
        slot: Float,
        spec: EnterSpec,
    ): Float = SheetChoreography.alphaAt(elapsed.floatValue, slot, spec)
}

/**
 * 面板入场驱动。在 ModalBottomSheet 的 content 根部调一次，再把 [sheetArrive] 分发给每块内容。
 *
 * [sheetState] 用来对齐「面板开始移动」的时刻；[lastSlot] 是最后一拍的槽位号，
 * 时间轴长度按它算（有信息卡与没有信息卡时不同，见 [SheetChoreography.actionsSlot]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberSheetArrival(
    sheetState: SheetState,
    lastSlot: Float = SheetChoreography.ACTIONS,
): SheetArrival {
    val arrival = remember { SheetArrival() }
    val reduced = rememberAnimationsDisabled()
    val total = SheetChoreography.totalMs(lastSlot)
    LaunchedEffect(sheetState, reduced, total) {
        if (reduced) {
            arrival.elapsed.floatValue = total.toFloat()
            return@LaunchedEffect
        }
        // 等面板真正开始显示：目标值离开 Hidden 即算开始。最多等 400ms 兜底——
        // Dialog 的 attach 偶发晚两三帧，不能无限等，否则内容一直停在 alpha=0。
        withTimeoutOrNull(SheetChoreography.SHOW_TIMEOUT_MS) {
            snapshotFlow { sheetState.targetValue != SheetValue.Hidden }.first { it }
        }
        // 线性推进：每个槽位自己的缓动在 progressAt 里做。
        // 这里再套一层缓动会让两种曲线相乘，前段的次序差被压扁成「一起出现」。
        animate(
            initialValue = 0f,
            targetValue = total.toFloat(),
            animationSpec = tween(durationMillis = total, easing = LinearEasing),
        ) { value, _ -> arrival.elapsed.floatValue = value }
    }
    return arrival
}

/**
 * 把一个槽位接到时间轴上：位移、缩放、透明度、过冲一次配齐。
 *
 * 全部在 graphicsLayer 块里算，**不产生重组**，也不改变测量——
 * 所以它放在背景 / 裁剪**之前**（外层）才不会被自身 bounds 裁掉位移。
 */
fun Modifier.sheetArrive(
    arrival: SheetArrival,
    slot: Float,
    spec: EnterSpec,
): Modifier =
    graphicsLayer {
        val p = arrival.progressOf(slot)
        val bounced = SheetChoreography.bounceAt(p, spec.bounce)
        alpha = arrival.alphaOf(slot, spec)
        val scale = spec.scaleFrom + (1f - spec.scaleFrom) * bounced
        scaleX = scale
        scaleY = scale
        translationY = (1f - p) * spec.riseDp.dp.toPx()
    }

/**
 * 出场：点「编辑」「删除」之后，先让内容收一下，再让宿主换屏。
 *
 * 为什么需要：这两个动作都会立刻换屏（进编辑页 / 回课表），而宿主把面板从组合里摘掉时
 * M3 不会播 hide 动画——面板是**直接消失**的。按钮刚按下去的按压反馈与内容本身
 * 会在同一帧被抹掉，读起来像「点了一下，屏幕被切走了」。
 * 现在内容先在 130ms 内收一下（缩 3%、淡 42%），再执行换屏：
 * 内容先收、容器后走，读作「这份内容被收起来了」而不是「抽屉被抽走、屏幕被切走」。
 *
 * 收得必须快：这是用户已经做完决定之后的事，任何停顿都在拖慢操作。
 * 130ms 与 [Motion.DUR_FAST] 同档，人只读得到一个「收」的余韵。
 */
@Stable
class SheetExitGate internal constructor(
    private val scope: CoroutineScope,
    private val reduced: Boolean,
) {
    /** 收起进度 0..1；只在 graphicsLayer 块里读，不参与重组 */
    internal val amount = Animatable(0f)

    /** 是否正在收：驱动内容图层，也用来挡住重复提交 */
    var leaving by mutableStateOf(false)
        private set

    /**
     * 提交一个动作：内容先收，收完再执行。
     *
     * 只认第一次提交——用户在收起过程中再点一次（双击按钮、连点编辑与删除）不会排成两段，
     * 既避免重复导航，也避免第二个动作把第一个挤掉。
     *
     * 放行时刻由 animatable 的「跑完」决定，而不是固定 delay：tween 的时长会按系统
     * 「动画时长缩放」放大，用固定 130ms 会在慢动画设置下提前切屏、画面跳一下。
     */
    fun submit(action: () -> Unit) {
        if (leaving) return
        if (reduced) {
            action()
            return
        }
        leaving = true
        scope.launch {
            try {
                amount.animateTo(
                    1f,
                    animationSpec = tween(durationMillis = SheetChoreography.DEPART_MS, easing = Motion.EaseOut),
                )
            } finally {
                // 即便被取消（面板被外部撤掉 / 页面离开）也要把动作放行一次，不能吞掉用户的点击
                leaving = false
                action()
            }
        }
    }
}

/**
 * 拿到一个换屏闸门。**必须在面板根部调**，并在把面板从组合里摘掉之前用 [SheetExitGate.submit]。
 *
 * 系统关掉动画时（[rememberAnimationsDisabled]）闸门直接放行：不做半截动画，
 * 也不让用户为一段看不见的动画多等一帧。
 */
@Composable
fun rememberSheetExitGate(): SheetExitGate {
    val scope = rememberCoroutineScope()
    val reduced = rememberAnimationsDisabled()
    return remember(scope, reduced) { SheetExitGate(scope, reduced) }
}

/**
 * 把出场接到内容上。**必须套在最外层**（包住整块内容），否则只有某一块在收。
 *
 * 与 [sheetArrive] 不冲突：两者各自是一个 graphicsLayer，叠加时缩放与透明度相乘。
 * 入场还没播完就点了编辑时，画面会在这两个值之间收口，不会跳。
 */
fun Modifier.sheetDepart(gate: SheetExitGate): Modifier =
    graphicsLayer {
        val amount = gate.amount.value
        val scale = 1f - SheetChoreography.DEPART_SCALE * amount
        scaleX = scale
        scaleY = scale
        alpha = 1f - SheetChoreography.DEPART_FADE * amount
    }
