package com.zhijian.schedule.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.navigation
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.SeedShape
import com.zhijian.schedule.feature.courseeditor.CourseEditorScreen
import com.zhijian.schedule.feature.exam.ExamScreen
import com.zhijian.schedule.feature.grades.GradesScreen
import com.zhijian.schedule.feature.importer.ImporterScreen
import com.zhijian.schedule.feature.jw.JwImportScreen
import com.zhijian.schedule.feature.jw.ScriptManageScreen
import com.zhijian.schedule.feature.profile.ProfileEntryKeys
import com.zhijian.schedule.feature.profile.ProfileEntryPage
import com.zhijian.schedule.feature.profile.ProfileScreen
import com.zhijian.schedule.feature.schedule.ScheduleScreen
import com.zhijian.schedule.feature.settings.SettingsScreen
import com.zhijian.schedule.feature.today.TodayScreen

/**
 * 应用导航图：三个底部 tab 各挂一个嵌套图，二级页挂在所属 tab 的图里。
 *
 * 路由字符串与参数名（[Routes] / [editorArgs] / [gradesArgs]）是对外契约：
 * 通知跳转、胶囊入口、WebView 自动跳转都按它导航，一个字都不能改。
 * 转场在这里只做「选哪一条」（见 [AppTransitions]），宿主与页面本体不参与。
 */
