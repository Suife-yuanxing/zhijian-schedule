package com.zhijian.schedule.core.capsule

import android.content.Context
import androidx.core.content.edit

/**
 * 「用户主动划掉实时卡片」的记账。
 *
 * 官方风险提示（Android 16 Live Updates 文档口径）：应用**不得**重新发布用户已经关闭的
 * 动态，否则可能被用户直接撤销该应用的发布权限。
 *
 * 改造前的行为是危险的：LiveCapsuleService 每 30s 检查 `activeNotifications`，
 * 一旦发现实时卡片不在就无条件「自愈重建」——这与「系统超时撤卡」的正当场景混在一起，
 * 用户手动划掉卡片后，30 秒内它就会自己爬回来，属于典型的与用户对着干。
 *
 * 区分办法：给实时卡片挂 `setDeleteIntent`，只有**用户主动清除**才会触发它
 * （`timeoutAfter` 到期由 system_server 撤下时不走 deleteIntent）。
 * 触发后按 [sessionKey] 记账，同一节课内不再重发；进入新课程（新的 sessionKey）自动失效。
 */
object CapsuleDismissTracker {
    private const val PREFS = "capsule_dismiss"
    private const val KEY_DISMISSED_SESSION = "dismissed_session"

    /** 记录用户划掉了某节课的卡片 */
    fun markDismissed(
        context: Context,
        sessionKey: String,
    ) {
        runCatching { prefs(context).edit { putString(KEY_DISMISSED_SESSION, sessionKey) } }
    }

    /** 这节课的卡片是否已被用户划掉 */
    fun isDismissed(
        context: Context,
        sessionKey: String?,
    ): Boolean {
        if (sessionKey == null) return false
        return runCatching {
            prefs(context).getString(KEY_DISMISSED_SESSION, null) == sessionKey
        }.getOrDefault(false)
    }

    /** 状态离开实时态时清账，避免下节课被上节课的标记误伤 */
    fun clear(context: Context) {
        runCatching { prefs(context).edit { remove(KEY_DISMISSED_SESSION) } }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
