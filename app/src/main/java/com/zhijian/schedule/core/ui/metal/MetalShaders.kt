package com.zhijian.schedule.core.ui.metal

/**
 * 水银镜面 AGSL 源码（零截屏捕获；屏幕空间反射走 RenderEffect 采样内容图层）。
 *
 * 材质主张：**哑光衬镜面**——页面是暗室，控件是被抛光到能映出这间房的金属。
 * 玻璃靠「透明 + 折射」定义自己，金属靠「不透明 + 反射方向」定义自己，两者物理上互斥，
 * 所以这不是「玻璃换皮」，是把整套材质模型换掉。
 *
 * 三个内核：
 * - [buildSurfaceSource]  元件表面（Brush 路径，程序化摄影棚环境）：
 *   球冠法线 → 反射方向 → 程序化 env；菲涅尔调 F0；各向异性拉丝高光（Kajiya-Kay 的 sin 幂）；
 *   薄膜干涉虹彩；按压凹陷 + 触摸光斑；哑光面与抛光面按 uChrome / 倒角带混合。
 * - [buildSsrSource]     屏幕空间反射（Effect 路径，浮岛用）：反射方向直接采样内容图层，
 *   三通道分离取样做色散，与程序化 env 混合——真实映出下方滚动的页面。
 * - [buildAmbientSource] 全屏环境（页面底）：极暗房间 + 两团随倾角漂移的柔光，
 *   让元件的反射方向有「房间」可映，且页面本身不会平。
 *
 * 约定：全部 float 运算；仅 main 返回 half4；uniform 全部 float / float2 / float3（无数组）。
 */
object MetalShaders {
    /** 圆角矩形 SDF：主几何，元件与浮岛共用 */
    private const val SD_ROUND_RECT = """float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}"""

    /** 白噪声：只用于颗粒（金属表面最怕「塑料感」，一颗颗粒就救回来） */
    private const val HASH = """float hash21(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453123);
}"""

    /**
     * 程序化摄影棚环境。r = 反射方向。
     * 组成：天顶冷灰 / 地面暖黑 / 上方偏左的大软箱主灯 / 右后侧灯 / 一道环形柔光带。
     * 金属之所以读作金属，靠的是这片「房间」在法线扫过时的明暗跳变，而不是某个高光点。
     */
    private const val STUDIO_ENV = """float3 studioEnv(float3 r, float bright) {
    float y = clamp(r.y, -1.0, 1.0);
    float az = atan(r.z, r.x);

    float3 sky = mix(float3(0.10, 0.11, 0.13), float3(0.62, 0.65, 0.72), smoothstep(0.0, 1.0, y));
    float3 gnd = mix(float3(0.03, 0.03, 0.04), float3(0.22, 0.20, 0.18), smoothstep(-1.0, 0.0, y));
    float3 base = mix(gnd, sky, smoothstep(-0.08, 0.08, y));

    float3 L1 = normalize(float3(-0.40, 0.88, 0.38));
    float d1 = clamp(dot(r, L1), 0.0, 1.0);
    float key = smoothstep(0.80, 0.995, d1);
    float halo = pow(d1, 9.0) * 0.30;

    float3 L2 = normalize(float3(0.92, 0.10, 0.38));
    float d2 = clamp(dot(r, L2), 0.0, 1.0);
    float rim = smoothstep(0.90, 0.999, d2);

    float ringPos = 0.5 + 0.5 * cos(az * 2.0);
    float ringBand = exp(-pow((y - 0.34) * 6.0, 2.0)) * (0.35 + 0.65 * ringPos * ringPos);

    float3 boxN = normalize(float3(0.12, 0.62, 0.78));
    float3 boxT = normalize(cross(boxN, float3(0.0, 0.0, 1.0)) + float3(0.0001, 0.0001, 0.0));
    float3 boxB = cross(boxN, boxT);
    float bx = dot(r, boxT) / 0.62;
    float by = dot(r, boxB) / 0.14;
    float softbox = exp(-(bx * bx + by * by) * 2.4) * smoothstep(0.0, 0.35, dot(r, boxN));

    float3 col = base;
    col += float3(1.0, 0.98, 0.94) * (key * 2.1 + halo);
    col += float3(0.70, 0.85, 1.0) * rim * 1.20;
    col += float3(1.0, 0.97, 0.92) * ringBand * 0.35;
    col += float3(1.0, 0.98, 0.95) * softbox * 1.55;
    return col * bright;
}"""

    /** 薄膜干涉（氧化膜虹彩）：三波长近似，cosI 为入射余弦，nm 为膜厚 */
    private const val THIN_FILM = """float3 thinFilm(float cosI, float nm) {
    float3 lambda = float3(680.0, 550.0, 445.0);
    float3 ph = 6.2831853 * (2.0 * nm * cosI) / lambda;
    return 0.5 + 0.5 * cos(ph);
}"""

