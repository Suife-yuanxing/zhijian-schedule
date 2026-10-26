package com.zhijian.schedule.core.ui

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * 系统是否允许播放动画。
 *
 * `Settings.Global.ANIMATOR_DURATION_SCALE == 0` 是 Android 表达「不要动画」的统一开关
 * （开发者选项的 Animator duration scale 关掉，以及无障碍里的「移除动画」都会写这个值）。
 *
 * 为什么纯装饰的动效必须查它：WCAG 2.3.3（AAA）把「由交互触发的运动」列为需要用户可控，
 * 并点名 **parallax**——卡片倾斜正是这一类；Apple tvOS 规范 ACCESS-04 同样是硬要求
 * （"Parallax effects and other animations must be reduced or disabled when the user
 * enables Reduce Motion"）。业界组件都照此办理：beUI 的 TiltCard 在 reduced motion 下
 * 「卡片保持平放、反光不绘制」。
 *
 * Compose 的 spring/tween 本身不会自动遵守这个设置（只有 Material 组件内部的部分动画会读它），
 * 所以浮岛、卡片倾斜这类自绘动效必须自己读。
 *
 * 用 ContentObserver 而不是只读一次：用户在系统设置里一改就立刻生效，无需重启 Activity。
 */
@Composable
fun rememberMotionAllowed(): Boolean {
    val resolver = LocalContext.current.contentResolver
    var allowed by remember(resolver) {
        mutableStateOf(resolver.readAnimatorScale() > 0f)
    }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                allowed = resolver.readAnimatorScale() > 0f
            }
        }
        resolver.registerContentObserver(
            Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),
            false,
            observer,
        )
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return allowed
}

/**
 * [rememberMotionAllowed] 的反向表述：true 表示应当**跳过动画直接落位**。
 *
 * 与 [rememberMotionAllowed] 读的是同一个系统开关，只是调用处读起来更顺——
 * 浮岛的收敛/展开是「有条件地跳过」，写成 disabled 比否定式更直白。
 */
@Composable
fun rememberAnimationsDisabled(): Boolean = !rememberMotionAllowed()

/** 读「动画时长缩放」；个别 ROM 会拒绝该 Global 读，失败按「允许动画」回落。 */
private fun ContentResolver.readAnimatorScale(): Float = try {
    Settings.Global.getFloat(this, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
} catch (_: Exception) {
    1f
}
