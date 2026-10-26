# 液态玻璃主题 · 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> 本仓库工作流适配：任务级检查点用「构建 / 单测 / 真机」替代频繁 commit（用户统一在验证通过后决定提交与发版时机）。

**Goal:** 为「纸间课表」新增第 6 套主题「液态玻璃」——深邃极光解析式背景 + 全局玻璃材质（课块/浮岛/FAB/弹层），零模糊、零截屏捕获，A13+ 自动叠加真折射（AGSL），适配全部原有动效。

**Architecture:** 三层：① AppRoot 底部全屏 `AuroraBackdrop`（解析式极光场，Kotlin/AGSL 双档共享同一数学）；② `Modifier.glassSurface()` 玻璃材质（A13+ 走 RuntimeShader：SDF 边缘折射 + R/B 色散 + 镜面高光；旧机走渐变光影档）；③ 内容层不变。折射采样复用极光解析函数，不做背景捕获。

**Tech Stack:** Compose UI 1.10.6（BOM 2026.03.01）、AGSL RuntimeShader（API 33+）、Gradle（`.tmp\run-tests.bat`）、JUnit4 单测。

**设计文档:** `docs/specs/2026-10-15-liquid-glass-theme-design.md`

---

## 文件结构

**新增（`core/ui/glass/`）**
| 文件 | 职责 |
|---|---|
| `AuroraField.kt` | 极光光雾定义与漂移纯函数（无 Android 依赖，可单测） |
| `GlassShaders.kt` | AGSL 源码生成（纯字符串，可单测）：背景 shader + 元素 shader，共用同一段极光函数 |
| `GlassRuntime.kt` | 能力判定（SDK≥33 且非低端机）、共享屏幕尺寸、shader 工厂与 uniform 配置（唯一引用 RuntimeShader 的文件） |
| `AuroraBackdrop.kt` | 全屏极光背景 Composable（shader/渐变双档）+ `rememberAuroraClock`（30fps + 生命周期）+ `LocalAuroraTime` |
| `GlassSurface.kt` | `Modifier.glassSurface()`（双档绘制）+ `SolidShaderBrush` |

**修改**
| 文件 | 改动 |
|---|---|
| `core/theme/ThemePreset.kt` | +`GlassSpec`、`ThemePreset.glass`、`ThemeIds.GLASS` |
| `core/theme/Presets.kt` | +`glass` 预设并注册进 `all` |
| `core/theme/CourseTheme.kt` | +`CourseColors.pageBg`（玻璃主题下透明） |
| `navigation/AppRoot.kt` | 时钟 + `LocalAuroraTime` 注入 + `AuroraBackdrop` + Scaffold 透明 |
| `navigation/IslandTabBar.kt` | 浮岛容器/指示胶囊玻璃化 |
| `feature/schedule/components/CourseBlock.kt` | 课块玻璃化（press/lift 实时驱动） |
| `feature/schedule/ScheduleScreen.kt` | FAB 玻璃球 |
| `feature/today/TodayScreen.kt` | 今日行课程卡玻璃化 |
| `core/ui/SecondaryTopBar.kt`、`feature/settings/SettingsScreen.kt`、`feature/importer/ImporterScreen.kt`、`feature/jw/JwImportScreen.kt`、`feature/course_editor/CourseEditorScreen.kt`、`feature/grades/GradesScreen.kt` | `colors.bg` → `colors.pageBg` |

**测试**：`app/src/test/java/com/zhijian/schedule/GlassThemeTest.kt`

**明确不做**：模糊；截屏捕获；周选择面板格子玻璃化（sheet 内 dim 层上收益低，靠 token 自动适配）；小组件改动（自动继承预设）；`Motion.kt` 任何改动。

**关键约定（实现时不要违反）**
1. AGSL 全部用 `float` 运算，`half4 main(float2)` 内仅最后 `half4(x,y,z,w)` 四参构造转换；`uTime` 声明 `float`。
2. 玻璃元素 shader 的 `uOrigin` = `positionInRoot()`（px），`uField` = 全屏尺寸，与背景 shader 同一坐标系；`FragCoord` 为元素局部坐标。
3. RuntimeShader 引用只允许出现在 `GlassRuntime.kt` 内；所有调用点必须被 `GlassRuntime.supported(context)` 短路（minSdk 26 的类加载安全）。
4. 元素本地绘制从 `(0,0)` 起（`drawRoundRect` 默认 topLeft），与 SDF 局部坐标一致。
5. AGSL 编译错误只在运行时抛出，真机验证必须查 logcat。

---

### Task 1: 数据模型与预设

**Files:**
- Modify: `app/src/main/java/com/zhijian/schedule/core/theme/ThemePreset.kt`
- Modify: `app/src/main/java/com/zhijian/schedule/core/theme/Presets.kt`
- Modify: `app/src/main/java/com/zhijian/schedule/core/theme/CourseTheme.kt`

- [ ] **Step 1.1: ThemePreset.kt 增加 GlassSpec 与注册位**

在 `RadiusPreset` 之后、`ThemePreset` 之前插入：

```kotlin
/**
 * 液态玻璃材质参数（仅「液态玻璃」预设非空）。
 * 背景极光为解析式场：底色 + N 团光雾，光雾色与 AuroraField.blobs 一一对应。
 */
data class GlassSpec(
    /** 极光底色（亮/暗） */
    val auroraBaseLight: Color,
    val auroraBaseDark: Color,
    /** 光雾色（亮/暗），与 AuroraField.blobs 同序 */
    val auroraLight: List<Color>,
    val auroraDark: List<Color>,
    /** 中性玻璃（浮岛/按钮/行卡）主体 alpha 亮/暗 */
    val paneAlphaLight: Float,
    val paneAlphaDark: Float,
    /** 折射位移强度（dp @1x），运行时乘 density */
    val bendDp: Float,
    /** 镜面高光强度 */
    val specular: Float,
)
```

