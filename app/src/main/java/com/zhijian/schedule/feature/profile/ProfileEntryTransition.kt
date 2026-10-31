package com.zhijian.schedule.feature.profile

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.rememberMotionAllowed
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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

/**
 * 展开期间「我的」页背景的景深磨砂上限（dp）：玻璃浮起时背景退后。
 * blur 依赖 RenderEffect（API 31+），低版本自动只保留 [ENTRY_BACKDROP_SCRIM_ALPHA] 压暗。
 */
internal const val ENTRY_BACKDROP_BLUR_DP = 16f

/** 背景压暗上限：向 bg 色冲淡一层，与磨砂一起给出「纸面退后」的层深 */
internal const val ENTRY_BACKDROP_SCRIM_ALPHA = 0.25f

/** 展开容器内侧流光的峰值透明度：中段擦亮一下，两端完全熄灭 */
internal const val ENTRY_SHEEN_MAX_ALPHA = 0.10f

/** 展开容器边缘扫光的峰值透明度：细描边上的高光可以亮一些 */
internal const val ENTRY_SWEEP_MAX_ALPHA = 0.55f

/** 着地起手：快照落进卡片的同一帧卡片先鼓到这一档，再由 [Motion.CatchSpring] 软着陆 */
internal const val ENTRY_CATCH_SCALE = 1.03f

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

    /**
     * 背景景深磨砂进度（0..1）：进入展开时跟随页面进度推满、返回收回时随浮层释放，
     * 二级页停留期间恒为 1（低层页不在组合里，这个值只是被带过去）。
     *
     * 读取一律放在绘制/图层阶段（graphicsLayer、drawWithContent）——状态写在驱动方的
     * 动画协程里，读方订阅的是快照状态，转场全程不会重组「我的」页。
     */
    var backdrop by mutableFloatStateOf(0f)
        internal set

    /** 每个入口卡「接住」返回画面的次数：着地时递增，Anchor 据此播放回弹 */
    private val receiveSerials = mutableStateMapOf<String, Int>()

    /** [key] 入口卡当前的着地序号；0 表示从未接收过 */
    fun receiveSerialFor(key: String): Int = receiveSerials[key] ?: 0

    /** 中途返回（反向收回）结束：页面收进卡片，同样算一次着地 */
    fun notifyReceive(key: String) {
        receiveSerials[key] = receiveSerialFor(key) + 1
    }

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
        // 收拢画完的同一帧通知着地：浮层撤掉、真实入口卡露出的瞬间开始回弹
        exitKey?.let { notifyReceive(it) }
        exitKey = null
    }
}

/**
 * 入口卡侧：上报位置，作为返回时的落点；返回画面落进卡片的同一帧播「着地回弹」——
 * 卡片先鼓一下（[ENTRY_CATCH_SCALE]）再由 [Motion.CatchSpring] 软着陆，读作把页面接住。
 *
 * 回弹走 graphicsLayer 缩放，不改变布局边界，`boundsInWindow` 上报的收回落点不受影响。
 */
@Composable
fun ProfileEntryAnchor(
    key: String,
    transitions: ProfileEntryTransitions,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val motionAllowed = rememberMotionAllowed()
    val catch = remember { Animatable(1f) }
    LaunchedEffect(transitions, key, motionAllowed) {
        if (!motionAllowed) return@LaunchedEffect
        snapshotFlow { transitions.receiveSerialFor(key) }
            .collectLatest {
                if (it <= 0) return@collectLatest
                catch.snapTo(ENTRY_CATCH_SCALE)
                catch.animateTo(1f, Motion.CatchSpring)
            }
    }
    Box(
        modifier
            .graphicsLayer {
                val c = catch.value
                scaleX = c
                scaleY = c
            }.onGloballyPositioned { coordinates ->
                transitions.updateCardBounds(key, coordinates.boundsInWindow())
            },
    ) {
        content()
    }
}
