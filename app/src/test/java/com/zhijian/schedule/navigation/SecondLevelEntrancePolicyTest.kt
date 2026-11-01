package com.zhijian.schedule.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 二级页入场闸门（规范见 docs/specs/2026-10-30-根页切换规范.md 的同一条规矩）。
 *
 * 钉住的是「打开同一张入口卡第二次，不许再播一遍入场」这条规矩——
 * 它正是主人报的「打开二级页像刷新了一遍、内容从左上角长出来」的根因。
 */
class SecondLevelEntrancePolicyTest {
    @Test
    fun firstOpenOfEachPagePlays() {
        val gate = secondLevelEntranceGate()
        listOf(Routes.PROFILE_GRADES, Routes.PROFILE_EXAMS, Routes.PROFILE_SETTINGS).forEach { route ->
            assertTrue(route, gate.shouldPlay(route))
        }
    }

    @Test
    fun afterMarkPlayedThatPageNeverPlaysAgain() {
        val gate = secondLevelEntranceGate()
        gate.markPlayed(Routes.PROFILE_GRADES)
        // 返回再进 = 新 back stack entry。闸门在宿主上，所以这里仍然是 false
        assertFalse(gate.shouldPlay(Routes.PROFILE_GRADES))
        // 其他页面还没播过，各自第一次照常播
        assertTrue(gate.shouldPlay(Routes.PROFILE_EXAMS))
        assertTrue(gate.shouldPlay(Routes.PROFILE_SETTINGS))
    }

    @Test
    fun markingOnePageDoesNotCloseTheOthers() {
        val gate = secondLevelEntranceGate()
        gate.markPlayed(Routes.PROFILE_EXAMS)
        gate.markPlayed(Routes.PROFILE_SETTINGS)
        assertTrue(gate.shouldPlay(Routes.PROFILE_GRADES))
        assertEquals(listOf(Routes.PROFILE_EXAMS, Routes.PROFILE_SETTINGS), gate.playedRoutes)
    }

    @Test
    fun markingIsIdempotent() {
        val gate = secondLevelEntranceGate()
        gate.markPlayed(Routes.PROFILE_GRADES)
        gate.markPlayed(Routes.PROFILE_GRADES)
        gate.markPlayed(Routes.PROFILE_GRADES)
        assertEquals(listOf(Routes.PROFILE_GRADES), gate.playedRoutes)
    }

    @Test
    fun rootPagesNeverParticipate() {
        val gate = secondLevelEntranceGate()
        listOf(Routes.TODAY, Routes.SCHEDULE, Routes.PROFILE).forEach { route ->
            assertFalse(route, gate.shouldPlay(route))
        }
        // 往闸门里塞根页也不该被记住
        gate.markPlayed(Routes.PROFILE)
        assertEquals(emptyList<String>(), gate.playedRoutes)
    }

    @Test
    fun nullRouteNeverPlays() {
        val gate = secondLevelEntranceGate()
        assertFalse(gate.shouldPlay(null))
        gate.markPlayed(null)
        assertEquals(emptyList<String>(), gate.playedRoutes)
    }

    @Test
    fun restoredGateKeepsItsState() {
        // 宿主用 rememberSaveable 存 playedRoutes 列表；恢复后的语义必须与关闸一致
        val restored = secondLevelEntranceGate(setOf(Routes.PROFILE_GRADES))
        assertFalse(restored.shouldPlay(Routes.PROFILE_GRADES))
        assertTrue(restored.shouldPlay(Routes.PROFILE_EXAMS))
    }

    @Test
    fun entryRoutesCoverEveryProfileEntryCard() {
        // 四张卡里只有导入页走四向 None 转场（它自己不播分段入场），
        // 其余三页必须全部在闸门内——少一个就是「那一页还在刷新」
        assertEquals(
            setOf(Routes.PROFILE_GRADES, Routes.PROFILE_EXAMS, Routes.PROFILE_SETTINGS),
            SecondLevelEntrancePolicy.routes,
        )
    }
}
