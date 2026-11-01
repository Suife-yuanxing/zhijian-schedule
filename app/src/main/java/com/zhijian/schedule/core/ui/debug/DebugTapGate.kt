package com.zhijian.schedule.core.ui.debug

/**
 * 「连点若干次才出现」的计数器：隐藏调试入口的开关。
 *
 * ## 为什么需要它，以及为什么它必须能被单测
 * 隐藏入口的用途是让开发和回归验证能手动触发那些**平时造不出来**的状态
 * （比如「本机密钥不可用」——要把 Keystore 弄坏才行）。
 * 但隐藏入口一旦自己出问题，代价是双向的：
 * - 太灵敏：正常用户点两下标题就弹出一个满是英文术语的面板；
 * - 太迟钝：真要用的时候点半天不出来，于是没人再信它。
 * 两种都不是崩溃，只是安静地变难用。所以判定逻辑抽成不依赖 Compose、
 * 不读系统时钟（时间由调用方传入）的纯类，用注入的时间戳把边界钉死。
 *
 * ## 为什么是「连续点」而不是「N 秒内点 N 次」
 * 后者要求用户手速够快：7 次点击要挤进 2 秒，很多人第一遍点不出来，
 * 会以为入口不在这儿。改成「相邻两次间隔不超过 [DEFAULT_MAX_GAP_MS]」，
 * 节奏由用户自己掌握，只要不停顿太久就行——这更接近用户真实的点击行为，
 * 也保留了「不会误触」这个前提：没人会对着同一行标题连续点七下。
 *
 * @param required 需要连续点几次
 * @param maxGapMs 相邻两次点击的最大间隔；超过即重新计数
 */
internal class DebugTapGate(
    private val required: Int = DEFAULT_REQUIRED,
    private val maxGapMs: Long = DEFAULT_MAX_GAP_MS,
) {
    private var taps: Int = 0
    private var lastAt: Long = 0L

    /**
     * 记一次点击，返回「这次是否刚好凑满」。
     *
     * 凑满后内部计数**归零**：不归零的话第 8、9、10 次点击会继续返回 true，
     * 面板被关掉后随便再点一下就又弹出来。
     *
     * @param now 当前时刻（毫秒）。由调用方传入而不是内部读时钟，
     *   这样单测能给任意时间序列，不必真的等待
     */
    fun tap(now: Long): Boolean {
        // 时间倒流（系统时钟被改、或注入的时间戳乱序）：当成一次全新的开始，
        // 否则 0 - 很大 会算出负数间隔，永远不重置
        if (now < lastAt) {
            taps = 0
        } else if (taps > 0 && now - lastAt > maxGapMs) {
            taps = 0
        }
        lastAt = now
        taps++
        if (taps < required) return false
        taps = 0
        return true
    }

    /** 已经点到第几下（调试面板用来显示进度，也为单测提供可观测性） */
    internal fun progress(): Int = taps

    companion object {
        /** 连续点 7 次：与 Android「关于手机 → 版本号」同一数量级，用户对这串动作有肌肉记忆 */
        const val DEFAULT_REQUIRED: Int = 7

        /** 相邻两次最多隔 1.2 秒：够从容，又不至于边想边点也能凑满 */
        const val DEFAULT_MAX_GAP_MS: Long = 1_200L
    }
}