`ThemePreset` 末尾（`isDynamic` 之前）加：

```kotlin
    /** 非空 = 启用玻璃材质渲染（仅 glass 预设） */
    val glass: GlassSpec? = null,
```

`ThemeIds` 增加：

```kotlin
    const val GLASS = "glass"
```

- [ ] **Step 1.2: Presets.kt 增加 glass 预设**

在 `candy` 之后加入（`GlassSpec` 与 `ThemePreset` 同包，无需 import）：

```kotlin
    /** 6. 液态玻璃：深邃极光底 + 全局玻璃材质（零模糊，A13+ 真折射） */
    val glass = ThemePreset(
        id = ThemeIds.GLASS,
        name = "液态玻璃",
        light = CourseColors(
            bg = Color(0xFFF2F6FD),
            surface = Color(0xD9FFFFFF),
            surfaceVariant = Color(0xB3FFFFFF),
            text = Color(0xFF17203A),
            textMuted = Color(0xFF5F6C8C),
            border = Color(0x2E17203A),
            accent = Color(0xFF3E7BFF),
            onAccent = Color(0xFFFFFFFF),
        ),
        dark = CourseColors(
            bg = Color(0xFF0A0E28),
            surface = Color(0x33FFFFFF),
            surfaceVariant = Color(0x1FFFFFFF),
            text = Color(0xFFEAF0FF),
            textMuted = Color(0xFF99A4C6),
            border = Color(0x33FFFFFF),
            accent = Color(0xFF6FD3FF),
            onAccent = Color(0xFF07203A),
        ),
        coursePalette = listOf(
            Color(0xFF57C7FF), Color(0xFF39D2C0), Color(0xFF8F7BF7), Color(0xFFF178B6),
            Color(0xFF7CE3A9), Color(0xFF9D8CFF), Color(0xFF4FA8E8), Color(0xFFC084FC),
        ),
        blockStyle = BlockStyle(fillAlphaLight = 0.12f, fillAlphaDark = 0.16f, barWidthDp = 3f),
        typography = TypePreset(serifTitle = false),
        radius = RadiusPreset(blockDp = 16f, cardDp = 20f, sheetDp = 26f),
        glass = GlassSpec(
            auroraBaseLight = Color(0xFFEDF2FC),
            auroraBaseDark = Color(0xFF0A0E28),
            auroraLight = listOf(
                Color(0xFFBDE8F5), Color(0xFFBDD2FF), Color(0xFFD9C9FF), Color(0xFFFFD2E8),
            ),
            auroraDark = listOf(
                Color(0xFF54D8E8), Color(0xFF3D6BFF), Color(0xFF8B5CFF), Color(0xFFFF5CA8),
            ),
            paneAlphaLight = 0.42f,
            paneAlphaDark = 0.22f,
            bendDp = 13f,
            specular = 0.55f,
        ),
    )

    val all: List<ThemePreset> = listOf(paper, dynamic, minimal, amoled, candy, glass)
```

- [ ] **Step 1.3: CourseTheme.kt 增加 pageBg**

在 `LocalIsDark` 定义后插入：

```kotlin
/** 玻璃主题下页面底色透明（极光垫底透出）；其余主题为原底色 */
val CourseColors.pageBg: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalCoursePreset.current.glass != null) Color.Transparent else bg
```

补充 import：`androidx.compose.runtime.ReadOnlyComposable`。

- [ ] **Step 1.4: 构建检查**

Run: `cmd //c ".tmp\run-tests.bat :app:compileDebugKotlin"`
Expected: BUILD SUCCESSFUL（此时 glass 尚未被任何 UI 消费，静态编译即可通过）

---

### Task 2: 极光场与 shader 源码生成（纯 Kotlin + 单测）

**Files:**
- Create: `app/src/main/java/com/zhijian/schedule/core/ui/glass/AuroraField.kt`
- Create: `app/src/main/java/com/zhijian/schedule/core/ui/glass/GlassShaders.kt`
- Create: `app/src/test/java/com/zhijian/schedule/GlassThemeTest.kt`

- [ ] **Step 2.1: 写 AuroraField.kt（完整文件）**

```kotlin
package com.zhijian.schedule.core.ui.glass

import kotlin.math.cos
import kotlin.math.sin

/**
 * 解析式极光场：底色之上叠加 [blobs] 团光雾，位置随时间极慢漂移。
 * 纯函数、零 Android 依赖——背景绘制（Kotlin 渐变档）与 AGSL 折射采样（GlassShaders 注入同一批常量）共享这一定义。
 */
data class AuroraBlob(
    val baseX: Float,
    val baseY: Float,
    val ampX: Float,
    val ampY: Float,
    val freqX: Float,
    val freqY: Float,
    val phaseX: Float,
    val phaseY: Float,
    /** 半径（相对屏幕短边） */
    val radius: Float,
    /** 混入强度（0~1） */
    val intensity: Float,
)

object AuroraField {

    val blobs = listOf(
        AuroraBlob(0.20f, 0.16f, 0.10f, 0.05f, 0.031f, 0.023f, 0.0f, 1.7f, 0.62f, 0.55f),
        AuroraBlob(0.84f, 0.28f, 0.08f, 0.06f, 0.026f, 0.019f, 2.1f, 0.6f, 0.55f, 0.45f),
        AuroraBlob(0.56f, 0.82f, 0.12f, 0.05f, 0.021f, 0.030f, 4.0f, 2.8f, 0.60f, 0.40f),
        AuroraBlob(0.12f, 0.68f, 0.09f, 0.07f, 0.024f, 0.027f, 1.2f, 3.9f, 0.50f, 0.35f),
    )

    /** 归一化圆心 x（0~1），t 为秒 */
    fun centerX(blob: AuroraBlob, t: Float): Float =
        blob.baseX + blob.ampX * sin(t * blob.freqX + blob.phaseX)

    /** 归一化圆心 y（0~1），t 为秒 */
    fun centerY(blob: AuroraBlob, t: Float): Float =
        blob.baseY + blob.ampY * cos(t * blob.freqY + blob.phaseY)
}
```

