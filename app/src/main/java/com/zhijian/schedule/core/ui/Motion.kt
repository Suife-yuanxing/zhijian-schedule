package com.zhijian.schedule.core.ui

import android.content.ContentResolver
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 全局动效语言：灵动有弹性。
 * 所有时长 / 弹簧 / 缓动集中于此，保证各页面观感一致（和谐、自然）。
 */
object Motion {
    /** 快速反馈：水波纹、渐隐 */
    const val DurFast = 150

    /** 标准过渡：淡入、局部布局切换 */
    const val DurMedium = 260

    /** 慢速登场：交错入场、进度扫描 */
    const val DurSlow = 420

    /**
     * **页面**扩散（加号 → 课程编辑页）的正向时长，缓动是 [EaseRevealAccelerate]。
     *
     * 为什么比 [DurSlow] 长：被裁的是一整屏旧帧，圆要一直长到屏幕最远角（见 revealRadius），
     * 收尾太快会读成「圆还没铺满就切好了」。
     *
     * 为什么是 450 而不是原来的 560——换曲线必须重新补时长。加速曲线的行程「后重前轻」：
     * 半径走到 77.9%（画面读作已铺满）的时刻，从旧曲线的 50% 时间点推迟到 83.7% 时间点。
     * 时长不动就从 280ms 拖到 469ms（晚近 190ms，起步那一段会被判成卡顿）；压到 450ms 后
     * 落在 377ms，只晚约 100ms，末端加速的观感拿得到，也不至于让人以为点空了。
     * 页面扩散与换主题动的是同一个几何量（半径）、用的是同一条曲线，所以补偿系数相同，
     * 两个常量取到同一个值是这条推导的结果，不是巧合、也不是互相抄（推导见 [DurRevealTheme]）。
     *
     * 保留两个名字而不是合成一个：它们是两条独立的时间线（页面转场 / 换皮肤），
     * 现在同值不等于以后必须同值，将来任何一条要单独调时不该被另一条拽着走。
     */
    const val DurReveal = 450

    /**
     * 反向扩散（页面收回被点按钮），缓动是 [EaseRevealDecelerate]。
     *
     * 缓动：正向曲线的**严格时间镜像**（`mirror(t) == 1 - accelerate(1 - t)`）。
     * 正向「慢起快收」，反向「快起慢收」——方向相反，但同一进度点上互为倒放，不会打架。
     *
     * 为什么不再比正向短一截：旧版本用「反向更短」表达「东西在离开」，那是在补旧曲线
     * （EaseReveal）的慢起步——它 100ms 时才收掉 16.5% 的半径，看着像按下去没反应，
     * 只能靠砍时长把速度找回来。镜像曲线天生前段重：100ms 已经收掉 35%，
     * 「离开」的感觉由曲线给出，不必再砍时长。
     *
     * 为什么是 370：按「读作收回完毕」的时刻对齐。半径走完 90% 时圆只剩约 1.4% 的屏幕面积
     * （0.9 × 半径的圆面积 / 满屏），可以算收回了；旧反向（EaseReveal @460ms）在
     * 0.633 × 460 ≈ 291ms 走到这一步，镜像曲线要 78.6% 的时长才走到，291 / 0.786 ≈ 370ms。
     * 落地时刻不变而前半程更快，收回的「清脆感」来自曲线，不来自赶时间。
     * 370 < [DurReveal]，「收回比张开短一截」的既定节奏仍然成立。
     */
    const val DurRevealBack = 370

    /**
     * **换主题**的扩散时长。
     *
     * 450 是给 [EaseRevealAccelerate] 的时长补偿：加速型的行程「后重前轻」，半径走到 77.9%
     * （画面读作换完）的时刻从旧曲线的 50% 时间点推迟到 83.7% 时间点。旧曲线 560ms 时这一步
     * 落在 280ms，时长不动的话要 560 × 0.837 ≈ 469ms 才「读作换完」，晚近 190ms，
     * 起步那一段就会被判成卡顿。压到 450ms 后落在 377ms，只晚约 100ms——末端加速的观感
     * 拿得到，又不至于让人以为点空了。
     * 450 = M3 duration token `long1`（material-components-android docs/theming/Motion.md）。
     *
     * 与页面扩散的 [DurReveal] 同值：两条时间线动的是同一个几何量、用同一条曲线、
     * 按同一条公式补偿，取到同一个数是推导的结果（细节见 [DurReveal]）。
     */
    const val DurRevealTheme = 450

