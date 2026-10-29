package com.zhijian.schedule.core.ui.glass

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.LocalCoursePreset

/** 承载 RuntimeShader 的 Brush：draw 阶段更新 uniform，复用同一 shader 实例 */
internal class SolidShaderBrush(
    private val shader: Shader,
) : ShaderBrush() {
    override fun createShader(size: Size): Shader = shader
}

/**
 * 清水玻璃材质（玻璃主题专用，调用点先判 preset.glass != null 再调用）：
 * - A13+ / 非低端机：AGSL 高透明主体 + 两条错速斜向流动光带 + 呼吸上沿高光 / 下沿液面波纹；
 * - 旧机：渐变光影档（半透明底 + 滑动光带 + 对角高光描边），流动同样由时钟驱动。
 * 零模糊、零截屏捕获。
 */
@Composable
fun Modifier.glassSurface(
    shape: RoundedCornerShape,
    tint: Color,
    bodyAlpha: Float,
    clock: State<Float>?,
    tilt: State<Offset>?,
): Modifier {
    val spec = LocalCoursePreset.current.glass ?: return this
    val context = LocalContext.current
    val supported = remember(context) { GlassRuntime.supported(context) }
    val density = LocalDensity.current
    val bandPx = with(density) { 10.dp.toPx() }

    if (supported && clock != null) {
        val shader = remember(spec) { GlassRuntime.waterGlassShader(spec) }
        val brush = remember(shader) { SolidShaderBrush(shader) }
        return this.drawBehind {
            val r = shape.radiusPx(size, this)
            GlassRuntime.updateWater(
                shader = shader,
                widthPx = size.width,
                heightPx = size.height,
                radiusPx = r,
                bandPx = bandPx,
                time = clock.value,
                tint = tint,
                bodyAlpha = bodyAlpha,
                tilt = tilt?.value ?: Offset.Zero,
            )
            drawRoundRect(brush = brush, cornerRadius = CornerRadius(r, r))
        }
    }

    return this.drawWithCache {
        val r = shape.radiusPx(size, this)
        val cr = CornerRadius(r, r)
        val pillPath =
            Path().apply {
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, cr, cr))
            }
        val rimBrush =
            Brush.linearGradient(
                colors =
                    listOf(
                        Color.White.copy(alpha = 0.40f),
                        Color.White.copy(alpha = 0.06f),
                        Color.White.copy(alpha = 0.18f),
                    ),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height),
            )
        val strokePx = 1.2.dp.toPx()
        onDrawBehind {
            val t = clock?.value ?: 0f
            val w = size.width
            val h = size.height
            drawRoundRect(color = tint.copy(alpha = bodyAlpha), cornerRadius = cr)
            clipPath(pillPath) {
                // 厚度带：水色加深的缓带（浅色底上主要靠它看见流动）
                val thickW = w * 0.42f
                val xt = ((t * 0.055f) % 1.4f - 0.2f) * w
                drawRect(
                    brush =
                        Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, tint.copy(alpha = bodyAlpha * 0.85f), Color.Transparent),
                            startX = xt,
                            endX = xt + thickW,
                        ),
                    topLeft = Offset(xt, 0f),
                    size = Size(thickW, h),
                )
                // 白色反光带 ×2：与厚度带错速对向
                val bandW1 = w * 0.30f
                val x1 = (1.15f - (t * 0.16f) % 1.4f) * w
                drawRect(
                    brush =
                        Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.12f), Color.Transparent),
                            startX = x1,
                            endX = x1 + bandW1,
                        ),
                    topLeft = Offset(x1, 0f),
                    size = Size(bandW1, h),
                )
                val bandW2 = w * 0.22f
                val x2 = ((t * 0.115f) % 1.4f - 0.2f) * w
                drawRect(
                    brush =
                        Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.09f), Color.Transparent),
                            startX = x2,
                            endX = x2 + bandW2,
                        ),
                    topLeft = Offset(x2, 0f),
                    size = Size(bandW2, h),
                )
                // 倾角高光：宽柔光泽 + 亮点，随重力方向滑动（与 shader 档同源）
                val tiltValue = tilt?.value ?: Offset.Zero
                val highlight = Offset((0.5f - tiltValue.x * 0.34f) * w, (0.26f - tiltValue.y * 0.20f) * h)
                drawCircle(
                    brush =
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.10f), Color.Transparent),
                            center = highlight,
                            radius = h,
                        ),
                    radius = h,
                    center = highlight,
                )
                drawCircle(
                    brush =
                        Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = 0.14f), Color.Transparent),
                            center = highlight,
                            radius = h * 0.34f,
                        ),
                    radius = h * 0.34f,
                    center = highlight,
                )
            }
            drawRoundRect(
                brush = rimBrush,
                cornerRadius = cr,
                style = Stroke(width = strokePx),
            )
        }
    }
}

