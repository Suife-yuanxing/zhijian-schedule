package com.zhijian.schedule.core.ui

import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.LocalCoursePreset

/**
 * 「浮在内容之上」这一类薄壳的统一表现：进出场时长与曲线、滑入比例、投影。
 *
 * ## 为什么需要它
 * 教务导入页在网页之上同时挂着三样东西：顶部的限时浮岛提示、WAF 拦截横幅、
 * 底部的「记住这次登录？」气泡。三者各写各的动效，于是真机上出现的是：
 * 一个 260ms 带 EaseOut 滑进来、150ms 用**默认曲线**滑出去；另一个出场却用了
 * 260ms；第三个入场从屏幕底边升起、同一个屏幕里前两个却只滑三分之一。
 *
 * 用户不会说出「缓动曲线不一致」，但会觉得"这几个提示不像同一个软件做的"。
 * 统一性的落点从来不是配色，而是这类**手感上的小分歧**的个数。
 *
 * ## 为什么是时长与曲线分开命名
 * 入场与离场本就不该等长（快点消失读作「说完了」，慢点消失读作「还赖着」），
 * 所以这里给出两个时长；但**曲线必须同一条**——出入场用不同的缓动，是这套不一致里
 * 最容易被写出来、也最难被看出来的一个。
 */
object OverlayMotion {
    /**
     * 滑入 / 滑出的位移比例（相对浮层自身高度）。
     *
     * 只走三分之一：这些浮层贴在网页边缘，整条滑进来会读成「又从上面掉下来一层界面」；
     * 三分之一足以让出现有方向、又不至于挡住在它下面正要被点的东西。
     *
     * 进出场**同一个比例**：浮层从哪个方向来就从哪个方向走（见 [slideUp] / [slideDown]），
     * 出场不必走得更远——"说完就走"由更短的时长表达，不用位移量再加一遍。
     */
    const val SLIDE_FRACTION = 1f / 3f

    /**
     * 浮层默认投影高度。
     *
     * 只说明「它浮在网页之上」，不说明「它是这屏的主角」——那件事交给底部浮岛（14dp）。
     * 一层软影的意义在于：网页自己也有白底卡片，没有影的浮层会读作网页的一部分。
     */
    val ELEVATION: Dp = 6.dp

    /** 浮层默认的环境影浓度（方向影在此基础上 ×1.6） */
    const val SHADOW_ALPHA = 0.10f

    /** 入场：标准时长 + 快起慢收。 */
    fun <T> enter(): TweenSpec<T> = tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut)

    /** 离场：比入场短一截，**曲线与入场相同**。 */
    fun <T> exit(): TweenSpec<T> = tween(durationMillis = Motion.DUR_FAST, easing = Motion.EaseOut)

    /**
     * 向上位移一个 [SLIDE_FRACTION]（负偏移）。
     *
     * 用函数而不是让每个调用点自己写 `-height * SLIDE_FRACTION`：方向是这个库里
     * **最容易写反一个符号**的东西，而写反了不会报错、只会让"向上离开"变成"向下栽"。
     * 偏移的正负约定收在这里，调用处只表达意图（向上 / 向下）。
     *
     * @param height 浮层自身的高度
     */
    fun slideUp(height: Int): Int = -(height * SLIDE_FRACTION).toInt()

    /**
     * 向下位移一个 [SLIDE_FRACTION]（正偏移）。
     *
     * 同一个函数同时用于「从下方进入」与「向下方离开」——两件事的几何是同一个方向，
     * 只是发生在时间轴的两端（见 [slideUp] 里关于符号的那段）。
     */
    fun slideDown(height: Int): Int = (height * SLIDE_FRACTION).toInt()
}

/**
 * 浮层投影：三样浮在网页之上的薄壳共用这一份配方。
 *
 * ## 为什么金属档不投
 * 金属面本身已经由着色器建模了球冠明暗与倒角高光，再压一层黑影会把那块金属读成
 * 「一张贴了影的图片」。投影的职责是补回"没有建模的层"，而金属档已经建过了。
 *
 * ## 为什么浓度固定而不按档位分
 * 这里的三样浮层寿命都很短（五秒 / 一次操作），用户读的是"它浮着"而不是"它有多高"。
 * 按档位分浓度会让一次提醒与一次拦截在观感上差出一层，而它们是同一类东西。
 */
@Composable
fun Modifier.overlayShadow(
    shape: Shape,
    elevation: Dp = OverlayMotion.ELEVATION,
): Modifier {
    if (LocalCoursePreset.current.metal != null) return this
    return shadow(
        elevation = elevation,
        shape = shape,
        clip = false,
        ambientColor = Color.Black.copy(alpha = OverlayMotion.SHADOW_ALPHA),
        spotColor = Color.Black.copy(alpha = OverlayMotion.SHADOW_ALPHA * 1.6f),
    )
}