- [ ] **Step 2.2: 写 GlassShaders.kt（完整文件）**

```kotlin
package com.zhijian.schedule.core.ui.glass

import java.util.Locale

/**
 * AGSL 源码生成：背景 shader 与元素 shader 共用同一段极光函数（常量由 AuroraField 注入字面量），
 * 保证「折射采样」与「背景绘制」数学完全一致。
 * 约定：全部 float 运算；uTime 为 float；仅 main 返回 half4（四参构造）。
 */
object GlassShaders {

    private fun f(v: Float): String = String.format(Locale.US, "%.5f", v)

    private val AURORA_UNIFORMS = """
uniform float3 uBase;
uniform float uVignette;
uniform float3 uBlob0;
uniform float3 uBlob1;
uniform float3 uBlob2;
uniform float3 uBlob3;
""".trimIndent()

    /** 极光场函数（两个 shader 共享的同一段源码） */
    fun auroraFunction(blobs: List<AuroraBlob> = AuroraField.blobs): String {
        val sb = StringBuilder()
        sb.appendLine("float3 aurora(float2 p, float2 s, float t) {")
        sb.appendLine("    float3 col = uBase;")
        blobs.forEachIndexed { i, b ->
            sb.appendLine(
                "    float2 c$i = float2((${f(b.baseX)} + ${f(b.ampX)} * sin(t * ${f(b.freqX)} + ${f(b.phaseX)})) * s.x," +
                    " (${f(b.baseY)} + ${f(b.ampY)} * cos(t * ${f(b.freqY)} + ${f(b.phaseY)})) * s.y);"
            )
            sb.appendLine("    float2 d$i = (p - c$i) / (${f(b.radius)} * min(s.x, s.y));")
            sb.appendLine("    col = mix(col, uBlob$i, ${f(b.intensity)} * exp(-dot(d$i, d$i) * 1.7));")
        }
        sb.appendLine("    float vig = length((p / s) - float2(0.5, 0.42));")
        sb.appendLine("    col *= 1.0 - uVignette * smoothstep(0.25, 0.85, vig);")
        sb.appendLine("    return clamp(col, float3(0.0, 0.0, 0.0), float3(1.0, 1.0, 1.0));")
        sb.appendLine("}")
        return sb.toString().trimEnd()
    }

    /** 全屏背景 shader */
    fun buildBackdropSource(blobs: List<AuroraBlob> = AuroraField.blobs): String = """
$AURORA_UNIFORMS
uniform float2 uField;
uniform float uTime;

${auroraFunction(blobs)}

half4 main(float2 fragCoord) {
    float3 col = aurora(fragCoord, uField, uTime);
    return half4(col.x, col.y, col.z, 1.0);
}
""".trimIndent()

    /** 玻璃元素 shader：SDF 边缘折射 + R/B 色散 + 镜面高光 + 清透主体 */
    fun buildElementSource(blobs: List<AuroraBlob> = AuroraField.blobs): String = """
$AURORA_UNIFORMS
uniform float2 uSize;
uniform float2 uOrigin;
uniform float2 uField;
uniform float uTime;
uniform float uRadius;
uniform float uBand;
uniform float uBend;
uniform float uBodyAlpha;
uniform float uEdgeAlpha;
uniform float uSpec;
uniform float3 uTint;

float sdRoundRect(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

${auroraFunction(blobs)}

half4 main(float2 fragCoord) {
    float2 halfSz = uSize * 0.5;
    float2 q = fragCoord - halfSz;
    float d = sdRoundRect(q, halfSz, uRadius);
    float edgeT = 1.0 - clamp(-d / uBand, 0.0, 1.0);
    float2 n = normalize(float2(
        sdRoundRect(q + float2(0.75, 0.0), halfSz, uRadius) - d,
        sdRoundRect(q + float2(0.0, 0.75), halfSz, uRadius) - d) + float2(0.0001, 0.0001));

    float2 screenPos = uOrigin + fragCoord;
    float2 disp = n * (uBend * edgeT * edgeT);

    float3 refr;
    refr.r = aurora(screenPos + disp * 1.15, uField, uTime).r;
    refr.g = aurora(screenPos + disp, uField, uTime).g;
    refr.b = aurora(screenPos + disp * 0.85, uField, uTime).b;

    float2 lightDir = normalize(float2(-0.55, -0.84));
    float facing = clamp(dot(n, lightDir), 0.0, 1.0);
    float spec = facing * facing * edgeT * edgeT * edgeT * uSpec;

    float e = edgeT * edgeT * (3.0 - 2.0 * edgeT);
    float3 baseCol = mix(uTint, refr, e * 0.92);
    float baseA = mix(uBodyAlpha, uEdgeAlpha, e);

    float3 premul = baseCol * baseA + float3(spec, spec, spec) * (1.0 - baseA);
    float outA = baseA + spec * (1.0 - baseA);
    float3 bounded = min(premul, float3(outA, outA, outA));
    return half4(bounded.x, bounded.y, bounded.z, outA);
}
""".trimIndent()
}
```

- [ ] **Step 2.3: 写单测 GlassThemeTest.kt（完整文件）**

