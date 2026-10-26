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
) {
    /** 复点当前 tab 的回顶请求：自增即触发，页面各自消费 */
    var scrollToTopTick by mutableIntStateOf(0)
        private set

    /**
     * 课表页的「入场回合」：每次真的切到课表就自增一次。
     * 为什么不能只靠组合重建撑场面：navigation-compose 的转场按 back stack entry 的 id
     * 复用内容槽，而 saveState/restoreState 恢复的 entry 还是同一个 id——在上一段转场
     * 收尾前切回来时，课表根本没离开组合，`remember` 不重跑，课块级联就静默不播了
     * （真机表现：慢慢切有入场动画，快速来回切没有）。
     */
    var scheduleEntryEpoch by mutableIntStateOf(0)
        private set

    /** 「我的」页分段级联的回合号：只在本次进程第一次进入时自增 */
    var profileEntranceEpoch by mutableIntStateOf(0)
        private set

    /** 从加号进课程编辑页时记下加号的位置：返回时页面要反向收回这里 */
    var fabOrigin by mutableStateOf<Rect?>(null)

    /**
     * 今日页的分段入场是否已播过。
     *
     * 课表页的「课块级联 + 加号弹出」不再有宿主闸门：每次进入课表都重播一次
     * （回合起点是目的地自己的组合生命周期，切走即销毁、切回即重来）。
     * 今日页仍然是「只在第一次进入时播分段入场」：它的 StaggerIn 最晚到 708ms，
     * 切页本身只要 224ms，叠在一起就是「滑完了内容还在长」，中途再切走还会闪。
     */
    var todayEntrancePlayed by mutableStateOf(false)
        private set

    /**
     * 「我的」的分段级联只在本次进程**第一次**进入时播一次，之后切回来直接落位。
     * 每次重播的话页面会先空一帧（全部区块 alpha=0）再从上往下长出来；这一页又是从右边
     * 滑入的，空帧 + 顶部先出现，读起来就是「整页从右上角打开」（连续快速切换时实测抓到了
     * 整屏只剩底色的空帧）。课表页的课块级联不设闸门，切回来会重播（见 ScheduleScreen）。
     */
    private var profileEntrancePlayed by mutableStateOf(false)

    fun requestScrollToTop() {
        scrollToTopTick++
    }

    fun onScheduleShown() {
        scheduleEntryEpoch++
    }

    /** 上方有二级页要进场时调用：本次进程只放行一次分段级联 */
    fun playProfileEntranceOnce() {
        if (!profileEntrancePlayed) {
            profileEntrancePlayed = true
            profileEntranceEpoch++
        }
    }

    /** 今日页首帧落位后调用，之后切回不再重播分段入场 */
    fun markTodayEntrancePlayed() {
        todayEntrancePlayed = true
    }
}
