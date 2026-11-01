package com.zhijian.schedule.feature.jw

import com.zhijian.schedule.core.ui.island.IslandHintTone

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
 * 浮岛提示要显示的一件事：**刚刚发生了什么**。
 *
 * 与 [JwCredentialFill] 的区别是时态：那个是「下次打开登录页要填什么」，一直躺在内存里；
 * 这个是「刚才那一下的结局」，只活五秒（见 [com.zhijian.schedule.core.ui.island.IslandHint]）
 * 随后就该消失。
 *
 * ## 为什么成功与失败共用一条通道
 * 「填进去了」和「没填进去」是同一件事的两种结局。分成两条提示通道，用户会看到
 * 两套位置、两套时长、两套动效，然后要分别学着认——而同一条通道上只差图标与颜色，
 * 一眼就知道「哦，这次没成」。
 *
 * ## 为什么不带密码
 * 提示只需要说清「填的是哪个号」。密码摆到屏幕上没有任何收益，
 * 只是多一处会被旁人看到的可能。
 */
data class JwCredentialFillNotice(
    /** 这一次提示的身份与重置令牌，见 [at] */
    val tone: IslandHintTone,
    /** 一行说明，直接上屏；由 [credentialFillHintText] 同一族函数生成 */
    val message: String,
    /**
     * 这一次结局发生的时刻（[System.currentTimeMillis]）。
     *
     * 它同时充当两件事：**身份**与**重新计时的令牌**。用户清掉 cookie、重新打开登录页，
     * 我们会再填一次——此时浮岛提示必须从头开始走进度条，而不是接着上一轮的余量。
     * 用时间戳而不是布尔值，是因为「又发生了一次」和「还挂着上一轮」在布尔值上不会变。
     */
    val at: Long,
)

/**
 * 回填成功的那句话：说清「填的是哪个号」，并提醒用户还差最后一步。
 *
 * 抽成纯函数是为了能进本机单测：它是用户在这条提示里读到的**唯一**内容，
 * 而这条提示只活五秒、不会再来一次——把话说错（比如写成「已为你保存密码」）
 * 用户也没有第二次机会澄清。
 *
 * 不写「已为你保存密码」：回填与保存是两件事。保存要用户按气泡里的「记住」才发生，
 * 把两者说成一件事会让用户以为密码已经存好了，于是下次换设备时找不到它。
 */
internal fun credentialFillHintText(username: String): String = "已填入保存的账号 " + username + " · 确认后点登录"

/**
 * 回填失败的那句话：说清**没能做什么**，并把用户下一步能做的事给出来。
 *
 * 两种失败原因各自给一条，因为它们该做的事完全不同：
 * - 页面上摸不到登录框：页面结构变了或把框藏了起来，用户得自己找输入位置；
 * - 有输入框但写不进去：页面自己锁死了 value（部分教务在 onload 之后把 input 设成只读），
 *   只能手输，别让人白等。
 *
 * 不给「重试」按钮：这些都是**自动**发生的，用户手动重试的等价动作就是再打开一次页面，
 * 而给一个只会重复同样失败的按钮，比不给更让人困惑。
 */
internal fun credentialFillFailedText(reason: JwCredentialFillFailure): String =
    when (reason) {
        JwCredentialFillFailure.NoInput -> "没能自动填入 · 这一页没有登录框，请手动输入"
        JwCredentialFillFailure.WriteRejected -> "没能自动填入 · 登录框不接受写入，请手动输入"
    }

/**
 * 回填失败的两种原因。
 *
 * 为什么要分开而不是统一说「自动填入失败」：两种情况下用户该做的事完全不同
 * （等一等 / 自己动手输），而这条提示只活五秒，说不清就等于白弹。
 *
 * ## 为什么只有两种
 * 「没有保存过凭据」不算失败——那是**每一位新用户**的常态，把它做成红叉加震动，
 * 等于每次打开登录页都被告知一次「你还没配」。这条通道只报「本该填上、却没填上」。
 */
enum class JwCredentialFillFailure {
    /** 停在登录页，页面上却摸不到可用的密码框：页面结构变了，或它把框藏了起来 */
    NoInput,

    /** 有密码框，但脚本没能把值写进去（页面把 value 锁成只读等） */
    WriteRejected,
}

/**
 * 保存失败的那句话。
 *
 * 只有两种结局值得单独说：密钥不可用（本机 Keystore 坏了，用户做什么都没用，
 * 但至少要让他知道「不是你的操作错了」）与候选已过期（登录账号在气泡挂着期间变了）。
 * 其余磁盘错误归成一类——用户对它们的可操作面完全相同：再试一次。
 */
internal fun credentialSaveFailedText(reason: JwCredentialSaveFailure): String =
    when (reason) {
        JwCredentialSaveFailure.KeyUnavailable -> "没能记住 · 本机密钥不可用，下次仍需手动输入"
        JwCredentialSaveFailure.CandidateChanged -> "没能记住 · 登录账号已变化，请重新确认"
        JwCredentialSaveFailure.Storage -> "没能记住 · 写入本机存储失败，请稍后再试"
    }

/** 保存失败的三种原因；口径见 [credentialSaveFailedText] */
enum class JwCredentialSaveFailure {
    /** Keystore 密钥不可用（加密失败），凭据根本没有离内存 */
    KeyUnavailable,

    /** 气泡挂着期间用户又登录了一次，待存的候选已经换人 */
    CandidateChanged,

    /** 加密成功但落盘失败 */
    Storage,
}