    /** 标准缓动：快速起步、缓慢收尾 */
    val EaseOut = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /**
     * **整屏扩散**的加速缓动：起步慢、末端最快。页面扩散与换主题**共用这一条**。
     *
     * 取值就是 M3 `standard.accelerate` token 的 `cubic-bezier(0.3, 0, 1, 1)`
     * （material-components-android `docs/theming/Motion.md` 的
     * `motionEasingStandardAccelerateInterpolator`）。起点切线水平 → 静止起步；
     * 终点切线斜率 (1-0)/(1-0.3) ≈ 1.43 → 收尾时速度最快，正好是 [EaseRevealDecelerate] 的反面。
     *
     * 为什么不是别的候选（同一组「加速型」里最靠前的行程才不会被读成卡顿）：
     * - Compose 预设 `FastOutLinearInEasing` = (0.4, 0, 1, 1)：半程只走到 32.6%；
     * - M3 `emphasized.accelerate` = (0.3, 0, 0.8, 0.15)：半程只走到 15.5%，整屏大半时间纹丝不动。
     * 本曲线 25% / 50% / 75% 时间点的半径行程 ≈ 13.0% / 37.2% / 66.8%，是本族里前段最满的一条。
     *
     * 也不能用 [EaseOut]：那条曲线在 50% 时间点已走完 87.8% 的行程，而扩散的行程是「圆半径」，
     * 半径一旦到 87.8% 就已经盖满整屏，剩下的时间全花在屏幕最远的那个角落里——
     * 观感上等于**瞬切**（真机实测就是这么「看不出效果」的）。
     *
     * 规范口径备注：Material 把 accelerate 族标给「离开屏幕」的元素，而这里的两次扩散
     * （页面进来 / 换皮肤）在语义上都是**新内容进入**（若严格照规范，入场该用 decelerate 族）。
     * 按需求选加速型的依据是：圆形扩散真正在动的是一次**抹除**——旧画面被圆边界吃掉，
     * 最后那一段是旧画面离开，加速收尾讲得通。代价是起步段的运动量只有旧曲线的一半左右，
     * 由 [DurReveal] / [DurRevealTheme] 压缩时长来补。
     */
    val EaseRevealAccelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    /**
     * **反向收回**的缓动：[EaseRevealAccelerate] 的严格时间镜像。
     *
     * 把一条三次贝塞尔 (x1, y1, x2, y2) 按时间倒放，得到 (1-x2, 1-y2, 1-x1, 1-y1)：
     * (0.3, 0, 1, 1) 倒放就是 (0, 0, 0.7, 1)。于是 `mirror(t) == 1 - accelerate(1 - t)`
     * 对任意 t 成立（单测把这条恒等式钉住了），反向就是正向的倒放：
     * 起步最快（按下立刻有反馈）、末端最慢（圆稳稳落在加号上，不留硬边）。
     *
     * 为什么反向不复用 [EaseRevealAccelerate]：那会让半径在满屏附近磨蹭一百多毫秒，
     * 读成「按了返回没反应」；也不能用 [EaseOut]（半程已走完 87.8%，等于瞬间收完）。
     */
    val EaseRevealDecelerate = CubicBezierEasing(0f, 0f, 0.7f, 1f)

    /** 按压回弹：按下干脆、松手轻微回弹 */
    val PressSpring = spring<Float>(dampingRatio = 0.62f, stiffness = 620f)

    /** 选中弹跳：chip / 图标选中时的俏皮放大 */
    val PopSpring = spring<Float>(dampingRatio = 0.5f, stiffness = 480f)

    /** 选中弹起：快速吸附、轻微过冲——课程块「被拿起」，与详情面板入场连成一体 */
    val SelectionEnter = spring<Float>(dampingRatio = 0.52f, stiffness = 520f)

