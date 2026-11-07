package com.zhijian.schedule.core.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.theme.MotionProfile
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

/**
 * 取帧图层的「画过没有」标记。
 * 用普通可变字段而不是 Compose 状态：它只在绘制阶段写、在协程里读，
 * 不需要触发任何重组（走状态的话绘制期写状态每帧都会让组合失效）。
 */
private class CaptureFlag {
    var ready = false
}

/**
 * 扩散宿主：旧帧铺底 → 换主题/导航 → 新内容按**种子轮廓**张开 → 种子离场。
 *
 * 必须放在主题外壳的根部（比导航、底栏、弹窗都外层），扩散才能盖满整屏；
 * 也要在 `CourseTheme` 之内，浮层才能和内容共享同一套坐标系。
 *
 * ## 页面扩散与主题扩散的形状口径不同
 *
 * - 换主题：仍是**圆**从胶囊长出去（[RevealShape.Circle]），语义没动；
 * - 进编辑页：边界是**被点控件实测轮廓的等距外扩**（[RevealShape.Seed]）。外扩距离为 0 时
 *   轮廓就是那个方块本身——所以第 0 帧的裁剪边界与方块严丝合缝，页面是「从方块里长出来」
 *   而不是「一个比方块大的圆把方块吃掉」。
 *
 * ## 加号方块为什么要在这里重画一遍
 *
 * 方块原先活在课表页里，导航一发生它就被销毁了；而需求要求它撑到**页面铺满之后**才离场。
 * 于是它被搬到这一层：由 [SeedOverlay] 用 [SeedBadge] 按同一份实测几何重画一枚盖在裁剪层
 * 之上，正向全程可见，铺满之后再缩小淡出。课表页里那枚真按钮此刻已经不在组合里，
 * 两者只在第 0 帧与最后一帧重合——那正是交接点，所以接缝不可见。
 */
@Composable
fun RevealHost(
    state: RevealState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val captureLayer = rememberGraphicsLayer()
    // 图层里到底有没有画过内容：没有就得先等帧，直接取会拿到空图（轮廓外会闪成纯色）
    val layerReady = remember { CaptureFlag() }

    val active = state.active
    val capturing = state.pending != null
    // 整屏录制只在「马上要取一张整屏」的时候挂着：
    // - 取帧阶段（pending）：正向抓旧页、反向抓编辑页，都靠这一张；
    // - 主题扩散进行中：连点要定格当前帧，图底必须已经在图层里，现等帧会跳。
    val recording = capturing || active?.toId != null

    LaunchedEffect(recording) {
        if (!recording) layerReady.ready = false
    }

    LaunchedEffect(capturing) {
        if (!capturing) return@LaunchedEffect
        val waits = if (layerReady.ready) 0 else 2
        RevealTrace.log { "capture start waits=" + waits + " layerReady=" + layerReady.ready }
        // 整个取帧过程带超时——窗口在后台/掉帧时帧回调可能一直不来，绝不能无限挂着把状态卡死
        val frame =
            try {
                withTimeoutOrNull(400) {
                    repeat(waits) { withFrameNanos { } }
                    captureLayer.toImageBitmap()
                }
            } catch (t: Throwable) {
                RevealTrace.log { "capture threw " + t::class.java.simpleName + ": " + t.message }
                null
            }?.takeIf { it.width > 0 && it.height > 0 }
        RevealTrace.log { "capture done frame=" + frame?.width + "x" + frame?.height }
        state.beginReveal(frame)
    }

    Box(
        modifier
            .fillMaxSize()
            .onSizeChanged { state.hostSize = it },
    ) {
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
            // 正向（换主题 / 进页面）：旧帧整屏铺底，新内容按边界裁剪盖上来
            if (active != null && !active.reverse) {
                OldFrameBackdrop(active = active, frame = state.oldFrame)
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { state.markHostOrigin(it.positionInRoot()) }
                    .clipToBoundary(state),
            ) {
                content()
            }

            // 正向：被点控件（加号方块）盖在裁剪层之上，全程可见，铺满后缩小离场。
            // 反向不放：那一程方块本来就还在课表页里活着，不需要副本。
            val seed = state.seed
            if (active != null && !active.reverse && seed?.badge != null) {
                SeedOverlay(state = state, active = active, seed = seed)
            }

            // 反向：被收走的那一页（旧帧）盖在最上层，被一个缩回被点控件的轮廓裁掉。
            //
            // 这里**不做整块缩放、也不做淡出**（只有最后一下化掉），就是正向的严格镜像。
            // 曾经试过再叠一层 zoom + fade 来「加强存在感」，结果是三重运动互相打架：
            // 圆被缩一遍、图又被缩一遍，剩下的那块既不在按钮中心、边界也不是圆。
            // 镜像本身就是最清楚的语义，加强只能靠时长与缓动，不能靠叠运动。
            //
            // 轮廓外**不铺任何底图**：浮层下面那层实时内容就是背景。上一版在 pop 之后另抓一张
            // 「上一页整屏位图」盖上来，那张图抓完即定格，而课表返回时会重播逐块入场，
            // 于是整段收回盖着的都是「半空的课表」，动画一结束撤掉浮层就硬切一下。
            if (active != null && active.reverse) {
                ReverseOldFrame(active = active, state = state)
            }
        }

        if (active != null) {
            // 逐帧取证：正向页面扩散（带方块副本）才采，其余路径没有「两条进度」这件事。
            //
            // 必须放在 LaunchedEffect 里而不是直接写在组合体内：`watch` 会新建一个
            // SeedFrameTimeline 当本轮统计，写在组合体里的话动画期间每重组一次就清零一次，
            // 日志里那些 frame=1、frame=2 的重复序号就是这么来的（实测同一轮出现过三段）。
            // 以 runId 为 key，一轮只挂一次。
            LaunchedEffect(active.runId) {
                SeedFrameProbe.watch(
                    tracking = active.toId == null && !active.reverse && state.seed?.badge != null,
                    progressOf = { state.progress.floatValue },
                    seedOf = { state.seedExit.floatValue },
                    writeSeqOf = { state.writeSeq },
                )
            }
            RevealDriver(state, active)
        }
    }
}

