package com.zhijian.schedule.core.update

import android.os.SystemClock
import android.util.Log
import com.zhijian.schedule.BuildConfig

/**
 * 更新下载子系统的诊断打点：本包的日志只从这里出，不再各处直接调 android.util.Log。
 *
 * 形状沿用 core/ui 的 RevealTrace（固定 TAG + 惰性消息），并额外带一条**相对时间戳**：
 * 这个子系统最常被问的问题就是「点了下载之后那几百毫秒在干什么」，没有相对时间就只能猜。
 *
 * **只在 debug 包输出**：正文里带下载源 URL 与探测细节，线上包不该持续往 logcat 写这些；
 * 而它存在的意义（排查「点下载为什么慢」）全部发生在开发期。
 * 惰性 lambda 保留：关掉时连字符串拼接都不发生，调用点不需要包 if。
 */
internal object UpdateTrace {
    private const val TAG = "UpdateDownload"

    private val enabled = BuildConfig.DEBUG

    /** 起点（elapsedRealtime）；0 = 还没 reset 过，第一条日志自行归零 */
    private var origin = 0L

    fun reset() {
        if (enabled) origin = SystemClock.elapsedRealtime()
    }

    fun log(message: () -> String) {
        if (!enabled) return
        val now = SystemClock.elapsedRealtime()
        if (origin == 0L) origin = now
        Log.i(TAG, "+" + (now - origin) + "ms " + message())
    }
}