```kotlin
package com.zhijian.schedule

import com.zhijian.schedule.core.theme.ThemeIds
import com.zhijian.schedule.core.theme.ThemePresets
import com.zhijian.schedule.core.ui.glass.AuroraField
import com.zhijian.schedule.core.ui.glass.GlassShaders
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GlassThemeTest {

    @Test
    fun glassPresetRegistered() {
        val preset = ThemePresets.byId(ThemeIds.GLASS)
        assertEquals(ThemeIds.GLASS, preset.id)
        assertEquals("液态玻璃", preset.name)
        assertNotNull(preset.glass)
        assertEquals(6, ThemePresets.all.size)
    }

    @Test
    fun onlyGlassPresetCarriesGlassSpec() {
        for (p in ThemePresets.all) {
            if (p.id == ThemeIds.GLASS) assertNotNull(p.id, p.glass) else assertNull(p.id, p.glass)
        }
    }

    @Test
    fun auroraColorSlotsMatchBlobCount() {
        val spec = ThemePresets.byId(ThemeIds.GLASS).glass!!
        assertEquals(AuroraField.blobs.size, spec.auroraLight.size)
        assertEquals(AuroraField.blobs.size, spec.auroraDark.size)
    }

    @Test
    fun auroraDriftStaysInUnitBounds() {
        for (b in AuroraField.blobs) {
            var t = 0
            while (t <= 600) {
                val x = AuroraField.centerX(b, t.toFloat())
                val y = AuroraField.centerY(b, t.toFloat())
                assertTrue("x=$x", x in 0f..1f)
                assertTrue("y=$y", y in 0f..1f)
                t += 7
            }
        }
    }

    @Test
    fun auroraDriftIsSlow() {
        for (b in AuroraField.blobs) {
            for (t in 0..100) {
                val dx = abs(AuroraField.centerX(b, t + 1f) - AuroraField.centerX(b, t.toFloat()))
                val dy = abs(AuroraField.centerY(b, t + 1f) - AuroraField.centerY(b, t.toFloat()))
                assertTrue("dx=$dx", dx < 0.01f)
                assertTrue("dy=$dy", dy < 0.01f)
            }
        }
    }

    @Test
    fun shaderSourcesContainRequiredUniforms() {
        val backdrop = GlassShaders.buildBackdropSource()
        assertTrue(backdrop.contains("uBlob0"))
        assertTrue(backdrop.contains("half4 main(float2 fragCoord)"))

        val element = GlassShaders.buildElementSource()
        for (u in listOf(
            "uSize", "uOrigin", "uField", "uRadius", "uBand",
            "uBend", "uBodyAlpha", "uEdgeAlpha", "uSpec", "uTint",
        )) {
            assertTrue(u, element.contains("uniform float2 $u") || element.contains("uniform float3 $u") || element.contains("uniform float $u"))
        }
        assertTrue(element.contains("sdRoundRect"))
    }
}
```

- [ ] **Step 2.4: 跑单测**

Run: `cmd //c ".tmp\run-tests.bat :app:testDebugUnitTest"`
Expected: 全部 PASS（含既有测试与新增 6 个）

---

### Task 3: 玻璃运行时（能力判定 + shader 工厂 + uniform 配置）

**Files:**
- Create: `app/src/main/java/com/zhijian/schedule/core/ui/glass/GlassRuntime.kt`

- [ ] **Step 3.1: 写 GlassRuntime.kt（完整文件）**

```kotlin
package com.zhijian.schedule.core.ui.glass

import android.app.ActivityManager
import android.content.Context
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.ui.graphics.Color
import com.zhijian.schedule.core.theme.GlassSpec

/**
 * 玻璃渲染运行时：能力判定、极光场共享尺寸、shader 工厂与 uniform 配置。
 * 本文件是唯一引用 android.graphics.RuntimeShader（API 33）的位置；
 * 所有公开方法的调用点都必须先经 [supported] 短路，保证 minSdk 26 类加载安全。
 */
object GlassRuntime {

    /** 真机降档预览开关（临时置 true 构建一次，截图后还原） */
    var forceFallbackForPreview: Boolean = false

    private var supportedCache: Boolean? = null

    fun supported(context: Context): Boolean {
        supportedCache?.let { return it }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val ok = !forceFallbackForPreview &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !am.isLowRamDevice
        supportedCache = ok
        return ok
    }

    /** 全屏极光场尺寸（px），由 AuroraBackdrop 每帧写入，玻璃元素折射采样依赖它对齐 */
    var fieldWidthPx: Float = 1f
    var fieldHeightPx: Float = 1f

    fun backdropShader(spec: GlassSpec, isDark: Boolean): RuntimeShader =
        RuntimeShader(GlassShaders.buildBackdropSource()).also {
            configureAurora(it, spec, isDark)
            it.setFloatUniform("uField", 1f, 1f)
            it.setFloatUniform("uTime", 0f)
        }

    fun elementShader(spec: GlassSpec, isDark: Boolean): RuntimeShader =
        RuntimeShader(GlassShaders.buildElementSource()).also {
            configureAurora(it, spec, isDark)
        }

    fun updateBackdrop(shader: RuntimeShader, widthPx: Float, heightPx: Float, time: Float) {
        shader.setFloatUniform("uField", widthPx, heightPx)
        shader.setFloatUniform("uTime", time)
        fieldWidthPx = widthPx
        fieldHeightPx = heightPx
    }

    fun updateElement(
        shader: RuntimeShader,
        widthPx: Float,
        heightPx: Float,
        originX: Float,
        originY: Float,
        time: Float,
        radiusPx: Float,
        bandPx: Float,
        bendPx: Float,
        bodyAlpha: Float,
        edgeAlpha: Float,
        spec: Float,
        tint: Color,
    ) {
        shader.setFloatUniform("uSize", widthPx, heightPx)
        shader.setFloatUniform("uOrigin", originX, originY)
        shader.setFloatUniform("uField", fieldWidthPx, fieldHeightPx)
        shader.setFloatUniform("uTime", time)
        shader.setFloatUniform("uRadius", radiusPx)
        shader.setFloatUniform("uBand", bandPx)
        shader.setFloatUniform("uBend", bendPx)
        shader.setFloatUniform("uBodyAlpha", bodyAlpha)
        shader.setFloatUniform("uEdgeAlpha", edgeAlpha)
        shader.setFloatUniform("uSpec", spec)
        shader.setFloatUniform("uTint", tint.red, tint.green, tint.blue)
    }

    private fun configureAurora(shader: RuntimeShader, spec: GlassSpec, isDark: Boolean) {
        val base = if (isDark) spec.auroraBaseDark else spec.auroraBaseLight
        val blobs = if (isDark) spec.auroraDark else spec.auroraLight
        shader.setFloatUniform("uBase", base.red, base.green, base.blue)
        shader.setFloatUniform("uVignette", if (isDark) 0.30f else 0.10f)
        for (i in 0 until 4) {
            val c = blobs.getOrElse(i) { Color.Transparent }
            shader.setFloatUniform("uBlob$i", c.red, c.green, c.blue)
        }
    }
}
```

