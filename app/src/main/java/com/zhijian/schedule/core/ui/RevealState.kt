package com.zhijian.schedule.core.ui

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.IntSize
import kotlin.math.min

/** 扩散时间线打点（诊断用；DEBUG=false 时零开销） */
internal object RevealTrace {
    private const val TAG = "Reveal"
    private const val DEBUG = false
    private var zero = 0L

    fun reset() {
        if (!DEBUG) return
        zero = SystemClock.uptimeMillis()
    }

    fun log(msg: () -> String) {
        if (!DEBUG) return
        val t = if (zero == 0L) 0L else SystemClock.uptimeMillis() - zero
        Log.i(TAG, "+" + t + "ms " + msg())
    }
}

/**
 * 一次扩散的边界形状。
 *
 * 两种口径并存是刻意的，不是没统一：
 * - [Circle] 给**换主题**用——皮肤之间的切换一直是「从胶囊长出一个圆」，
 *   圆的边界只有一个自由度（半径），语义干净，这次需求没有要动它；
 * - [Seed] 给**页面扩散**用——文档要求「页面沿着整个加号方块展开」，
 *   边界的起手形状必须**就是**被点控件本身（圆角、边长、位置全部实测），
 *   之后每一帧都是它的等距外扩，终点自然吞掉整屏。
 */
internal sealed interface RevealShape {
    /** 圆心 + 起始半径（换主题：胶囊） */
    data class Circle(
        val center: Offset,
        val startRadius: Float,
    ) : RevealShape

    /** 被点控件的实测轮廓（页面扩散：加号方块 / 空课块 / 空状态按钮） */
    data class Seed(
        val seed: SeedShape,
    ) : RevealShape
}

/**
 * 圆形扩散（circular reveal）的状态机。
 *
 * 结构照抄业界标准写法（Scott Pierce《Circular Reveal Animations in Compose》、
 * Krossovochkin《Change Theme Dynamically with Circular Reveal》、bmonjoie《Reveal effect》）：
 * **旧画面留在下面不动 → 新画面按边界裁剪盖上来 → 边界盖满再撤掉旧画面。**
 *
 * 两处刻意的实现选择：
 * - 旧画面用 [androidx.compose.ui.graphics.layer.GraphicsLayer.toImageBitmap] 取**真实像素**，
 *   不用 GraphicsLayer.record{} 录绘制指令。录指令依赖「绘制阶段写标记、下一帧读标记」的时序，
 *   任何一帧没画到就会录到空帧，于是整段扩散退化成瞬间切换；取像素是一次同步的
 *   RenderNode→Bitmap，成败是确定的。
 * - 裁剪在**绘制阶段**做，进度也在绘制阶段读：这样每帧只失效绘制，不依赖
 *   「重组 → 换 Shape → 刷新图层轮廓」这条链。实测在 graphicsLayer{} 里改 shape
 *   只有图层首帧会被采纳，改半径不生效——那一版的表现就是「没有扩散、直接切好」。
 *
 * 连点：**每一拍都重新起播**，种子跟着刚点的那颗胶囊走。具体分三步：
 * 1. 点下去先**冻结**当前这一帧（停写进度），屏幕定格；
 * 2. 把定格的整屏（可能已经包含上一段扩散的半个边界）取成位图，当作新一段的「旧画面」；
 * 3. 进度归零、种子换成新胶囊：新一段边界从新按钮长出来。
 * 因为第 2 步取的就是屏幕上此刻的像素，第 3 步画出来的第一帧和定格画面完全一致，
 * 所以重播不会跳、不会闪。
 *
 * 退化策略：旧帧取不到（极少数机型 RenderNode 取像素失败）时，只画旧主题底色兜底，
 * 边界照样张开，**绝不静默跳过动画**。
 */
