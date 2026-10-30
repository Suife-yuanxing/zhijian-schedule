package com.zhijian.schedule.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 根页入场闸门（规范见 docs/specs/2026-10-30-根页切换规范.md）。
 *
 * 这组断言钉住的是「切页一律不播入场」这条规矩——它正是主人反复抱怨的
 * 「切页刷新、从右上方打开」的根因所在。
 */
class RootEntrancePolicyTest {
    @Test
    fun coldStartOnAnyRootPlays() {
        assertTrue(RootEntrancePolicy.shouldPlay(consumed = false, route = Routes.TODAY))
        assertTrue(RootEntrancePolicy.shouldPlay(consumed = false, route = Routes.SCHEDULE))
        assertTrue(RootEntrancePolicy.shouldPlay(consumed = false, route = Routes.PROFILE))
    }

    @Test
    fun afterGateClosedNothingPlays() {
        val gate = rootEntranceGate(played = true)
        assertFalse(gate.shouldPlay(Routes.TODAY))
        assertFalse(gate.shouldPlay(Routes.SCHEDULE))
        assertFalse(gate.shouldPlay(Routes.PROFILE))
    }

    @Test
    fun secondLevelPagesNeverPlay() {
        val secondLevel =
            listOf(
                Routes.SCHEDULE_EDITOR,
                Routes.SCHEDULE_IMPORTER,
                Routes.TODAY_EDITOR,
                Routes.PROFILE_SETTINGS,
                Routes.PROFILE_GRADES,
            )
        secondLevel.forEach { route ->
            assertFalse(route, RootEntrancePolicy.shouldPlay(consumed = false, route = route))
        }
    }

    @Test
    fun nullRouteNeverPlays() {
        assertFalse(RootEntrancePolicy.shouldPlay(consumed = false, route = null))
    }

    @Test
    fun gateStaysClosedAcrossTabSwitches() {
        val gate = rootEntranceGate(played = false)
        assertTrue(gate.shouldPlay(Routes.PROFILE))
        gate.markPlayed()
        // 第一次切走再切回：不播——「从右上方打开」正是在这里消失的
        assertFalse(gate.shouldPlay(Routes.PROFILE))
        assertFalse(gate.shouldPlay(Routes.TODAY))
        assertFalse(gate.shouldPlay(Routes.SCHEDULE))
    }

    @Test
    fun closingGateIsIdempotent() {
        val gate = rootEntranceGate(played = false)
        gate.markPlayed()
        gate.markPlayed()
        gate.markPlayed()
        assertTrue(gate.played)
        assertFalse(gate.shouldPlay(Routes.SCHEDULE))
    }

    @Test
    fun restoredGateKeepsItsState() {
        // 宿主用 rememberSaveable 只存这一个布尔；恢复后的语义与关闸一致
        assertFalse(rootEntranceGate(played = true).shouldPlay(Routes.SCHEDULE))
        // 反向：重建时若还没放过，首帧仍应放行一次
        assertTrue(rootEntranceGate(played = false).shouldPlay(Routes.SCHEDULE))
    }
}