    /** 各向异性高光：Kajiya-Kay 的 sin(T,H) 幂，shin 越大越像「一道细亮线」 */
    private const val ANISO = """float aniso(float3 n, float3 v, float3 T, float shin) {
    float3 L = normalize(float3(-0.42, 0.76, 0.50));
    float3 H = normalize(L + v);
    float th = clamp(dot(T, H), -1.0, 1.0);
    float s = sqrt(max(0.0, 1.0 - th * th));
    return pow(s, shin);
}"""

    /** 拉丝纹理：沿 uSpin 方向的高频细纹，与高光相乘才有「拉丝金属」而不是「光滑塑料」 */
    private const val BRUSH = """float brushTex(float2 p, float spin) {
    float c = cos(spin);
    float s = sin(spin);
    float2 q = float2(p.x * c - p.y * s, p.x * s + p.y * c);
    float f = sin(q.y * 1.9 + sin(q.x * 0.004) * 1.4);
    float g = sin(q.y * 0.7 + 2.3);
    return 0.5 + 0.30 * f + 0.20 * g;
}"""

    /**
     * 元件表面（Brush 路径）。
     *
     * uniform：
     * - uSize/uRadius   元件尺寸与圆角（px）
     * - uTime           秒（薄膜厚度缓慢漂移，金属像在呼吸）
     * - uTouch          触摸点（0..1 归一化），远处传 (-1,-1)
     * - uPress          按压强度 0..1（凹陷 + 光斑）
     * - uTint           金属 F0 染色
     * - uFace           哑光面底色（卡面主体）
     * - uChrome         抛光程度 0..1（0 = 几乎全哑光，1 = 全镜面）
     * - uSpec/uBrush    高光强度 / 拉丝强度
     * - uIrid           虹彩强度
     * - uFilm           膜厚 nm
     * - uEnviron        环境亮度
     * - uGrain          颗粒强度
     * - uSpin           拉丝方向（弧度）
     */
    fun buildSurfaceSource(): String =
        """
uniform float2 uSize;
uniform float uRadius;
uniform float uTime;
uniform float2 uTouch;
uniform float uPress;
uniform float3 uTint;
uniform float3 uFace;
uniform float uChrome;
uniform float uSpec;
uniform float uBrush;
uniform float uIrid;
uniform float uFilm;
uniform float uEnviron;
uniform float uGrain;
uniform float uSpin;
uniform float uBlade;
uniform float uBloom;
uniform float uContact;

$SD_ROUND_RECT
$HASH
$STUDIO_ENV
$THIN_FILM
$ANISO
$BRUSH

half4 main(float2 fragCoord) {
    float2 halfSz = uSize * 0.5;
    float2 q = fragCoord - halfSz;
    float d = sdRoundRect(q, halfSz, uRadius);
    float inside = -d;

    float2 p = fragCoord / max(uSize, float2(1.0, 1.0));
    float2 qn = p * 2.0 - 1.0;

    float bevelW = 7.0;
    float bevel = 1.0 - smoothstep(0.0, bevelW, inside);

    float2 tq = uTouch * 2.0 - 1.0;
    float2 dt = qn - tq;

    float2 nxy = qn * 0.10;

    float dent = uPress * exp(-dot(dt, dt) * 2.6);
    nxy -= dt * dent * 2.4;

    float3 n = normalize(float3(nxy, 1.0));

    if (bevel > 0.001) {
        float2 dirq = normalize(q + float2(0.0001, 0.0001));
        n = normalize(n + float3(dirq * pow(bevel, 0.62) * 1.55, 0.0));
    }

    float3 v = float3(0.0, 0.0, 1.0);
    float ndv = clamp(dot(n, v), 0.0, 1.0);
    float fres = pow(1.0 - ndv, 5.0);
    float3 r = reflect(-v, n);

    float3 env = studioEnv(r, uEnviron * 0.55);
    float3 F = uTint + (float3(1.0) - uTint) * fres;

    float3 T = normalize(float3(cos(uSpin), sin(uSpin), 0.0));
    float a1 = aniso(n, v, T, 34.0);
    float a2 = aniso(n, v, T, 190.0);
    float tex = brushTex(fragCoord, uSpin);
    float grainHi = (a1 * 0.55 + a2 * 0.85) * uBrush * mix(0.30, 1.30, tex);
    grainHi *= 1.0 - bevel * 0.85;

    float3 ir = thinFilm(ndv, uFilm + 45.0 * sin(uTime * 0.22));
    float3 irid = (ir - 0.5) * 2.0;
    float iMask = clamp(pow(fres, 1.35) * 2.2 + pow(bevel, 1.6) * 0.85, 0.0, 1.0);

    float luma = dot(env, float3(0.299, 0.587, 0.114));
    // 面心本体：材质本色为主体（保住课程色相），但**注入一份环境方向光**——
    // 这是"金属片"与"塑料片"的分界：同样颜色，金属的亮部会随面朝向移动。
    float3 matte = uFace * (0.88 + 0.30 * clamp(luma, 0.0, 2.0));
    // 面心的一层薄环境反照：极低角度权重，只让平面"有光在流"，不改色相
    matte += env * 0.22 * (0.4 + 0.6 * uChrome);

    float3 chrome = env * mix(float3(0.38, 0.40, 0.46), F, 0.95);
    chrome += grainHi * uSpec * 0.85;
    chrome += irid * iMask * uIrid;

    float bladeW = 1.0 - smoothstep(0.0, 2.2, inside);
    float bladeLight = bladeW * uBlade * (0.42 + 0.58 * clamp(dot(n, normalize(float3(-0.34, 0.86, 0.38))), 0.0, 1.0));
    chrome += bladeLight * uSpec * 1.15;

    float spotCore = exp(-dot(dt, dt) * 30.0) * (0.18 + 0.82 * uPress);
    float spotHalo = exp(-dot(dt, dt) * 4.2) * (0.06 + 0.42 * uPress);
    chrome += (spotCore + spotHalo) * uSpec * 0.72;

    // 面心也吃一部分镜面（底线 uChrome*0.42），倒角再拉满——读作"抛光平板"而不是"描了一圈边"
    float faceMix = clamp(uChrome * 0.42 + pow(bevel, 1.25) * uChrome * 2.1, 0.0, 1.0);
    float3 col = mix(matte, chrome, faceMix);

    col += grainHi * uSpec * 0.26 * (1.0 - faceMix * 0.6);

    // 接触阴影只压最外 3px 的极窄一圈（原来 7px 压得太狠，圆按钮像被描了黑边）
    float contactMask = 1.0 - smoothstep(0.0, 3.2, inside);
    col *= 1.0 - contactMask * uContact * 0.42;

    float lum = dot(col, float3(0.299, 0.587, 0.114));
    col += col * smoothstep(0.55, 1.0, lum) * uBloom * 0.35;

    float g = hash21(fragCoord);
    col += (g - 0.5) * uGrain;

    float alpha = 1.0 - smoothstep(-0.8, 0.8, d);
    return half4(clamp(col, 0.0, 1.0) * alpha, alpha);
}
        """.trimIndent()