    /** 选中恢复：干脆回落不拖尾——与详情面板滑出并行播完，消除「等面板走完才复位」的滞后感 */
    val SelectionExit = tween<Float>(durationMillis = 130, easing = EaseOut)

    /** 整屏横滑转场：无过冲，保证大位移干净利落 */
    val SlideSpring = spring<IntOffset>(
        dampingRatio = 1f,
        stiffness = 420f,
        visibilityThreshold = IntOffset(1, 1),
    )

    /** 级联入场：课块随页面切换涌入的落位弹簧（轻微过冲、收尾干净） */
    val CascadeEnter = spring<Float>(dampingRatio = 0.62f, stiffness = 460f)

    /** 底部浮岛选中胶囊滑动：流体转移、轻微过冲后落位即稳 */
    val FluidSlide = spring<Dp>(dampingRatio = 0.72f, stiffness = 480f)

    /** 底部浮岛胶囊转移形变：行进中横向拉伸 / 纵向压缩，弹性回位（液体被吸走的顿挫感） */
    val FluidStretch = spring<Float>(dampingRatio = 0.62f, stiffness = 480f)

    /**
     * 果冻开关的滑行弹簧：阻尼比 0.55，单位行程上约 12.6% 过冲。
     *
     * 为什么不用临界阻尼（[SlideSpring] 的 1f）：开关是「点一下就换边」的离散动作，
     * 位移本身其实不需要过冲；但果冻的形变是从**速度**里长出来的（见 `JellySwitch.kt`），
     * 没有过冲就没有落位后那一下回弹，胶囊会一路缩成圆就停住、缺一口气。
     *
     * 为什么是 0.55 / 420：过冲幅度 = e^(-πζ/√(1-ζ²)) ≈ 12.6%，在开关 20dp 的行程上约 2.5dp，
     * 正好小于轨道 4dp 的内边距——回弹看得见，手柄又顶不出轨道（囊形鼻尖离轨道壁约 1.5dp，
     * 是「差一点就顶到」的观感；再大就会撞墙夹紧，那一下会读成被挡住而不是回弹）。
     * 刚度 420 ⇒ ω ≈ 20.5 rad/s，半程约 90ms、落位约 350ms，与 M3 开关的手感同档，
     * 不会因为要「演出形变」而把一次点击拖成一次转场。
     */
    val JellySlide = spring<Float>(dampingRatio = 0.55f, stiffness = 420f)

    /**
     * 果冻开关的**手柄尺寸**弹簧：选中 / 未选中之间 16↔24dp 的「呼吸」。
     *
     * 为什么比 [JellySlide] 硬（620 vs 420）：尺寸变化是**状态**信号，要在形变高峰之前收口。
     * 逐帧积分下 50ms（拉伸峰值）时直径已从 16 走到 19.7dp，120ms 落定；
     * 若跟着位置弹簧走，尺寸要到 180ms 才稳，那时胶囊早已缩回圆形，会读成
     * 「先飞过去、再在原地长大一下」两个动作，而不是「小水滴飞过去长成一枚球」。
     *
     * 阻尼比 0.62 与 [PressSpring] 同档：允许 9.5% 的过冲（24 → 26.2dp）再收回，
     * 落位时手柄「鼓一下」，与滑行弹簧的回弹错开约 60ms，是两次呼吸而不是一次抖动。
     */
    val JellyGrow = spring<Float>(dampingRatio = 0.62f, stiffness = 620f)

    /**
     * 触摸倾动的**指腹跟随**弹簧：倾角要贴着手指走，但不能瞬移。
     *
     * 为什么不是直接 snapTo：触摸事件是一帧一跳的离散采样，直接赋值等于把采样抖动原样
     * 交给画面；拖动越快，跳越大。业界无一例外都在这一层加阻尼——vanilla-tilt 用
     * 300ms 的 CSS transition（`speed`），beUI/Framer 用 `SPRING_MOUSE`
     * （stiffness 200 / mass 0.3 ⇒ ω ≈ 26 rad/s，damping 15 ⇒ ζ ≈ 0.97），
     * Atropos 的 `duration` 也是 300ms。
     *
     * 为什么取 900 / 0.88：Compose 的 stiffness 就是 ω²，900 ⇒ ω = 30 rad/s，
     * 时间常数约 33ms——仍是「指哪打哪」，但一帧一跳被抹平的同时留了一点追手感
     * （ζ 0.88 的过冲约 0.3%，肉眼读不到抖动，只读得到"软"）。
     */
    val TiltFollow = spring<Float>(dampingRatio = 0.88f, stiffness = 900f)