- [ ] **Step 3.2: 构建检查**

Run: `cmd //c ".tmp\run-tests.bat :app:compileDebugKotlin"`
Expected: BUILD SUCCESSFUL

---

### Task 4: 极光背景 + 时钟 + 玻璃 Modifier

**Files:**
- Create: `app/src/main/java/com/zhijian/schedule/core/ui/glass/AuroraBackdrop.kt`
- Create: `app/src/main/java/com/zhijian/schedule/core/ui/glass/GlassSurface.kt`

- [ ] **Step 4.1: 写 AuroraBackdrop.kt（完整文件）**

```kotlin
package com.zhijian.schedule.core.ui.glass

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.zhijian.schedule.core.theme.GlassSpec
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.theme.LocalIsDark
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.math.min

/** 极光时钟（秒）：AppRoot 每帧注入；玻璃元素在 draw 阶段读取，触发重绘而不重组 */
val LocalAuroraTime = staticCompositionLocalOf<State<Float>?> { null }

/**
 * 30fps 量化时钟 + 生命周期暂停：非 RESUMED 不请求帧，恢复时从 0 重新计时。
 */
@Composable
fun rememberAuroraClock(): State<Float> {
    val time = remember { mutableFloatStateOf(0f) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val lifecycle = lifecycleOwner.lifecycle
        while (currentCoroutineContext().isActive) {
            snapshotFlow { lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
                .first { it }
            var base = 0L
            var last = 0L
            withFrameNanos { base = it; last = it }
            while (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                val now = withFrameNanos { it }
                if (now - last >= 33_000_000L) {
                    last = now
                    time.floatValue = (now - base) / 1_000_000_000f
                }
            }
        }
    }
    return time
}

/**
 * 全屏极光背景：A13+ 且非低端机走 AGSL shader（与玻璃元素共用解析场）；
 * 其余走 Kotlin 径向渐变光雾（同参数近似）。
 */
@Composable
fun AuroraBackdrop(clock: State<Float>, modifier: Modifier = Modifier) {
    val preset = LocalCoursePreset.current
    val spec = preset.glass ?: return
    val isDark = LocalIsDark.current
    val context = LocalContext.current
    val supported = remember(context) { GlassRuntime.supported(context) }

    if (supported) {
        val shader = remember(spec, isDark) { GlassRuntime.backdropShader(spec, isDark) }
        val brush = remember(shader) { SolidShaderBrush(shader) }
        Box(
            modifier.drawBehind {
                GlassRuntime.updateBackdrop(shader, size.width, size.height, clock.value)
                drawRect(brush)
            },
        )
    } else {
        val base = if (isDark) spec.auroraBaseDark else spec.auroraBaseLight
        val blobColors = if (isDark) spec.auroraDark else spec.auroraLight
        Box(
            modifier.drawBehind {
                val t = clock.value
                drawRect(base)
                val minDim = min(size.width, size.height)
                AuroraField.blobs.forEachIndexed { i, blob ->
                    val cx = AuroraField.centerX(blob, t) * size.width
                    val cy = AuroraField.centerY(blob, t) * size.height
                    val radius = blob.radius * minDim
                    val color = blobColors.getOrElse(i) { Color.Transparent }
                    drawCircle(
                        brush = Brush.radialGradient(
                            0f to color.copy(alpha = blob.intensity),
                            0.55f to color.copy(alpha = blob.intensity * 0.45f),
                            1f to Color.Transparent,
                            center = Offset(cx, cy),
                            radius = radius,
                        ),
                        radius = radius,
                        center = Offset(cx, cy),
                    )
                }
            },
        )
    }
}
```

- [ ] **Step 4.2: 写 GlassSurface.kt（完整文件）**

