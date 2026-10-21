package com.zhijian.schedule.navigation


import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
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
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.glass.glassBackdropSource
import com.zhijian.schedule.core.ui.glass.rememberGlassBackdrop
import com.zhijian.schedule.core.ui.glass.rememberGlassClock
import com.zhijian.schedule.core.ui.glass.rememberGlassTilt
import com.zhijian.schedule.feature.course_editor.CourseEditorScreen
import com.zhijian.schedule.feature.grades.GradesScreen
import com.zhijian.schedule.feature.importer.ImporterScreen
import com.zhijian.schedule.feature.jw.JwImportScreen
import com.zhijian.schedule.feature.profile.PROFILE_ENTRY_HOLD_MS
import com.zhijian.schedule.feature.profile.ProfileEntryExitOverlay
import com.zhijian.schedule.feature.profile.ProfileEntryKeys
import com.zhijian.schedule.feature.profile.ProfileEntryPage
import com.zhijian.schedule.feature.profile.ProfileEntryTransitions
import com.zhijian.schedule.feature.profile.ProfileScreen
import com.zhijian.schedule.feature.schedule.ScheduleScreen
import com.zhijian.schedule.feature.settings.SettingsScreen
import com.zhijian.schedule.feature.today.TodayScreen
import com.zhijian.schedule.feature.update.UpdateDialog
import com.zhijian.schedule.feature.update.UpdatePromptViewModel

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
    const val PROFILE_GRADES = "profile_grades?entryKey={entryKey}"

    fun profileGrades(entryKey: String = ProfileEntryKeys.GRADES): String =
        "profile_grades?entryKey=$entryKey"

    fun scheduleEditor(courseId: Long? = null, day: Int? = null, section: Int? = null): String =
        "schedule_editor?courseId=${courseId ?: -1L}&day=${day ?: -1}&section=${section ?: -1}"

    fun todayEditor(courseId: Long? = null, day: Int? = null, section: Int? = null): String =
        "today_editor?courseId=${courseId ?: -1L}&day=${day ?: -1}&section=${section ?: -1}"
}

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

/**
 * 根 tab 按底栏顺序做方向性切换：往右切，新页从右整页滑入、旧页向左退 1/4；
 * 往左切反向。页根都铺了不透明底色，滑动时不会透出旧页卡片。
 */
private fun NavBackStackEntry.tabIndex(): Int? = when {
    destination.hierarchy.any { it.route == Routes.TODAY_TAB } -> 0
    destination.hierarchy.any { it.route == Routes.SCHEDULE_TAB } -> 1
    destination.hierarchy.any { it.route == Routes.PROFILE_TAB } -> 2
    else -> null
}

private fun NavBackStackEntry.isRootTab(): Boolean =
    destination.route == Routes.TODAY ||
        destination.route == Routes.SCHEDULE ||
        destination.route == Routes.PROFILE

private fun tabSwitchForward(
    initialState: NavBackStackEntry,
    targetState: NavBackStackEntry,
): Boolean {
    val from = initialState.tabIndex()
    val to = targetState.tabIndex()
    return from != null && to != null && to > from
}

private fun tabSlideEnter(
    initialState: NavBackStackEntry,
    targetState: NavBackStackEntry,
): EnterTransition {
    val fromRight = tabSwitchForward(initialState, targetState)
    return slideInHorizontally(
        animationSpec = Motion.SlideSpring,
        initialOffsetX = { if (fromRight) it else -it },
    )
}

private fun tabSlideExit(
    initialState: NavBackStackEntry,
    targetState: NavBackStackEntry,
): ExitTransition {
    val toLeft = tabSwitchForward(initialState, targetState)
    return slideOutHorizontally(
        animationSpec = Motion.SlideSpring,
        targetOffsetX = { if (toLeft) -it / 4 else it / 4 },
    ) + fadeOut(tween(durationMillis = Motion.DurFast, easing = Motion.EaseOut))
}

// ---------- 「我的」入口卡 ↔ 二级页：共享容器变换 ----------

private val profileEntryRoutes = setOf(
    Routes.PROFILE_IMPORTER,
    Routes.PROFILE_GRADES,
    Routes.PROFILE_SETTINGS,
)

/**
 * 进入二级页时「我的」页保持原位，并延后销毁，给实时展开/中途收回当背景。
 * 位移只留 1px（约 0.5dp，肉眼不可见）：位移为 0 会被 Compose 判定为零距离动画
 * 直接完成，「我的」页会立刻销毁，展开与收回就只剩空底色。
 */
private val profileEntryHoldEnter: EnterTransition =
    slideInHorizontally(
        animationSpec = tween(PROFILE_ENTRY_HOLD_MS, easing = Motion.EaseOut),
        initialOffsetX = { -1 },
    )
