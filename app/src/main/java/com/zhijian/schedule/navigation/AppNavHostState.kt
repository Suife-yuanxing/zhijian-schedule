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
     * 课表课块级联是否已播过：与今日页 / 我的页同一套闸门，本次进程只放行一次。
     *
     * 为什么需要宿主闸门而不是只看组合生命周期：navigation-compose 的转场按 back stack
     * entry 的 id 复用内容槽，而 saveState/restoreState 恢复的 entry 还是同一个 id——
     * 在上一段转场收尾前切回来时，课表根本没离开组合，级联不会自己重播；反过来，切走
     * 够久让页面真被销毁，切回时又会从 alpha=0 重来。同一个动作两种结果，取决于转场
     * 有没有收尾，这就是「偶发」的来源。
     *
     * 为什么不保留「每次进入都重播」：那会把所有课块 alpha 归零，再按阅读顺序（先上排、
     * 上排内从左到右）逐块长回来，整轮封顶 700ms；而切页滑动只要约 230ms，读起来就是
     * 「这一页重新刷新了一遍、内容从左上角摊开」（真机抓帧：点课表后 30ms 全块不可见、
     * 90/300/500ms 才逐列浮出）。改成一次性之后与今日 / 我的完全同构：切 tab 只做位移。
     */
    private var scheduleEntrancePlayed by mutableStateOf(false)

    /** 课表这次进场要不要播课块级联：只有本次进程第一次进入时为 true */
    val playScheduleEntrance: Boolean get() = !scheduleEntrancePlayed

    /** 「我的」页分段级联的回合号：只在本次进程第一次进入时自增 */
    var profileEntranceEpoch by mutableIntStateOf(0)
        private set

    /** 从加号进课程编辑页时记下加号的位置：返回时页面要反向收回这里 */
    var fabOrigin by mutableStateOf<Rect?>(null)

    /**
     * 今日页的分段入场是否已播过。
     *
     * 今日页与课表页同一条规矩：只在第一次进入时播分段入场。它的 StaggerIn 最晚到 708ms，
     * 切页本身只要 224ms，叠在一起就是「滑完了内容还在长」，中途再切走还会闪。
     */
    var todayEntrancePlayed by mutableStateOf(false)
        private set

    /**
     * 「我的」的分段级联只在本次进程**第一次**进入时播一次，之后切回来直接落位。
     * 每次重播的话页面会先空一帧（全部区块 alpha=0）再从上往下长出来；这一页又是从右边
     * 滑入的，空帧 + 顶部先出现，读起来就是「整页从右上角打开」（连续快速切换时实测抓到了
     * 整屏只剩底色的空帧）。课表页的课块级联走同一套闸门（见 [playScheduleEntrance]）。
     */
    private var profileEntrancePlayed by mutableStateOf(false)

    fun requestScrollToTop() {
        scrollToTopTick++
    }

    /**
     * 标记课表入场已经用过：之后每次切回课表都直接落位，不再叠一层
     * 「全部课块先透明再长出来」——那一层正是「切页像刷新、内容从左上角摊开」的来源。
     *
     * 与今日页 / 我的页同一套策略（[markTodayEntrancePlayed] / [playProfileEntranceOnce]）。
     */
    fun markScheduleEntrancePlayed() {
        scheduleEntrancePlayed = true
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