```kotlin
package com.zhijian.schedule.core.ui.glass

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.theme.LocalIsDark

/** 承载 RuntimeShader 的 Brush：多次绘制复用同一个 shader 实例，uniform 在 draw 阶段更新 */
internal class SolidShaderBrush(private val shader: Shader) : ShaderBrush() {
    override fun createShader(size: Size): Shader = shader
}

/**
 * 液态玻璃材质（玻璃主题专用，调用点先判 preset.glass != null 再调用）：
 * - A13+ / 非低端机：AGSL 圆角矩形 SDF 边缘折射 + R/B 色散 + 镜面高光（清透主体，不做模糊）；
 * - 旧机：渐变光影档（半透明底 + 对角高光描边），无 shader 无时钟依赖。
 * [press]∈[0.94,1] 与 [lift]∈[0,1] 在 draw 阶段读取——按压/选中动画实时驱动折射与高光。
 */
@Composable
fun Modifier.glassSurface(
    shape: RoundedCornerShape,
    tint: Color,
    bodyAlpha: Float,
    press: () -> Float = { 1f },
    lift: () -> Float = { 0f },
): Modifier {
    val spec = LocalCoursePreset.current.glass ?: return this
    val isDark = LocalIsDark.current
    val context = LocalContext.current
    val supported = remember(context) { GlassRuntime.supported(context) }
    val clock = LocalAuroraTime.current

    if (supported && clock != null) {
        val shader = remember(spec, isDark) { GlassRuntime.elementShader(spec, isDark) }
        val brush = remember(shader) { SolidShaderBrush(shader) }
        val origin = remember { FloatArray(2) }
        val density = LocalDensity.current
        val bandPx = with(density) { 18.dp.toPx() }
        val bendPx = with(density) { spec.bendDp.dp.toPx() }
        val edgeAlpha = if (isDark) 0.85f else 0.80f
        return this
            .onGloballyPositioned { coords ->
                val pos = coords.positionInRoot()
                origin[0] = pos.x
                origin[1] = pos.y
            }
            .drawBehind {
                val l = lift().coerceIn(0f, 1f)
                val p = press()
                val r = shape.radiusPx(size, this)
                GlassRuntime.updateElement(
                    shader = shader,
                    widthPx = size.width,
                    heightPx = size.height,
                    originX = origin[0],
                    originY = origin[1],
                    time = clock.value,
                    radiusPx = r,
                    bandPx = bandPx,
                    bendPx = bendPx * (2f - p) * (1f + 0.9f * l),
                    bodyAlpha = (bodyAlpha + 0.10f * l).coerceAtMost(0.92f),
                    edgeAlpha = edgeAlpha,
                    spec = spec.specular * (2f - p) * (1f + 1.1f * l),
                    tint = tint,
                )
                drawRoundRect(
                    brush = brush,
                    cornerRadius = CornerRadius(r, r),
                )
            }
    }

    return this.drawWithCache {
        val r = shape.radiusPx(size, this)
        val cr = CornerRadius(r, r)
        val highlight = Brush.linearGradient(
            colors = listOf(
                Color.White.copy(alpha = 0.34f),
                Color.White.copy(alpha = 0.05f),
                Color.White.copy(alpha = 0.16f),
            ),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height),
        )
        val strokePx = 1.4.dp.toPx()
        onDrawBehind {
            val l = lift().coerceIn(0f, 1f)
            val p = press()
            val boost = (2f - p) * (1f + 0.8f * l)
            drawRoundRect(
                color = tint.copy(alpha = (bodyAlpha * (0.6f + 0.4f * boost)).coerceIn(0f, 0.92f)),
                cornerRadius = cr,
            )
            drawRoundRect(
                brush = highlight,
                cornerRadius = cr,
                style = Stroke(width = strokePx),
                alpha = (0.7f + 0.3f * ((boost - 1f).coerceIn(0f, 1f))).coerceIn(0f, 1f),
            )
        }
    }
}

private fun RoundedCornerShape.radiusPx(size: Size, density: Density): Float =
    topStart.toPx(size, density)
```

- [ ] **Step 4.3: 构建检查**

Run: `cmd //c ".tmp\run-tests.bat :app:compileDebugKotlin"`
Expected: BUILD SUCCESSFUL（若 `Density` 类型不匹配报错：`radiusPx` 的 density 参数改为 `androidx.compose.ui.unit.Density`）

---

### Task 5: AppRoot 接入（背景 + 时钟 + 页面透明）

**Files:**
- Modify: `app/src/main/java/com/zhijian/schedule/navigation/AppRoot.kt:159-176`
- Modify: `core/ui/SecondaryTopBar.kt:38`
- Modify: `feature/settings/SettingsScreen.kt:105`
- Modify: `feature/importer/ImporterScreen.kt:104`
- Modify: `feature/jw/JwImportScreen.kt:172`
- Modify: `feature/course_editor/CourseEditorScreen.kt:103`
- Modify: `feature/grades/GradesScreen.kt:76`

- [ ] **Step 5.1: AppRoot.kt 改造 AppScaffold 头部**

将（第 ~159-176 行）：

```kotlin
@Composable
private fun AppScaffold(openTodayCount: Int) {
    val navController = rememberNavController()
    ...
    Scaffold { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
```

改为：

```kotlin
@Composable
private fun AppScaffold(openTodayCount: Int) {
    val navController = rememberNavController()
    ...
    val preset = LocalCoursePreset.current
    val isGlass = preset.glass != null
    val auroraClock = if (isGlass) rememberAuroraClock() else null

    CompositionLocalProvider(LocalAuroraTime provides auroraClock) {
        Box(Modifier.fillMaxSize()) {
            if (auroraClock != null) {
                AuroraBackdrop(clock = auroraClock, modifier = Modifier.fillMaxSize())
            }
            Scaffold(
                containerColor = if (isGlass) Color.Transparent else MaterialTheme.colorScheme.background,
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
```

并在文件尾（`AnimatedVisibility` 浮岛块与 `Box` 之后、`}` 之前）补上两层闭合花括号（原 Scaffold 出现位置缩进整体 +4）。

新增 import：

```kotlin
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.ui.glass.AuroraBackdrop
import com.zhijian.schedule.core.ui.glass.LocalAuroraTime
import com.zhijian.schedule.core.ui.glass.rememberAuroraClock
```

- [ ] **Step 5.2: 六个页面底色透明化**

逐处替换（找到即替换，不新增其他改动）：

| 文件:行 | 原 | 新 |
|---|---|---|
| `core/ui/SecondaryTopBar.kt:38` | `containerColor = colors.bg` | `containerColor = colors.pageBg` |
| `feature/settings/SettingsScreen.kt:105` | `containerColor = colors.bg` | `containerColor = colors.pageBg` |
| `feature/importer/ImporterScreen.kt:104` | `containerColor = colors.bg` | `containerColor = colors.pageBg` |
| `feature/jw/JwImportScreen.kt:172` | `containerColor = colors.bg` | `containerColor = colors.pageBg` |
| `feature/course_editor/CourseEditorScreen.kt:103` | `containerColor = colors.bg` | `containerColor = colors.pageBg` |
| `feature/grades/GradesScreen.kt:76` | `.background(colors.bg)` | `.background(colors.pageBg)` |

各文件补 import：`com.zhijian.schedule.core.theme.pageBg`。

- [ ] **Step 5.3: 构建检查**

Run: `cmd //c ".tmp\run-tests.bat :app:compileDebugKotlin"`
Expected: BUILD SUCCESSFUL

---

### Task 6: 课块玻璃化

**Files:**
- Modify: `app/src/main/java/com/zhijian/schedule/feature/schedule/components/CourseBlock.kt:56-93`

