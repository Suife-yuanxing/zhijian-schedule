package com.zhijian.schedule.core.ui.island

import android.content.Context
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * 失败提示那一下震动的**时长**（毫秒）。
 *
 * 45ms 是「一个短促的否定」的量级：短到不打断手上的动作，长到能被感知。
 * 这个数不是随手取的，见 [IslandHaptics.failure] 里那段真机实测。
 */
const val ISLAND_FAIL_HAPTIC_MS: Long = 45L

/**
 * 失败提示的触觉反馈。
 *
 * ## 为什么不用 HapticFeedbackType.Reject
 * 语义上它是对的（REJECT = 17），但**时长不由我们决定**。真机上抓到的波形：
 * `com.zhijian.schedule.debug | performHapticFeedback(constant=17)
 *  | duration: 495ms | played: OplusPrebakedSegment{mEffectId=3, mDuration=480}`
 * ——ColorOS 把 REJECT 映射成一段 480ms 的长震。
 * 那是「出大事了」的手感，而这里只是「这一页没能自动填入」，
 * 一条五秒后自己消失的提示配不上近半秒的嗡嗡声。
 *
 * ## 为什么自己控制时长
 * 自定义时长需要 VIBRATE 权限（普通权限，安装即授予、无运行时弹窗），
 * 换来的是「短暂」这件事真的成立，而不是各家 ROM 各给一个数。
 *
 * ## 取不到 Vibrator 时
 * 回落 [android.view.HapticFeedbackConstants.REJECT]——宁可长一点，也不能完全没有反馈：
 * 失败提示正压在网页上，用户此刻的注意力未必在顶部，
 * 触觉是唯一不依赖注视的通道。
 */
object IslandHaptics {
    /**
     * 震一下「没成」。
     *
     * 用 [VibrationEffect.createOneShot] 而不是预置效果（TICK / CLICK）：
     * 预置效果的时长同样由 ROM 决定，绕了一圈还是回到了不可控。
     *
     * USAGE_TOUCH + DEFAULT_AMPLITUDE：让系统把它归进「触碰反馈」这一类，
     * 用户在系统设置里调「触摸震动强度」时，这条反馈会跟着一起变——
     * 那是用户的偏好，不该由应用绕过去。
     */
    fun failure(context: Context) {
        val vibrator = vibratorOf(context)
        if (vibrator == null || !vibrator.hasVibrator()) return
        val effect =
            VibrationEffect.createOneShot(
                ISLAND_FAIL_HAPTIC_MS,
                VibrationEffect.DEFAULT_AMPLITUDE,
            )
        // USAGE_TOUCH 是 API 33 才有的重载；低版本走单参版本，行为一致。
        // 注意 USAGE_TOUCH 是个 int 常量，重载要的是 VibrationAttributes 对象——
        // 直接传常量编译不过（vibrate(effect, p1: VibrationAttributes)）
        val ok =
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val attrs = VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH)
                    vibrator.vibrate(effect, attrs)
                } else {
                    vibrator.vibrate(effect)
                }
            }.isSuccess
        if (!ok) {
            // 部分 ROM 会拒绝自定义波形（省电模式 / 触觉反馈总开关关闭）
            // 此时安静地放弃，不额外抛异常打断这条提示的其余部分
        }
    }

    /**
     * 取本机振动器。
     *
     * API 31 起 Vibrator 藏在 VibratorManager 后面，直接 getSystemService(VIBRATOR) 会拿到
     * 一个不可用的壳；低版本才有那个直接入口。
     */
    private fun vibratorOf(context: Context): Vibrator? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val manager = context.getSystemService(VibratorManager::class.java)
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        }.getOrNull()
}
