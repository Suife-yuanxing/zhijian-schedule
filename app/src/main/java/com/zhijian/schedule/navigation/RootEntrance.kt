package com.zhijian.schedule.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * 根页入场闸门：决定「这一次进入某个根页，要不要播放分段入场 / 课块级联」。
 *
 * ## 规范（2026-10-30 定，见 docs/specs/2026-10-30-根页切换规范.md）
 * 三个根页（今日 / 课表 / 我的）的分段入场**只在冷启动首帧播放，整个进程只放行一次**。
 * 切 tab、二级页返回、Activity 重建之后，一律首帧直接落位，不再播。
 *
 * ## 为什么切页不能播（「从右上方打开」的根因）
 * 切 tab 已经有一整屏横向位移（[tabSlideEnter] / [tabSlideExit]，约 220ms）在说「这一页进来了」。
 * 再叠一层「从 alpha=0 按阅读顺序长出来」是同一件事说了两遍，而且两条时间轴不同步：
 * 位移是左右方向，级联是从上往下的先后次序。合起来读作**整页从右上方展开、刷新了一遍**——
 * 主人反复报的那个现象。
 *
 * 上一版只给课表补了闸门，今日与「我的」仍是「本进程第一次进入时放行一次」——
 * 而「第一次进入」恰好就是第一次切过去，于是**每个进程的头一次切 tab 照样会从右上方打开**，
 * 之后就正常。表现成「偶发」，实际是必然发生、每进程一次。这是本次确认的根因。
 *
 * ## 为什么闸门要能跨重建保存
 * 闸门原先挂在 `remember` 上，Activity 重建（字体缩放 / 语言 / 深色模式 / 分屏尺寸等未在
 * manifest 的 configChanges 里声明的配置）会把三个布尔全部清零，重建后第一次进任一根页
 * 又播一次——同样读作「刷新」。现在由 [rememberRootEntranceGate] 用 rememberSaveable 持有。
 *
 * 纯逻辑与纯状态，不碰导航库，便于 JVM 单测逐条钉住
 * （见 `app/src/test/java/com/zhijian/schedule/navigation/RootEntrancePolicyTest.kt`）。
 */
internal object RootEntrancePolicy {
    /** 参与入场闸门的根页路由（叶子路由，不是 tab 图路由——这条踩过） */
    internal val rootRoutes: Set<String> =
        setOf(
            Routes.TODAY,
            Routes.SCHEDULE,
            Routes.PROFILE,
        )

    /**
     * 这一次落定要不要播放分段入场。
     *
     * @param consumed 本次进程里冷启动首帧的入场是否已经被消费过
     * @param route 这次落定的目的地路由（二级页一律不算根页）
     */
    internal fun shouldPlay(
        consumed: Boolean,
        route: String?,
    ): Boolean = !consumed && route != null && rootRoutes.contains(route)
}

/**
 * 闸门本体：宿主创建一次，三个根页共读同一份。
 *
 * 读（[played] / [shouldPlay]）与写（[markPlayed]）分开是刻意的：在组合期写状态会让
 * 这一帧的内容与下一次重组对不上，页面拿到的 playEntrance 会当场翻面。
 */
@Stable
internal class RootEntranceGate internal constructor(
    private val state: MutableState<Boolean>,
) {
    /** 冷启动首帧的入场是否已经放过 */
    val played: Boolean get() = state.value

    /** 这次落定是否该播（只读，不消费） */
    fun shouldPlay(route: String?): Boolean = RootEntrancePolicy.shouldPlay(state.value, route)

    /** 关闸：之后任何根页都不再播入场 */
    fun markPlayed() {
        if (!state.value) state.value = true
    }
}

/** 只存布尔，重建后包回同一种对象形态 */
private val RootEntranceGateSaver: Saver<RootEntranceGate, Boolean> =
    Saver(
        save = { it.played },
        restore = { RootEntranceGate(mutableStateOf(it)) },
    )

/**
 * 宿主创建一次。用 rememberSaveable 而不是 remember：Activity 重建后闸门仍然关着，
 * 否则「重建 → 又播一遍入场」正是另一条读作「刷新」的路径。
 */
@Composable
internal fun rememberRootEntranceGate(): RootEntranceGate =
    rememberSaveable(saver = RootEntranceGateSaver) {
        RootEntranceGate(mutableStateOf(false))
    }

/** 不走组合的构造入口：单测直接喂初始状态，与 rememberSaveable 恢复出来的形态一致 */
internal fun rootEntranceGate(played: Boolean): RootEntranceGate = RootEntranceGate(mutableStateOf(played))