@Composable
internal fun AppNavHost(
    navController: NavHostController,
    state: AppNavHostState,
    currentDestination: NavDestination?,
    /**
     * 入口卡 → 二级页的唯一导航入口（宿主侧的单飞闸门 + 栈上限，见 [ProfileEntryNavigator]）。
     *
     * 为什么从这里进来而不是各页面各调 navController.navigate：两张不同的卡在展开首帧
     * 之前相继被点中，会各压一层二级页，按一次返回退不出去（真机 30ms 间隔复现）。
     * 闸门必须活在宿主这一层，「我的」页返回后会被销毁，闸门挂在页面里就跟着没了。
     */
    onOpenProfileEntry: (String) -> Unit = { route ->
        navController.navigateToProfileEntry(route)
    },
    modifier: Modifier = Modifier,
) {
    val colors = LocalCourseColors.current
    // 编辑已有课程：圆形扩散从**被点的课块/课卡**长出来（seed 在点开详情面板那一刻定格，
    // 见 docs/specs/2026-11-07-edit-reveal-design.md）。与三个添加入口同一条语言；
    // 退化（seed 缺失或还没测出几何）时直接导航走默认横滑，与添加入口同一条兜底。
    // 面板窗口的移除与 reveal 盖屏落在同一帧快照——课表不会裸露（断点帧的修复）。
    val navigateEditor: (String, SeedShape?) -> Unit = { route, seed ->
        val usable = seed?.takeIf { it.bounds.width > 0f && it.bounds.height > 0f }
        state.seedOrigin = usable
        if (usable == null) {
            navController.navigate(route)
        } else {
            val started =
                state.reveal.requestNavigation(usable, colors.bg) {
                    navController.navigate(route)
                }
            if (!started) navController.navigate(route)
        }
    }
    NavHost(
        navController = navController,
        startDestination = Routes.SCHEDULE_TAB,
        modifier = modifier,
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
            popEnterTransition = {
                // 反向扩散回到今日：今日页是被露出的一页，不能自己再淡入
                if (state.reveal.navigating) {
                    EnterTransition.None
                } else if (initialState.isInTabGraph()) {
                    tabPopEnter
                } else {
                    popEnter
                }
            },
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
                        // 圆形扩散接管了这一程：旧页静止不动，由浮层的旧帧顶住圆外
                        state.reveal.navigating -> ExitTransition.None
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
                        islandCollapse = state.islandCollapse,
                        scrollToTopTick = state.scrollToTopTick,
                        // 三个根页读同一个闸门：只有冷启动首帧为 true，其余（切 tab / 二级页返回 /
                        // Activity 重建）一律 false → 首帧直接落位。见 RootEntrancePolicy。
                        playEntrance = state.entrance.shouldPlay(Routes.TODAY),
                        onEntranceShown = { state.entrance.markPlayed() },
                        onEditCourse = { id, seed ->
                            navigateEditor(Routes.todayEditor(courseId = id), seed)
                        },
                        onOpenExams = {
                            // 直达考试页：先切 tab 再 push。切 tab 不播入场（闸门在宿主），
                            // 所以这里不需要也不应该再给「我的」开一次级联。
                            navController.navigateToTab(Routes.PROFILE_TAB)
                            navController.navigate(Routes.PROFILE_EXAMS) { launchSingleTop = true }
                        },
                    )
                }
            }
            composable(
                route = Routes.TODAY_EDITOR,
                arguments = editorArgs,
                // 从课卡圆形扩散进来时不要滑入：新页面只被浮层按轮廓裁剪
                enterTransition = { if (state.reveal.navigating) EnterTransition.None else pushEnter },
                exitTransition = { if (state.reveal.navigating) ExitTransition.None else pushExit },
                popEnterTransition = { popEnter },
                popExitTransition = { if (state.reveal.navigating) ExitTransition.None else popExit },
            ) {
                // 与 SCHEDULE_EDITOR 同一条返回语言：反向圆形收回进被点的课卡。
                // 今日页没有加号，也就没有 ×→+ 的复位信号要发。
                val leave: () -> Unit = {
                    val seed = state.seedOrigin
                    val started =
                        seed != null &&
                            state.reveal.requestNavigationBack(seed, colors.bg) {
                                navController.popBackStack()
                            }
                    if (!started) navController.popBackStack()
                }
                BackHandler(onBack = leave)
                CourseEditorScreen(onDone = leave)
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
            popEnterTransition = {
                if (state.reveal.navigating) {
                    EnterTransition.None
                } else if (initialState.isInTabGraph()) {
                    tabPopEnter
                } else {
                    popEnter
                }
            },
            popExitTransition = { tabPopExit },
        ) {
            composable(
                route = Routes.SCHEDULE,
                enterTransition = {
                    // 反向扩散：课表是被露出的一页，不能自己再滑入，否则被收走的编辑页会留在下面成重影
                    if (state.reveal.navigating) {
                        EnterTransition.None
                    } else if (initialState.isInTabGraph()) {
                        tabSlideEnter(initialState, targetState)
                    } else {
                        tabEnter
                    }
                },
                exitTransition = {
                    when {
                        // 圆形扩散接管了这一程：旧页静止不动，由浮层的旧帧顶住圆外
                        state.reveal.navigating -> ExitTransition.None
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
                        islandCollapse = state.islandCollapse,
                        scrollToTopTick = state.scrollToTopTick,
                        playEntrance = state.entrance.shouldPlay(Routes.SCHEDULE),
                        onEntranceShown = { state.entrance.markPlayed() },
                        // 三个「添加课程」入口（空课块 / 空状态卡片 / 加号）走同一条路：
                        // 都从**被点的那个控件**长出来。圆外仍是旧课表，转场交给根部 RevealHost，
                        // 导航自己的横滑转场让位（见 AppNavGraph 里 SCHEDULE_EDITOR 的分支）。
                        //
                        // 退化：origin 为 Rect.Zero（列框还没测出来，理论上只在首帧出现）时
                        // 直接导航——扩散从一个 0×0 的圆起手等于整屏瞬切，那比没有动画更糟。
                        onAddCourse = { day, section, seed ->
                            // 退化：bounds 还没测出来（理论上只在首帧出现）时直接导航——
                            // 从一个 0×0 的轮廓起手等于整屏瞬切，那比没有动画更糟。
                            val usable = seed.takeIf { it.bounds.width > 0f && it.bounds.height > 0f }
                            state.seedOrigin = usable
                            val route = Routes.scheduleEditor(day = day, section = section)
                            if (usable == null) {
                                navController.navigate(route)
                            } else {
                                val started =
                                    state.reveal.requestNavigation(usable, colors.bg) {
                                        navController.navigate(route)
                                    }
                                if (!started) navController.navigate(route)
                            }
                        },
                        onAddCourseFromFab = { seed ->
                            state.seedOrigin = seed
                            val route = Routes.scheduleEditor(day = null, section = null)
                            val started =
                                state.reveal.requestNavigation(seed, colors.bg) {
                                    navController.navigate(route)
                                }
                            if (!started) navController.navigate(route)
                        },
                        // 从编辑页回到课表：加号要把 × 转回 +（页面自己不知道这件事，
                        // 它是「谁离开了」这个宿主级事实，所以由这里计数下发）
                        fabResetTick = state.fabResetTick,
                        // 编辑已有课程：扩散从被点的课块长出来（seed 见 navigateEditor），
                        // 返回走反向收回；不再是无连续性的默认横滑
                        onEditCourse = { id, seed ->
                            navigateEditor(Routes.scheduleEditor(courseId = id), seed)
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
                JwImportScreen(
                    onBack = { navController.popBackStack() },
                    onOpenScripts = {
                        navController.navigate(Routes.PROFILE_JW_SCRIPTS) { launchSingleTop = true }
                    },
                )
            }
            composable(
                route = Routes.SCHEDULE_EDITOR,
                arguments = editorArgs,
                // 从加号圆形扩散进来时不要滑入：新页面只被浮层按圆裁剪
                enterTransition = { if (state.reveal.navigating) EnterTransition.None else pushEnter },
                exitTransition = { if (state.reveal.navigating) ExitTransition.None else pushExit },
                popEnterTransition = { popEnter },
                popExitTransition = { if (state.reveal.navigating) ExitTransition.None else popExit },
            ) {
                // 从加号 / 空课块进来时，返回改成反向圆形收回：先定格编辑页取图底，再 pop，
                // 编辑页按圆一路收进被点的那个控件，露出下面的课表；其他入口保持原本的返回转场。
                //
                // 无论走哪条路，离开前都要给加号一个复位信号：它是 × 状态，回来时得转回 +。
                val leave: () -> Unit = {
                    val seed = state.seedOrigin
                    val started =
                        seed != null &&
                            state.reveal.requestNavigationBack(seed, colors.bg) {
                                state.requestFabReset()
                                navController.popBackStack()
                            }
                    if (!started) {
                        state.requestFabReset()
                        navController.popBackStack()
                    }
                }
                BackHandler(onBack = leave)
                CourseEditorScreen(onDone = leave)
            }
        }

        navigation(
            startDestination = Routes.PROFILE,
            route = Routes.PROFILE_TAB,
            // 入口卡二级页：图上这一层也不能淡入淡出，否则包裹层会盖掉
            // 实时展开卡片所需的「我的」页背景
            enterTransition = {
                when {
                    isProfileEntryRoute(targetState.destination.route) -> profileEntryHoldEnter
                    targetState.isRootTab() && initialState.isInTabGraph() ->
                        tabSlideEnter(initialState, targetState)
                    else -> tabEnter
                }
            },
            exitTransition = {
                when {
                    isProfileEntryRoute(targetState.destination.route) -> profileEntryHoldExit
                    targetState.isRootTab() -> tabSlideExit(initialState, targetState)
                    targetState.isInTabGraph() -> tabExit
                    else -> pushExit
                }
            },
            popEnterTransition = {
                if (isProfileEntryRoute(initialState.destination.route)) {
                    EnterTransition.None
                } else if (initialState.isInTabGraph()) {
                    tabPopEnter
                } else {
                    popEnter
                }
            },
            popExitTransition = {
                if (isProfileEntryRoute(initialState.destination.route)) {
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
                        isProfileEntryRoute(targetState.destination.route) -> profileEntryHoldExit
                        targetState.isRootTab() -> tabSlideExit(initialState, targetState)
                        targetState.isInTabGraph() -> tabExit
                        else -> pushExit
                    }
                },
                popEnterTransition = {
                    if (isProfileEntryRoute(initialState.destination.route)) {
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
                        islandCollapse = state.islandCollapse,
                        scrollToTopTick = state.scrollToTopTick,
                        entryTransitions = state.entryTransitions,
                        reveal = state.reveal,
                        playEntrance = state.entrance.shouldPlay(Routes.PROFILE),
                        onEntranceShown = { state.entrance.markPlayed() },
                        // 五个入口统一走宿主侧的单飞闸门（ProfileEntryNavigator）：
                        // 窗口内的第二次点击被丢弃，且每次导航前先把栈收回到「我的」。
                        // 各页面自己调 navigate + launchSingleTop 拦不住「两张不同的卡」——
                        // 30ms 内连点会各压一层，按一次返回退不回去（真机钉死）。
                        onOpenImport = { onOpenProfileEntry(Routes.PROFILE_IMPORTER) },
                        onOpenSettings = { onOpenProfileEntry(Routes.PROFILE_SETTINGS) },
                        onOpenGrades = {
                            onOpenProfileEntry(Routes.profileGrades(ProfileEntryKeys.GRADES))
                        },
                        onOpenGradesFromSummary = {
                            onOpenProfileEntry(Routes.profileGrades(ProfileEntryKeys.GRADES_GPA))
                        },
                        onOpenExams = { onOpenProfileEntry(Routes.PROFILE_EXAMS) },
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
                    transitions = state.entryTransitions,
                    captureLayer = state.entryLayer,
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
                JwImportScreen(
                    onBack = { navController.popBackStack() },
                    onOpenScripts = {
                        navController.navigate(Routes.PROFILE_JW_SCRIPTS) { launchSingleTop = true }
                    },
                )
            }
            composable(
                route = Routes.PROFILE_JW_SCRIPTS,
                enterTransition = { EnterTransition.None },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                ScriptManageScreen(onBack = { navController.popBackStack() })
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
                    transitions = state.entryTransitions,
                    captureLayer = state.entryLayer,
                    isTop = currentDestination?.route == Routes.PROFILE_SETTINGS,
                    onPop = { navController.popBackStack() },
                ) { handleBack ->
                    SettingsScreen(
                        onBack = handleBack,
                        // 二级页入场闸门：本次进程里这一页只播一次（见 SecondLevelEntrancePolicy）。
                        // 写在宿主这一层才跨 entry 生效——页面内部的 rememberSaveable
                        // 在返回再进（新 entry）时会重新初始化，那正是「打开像刷新了一遍」
                        playEntrance = state.secondLevelEntrance.shouldPlay(Routes.PROFILE_SETTINGS),
                        onEntranceShown = { state.secondLevelEntrance.markPlayed(Routes.PROFILE_SETTINGS) },
                    )
                }
            }
            composable(
                route = Routes.PROFILE_GRADES,
                arguments = gradesArgs,
                enterTransition = { profileEntryHoldEnter },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) { backStackEntry ->
                val entryKey =
                    backStackEntry.arguments
                        ?.getString("entryKey")
                        ?.takeIf {
                            it == ProfileEntryKeys.GRADES ||
                                it == ProfileEntryKeys.GRADES_GPA
                        }
                        ?: ProfileEntryKeys.GRADES
                ProfileEntryPage(
                    entryKey = entryKey,
                    transitions = state.entryTransitions,
                    captureLayer = state.entryLayer,
                    isTop = currentDestination?.route == Routes.PROFILE_GRADES,
                    onPop = { navController.popBackStack() },
                ) { handleBack ->
                    GradesScreen(
                        onBack = handleBack,
                        onOpenJwImport = { navController.navigate(Routes.PROFILE_JW_IMPORT) },
                        playEntrance = state.secondLevelEntrance.shouldPlay(Routes.PROFILE_GRADES),
                        onEntranceShown = { state.secondLevelEntrance.markPlayed(Routes.PROFILE_GRADES) },
                    )
                }
            }
            composable(
                route = Routes.PROFILE_EXAMS,
                enterTransition = { profileEntryHoldEnter },
                exitTransition = { ExitTransition.None },
                popEnterTransition = { EnterTransition.None },
                popExitTransition = { ExitTransition.None },
            ) {
                ProfileEntryPage(
                    entryKey = ProfileEntryKeys.EXAMS,
                    transitions = state.entryTransitions,
                    captureLayer = state.entryLayer,
                    isTop = currentDestination?.route == Routes.PROFILE_EXAMS,
                    onPop = { navController.popBackStack() },
                ) { handleBack ->
                    ExamScreen(
                        onBack = handleBack,
                        onNeedsLogin = {
                            navController.navigate(Routes.PROFILE_JW_IMPORT) { launchSingleTop = true }
                        },
                        playEntrance = state.secondLevelEntrance.shouldPlay(Routes.PROFILE_EXAMS),
                        onEntranceShown = { state.secondLevelEntrance.markPlayed(Routes.PROFILE_EXAMS) },
                    )
                }
            }
        }
    }
}
