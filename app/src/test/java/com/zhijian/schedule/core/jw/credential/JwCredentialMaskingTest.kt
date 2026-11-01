package com.zhijian.schedule.core.jw.credential

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 账号打码的边界。
 *
 * 这层的价值全在"短账号"那一档：教学号普遍 8~12 位，留首尾刚好认得出；
 * 但测试号 / 工号只有两三位，留首尾就等于全露——那还不如不遮。
 * 设置页与确认气泡共用这一份规则，所以两处显示永远一致。
 */
class JwCredentialMaskingTest {
    @Test
    fun emptyStaysEmpty() {
        assertEquals("", JwCredentialMasking.mask(""))
    }

    @Test
    fun shortAccountIsFullyMasked() {
        // 一两位的账号留首尾就等于没遮
        assertEquals("*", JwCredentialMasking.mask("a"))
        assertEquals("**", JwCredentialMasking.mask("ab"))
    }

    @Test
    fun mediumAccountKeepsOneHeadOneTail() {
        assertEquals("a***f", JwCredentialMasking.mask("abcdef"))
    }

    @Test
    fun longAccountKeepsTwoEachEnd() {
        assertEquals("20***01", JwCredentialMasking.mask("20230001"))
        assertEquals("20***ab", JwCredentialMasking.mask("202300001ab"))
    }

    @Test
    fun maskNeverEqualsOriginal() {
        // 一句话就能钉死的性质：打码后的字符串不可能等于原文（长度 >= 3 时）
        listOf("abc", "20230001", "teacher01").forEach { account ->
            assertEquals(false, JwCredentialMasking.mask(account) == account)
        }
    }
}
