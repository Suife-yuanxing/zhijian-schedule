package com.zhijian.schedule.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.feature.profile.PROFILE_ENTRY_HOLD_MS

// ---------- 全局转场动效（灵动有弹性：弹簧横滑 + 淡入微缩放） ----------

/** 二级页 push：新页从右整屏滑入；旧页向左退 1/3 视差并淡出，制造纵深 */
internal val pushEnter: EnterTransition =
    slideInHorizontally(animationSpec = Motion.SlideSpring, initialOffsetX = { it }) +
        fadeIn(tween(durationMillis = 220, easing = Motion.EaseOut))
internal val pushExit: ExitTransition =
    slideOutHorizontally(animationSpec = Motion.SlideSpring, targetOffsetX = { -it / 3 }) +
        fadeOut(tween(durationMillis = Motion.DUR_FAST))

/** 二级页 pop：当前页向右滑出，上一页从左侧 1/3 处滑回原位 */
internal val popEnter: EnterTransition =
    slideInHorizontally(animationSpec = Motion.SlideSpring, initialOffsetX = { -it / 3 }) +
        fadeIn(tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut))
internal val popExit: ExitTransition =
    slideOutHorizontally(animationSpec = Motion.SlideSpring, targetOffsetX = { it }) +
        fadeOut(tween(durationMillis = Motion.DUR_FAST))

/** tab 之间：淡入淡出 + 1~2% 微缩放，保持安静不抢戏 */
internal val tabEnter: EnterTransition =
    fadeIn(tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut)) +
        scaleIn(tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut), initialScale = 0.98f)
internal val tabExit: ExitTransition =
    fadeOut(tween(durationMillis = Motion.DUR_FAST)) +
        scaleOut(tween(durationMillis = Motion.DUR_FAST), targetScale = 0.99f)
internal val tabPopEnter: EnterTransition =
    fadeIn(tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut)) +
        scaleIn(tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut), initialScale = 0.99f)
internal val tabPopExit: ExitTransition =
    fadeOut(tween(durationMillis = Motion.DUR_FAST)) +
        scaleOut(tween(durationMillis = Motion.DUR_FAST), targetScale = 0.98f)

/**
 * 根 tab 按底栏顺序做方向性切换：往右切，新页从右整页滑入、旧页向左整页滑出；
 * 往左切反向。页根都铺了不透明底色，滑动时不会透出旧页卡片。
 *
 * 两条约束都是被真机复现钉住的（连点底栏时才出问题）：
 *
 * 1) **旧页的滑出距离必须等于新页的滑入距离**。曾经旧页只退 1/4，而
 *    AnimatedContent 是在「退出转场自己播完」时就把旧页content 摘掉的——
 *    270px 的弹簧比 1080px 的先收尾，旧页被摘走时新页还差几十像素没盖过来，
 *    左边缘会硬切一下（实测「今日→课表」会看到一条瞬间消失的旧页）。
 *    两边同距同弹簧 ⇒ 同时收尾，且两页edges 始终相接，任何一帧都是满屏。
 *
 * 2) **这条转场不能碰 alpha**。旧版旧页带 fadeOut(150ms)、新页不带 fadeIn：
 *    快速连点打断转场时，被重新指回目标的那一页 alpha 停在 0.1 左右，
 *    而入场转场里没有 alpha 动画去把它拉回 1——连续快速切换实测能抓到
 *    整页只剩 ~10% 不透明度的「幽灵帧」。两页都只做位移，alpha 恒为 1，
 *    打断多少次都不会留下半透明的页面。
 */
internal fun NavBackStackEntry.tabIndex(): Int? =
    when {
        destination.hierarchy.any { it.route == Routes.TODAY_TAB } -> 0
        destination.hierarchy.any { it.route == Routes.SCHEDULE_TAB } -> 1
        destination.hierarchy.any { it.route == Routes.PROFILE_TAB } -> 2
        else -> null
    }

