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
 * 教务回填专用的浮岛提示 = 通用的 [com.zhijian.schedule.core.ui.island.IslandNotice]。
 *
 * ## 为什么它变成了别名而不是自己的数据类
 * 这条通道原本专属于「回填保存的教务账号」，于是类型也就跟着叫了这个名字。但浮岛
 * 本身没有凭据语义——它是全站唯一一条「说完就收」的提示通道。第二个使用者
 * （开学日期缺来源的提醒，见 `SettingsViewModel`）出现时，若照着抄一个平行的类型，
 * 同一条通道就会有两套位置、两套时长、两套动效。
 *
 * 保留这个名字是为了让 [JwImportViewModel] 与它那批单测继续读得懂：
 * 「教务回填的那条浮岛提示」仍然是这个名字，只是它现在与别的提示**同源**。
 */
typealias JwCredentialFillNotice = com.zhijian.schedule.core.ui.island.IslandNotice

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
