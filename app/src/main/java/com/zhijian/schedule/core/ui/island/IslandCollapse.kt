package com.zhijian.schedule.core.ui.island

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 浮岛收敛状态：0 = 完全展开（64dp），1 = 完全收起（48dp）。
 *
 * 设计依据（业界做法）：
 * - iOS 26 Liquid Glass 的 tab bar 用 TabBarMinimizeBehavior.onScrollDown，
 *   向下滚就把标签栏收起来，点任一条目或滚回顶部恢复；
 * - Material 3 Expressive 的 HorizontalFloatingToolbar 用
 *   floatingToolbarVerticalNestedScroll 驱动收展，同样由滚动容器而非定时器触发。
 *
 * 本项目照这个模型实现，但有两条自己的硬约束：
 * 1) **回顶必须强制展开**——否则用户在「今日」页滑到底就再也看不见底栏；
 * 2) **横向翻周期间冻结**——课表页是 HorizontalPager 套 verticalScroll，
 *    横向手势与纵向收起会在边界打架，翻周动画中不改变收敛值。
 */
@Stable
class IslandCollapseState {

    /** 目标值（0 展开 / 1 收起），滚动直接写这里 */
    var fraction by mutableFloatStateOf(0f)
        private set

    /** 浮岛渲染用的平滑值：跟随 fraction 走弹簧，避免逐帧硬跳 */
    val animated = Animatable(0f)

    /** 是否处于「展开」目标态（标签该不该显示） */
    val expanded: Boolean get() = fraction < 0.5f

    private val mutex = MutatorMutex()

    /** 绑定的协程作用域：由 [rememberIslandCollapseState] 注入，供外部无参调用 expand()/collapse() */
    internal var scope: CoroutineScope? = null

    /** 向下滚过这个距离开始收（12px 起步，太灵敏会让轻微回弹误触） */
    private val collapseTriggerPx = 12f

    /** 向上滚过这个距离开始展（8px，比收起更灵敏：露出信息比藏起来更急） */
    private val expandTriggerPx = 8f

    /** 收满所需的滚动行程参考值（约 160dp，各种密度下都是「滚一小段」的手感） */
    private val fullTravelPx = 160f

    internal fun setTarget(value: Float, scope: CoroutineScope) {
        val v = value.coerceIn(0f, 1f)
        if (v == fraction) return
        fraction = v
        scope.launch {
            mutex.mutate {
                animated.animateTo(
                    targetValue = v,
                    animationSpec = spring(dampingRatio = 0.85f, stiffness = 380f),
                )
            }
        }
    }

    /** 回到顶部：无条件展开（对应 iOS「scrolling to the top of the view」） */
    internal fun expandNow(scope: CoroutineScope) = setTarget(0f, scope)

    /** 外部（浮岛复点、页面切回）请求展开；作用域未绑定时不动作，不会抛 */
    fun expand() = scope?.let { setTarget(0f, it) }

    /** 外部请求收起（进入二级页等需要腾空间时） */
    fun collapse() = scope?.let { setTarget(1f, it) }

    /**
     * 把一个纵向滚动源接到浮岛：挂在滚动容器的 Modifier.nestedScroll(...) 上。
     *
     * 只观察、不消费（返回值恒为 Zero），所以页面照常滚动；
     * 收敛量按「手指走过的距离」累计，滚到顶/底时由调用方调用 [expandNow] 归位。
     */
    fun connection(
        scope: CoroutineScope,
        frozen: () -> Boolean = { false },
    ): NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput) return Offset.Zero
            if (frozen()) return Offset.Zero
            val dy = available.y
            if (dy < 0f && -dy > collapseTriggerPx) {
                // 向下滚（内容上移）：收起。按行程比例累加，不是一滚就到底
                setTarget(fraction + (-dy / fullTravelPx), scope)
            } else if (dy > 0f && dy > expandTriggerPx) {
                setTarget(fraction - (dy / fullTravelPx), scope)
            }
            return Offset.Zero
        }
    }
}

@Composable
fun rememberIslandCollapseState(): IslandCollapseState {
    val state = remember { IslandCollapseState() }
    val scope = rememberCoroutineScope()
    // 作用域绑定要在组合期完成，供复点回顶这类无参调用使用
    state.scope = scope
    // 首帧把平滑值对齐到目标（0），避免动画从 1 起步闪一下
    LaunchedEffect(state) { state.animated.snapTo(0f) }
    return state
}
