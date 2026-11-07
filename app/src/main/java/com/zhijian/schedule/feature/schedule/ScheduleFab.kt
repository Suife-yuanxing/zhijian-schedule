package com.zhijian.schedule.feature.schedule

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.CourseColors
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.theme.MotionProfile
import com.zhijian.schedule.core.ui.FabMorph
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.SeedBadge
import com.zhijian.schedule.core.ui.SeedShape
import com.zhijian.schedule.core.ui.buttonFeedback
import com.zhijian.schedule.core.ui.metal.LocalMetalClock
import com.zhijian.schedule.core.ui.metal.MetalTouchState
import com.zhijian.schedule.core.ui.metal.metalPointer
import com.zhijian.schedule.core.ui.metal.metalSurface
import com.zhijian.schedule.core.ui.metal.rememberMetalTouchState
import com.zhijian.schedule.core.ui.rememberAnimationsDisabled
import com.zhijian.schedule.navigation.IslandContentPadding
import kotlinx.coroutines.launch

/** 方块边长：常规主题 48dp、金属键帽 56dp（两者的角半径不同，见 [FabMetrics]） */
private const val FAB_SIZE_DP = 48
private const val FAB_SIZE_METAL_DP = 56

/** 常规主题 FAB 的角半径：M3 FloatingActionButton 的默认 shape 是 16dp 圆角方块 */
private const val FAB_CORNER_DP = 16f

/**
 * 加号按钮的实测尺寸与角半径——页面扩散的「种子」就是按这三个值算出来的。
 *
 * 为什么角半径要单独测而不是一律取边长一半：需求要求「页面沿着整个加号方块展开，精准测量
 * 边缘」。方块是圆角矩形，角半径直接决定起手那几帧轮廓的四角是方的还是圆的；把它当成
 * 「圆形按钮」处理，扩散第一帧就会比方块多出四个角——那正是要修的观感。
 */
private class FabMetrics(
    val sizePx: Float,
    val cornerPx: Float,
) {
    companion object {
        fun of(
            metal: Boolean,
            density: Float,
        ): FabMetrics {
            val size = (if (metal) FAB_SIZE_METAL_DP else FAB_SIZE_DP) * density
            // 金属键帽是 RoundedCornerShape(percent = 50) = 正圆；常规是 16dp 圆角方块
            val corner = if (metal) size / 2f else FAB_CORNER_DP * density
            return FabMetrics(sizePx = size, cornerPx = corner)
        }
    }
}

/**
 * 课表页的加号按钮：本次进程第一次进入课表时弹一次，并把方块自身的实测几何交给调用方，
 * 供「页面从方块轮廓长出来、铺满后方块缩小离场」用。
 *
 * ## 形变与扩散现在同时起跑
 *
 * 旧实现里加号要先转 45° 变 ×、等 150ms 才放扩散过去，因为扩散的第 0 帧圆心就是加号中心、
 * 起始半径就是加号半径——**圆内第一帧已经是新页面**，不先转就永远看不到那个 ×。
 *
 * 现在方块由扩散浮层按实测几何重画一份盖在裁剪层**之上**（见 `SeedOverlay`），全程可见：
 * 没有任何东西会盖掉它，所以那 150ms 的串行等待被删掉了，形变与页面展开同时开始，
 * 一次点击只等一次动画。
 *
 * ## 转回来
 *
 * 从编辑页返回时，按钮要重新是加号。这里不复位成「重新长出来」：用户离开屏幕时它已经是 ×，
 * 回来时看到的应该是**它自己转回去**，而不是一个全新的加号跳出来。因此返回后按
 * [FabMorph.progressOf] 接住当前角度继续转，而不是一律从 45° 起。
 */
