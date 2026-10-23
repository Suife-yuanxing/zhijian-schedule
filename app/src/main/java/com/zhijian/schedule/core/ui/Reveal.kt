package com.zhijian.schedule.core.ui

import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.withTimeoutOrNull

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
        Log.i(TAG, "+${t}ms ${msg()}")
    }
}

/**
 * 主题切换的圆形扩散（circular reveal）。
 *
 * 结构照抄业界标准写法（Scott Pierce《Circular Reveal Animations in Compose》、
 * Krossovochkin《Change Theme Dynamically with Circular Reveal》、bmonjoie《Reveal effect》）：
 * **旧画面留在下面不动 → 新画面按一个从点击处长大的圆裁剪盖上来 → 圆盖满再撤掉旧画面。**
 *
 * 两处刻意的实现选择：
 * - 旧画面用 [GraphicsLayer.toImageBitmap] 取**真实像素**，不用 `GraphicsLayer.record{}` 录绘制指令。
 *   录指令依赖「绘制阶段写标记、下一帧读标记」的时序，任何一帧没画到就会录到空帧，
 *   于是整段扩散退化成瞬间切换；取像素是一次同步的 RenderNode→Bitmap，成败是确定的。
 * - 裁剪在**绘制阶段**用 `clipPath` 做，进度也在绘制阶段读：这样每帧只失效绘制，
 *   不依赖「重组 → 换 Shape → 刷新图层轮廓」这条链。实测在 graphicsLayer{} 里改 shape
 *   只有图层首帧会被采纳，改半径不生效——那一版的表现就是「没有扩散、直接切好」。
 *
 * 连点：**每一拍都重新起播**，圆心跟着刚点的那颗胶囊走（与 sinasamaki 的 Compose 主题选择器、
 * Uniwind 的 snapshot-overlay 两阶段做法一致）。具体分三步：
 * 1. 点下去先**冻结**当前这一帧（停写进度），屏幕定格；
 * 2. 把定格的整屏（可能已经包含上一段扩散的半个圆）取成位图，当作新一段的「旧画面」；
 * 3. 进度归零、圆心换成新胶囊：新一段圆从新按钮长出来。
 * 因为第 2 步取的就是屏幕上此刻的像素，第 3 步画出来的第一帧和定格画面完全一致，
 * 所以重播不会跳、不会闪，而主题**永远是在一个正在张开的圆里面换的**——
 * 不会出现「圆已经铺开、主题啪一下换掉」那种看起来像直接切换的瞬间。
 *
 * 退化策略：旧帧取不到（极少数机型 RenderNode 取像素失败）时，只画旧主题底色兜底，
 * 圆照样张开、主题照样从胶囊扩散出去，**绝不静默跳过动画**。
 *
 * 同一套机制也服务「页面扩散」（[requestNavigation]）：加号开课程编辑页时，
 * 先定格整屏取图底 → 再导航 → 新页面从加号那个圆里长出来，圆外仍是旧课表。
 *
 * 返回（[requestNavigationBack]）是它的反向用法：编辑页同样先定格取图底，再 pop，
 * 然后这张图底盖在最上层按圆收缩进加号——**圆外不铺任何底图**，直接露浮层下面的实时课表，
 * 这样动画结束撤掉浮层时画面连续（缘由见 [RevealHost] 反向分支）。
 */
@Stable
class RevealState {

    /** 已受理、正在取旧帧 */
    internal data class Pending(
        /** 目标主题；null = 这次是页面扩散（导航），不换主题 */
        val toId: String?,
        /** 被点控件中心，Compose 根坐标系 px */
        val centerInRoot: Offset,
        /** 起始半径：刚好盖住被点控件，起手不会先把按钮抹掉 */
        val startRadius: Float,
        /** 旧底色：旧帧缺失时兜底 */
        val fromBg: Color,
        /** 旧帧到手后要执行的动作（页面扩散用它来导航）；须在取帧之后跑，否则旧帧就拍到新页面了 */
        val onReady: (() -> Unit)? = null,
        /** true = 反向（返回）：实时内容整屏铺底，旧帧在上层按圆收缩进被点控件 */
        val reverse: Boolean = false,
    )