internal fun NavBackStackEntry.isRootTab(): Boolean =
    destination.route == Routes.TODAY ||
        destination.route == Routes.SCHEDULE ||
        destination.route == Routes.PROFILE

internal fun tabSwitchForward(
    initialState: NavBackStackEntry,
    targetState: NavBackStackEntry,
): Boolean {
    val from = initialState.tabIndex()
    val to = targetState.tabIndex()
    return from != null && to != null && to > from
}

internal fun tabSlideEnter(
    initialState: NavBackStackEntry,
    targetState: NavBackStackEntry,
): EnterTransition {
    val fromRight = tabSwitchForward(initialState, targetState)
    return slideInHorizontally(
        animationSpec = Motion.SlideSpring,
        initialOffsetX = { if (fromRight) it else -it },
    )
}

internal fun tabSlideExit(
    initialState: NavBackStackEntry,
    targetState: NavBackStackEntry,
): ExitTransition {
    val toLeft = tabSwitchForward(initialState, targetState)
    return slideOutHorizontally(
        animationSpec = Motion.SlideSpring,
        targetOffsetX = { if (toLeft) -it else it },
    )
}

// ---------- 「我的」入口卡 ↔ 二级页：共享容器变换 ----------

/** 目的地是否属于「我的」入口卡对应的二级页（这些页不参与整页横滑） */
internal fun isProfileEntryRoute(route: String?): Boolean = route in profileEntryRoutes

internal val profileEntryRoutes =
    setOf(
        Routes.PROFILE_IMPORTER,
        Routes.PROFILE_GRADES,
        Routes.PROFILE_EXAMS,
        Routes.PROFILE_SETTINGS,
    )

/**
 * 进入二级页时「我的」页保持原位，并延后销毁，给实时展开/中途收回当背景。
 * 位移只留 1px（约 0.5dp，肉眼不可见）：位移为 0 会被 Compose 判定为零距离动画
 * 直接完成，「我的」页会立刻销毁，展开与收回就只剩空底色。
 */
internal val profileEntryHoldEnter: EnterTransition =
    slideInHorizontally(
        animationSpec = tween(PROFILE_ENTRY_HOLD_MS, easing = Motion.EaseOut),
        initialOffsetX = { -1 },
    )
internal val profileEntryHoldExit: ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(PROFILE_ENTRY_HOLD_MS, easing = Motion.EaseOut),
        targetOffsetX = { -1 },
    )

internal val tabRoutes = setOf(Routes.TODAY_TAB, Routes.SCHEDULE_TAB, Routes.PROFILE_TAB)

/** 目标/起始页是否属于某个 tab 根图：用于区分「tab 切换」与「进入/退出二级页」 */
internal fun NavBackStackEntry.isInTabGraph(): Boolean =
    destination.hierarchy.any { node ->
        val route = node.route
        route != null && route in tabRoutes
    }

/**
 * 底部 tab 切换：保存当前 tab 栈、恢复目标 tab 栈。
 *
 * popUpTo 的目标必须是**根图 id**，不能用 graph.findStartDestination()：
 * 那个扩展会一路递归到叶子（= today，today_tab 里的第一页），而第一次切换时
 * saveState 已经把 today 从返回栈上摘走；之后再切 tab，popUpTo 找不到目标就
 * 静默失败（NavController 只打一行 "Ignoring popBackStack"），于是每切一次就往
 * 返回栈里**追加**一份 tab 图。真机实测（PKG110 / v1.0.12）：今日→课表→我的，
 * 按一次返回落回课表、再按落回今日、第三下才退出——返回键变成了「逐个 tab 倒带」。
 * 连点 tab 时栈会无限增长，NavHost 每次导航要维护的 entry 越来越多，转场首帧的
 * 重组预算被历史页面吃掉；tabIndex() / isRootTab() 也会在同一路由的多份 entry 上
 * 得到重复判定，偶发挑错转场。
 */
internal fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
