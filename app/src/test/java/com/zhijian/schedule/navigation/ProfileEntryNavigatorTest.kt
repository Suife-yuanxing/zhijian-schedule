package com.zhijian.schedule.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 入口卡单飞闸门（= 主人报的「打开二级页后回不到根页」的根因修复）。
 *
 * 钉住两条：
 * 1. 落位窗口内的第二次请求**必须被丢弃**（不是排队、不是合并成最后一次）；
 * 2. 窗口过期后照常放行，且预算每次重新求值（系统动画缩放可能在两次之间变化）。
 *
 * 语义与 [TabSwitchQueue] 刻意相反：切 tab 幂等，合并成最后一次意图；
 * 打开哪张入口卡不幂等，把 30ms 后的误触当意图会把用户送到他没想打开的页面。
 */
class ProfileEntryNavigatorTest {
    private val log = mutableListOf<String>()
    private var now = 1_000L

    private fun navigator(budget: Long = 480L): ProfileEntryNavigator =
        ProfileEntryNavigator(
            budgetMs = { budget },
            nowMs = { now },
            navigate = { route -> log += route },
        )

    @Test
    fun firstRequestNavigatesImmediately() {
        val nav = navigator()
        assertTrue(nav.request(Routes.PROFILE_EXAMS))
        assertEquals(listOf(Routes.PROFILE_EXAMS), log)
    }

    @Test
    fun secondCardInsideWindowIsDropped() {
        // 真机复现的那个场景：成绩查询 (t=0) → 考试安排 (t=30)
        // 修前两层都压进栈，按一次返回停在成绩页；修后第二次直接丢弃
        val nav = navigator()
        nav.request(Routes.PROFILE_GRADES)
        now += 30
        assertFalse(nav.request(Routes.PROFILE_EXAMS))
        assertEquals(listOf(Routes.PROFILE_GRADES), log)
    }

    @Test
    fun requestAfterWindowPasses() {
        val nav = navigator(budget = 480)
        nav.request(Routes.PROFILE_GRADES)
        now += 479
        assertFalse(nav.request(Routes.PROFILE_EXAMS))
        now += 2 // 累计 481 > 480
        assertTrue(nav.request(Routes.PROFILE_EXAMS))
        assertEquals(listOf(Routes.PROFILE_GRADES, Routes.PROFILE_EXAMS), log)
    }

    @Test
    fun windowRestartsAfterEachAcceptedNavigation() {
        val nav = navigator(budget = 480)
        nav.request(Routes.PROFILE_GRADES)
        now += 500
        nav.request(Routes.PROFILE_EXAMS)
        // 下一次的窗口从第二次导航起算，不是从第一次
        now += 100
        assertFalse(nav.request(Routes.PROFILE_SETTINGS))
        now += 400
        assertTrue(nav.request(Routes.PROFILE_SETTINGS))
        assertEquals(
            listOf(Routes.PROFILE_GRADES, Routes.PROFILE_EXAMS, Routes.PROFILE_SETTINGS),
            log,
        )
    }

    @Test
    fun sameCardRepeatInsideWindowIsAlsoDropped() {
        // 连点同一张卡：launchSingleTop + popUpTo 已经能挡住重复压栈，
        // 但闸门先把它挡掉，连一次导航都不发生（省掉整轮转场）
        val nav = navigator()
        nav.request(Routes.PROFILE_GRADES)
        now += 20
        assertFalse(nav.request(Routes.PROFILE_GRADES))
        assertEquals(listOf(Routes.PROFILE_GRADES), log)
    }

    @Test
    fun zeroBudgetNeverBlocks() {
        // 系统「移除动画」：转场瞬间完成，不存在漏洞窗口，也不该引入人为等待
        val nav = navigator(budget = 0)
        nav.request(Routes.PROFILE_GRADES)
        now += 0
        assertTrue(nav.request(Routes.PROFILE_EXAMS))
        assertEquals(listOf(Routes.PROFILE_GRADES, Routes.PROFILE_EXAMS), log)
    }

    @Test
    fun budgetIsReReadOnEveryRequest() {
        // 生产里预算随系统动画缩放折算（entryNavBudgetMs 每次现读 Settings.Global）：
        // 用户在两次点击之间改了开发者选项的动画时长，必须当场生效，不能拿旧窗口。
        // 实现是「每次 request 都求值」而不是「每次导航后缓存一次」，这里把这条钉住。
        var budget = 480L
        val nav =
            ProfileEntryNavigator(
                budgetMs = { budget },
                nowMs = { now },
                navigate = { route -> log += route },
            )
        nav.request(Routes.PROFILE_GRADES)

        // 缩放调到 2x：窗口涨到 960，原本过了 480 就该放行的请求现在要被挡
        budget = 960L
        now += 500
        assertFalse(nav.request(Routes.PROFILE_EXAMS))
        now += 470 // 累计 970 > 960
        assertTrue(nav.request(Routes.PROFILE_EXAMS))

        // 缩放关掉（0）：不再引入任何人为等待
        budget = 0L
        now += 1
        assertTrue(nav.request(Routes.PROFILE_SETTINGS))
        assertEquals(
            listOf(Routes.PROFILE_GRADES, Routes.PROFILE_EXAMS, Routes.PROFILE_SETTINGS),
            log,
        )
    }
}
