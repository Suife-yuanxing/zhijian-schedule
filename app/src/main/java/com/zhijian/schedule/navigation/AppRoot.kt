package com.zhijian.schedule.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zhijian.schedule.core.theme.CourseTheme
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.ui.LocalClock
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.RevealHost
import com.zhijian.schedule.core.ui.RevealState
import com.zhijian.schedule.core.ui.glass.glassBackdropSource
import com.zhijian.schedule.core.ui.glass.rememberGlassBackdrop
import com.zhijian.schedule.core.ui.glass.rememberGlassClock
import com.zhijian.schedule.core.ui.glass.rememberGlassTilt
import com.zhijian.schedule.core.ui.island.rememberIslandCollapseState
import com.zhijian.schedule.core.ui.rememberAppClock
import com.zhijian.schedule.core.ui.rememberRevealState
import com.zhijian.schedule.feature.profile.ProfileEntryExitOverlay
import com.zhijian.schedule.feature.profile.ProfileEntryTransitions
import com.zhijian.schedule.feature.update.UpdateDialog
import com.zhijian.schedule.feature.update.UpdatePromptViewModel

/**
 * 应用根：主题壳 + 全局浮层 + 底部浮岛 + 导航图的挂载点。
 *
 * 这一层只负责「谁在外面、谁在里面、谁盖在谁上面」：主题、整分时钟、扩散浮层、
 * 更新弹窗依次包裹；导航图本体见 [AppNavHost]，转场见 [AppTransitions]，
 * 路由契约见 [Routes]。
 */
@Composable
fun AppRoot(
    openTodayCount: Int = 0,
    openJwImportCount: Int = 0,
) {
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
    // 全 App 一根整分时钟：谁需要「现在」谁读，组合期不读就不会因为分钟刻度整页重组
    val appClock = rememberAppClock()
    CourseTheme(
        presetId = renderedThemeId,
        darkMode = settings.darkMode,
        gridStyle = settings.gridStyle,
    ) {
        // 扩散浮层要盖住导航、底栏与所有页面，所以挂在主题壳的根部
        CompositionLocalProvider(LocalClock provides appClock) {
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
}

/**
 * 页面宿主：一个 Scaffold 里同时坐着导航图与悬浮于其上的浮岛。
 *
 * 浮岛不参与内容布局（页面按各自的 IslandContentPadding 让位），只按当前目的地
 * 决定显隐；扩散浮层与入口卡回收浮层则永远盖在最上层。
 */
@Composable
private fun AppScaffold(
    openTodayCount: Int,
    openJwImportCount: Int,
    reveal: RevealState,
) {
    val navController = rememberNavController()
    val entryTransitions = remember { ProfileEntryTransitions() }
    // 二级页持续录进这个图层，返回时浮层直接重绘它（无位图读回）
    val entryLayer = rememberGraphicsLayer()
    // 浮岛收敛状态：由三个根页的纵向滚动源驱动（见 IslandScroll.kt）。
    // 挂在 AppScaffold 而不是浮岛内部——浮岛自己监听手势会与页面滚动抢事件。
    val islandCollapse = rememberIslandCollapseState()
    val navState = remember { AppNavHostState(reveal, islandCollapse, entryTransitions, entryLayer) }
    val colors = LocalCourseColors.current
    val tabs =
        listOf(
            TabItem(Routes.TODAY_TAB, Routes.TODAY, "今日", Icons.Filled.Star, Icons.Outlined.Star),
            TabItem(Routes.SCHEDULE_TAB, Routes.SCHEDULE, "课表", Icons.Filled.DateRange, Icons.Outlined.DateRange),
            TabItem(Routes.PROFILE_TAB, Routes.PROFILE, "我的", Icons.Filled.Person, Icons.Outlined.Person),
        )
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    // 课表的入场闸门：与今日页 / 我的页同一条规矩——本次进程第一次进入才播课块级联，
    // 之后切回来直接落位。不加闸门时，每切一次 tab 都要把所有课块 alpha 归零、按阅读顺序
    // （先上排、上排内从左到右）逐块长回来（整轮封顶 700ms），而切页滑动只要约 230ms，
    // 读起来就是「这一页刷新了一遍、内容从左上角摊开」（真机抓帧确认过）。
    // 注意比的是叶子路由 Routes.SCHEDULE（"schedule"），不是 tab 图的 Routes.SCHEDULE_TAB——
    // currentDestination 给出的是叶子，拿 tab 图路由去比永远不相等（这条踩过）。
    LaunchedEffect(backStackEntry) {
        if (backStackEntry?.destination?.route == Routes.SCHEDULE) navState.markScheduleEntrancePlayed()
    }
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
                .onGloballyPositioned { entryTransitions.updateContentBounds(it.boundsInWindow()) },
        ) {
            AppNavHost(
                navController = navController,
                state = navState,
                currentDestination = currentDestination,
                modifier =
                    Modifier
                        .glassBackdropSource(
                            backdrop = backdrop,
                            clock = glassClock,
                            enabled = showBottomBar,
                            baseColor = colors.bg,
                        ).onGloballyPositioned { backdrop?.onContentPositioned(it.boundsInWindow().topLeft) },
            )

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
                enter =
                    slideInVertically(animationSpec = Motion.SlideSpring, initialOffsetY = { it }) +
                        fadeIn(tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut)),
                exit =
                    slideOutVertically(animationSpec = Motion.SlideSpring, targetOffsetY = { it }) +
                        fadeOut(tween(durationMillis = Motion.DUR_FAST)),
            ) {
                FloatingIslandBar(
                    selectedIndex =
                        tabs.indexOfFirst { tab ->
                            currentDestination?.hierarchy?.any { it.route == tab.route } == true
                        },
                    itemCount = tabs.size,
                    clock = glassClock,
                    tilt = glassTilt,
                    backdrop = backdrop,
                    collapse = islandCollapse,
                ) {
                    tabs.forEach { tab ->
                        val selected = currentDestination?.hierarchy?.any { it.route == tab.route } == true
                        var bounceTrigger by remember { mutableIntStateOf(0) }
                        IslandTabItem(
                            tab = tab,
                            selected = selected,
                            bounceTrigger = bounceTrigger,
                            collapse = islandCollapse,
                            onClick = {
                                // 当前 tab：复点弹跳一次，并把页面滚回顶部（iOS 26 的同款习惯）。
                                // 其他 tab：切换（切页本身也触发弹跳），回顶交给新页自己首帧归位。
                                bounceTrigger++
                                if (tab.rootRoute != currentDestination?.route) {
                                    if (tab.rootRoute == Routes.PROFILE_TAB) {
                                        navState.playProfileEntranceOnce()
                                    }

                                    // 换 tab 一定把浮岛恢复正常态：收起只跟随「当前这一页」的滚动，
                                    // 换了页就不该继续矮着。翻周不算换页（那是 pager 内部），
                                    // 所以那条路径由 WeekGrid 的 islandActive 单独把关。
                                    islandCollapse.expand()
                                    navController.navigateToTab(tab.route)
                                } else {
                                    navState.requestScrollToTop()
                                    islandCollapse.expand()
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
