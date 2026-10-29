package com.zhijian.schedule.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * 全局「现在」。
 *
 * 为什么收成一根时钟，而不是每个页面各起一个 while(true) { delay(15_000) }：
 * - 三条循环各自醒在自己的相位上，同一屏里的「第 X 周」和「剩 12 分」会差几十秒；
 * - 唤醒频率是 4 次/分 × 页面数，而画面上真正随分钟变的只有四处：
 *   当前这节课卡、下一节课卡、每行的状态徽章、课表里的「现在线」；
 * - 每次唤醒都会让**整页**重组一遍（改前 今日 15s、课表 30s），实测静置 35s 是 0 帧，
 *   也就是说这些重组不产生任何一帧画面——纯属白烧 CPU（实测 ≈0.4% 单核）。
 *
 * 这里只提供**一根**整分对齐的时钟，谁需要谁读：值是 MutableState（稳定对象），
 * 经 CompositionLocal 传下去不会让整棵子树重组，只有读 .value 的那个叶子会重组。
 */
val LocalClock =
    compositionLocalOf<State<LocalDateTime>> {
        error("LocalClock 未提供：请在 AppRoot 用 CompositionLocalProvider(LocalClock provides rememberAppClock()) 包一层")
    }

@Composable
fun rememberAppClock(): State<LocalDateTime> {
    val now = remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(now) {
        while (true) {
            val current = LocalDateTime.now()
            now.value = current
            // 对齐到下一个整分：秒级跳动对上面那四处用途没有意义，闹钟式唤醒也省电
            val nextMinute = current.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1)
            delay(Duration.between(current, nextMinute).toMillis().coerceAtLeast(1_000L) + 60L)
        }
    }
    return now
}
