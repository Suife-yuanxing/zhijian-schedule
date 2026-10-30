package com.zhijian.schedule.feature.schedule

/**
 * 课表页「定位到哪一周」的纯逻辑：决定一次组合要不要把 pager 强制拉回某一页。
 *
 * ## 这条规则在修什么（「根页面切换时刷新位置错误」）
 * 旧实现是一个裸副作用，key 是**课表元数据**而不是「这次组合是不是第一次」：
 *
 * ```
 * LaunchedEffect(ui.timetable?.id, ui.timetable?.startDate, ui.timetable?.totalWeeks) {
 *     val target = (ui.currentWeek ?: 1) - 1
 *     if (pagerState.currentPage != target) pagerState.scrollToPage(target)
 * }
 * ```
 *
 * 于是只要页面重新进入组合（切 tab 回来、从编辑页返回、Activity 重建），这段就重跑一次，
 * 把 pager 拉回「自然当前周」。用户手动翻到第 10 周、或用周选择器跳周之后再切一次 tab，
 * 回来就变回第 9 周——读起来正是「切换根页面时刷新位置错误」。
 *
 * ## 规则：只认锚点变化
 * 锚点 = 课表 id + 开学日期 + 总周数。三者都没变，说明课表本身没换，
 * 当前页码属于**用户状态**，一律不覆盖（pager 自己会从 saveable 状态恢复页码）。
 * 锚点一变（换了课表、改了开学日期、改了总周数）就必须重新定位到自然当前周。
 *
 * 纯逻辑、无 Compose 依赖，便于 JVM 单测（见 `WeekAnchorPolicyTest`）。
 */
internal object WeekAnchorPolicy {
    /**
     * 本次组合要不要把 pager 强制定位。
     *
     * @param previousAnchor 上一次定位时用的锚点签名（null = 还没定位过）
     * @param currentAnchor 本次组合的锚点签名（null = 课表还没加载出来）
     */
    internal fun shouldRelocate(
        previousAnchor: String?,
        currentAnchor: String?,
    ): Boolean {
        // 课表还没加载出来：不定位（否则会落到第 1 周）
        if (currentAnchor == null) return false
        return currentAnchor != previousAnchor
    }

    /**
     * 定位目标页（0 基）：自然当前周，并夹进总周数范围内。
     *
     * 夹取不是防御性冗余：总周数被改小之后，旧页码可能已经越界。
     */
    internal fun targetPage(
        currentWeek: Int?,
        totalWeeks: Int,
    ): Int {
        val last = (totalWeeks - 1).coerceAtLeast(0)
        return (currentWeek ?: 1).minus(1).coerceIn(0, last)
    }

    /** 锚点签名：三者任一变化都要重新定位 */
    internal fun anchorOf(
        timetableId: Long?,
        startDate: String?,
        totalWeeks: Int,
    ): String? = timetableId?.let { "$it|${startDate.orEmpty()}|$totalWeeks" }
}
