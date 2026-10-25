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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
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
import com.zhijian.schedule.core.ui.RevealHost
import com.zhijian.schedule.core.ui.RevealState
import com.zhijian.schedule.core.ui.glass.glassBackdropSource
import com.zhijian.schedule.core.ui.glass.rememberGlassBackdrop
import com.zhijian.schedule.core.ui.glass.rememberGlassClock
import com.zhijian.schedule.core.ui.glass.rememberGlassTilt
import com.zhijian.schedule.core.ui.rememberRevealState
import com.zhijian.schedule.feature.course_editor.CourseEditorScreen
import com.zhijian.schedule.feature.exam.ExamScreen
import com.zhijian.schedule.feature.grades.GradesScreen
import com.zhijian.schedule.feature.importer.ImporterScreen
import com.zhijian.schedule.feature.jw.JwImportScreen
import com.zhijian.schedule.feature.jw.ScriptManageScreen
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
    const val PROFILE_EXAMS = "profile_exams"
    const val PROFILE_JW_SCRIPTS = "profile_jw_scripts"

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
        targetOffsetX = { if (toLeft) -it else it },
    )
}

// ---------- 「我的」入口卡 ↔ 二级页：共享容器变换 ----------

private val profileEntryRoutes = setOf(
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
    val reveal = rememberRevealState()
    // 扩散期间先按旧主题渲染，旧帧录好之后才切新主题（renderedId 由扩散状态机给出）；
    // 播完由 settlingId 兜住 DataStore 回传延迟，避免闪回旧主题一帧。
    val renderedThemeId = reveal.renderedId ?: settings.themeId
    LaunchedEffect(renderedThemeId) {
        com.zhijian.schedule.core.ui.RevealTrace.log {
            "render theme=$renderedThemeId settings=${settings.themeId}"
        }
    }
    LaunchedEffect(settings.themeId) { reveal.onSettingsTheme(settings.themeId) }
    CourseTheme(
        presetId = renderedThemeId,
        darkMode = settings.darkMode,
        gridStyle = settings.gridStyle,
    ) {
        // 扩散浮层要盖住导航、底栏与所有页面，所以挂在主题壳的根部
        RevealHost(state = reveal) {
            AppScaffold(
                openTodayCount = openTodayCount,
                openJwImportCount = openJwImportCount,
                reveal = reveal,
            )

            // 进入应用自动检查更新：静默检查，发现新版本才弹更新窗口（失败/已最新不打扰）
            LaunchedEffect(Unit) { updatePrompt.checkOnLaunch() }
            if (updatePrompt.promptVersion != null) {
                UpdateDialog(onDismiss = { updatePrompt.dismiss() })
            }
        }
    }
}

@Composable
private fun AppScaffold(
    openTodayCount: Int,
    openJwImportCount: Int,
    reveal: RevealState,
) {
    val navController = rememberNavController()
    val entryTransitions = remember { ProfileEntryTransitions() }
    // 「我的」的分段级联只在本次进程**第一次**进入时播一次，之后切回来直接落位。
    // 每次重播的话页面会先空一帧（全部区块 alpha=0）再从上往下长出来；这一页又是从右边
    // 滑入的，空帧 + 顶部先出现，读起来就是「整页从右上角打开」（连续快速切换时实测抓到了
    // 整屏只剩底色的空帧）。与课表页的 scheduleEntrancePlayed 用同一套节奏：切 tab 只做位移。
    var profileEntranceEpoch by remember { mutableIntStateOf(0) }
    var profileEntrancePlayed by remember { mutableStateOf(false) }
    val playProfileEntranceOnce = {
        if (!profileEntrancePlayed) {
            profileEntrancePlayed = true
            profileEntranceEpoch++
        }
    }
    // 从加号进课程编辑页时记下加号的位置：返回时页面要反向收回这里
    var fabOrigin by remember { mutableStateOf<Rect?>(null) }
    // 课表页的「课块级联 + 加号弹出」只播一次（冷启动那一次）。
    // 不这么做的话，切回课表会比切到今日/我的多一段内容收尾，两个方向读起来不是同一条曲线。
    var scheduleEntrancePlayed by remember { mutableStateOf(false) }
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
                                onOpenExams = {
                                    playProfileEntranceOnce()
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
                        if (reveal.navigating) EnterTransition.None
                        else if (initialState.isInTabGraph()) tabPopEnter else popEnter
                    },
                    popExitTransition = { tabPopExit },
                ) {
                    composable(
                        route = Routes.SCHEDULE,
                        enterTransition = {
                            // 反向扩散：课表是被露出的一页，不能自己再滑入，否则被收走的编辑页会留在下面成重影
                            if (reveal.navigating) EnterTransition.None
                            else if (initialState.isInTabGraph()) tabSlideEnter(initialState, targetState)
                            else tabEnter
                        },
                        exitTransition = {
                            when {
                                // 圆形扩散接管了这一程：旧页静止不动，由浮层的旧帧顶住圆外
                                reveal.navigating -> ExitTransition.None
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
                                    fabOrigin = null
                                    navController.navigate(Routes.scheduleEditor(day = day, section = section))
                                },
                                // 加号：先定格整屏取成图底再导航，编辑页从加号那个圆里长出来，
                                // 圆外仍是旧课表（转场交给根部 RevealHost，导航转场让位）
                                onAddCourseFromFab = { bounds ->
                                    fabOrigin = bounds
                                    val started = reveal.requestNavigation(bounds, colors.bg) {
                                        navController.navigate(Routes.scheduleEditor(day = null, section = null))
                                    }
                                    if (!started) {
                                        navController.navigate(Routes.scheduleEditor(day = null, section = null))
                                    }
                                },
                                onEditCourse = { id ->
                                    fabOrigin = null
                                    navController.navigate(Routes.scheduleEditor(courseId = id))
                                },
                                onOpenSettings = {
                                    playProfileEntranceOnce()
                                    navController.navigateToTab(Routes.PROFILE_TAB)
                                },
                                onOpenImport = { navController.navigate(Routes.SCHEDULE_IMPORTER) },
                                playEntrance = !scheduleEntrancePlayed,
                                onEntranceShown = { scheduleEntrancePlayed = true },
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
                        enterTransition = { if (reveal.navigating) EnterTransition.None else pushEnter },
                        exitTransition = { if (reveal.navigating) ExitTransition.None else pushExit },
                        popEnterTransition = { popEnter },
                        popExitTransition = { if (reveal.navigating) ExitTransition.None else popExit },
                    ) {
                        // 从加号进来时，返回改成反向圆形收回：先定格编辑页取图底，再 pop，
                        // 编辑页按圆一路收进加号，露出下面的课表；其他入口保持原本的返回转场
                        val leave: () -> Unit = {
                            val origin = fabOrigin
                            val started = origin != null &&
                                reveal.requestNavigationBack(origin, colors.bg) {
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
                                reveal = reveal,
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
                    composable(
                        route = Routes.PROFILE_EXAMS,
                        enterTransition = { profileEntryHoldEnter },
                        exitTransition = { ExitTransition.None },
                        popEnterTransition = { EnterTransition.None },
                        popExitTransition = { ExitTransition.None },
                    ) {
                        ProfileEntryPage(
                            entryKey = ProfileEntryKeys.EXAMS,
                            transitions = entryTransitions,
                            captureLayer = entryLayer,
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
                                    if (tab.rootRoute == Routes.PROFILE_TAB) {
                                        playProfileEntranceOnce()
                                    }
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
