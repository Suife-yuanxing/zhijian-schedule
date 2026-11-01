package com.zhijian.schedule.core.jw.credential

/**
 * 「这次登录成功后要不要弹气泡问一句」的判定。
 *
 * 抽成纯函数是为了能进本机单测：它是「会不会打扰用户」的唯一开关，
 * 判错的两个方向都要付代价——该问而不问是**悄悄存了密码**，
 * 不该问而问是**每次登录都被弹一次**。两种错误都不会抛异常，只会让用户困惑。
 *
 * ## 三条不打扰的情况
 * 1. 这对账号密码与已保存的**完全一致**：重复登录，没有什么新东西可存。
 * 2. 候选不合法（空密码 / 超长 / 含控制字符）：页面抓错了，不该拿去问人。
 * 3. 提交页与用户配置的教务**不同源**：走统一认证的学校，那套密码属于 authserver，
 *    存下来填回教务登录页未必有效。
 */
object JwCredentialPromptPolicy {
    /** 与 [com.zhijian.schedule.feature.jw.JwImportViewModel] 里的长度上限保持一致 */
    private const val MAX_LENGTH = 200

    fun shouldAsk(
        origin: String,
        username: String,
        password: String,
        saved: JwCredential?,
    ): Boolean {
        if (!isPlausible(password) || !isPlausible(username)) return false
        if (saved == null) return true
        val sameOrigin = saved.origin.equals(origin, ignoreCase = true)
        val sameAccount = saved.username == username && saved.password == password
        // 已存的就是这一条：无需再问。已存的是**另一个账号**则要问——换号是真实场景
        return !(sameOrigin && sameAccount)
    }

    private fun isPlausible(value: String): Boolean =
        value.isNotEmpty() && value.length <= MAX_LENGTH && value.none { it.isISOControl() }
}
