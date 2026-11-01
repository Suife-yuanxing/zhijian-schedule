package com.zhijian.schedule.navigation

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import com.zhijian.schedule.BuildConfig

/**
 * 二级页入场闸门：与根页闸门（[RootEntrancePolicy]）同一条规矩，按路由各记一次。
 *
 * ## 为什么二级页也需要它（2026-11-01 真机钉死）
 * 根页早在 2026-10-30 就收成「冷启动首帧放行一次」（见 docs/specs/2026-10-30-根页切换规范.md），
 * 但**二级页从来没跟上**：成绩 / 考试 / 设置三页的 StaggerIn 每次推入都从 alpha=0
 * 按阅读顺序长出来（成绩 4 个结构块 + 逐行级联，考试 2 组 + 逐行级联，设置 7 个分组）。
 *
 * 读起来就是「打开这一页时它刷新了一遍」——尤其是**从卡片拉开的那 480ms 里**：
 * 容器一边张开、内容一边一块块浮现，两条时间轴不同步，观感比静止页面更糟。
 *
 * 成绩页一度用 rememberSaveable 自己记了一个「已播」标记，但那只在**同一个 back stack entry
 * 还活着时**有效：返回再进 = 新 entry = rememberSaveable 重新初始化 = 又播一遍。
 * 闸门必须挂在宿主（与根页同一层），才能跨 entry、跨 Activity 重建生效。
 *
 * ## 为什么按路由分记，而不是一个总布尔
 * 四张卡是四个不同的页面，用户完全可能第一次开成绩、第二次开设置；
 * 用一个总布尔会让「第二张卡天生没有入场」。按路由分记才符合「每一页各播一次」的直觉。
 *
 * 纯逻辑与纯状态，不碰导航库，JVM 单测逐条钉住（SecondLevelEntrancePolicyTest）。
 */
internal object SecondLevelEntrancePolicy {
    /** 参与入场闸门的二级页路由（叶子路由；带参数的页面比的是模板串） */
    internal val routes: Set<String> =
        setOf(
            Routes.PROFILE_GRADES,
            Routes.PROFILE_EXAMS,
            Routes.PROFILE_SETTINGS,
        )

    /**
     * 这一次落定要不要播放分段入场。
     *
     * @param played 本次进程里已经播过入场的二级页路由
     * @param route 这次落定的目的地路由
     */
    internal fun shouldPlay(
        played: Set<String>,
        route: String?,
    ): Boolean = route != null && route in routes && route !in played
}

/**
 * 闸门本体：宿主创建一次，三个二级页各读自己那一条。
 *
 * 读（[shouldPlay]）与写（[markPlayed]）分开是刻意的，理由同 [RootEntranceGate]：
 * 在组合期写状态会让这一帧的内容与下一次重组对不上，页面拿到的入参会当场翻面。
 */
@Stable
internal class SecondLevelEntranceGate internal constructor(
    private val played: MutableState<Set<String>>,
) {
    /** 本次进程里已播过入场的二级页路由（升序，便于断言与序列化） */
    internal val playedRoutes: List<String> get() = played.value.sorted()

    /** 这次落定是否该播（只读，不消费） */
    fun shouldPlay(route: String?): Boolean =
        SecondLevelEntrancePolicy.shouldPlay(played.value, route).also { play ->
            if (BuildConfig.DEBUG) logGate("shouldPlay($route)=$play played=${played.value.sorted()}")
        }

    /** 关掉这一页的闸：之后再打开它只做落位，不再播入场 */
    fun markPlayed(route: String?) {
        val key = route ?: return
        if (key !in SecondLevelEntrancePolicy.routes) return
        if (key !in played.value) played.value = played.value + key
        if (BuildConfig.DEBUG) logGate("markPlayed($key) -> ${played.value.sorted()}")
    }
}

/** 闸门的 debug 轨迹：真机验收靠它确认「第二次进入确实走了不播」这条分支 */
private fun logGate(message: String) {
    Log.d("EntranceGate", message)
}

/** 只存路由列表，恢复后包回同一种对象形态 */
private val SecondLevelEntranceGateSaver: Saver<SecondLevelEntranceGate, List<String>> =
    Saver(
        save = { it.playedRoutes },
        restore = { SecondLevelEntranceGate(mutableStateOf(it.toSet())) },
    )

/**
 * 宿主创建一次。用 rememberSaveable 而不是 remember：Activity 重建（字体缩放 / 深色模式 /
 * 分屏尺寸等）后闸门仍然关着，否则「重建 → 再进又播一遍」正是另一条读作「刷新」的路径。
 */
@Composable
internal fun rememberSecondLevelEntranceGate(): SecondLevelEntranceGate =
    rememberSaveable(saver = SecondLevelEntranceGateSaver) {
        SecondLevelEntranceGate(mutableStateOf(emptySet()))
    }

/** 不走组合的构造入口：单测直接喂初始状态，与 rememberSaveable 恢复出来的形态一致 */
internal fun secondLevelEntranceGate(played: Set<String> = emptySet()): SecondLevelEntranceGate =
    SecondLevelEntranceGate(mutableStateOf(played))