/**
 * 折射浮岛（玻璃主题 shader 档专用）：采样背景图层 [layer] 并按边缘透镜位移「膨胀」后铺进浮岛，
 * 水膜（流动光带/厚度波/边缘光影）作为独立笔刷叠加在折射结果上，输出不透明覆盖——真实实时折射、零截屏。
 * [originInLayer] = 浮岛左上角在图层坐标系中的位置（px，绘制期读取）。
 * shader 档不可用时退回 [glassSurface]（零折射光影档）。
 */
@Composable
fun Modifier.glassBackdropSurface(
    shape: RoundedCornerShape,
    tint: Color,
    bodyAlpha: Float,
    clock: State<Float>?,
    tilt: State<Offset>?,
    backdrop: GlassBackdropState,
    originInLayer: () -> Offset,
): Modifier {
    val spec = LocalCoursePreset.current.glass ?: return this
    val context = LocalContext.current
    val supported = remember(context) { GlassRuntime.supported(context) }
    if (!supported || clock == null) {
        return this.glassSurface(
            shape = shape,
            tint = tint,
            bodyAlpha = bodyAlpha,
            clock = clock,
            tilt = tilt,
        )
    }
    // shader 与图层同生命周期：RuntimeShader 的图层采样绑定会固化在首个 effect 上，
    // 复用旧 shader 挂到新图层会采样旧节点，因此随图层一起重建
    val lensShader = remember(backdrop.layer, spec) { GlassRuntime.lensShader(spec) }
    // 水膜走画笔路径（uniform 逐帧更新才生效；effect 内会随挂载取快照）
    val filmShader = remember(spec) { GlassRuntime.waterGlassShader(spec) }
    val filmBrush = remember(filmShader) { SolidShaderBrush(filmShader) }
    val bandPx = with(LocalDensity.current) { 10.dp.toPx() }
    return this.drawBehind {
        val r = shape.radiusPx(size, this)
        val origin = originInLayer()
        // 惰性挂载：本绘制在内容录制之后执行，确保 effect 绑定时图层已有内容；
        // 折射静态参数（原点/尺寸/半径）必须在挂载前写齐——effect 对 uniform 取快照，之后写入不生效
        val signature = LensSignature(origin.x, origin.y, size.width, size.height, r)
        if (signature != backdrop.lensAttachSignature) {
            GlassRuntime.updateLens(
                shader = lensShader,
                originX = origin.x,
                originY = origin.y,
                widthPx = size.width,
                heightPx = size.height,
                radiusPx = r,
                lensFactor = spec.lens,
            )
            GlassRuntime.attachLensEffect(backdrop.layer, lensShader)
            backdrop.lensAttachSignature = signature
        }
        val pill =
            Path().apply {
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(r, r), CornerRadius(r, r)))
            }
        clipPath(pill) {
            translate(left = -origin.x, top = -origin.y) { drawLayer(backdrop.layer) }
        }
        GlassRuntime.updateWater(
            shader = filmShader,
            widthPx = size.width,
            heightPx = size.height,
            radiusPx = r,
            bandPx = bandPx,
            time = clock.value,
            tint = tint,
            bodyAlpha = bodyAlpha,
            tilt = tilt?.value ?: Offset.Zero,
        )
        drawRoundRect(brush = filmBrush, cornerRadius = CornerRadius(r, r))
    }
}