    /** 正在扩散 */
    internal data class Active(
        /** 目标主题；null = 页面扩散 */
        val toId: String?,
        /** 圆心，宿主自身坐标系 px */
        val center: Offset,
        val startRadius: Float,
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
     * 却必须留在屏幕上的画面」。反向的**背景**不再另抓位图，直接用浮层下面的实时内容
     * （见 [RevealHost] 反向分支的注释）：上一版在 pop 之后另抓过一张课表整屏位图当背景，
     * 那张图抓完就定格，而课表返回时会重播逐块入场，于是整段收回盖的都是过期画面、
     * 动画一结束硬切一下——返回闪烁的直接来源。
     */
    internal var oldFrame by mutableStateOf<ImageBitmap?>(null)
        private set

    /**
     * 扩散进度 0..1。**只在绘制阶段读**：读它的地方是 drawWithContent，
     * 每帧只让绘制失效，不触发重组，也不会因为重组被跳过而卡住轮廓。
     */
    internal val progress = mutableFloatStateOf(0f)

    /**
     * 正在渲染的主题。渲染主题只由状态机决定，不直接跟随设置：
     * 取旧帧那两帧里设置可能已经变成新值，若跟着设置走，圆还没开始画主题就换了（等于没动画）。
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

    /** 宿主尺寸（px）：算圆的终点半径要用 */
    internal var hostSize by mutableStateOf(IntSize.Zero)

    /** 当前应该渲染的主题；null = 跟随设置里的 themeId */
    val renderedId: String?
        get() = active?.toId ?: currentId

    /** 正在做页面扩散（导航）：圆外是旧页面，圆内是新页面 */
    val navigating: Boolean
        get() = active != null && active?.toId == null

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
        RevealTrace.log { "request to=$toId effective=$effective bounds=$chipBoundsInRoot" }
        if (toId == effective) {
            RevealTrace.log { "reject: already heading to $toId" }
            return false
        }

        val center = chipBoundsInRoot.center
        val startRadius = min(chipBoundsInRoot.width, chipBoundsInRoot.height) / 2f
        if (active != null) {
            // 连点：先定格，等宿主把这一帧取成图底再重播（圆心换成刚点的胶囊）
            frozen = true
            RevealTrace.log { "freeze for restart t=${progress.floatValue}" }
        } else {
            RevealTrace.reset()
        }
        pending = Pending(
            toId = toId,
            centerInRoot = center,
            startRadius = startRadius,
            fromBg = fromBg,
        )
        lastAccepted = toId
        RevealTrace.log { "accepted host=${hostSize} origin=$hostOrigin" }
        return true
    }

    /**
     * 发起一次**页面**扩散：先把当前整屏定格取成图底，取到之后再执行 [onReady]（通常就是导航），
     * 新页面随后从被点控件那个圆里长出来，圆外仍是旧页面。
     *
     * 顺序很关键：导航必须在取帧之后，否则旧帧拍到的就是新页面，圆外的底会「提前」变成新页面。
     * 转场正在播时不接这次请求（返回 false，由调用方直接导航），避免两段动画抢同一块画布。
     */
    fun requestNavigation(
        boundsInRoot: Rect,
        fromBg: Color,
        onReady: () -> Unit,
    ): Boolean {
        if (active != null || pending != null) {
            RevealTrace.log { "nav reveal skipped: busy" }
            return false
        }
        RevealTrace.reset()
        pending = Pending(
            toId = null,
            centerInRoot = boundsInRoot.center,
            startRadius = min(boundsInRoot.width, boundsInRoot.height) / 2f,
            fromBg = fromBg,
            onReady = onReady,
        )
        RevealTrace.log { "nav accepted center=${boundsInRoot.center} host=${hostSize}" }
        return true
    }

    /**
     * 反向的页面扩散（返回）：先把当前整屏定格取成图底，取到之后执行 [onReady]（通常就是 pop），
     * 旧页面（就是这张图底）在上层按圆一路收缩进 [boundsInRoot] 那个控件，
     * 圆外露出下面的**实时内容**——也就是被返回的那一页。
     *
     * 与正向对称：取帧必须在 pop 之前，否则拍到的就是上一页而不是要收走的那一页。
     * 与正向不同：反向**只取这一张**。上一版还会在 pop 之后再抓一张上一页的整屏位图当背景，
     * 那张图抓完即定格，而上一页（课表）这时正在重播入场动画，于是整段收回盖的都是过期画面；
     * 现在背景交给实时内容，交接处天然连续（缘由见 [oldFrame] 与 [RevealHost] 反向分支）。
     */
    fun requestNavigationBack(
        boundsInRoot: Rect,
        fromBg: Color,
        onReady: () -> Unit,
    ): Boolean {
        if (active != null || pending != null) {
            RevealTrace.log { "nav back skipped: busy" }
            return false
        }
        RevealTrace.reset()
        pending = Pending(
            toId = null,
            centerInRoot = boundsInRoot.center,
            startRadius = min(boundsInRoot.width, boundsInRoot.height) / 2f,
            fromBg = fromBg,
            onReady = onReady,
            reverse = true,
        )
        RevealTrace.log { "nav back accepted center=${boundsInRoot.center}" }
        return true
    }

