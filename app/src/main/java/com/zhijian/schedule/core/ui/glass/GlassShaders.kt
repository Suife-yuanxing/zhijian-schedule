package com.zhijian.schedule.core.ui.glass

/**
 * 液态玻璃 AGSL 源码（零模糊、零截屏捕获）。
 * 约定：全部 float 运算；仅 main 返回 half4（四参构造）。
 * 流动的可感来源 =「厚度波」：水膜忽厚忽薄，色深随之缓缓涨落（浅色底上主要靠它看见流动），
 * 叠加三条错速斜向光带的白色反光；浮岛水体保持可辨的钢蓝水色（与页面底色拉开层次）。
 * 三个材质：
 * - 浮岛水体：钢蓝水膜 + 厚度波 + 三条光带 + 倾角镜面高光 + 1px 精光边（无折射，兜底）；
 *   浮岛在折射档下同样由它作「水膜笔刷」叠加在折射结果上——effect 内的 uniform 会随挂载取快照，
 *   动画（含倾角高光）只能走画笔路径，故水膜不放进折射着色器；
 * - 折射浮岛（lens）：边缘透镜位移 + 外缘镜像反射 + 线性光回编 sRGB（效果输入为线性光、输出不回编），
 *   全部静态 uniform，必须在挂载 effect 前写入；
 * - 选中液滴：近实心水色珠 + 内部缓厚带（色深流动）+ 细光斑 + 上强下柔精光边；
 *   ——真实实时折射（滚动同步）、输出不透明覆盖；水膜运算与浮岛水体同一套。
 */
object GlassShaders {
    private const val SD_ROUND_RECT = """float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}"""

    /**
     * 水膜核心：流动光带 / 厚度波 / 边缘光影 / 倾角高光。
     * 前置局部变量：inside（SDF 内距）、n（边缘法线）、uSize/uRadius/uBand/uTime/uSpec/uFlow/uTilt/uTiltSpec。
     * 产出局部变量：hiA（白色反光 alpha）、bodyA（着色主体 alpha）。[c] 为浮岛局部坐标表达式。
     */
    private fun waterFilmOps(c: String): String =
        """
    float x = $c.x / uSize.x;
    float y = $c.y / uSize.y;

    // 流动光带：三条错速斜向带，fract 循环无缝；纵向轻摆像水面缓流
    float wob = 0.02 * sin(y * 6.0 + uTime * 0.5);
    float flowX = x + (y - 0.5) * 0.30 + wob;
    float w1 = fract(flowX - uTime * 0.16);
    float w2 = fract(-flowX + uTime * 0.115 + 0.37);
    float w3 = fract(flowX * 0.6 - uTime * 0.055 + 0.71);
    float s1 = (w1 - 0.5) * 6.0;
    float s2 = (w2 - 0.5) * 8.0;
    float s3 = (w3 - 0.5) * 3.4;
    float g1 = exp(-s1 * s1);
    float g2 = exp(-s2 * s2);
    float g3 = exp(-s3 * s3);
    float flowHi = (g1 * 0.42 + g2 * 0.30 + g3 * 0.18) * uFlow;

    // 厚度波：水膜忽厚忽薄的缓带（缓带为主、快带跟随）——浅色底上流动的主要可感来源
    float thick = (g3 * 0.65 + g1 * 0.30) * uFlow;

    // 倾角高光：像玻璃在固定光源下转动——宽柔光泽 + 亮点，随重力方向滑动（滑向抬起一侧）
    float2 hl = float2(0.5 - uTilt.x * 0.34, 0.26 - uTilt.y * 0.20);
    float2 dSheen = float2((x - hl.x) * 1.7, (y - hl.y) * 1.15);
    float sheen = exp(-dot(dSheen, dSheen) * 2.4);
    float2 dSpot = float2((x - hl.x) * 3.4, (y - hl.y) * 2.6);
    float spot = exp(-dot(dSpot, dSpot) * 2.0);
    float tiltHi = (sheen * 0.16 + spot * 0.20) * uTiltSpec;

    // 边缘：2.5px 精光边定义轮廓 + 柔光带铺体积；上沿最亮（呼吸），下沿带液面波纹
    float thin = 1.0 - smoothstep(0.0, 2.5, inside);
    float soft = 1.0 - clamp(inside / uBand, 0.0, 1.0);
    soft = soft * soft;
    float up = clamp(-n.y, 0.0, 1.0);
    float down = clamp(n.y, 0.0, 1.0);
    float breathe = 0.88 + 0.12 * sin(uTime * 0.4);
    float wave = 1.0 + 0.25 * sin(x * 22.0 + uTime * 0.9);
    float rimTop = (thin * 0.55 + soft * 0.45) * up * up * uSpec * breathe;
    float rimBot = (thin * 0.30 + soft * 0.20) * down * uSpec * wave;

    float hiA = clamp(flowHi + rimTop + rimBot + tiltHi, 0.0, 1.0);
    float bodyA = uBodyAlpha * mix(0.90, 1.10, y) * (1.0 + 0.85 * thick);
"""