/** 折射 effect 的挂载签名：这些静态参数变化时需要重新挂载（uniform 在挂载前写入） */
private data class LensSignature(
    val originX: Float,
    val originY: Float,
    val width: Float,
    val height: Float,
    val radius: Float,
)

/**
 * 选中液滴材质（玻璃主题专用）：饱和水色珠 —— 内部缓厚带色深流动 / 细光斑 / 上强下柔精光边 / 弱底部焦散。
 * - A13+ / 非低端机：AGSL 液滴着色器（时间驱动内部流动）；
 * - 旧机：渐变光影档（水色底 + 流动厚带与光斑 + 顶部泛光 + 上亮下柔描边）。
 */
@Composable
fun Modifier.glassDroplet(
    shape: RoundedCornerShape,
    tint: Color,
    alpha: Float,
    clock: State<Float>?,
): Modifier {
    val spec = LocalCoursePreset.current.glass ?: return this
    val context = LocalContext.current
    val supported = remember(context) { GlassRuntime.supported(context) }

    if (supported && clock != null) {
        val shader = remember(spec) { GlassRuntime.dropletShader(spec) }
        val brush = remember(shader) { SolidShaderBrush(shader) }
        return this.drawBehind {
            val r = shape.radiusPx(size, this)
            GlassRuntime.updateDroplet(
                shader = shader,
                widthPx = size.width,
                heightPx = size.height,
                radiusPx = r,
                time = clock.value,
                tint = tint,
                alpha = alpha,
            )
            drawRoundRect(brush = brush, cornerRadius = CornerRadius(r, r))
        }
    }

    return this.drawWithCache {
        val r = shape.radiusPx(size, this)
        val cr = CornerRadius(r, r)
        val pillPath =
            Path().apply {
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, cr, cr))
            }
        val topGlowBrush =
            Brush.verticalGradient(
                colors = listOf(Color.White.copy(alpha = 0.15f), Color.Transparent),
                startY = 0f,
                endY = size.height * 0.5f,
            )
        val edgeBrush =
            Brush.verticalGradient(
                colors = listOf(Color.White.copy(alpha = 0.62f), Color.White.copy(alpha = 0.26f)),
                startY = 0f,
                endY = size.height,
            )
        val strokePx = 1.2.dp.toPx()
        onDrawBehind {
            val t = clock?.value ?: 0f
            val w = size.width
            val h = size.height
            drawRoundRect(color = tint.copy(alpha = alpha), cornerRadius = cr)
            clipPath(pillPath) {
                // 缓厚带：色深涨落（水在珠内流动）
                val thickW = w * 0.66f
                val xt = ((t * 0.055f) % 1.5f - 0.25f) * w
                drawRect(
                    brush =
                        Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, tint.copy(alpha = alpha * 0.55f), Color.Transparent),
                            startX = xt,
                            endX = xt + thickW,
                        ),
                    topLeft = Offset(xt, 0f),
                    size = Size(thickW, h),
                )
                // 细光斑（对向、更快）
                val bandW = w * 0.24f
                val xs = (1.2f - (t * 0.16f) % 1.5f) * w
                drawRect(
                    brush =
                        Brush.horizontalGradient(
                            colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.14f), Color.Transparent),
                            startX = xs,
                            endX = xs + bandW,
                        ),
                    topLeft = Offset(xs, 0f),
                    size = Size(bandW, h),
                )
                drawRect(brush = topGlowBrush)
            }
            drawRoundRect(brush = edgeBrush, cornerRadius = cr, style = Stroke(width = strokePx))
        }
    }
}

private fun RoundedCornerShape.radiusPx(
    size: Size,
    density: Density,
): Float = topStart.toPx(size, density)