private val profileEntryHoldExit: ExitTransition =
    slideOutHorizontally(
        animationSpec = tween(PROFILE_ENTRY_HOLD_MS, easing = Motion.EaseOut),
        targetOffsetX = { -1 },
    )

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
fun AppRoot(openTodayCount: Int = 0, openJwImportCount: Int = 0) {
    val appViewModel: AppViewModel = hiltViewModel()
    val updatePrompt: UpdatePromptViewModel = hiltViewModel()
    val settings by appViewModel.settings.collectAsStateWithLifecycle()
    CourseTheme(
        presetId = settings.themeId,
        darkMode = settings.darkMode,
        gridStyle = settings.gridStyle,
    ) {
        AppScaffold(openTodayCount = openTodayCount, openJwImportCount = openJwImportCount)

        // 进入应用自动检查更新：静默检查，发现新版本才弹更新窗口（失败/已最新不打扰）
        LaunchedEffect(Unit) { updatePrompt.checkOnLaunch() }
        if (updatePrompt.promptVersion != null) {
            UpdateDialog(onDismiss = { updatePrompt.dismiss() })
        }
    }
}

@Composable
private fun AppScaffold(
    openTodayCount: Int,
    openJwImportCount: Int,
) {
    val navController = rememberNavController()
    val entryTransitions = remember { ProfileEntryTransitions() }
    // 每次从其他 tab 进入「我的」自增，触发该页重播分段级联；二级页返回不自增
    var profileEntranceEpoch by remember { mutableIntStateOf(0) }
    // 二级页持续录进这个图层，返回时浮层直接重绘它（无位图读回）
    val entryLayer = rememberGraphicsLayer()
    val colors = LocalCourseColors.current
    val tabs = listOf(
        TabItem(Routes.TODAY_TAB, Routes.TODAY, "今日", Icons.Filled.Star, Icons.Outlined.Star),
        TabItem(Routes.SCHEDULE_TAB, Routes.SCHEDULE, "课表", Icons.Filled.DateRange, Icons.Outlined.DateRange),
        TabItem(Routes.PROFILE_TAB, Routes.PROFILE, "我的", Icons.Filled.Person, Icons.Outlined.Person),
    )
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // 浮岛只在三个 tab 根页显示；二级页隐藏（内容也不再为其预留空间）
    val showBottomBar = tabs.any { tab -> tab.rootRoute == currentDestination?.route }
    // 玻璃主题：把根内容录制成可折射背景图层（绘制指令级复用，零截屏；其余主题返回 null 零开销）。
    // 时钟由这里统一持有：录制层与浮岛共用同一拍，折射背景才不会落后于页面
    val glassActive = LocalCoursePreset.current.glass != null
    val glassClock = if (glassActive) rememberGlassClock() else null
    // 倾角信号：驱动玻璃表面的镜面高光随重力方向滑动（仅玻璃主题下注册传感器）
    val glassTilt = if (glassActive) rememberGlassTilt() else null
    val backdrop = rememberGlassBackdrop(enabled = glassActive)

    Scaffold { padding ->
        // 外层已按系统栏 inset 偏移内容，声明为已消费，
        // 避免二级页 Scaffold/TopAppBar 再次叠加状态栏高度把标题压低
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding)
                .onGloballyPositioned { entryTransitions.updateContentBounds(it.boundsInWindow()) }
        ) {
            NavHost(
                navController = navController,
                startDestination = Routes.SCHEDULE_TAB,
                modifier = Modifier
                    .glassBackdropSource(
                        backdrop = backdrop,
                        clock = glassClock,
                        enabled = showBottomBar,
                        baseColor = colors.bg,
                    )
                    .onGloballyPositioned { backdrop?.onContentPositioned(it.boundsInWindow().topLeft) },
            ) {
                navigation(
                    startDestination = Routes.TODAY,
                    route = Routes.TODAY_TAB,
                    enterTransition = {
                        if (targetState.isRootTab() && initialState.isInTabGraph()) {
                            tabSlideEnter(initialState, targetState)
                        } else {
                            tabEnter
                        }
                    },
                    exitTransition = {
                        when {
                            targetState.isRootTab() -> tabSlideExit(initialState, targetState)
                            targetState.isInTabGraph() -> tabExit
                            else -> pushExit
                        }
                    },
                    popEnterTransition = { if (initialState.isInTabGraph()) tabPopEnter else popEnter },
                    popExitTransition = { tabPopExit },
                ) {
                    composable(
                        route = Routes.TODAY,
                        enterTransition = {
                            if (initialState.isInTabGraph()) {
                                tabSlideEnter(initialState, targetState)
                            } else {
                                tabEnter
                            }
                        },
                        exitTransition = {
                            when {
                                targetState.isRootTab() -> tabSlideExit(initialState, targetState)
                                targetState.isInTabGraph() -> tabExit
                                else -> pushExit
                            }
                        },
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(colors.bg),
                        ) {
                            TodayScreen(
                                onEditCourse = { id -> navController.navigate(Routes.todayEditor(courseId = id)) },
                            )
                        }
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
                    enterTransition = {
                        if (targetState.isRootTab() && initialState.isInTabGraph()) {
                            tabSlideEnter(initialState, targetState)
                        } else {
                            tabEnter
                        }
                    },
                    exitTransition = {
                        when {
                            targetState.isRootTab() -> tabSlideExit(initialState, targetState)
                            targetState.isInTabGraph() -> tabExit
                            else -> pushExit
                        }
                    },
                    popEnterTransition = { if (initialState.isInTabGraph()) tabPopEnter else popEnter },
                    popExitTransition = { tabPopExit },
                ) {
                    composable(
                        route = Routes.SCHEDULE,
                        enterTransition = {
                            if (initialState.isInTabGraph()) {
                                tabSlideEnter(initialState, targetState)
                            } else {
                                tabEnter
                            }
                        },
                        exitTransition = {
                            when {
                                targetState.isRootTab() -> tabSlideExit(initialState, targetState)
                                targetState.isInTabGraph() -> tabExit
                                else -> pushExit
                            }
                        },
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(colors.bg),
                        ) {
                            ScheduleScreen(
                                onAddCourse = { day, section ->
                                    navController.navigate(Routes.scheduleEditor(day = day, section = section))
                                },
                                onEditCourse = { id -> navController.navigate(Routes.scheduleEditor(courseId = id)) },
                                onOpenSettings = {
                                    profileEntranceEpoch++
                                    navController.navigateToTab(Routes.PROFILE_TAB)
                                },
                                onOpenImport = { navController.navigate(Routes.SCHEDULE_IMPORTER) },
                            )
                        }
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
                    // 入口卡二级页：图上这一层也不能淡入淡出，否则包裹层会盖掉
                    // 实时展开卡片所需的「我的」页背景
                    enterTransition = {
                        when {
                            targetState.destination.route in profileEntryRoutes -> profileEntryHoldEnter
                            targetState.isRootTab() && initialState.isInTabGraph() ->
                                tabSlideEnter(initialState, targetState)
                            else -> tabEnter
                        }
                    },
                    exitTransition = {
                        when {
                            targetState.destination.route in profileEntryRoutes -> profileEntryHoldExit
                            targetState.isRootTab() -> tabSlideExit(initialState, targetState)
                            targetState.isInTabGraph() -> tabExit
                            else -> pushExit
                        }
                    },
                    popEnterTransition = {
                        if (initialState.destination.route in profileEntryRoutes) {
                            EnterTransition.None
                        } else if (initialState.isInTabGraph()) {
                            tabPopEnter
                        } else {
                            popEnter
                        }
                    },
                    popExitTransition = {
                        if (initialState.destination.route in profileEntryRoutes) {
                            ExitTransition.None
                        } else {
                            tabPopExit
                        }
                    },
                ) {
                    composable(
                        route = Routes.PROFILE,
                        enterTransition = {
                            if (initialState.isInTabGraph()) {
                                tabSlideEnter(initialState, targetState)
                            } else {
                                tabEnter
                            }
                        },
                        // 进出入口卡对应的二级页时，我的页保持原位：卡片是共享元素锚点，
                        // 若整页横滑会让收回落点跟着漂移。
                        exitTransition = {
                            when {
                                targetState.destination.route in profileEntryRoutes ->
                                    profileEntryHoldExit
                                targetState.isRootTab() -> tabSlideExit(initialState, targetState)
                                targetState.isInTabGraph() -> tabExit
                                else -> pushExit
                            }
                        },
                        popEnterTransition = {
                            if (initialState.destination.route in profileEntryRoutes) {
                                EnterTransition.None
                            } else if (initialState.isInTabGraph()) {
                                tabPopEnter
                            } else {
                                popEnter
                            }
                        },
                        popExitTransition = { tabPopExit },
                    ) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(colors.bg),
                        ) {
                            ProfileScreen(
                                entryTransitions = entryTransitions,
                                entranceEpoch = profileEntranceEpoch,
                                onOpenImport = {
                                    navController.navigate(Routes.PROFILE_IMPORTER) { launchSingleTop = true }
                                },
                                onOpenSettings = {
                                    navController.navigate(Routes.PROFILE_SETTINGS) { launchSingleTop = true }
                                },
                                onOpenGrades = {
                                    navController.navigate(Routes.profileGrades(ProfileEntryKeys.GRADES)) {
                                        launchSingleTop = true
                                    }
                                },
                                onOpenGradesFromSummary = {
                                    navController.navigate(Routes.profileGrades(ProfileEntryKeys.GRADES_GPA)) {
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }
                    }
                    composable(
                        route = Routes.PROFILE_IMPORTER,
                        enterTransition = { profileEntryHoldEnter },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        ProfileEntryPage(
                            entryKey = ProfileEntryKeys.IMPORT,
                            transitions = entryTransitions,
                            captureLayer = entryLayer,
                            isTop = currentDestination?.route == Routes.PROFILE_IMPORTER,
                            onPop = { navController.popBackStack() },
                        ) { handleBack ->
                            ImporterScreen(
                                onBack = handleBack,
                                onJwImport = { navController.navigate(Routes.PROFILE_JW_IMPORT) },
                            )
                        }
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
                        enterTransition = { profileEntryHoldEnter },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        ProfileEntryPage(
                            entryKey = ProfileEntryKeys.SETTINGS,
                            transitions = entryTransitions,
                            captureLayer = entryLayer,
                            isTop = currentDestination?.route == Routes.PROFILE_SETTINGS,
                            onPop = { navController.popBackStack() },
                        ) { handleBack ->
                            SettingsScreen(onBack = handleBack)
                        }
                    }
                    composable(
                        route = Routes.PROFILE_GRADES,
                        arguments = listOf(
                            navArgument("entryKey") {
                                type = NavType.StringType
                                defaultValue = ProfileEntryKeys.GRADES
                            },
                        ),
                        enterTransition = { profileEntryHoldEnter },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) { backStackEntry ->
                        val entryKey = backStackEntry.arguments?.getString("entryKey")
                            ?.takeIf {
                                it == ProfileEntryKeys.GRADES ||
                                    it == ProfileEntryKeys.GRADES_GPA
                            }
                            ?: ProfileEntryKeys.GRADES
                        ProfileEntryPage(
                            entryKey = entryKey,
                            transitions = entryTransitions,
                            captureLayer = entryLayer,
                            isTop = currentDestination?.route == Routes.PROFILE_GRADES,
                            onPop = { navController.popBackStack() },
                        ) { handleBack ->
                            GradesScreen(
                                onBack = handleBack,
                                onOpenJwImport = { navController.navigate(Routes.PROFILE_JW_IMPORT) },
                            )
                        }
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

            // 点同步失败通知直达「教务导入」：切到我的 tab 再推入导入页（WebView 自动走 SSO 恢复/登录）
            LaunchedEffect(openJwImportCount) {
                if (openJwImportCount > 0) {
                    withFrameNanos { }
                    navController.navigateToTab(Routes.PROFILE_TAB)
                    navController.navigate(Routes.PROFILE_JW_IMPORT) { launchSingleTop = true }
                }
            }

            // 浮岛悬浮于内容之上：仅根页显示，进入二级页时滑出/返回时滑入（与页面 push/pop 同一弹簧）。
            // 不参与内容布局（页面按各自的 IslandContentPadding 让位），避免转场期间内容随其变形。
            AnimatedVisibility(
                visible = showBottomBar,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = slideInVertically(animationSpec = Motion.SlideSpring, initialOffsetY = { it }) +
                    fadeIn(tween(durationMillis = Motion.DurMedium, easing = Motion.EaseOut)),
                exit = slideOutVertically(animationSpec = Motion.SlideSpring, targetOffsetY = { it }) +
                    fadeOut(tween(durationMillis = Motion.DurFast)),
            ) {
                FloatingIslandBar(
                    selectedIndex = tabs.indexOfFirst { tab ->
                        currentDestination?.hierarchy?.any { it.route == tab.route } == true
                    },
                    itemCount = tabs.size,
                    clock = glassClock,
                    tilt = glassTilt,
                    backdrop = backdrop,
                ) {
                    tabs.forEach { tab ->
                        val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                        var bounceTrigger by remember { mutableIntStateOf(0) }
                        IslandTabItem(
                            tab = tab,
                            selected = selected,
                            bounceTrigger = bounceTrigger,
                            onClick = {
                                // 当前 tab：复点也弹跳一次；其他 tab：切换（切页本身也触发弹跳）
                                bounceTrigger++
                                if (tab.rootRoute != currentDestination?.route) {
                                    if (tab.rootRoute == Routes.PROFILE_TAB) profileEntranceEpoch++
                                    navController.navigateToTab(tab.route)
                                }
                            },
                        )
                    }
                }
            }

            // 返回回收浮层：盖在页面与底栏之上，把快照直接收进点按的入口卡
            if (entryTransitions.exitKey != null) {
                // 回收期间吞掉返回键，避免落到「我的」页的上一级
                BackHandler { }
            }
            ProfileEntryExitOverlay(
                transitions = entryTransitions,
                captureLayer = entryLayer,
            )
        }
    }
}
