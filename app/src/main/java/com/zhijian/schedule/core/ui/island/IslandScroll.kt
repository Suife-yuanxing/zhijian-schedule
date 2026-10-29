package com.zhijian.schedule.core.ui.island

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll

/**
 * 「滚回顶部」的边沿判定，只在**一次激活期间**有效。
 *
 * 为什么不写成 `if (atTop) expand()`：
 * 课表页是 HorizontalPager 套 verticalScroll，而**每一周的 grid 都有自己的一份
 * ScrollState**（各自 remember）。翻周那一刻新页是首次组合，滚动位置天然是 0，
 * 于是 `atTop == true` 一开局就成立——浮岛会被当成「用户滚回顶部」直接顶开。
 * 真机实测：浮岛收起（48dp）后横滑翻一周，底栏立刻弹回 64dp 展开态；
 * 而只做 10px 微拖（翻不了页、不新建页）时两帧完全相同，浮岛纹丝不动。
 *
 * 所以只认「从非顶部走到顶部」这一条边沿：刚激活就已在顶部不算，必须先观察
 * 到一次「不在顶部」，之后回到顶部才展开。
 */
internal class IslandTopEdgeTracker {
    /** null = 本次激活还没观察过；否则是上一次观察到的 atTop */
    private var lastAtTop: Boolean? = null

    /** 返回 true 表示「该展开浮岛」 */
    fun observe(atTop: Boolean): Boolean {
        val previous = lastAtTop
        lastAtTop = atTop
        return atTop && previous == false
    }
}

/**
 * 把一根纵向滚动源接到浮岛：挂在滚动容器的 Modifier 上即可，一行搞定。
 *
 * 做两件事：
 * 1) 观察滚动方向与距离，累计到 [state] 的收敛量（不消费手势，页面照常滚）；
 * 2) 用户真的滚回顶部时无条件展开——对应 iOS 26 minimize 行为，
 *    也是「收起来以后用户找不到底栏」这一最常见事故的唯一保险。
 *
 * [active] 表示这一页此刻是否「拥有」浮岛（pager 里只有落定的那一页算）。
 * 非活跃页既不贡献收敛量、也不触发展开：提前组合的邻页不该抢着改同一个状态。
 * active 一翻转就换一个新的边沿计数器，于是「重新激活时本来就在顶部」恒不算回顶。
 *
 * [frozen] 返回 true 时本次不改变收敛量（课表页翻周期间用）。
 */
@Composable
fun Modifier.islandScrollObserver(
    state: IslandCollapseState?,
    scroll: ScrollState,
    active: Boolean = true,
    frozen: () -> Boolean = { false },
): Modifier {
    if (state == null) return this
    val scope = rememberCoroutineScope()
    val atTop by remember(scroll) { derivedStateOf { scroll.value <= 0 } }
    val frozenNow by rememberUpdatedState(frozen)
    // 每次激活都是一轮新的观察：换新计数器，lastAtTop 归 null（见类注释）
    val edge = remember(scroll, active) { IslandTopEdgeTracker() }
    // 键里必须带 active：换回这一页时要先把当前 atTop 记成基线（不展开），
    // 之后用户再滚回顶部才算数。
    LaunchedEffect(active, atTop) {
        // 非活跃页不参与判定：提前组合的邻页手里那份 ScrollState 位置恒为 0
        if (!active) return@LaunchedEffect
        if (edge.observe(atTop)) state.expandNow(scope)
    }
    val connection =
        remember(state, scope, active) {
            state.connection(scope) { frozenNow() || !active }
        }
    return this.nestedScroll(connection)
}

/**
 * 页面顶部锚点：把滚动状态交给浮岛观察，并在需要时同步回到顶部信号。
 *
 * 信号是自增计数，靠 [lastConsumed] 去重而不是只靠 LaunchedEffect 的键：
 * 本组合项会随 pager 落定而重新进出组合，只按键的话重进时会把旧计数再执行一遍，
 * 于是「翻到这一页」莫名变成了「滚回顶部」。
 */
@Composable
fun IslandScrollToTop(
    state: IslandCollapseState?,
    scroll: ScrollState,
    signal: Int,
    active: Boolean = true,
) {
    if (state == null) return
    val scope = rememberCoroutineScope()
    var lastConsumed by remember(state) { mutableIntStateOf(signal) }
    LaunchedEffect(active, signal) {
        if (!active || signal == lastConsumed) return@LaunchedEffect
        lastConsumed = signal
        state.expandNow(scope)
        scroll.animateScrollTo(0)
    }
}
