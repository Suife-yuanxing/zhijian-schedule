package com.zhijian.schedule.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.zhijian.schedule.core.theme.CourseTheme
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.feature.course_editor.CourseEditorScreen
import com.zhijian.schedule.feature.importer.ImporterScreen
import com.zhijian.schedule.feature.jw.JwImportScreen
import com.zhijian.schedule.feature.profile.ProfileScreen
import com.zhijian.schedule.feature.schedule.ScheduleScreen
import com.zhijian.schedule.feature.settings.SettingsScreen
import com.zhijian.schedule.feature.today.TodayScreen

/**
 * 每个底部 tab 一个独立嵌套图。
 * 不能把二级页与三个 tab 平铺在同一个图里：popUpTo(start){saveState}+restoreState
 * 会把「被弹出页面」的状态挂在起始目的地键下，之后恢复课表 tab 时可能把
 * 「我的 / 今日」的页面原样恢复出来（点课表跳到我的）。嵌套图按 tab 隔离存取。
 */
object Routes {
    const val TODAY_TAB = "today_tab"
    const val SCHEDULE_TAB = "schedule_tab"
    const val PROFILE_TAB = "profile_tab"

    const val TODAY = "today"
    const val SCHEDULE = "schedule"
    const val PROFILE = "profile"

    const val SCHEDULE_IMPORTER = "schedule_importer"
    const val SCHEDULE_JW_IMPORT = "schedule_jw_import"
    const val SCHEDULE_EDITOR = "schedule_editor?courseId={courseId}&day={day}&section={section}"

    const val TODAY_EDITOR = "today_editor?courseId={courseId}&day={day}&section={section}"

    const val PROFILE_IMPORTER = "profile_importer"
    const val PROFILE_JW_IMPORT = "profile_jw_import"
    const val PROFILE_SETTINGS = "profile_settings"

    fun scheduleEditor(courseId: Long? = null, day: Int? = null, section: Int? = null): String =
        "schedule_editor?courseId=${courseId ?: -1L}&day=${day ?: -1}&section=${section ?: -1}"

    fun todayEditor(courseId: Long? = null, day: Int? = null, section: Int? = null): String =
        "today_editor?courseId=${courseId ?: -1L}&day=${day ?: -1}&section=${section ?: -1}"
}

private data class TabItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

private val editorArgs = listOf(
    navArgument("courseId") { type = NavType.LongType; defaultValue = -1L },
    navArgument("day") { type = NavType.IntType; defaultValue = -1 },
    navArgument("section") { type = NavType.IntType; defaultValue = -1 },
)

// ---------- 全局转场动效（灵动有弹性：弹簧横滑 + 淡入微缩放） ----------

/** 二级页 push：新页从右整屏滑入；旧页向左退 1/3 视差并淡出，制造纵深 */
private val pushEnter: EnterTransition =
    slideInHorizontally(animationSpec = Motion.SlideSpring, initialOffsetX = { it }) +
        fadeIn(tween(durationMillis = 220, easing = Motion.EaseOut))
private val pushExit: ExitTransition =
    slideOutHorizontally(animationSpec = Motion.SlideSpring, targetOffsetX = { -it / 3 }) +
        fadeOut(tween(durationMillis = Motion.DurFast))

/** 二级页 pop：当前页向右滑出，上一页从左侧 1/3 处滑回原位 */
private val popEnter: EnterTransition =
    slideInHorizontally(animationSpec = Motion.SlideSpring, initialOffsetX = { -it / 3 }) +
        fadeIn(tween(durationMillis = Motion.DurMedium, easing = Motion.EaseOut))
private val popExit: ExitTransition =
    slideOutHorizontally(animationSpec = Motion.SlideSpring, targetOffsetX = { it }) +
        fadeOut(tween(durationMillis = Motion.DurFast))

/** tab 之间：淡入淡出 + 1~2% 微缩放，保持安静不抢戏 */
private val tabEnter: EnterTransition =
    fadeIn(tween(durationMillis = Motion.DurMedium, easing = Motion.EaseOut)) +
        scaleIn(tween(durationMillis = Motion.DurMedium, easing = Motion.EaseOut), initialScale = 0.98f)
