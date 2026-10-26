package com.zhijian.schedule.core.repository

import com.zhijian.schedule.core.repository.ImportGuard
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ImportGuardTest {

    @Test
    fun `first sync never blocks and never deletes`() {
        assertNull(ImportGuard.evaluate(existingCount = 20, addedCount = 30, removedCount = 20, firstSync = true))
    }

    @Test
    fun `large removal blocks`() {
        // 20 门现有课，阈值 max(3, 5) = 5；取消 6 门触发
        assertNotNull(ImportGuard.evaluate(existingCount = 20, addedCount = 0, removedCount = 6, firstSync = false))
    }

    @Test
    fun `small removal passes`() {
        assertNull(ImportGuard.evaluate(existingCount = 20, addedCount = 0, removedCount = 5, firstSync = false))
    }

    @Test
    fun `large addition blocks`() {
        // 20 门现有课，阈值 max(5, 8) = 8；新增 9 门触发
        assertNotNull(ImportGuard.evaluate(existingCount = 20, addedCount = 9, removedCount = 0, firstSync = false))
    }

    @Test
    fun `tiny timetable is exempt`() {
        assertNull(ImportGuard.evaluate(existingCount = 4, addedCount = 0, removedCount = 4, firstSync = false))
    }
}