    /**
     * 屏幕空间反射（Effect 路径，浮岛专用）。
     * uniform shader uContent 由 createRuntimeShaderEffect 按名绑定内容图层；
     * uOrigin = 浮岛左上角在图层坐标中的位置（px），fragCoord 即图层坐标。
     */
    fun buildSsrSource(): String =
        """
uniform shader uContent;
uniform float2 uOrigin;
uniform float2 uSize;
uniform float uRadius;
uniform float2 uTouch;
uniform float uPress;
uniform float3 uTint;
uniform float3 uFace;
uniform float uChrome;
uniform float uSpec;
uniform float uBrush;
uniform float uIrid;
uniform float uFilm;
uniform float uEnviron;
uniform float uMirror;
uniform float uReflGain;
uniform float uSpin;
uniform float uBlade;
uniform float uBloom;
uniform float uContact;

$SD_ROUND_RECT
$STUDIO_ENV
$THIN_FILM
$ANISO

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
    float2 p = lc / max(uSize, float2(1.0, 1.0));
    float2 qn = p * 2.0 - 1.0;

    float r2 = clamp(dot(qn, qn) * 0.42, 0.0, 0.98);
    float dome = sqrt(1.0 - r2);
    float2 nxy = qn * 0.66;

    float2 tq = uTouch * 2.0 - 1.0;
    float2 dt = qn - tq;
    float dent = uPress * exp(-dot(dt, dt) * 3.0);
    nxy -= dt * dent * 1.9;

    float3 n = normalize(float3(nxy, max(dome, 0.06)));
    float bevel = 1.0 - smoothstep(0.0, 5.5, inside);
    if (bevel > 0.002) {
        float2 dirq = normalize(q + float2(0.0001, 0.0001));
        n = normalize(n + float3(dirq * bevel * 1.25, 0.0));
    }

    float3 v = float3(0.0, 0.0, 1.0);
    float ndv = clamp(dot(n, v), 0.0, 1.0);
    float fres = pow(1.0 - ndv, 5.0);
    float3 rr = reflect(-v, n);

    float2 off = rr.xy * uMirror * uSize.y * 1.9;
    float2 sp = fragCoord + off;
    half4 c0 = uContent.eval(sp + rr.xy * 2.0);
    half4 c1 = uContent.eval(sp);
    half4 c2 = uContent.eval(sp - rr.xy * 2.0);
    float3 refl = lin2srgb(float3(c0.r, c1.g, c2.b)) * uReflGain;

    float3 env = studioEnv(rr, uEnviron * 0.55);
    float3 F = uTint + (float3(1.0) - uTint) * fres;

    float3 T = normalize(float3(cos(uSpin), sin(uSpin), 0.0));
    float grainHi = (aniso(n, v, T, 34.0) * 0.55 + aniso(n, v, T, 190.0) * 0.85) * uBrush;
    grainHi *= 1.0 - bevel * 0.85;

    float3 ir = thinFilm(ndv, uFilm + 45.0 * sin(0.22));
    float3 irid = (ir - 0.5) * 2.0;
    float iMask = clamp(pow(fres, 1.35) * 2.2 + pow(bevel, 1.6) * 0.85, 0.0, 1.0);

    float reflW = clamp(uMirror * (0.30 + 0.70 * fres), 0.0, 1.0);
    float3 chrome = mix(env * mix(float3(0.38, 0.40, 0.46), F, 0.95), refl, reflW);
    chrome += grainHi * uSpec * 0.85;
    chrome += irid * iMask * uIrid;

    float bladeW = 1.0 - smoothstep(0.0, 2.2, inside);
    float bladeLight = bladeW * uBlade * (0.42 + 0.58 * clamp(dot(n, normalize(float3(-0.34, 0.86, 0.38))), 0.0, 1.0));
    chrome += bladeLight * uSpec * 1.15;

    chrome += (exp(-dot(dt, dt) * 30.0) * (0.18 + 0.82 * uPress) + exp(-dot(dt, dt) * 4.2) * (0.06 + 0.42 * uPress)) * uSpec * 0.72;

    float luma = dot(env, float3(0.299, 0.587, 0.114));
    float3 matte = uFace * (0.88 + 0.30 * clamp(luma, 0.0, 2.0));
    matte += env * 0.22 * (0.4 + 0.6 * uChrome);

    float faceMix = clamp(uChrome * 0.42 + pow(bevel, 1.25) * uChrome * 2.1, 0.0, 1.0);
    float3 col = mix(matte, chrome, faceMix);
    col += grainHi * uSpec * 0.26 * (1.0 - faceMix * 0.6);

    // 接触阴影只压最外 3px 的极窄一圈（原来 7px 压得太狠，圆按钮像被描了黑边）
    float contactMask = 1.0 - smoothstep(0.0, 3.2, inside);
    col *= 1.0 - contactMask * uContact * 0.42;

    float lum = dot(col, float3(0.299, 0.587, 0.114));
    col += col * smoothstep(0.55, 1.0, lum) * uBloom * 0.35;

    float alpha = 1.0 - smoothstep(-0.8, 0.8, d);
    return half4(clamp(col, 0.0, 1.0) * alpha, alpha);
}
        """.trimIndent()