    /**
     * 设置里的主题变化：只在**空闲**时跟随。
     * 取帧期间跟随会让主题提前切换（圆还没开始画），扩散中跟随会与状态机的目标打架。
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

    /** 旧帧到手（或取不到）→ 切新主题并从新胶囊重新起播；页面扩散则在这里执行导航 */
    internal fun beginReveal(frame: ImageBitmap?) {
        val start = pending ?: return
        oldFrame = frame
        progress.floatValue = 0f
        frozen = false
        runCounter++
        active = Active(
            toId = start.toId,
            center = start.centerInRoot - hostOrigin,
            startRadius = start.startRadius,
            fromBg = start.fromBg,
            runId = runCounter,
            reverse = start.reverse,
        )
        pending = null
        // 页面扩散不改渲染主题；主题扩散才切换
        start.toId?.let { currentId = it }
        RevealTrace.log {
            "beginReveal runId=$runCounter frame=${frame?.width}x${frame?.height} " +
                "center=${active?.center} nav=${start.toId == null}"
        }
        // 旧帧已经拿到，这时再导航：新页面出现时圆外的底才是旧页面
        start.onReady?.invoke()
    }

    internal fun finish() {
        RevealTrace.log { "finish t=${progress.floatValue}" }
        active = null
        pending = null
        oldFrame = null
        frozen = false
        progress.floatValue = 0f
    }

    /** 只收掉指定这一轮；换目标后旧驱动被取消时不能误清新状态 */
    internal fun finishIfRunning(runId: Long) {
        if (active?.runId == runId) finish()
    }

    /** 当帧圆的半径（宿主坐标） */
    internal fun radiusOf(active: Active): Float {
        val far = revealRadius(
            active.center.x,
            active.center.y,
            hostSize.width.toFloat(),
            hostSize.height.toFloat(),
        ) + 2f
        val t = progress.floatValue.coerceIn(0f, 1f)
        // 正向：小圆 → 铺满；反向：铺满 → 收回被点控件
        return if (active.reverse) {
            far + (active.startRadius - far) * t
        } else {
            active.startRadius + (far - active.startRadius) * t
        }
    }
}

@Composable
fun rememberRevealState(): RevealState = remember { RevealState() }

/**
 * 取帧图层的「画过没有」标记。
 * 用普通可变字段而不是 Compose 状态：它只在绘制阶段写、在协程里读，
 * 不需要触发任何重组（走状态的话绘制期写状态每帧都会让组合失效）。
 */
private class CaptureFlag {
    var ready = false
}

/**
 * 圆形扩散宿主：旧帧铺底 → 换主题/导航 → 新内容按圆张开。
 *
 * 必须放在主题外壳的根部（比导航、底栏、弹窗都外层），扩散才能盖满整屏；
 * 也要在 `CourseTheme` 之内，浮层才能和内容共享同一套坐标系。
 */
