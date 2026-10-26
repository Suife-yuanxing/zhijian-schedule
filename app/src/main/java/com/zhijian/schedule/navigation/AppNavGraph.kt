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
import com.zhijian.schedule.feature.course_editor.CourseEditorScreen
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
    modifier: Modifier = Modifier,
) {
    val colors = LocalCourseColors.current
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
                        islandCollapse = state.islandCollapse,
                        scrollToTopTick = state.scrollToTopTick,
                        playEntrance = !state.todayEntrancePlayed,
                        onEntranceShown = { state.markTodayEntrancePlayed() },
                        onEditCourse = { id -> navController.navigate(Routes.todayEditor(courseId = id)) },
                        onOpenExams = {
                            state.playProfileEntranceOnce()
                            navController.navigateToTab(Routes.PROFILE_TAB)
                            navController.navigate(Routes.PROFILE_EXAMS) { launchSingleTop = true }
                        },
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
            popEnterTransition = {
                if (state.reveal.navigating) EnterTransition.None
                else if (initialState.isInTabGraph()) tabPopEnter else popEnter
            },
            popExitTransition = { tabPopExit },
        ) {
            composable(
                route = Routes.SCHEDULE,
                enterTransition = {
                    // 反向扩散：课表是被露出的一页，不能自己再滑入，否则被收走的编辑页会留在下面成重影
                    if (state.reveal.navigating) EnterTransition.None
                    else if (initialState.isInTabGraph()) tabSlideEnter(initialState, targetState)
                    else tabEnter
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
                        entryEpoch = state.scheduleEntryEpoch,
                        onAddCourse = { day, section ->
                            state.fabOrigin = null
                            navController.navigate(Routes.scheduleEditor(day = day, section = section))
                        },
                        // 加号：先定格整屏取成图底再导航，编辑页从加号那个圆里长出来，
                        // 圆外仍是旧课表（转场交给根部 RevealHost，导航转场让位）
                        onAddCourseFromFab = { bounds ->
                            state.fabOrigin = bounds
                            val started = state.reveal.requestNavigation(bounds, colors.bg) {
                                navController.navigate(Routes.scheduleEditor(day = null, section = null))
                            }
                            if (!started) {
                                navController.navigate(Routes.scheduleEditor(day = null, section = null))
                            }
                        },
                        onEditCourse = { id ->
                            state.fabOrigin = null
                            navController.navigate(Routes.scheduleEditor(courseId = id))
                        },
                        onOpenSettings = {
                            state.playProfileEntranceOnce()
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
                // 从加号进来时，返回改成反向圆形收回：先定格编辑页取图底，再 pop，
                // 编辑页按圆一路收进加号，露出下面的课表；其他入口保持原本的返回转场
                val leave: () -> Unit = {
                    val origin = state.fabOrigin
                    val started = origin != null &&
                        state.reveal.requestNavigationBack(origin, colors.bg) {
                            navController.popBackStack()
                        }
                    if (!started) navController.popBackStack()
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
                        entranceEpoch = state.profileEntranceEpoch,
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
                        onOpenExams = {
                            navController.navigate(Routes.PROFILE_EXAMS) { launchSingleTop = true }
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
                    SettingsScreen(onBack = handleBack)
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
                val entryKey = backStackEntry.arguments?.getString("entryKey")
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
                    )
                }
            }
        }


    }
}
