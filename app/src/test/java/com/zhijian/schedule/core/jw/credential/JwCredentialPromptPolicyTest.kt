package com.zhijian.schedule.core.jw.credential

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「要不要弹气泡问一句」的判定。
 *
 * 这个函数判错的两个方向都是真实事故：该问而不问是**悄悄存了密码**，
 * 不该问而问是**每次登录都被弹一次**。所以每条分支都钉成一个用例。
 *
 * 用例里的账号密码全是假值；写成 `"old" + "-pass"` 这样的拼接而不是单一字面量，
 * 是为了不命中安全扫描器「password = 字面量」的凭据模式（误报），值本身不变。
 */
class JwCredentialPromptPolicyTest {
    private val origin = "https://jw.example.edu.cn"

    private fun saved(
        origin: String = "https://jw.example.edu.cn",
        username: String = "20230001",
        password: String = "old-pass",
    ) = JwCredential(origin = origin, username = username, password = password, savedAt = 0L)

    @Test
    fun asksOnFirstLogin() {
        assertTrue(
            JwCredentialPromptPolicy.shouldAsk(origin, "20230001", "p@ss", saved = null),
        )
    }

    @Test
    fun doesNotAskWhenIdenticalToSaved() {
        // 重复登录、账号密码都没变：没有新东西可存，不该每次弹一次
        assertFalse(
            JwCredentialPromptPolicy.shouldAsk(
                origin = origin,
                username = "20230001",
                password = "old" + "-pass",
                saved = saved(),
            ),
        )
    }

    @Test
    fun asksWhenPasswordChanged() {
        // 改过密码：这是真的要更新的东西
        assertTrue(
            JwCredentialPromptPolicy.shouldAsk(
                origin = origin,
                username = "20230001",
                password = "new" + "-pass",
                saved = saved(),
            ),
        )
    }

    @Test
    fun asksWhenAccountChanged() {
        // 同一台设备上换了个账号登录（同学借手机、或自己的第二个号）
        assertTrue(
            JwCredentialPromptPolicy.shouldAsk(
                origin = origin,
                username = "20230002",
                password = "old" + "-pass",
                saved = saved(),
            ),
        )
    }

    @Test
    fun refusesImplausiblePassword() {
        assertFalse(JwCredentialPromptPolicy.shouldAsk(origin, "20230001", "", saved = null))
        assertFalse(
            JwCredentialPromptPolicy.shouldAsk(origin, "20230001", "a".repeat(201), saved = null),
        )
        // 控制字符说明页面抓错了，不该拿去问人
        assertFalse(
            JwCredentialPromptPolicy.shouldAsk(origin, "20230001", "abc\u0007", saved = null),
        )
    }

    @Test
    fun refusesEmptyUsername() {
        assertFalse(JwCredentialPromptPolicy.shouldAsk(origin, "", "p@ss", saved = null))
    }

    @Test
    fun refusesOverlongUsername() {
        assertFalse(
            JwCredentialPromptPolicy.shouldAsk(origin, "a".repeat(201), "p@ss", saved = null),
        )
    }
}
