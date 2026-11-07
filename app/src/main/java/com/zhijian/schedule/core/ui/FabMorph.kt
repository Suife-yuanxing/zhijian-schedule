package com.zhijian.schedule.core.ui

import androidx.compose.animation.core.Easing

/**
 * 加号 ↔ 叉号的形变规则（纯逻辑，无 Android 依赖，可进本机单测）。
 *
 * 为什么单独抽一个文件而不是写在 ScheduleFab 里：转角是「点击 → 变形」这条时间线上
 * 唯一有判定逻辑的一环（什么时候算转完），而它必须能被 JVM 单测钉住——
 * 真机 240fps 逐帧验一次的成本远高于一个纯函数断言。
 *
 * ## 形变不再阻塞页面展开
 *
 * 旧版这里还有一个 handoffWaitMillis()：因为扩散的第 0 帧圆心就是加号中心、起始半径就是
 * 加号半径，圆内第一帧已经是新页面，所以形变必须先播、扩散后放行（等 150ms）。
 *
 * 现在方块浮在裁剪层之上（见 RevealHost.SeedOverlay），全程可见、没有任何东西会盖掉它，
 * 那段串行等待的**存在理由**消失了，于是整条 handoff 判定被删除——形变与页面展开同时起跑。
 * 删掉的是判定而不是动画：转 45° 那一段仍然播，只是不再挡路。
 */
internal object FabMorph {
    /**
     * 越过这个进度就读作「已经是叉号」了。
     *
     * 0.5 对应 22.5°，也就是加号变成「歪着的加号」与「看起来像叉」的分界。
     * 只用于无障碍描述切换（TalkBack 念的是哪个动作），不参与任何动画：
     * 视觉上转过去就是转过去，没有第二个阈值。
     */
    const val READ_AS_CROSS_AT = 0.5f

    /**
     * 判定「已经归位」的容差。
     *
     * Animatable 收尾时可能停在 1e-7 量级而不是精确的 0，直接比 0 会让「已经回正的加号」
     * 在每次返回时都白跑一遍 animateTo(0f)。
     */
    const val SETTLED_EPSILON = 0.001f

    /**
     * 当前应当显示的转角（度）。
     *
     * [progress] 是 0..1 的形变进度：0 = 加号，1 = 叉号。中途按 [Motion.FAB_MORPH_DEGREES]
     * 线性折算，不额外加缓动——缓动属于时间线（`tween(easing = ...)`），
     * 这里只负责「进度到角度」，两件事分开才能各自被替换而不互相牵连。
     */
    fun rotationDegrees(progress: Float): Float = Motion.FAB_MORPH_DEGREES * progress.coerceIn(0f, 1f)

    /**
     * 形变进度从 [rotationDegrees] 反解：给定一个转角，此刻的进度是多少。
     *
     * 用于「返回时从当前角度接着转回去」——不能一律从 0 开始，否则用户还没看到 ×
     * 就按了返回，加号会先「跳」到 45° 再转回 0，多出一次没人要求的动作。
     */
    fun progressOf(rotationDegrees: Float): Float = (rotationDegrees / Motion.FAB_MORPH_DEGREES).coerceIn(0f, 1f)

    /** 形变时间线：加号 ↔ 叉号共用一条，方向靠进度目标决定 */
    const val DURATION_MILLIS: Int = Motion.DUR_FAB_MORPH

    /** 形变缓动：快速起步、缓慢收尾，读作「按下去它就转过去了」 */
    val EASING: Easing = Motion.EaseOut
}