@Stable
class RevealState {
    /** 已受理、正在取旧帧 */
    internal data class Pending(
        /** 目标主题；null = 这次是页面扩散（导航），不换主题 */
        val toId: String?,
        /** 种子（**根坐标系**；取帧完成后减去宿主原点换成宿主坐标） */
        val shapeInRoot: RevealShape,
        /** 旧底色：旧帧缺失时兜底 */
        val fromBg: Color,
        /** 旧帧到手后要执行的动作（页面扩散用它来导航）；须在取帧之后跑，否则旧帧就拍到新页面了 */
        val onReady: (() -> Unit)? = null,
        /** true = 反向（返回）：实时内容整屏铺底，旧帧在上层按边界收缩进被点控件 */
        val reverse: Boolean = false,
    )

    /** 正在扩散 */
    internal data class Active(
        /** 目标主题；null = 页面扩散 */
        val toId: String?,
        /** 种子（**宿主坐标系**） */
        val shape: RevealShape,
        val fromBg: Color,
        /** 一段扩散的编号：每次重新取帧起播都会自增，驱动协程按它换班 */
        val runId: Long,
        /** true = 反向（返回） */
        val reverse: Boolean = false,
    )

    internal var pending by mutableStateOf<Pending?>(null)
        private set

    internal var active by mutableStateOf<Active?>(null)
        private set

    /**
     * 旧帧像素；null = 退化到纯底色。
     *
     * 反向（返回）用的也是这一张：它是 pop **之前**抓下的编辑页，是唯一一张「已经不在组合里、
     * 却必须留在屏幕上的画面」。
     */
    internal var oldFrame by mutableStateOf<ImageBitmap?>(null)
        private set

    /**
     * 边界扩张进度 0..1。**只在绘制阶段读**：读它的地方是 drawWithContent，
     * 每帧只让绘制失效，不触发重组，也不会因为重组被跳过而卡住轮廓。
     */
    internal val progress = mutableFloatStateOf(0f)

    /**
     * 驱动写入两条进度的**代数**：每写一帧自增一次。
     *
     * 它只服务于诊断（见 [SeedFrameTimeline]）：采样端靠它区分「这一帧有没有被新值刷新」，
     * 否则拿不到 vsync 的同一帧样本会被误判成「两条进度没动」而不是「它们一起动了」。
     * 用普通字段而不是 Compose 状态：只在协程里写、在帧回调里读，不该触发任何重组。
     */
    internal var writeSeq: Long = 0L

    /**
     * 方块离场的进度 0..1（只在正向页面扩散用）。
     *
     * 为什么是独立的一条时间线而不是接在 [progress] 后面：两者语义不同——[progress] 推的是
     * **新页面**的边界（旧页被吃掉），方块离场推的是**种子自己**的退场。串在一条进度上
     * 会让「页面已铺满」这个可观察时刻落在一个非整数进度上，所有「读作铺满」的推导都要重算。
     */
    internal val seedExit = mutableFloatStateOf(0f)

    /**
     * 正在渲染的主题。渲染主题只由状态机决定，不直接跟随设置：
     * 取旧帧那两帧里设置可能已经变成新值，若跟着设置走，边界还没开始画主题就换了（等于没动画）。
     */
    private var currentId by mutableStateOf<String?>(null)

    /** 最后一次受理的目标主题：设置回传时用来识别过期中间值（连点会依次回传中间主题） */
    private var lastAccepted: String? = null

    /**
     * 定格标记：连点时先停写进度，让屏幕这一帧停住，
     * 保证「取到的位图」与「用户看到的画面」是同一帧，重播时不跳。
     */
    internal var frozen = false
        private set

    private var runCounter = 0L

    /** 浮层在 Compose 根坐标系中的原点：点击点上报的是 root 坐标，绘制用的是自身坐标 */
    internal var hostOrigin by mutableStateOf(Offset.Zero)
        private set

    /** 宿主尺寸（px）：算边界终点要用 */
    internal var hostSize by mutableStateOf(IntSize.Zero)

    /** 当前应该渲染的主题；null = 跟随设置里的 themeId */
    val renderedId: String?
        get() = active?.toId ?: currentId

