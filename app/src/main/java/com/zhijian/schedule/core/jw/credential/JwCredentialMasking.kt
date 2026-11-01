package com.zhijian.schedule.core.jw.credential

/**
 * 账号打码：设置页的摘要与导入页的确认气泡共用这一份规则。
 *
 * 为什么要收在一处：两处都要显示「记住的是哪个账号」，但都不能把账号原样摊在屏幕上
 * （旁边站个人就能读走学号）。同一个账号在两个界面显示成两种样子，用户会以为记的是两个号。
 *
 * 规则按长度分档，不是简单截前几位——学号普遍 8~12 位，只留首尾才认得出；
 * 短账号（测试号、工号）留首尾就等于全露，所以全遮。
 */
object JwCredentialMasking {
    fun mask(username: String): String =
        when {
            username.isEmpty() -> ""
            username.length <= SHORT_LIMIT -> "*".repeat(username.length)
            username.length <= MEDIUM_LIMIT -> username.take(1) + "***" + username.takeLast(1)
            else -> username.take(2) + "***" + username.takeLast(2)
        }

    private const val SHORT_LIMIT = 2
    private const val MEDIUM_LIMIT = 6
}