    fun buildWaterGlassSource(): String =
        """
uniform float2 uSize;
uniform float uRadius;
uniform float uBand;
uniform float uTime;
uniform float3 uTint;
uniform float uBodyAlpha;
uniform float uSpec;
uniform float uFlow;
uniform float2 uTilt;
uniform float uTiltSpec;

$SD_ROUND_RECT

half4 main(float2 fragCoord) {
    float2 halfSz = uSize * 0.5;
    float2 q = fragCoord - halfSz;
    float d = sdRoundRect(q, halfSz, uRadius);
    float inside = -d;
    float2 n = normalize(float2(
        sdRoundRect(q + float2(0.75, 0.0), halfSz, uRadius) - d,
        sdRoundRect(q + float2(0.0, 0.75), halfSz, uRadius) - d) + float2(0.0001, 0.0001));

${waterFilmOps("fragCoord")}
    float outA = hiA + bodyA * (1.0 - hiA);
    float3 premul = float3(1.0, 1.0, 1.0) * hiA + uTint * (bodyA * (1.0 - hiA));
    return half4(premul.r, premul.g, premul.b, outA);
}
        """.trimIndent()

    fun buildLensGlassSource(): String =
        """
uniform shader uContent;
uniform float2 uOrigin;
uniform float2 uSize;
uniform float uRadius;
uniform float uLens;
uniform float uMirror;
uniform float uMirrorLen;

$SD_ROUND_RECT

float3 lin2srgb(float3 c) {
    float3 lo = c * 12.92;
    float3 hi = 1.055 * pow(max(c, float3(0.0031308)), float3(1.0 / 2.4)) - 0.055;
    return mix(lo, hi, step(float3(0.0031308), c));
}

half4 main(float2 fragCoord) {
    float2 lc = fragCoord - uOrigin;
    float2 halfSz = uSize * 0.5;
    float2 q = lc - halfSz;
    float d = sdRoundRect(q, halfSz, uRadius);
    if (d > 2.0) return uContent.eval(fragCoord);
    float inside = -d;

    // 边缘透镜：贴边处向心采样（放大）→ 背景沿边「膨胀」弯折
    float lensBand = uRadius * 0.5;
    float lt = clamp(inside / lensBand, 0.0, 1.0);
    float bulge = 1.0 - lt;
    bulge = bulge * bulge;
    float2 dir = normalize(q + float2(0.0001, 0.0001));
    float3 base = uContent.eval(fragCoord - dir * bulge * uLens).rgb;

    // 边缘镜像反射：外缘约 2/3 半径的宽带（掠射权重三次方、贴边最强），
    // 沿外法线向外两段采样取平均——像玻璃唇边把周围余光「收」进反光，带一点拉丝柔化
    float grazing = 1.0 - clamp(inside / max(uRadius * 0.8, 16.0), 0.0, 1.0);
    grazing = grazing * grazing * grazing;
    if (grazing > 0.01) {
        float2 n = normalize(float2(
            sdRoundRect(q + float2(0.75, 0.0), halfSz, uRadius) - d,
            sdRoundRect(q + float2(0.0, 0.75), halfSz, uRadius) - d) + float2(0.0001, 0.0001));
        half4 reflA = uContent.eval(fragCoord + n * uMirrorLen * (0.45 + 0.55 * grazing));
        half4 reflB = uContent.eval(fragCoord + n * uMirrorLen * (1.05 + 0.55 * grazing));
        float3 refl = (reflA.rgb + reflB.rgb) * 0.5;
        base = mix(base, refl, grazing * uMirror);
    }

    // 效果输入为线性光、输出不回编：显式编码回 sRGB，折射结果才与原内容同亮度
    return half4(lin2srgb(base), 1.0);
}
        """.trimIndent()

    fun buildDropletSource(): String =
        """
uniform float2 uSize;
uniform float uRadius;
uniform float uTime;
uniform float3 uTint;
uniform float uAlpha;
uniform float uSpec;

$SD_ROUND_RECT

half4 main(float2 fragCoord) {
    float2 halfSz = uSize * 0.5;
    float2 q = fragCoord - halfSz;
    float d = sdRoundRect(q, halfSz, uRadius);
    float inside = -d;
    float2 p = fragCoord / uSize;

    // 水珠内的流动：缓厚带（色深涨落）+ 细光斑，与底栏同一节拍
    float fx = p.x + (p.y - 0.5) * 0.45;
    float wt = fract(fx - uTime * 0.055 + 0.58);
    float st = (wt - 0.5) * 3.0;
    float thick = exp(-st * st);
    float ws = fract(fx - uTime * 0.16 + 0.21);
    float ss = (ws - 0.5) * 6.0;
    float spec = exp(-ss * ss);

    // 水体：上薄下厚 + 厚带流动（色深随之涨落）
    float bodyA = uAlpha * mix(0.88, 1.0, p.y) * (1.0 + 0.50 * thick);

    // 边缘：1.6px 精光边（上强下柔），像水珠被光勾出的轮廓
    float thin = 1.0 - smoothstep(0.0, 1.6, inside);
    float upW = 1.0 - smoothstep(0.0, 0.95, p.y);
    float rim = thin * mix(0.28, 0.80, upW);

    // 顶部内壁柔光（呼吸）
    float dy = (p.y - (0.16 + 0.012 * sin(uTime * 0.9))) / 0.24;
    float glow = exp(-dy * dy) * 0.14;

    // 底部焦散细弧（弱，光被水珠聚到底沿）
    float caust = smoothstep(0.88, 0.97, p.y) * (1.0 - smoothstep(0.975, 1.0, p.y)) * 0.20;

    float hiA = clamp((rim * 0.75 + glow + spec * 0.22 + caust) * uSpec, 0.0, 1.0);
    float outA = hiA + bodyA * (1.0 - hiA);
    float3 premul = float3(1.0, 1.0, 1.0) * hiA + uTint * (bodyA * (1.0 - hiA));
    return half4(premul.r, premul.g, premul.b, outA);
}
        """.trimIndent()
}