@Composable
internal fun ScheduleFab(
    playEntrance: Boolean,
    onAddCourseFromFab: (SeedShape) -> Unit,
    /** 从编辑页回来了：把 × 转回 +（由宿主在目的地回到课表时自增触发） */
    resetTick: Int = 0,
) {
    val colors = LocalCourseColors.current
    val metal = LocalCoursePreset.current.metal
    val metalClock = LocalMetalClock.current
    val metalTouch = rememberMetalTouchState()
    val fabSource = remember { MutableInteractionSource() }
    val density = LocalDensity.current.density
    val metrics = remember(metal, density) { FabMetrics.of(metal = metal != null, density = density) }
    // 方块在 Compose 根坐标系里的位置：编辑页从这个轮廓里长出来
    var fabBounds by remember { mutableStateOf(Rect.Zero) }

    // 与课块同一拍：只有本次进程第一次进入课表才弹。用 Animatable 而不是 animateFloatAsState，
    // 是为了新的回合能瞬间回 0（拉目标值会先播一段收缩）。
    // 不播时 snapshot 到 1f 落位——加号是常驻控件，切页时它跟着页面一起滑就行，
    // 再叠一次「缩回 0 再长出来」只会强化「整页刷新」的观感。
    val fabScale = remember { Animatable(if (playEntrance) 0f else 1f) }
    LaunchedEffect(playEntrance) {
        if (playEntrance) {
            fabScale.snapTo(0f)
            fabScale.animateTo(1f, Motion.PopSpring)
        } else {
            fabScale.snapTo(1f)
        }
    }

    // 系统关掉动画时，形变没有意义：直接把 × 摆上去、立刻放扩散过去。
    //
    // 素墨同一档处理，但理由不同：它规范里写死「不做旋转、不做缩放」。那条约束是**主题身份**，
    // 不是无障碍降级——所以这里跳过的只是装饰，扩散照常播（空间语义不能因皮肤而变），
    // 方块改成原地淡出离场（见 Motion.SeedFade）。
    val animationsDisabled = rememberAnimationsDisabled()
    val morphAllowed = LocalCoursePreset.current.motion == MotionProfile.Lively && !animationsDisabled
    val badge =
        SeedBadge(
            fill = colors.accent,
            tint = colors.onAccent,
            // 金属键帽的球冠高光：非金属主题不画，方块就是一块纯色
            highlight = if (metal != null) Color.White.copy(alpha = 0.18f) else null,
        )
    val morphState =
        rememberFabMorphState(
            resetTick = resetTick,
            morphAllowed = morphAllowed,
            onLaunch = {
                val bounds = fabBounds
                onAddCourseFromFab(
                    SeedShape(
                        bounds = bounds,
                        cornerRadius = metrics.cornerPx,
                        badge = badge,
                    ),
                )
            },
        )
    val launch = morphState.launch

    val skin =
        FabSkin(
            colors = colors,
            touch = metalTouch,
            clock = metalClock,
            source = fabSource,
            metal = metal != null,
            metrics = metrics,
        )
    val motion = FabMotion(scale = fabScale, progress = morphState.progress, morphed = morphState.morphed)
    Box(Modifier.fillMaxSize()) {
        if (metal != null) {
            FabSurface(skin, motion, onClick = launch, onBounds = { fabBounds = it })
        } else {
            FabPlain(skin, motion, onClick = launch, onBounds = { fabBounds = it })
        }
    }
}

/**
 * 加号形变 + 点击编排的状态包。
 *
 * 从 [ScheduleFab] 里抽出来是为了让那个「长命」composable 只管布局：
 * 形变与连点闸门是两条各自有判定逻辑的时间线，混在布局代码里既读不懂也测不到。
 */
private class FabMorphState(
    val progress: Animatable<Float, AnimationVector1D>,
    val morphed: Boolean,
    val launch: () -> Unit,
)

/**
 * 按钮外观所需的一切（主题色、材质、交互源、实测几何）。
 *
 * 打成一个包是为了让 [FabSurface] / [FabPlain] 的参数表停在一行读得完的长度：
 * 底下那几个值彼此没有逻辑关系，只是恰好都要用。
 */
private class FabSkin(
    val colors: CourseColors,
    val touch: MetalTouchState,
    val clock: State<Float>?,
    val source: MutableInteractionSource,
    val metal: Boolean,
    val metrics: FabMetrics,
)

/** 形变相关的两个动画值（入场缩放 + 转角进度）与「现在读作叉了吗」 */
private class FabMotion(
    val scale: Animatable<Float, AnimationVector1D>,
    val progress: Animatable<Float, AnimationVector1D>,
    val morphed: Boolean,
)

@Composable
private fun rememberFabMorphState(
    resetTick: Int,
    morphAllowed: Boolean,
    onLaunch: () -> Unit,
): FabMorphState {
    val scope = rememberCoroutineScope()
    // 形变进度 0..1（0 = 加号，1 = 叉号）。用 Animatable 而不是 animateFloatAsState：
    // 要能在连点时瞬间 snap 回某个值而不播过渡。
    val morph = remember { Animatable(0f) }
    // 一次点击只认一次：形变还没转完时再点不能把扩散排两遍
    var launching by remember { mutableStateOf(false) }
    // 「现在读作叉号了吗」只用来切换无障碍描述。走 derivedStateOf 而不是直接读 morph.value：
    // 直接读会让整个按钮在形变的 170ms 里每帧重组一次，而它只在越过中点时才有意义。
    val morphed by remember { derivedStateOf { morph.value > FabMorph.READ_AS_CROSS_AT } }

    // 返回课表：× 转回 +。不 snap：用户看得见它转回去，才知道「这里还能再点」。
    // 也不从 45° 硬起：接住当前角度（动画被打断时它可能停在半路）。
    LaunchedEffect(resetTick) {
        if (resetTick == 0) return@LaunchedEffect
        launching = false
        if (morph.value <= FabMorph.SETTLED_EPSILON) return@LaunchedEffect
        if (!morphAllowed) {
            morph.snapTo(0f)
            return@LaunchedEffect
        }
        morph.animateTo(
            targetValue = 0f,
            animationSpec = tween(durationMillis = FabMorph.DURATION_MILLIS, easing = FabMorph.EASING),
        )
    }

    val launch: () -> Unit = {
        if (!launching) {
            launching = true
            if (morphAllowed) {
                scope.launch {
                    morph.animateTo(
                        targetValue = 1f,
                        animationSpec =
                            tween(durationMillis = FabMorph.DURATION_MILLIS, easing = FabMorph.EASING),
                    )
                }
            } else {
                // 不形变：一路是加号。用户看不到 ×，也就不需要在返回时转回来。
                scope.launch { morph.snapTo(1f) }
            }
            // 形变**不阻塞**扩散：方块已经浮在裁剪层之上（见 RevealHost.SeedOverlay），
            // 转与不转、转到哪一帧，页面都从第 0 帧开始张开。
            onLaunch()
        }
    }
    return FabMorphState(morph, morphed, launch)
}

