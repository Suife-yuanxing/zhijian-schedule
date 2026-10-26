package com.zhijian.schedule.core.capsule

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * 胶囊后台可靠性的用户侧设置入口。
 *
 * ColorOS 的「应用速冻」/ 电池优化会在后台冻结进程，导致状态切换与倒计时刷新停摆。
 * 允许后台运行后，普通精确闹钟即可准点唤醒；未允许时 CapsuleCoordinator 会用
 * setAlarmClock 兜底（状态栏会短暂出现闹钟图标）。
 */
object CapsuleReliability {

    fun isBatteryOptimizationIgnored(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                context.getSystemService(PowerManager::class.java)
                    ?.isIgnoringBatteryOptimizations(context.packageName) ?: true
            }.getOrDefault(true)
        } else {
            true
        }

    /**
     * 弹出系统「允许后台运行」确认框；无法直接请求时退回电池优化列表。
     * 返回 true 表示已经启动了某个系统页面。
     */
    @SuppressLint("BatteryLife")
    fun requestIgnoreBatteryOptimization(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(direct) }.isSuccess) return true
        val list = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(list) }.isSuccess
    }

    /** 应用详情页：ColorOS 上可继续打开「自启动」「耗电管理」 */
    fun openAppDetails(context: Context): Boolean =
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.isSuccess
}
