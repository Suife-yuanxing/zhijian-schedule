package com.zhijian.schedule.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.LocalCourseColors

/**
 * 底部面板的**手势层**：把「容器在动」翻译成「内容在退」。
 *
 * # 为什么需要它
 *
 * M3 的 ModalBottomSheet 只有展开 / 隐藏两个锚点，拖拽时位移作用在**容器**上，
 * 内容跟着整体平移——观感是一整块板子被推走。而手势的语义应该是「把这份内容收起来」：
 * 手往下走，内容同时退后（缩放 + 淡出），松手无论落回展开还是消失，两个方向都是连续的。
 * 拖到四成时补一次轻震，「再拉就关了」在用户有意识之前就成立，不必试一次。
 *
 * # 覆盖三条退出路径
 *
 * 手指下滑、点遮罩、按返回键。后两条走的是同一个 hide 动画，容器偏移同样从停稳位
 * 增到隐藏位，所以同一份模型顺带兜住它们——不分别给三条路径写动效，
 * 那会得到三套略有出入的手感。这一条与 [SheetExitGate] 是两件事：那是「点编辑 / 删除
 * 之后宿主直接摘掉面板、不走 hide 动画」的那条路径，两者互不重叠。
 *
 * # 为什么进度只在绘制阶段读
 *
 * 偏移是每帧都在变的值。若在组合体里解包，一次下拉会让整棵内容树重组上百轮；
 * 这里把它读进 graphicsLayer，每帧只失效绘制。轻震提示是唯一需要副作用的地方，
 * 走 snapshotFlow 且带回滞，一轮拖拽最多响一次。
 *
 * # 什么时候不生效
 *
 * 面板**正在展开**时不退开（此时 M3 的 currentValue 仍是 Hidden）：入场那一段已经由
 * [SheetArrival] 编排，再叠一层「从 55% 淡入」会让两层透明度相乘，看得出两次起跑。
 * 只有面板已经落定、此后再往下的位移才算「退」。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberSheetRecede(sheetState: SheetState): SheetRecede {
    val reduced = rememberAnimationsDisabled()
    // key 带上 reduced：系统开关一改就换一个新实例，退开立刻停用 / 恢复。
    // 不这么做的话，用户先在设置里关掉动画、再回到这里下拉，内容还会继续退——
    // 那正是「我说了不要动画，它还在动」。
    val recede = remember(sheetState, reduced) { SheetRecede(sheetState, reduced) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(recede) {
        snapshotFlow { recede.progress }.collect { progress ->
            if (recede.consumeHint(progress)) {
                haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
            }
        }
    }
    // 每次落回展开态就重新记一次停稳位：面板高度会随内容 / 字体缩放变化，
    // 把停稳位钉死在第一次会算错行程，表现为「拉到底了内容还没退完」。
    //
    // 必须等一帧：M3 的锚点是在**布局阶段**写进拖拽状态的，currentValue 变 Expanded 的
    // 那一刻 requireOffset() 还读不到值（会抛它自己的断言）。在这一帧之后读，
    // 拿到的才是真正的停稳位；读早了会静默回落成 0，那之后的进度就全靠猜。
    LaunchedEffect(recede) {
        snapshotFlow { sheetState.currentValue }.collect { value ->
            if (value == SheetValue.Expanded) {
                withFrameNanos { }
                recede.captureRest()
            }
        }
    }
    return recede
}

/**
 * 下拉进度的持有者。
 *
 * [progress] 是**派生值**而不是自己维护的动画量：它唯一的来源是 [SheetState.requireOffset]，
 * 也就是 M3 自己的拖拽状态。自己再起一条动画去「跟随」会出现两套速度，
 * 一旦不同步就会看到内容在手指之外滑一下。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Stable
class SheetRecede internal constructor(
    private val sheetState: SheetState,
    /**
     * 系统是否关掉了动画。
     *
     * 关掉时 [progress] 恒为 0：下拉仍然由 M3 自己跟手（那是手势，不是装饰动效），
     * 但内容不再缩放淡出。退开属于纯装饰，无障碍开关对它有效。
     */
    private val reduced: Boolean,
) {
    /** 面板停稳时的偏移量（展开态锚点值）；0 表示还没落定过 */
    private var restPx by mutableFloatStateOf(0f)

    /** 面板自身高度：面板比父容器矮时，它就是「停稳位到隐藏位」的完整行程 */
    private var sheetPx by mutableFloatStateOf(0f)

    private var hinted by mutableStateOf(false)

    /** 当前下拉进度 0..1；读它会建立快照订阅，只许在绘制 / 副作用里读 */
    val progress: Float
        get() {
            if (reduced) return 0f
            if (sheetState.currentValue == SheetValue.Hidden) return 0f
            val offset =
                try {
                    sheetState.requireOffset()
                } catch (_: IllegalStateException) {
                    // 布局还没跑过时读偏移会抛（M3 自己的断言）。这一刻没有拖拽可言，
                    // 按「没在拖」处理，好过让整个面板在首帧崩掉
                    return 0f
                }
            // 停稳位还没落定（面板刚组合、或刚被摘掉重来）时一律按「没在拖」：
            // 此时 offset 是展开过程中的瞬时值，拿它当位移会把入场也读成「正在退」，
            // 表现是面板滑上来的最后几帧内容突然缩小变淡一下。
            if (restPx <= 0f) return 0f
            return SheetRecedeModel.progressOf(offset, restPx, sheetPx)
        }

    internal fun onSheetSized(heightPx: Int) {
        if (heightPx > 0) sheetPx = heightPx.toFloat()
    }

    internal fun captureRest() {
        restPx =
            try {
                sheetState.requireOffset()
            } catch (_: IllegalStateException) {
                0f
            }
    }

    /**
     * 该给一次轻震吗；顺带消费掉这次机会（一轮拖拽只响一次）。
     *
     * 只在**没有动画在跑**时提示：点遮罩 / 按返回也会让进度一路涨到 1，
     * 但那两条路径用户已经明确表达了「关掉」，再补一句「就要关了」是废话，
     * 而且会让人怀疑自己误触。M3 的拖拽本身不启动动画（松手之后才起），
     * 所以这条判据正好把「手在拉」与「系统在收」分开。
     */
    internal fun consumeHint(progress: Float): Boolean {
        if (hinted && SheetRecedeModel.hintShouldReset(progress)) hinted = false
        if (sheetState.isAnimationRunning) return false
        if (!SheetRecedeModel.shouldHint(progress, hinted)) return false
        hinted = true
        return true
    }
}