private val tabExit: ExitTransition =
    fadeOut(tween(durationMillis = Motion.DurFast)) +
        scaleOut(tween(durationMillis = Motion.DurFast), targetScale = 0.99f)
private val tabPopEnter: EnterTransition =
    fadeIn(tween(durationMillis = Motion.DurMedium, easing = Motion.EaseOut)) +
        scaleIn(tween(durationMillis = Motion.DurMedium, easing = Motion.EaseOut), initialScale = 0.99f)
private val tabPopExit: ExitTransition =
    fadeOut(tween(durationMillis = Motion.DurFast)) +
        scaleOut(tween(durationMillis = Motion.DurFast), targetScale = 0.98f)

private val tabRoutes = setOf(Routes.TODAY_TAB, Routes.SCHEDULE_TAB, Routes.PROFILE_TAB)

/** 目标/起始页是否属于某个 tab 根图：用于区分「tab 切换」与「进入/退出二级页」 */
private fun NavBackStackEntry.isInTabGraph(): Boolean =
    destination.hierarchy.any { node ->
        val route = node.route
        route != null && route in tabRoutes
    }

/** 底部 tab 切换：保存当前 tab 栈、恢复目标 tab 栈（标准嵌套图用法） */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

@Composable
fun AppRoot(openTodayCount: Int = 0) {
    val appViewModel: AppViewModel = hiltViewModel()
    val settings by appViewModel.settings.collectAsStateWithLifecycle()
    CourseTheme(presetId = settings.themeId, darkMode = settings.darkMode) {
        AppScaffold(openTodayCount = openTodayCount)
    }
}

