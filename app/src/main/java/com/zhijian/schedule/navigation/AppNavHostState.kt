package com.zhijian.schedule.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import com.zhijian.schedule.core.ui.RevealState
import com.zhijian.schedule.core.ui.island.IslandCollapseState
import com.zhijian.schedule.feature.profile.ProfileEntryTransitions

/**
 * 导航图与页面宿主之间共享的状态与回调。
 *
 * 这些状态原先都是 AppScaffold 里的局部变量：拆出导航图之后，若继续逐个做参数传递，
 * 导航图的入口会膨胀到二十多个参数，漏传一个也不会报错（只静默改变行为）。
 * 集中成一个持有者后，宿主负责创建一次，导航图的每个目的地只读它需要的那一项。
 */
internal class AppNavHostState(
    /** 圆形扩散导航的浮层状态：由宿主创建，导航图按需让位转场 */
    val reveal: RevealState,
    /** 浮岛收敛状态：由三个根页的纵向滚动源驱动 */
    val islandCollapse: IslandCollapseState,
    /** 「我的」入口卡 ↔ 二级页的共享容器变换 */
    val entryTransitions: ProfileEntryTransitions,
    /** 二级页持续录进的图层：返回时浮层直接重绘它（无位图读回） */
    val entryLayer: GraphicsLayer,
    /**
     * 三个根页共用的入场闸门。
     *
     * **一处持有、三页共读**是本次修复的关键：上一版是三个各自独立的布尔（课表已闸门、
     * 今日「进程内第一次进入」、我的「进程内第一次进入 + epoch」），于是每个进程的
     * 头一次切 tab 照样会播入场——读出来就是「整页从右上方打开、刷新了一遍」。
     * 现在收成一个闸门，语义只有一句话：冷启动首帧放行一次，其余一律落位。
     * 规则本体见 [RootEntrancePolicy]（纯逻辑，有单测）。
     */
    val entrance: RootEntranceGate,
) {
    /** 复点当前 tab 的回顶请求：自增即触发，页面各自消费 */
    var scrollToTopTick by mutableIntStateOf(0)
        private set

    /** 从加号进课程编辑页时记下加号的位置：返回时页面要反向收回这里 */
    var fabOrigin by mutableStateOf<Rect?>(null)

    fun requestScrollToTop() {
        scrollToTopTick++
    }
}