/**
 * 把退开量接到内容图层上。
 *
 * 缩放原点压在**顶边中点**：内容往下收时上沿要贴住拖柄，从中心缩会变成「一小块往中间聚」，
 * 那读作内容自己塌了，而不是被推走。
 */
fun Modifier.sheetRecede(recede: SheetRecede): Modifier =
    graphicsLayer {
        val spec = SheetRecedeModel.specAt(recede.progress)
        scaleX = spec.scale
        scaleY = spec.scale
        alpha = spec.alpha
        transformOrigin = TransformOrigin(SheetRecedeModel.ORIGIN_X, SheetRecedeModel.ORIGIN_Y)
    }

/**
 * 自绘拖柄。
 *
 * 存在理由不是好看：M3 默认把手是一条静态横杠，看不出「已经抓住、正在拉、再拉就关」。
 * 这里让它随下拉进度**变长、加深**——比变粗更容易读（宽度变化落在扫视路径上），
 * 也比加一圈光晕省一层绘制。
 *
 * 变长用 scaleX 而不是改宽度：改宽度要走一次测量，而下拉是每帧都在发生的事；
 * 横向拉伸对一枚 RoundedCornerShape(50) 的胶囊来说只是把它拉长，圆头形状不变。
 * 占位高度与 M3 默认把手一致（[SheetRecedeModel.HANDLE_SLOT_DP]），替换后不顶高面板。
 */
@Composable
fun SheetDragHandle(
    recede: SheetRecede,
    modifier: Modifier = Modifier,
    /**
     * 拖柄颜色。课程详情传课程色（把课块那一块颜色带到面板上），其余面板留空用中性色。
     *
     * 这是「从课块到面板」唯一不靠动画的连续性手段：面板是独立窗口，真正的容器变换
     * （课块长成面板）在 Compose 里做不了；把同一个色相放在面板最上沿，
     * 眼睛就会把两块读成同一个东西的两端。
     */
    tint: Color? = null,
) {
    val colors = LocalCourseColors.current
    Box(
        modifier.fillMaxWidth().height(SheetRecedeModel.HANDLE_SLOT_DP.dp),
        contentAlignment = Alignment.Center,
    ) {
        Spacer(
            Modifier
                .width(SheetRecedeModel.HANDLE_MIN_DP.dp)
                .height(SheetRecedeModel.HANDLE_HEIGHT_DP.dp)
                .graphicsLayer {
                    val p = recede.progress
                    scaleX = SheetRecedeModel.handleWidthDp(p) / SheetRecedeModel.HANDLE_MIN_DP
                    alpha = SheetRecedeModel.handleAlpha(p)
                }.clip(RoundedCornerShape(SheetRecedeModel.HANDLE_CORNER))
                .background(tint ?: colors.textMuted),
        )
    }
}

/**
 * 面板本体：把自绘拖柄与下拉退开一次接好。
 *
 * 为什么封装成一个 composable 而不是一个 Modifier：拖柄要替换 M3 的 dragHandle 槽，
 * 尺寸回调要挂在 ModalBottomSheet 的 modifier 上，退开要包住 content——这三处分布在
 * 不同参数上，各自单独调用会在每个面板里复制一遍接线，也容易漏掉其中一处。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecedingBottomSheet(
    onDismissRequest: () -> Unit,
    sheetState: SheetState,
    modifier: Modifier = Modifier,
    /** 拖柄色（课程详情传课程色，见 [SheetDragHandle]） */
    handleTint: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val recede = rememberSheetRecede(sheetState)
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        modifier = modifier.onSizeChanged { recede.onSheetSized(it.height) },
        dragHandle = { SheetDragHandle(recede, tint = handleTint) },
    ) {
        // 用 Column 而不是 Box 承接 content：面板的 content 是 ColumnScope 的扩展函数
        // （列内子项会用到 weight / align），换成 Box 就把它丢了。
        // 这一层是唯一挂 sheetRecede 的地方——它必须包住全部内容，
        // 只挂在某一块上会变成「那一块自己在缩」。
        Column(Modifier.fillMaxWidth().sheetRecede(recede)) {
            content()
        }
    }
}