- [ ] **Step 6.1: 改造 CourseBlock 背景链**

在 `val ring = lift.coerceIn(0f, 1f)` 后加：

```kotlin
    val glass = preset.glass
```

将 Row 的修饰链（第 87-88 行两行）：

```kotlin
            .background(color.copy(alpha = fillAlpha + 0.10f * ring))
            .border(2.dp, color.copy(alpha = ring), shape)
```

替换为：

```kotlin
            .then(
                if (glass != null) {
                    Modifier.glassSurface(
                        shape = shape,
                        tint = color,
                        bodyAlpha = fillAlpha,
                        press = { press },
                        lift = { ring },
                    )
                } else {
                    Modifier.background(color.copy(alpha = fillAlpha + 0.10f * ring))
                },
            )
            .border(2.dp, color.copy(alpha = ring), shape)
```

新增 import：`com.zhijian.schedule.core.ui.glass.glassSurface`。

- [ ] **Step 6.2: 构建检查**

Run: `cmd //c ".tmp\run-tests.bat :app:compileDebugKotlin"`
Expected: BUILD SUCCESSFUL

---

### Task 7: 浮岛与 FAB 玻璃化

**Files:**
- Modify: `app/src/main/java/com/zhijian/schedule/navigation/IslandTabBar.kt:86-132`
- Modify: `app/src/main/java/com/zhijian/schedule/feature/schedule/ScheduleScreen.kt:175-201`

- [ ] **Step 7.1: IslandTabBar.kt 容器与指示胶囊**

`FloatingIslandBar` 内（第 92 行后）加：

```kotlin
    val preset = LocalCoursePreset.current
    val glass = preset.glass
    val isDark = LocalIsDark.current
```

容器（第 105-112 行）：

```kotlin
        Box(
            Modifier
                .fillMaxWidth()
                .height(IslandHeight)
                .shadow(6.dp, IslandShape)
                .clip(IslandShape)
                .background(colors.surface)
                .border(1.dp, colors.border, IslandShape),
        ) {
```

替换为：

```kotlin
        val paneAlpha = glass?.let { if (isDark) it.paneAlphaDark else it.paneAlphaLight }

        Box(
            Modifier
                .fillMaxWidth()
                .height(IslandHeight)
                .shadow(6.dp, IslandShape)
                .then(
                    if (glass != null) {
                        Modifier.glassSurface(
                            shape = IslandShape,
                            tint = Color.White,
                            bodyAlpha = paneAlpha ?: 0f,
                        )
                    } else {
                        Modifier
                            .clip(IslandShape)
                            .background(colors.surface)
                            .border(1.dp, colors.border, IslandShape)
                    },
                ),
        ) {
```

指示胶囊（第 114-122 行 `.clip(IslandShape).background(...)`）：

```kotlin
                .fillMaxHeight()
                .then(
                    if (glass != null) {
                        Modifier.glassSurface(
                            shape = IslandShape,
                            tint = colors.accent,
                            bodyAlpha = 0.20f,
                        )
                    } else {
                        Modifier
                            .clip(IslandShape)
                            .background(colors.accent.copy(alpha = 0.13f))
                    },
                ),
```

新增 import：

```kotlin
import androidx.compose.ui.graphics.Color
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.theme.LocalIsDark
import com.zhijian.schedule.core.ui.glass.glassSurface
```

- [ ] **Step 7.2: ScheduleScreen.kt FAB 改玻璃球**

`val fabScale by ...` 之后加：

```kotlin
    val glass = LocalCoursePreset.current.glass
    val isDarkFab = LocalIsDark.current
```

将（第 183-201 行）整个 `Box(Modifier.fillMaxSize()) { FloatingActionButton(...) }` 的 FAB 部分替换为：

```kotlin
    Box(Modifier.fillMaxSize()) {
        val fabShape = CircleShape
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = IslandContentPadding)
                .pressScale(fabSource, pressedScale = 0.9f)
                .graphicsLayer {
                    scaleX = fabScale
                    scaleY = fabScale
                    alpha = fabScale.coerceIn(0f, 1f)
                }
                .size(56.dp)
                .shadow(6.dp, fabShape)
                .then(
                    if (glass != null) {
                        Modifier.glassSurface(
                            shape = fabShape,
                            tint = colors.accent,
                            bodyAlpha = (if (isDarkFab) glass.paneAlphaDark else glass.paneAlphaLight) + 0.14f,
                        )
                    } else {
                        Modifier.clip(fabShape).background(colors.accent)
                    },
                )
                .clickable(
                    interactionSource = fabSource,
                    indication = LocalIndication.current,
                    onClick = { onAddCourse(null, null) },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Add, contentDescription = "添加课程", tint = colors.onAccent)
        }
    }
```

新增/确认 import：`androidx.compose.foundation.clickable`、`androidx.compose.foundation.LocalIndication`、`androidx.compose.foundation.background`、`androidx.compose.foundation.layout.size`、`androidx.compose.foundation.shape.CircleShape`、`androidx.compose.ui.draw.shadow`、`androidx.compose.ui.draw.clip`、`com.zhijian.schedule.core.theme.LocalCoursePreset`、`com.zhijian.schedule.core.theme.LocalIsDark`、`com.zhijian.schedule.core.ui.glass.glassSurface`。若 `FloatingActionButton` import 不再使用则移除。

- [ ] **Step 7.3: 构建检查**

Run: `cmd //c ".tmp\run-tests.bat :app:compileDebugKotlin"`
Expected: BUILD SUCCESSFUL

---

### Task 8: 今日页课程行玻璃化

**Files:**
- Modify: `app/src/main/java/com/zhijian/schedule/feature/today/TodayScreen.kt:436-461`

- [ ] **Step 8.1: 改造行卡片背景链**

`val ring = lift.coerceIn(0f, 1f)`（第 434 行）后加：

```kotlin
    val glass = LocalCoursePreset.current.glass
    val isDarkRow = LocalIsDark.current
```