    /** 正在做页面扩散（导航）：边界外是旧页面，边界内是新页面 */
    val navigating: Boolean
        get() = active != null && active?.toId == null

    /** 这次页面扩散的种子（方块要按它重画 + 缩回去）；null = 没有可复用的几何 */
    val seed: SeedShape?
        get() = (active?.shape as? RevealShape.Seed)?.seed

    /**
     * 发起一次主题切换。[chipBoundsInRoot] 取自胶囊的 onGloballyPositioned（root 坐标）。
     *
     * 返回 false 只表示「目标就是正在渲染/正在去的那颗，本次无意义」，
     * 调用方据此决定是否落库。**扩散进行中再点别的主题一律受理**：先定格当前帧，
     * 取到新图底后从新胶囊重新起播（见类注释）。
     */
    fun requestTheme(
        toId: String,
        fromId: String,
        chipBoundsInRoot: Rect,
        fromBg: Color,
    ): Boolean {
        val effective = active?.toId ?: pending?.toId ?: currentId ?: fromId
        RevealTrace.log { "request to=" + toId + " effective=" + effective + " bounds=" + chipBoundsInRoot }
        if (toId == effective) {
            RevealTrace.log { "reject: already heading to " + toId }
            return false
        }

        val center = chipBoundsInRoot.center
        val startRadius = min(chipBoundsInRoot.width, chipBoundsInRoot.height) / 2f
        if (active != null) {
            // 连点：先定格，等宿主把这一帧取成图底再重播（种子换成刚点的胶囊）
            frozen = true
            RevealTrace.log { "freeze for restart t=" + progress.floatValue }
        } else {
            RevealTrace.reset()
        }
        pending =
            Pending(
                toId = toId,
                shapeInRoot = RevealShape.Circle(center = center, startRadius = startRadius),
                fromBg = fromBg,
            )
        lastAccepted = toId
        RevealTrace.log { "accepted host=" + hostSize + " origin=" + hostOrigin }
        return true
    }

    /**
     * 发起一次**页面**扩散：先把当前整屏定格取成图底，取到之后再执行 [onReady]（通常就是导航），
     * 新页面随后从 [seed] 那个轮廓里长出来，轮廓外仍是旧页面。
     *
     * 顺序很关键：导航必须在取帧之后，否则旧帧拍到的就是新页面，轮廓外的底会「提前」变成新页面。
     * 转场正在播时不接这次请求（返回 false，由调用方直接导航），避免两段动画抢同一块画布。
     */
    fun requestNavigation(
        seed: SeedShape,
        fromBg: Color,
        onReady: () -> Unit,
    ): Boolean {
        if (active != null || pending != null) {
            RevealTrace.log { "nav reveal skipped: busy" }
            return false
        }
        RevealTrace.reset()
        pending =
            Pending(
                toId = null,
                shapeInRoot = RevealShape.Seed(seed),
                fromBg = fromBg,
                onReady = onReady,
            )
        RevealTrace.log { "nav accepted seed=" + seed + " host=" + hostSize }
        return true
    }

    /**
     * 反向的页面扩散（返回）：先把当前整屏定格取成图底，取到之后执行 [onReady]（通常就是 pop），
     * 旧页面（就是这张图底）在上层按轮廓一路收缩进 [seed]，轮廓外露出下面的**实时内容**。
     *
     * 与正向对称：取帧必须在 pop 之前，否则拍到的就是上一页而不是要收走的那一页。
     * 反向**只取这一张**：背景交给实时内容，交接处天然连续。
     */
    fun requestNavigationBack(
        seed: SeedShape,
        fromBg: Color,
        onReady: () -> Unit,
    ): Boolean {
        if (active != null || pending != null) {
            RevealTrace.log { "nav back skipped: busy" }
            return false
        }
        RevealTrace.reset()
        pending =
            Pending(
                toId = null,
                shapeInRoot = RevealShape.Seed(seed),
                fromBg = fromBg,
                onReady = onReady,
                reverse = true,
            )
        RevealTrace.log { "nav back accepted seed=" + seed }
        return true
    }

