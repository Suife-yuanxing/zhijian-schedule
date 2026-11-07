package com.zhijian.schedule.navigation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.layer.GraphicsLayer
import com.zhijian.schedule.core.ui.RevealState
import com.zhijian.schedule.core.ui.SeedShape
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
    /**
     * 二级页的入场闸门：与 [entrance] 同一条规矩，但**按路由各记一次**。
     *
     * 根页的闸门管不到二级页：成绩 / 考试 / 设置三页原先各自在页面内部用
     * rememberSaveable 记「已播」，而那只在同一个 back stack entry 存活期内有效——
     * 返回再进就是新 entry，标记重新初始化，整页又从 alpha=0 长一遍。
     * 闸门挂在宿主这一层，才能跨 entry、跨 Activity 重建都算数。
     */
    val secondLevelEntrance: SecondLevelEntranceGate,
) {
    /** 复点当前 tab 的回顶请求：自增即触发，页面各自消费 */
    var scrollToTopTick by mutableIntStateOf(0)
        private set

    /**
     * 进课程编辑页时记下**被点控件的实测轮廓**：返回时页面要反向收回这里。
     *
     * 从「加号的位置」扩展成「上一个入口的轮廓」——空课块、空状态卡片的添加按钮都走这条，
     * 所以它**不能**假设自己一定在右下角：返回目标可能是课表正中间某一格，
     * 加号方块与这块轮廓没有必然关系。角半径也一并记下来，反向收回的最后一帧
     * 才能收成被点控件**自己的形状**，而不是一个外接圆。
     */
    var seedOrigin by mutableStateOf<SeedShape?>(null)

    /**
     * 加号「× → +」的复位信号：从编辑页回到课表时自增。
     *
     * 为什么需要它而不是让 ScheduleFab 自己看导航状态：加号活在课表页里，而课表页在
     * 返回时会重新进入组合，`remember` 的形变进度会被重置成加号——看起来像是「复位了」，
     * 但那是**新的加号**而不是**转回去的 ×**，中间还夹着反向扩散的最后几十毫秒，
     * 用户会看到 × 突然变成 + 再被圆盖住。用宿主级计数下发，页面在组合期间接住同一个
     * 进度值继续转，动作才是连续的。
     */
    var fabResetTick by mutableIntStateOf(0)
        private set

    /** 从编辑页返回课表：把加号从 × 转回 + */
    fun requestFabReset() {
        fabResetTick++
    }

    fun requestScrollToTop() {
        scrollToTopTick++
    }
}