    /**
     * 触摸倾动回正：松手后带一次明显过冲再落位，阻尼比低于 1 才有「纸片弹回」的手感。
     *
     * 0.38 / 460：ω ≈ 21.4 rad/s，过冲约 27%、周期约 0.33s，约 0.5s 落定——
     * 比最初 0.55 / 420（过冲约 12%）在 17° 的大卡上肉眼可见地更"弹"。
     * 这是"夸张"最省力也最有效的来源：角度再大，回正太干脆就还是块平板。
     */
    val TiltReturn = spring<Float>(dampingRatio = 0.38f, stiffness = 460f)

    /**
     * 按下进度（反光不透明度与抬升高度**共用**）的加压弹簧：0 → 1 带一次小鼓胀。
     *
     * 用弹簧而不是 tween：tween 是"匀速爬到 1"，读成一次状态变化；
     * 弹簧约 110ms（峰值时刻 π/ω_d）冲到 1.095 再收口，读成"被指腹压了一下"。
     * 过冲量与 [PressSpring] 接近（9.5% vs 8.4%），不会看起来像在抖。
     */
    val PressIn = spring<Float>(dampingRatio = 0.60f, stiffness = 1200f)

    /** 倾动高光淡出：松手后留一点余晖，但不要拖住回正（比旧版 150ms 更"余韵"） */
    const val DurTiltGlareOut = 200
}

/**
 * 按压缩放反馈。与 clickable 共用同一个 [interactionSource]，
 * 并放在 clickable 外层，使缩放同时作用于背景与水波纹。
 */
@Composable
fun Modifier.pressScale(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.96f,
    enabled: Boolean = true,
): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = Motion.PressSpring,
        label = "pressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 按钮统一反馈：按下回弹；触觉默认关闭。
 *
 * 只有「完成态 / 破坏性操作 / 长按」才显式传 [HapticFeedbackType]：
 * - 完成态：保存成功、导入完成、扫码识别成功；
 * - 破坏性操作：删除；
 * - 长按：长按菜单、拖拽起点。
 *
 * 普通导航、开关、选项、打开弹窗不要传 hapticType——全局每个按钮都震会让触感失去信息量。
 * 页面主体的卡片（课块、今日课程行）不用这个入口，改用 [rememberTapHaptic]。
 * 与 clickable 共用同一个 [interactionSource]，不改变水波纹。
 */
@Composable
fun Modifier.buttonFeedback(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.96f,
    enabled: Boolean = true,
    hapticType: HapticFeedbackType? = null,
): Modifier {
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(interactionSource, hapticType, enabled) {
        val type = hapticType
        if (!enabled || type == null) return@LaunchedEffect
        interactionSource.interactions.collect { interaction ->
            if (interaction is PressInteraction.Release) {
                haptics.performHapticFeedback(type)
            }
        }
    }
    return pressScale(interactionSource, pressedScale, enabled)
}

/**
 * 卡片轻点反馈：点「整块卡片本身」时的一下轻响（课表课块、今日课程行）。
 *
 * 这类卡片是页面主体，一次点击就换出一屏详情，值得给一次触觉；但它不是「完成态」，
 * 所以用 [HapticFeedbackType.ContextClick]（轻、短）而不是 Confirm——两者混用就分不出轻重了。
 *
 * 只在 click 回调里调用：一次手势最多响一次，滚动手势取消点击时也不会误震。
 */
@Composable
fun rememberTapHaptic(type: HapticFeedbackType = HapticFeedbackType.ContextClick): () -> Unit {
    val haptics = LocalHapticFeedback.current
    return remember(haptics, type) {
        { haptics.performHapticFeedback(type) }
    }
}

