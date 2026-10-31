package com.zhijian.schedule.navigation

import android.content.Context
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 底栏切页的落位窗口：上一次导航之后，至少隔这么久才允许下一次导航。
 *
 * 取值依据：tabSlide 的弹簧是 [Motion.SlideSpring]（0 阻尼、stiffness 420，约 220ms 落位），
 * 留 40ms 余量。落位窗口内的第二次导航会让 NavHost 内部的 AnimatedContent 走打断路径，
 * 而它处理不好（见 [TabSwitchQueue] 的类注释），所以这个窗口必须由队列保证。
 */
internal const val TAB_SWITCH_SETTLE_BUDGET_MS: Long = 260L

/** 生产用的单调时钟（毫秒）。单测注入虚拟时钟替换它，让排队时机的契约可以确定性断言 */
internal val systemMonotonicMs: () -> Long = { System.nanoTime() / 1_000_000L }

/**
 * 按系统动画时长缩放折算落位窗口：Compose 动画（含 [Motion.SlideSpring]）遵循
 * 开发者选项「动画时长」缩放，10x 慢放下转场真的要 2.2s——预算若不跟着缩放，
 * 慢放设备（以及任何调过该选项的用户）会在窗口过期后照样触发打断。
 * 缩放为 0（动画关闭）时转场瞬间完成，预算为 0，不引入人为等待。
 */
internal fun animatorScaledBudgetMs(context: Context): Long {
    val scale =
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        )
    return (TAB_SWITCH_SETTLE_BUDGET_MS * scale).toLong()
}

/**
 * 切页单飞队列：把落位窗口内的快速连点合并成「最后一次意图、一次干净转场」。
 *
 * ## 为什么必须有它（2026-10-31 真机抓帧钉死的根因）
 *
 * NavHost 内部是 AnimatedContent。转场进行中再次改目标（= 用户在约 220ms 的滑动落位前
 * 又点了一个 tab，1x 速度下对应 ~120-250ms 的连点间隔）时，AnimatedContent 走打断路径，
 * 真机 10x 慢放抓到的病灶帧是：**三个页面同框**——目的地只露出一角（弹簧从错误相位重启、
 * 迟到落位）、旧页停在中途、上一次的目标页残影还挂在边缘，页与页之间还有缝隙露出底色。
 * backward 方向（课表→今日）时目的地从左侧迟到滑入，读作「画面从左上角打开」——
 * 就是主人反复报告的那个偶发问题。等距滑动、去 alpha、入场闸门三次修复都没碰到
 * 打断路径，所以一直在复发。
 *
 * 队列把打断从源头消掉：conflated channel 只保留最后一次意图，worker 保证任意两次
 * 导航之间至少隔 [TAB_SWITCH_SETTLE_BUDGET_MS]，转场永远不会被下一次导航打断。
 * 纯逻辑与时机全部可注入，JVM 单测钉住契约（TabSwitchQueueTest）。
 *
 * 注意：只接底栏点击这条最高频的路径。通知/胶囊跳转（openTodayCount / openJwImportCount）
 * 发生在进程刚启动、没有转场在跑的时刻，直接导航即可，不值得为它排队。
 *
 * @param scope worker 的宿主（组合期给 rememberCoroutineScope 的产物）
 * @param budgetMs 两次导航的最小间隔（= 落位窗口）。**每次导航后重新求值**：
 * 生产给 [animatorScaledBudgetMs]（跟随系统动画缩放），单测给常量
 * @param nowMs 单调时钟（毫秒），生产给 System.nanoTime 换算，单测给虚拟时钟
 * @param navigate 真正执行导航的回调（navController::navigateToTab）
 */
internal class TabSwitchQueue(
    scope: CoroutineScope,
    private val budgetMs: () -> Long,
    private val nowMs: () -> Long,
    private val navigate: suspend (String) -> Unit,
) {
    /**
     * CONFLATED：worker 还没来得及取的请求会被后到的覆盖。
     * 「窗口内连点三个 tab」就是一次干净的直达最后一页，而不是排队连播三段转场。
     */
    private val requests = Channel<String>(capacity = Channel.CONFLATED)

    init {
        scope.launch { worker() }
    }

    /** 底栏点击入口：永不阻塞、永不挂起，点击反馈（弹跳/浮岛展开）照常立即发生 */
    fun request(route: String) {
        requests.trySend(route)
    }

    private suspend fun worker() {
        // MIN_VALUE/2 让「进程里第一次导航」必然不用等；
        // 预算在每次导航后才求值（首次导航之前不存在「上一次转场」，0 即可），
        // 这样动画缩放中途变化、以及注入式预算（单测）都在正确的时刻生效
        var lastNavAtMs = Long.MIN_VALUE / 2
        var budget = 0L
        for (route in requests) {
            val waitMs = budget - (nowMs() - lastNavAtMs)
            if (waitMs > 0) delay(waitMs)
            navigate(route)
            lastNavAtMs = nowMs()
            budget = budgetMs()
        }
    }
}