@Composable
fun RevealHost(
    state: RevealState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val captureLayer = rememberGraphicsLayer()
    // 图层里到底有没有画过内容：没有就得先等帧，直接取会拿到空图（圆外会闪成纯色）
    val layerReady = remember { CaptureFlag() }

    val active = state.active
    val capturing = state.pending != null
    // 整屏录制只在「马上要取一张整屏」的时候挂着：
    // - 取帧阶段（pending）：正向抓旧页、反向抓编辑页，都靠这一张；
    // - 主题扩散进行中：连点要定格当前帧，图底必须已经在图层里，现等帧会跳。
    // 页面扩散（toId == null）一旦起播就不再需要它：正向的底是 oldFrame、反向的底是实时内容，
    // 没有任何「再抓一张」的需求。继续录就是每帧多一次整屏离屏渲染 + 一次全屏合成。
    val recording = capturing || active?.toId != null

    LaunchedEffect(recording) {
        if (!recording) layerReady.ready = false
    }

    LaunchedEffect(capturing) {
        if (!capturing) return@LaunchedEffect
        val waits = if (layerReady.ready) 0 else 2
        RevealTrace.log { "capture start waits=$waits layerReady=${layerReady.ready}" }
        // 整个取帧过程带超时——窗口在后台/掉帧时帧回调可能一直不来，绝不能无限挂着把状态卡死
        val frame = try {
            withTimeoutOrNull(400) {
                repeat(waits) { withFrameNanos { } }
                captureLayer.toImageBitmap()
            }
        } catch (t: Throwable) {
            RevealTrace.log { "capture threw ${t::class.java.simpleName}: ${t.message}" }
            null
        }?.takeIf { it.width > 0 && it.height > 0 }
        RevealTrace.log { "capture done frame=${frame?.width}x${frame?.height}" }
        state.beginReveal(frame)
    }

    Box(modifier.fillMaxSize().onSizeChanged { state.hostSize = it }) {
        Box(
            Modifier
                .fillMaxSize()
                // 官方 capture-composable 写法：录一帧再重绘一帧；连点时取的就是这里录下的定格帧
                .then(
                    if (recording) {
                        Modifier.drawWithContent {
                            captureLayer.record {
                                this@drawWithContent.drawContent()
                            }
                            drawLayer(captureLayer)
                            layerReady.ready = true
                        }
                    } else {
                        Modifier
                    },
                ),
        ) {
            // 正向（换主题 / 进页面）：旧帧整屏铺底，新内容按圆裁剪盖上来
            if (active != null && !active.reverse) {
                val frame = state.oldFrame
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(active.fromBg)
                    if (frame != null) {
                        drawImage(
                            image = frame,
                            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                        )
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { state.markHostOrigin(it.positionInRoot()) }
                    .drawWithContent {
                        val current = state.active
                        // 反向（返回）：新页面整屏画，被收走的那一页在上层按圆收缩
                        if (current == null || current.reverse) {
                            drawContent()
                            return@drawWithContent
                        }
                        // 进度在绘制阶段读：只失效绘制，不依赖重组
                        val radius = state.radiusOf(current)
                        val path = Path().apply {
                            addOval(
                                Rect(
                                    left = current.center.x - radius,
                                    top = current.center.y - radius,
                                    right = current.center.x + radius,
                                    bottom = current.center.y + radius,
                                ),
                            )
                        }
                        clipPath(path) { this@drawWithContent.drawContent() }
                    },
            ) {
                content()
            }

            // 反向：被收走的那一页（旧帧）盖在最上层，被一个缩回被点控件的圆裁掉。
            //
            // 这里**不做整块缩放、也不做淡出**（只有最后一下化掉），就是正向的严格镜像：
            // 正向「圆从按钮长大、圆内是新页面」，反向「圆从满屏缩回按钮、圆内是旧页面」。
            // 曾经试过再叠一层 zoom + fade 来「加强存在感」，结果是三重运动互相打架：
            // 圆被缩一遍、图又被缩一遍，剩下的那块既不在按钮中心、边界也不是圆，
            // 画出来是一块飘在屏幕中间的残影（像素分析实测缩放中心落在画布中心附近，
            // 根本不是加号）。镜像本身就是最清楚的语义，加强只能靠时长与缓动，不能靠叠运动。
            //
            // 圆外**不铺任何底图**：浮层下面那层实时内容就是背景。上一版在 pop 之后另抓一张
            // 「上一页整屏位图」盖上来，那张图抓完即定格，而课表返回时会重播逐块入场
            // （WeekGrid 的 entranceDelayMs → CourseBlock.cascadeIn），抓图那一刻课块还没落位，
            // 于是整段收回盖着的都是「半空的课表」；动画一结束 finish() 撤掉浮层，真实课表
            // 整屏顶上来，交接处硬切一下——返回闪烁的直接来源。
            // 另外抓图要等 6 帧（旧代码），而驱动协程一设 active 就起播，那 6 帧里 backdrop 还是
            // null，圆外露的是纯底色，起手先闪一下。
            // 铺实时内容之后：整段收回背景=真实课表，最后那一段淡出露出的也正是它自己，
            // 交接在构造上就不可能跳；少一次整屏位图读回（GPU 同步），也少两张整屏图层的录制。
            if (active != null && active.reverse) {
                val frame = state.oldFrame
                if (frame != null) {
                    Canvas(Modifier.fillMaxSize()) {
                        val t = state.progress.floatValue.coerceIn(0f, 1f)
                        val radius = state.radiusOf(active)
                        // 最后一小段化掉：圆收到加号大小时让真实加号接上，别留一个硬边的圆
                        val alpha = reverseMaskAlpha(t)
                        val path = Path().apply {
                            addOval(
                                Rect(
                                    left = active.center.x - radius,
                                    top = active.center.y - radius,
                                    right = active.center.x + radius,
                                    bottom = active.center.y + radius,
                                ),
                            )
                        }
                        clipPath(path) {
                            drawImage(
                                image = frame,
                                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                                alpha = alpha,
                            )
                        }
                    }
                }
            }
        }

        if (active != null) RevealDriver(state, active)
    }
}

/** 只推进进度、不画东西：进度是绘制阶段读的，改它只失效绘制 */
@Composable
private fun RevealDriver(state: RevealState, active: RevealState.Active) {
    // 时间线（时长 + 缓动）由方向与语义决定，分发抽成纯函数便于单测钉住，见 revealTiming。
    val timing = revealTiming(reverse = active.reverse, themeSwitch = active.toId != null)
    LaunchedEffect(active.runId) {
        RevealTrace.log { "driver start run=${active.runId}" }
        try {
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = timing.durationMillis,
                    easing = timing.easing,
                ),
            ) { value, _ ->
                // 连点定格期间停写：屏幕停在取图底的那一帧，重播才不会跳
                if (!state.frozen) state.progress.floatValue = value
            }
            RevealTrace.log { "driver done run=${active.runId}" }
        } finally {
            // 被取消（切后台 / 离开组合 / 旋转 / 换新一段）时也要收尾，
            // 但只收自己这一轮：换新一段后旧协程被取消，不能把新的清掉
            state.finishIfRunning(active.runId)
        }
    }
}