/** 正向的底：旧页整屏。旧帧取不到时退化成旧主题底色，绝不静默跳过动画 */
@Composable
private fun OldFrameBackdrop(
    active: RevealState.Active,
    frame: androidx.compose.ui.graphics.ImageBitmap?,
) {
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

/**
 * 按当帧边界裁剪新内容。两种口径分开算，进度在**绘制阶段**读（每帧只失效绘制）。
 */
private fun Modifier.clipToBoundary(state: RevealState): Modifier =
    this.drawWithContent {
        val current = state.active
        // 反向（返回）：新页面整屏画，被收走的那一页在上层按轮廓收缩
        if (current == null || current.reverse) {
            drawContent()
            return@drawWithContent
        }
        clipPath(boundaryPath(state, current)) { this@drawWithContent.drawContent() }
    }

/** 当帧边界路径（宿主坐标）：换主题是圆，页面扩散是种子轮廓的等距外扩 */
private fun boundaryPath(
    state: RevealState,
    active: RevealState.Active,
): Path =
    Path().apply {
        when (val shape = active.shape) {
            is RevealShape.Circle -> {
                val radius = state.radiusOf(active)
                addOval(
                    Rect(
                        left = shape.center.x - radius,
                        top = shape.center.y - radius,
                        right = shape.center.x + radius,
                        bottom = shape.center.y + radius,
                    ),
                )
            }
            is RevealShape.Seed ->
                addRoundRect(dilatedRound(shape.seed, state.dilationOf(active)))
        }
    }

/** 反向：旧页整屏，被收缩的轮廓裁掉；最后 10% 化掉，让真实控件接手 */
@Composable
private fun BoxScope.ReverseOldFrame(
    active: RevealState.Active,
    state: RevealState,
) {
    val frame = state.oldFrame ?: return
    Canvas(Modifier.fillMaxSize()) {
        val alpha = reverseMaskAlpha(state.progress.floatValue.coerceIn(0f, 1f))
        clipPath(boundaryPath(state, active)) {
            drawImage(
                image = frame,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                alpha = alpha,
            )
        }
    }
}

/**
 * 加号方块的副本：按实测几何重画一枚盖在裁剪层之上，与页面展开**同时**离场。
 *
 * ## 它不是被缩小的，是被页面「吃掉」的
 *
 * 缩放是相似变换，形状比恒定；而扩张方块的边界是闵可夫斯基外扩，形状比随外扩单调趋近
 * 正圆。两者只在 p = 0 那一帧重合，之后分属两族曲线——所以旧版（中心缩放 1→0）
 * 无论怎么调曲线都读作「另一个小方块在动」，跟页面不是一套语言。
 * 现在方块走的是 [shrunkenRound]：**四边等速内缩 + 圆角朝半边化**，与边界同一族、同一方向。
 *
 * ## 三层排序借 container transform 的两条 fadeMode 语义
 *
 * 出场侧用 `THROUGH` 的**先后**：内容先离场、容器后离场——字形（0.24→0.40）比本体
 * （0.18→0.62）走得早而快。入场侧用 `OUT` 的语义：新页面全程不淡入，只被边界揭示。
 * 顺序反过来的话，会先看到一个孤零零的叉号浮在浅色页面上，比看到方块更刺眼。
 * 三层都由同一条 [RevealState.seedExit] 驱动，仍与边界进度逐帧齐平。
 */
@Composable
private fun BoxScope.SeedOverlay(
    state: RevealState,
    active: RevealState.Active,
    seed: SeedShape,
) {
    val badge = seed.badge ?: return
    val motion = seedMotion()
    // 字形转角：与页面展开同时起跑（方块不再被盖住，不需要先转完再放行）
    val glyph = remember(active.runId) { Animatable(0f) }
    LaunchedEffect(active.runId) {
        if (!motion.morphGlyph) {
            glyph.snapTo(0f)
            return@LaunchedEffect
        }
        glyph.animateTo(1f, tween(durationMillis = Motion.DUR_FAB_MORPH, easing = Motion.EaseOut))
    }
    Canvas(Modifier.fillMaxSize()) {
        val exit = state.seedExit.floatValue.coerceIn(0f, 1f)
        val alpha = seedAlpha(exit)
        val glyphAlpha = seedGlyphAlpha(exit)
        // 颜色移交：方块的颜色先以细边形式长到页面的边界上，再化掉。
        // 先画（压在方块下面）：描边涨满那一刻边界已经离开方块 190px，整条线都在方块之外；
        // 而第 0 帧它自己就是 0，所以不会在方块四周多出一圈同色光晕。
        val edge = seedEdgeAlpha(exit)
        if (edge > SEED_INVISIBLE_BELOW) {
            drawPath(
                path = boundaryPath(state, active),
                color = badge.fill.copy(alpha = badge.fill.alpha * edge),
                style = Stroke(width = SEED_EDGE_WIDTH.toPx()),
            )
        }
        if (alpha <= SEED_INVISIBLE_BELOW) return@Canvas
        val rect = shrunkenRound(seed, exit, roundOut = motion.roundOut)
        val size = Size(rect.width, rect.height)
        val topLeft = Offset(rect.left, rect.top)
        drawRoundRect(
            color = badge.fill.copy(alpha = badge.fill.alpha * alpha),
            topLeft = topLeft,
            size = size,
            cornerRadius = rect.topLeftCornerRadius,
        )
        // 球冠高光跟着方块一起收：比例不变，几何从同一个短边算出来，读作同一块材质在变小
        badge.highlight?.let { light ->
            val spot = seedHighlight(size.width, size.height)
            drawCircle(
                color = light.copy(alpha = light.alpha * alpha),
                radius = spot.radius,
                center = topLeft + spot.center,
            )
        }
        if (glyphAlpha <= SEED_INVISIBLE_BELOW) return@Canvas
        drawGlyph(
            boxSize = size,
            origin = topLeft,
            tint = badge.tint.copy(alpha = badge.tint.alpha * glyphAlpha),
            degrees = FabMorph.rotationDegrees(glyph.value),
        )
    }
}

/**
 * 方块副本的可见阈值：不透明度低于它就不再画。
 *
 * 1% 是「眼睛已经看不见、但显卡还在为它做一次合成」的分界；不设这道闸时，
 * 缩到 0 的最后一帧仍会画出一个亚像素的实心点。
 */
private const val SEED_INVISIBLE_BELOW = 0.01f

/**
 * 边界描边的粗细。
 *
 * 2dp 是「看得出是一条线、又不像给页面镶了个框」的分界：再细（1dp）在 3x 屏上只剩 3px，
 * 加速到末段时会被运动模糊吃掉；再粗（3dp 以上）就会读成独立的装饰边框，
 * 而不是「从方块里被拉出来的一道边」。
 */
private val SEED_EDGE_WIDTH = 2.dp

/**
 * 那两条笔画：长度与粗细按方块边长折算，与 Material `Add` 图标在同一档比例上。
 *
 * 现在要显式给 [origin] 与 [boxSize]：方块不再是原地画满整个画布，它的矩形每帧都在内缩，
 * 画布却仍是整屏（见 SeedOverlay）。字形必须跟着那个矩形走，否则会留在屏幕左上角。
 */
private fun DrawScope.drawGlyph(
    boxSize: Size,
    origin: Offset,
    tint: Color,
    degrees: Float,
) {
    val side = min(boxSize.width, boxSize.height)
    val bar = side * 0.25f
    val thickness = (side * 0.036f).coerceAtLeast(1.dp.toPx())
    val center = origin + Offset(boxSize.width / 2f, boxSize.height / 2f)
    rotate(degrees, pivot = center) {
        drawLine(
            color = tint,
            start = Offset(center.x - bar, center.y),
            end = Offset(center.x + bar, center.y),
            strokeWidth = thickness,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = tint,
            start = Offset(center.x, center.y - bar),
            end = Offset(center.x, center.y + bar),
            strokeWidth = thickness,
            cap = StrokeCap.Round,
        )
    }
}

/** 当前皮肤允许的离场编排：素墨只淡出不缩放、也不旋转（见 [MotionProfile]） */
@Composable
private fun seedMotion() =
    if (LocalCoursePreset.current.motion == MotionProfile.Still) Motion.SeedFade else Motion.SeedShrink

/**
 * 只推进进度、不画东西：进度是绘制阶段读的，改它只失效绘制。
 *
 * 两条进度在这里**同时**推进（同一帧回调、同一条曲线）：新页面的边界向外扩
 * （[RevealState.progress]）、方块同时被吃掉（[RevealState.seedExit]）。
 * 它们不是两段各有开始时刻的运动，而是同一次加速的两个侧面——所以方块在页面
 * 铺满的那一刻已经缩没了，交接处没有第二次动作。
 */
@Composable
private fun RevealDriver(
    state: RevealState,
    active: RevealState.Active,
) {
    // 时间线（时长 + 缓动）由方向与语义决定，分发抽成纯函数便于单测钉住，见 revealTiming。
    val timing = revealTiming(reverse = active.reverse, themeSwitch = active.toId != null)
    // 系统关掉动画（开发者选项的 Animator duration scale = 0，或无障的「移除动画」）时，
    // 这一屏擦除必须一步到位：整屏扩散叠在「不要动画」的设置上是最难受的一类。
    // 直接写 1f 而不是跑一个 0ms 的 tween：0ms 的 tween 仍会走一帧调度，
    // 而这一步的语义是「没有动画」，不该留下任何时间线。
    val animationsDisabled = rememberAnimationsDisabled()
    LaunchedEffect(active.runId) {
        RevealTrace.log { "driver start run=" + active.runId + " disabled=" + animationsDisabled }
        try {
            if (animationsDisabled) {
                state.progress.floatValue = 1f
                state.seedExit.floatValue = 1f
                return@LaunchedEffect
            }
            // 两条进度在**同一次 animate 里**推进：轮廓外扩（progress）与方块离场（seedExit）。
            //
            // 为什么不是两条各跑各的协程：那样两者只能靠时长凑齐，一旦系统动画缩放、掉帧
            // 或连点打断，两条时间线就会各自漂移，方块的缩小会与轮廓的铺满错开成两次动作。
            // 写成同一条动画的同一帧回调，它们**逐帧对齐**是构造出来的性质，不依赖调参。
            //
            // 只有正向页面扩散带方块副本；换主题、反向都不需要，seedExit 保持 0。
            val exits = !active.reverse && state.seed?.badge != null
            animate(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = tween(durationMillis = timing.durationMillis, easing = timing.easing),
            ) { value, _ ->
                // 连点定格期间停写：屏幕停在取图底的那一帧，重播才不会跳
                if (!state.frozen) {
                    state.progress.floatValue = value
                    if (exits) state.seedExit.floatValue = value
                    // 代数紧跟在两次赋值之后自增：诊断端据此判断这一帧有没有被刷新
                    state.writeSeq++
                }
            }
            RevealTrace.log { "driver done run=" + active.runId }
        } finally {
            // 采样收口必须写在 finally：`animationsDisabled` 分支是提前 return 的，
            // 收口若只跟着正常路径走，那一支会留下一个永不注销的 Choreographer 回调——
            // 实测泄漏过 3289 帧、54 秒的采样，而这一支恰恰是「不要动画」时最该零开销的路径。
            SeedFrameProbe.stop()
            // 被取消（切后台 / 离开组合 / 旋转 / 换新一段）时也要收尾，
            // 但只收自己这一轮：换新一段后旧协程被取消，不能把新的清掉
            state.finishIfRunning(active.runId)
        }
    }
}