    /**
     * 全屏环境（页面底）。
     * 极暗房间：天顶一点冷光、地面一点暖反光，两团柔光随倾角缓慢漂移。
     * 页面本身不抢戏，但它给了元件的反射方向一个「房间」——这是页面与材质配合的地基。
     */
    fun buildAmbientSource(): String =
        """
uniform float2 uSize;
uniform float uTime;
uniform float2 uGyro;
uniform float3 uFloor;
uniform float3 uCool;
uniform float3 uWarm;
uniform float uBright;

$HASH

half4 main(float2 fragCoord) {
    float2 p = fragCoord / max(uSize, float2(1.0, 1.0));
    float3 c = uFloor;

    float2 c1 = float2(0.26 + uGyro.x * 0.12, 0.08 - uGyro.y * 0.07)
              + float2(sin(uTime * 0.07) * 0.03, cos(uTime * 0.05) * 0.02);
    float2 c2 = float2(0.82 + uGyro.x * 0.07, 0.30 - uGyro.y * 0.11)
              + float2(cos(uTime * 0.06) * 0.04, sin(uTime * 0.08) * 0.03);

    float2 a1 = (p - c1) * float2(1.0, 1.35);
    float2 a2 = (p - c2) * float2(1.15, 1.0);
    float g1 = exp(-dot(a1, a1) * 7.0);
    float g2 = exp(-dot(a2, a2) * 11.0);

    c += uCool * g1 * uBright;
    c += uWarm * g2 * uBright;

    float horizon = exp(-pow((p.y - 0.72) * 4.5, 2.0));
    c *= 1.0 - horizon * 0.35;

    float g = hash21(floor(fragCoord * 0.75) + uTime * 0.3);
    c += (g - 0.5) * 0.012;

    return half4(clamp(c, 0.0, 1.0), 1.0);
}
        """.trimIndent()
}
