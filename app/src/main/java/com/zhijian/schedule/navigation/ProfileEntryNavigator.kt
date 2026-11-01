package com.zhijian.schedule.navigation

import android.content.Context
import android.util.Log
import com.zhijian.schedule.BuildConfig
import com.zhijian.schedule.feature.profile.PROFILE_ENTRY_ENTER_MS

/**
 * 入口卡 → 二级页的单飞闸门。
 *
 * ## 为什么必须有它（2026-11-01 真机钉死）
 * 底栏切页早就走了 [TabSwitchQueue]，但**入口卡一条都没管**：ProfileScreen 的五个回调
 * 各自直接调 navController.navigate(...)。于是两张不同的卡在展开首帧之前相继被点中时
 * （换手、误触、手指没抬起来又滑到另一张卡），会各压一层二级页：
 *
 *   tap 成绩查询 (t=0)   -> navigate(profile_grades)
 *   tap 考试安排 (t=30)  -> navigate(profile_exams)     // 上一页还没画出来，没人拦
 *   返回栈 = 我的 / 成绩 / 考试
 *   按一次返回 -> 落回「成绩查询」，用户读作「返回没用、回不到根页」
 *
 * launchSingleTop 拦不住：它只合并**同一路由**的连续压栈，两个不同路由照样叠。
 * 真机实测（PKG110 / 1080x2376 / v1.0.15）：间隔 30ms 时展开停在「考试安排」，
 * 1 次返回回到「成绩查询」，2 次返回才回根页；间隔 60ms 起恢复正常。
 *
 * ## 漏洞窗口为什么是「开头一两帧」而不是整段动画
 * ProfileEntryPage 有一层整屏吞指针的 Box，但它挂在 (forwarded || ready) 之后，
 * 而 ready 要等 contentBounds 与卡片矩形都非零——也就是**首帧之后**才成立。
 * 在这之前二级页什么都没画，第二次点击穿过它落在下面「我的」页的另一张卡上。
 * 所以窗口很窄，但卡顿时首帧可能迟到几百毫秒，因此按展开时长折算而不是写死几毫秒。
 *
 * ## 两道闸
 * 1. **落位窗口**（[entryNavBudgetMs]）：窗口内的第二次点击直接丢弃。这时屏幕上还没有
 *    可点的第二个目标，收到的第二次点击只可能是同一次手势的抖动——丢弃它，
 *    比压两层、让用户一次退不回去正确。
 * 2. **栈上限**：导航前 popUpTo(Routes.PROFILE)，保证「我的」之上永远只有一层入口页。
 *    即便第一道闸被系统卡顿漏掉，返回栈也不会越叠越深。见 [navigateToProfileEntry]。
 *
 * ## 与 TabSwitchQueue 的语义差别（刻意相反）
 * TabSwitchQueue 合并成「最后一次意图」——切 tab 幂等，到哪一页都一样；
 * 入口卡只认**第一次**——两张卡是两件不同的事，把 30ms 后的误触当意图，
 * 会把用户送到一个他没想打开、且一次返回退不出去的页面。
 *
 * 纯逻辑（无协程、无 Compose），JVM 单测直接钉住契约（ProfileEntryNavigatorTest）。
 *
 * @param budgetMs 两次导航之间的最小间隔。**每次导航后重新求值**：生产给
 * [entryNavBudgetMs]（跟随系统动画缩放），单测给常量
 * @param nowMs 单调时钟（毫秒），生产给 [systemMonotonicMs]，单测给虚拟时钟
 * @param navigate 真正执行导航的回调
 */
internal class ProfileEntryNavigator(
    private val budgetMs: () -> Long,
    private val nowMs: () -> Long,
    private val navigate: (String) -> Unit,
) {
    /** 上次受理导航的时刻；MIN_VALUE/2 让进程里第一次点击必然放行 */
    private var lastNavAtMs = Long.MIN_VALUE / 2

    /**
     * 请求打开某张入口卡对应的二级页。
     *
     * @return true = 本次受理并已导航；false = 落在上一段的落位窗口内、已丢弃
     */
    fun request(route: String): Boolean {
        val elapsed = nowMs() - lastNavAtMs
        val budget = budgetMs()
        if (elapsed < budget) {
            if (BuildConfig.DEBUG) {
                Log.d("EntryNav", "dropped $route (elapsed=$elapsed < budget=$budget)")
            }
            return false
        }
        lastNavAtMs = nowMs()
        if (BuildConfig.DEBUG) Log.d("EntryNav", "accepted $route (elapsed=$elapsed budget=$budget)")
        navigate(route)
        return true
    }
}

/**
 * 入口页要挡多久：**展开动画的时长**（PROFILE_ENTRY_ENTER_MS = 480ms），按系统
 * 「动画时长」缩放折算——10x 慢放下展开真的要 4.8s，固定值会在调过开发者选项的设备上失效。
 *
 * 为什么不用入口页的保持窗口（PROFILE_ENTRY_HOLD_MS = 920ms）：那是「下层页不被销毁」
 * 的时长，其中后 440ms 二级页早已铺满整屏并自己吞掉指针，不需要再挡。用 480ms 既盖住
 * 首帧迟到的最坏情况，也不会让返回后紧接着点第二张卡被无谓地忽略半秒。
 *
 * 缩放为 0（动画关闭 / 无障碍「移除动画」）时为 0：转场瞬间完成，不存在漏洞窗口。
 */
internal fun entryNavBudgetMs(context: Context): Long = (PROFILE_ENTRY_ENTER_MS * animatorScale(context)).toLong()

/**
 * 入口卡的导航（唯一入口）：先收栈到「我的」，再推入目标二级页。
 *
 * 收栈用 inclusive = false：popUpTo 到 PROFILE 时把「我的」自己留下。
 * 这样「我的」之上永远只有一层入口页——即便第一道时间闸被系统卡顿漏掉，
 * 用户按一次返回也一定回到根页。
 */
internal fun androidx.navigation.NavHostController.navigateToProfileEntry(route: String) {
    navigate(route) {
        popUpTo(Routes.PROFILE) { inclusive = false }
        launchSingleTop = true
    }
}
