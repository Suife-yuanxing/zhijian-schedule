package com.zhijian.schedule.feature.jw

/**
 * 「这次页面加载要回填的凭据」。
 *
 * 只是把三个字符串打包过一遍 UI 层，没有任何生命周期：谁需要填、什么时候填由
 * [com.zhijian.schedule.feature.jw.JwImportViewModel] 决定，[WebViewBox] 只负责在
 * 页面加载完成的那一刻把它写进登录框。
 */
data class JwCredentialFill(
    /** 保存这条凭据时的页面 origin（`scheme://host`）；只有当前页 origin 与它一致才回填 */
    val origin: String,
    val username: String,
    val password: String,
)

/**
 * 「刚刚把保存的账号填进登录页了」这一件事。
 *
 * 与 [JwCredentialFill] 的区别是**时态**：那个是「下次打开登录页要填什么」，
 * 一直躺在内存里；这个是「刚刚真的填了一次」，只活五秒（见
 * [com.zhijian.schedule.core.ui.island.IslandHint]）随后就该消失。
 *
 * ## 为什么带一个时间戳
 * 它同时充当两件事：**身份**与**重新计时的令牌**。用户清掉 cookie、重新打开登录页，
 * 我们会再填一次——此时浮岛提示必须从头开始走进度条，而不是接着上一轮的余量。
 * 用时间戳而不是布尔值，是因为「又填了一次」和「还挂着上一轮」在布尔值上是同一个值。
 *
 * ## 为什么不带密码
 * 提示只需要说清「填的是哪个号」。密码摆到屏幕上没有任何收益，
 * 只是多一处会被旁人看到的可能。
 */
data class JwCredentialFillNotice(
    val username: String,
    /** 这一次填入发生的时刻（[System.currentTimeMillis]）；同时是进度条的重置令牌 */
    val filledAt: Long,
)

/**
 * 浮岛提示的一句话：说清「填的是哪个号」，并提醒用户还差最后一步。
 *
 * 抽成纯函数是为了能进本机单测：它是用户在这条提示里读到的**唯一**内容，
 * 而这条提示只活五秒、不会再来一次——把话说错（比如写成「已为你保存密码」）
 * 用户也没有第二次机会澄清。
 *
 * 不写「已为你保存密码」：回填与保存是两件事。保存要用户按气泡里的「记住」才发生，
 * 把两者说成一件事会让用户以为密码已经存好了，于是下次换设备时找不到它。
 */
internal fun credentialFillHintText(username: String): String =
    "已填入保存的教务账号（" + username + "），确认无误后点登录"
