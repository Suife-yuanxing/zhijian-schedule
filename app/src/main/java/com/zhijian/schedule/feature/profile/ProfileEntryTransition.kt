package com.zhijian.schedule.feature.profile

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned

/*
 * 入口卡 ⇄ 二级页的共享状态与参数。
 *
 * 职责边界：本文件只放「谁是谁、走多久、落点在哪」——key 常量、时长/圆角参数、
 * 共享状态容器 [ProfileEntryTransitions]、以及上报位置用的 [ProfileEntryAnchor]。
 * 真正画动画的两端在 ProfileEntryPage.kt（进入）与 ProfileEntryExitOverlay.kt（返回），
 * 它们用到的像素计算全在 ProfileEntryGeometry.kt 的纯函数里。
 */

/** 「我的」页入口卡与二级页共享的 key；AppRoot 与 ProfileScreen 必须使用同一组常量 */
object ProfileEntryKeys {
    const val IMPORT = "profile-entry-import"
    const val GRADES = "profile-entry-grades"
    const val GRADES_GPA = "profile-entry-grades-gpa"
    const val EXAMS = "profile-entry-exams"
    const val SETTINGS = "profile-entry-settings"
}

/** 进入：卡片实时展开到整页 */
internal const val PROFILE_ENTRY_ENTER_MS = 480

/** 返回：整页直接收进入口卡；略快于进入，保证返回干脆 */
internal const val PROFILE_ENTRY_EXIT_MS = 400

/** 展开中途返回：反向收回的基准时长，按剩余距离缩放，但不会一下收完 */
internal const val PROFILE_ENTRY_ABORT_MS = 380
internal const val PROFILE_ENTRY_ABORT_MIN_MS = 240

/** 进入期间「我的」页保持原位、不提前销毁，给实时展开当背景；
 *  需覆盖进入动画与中途返回的反向收回，否则收拢途中背景会先被销毁 */
const val PROFILE_ENTRY_HOLD_MS = PROFILE_ENTRY_ENTER_MS + PROFILE_ENTRY_ABORT_MS + 60

/** 展开与反向收回共用的缓动：起步干脆、落位稳 */
internal val EntryEasing = CubicBezierEasing(0.22f, 0f, 0f, 1f)

/** 入口卡的圆角（dp）：展开起点与回收终点共用同一份取值 */
internal const val CARD_RADIUS_DP = 14f

@Stable
class ProfileEntryTransitions {
    private val cardWindowBounds = mutableStateMapOf<String, Rect>()

    /** 内容容器（NavHost 所在区域）在窗口中的位置，同时给出浮层与页面共用的像素坐标原点 */
    var contentBounds by mutableStateOf(Rect.Zero)
        private set

    /** 正在返回的入口 key；非空期间显示回收浮层 */
    var exitKey by mutableStateOf<String?>(null)
        private set
    var exitSerial by mutableIntStateOf(0)
        private set

    fun updateContentBounds(bounds: Rect) {
        if (contentBounds != bounds) contentBounds = bounds
    }

    fun updateCardBounds(
        key: String,
        bounds: Rect,
    ) {
        if (cardWindowBounds[key] != bounds) cardWindowBounds[key] = bounds
    }

    /** 入口卡在内容区域内的像素坐标；页面根与回收浮层共用这一坐标系 */
    fun cardBounds(key: String): Rect? {
        val card = cardWindowBounds[key] ?: return null
        val origin = contentBounds.topLeft
        return Rect(
            left = card.left - origin.x,
            top = card.top - origin.y,
            right = card.right - origin.x,
            bottom = card.bottom - origin.y,
        )
    }

    fun beginExit(key: String) {
        if (exitKey == null) {
            exitKey = key
            exitSerial++
        }
    }

    fun finishExit(serial: Int) {
        if (serial != exitSerial) return
        exitKey = null
    }
}

/** 入口卡侧：上报位置，作为返回时的落点 */
@Composable
fun ProfileEntryAnchor(
    key: String,
    transitions: ProfileEntryTransitions,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(
        modifier.onGloballyPositioned { coordinates ->
            transitions.updateCardBounds(key, coordinates.boundsInWindow())
        },
    ) {
        content()
    }
}
