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
        pending =
            Pending(
                toId = toId,
                centerInRoot = center,
                startRadius = startRadius,
                fromBg = fromBg,
            )
        lastAccepted = toId
        RevealTrace.log { "accepted host=$hostSize origin=$hostOrigin" }
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
        pending =
            Pending(
                toId = null,
                centerInRoot = boundsInRoot.center,
                startRadius = min(boundsInRoot.width, boundsInRoot.height) / 2f,
                fromBg = fromBg,
                onReady = onReady,
            )
        RevealTrace.log { "nav accepted center=${boundsInRoot.center} host=$hostSize" }
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
        pending =
            Pending(
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
        active =
            Active(
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
        val far =
            revealRadius(
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