将（第 447-454 行）：

```kotlin
            .background(
                lerp(
                    if (isCurrent) colors.surfaceVariant else colors.surface,
                    colors.surfaceVariant,
                    ring,
                ),
                shape,
            )
```

替换为：

```kotlin
            .then(
                if (glass != null) {
                    Modifier.glassSurface(
                        shape = shape,
                        tint = Color(item.course.color),
                        bodyAlpha = if (isDarkRow) glass.paneAlphaDark else glass.paneAlphaLight,
                        press = { press },
                        lift = { ring },
                    )
                } else {
                    Modifier.background(
                        lerp(
                            if (isCurrent) colors.surfaceVariant else colors.surface,
                            colors.surfaceVariant,
                            ring,
                        ),
                        shape,
                    )
                },
            )
```

`.border(2.dp, Color(item.course.color).copy(alpha = 0.9f * ring), shape)` 保持不变。

新增 import：`com.zhijian.schedule.core.theme.LocalCoursePreset`、`com.zhijian.schedule.core.theme.LocalIsDark`、`com.zhijian.schedule.core.ui.glass.glassSurface`。

- [ ] **Step 8.2: 构建检查**

Run: `cmd //c ".tmp\run-tests.bat :app:compileDebugKotlin"`
Expected: BUILD SUCCESSFUL

---

### Task 9: 真机验证（暗/亮两版 + 微调）

**Files:** 无代码变更（除按观感调 `GlassSpec` 数值）

- [ ] **Step 9.1: 构建 release 并安装**

```bash
cmd //c ".tmp\run-tests.bat :app:assembleRelease"
MSYS_NO_PATHCONV=1 adb install -r "D:/课程表/app/build/outputs/apk/release/app-release.apk"
```

- [ ] **Step 9.2: 切到液态玻璃主题（暗色）**

- `adb shell cmd uimode night yes` → 打开 App（`adb shell am start -n com.zhijian.schedule/.MainActivity`）→ 我的页 → 主题芯片选「液态玻璃」；
- 截图：`adb exec-out screencap -p > D:/课程表/.tmp/glass_dark.png`（Read 查看）；
- **必查 logcat shader 编译**：`adb logcat -d | grep -iE "shader|AGSL|RuntimeShader"`，若有编译错误按报错修 `GlassShaders`（AGSL 错误只在运行时抛出）。

检查项：极光背景可见且流动；课块边缘有折射彩边与高光；文字可读；浮岛为玻璃；FAB 玻璃球；按压课块有实时高光反馈。

- [ ] **Step 9.3: 切亮色验证**

`adb shell cmd uimode night no` → 重进 App → 截图 `glass_light.png`。检查浅冰极光与白玻璃观感、文字对比度。

- [ ] **Step 9.4: 观感微调**

按截图调整 `Presets.kt` 中 `GlassSpec`（bendDp、specular、paneAlpha、aurora 强度/色）与 `GlassShaders` 的 `uVignette`（GlassRuntime.configureAurora）；每轮微调后只需重装 release。检查二级页（设置/成绩）、弹层（周面板/课程详情）、横竖屏切换。

---

### Task 10: 降档预览与性能采样

- [ ] **Step 10.1: 强制回退档截图（API<33 机型观感）**

临时把 `GlassRuntime.forceFallbackForPreview` 置 `true` → `assembleRelease` + 安装 → 截图 `glass_fallback.png` → **还原为 `false` 重新构建**。

检查项：回退档下玻璃仍通透（半透明底 + 对角高光描边），无 shader 报错，动效正常。

- [ ] **Step 10.2: 帧统计**

课表页上下拖动 5 秒后：

```bash
adb shell dumpsys gfxinfo com.zhijian.schedule reset
# （操作 5 秒）
adb shell dumpsys gfxinfo com.zhijian.schedule | head -40
```

观察 Total frames / Janky frames 占比；若 janky > 5%：优先降 `uVignette` 系数、减少元素 shader 覆盖面（或把 `bendDp` 调小），仍不行则在 `GlassRuntime.supported` 追加更严格门控（如 `isLowRamDevice` 已有，可再加 `Build.VERSION.SDK_INT < 34` 时降档）。

- [ ] **Step 10.3: 回归检查**

切回「纸本暖白」「墨夜纯黑」：课块/浮岛/页面底色与改造前一致（玻璃分支零进入）；切「动态取色」正常；进出二级页、周面板、课程详情动效正常。

---

## Self-Review 结论

- **Spec coverage**：设计文档 §2 视觉（Task 1/3/4）、§3 架构（Task 4/5）、§4 性能（Task 4/10：30fps+生命周期+门控）、§5 动效适配（Task 6/7/8 复用 press/lift；Motion 零改动）、§6 数据模型（Task 1）、§7 覆盖清单（Task 5/6/7/8；弹层经 translucent surface token 自动适配）、§9 验证（Task 2/9/10）——全部有对应任务。
- **Type consistency**：`GlassSpec` 字段（bendDp/specular/paneAlpha*/aurora*）在 Task 1 定义，Task 3/4/7/8 使用一致；`glassSurface(shape, tint, bodyAlpha, press, lift)` 签名在 Task 4 定义，Task 6/7/8 调用一致；`GlassRuntime.updateElement/updateBackdrop`、`LocalAuroraTime`、`rememberAuroraClock` 命名前后一致。
- **占位符扫描**：无 TBD/TODO；所有代码步骤含完整代码或精确替换指令。
- **已知风险与备选**：AGSL `half` 精度/类型问题→全 float 已规避，编译错误走 logcat 反馈循环；`Density` 类型导入（compose 的 Density 在 `androidx.compose.ui.unit`）——Task 4 已给出修正路径；ModalBottomSheet 弹层玻璃感依赖 `surface` token 半透明——Task 9 真机确认，不达标则给三个 sheet 显式 `containerColor = colors.surface` 并加顶部高光。