@Composable
private fun AppScaffold(openTodayCount: Int) {
    val navController = rememberNavController()
    val tabs = listOf(
        TabItem(Routes.TODAY_TAB, "今日", Icons.Filled.Star),
        TabItem(Routes.SCHEDULE_TAB, "课表", Icons.Filled.DateRange),
        TabItem(Routes.PROFILE_TAB, "我的", Icons.Filled.Person),
    )
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val showBottomBar = tabs.any { tab -> currentDestination?.hierarchy?.any { it.route == tab.route } == true }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navController.navigateToTab(tab.route) },
                            icon = {
                                val iconScale by animateFloatAsState(
                                    targetValue = if (selected) 1.12f else 1f,
                                    animationSpec = Motion.PopSpring,
                                    label = "tabIconScale",
                                )
                                Icon(
                                    tab.icon,
                                    contentDescription = tab.label,
                                    modifier = Modifier.graphicsLayer {
                                        scaleX = iconScale
                                        scaleY = iconScale
                                    },
                                )
                            },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        // 外层已按系统栏 inset 偏移内容，声明为已消费，
        // 避免二级页 Scaffold/TopAppBar 再次叠加状态栏高度把标题压低
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
            NavHost(
                navController = navController,
                startDestination = Routes.SCHEDULE_TAB,
            ) {
                navigation(
                    startDestination = Routes.TODAY,
                    route = Routes.TODAY_TAB,
                    enterTransition = { tabEnter },
                    exitTransition = { if (targetState.isInTabGraph()) tabExit else pushExit },
                    popEnterTransition = { if (initialState.isInTabGraph()) tabPopEnter else popEnter },
                    popExitTransition = { tabPopExit },
                ) {
                    composable(Routes.TODAY) {
                        TodayScreen(
                            onEditCourse = { id -> navController.navigate(Routes.todayEditor(courseId = id)) },
                        )
                    }
                    composable(
                        route = Routes.TODAY_EDITOR,
                        arguments = editorArgs,
                        enterTransition = { pushEnter },
                        exitTransition = { pushExit },
                        popEnterTransition = { popEnter },
                        popExitTransition = { popExit },
                    ) {
                        CourseEditorScreen(onDone = { navController.popBackStack() })
                    }
                }

                navigation(
                    startDestination = Routes.SCHEDULE,
                    route = Routes.SCHEDULE_TAB,
                    enterTransition = { tabEnter },
                    exitTransition = { if (targetState.isInTabGraph()) tabExit else pushExit },
                    popEnterTransition = { if (initialState.isInTabGraph()) tabPopEnter else popEnter },
                    popExitTransition = { tabPopExit },
                ) {
                    composable(Routes.SCHEDULE) {
                        ScheduleScreen(
                            onAddCourse = { day, section ->
                                navController.navigate(Routes.scheduleEditor(day = day, section = section))
                            },
                            onEditCourse = { id -> navController.navigate(Routes.scheduleEditor(courseId = id)) },
                            onOpenSettings = { navController.navigateToTab(Routes.PROFILE_TAB) },
                            onOpenImport = { navController.navigate(Routes.SCHEDULE_IMPORTER) },
                        )
                    }
                    composable(
                        route = Routes.SCHEDULE_IMPORTER,
                        enterTransition = { pushEnter },
                        exitTransition = { pushExit },
                        popEnterTransition = { popEnter },
                        popExitTransition = { popExit },
                    ) {
                        ImporterScreen(
                            onBack = { navController.popBackStack() },
                            onJwImport = { navController.navigate(Routes.SCHEDULE_JW_IMPORT) },
                        )
                    }
                    // WebView 页面禁用转场动画：AndroidView 在 Compose 转场动画期间
                    // 的 z-order/裁剪不稳定，会导致白色 WebView 短暂盖住上方 UI
                    composable(
                        route = Routes.SCHEDULE_JW_IMPORT,
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        JwImportScreen(onBack = { navController.popBackStack() })
                    }
                    composable(
                        route = Routes.SCHEDULE_EDITOR,
                        arguments = editorArgs,
                        enterTransition = { pushEnter },
                        exitTransition = { pushExit },
                        popEnterTransition = { popEnter },
                        popExitTransition = { popExit },
                    ) {
                        CourseEditorScreen(onDone = { navController.popBackStack() })
                    }
                }

                navigation(
                    startDestination = Routes.PROFILE,
                    route = Routes.PROFILE_TAB,
                    enterTransition = { tabEnter },
                    exitTransition = { if (targetState.isInTabGraph()) tabExit else pushExit },
                    popEnterTransition = { if (initialState.isInTabGraph()) tabPopEnter else popEnter },
                    popExitTransition = { tabPopExit },
                ) {
                    composable(Routes.PROFILE) {
                        ProfileScreen(
                            onOpenImport = { navController.navigate(Routes.PROFILE_IMPORTER) },
                            onOpenSettings = { navController.navigate(Routes.PROFILE_SETTINGS) },
                        )
                    }
                    composable(
                        route = Routes.PROFILE_IMPORTER,
                        enterTransition = { pushEnter },
                        exitTransition = { pushExit },
                        popEnterTransition = { popEnter },
                        popExitTransition = { popExit },
                    ) {
                        ImporterScreen(
                            onBack = { navController.popBackStack() },
                            onJwImport = { navController.navigate(Routes.PROFILE_JW_IMPORT) },
                        )
                    }
                    composable(
                        route = Routes.PROFILE_JW_IMPORT,
                        enterTransition = { EnterTransition.None },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        JwImportScreen(onBack = { navController.popBackStack() })
                    }
                    composable(
                        route = Routes.PROFILE_SETTINGS,
                        enterTransition = { pushEnter },
                        exitTransition = { pushExit },
                        popEnterTransition = { popEnter },
                        popExitTransition = { popExit },
                    ) {
                        SettingsScreen(onBack = { navController.popBackStack() })
                    }
                }
            }

            // 点实时胶囊进「今日」：计数变化时切一次 tab（等首帧后再导航，确保 NavHost 已就绪）
            LaunchedEffect(openTodayCount) {
                if (openTodayCount > 0) {
                    withFrameNanos { }
                    navController.navigateToTab(Routes.TODAY_TAB)
                }
            }
        }
    }
}
