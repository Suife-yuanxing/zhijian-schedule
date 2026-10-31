package com.zhijian.schedule.navigation

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 切页单飞队列（底栏 tab 切换的唯一导航入口）。
 *
 * 钉住的契约：**任何一次导航都必须发生在上一次导航的落位窗口之后**。
 * NavHost 内部的 AnimatedContent 在转场进行中被再次改目标时，会保留被丢弃页的
 * 残影、并让新旧页弹簧从错误相位重启（真机 10x 慢放抓到「三页同框 + 页间缝隙」
 * 的病灶帧，见 docs/reviews/2026-10-31-切页打断三页同框根因.md）——所以快速连点
 * 必须在这里被合并成「最后一次意图、一次干净转场」，打断在源头就不可能发生。
 *
 * 注：advanceTimeBy 不会执行恰好落在目标时刻的任务，边界处都要补一次 runCurrent。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TabSwitchQueueTest {
    /** 记录 (虚拟时刻, 路由)：navigate 的实际发生顺序与时机就是被测行为 */
    private val log = mutableListOf<Pair<Long, String>>()

    /** worker 挂在 [TabSwitchQueue] 内部的协程上，必须交给 backgroundScope，否则 runTest 会等它跑完 */
    private fun TestScope.queue(budgetMs: Long): TabSwitchQueue =
        TabSwitchQueue(
            scope = backgroundScope,
            budgetMs = { budgetMs },
            nowMs = { testScheduler.currentTime },
            navigate = { route -> log += testScheduler.currentTime to route },
        )

    @Test
    fun firstRequestNavigatesImmediately() =
        runTest {
            val q = queue(budgetMs = 300)
            q.request(Routes.TODAY)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(0L to Routes.TODAY), log)
        }

    @Test
    fun requestWithinBudgetWaitsUntilWindowEnds() =
        runTest {
            val q = queue(budgetMs = 300)
            q.request(Routes.PROFILE)
            runCurrent()
            // 落位窗口中途（t=100）又点了另一个 tab
            advanceTimeBy(100)
            q.request(Routes.TODAY)
            advanceTimeBy(150)
            runCurrent()
            assertEquals(listOf(0L to Routes.PROFILE), log)
            advanceTimeBy(50)
            runCurrent()
            assertEquals(listOf(0L to Routes.PROFILE, 300L to Routes.TODAY), log)
        }

    @Test
    fun rapidRequestsCollapseToLastIntent() =
        runTest {
            val q = queue(budgetMs = 300)
            // 同一帧内的三连点（中间没有任何挂起点让 worker 插脚）：只有最后一次意图落地
            q.request(Routes.PROFILE)
            q.request(Routes.TODAY)
            q.request(Routes.SCHEDULE)
            advanceTimeBy(400)
            runCurrent()
            assertEquals(listOf(0L to Routes.SCHEDULE), log)
        }

    @Test
    fun requestAfterBudgetNavigatesImmediately() =
        runTest {
            val q = queue(budgetMs = 300)
            q.request(Routes.TODAY)
            runCurrent()
            // 上一次转场已经落位（t=300 之后）：不再等待
            advanceTimeBy(300)
            q.request(Routes.PROFILE)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(0L to Routes.TODAY, 300L to Routes.PROFILE), log)
        }

    @Test
    fun queuedNavigationRestartsTheClock() =
        runTest {
            val q = queue(budgetMs = 300)
            q.request(Routes.PROFILE)
            runCurrent()
            advanceTimeBy(100)
            q.request(Routes.TODAY)
            // 排队的导航在 t=300 执行；下一次请求的窗口必须从 300 起算，而不是从更早的 0
            advanceTimeBy(199)
            runCurrent()
            assertEquals(listOf(0L to Routes.PROFILE), log)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf(0L to Routes.PROFILE, 300L to Routes.TODAY), log)
            q.request(Routes.SCHEDULE)
            advanceTimeBy(299)
            runCurrent()
            assertEquals(listOf(0L to Routes.PROFILE, 300L to Routes.TODAY), log)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(
                listOf(0L to Routes.PROFILE, 300L to Routes.TODAY, 600L to Routes.SCHEDULE),
                log,
            )
        }

    @Test
    fun budgetIsReReadAfterEachNavigation() =
        runTest {
            // 生产里预算随系统动画缩放折算：每次导航后都要重新求值，
            // 否则中途改缩放（开发者选项）会拿旧窗口
            val budgets = ArrayDeque(listOf(300L, 600L, 600L))
            val q =
                TabSwitchQueue(
                    scope = backgroundScope,
                    budgetMs = { budgets.removeFirst() },
                    nowMs = { testScheduler.currentTime },
                    navigate = { route -> log += testScheduler.currentTime to route },
                )
            q.request(Routes.PROFILE)
            runCurrent()
            assertEquals(listOf(0L to Routes.PROFILE), log)
            advanceTimeBy(100)
            q.request(Routes.TODAY)
            advanceTimeBy(199)
            runCurrent()
            assertEquals(listOf(0L to Routes.PROFILE), log)
            advanceTimeBy(1)
            runCurrent()
            // 第二次导航在 t=300（首个预算 300）；第三个请求的窗口按新预算 600、从 t=300 起算
            assertEquals(listOf(0L to Routes.PROFILE, 300L to Routes.TODAY), log)
            q.request(Routes.SCHEDULE)
            advanceTimeBy(599)
            runCurrent()
            assertEquals(listOf(0L to Routes.PROFILE, 300L to Routes.TODAY), log)
            advanceTimeBy(1)
            runCurrent()
            assertEquals(
                listOf(0L to Routes.PROFILE, 300L to Routes.TODAY, 900L to Routes.SCHEDULE),
                log,
            )
        }
}
