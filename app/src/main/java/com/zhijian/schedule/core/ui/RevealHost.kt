package com.zhijian.schedule.core.ui
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.withTimeoutOrNull

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