    /**
     * 设置里的主题变化：只在**空闲**时跟随。
     * 取帧期间跟随会让主题提前切换（边界还没开始画），扩散中跟随会与状态机的目标打架。
     * 连点时设置会依次回传中间值，只认最后一次受理的目标，否则播完会看到一下主题回跳。
     */
    fun onSettingsTheme(id: String) {
        if (pending != null || active != null) return
        val target = lastAccepted
        if (target != null && id != target) return
        currentId = id
    }

    internal fun markHostOrigin(origin: Offset) {
        if (hostOrigin != origin) hostOrigin = origin
    }

    /** 旧帧到手（或取不到）→ 切新主题并从新种子重新起播；页面扩散则在这里执行导航 */
    internal fun beginReveal(frame: ImageBitmap?) {
        val start = pending ?: return
        oldFrame = frame
        progress.floatValue = 0f
        seedExit.floatValue = 0f
        frozen = false
        runCounter++
        active =
            Active(
                toId = start.toId,
                shape = start.shapeInRoot.toHost(hostOrigin),
                fromBg = start.fromBg,
                runId = runCounter,
                reverse = start.reverse,
            )
        pending = null
        // 页面扩散不改渲染主题；主题扩散才切换
        start.toId?.let { currentId = it }
        RevealTrace.log {
            "beginReveal runId=" + runCounter + " frame=" + frame?.width + "x" + frame?.height +
                " seed=" + active?.shape + " nav=" + (start.toId == null)
        }
        // 旧帧已经拿到，这时再导航：新页面出现时轮廓外的底才是旧页面
        start.onReady?.invoke()
    }

    internal fun finish() {
        RevealTrace.log { "finish t=" + progress.floatValue }
        active = null
        pending = null
        oldFrame = null
        frozen = false
        progress.floatValue = 0f
        seedExit.floatValue = 0f
    }

    /** 只收掉指定这一轮；换目标后旧驱动被取消时不能误清新状态 */
    internal fun finishIfRunning(runId: Long) {
        if (active?.runId == runId) finish()
    }

    /** 当帧的圆形半径（换主题用；宿主坐标）。页面扩散请用 [dilationOf] */
    internal fun radiusOf(active: Active): Float {
        val circle = active.shape as? RevealShape.Circle ?: return 0f
        val far =
            revealRadius(
                circle.center.x,
                circle.center.y,
                hostSize.width.toFloat(),
                hostSize.height.toFloat(),
            ) + 2f
        val t = progress.floatValue.coerceIn(0f, 1f)
        return if (active.reverse) {
            far + (circle.startRadius - far) * t
        } else {
            circle.startRadius + (far - circle.startRadius) * t
        }
    }

    /**
     * 当帧的外扩距离（页面扩散用；宿主坐标）。
     *
     * 正向 0 → 覆盖整屏；反向 覆盖整屏 → 0。两端都是 0 的含义是「轮廓就是种子本身」，
     * 所以正向第 0 帧画出来与那个方块严丝合缝，反向最后一帧正好收回方块。
     */
    internal fun dilationOf(active: Active): Float {
        val seed = (active.shape as? RevealShape.Seed)?.seed ?: return 0f
        val far = dilationToCover(seed, hostSize.width.toFloat(), hostSize.height.toFloat())
        val t = progress.floatValue.coerceIn(0f, 1f)
        return if (active.reverse) dilationAt(far, 0f, t) else dilationAt(0f, far, t)
    }
}

/** 根坐标 → 宿主坐标 */
private fun RevealShape.toHost(origin: Offset): RevealShape =
    when (this) {
        is RevealShape.Circle -> copy(center = center - origin)
        is RevealShape.Seed ->
            copy(
                seed =
                    seed.copy(
                        bounds =
                            seed.bounds.translate(-origin.x, -origin.y),
                    ),
            )
    }

@Composable
fun rememberRevealState(): RevealState = remember { RevealState() }
