package com.zhijian.schedule.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 设置页「教务账号密码」那一块的显示判定。
 *
 * 这组测试钉的是**判定顺序**，不是文案。真机上出过一次：
 * 凭据已经存好了，但 jw_url 是空的，于是界面被一句「先填写教务地址」
 * 整个盖住——用户以为保存失败，实际上是显示条件把数据挡在后面。
 *
 * 这类 bug 在界面上看不出来：两句话都是灰字、都像在讲道理，
 * 唯一能固定下来的方式就是把它做成纯函数再逐格枚举。
 */
class JwCredentialPlaceholderTest {
    @Test
    fun credentialWinsOverMissingAddress() {
        // 核心回归：有凭据时，地址在不在都不影响显示摘要
        assertNull(jwCredentialPlaceholder(hasCredential = true, addressConfigured = false))
        assertNull(jwCredentialPlaceholder(hasCredential = true, addressConfigured = true))
    }

    @Test
    fun addressWithoutCredentialAsksToLogin() {
        assertEquals(
            JwCredentialPlaceholder.NoCredential,
            jwCredentialPlaceholder(hasCredential = false, addressConfigured = true),
        )
    }

    @Test
    fun nothingConfiguredAsksForAddressFirst() {
        assertEquals(
            JwCredentialPlaceholder.NoAddress,
            jwCredentialPlaceholder(hasCredential = false, addressConfigured = false),
        )
    }

    @Test
    fun everyCombinationIsCovered() {
        // 两个布尔的全排列都必须有确定结论，不允许某个组合落到「什么都不显示」
        for (hasCredential in listOf(true, false)) {
            for (address in listOf(true, false)) {
                val result = jwCredentialPlaceholder(hasCredential, address)
                val expectedNull = hasCredential
                assertEquals(
                    "hasCredential=$hasCredential addressConfigured=$address",
                    expectedNull,
                    result == null,
                )
            }
        }
    }
}