/** 一次扩散的时长与缓动 */
internal data class RevealTiming(val durationMillis: Int, val easing: Easing)

/**
 * 扩散时间线的分发：一次扩散的方向（reverse）与语义（换主题 / 换页面）决定用哪套时长与缓动。
 *
 * - 页面正向（加号 → 编辑页）与换主题：同一条加速曲线 [Motion.EaseRevealAccelerate]，
 *   时长各自补偿过（见 Motion 里两个常量的推导）；
 * - 页面反向（返回收回）：[Motion.EaseRevealDecelerate]，正向曲线的严格时间镜像——
 *   方向相反但不打架，反向就是正向的倒放；
 * - [reverse] 优先于 [themeSwitch]：反向只可能来自 requestNavigationBack（toId 恒为 null），
 *   两者同时为真不可达；这条优先级只是把「不可达组合」的语义提前定死，免得日后改动踩空。
 */
internal fun revealTiming(reverse: Boolean, themeSwitch: Boolean): RevealTiming = when {
    reverse -> RevealTiming(Motion.DurRevealBack, Motion.EaseRevealDecelerate)
    themeSwitch -> RevealTiming(Motion.DurRevealTheme, Motion.EaseRevealAccelerate)
    else -> RevealTiming(Motion.DurReveal, Motion.EaseRevealAccelerate)
}

/**
 * 反向遮罩的淡出系数：进度 0.9 之前整块不透明，到 1 完全化掉，
 * 让圆收到加号大小时由真实的加号接手，不留一个硬边的圆点。
 * 圆外不参与——那里是实时内容（见 [RevealHost] 反向分支）。
 */
internal fun reverseMaskAlpha(progress: Float): Float =
    1f - smoothstep(0.9f, 1f, progress)

/**
 * 圆心到屏幕最远角的距离：扩散半径的终点。
 * 按「水平最远边 + 垂直最远边」合成，圆心贴边或落在屏幕外时同样成立，
 * 保证圆最终一定完整覆盖整块画布。
 */
private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
    if (edge0 == edge1) return if (value < edge0) 0f else 1f
    val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal fun revealRadius(centerX: Float, centerY: Float, width: Float, height: Float): Float {
    val dx = max(centerX, width - centerX)
    val dy = max(centerY, height - centerY)
    return hypot(dx, dy)
}