/**
 * 金属主题的加号：一枚悬在页面上的水银键帽——球冠高光 + 按压凹陷 + 拉丝。
 *
 * 拆成独立 composable 而不是与普通主题挤在同一个 if/else 里：两条分支的修饰符链
 * 完全不同（金属要走 cell 材质管线），挤在一起时父函数会长到 135 行。
 */
@Composable
private fun BoxScope.FabSurface(
    skin: FabSkin,
    motion: FabMotion,
    onClick: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    Box(
        Modifier
            .align(Alignment.BottomEnd)
            // 手势条高度先补上，再加浮岛让位：按钮浮在手势条正上方、岛之上
            .navigationBarsPadding()
            .padding(end = 20.dp, bottom = IslandContentPadding)
            .size(FAB_SIZE_METAL_DP.dp)
            .onGloballyPositioned { onBounds(it.boundsInRoot()) }
            .graphicsLayer {
                val s = motion.scale.value
                scaleX = s
                scaleY = s
                alpha = s.coerceIn(0f, 1f)
            }.shadow(
                elevation = 12.dp,
                shape = CircleShape,
                ambientColor = Color.Black.copy(alpha = 0.30f),
                spotColor = Color.Black.copy(alpha = 0.50f),
            ).metalSurface(
                shape = RoundedCornerShape(percent = 50),
                touch = skin.touch,
                clock = skin.clock,
                chromeOverride = 0.95f,
                faceOverride = skin.colors.accent,
            ).metalPointer(skin.touch)
            .buttonFeedback(skin.source, pressedScale = 0.92f)
            .clickable(
                interactionSource = skin.source,
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        FabGlyph(motion = motion, tint = skin.colors.onAccent)
    }
}

/** 常规主题的加号：Material FAB，48dp 圆角方块 */
@Composable
private fun BoxScope.FabPlain(
    skin: FabSkin,
    motion: FabMotion,
    onClick: () -> Unit,
    onBounds: (Rect) -> Unit,
) {
    FloatingActionButton(
        onClick = onClick,
        interactionSource = skin.source,
        modifier =
            Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 20.dp, bottom = IslandContentPadding)
                .size(FAB_SIZE_DP.dp)
                .onGloballyPositioned { onBounds(it.boundsInRoot()) }
                .buttonFeedback(skin.source, pressedScale = 0.9f)
                .graphicsLayer {
                    val s = motion.scale.value
                    scaleX = s
                    scaleY = s
                    alpha = s.coerceIn(0f, 1f)
                },
        containerColor = skin.colors.accent,
        contentColor = skin.colors.onAccent,
    ) {
        FabGlyph(motion = motion)
    }
}

/**
 * 那两条笔画。
 *
 * 转角挂在**图标层**而不是按钮层：按钮本体的圆角、阴影、金属高光都不跟着转——
 * 转的只有加号自己，这才是「加号变成 ×」而不是「整个按钮被拧了一下」。
 * 金属主题下这条差别尤其明显：键帽一转会带着球冠高光一起歪，读作故障。
 */
@Composable
private fun FabGlyph(
    motion: FabMotion,
    tint: Color = Color.Unspecified,
) {
    Icon(
        Icons.Filled.Add,
        // 描述跟着形态走：转成 × 之后它不再是「添加课程」，而是「一个已经按下去的加号」。
        // 这里不写「关闭」——它确实不会关闭任何东西（点的下一步就是跳页），
        // 写「关闭」等于向读屏用户承诺一个不存在的动作。
        contentDescription = if (motion.morphed) "正在打开添加课程" else "添加课程",
        tint = tint,
        modifier =
            Modifier.graphicsLayer {
                rotationZ = FabMorph.rotationDegrees(motion.progress.value)
            },
    )
}