/** 选中弹跳：选中项放大少许并带弹性落点（FilterChip / 日期条 / 色板圆点）。 */
@Composable
fun Modifier.selectedPop(selected: Boolean, popScale: Float = 1.06f): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (selected) popScale else 1f,
        animationSpec = Motion.PopSpring,
        label = "selectedPop",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * 交错登场：内容始终占位，只做透明度 + 上浮过渡，避免布局跳动。
 * [index] 越大登场越晚（步进 [stepMs]，封顶 [maxIndex]）。
 * 注意：内部是 Box，[content] 应只放单一子项；需要间距请在 StaggerIn 外层放 Spacer，
 * 写在 content 里只会与内容叠放、被静默吞掉。
 * [contentPadding]：在 alpha 图层内为内容垫一圈空间。alpha<1 的离屏层以自身 bounds 为界，
 * 子项的越界绘制（选中弹跳、投影溢出）会被裁掉；需要溢出时用它撑大图层，
 * 外层布局间距自行补偿。
 * [durationMs]：单段淡入上浮的时长；切页级联可缩短以压住整体节奏。
 * [skip]：首次组合时直接落位。用于「我的」页返回时避免入口卡重播入场，
 * 保证共享元素锚点从第一帧起就可见。
 */
@Composable
fun StaggerIn(
    index: Int,
    modifier: Modifier = Modifier,
    stepMs: Long = 36,
    maxIndex: Int = 8,
    durationMs: Int = Motion.DurSlow,
    contentPadding: PaddingValues = PaddingValues(),
    skip: Boolean = false,
    content: @Composable () -> Unit,
) {
    var arrived by remember { mutableFloatStateOf(if (skip) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (!skip) {
            delay(index.coerceAtMost(maxIndex) * stepMs)
            arrived = 1f
        }
    }
    val progress by animateFloatAsState(
        targetValue = arrived,
        animationSpec = tween(durationMillis = durationMs, easing = Motion.EaseOut),
        label = "staggerIn",
    )
    Box(
        modifier
            .graphicsLayer {
                alpha = progress
                translationY = (1f - progress) * 20.dp.toPx()
            }
            .padding(contentPadding),
    ) {
        content()
    }
}

/**
 * 级联入场：延迟 [delayMs] 后淡入 + 弹簧放大 + 自下而上落位（课块随 tab 切页涌入）。
 * [delayMs] < 0 表示不启用、直接落位；同一实例只播一次，页面重建时随新实例重播。
 */
@Composable
fun Modifier.cascadeIn(
    delayMs: Long,
    startScale: Float = 0.78f,
    riseDp: Float = 10f,
): Modifier {
    var arrived by remember { mutableStateOf(delayMs < 0) }
    LaunchedEffect(delayMs) {
        if (delayMs >= 0) {
            delay(delayMs)
            arrived = true
        }
    }
    val progress by animateFloatAsState(
        targetValue = if (arrived) 1f else 0f,
        animationSpec = Motion.CascadeEnter,
        label = "cascadeIn",
    )
    return graphicsLayer {
        alpha = progress.coerceIn(0f, 1f)
        val scale = startScale + (1f - startScale) * progress
        scaleX = scale
        scaleY = scale
        translationY = (1f - progress) * riseDp.dp.toPx()
    }
}

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

private fun ContentResolver.readAnimatorScale(): Float =
    Settings.Global.getFloat(this, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)

/**
 * 分段关键帧求值：把单一动画进度 [progress]（0..1）映射到多通道（缩放/位移/旋转），
 * 让一套弹跳的所有通道共享同一节拍；points 按时间升序，超出两端取端点值。
 */
internal fun keyframed(progress: Float, vararg points: Pair<Float, Float>): Float {
    val p = progress.coerceIn(0f, 1f)
    if (p <= points.first().first) return points.first().second
    for (i in 1 until points.size) {
        val (t, v) = points[i]
        if (p <= t) {
            val (pt, pv) = points[i - 1]
            return pv + (v - pv) * ((p - pt) / (t - pt))
        }
    }
    return points.last().second
}
