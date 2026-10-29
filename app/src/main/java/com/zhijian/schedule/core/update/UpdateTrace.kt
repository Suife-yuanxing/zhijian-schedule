package com.zhijian.schedule.core.update

import android.util.Log

/**
 * 更新下载子系统的诊断打点：本包的日志只从这里出，不再各处直接调 android.util.Log。
 *
 * 形状沿用 core/ui 的 RevealTrace（固定 TAG + 惰性消息），刻意不加 DEBUG 开关：
 * 这些日志在原实现里就是常开的，本次结构整理不改变日志语义。
 */
internal object UpdateTrace {
    private const val TAG = "UpdateDownload"

    fun log(message: () -> String) {
        Log.i(TAG, message())
    }
}
