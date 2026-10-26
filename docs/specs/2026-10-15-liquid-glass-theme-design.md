# 液态玻璃主题 · 设计文档

日期：2026-10-15
状态：已确认（用户选择：全局玻璃语言 + 自适应双档 + 深邃极光）

## 1. 背景与目标

为「纸间课表」新增第 6 套主题「液态玻璃」。核心约束（用户需求）：

- **不要毛玻璃**：禁止模糊（blur），玻璃是"清透 + 折射 + 高光"，不是磨砂；
- **实时渲染但控制性能**：材质随交互实时变化，但不做逐帧截屏捕获；
- **适配原有动效**：复用现有 Motion 体系，不新增动画通道、不改动效代码；
- 全局玻璃语言：课块、浮岛、弹层、FAB 全部玻璃化（用户选择）；
- 自适应双档：全机型光影玻璃；A13+ 且非低端机自动叠加真折射（用户选择）；
- 基调：暗=深邃极光（旗舰），亮=浅冰极光（用户选择）。

## 2. 视觉定位

### 2.1 极光背景层

- 深靛底 `#0A0E28`（亮色版：浅冰底 `#F2F6FD`）+ 3~4 团缓慢漂移的彩色光雾：
  - 暗：青 `#35E0E8`、蓝 `#3D6BFF`、紫 `#8B5CFF`（可加粉 `#FF5CA8` 点缀，强度低）；
  - 亮：青 `#9BE8F5`、蓝 `#A9C4FF`、紫 `#C9B8FF`、粉 `#FFC7E2`；
- 漂移速度极慢（周期 2~5 分钟）。光雾是**解析式函数**（若干高斯球），不是位图。

### 2.2 玻璃材质

- 主体**清透**：alpha 很低（暗 0.06~0.16，亮 0.30~0.45），背后内容清晰可见（不是模糊）；
- 边缘带（约 12~16dp）：折射位移采样极光场 + R/B 通道微偏移（色散彩边）+ 镜面高光弧（光源左上）；
- 按压/选中时折射强度与高光同步变化（实时）；
- 深色下玻璃本体极淡、靠边缘高光与彩边定义形态；浅色下白玻璃感。

### 2.3 课程方块

- 保持「透明填充 + 左色条」的全局课块规则；玻璃版填充改为玻璃材质，填充色少量混入课程色相（约 18%），保留色相辨识；
- 选中 lift：亮弧 + 折射增强；按压 press：折射指数轻微变化。

## 3. 渲染架构（三层）

```
AppRoot
 ├─ AuroraBackdrop（全屏极光层，位于 Scaffold 之下）
 └─ Scaffold(containerColor = 透明) → NavHost → 各页面（页面底色在玻璃主题下透明）
      └─ 各玻璃元素（glassSurface Modifier）
```

### 3.1 极光场（解析式，双实现共享同一数学）

- **共同常量**：`AuroraMotion`（Kotlin）定义每个光雾的基位、漂移幅度/频率/相位，玻璃色与强度来自 `GlassSpec`；
- **A13+**：单个全屏 AGSL RuntimeShader 绘制；漂移公式在 GLSL 中重写但**常量由 Kotlin 注入**（生成 AGSL 源码字符串时插值），保证与回退路径数值一致；
- **A13 以下**：Kotlin 每帧算光雾圆心 → 4~5 个 `Brush.radialGradient` drawRect（线性衰减近似高斯），全屏 30fps 成本很低。

### 3.2 玻璃元素（`Modifier.glassSurface()`）

每个玻璃元素在 draw 阶段：

1. 从 `onGloballyPositioned.localToRoot()` 拿元素在 Compose 根坐标中的原点（px）；
2. draw 一个本地坐标 `(0,0,size)` 的圆角矩形，shader 内 `FragCoord` 即元素本地坐标；
3. `screenPos = uOrigin + FragCoord`，在边缘带内按 SDF 法线位移采样极光场解析函数；
4. 输出 color+alpha：主体低 alpha（清透）、边缘带高 alpha（折射彩边 + 高光弧），由 Skia 与背后真实内容 alpha 混合。

**坐标空间约定**：AGSL `FragCoord` 为 shader 局部坐标（skia 以逆 CTM 映射），元素本地绘制发在 `(0,0)` 起的坐标，因此 `FragCoord ∈ [0,size]`，与元素 origin 相加即屏幕坐标。缩放（按压 0.94、入场放大）由 RenderNode 合成层承担，不影响该约定（瞬态微小误差不可见）。

**AGSL 关键函数**（共享源码）：

- `sdRoundRect`（圆角矩形 SDF）；
- 法线 = SDF 有限差分；
- `aurora(p, t)`：基色垂直渐变 + N 个高斯球（中心 = 基位 + 漂移(t)）；
- 边缘带：`edgeT = 1 - clamp(distIn / band, 0, 1)`；
- 折射位移：`samplePos = screenPos + n * bend * edgeT²`，`bend ≈ 10~16px`；
- 色散：R 通道采样 `+0.6*bend*edgeT²`、B 通道 `-0.6*...`；
- 高光：`spec = edgeT³ * max(dot(n, L), 0)² * specAlpha`（L = 固定光源方向，可被按压增强）；
- 输出 alpha：`body + (edgeMax - body) * edgeT`。

### 3.3 回退档（API < 33 或 `isLowRamDevice`）

无 shader 的「光影玻璃」：

1. 玻璃底：半透明 tint `drawRoundRect`；
2. 顶左高光：1dp 内描边，linearGradient 白 0.5 → 白 0.06（对角）；
3. 底部内阴影：下半描边，黑 0.12；
4. 按压/选中：高光 alpha 与底色 alpha 随状态变化（仍是实时的）。

成本：每元素 3~4 次 drawRoundRect，无模糊无 shader。

玻璃元素 shader 实例策略：每个元素 remember 自己的 `RuntimeShader` 实例（同一源码由系统按源码字符串缓存编译），draw 阶段更新 uniform 后立即绘制，避免共享实例的 uniform 竞态；屏幕上玻璃元素上限约 40 个，实例创建成本可接受。

## 4. 性能控制清单

| 项 | 策略 |
|---|---|
| 模糊 | 全局禁用（架构级红线） |
| 截屏捕获 | 无（折射采样解析式极光场，不捕获背景） |
| 帧率 | 极光时钟量化 30fps（`withFrameNanos` 累积 ≥33ms 才更新状态） |
| 生命周期 | 非 RESUMED 暂停时钟，零后台开销 |
| 能力门控 | `SDK >= 33 && !isLowRamDevice` 才走 shader 档 |
| 绘制范围 | 仅可见元素绘制；draw 阶段读状态（只触发重绘不触发布局/重组） |
| 状态读取 | 时钟状态在 draw 阶段读取，避免每帧重组 |

## 5. 动效适配清单（不改动效代码）

| 动效 | 适配方式 |
|---|---|
| 课块级联入场 cascadeIn | 零改动；玻璃绘制在原有 graphicsLayer 内，淡入缩放照常 |
| 按压缩放 press 0.94 | press 值作为 uniform 驱动折射/高光（实时） |
| 选中 lift 弹簧 | lift 值驱动亮弧与折射增强 |
| 浮岛果冻弹跳 | 图标动画零改动；选中胶囊改玻璃液滴（滑动沿用 FluidSlide） |
| 周面板 StaggerIn/PopSpring | 零改动；选中格改玻璃液滴 |
| 顶栏周标题/FAB 弹入 | 零改动，仅换材质 |

## 6. 数据模型

```kotlin
// ThemePreset.kt 新增
data class GlassSpec(
    val bodyAlphaLight: Float,   // 玻璃主体 alpha（亮）
    val bodyAlphaDark: Float,    // 玻璃主体 alpha（暗）
    val bendStrength: Float,     // 折射强度（px，按 density 缩放）
    val specularAlpha: Float,    // 高光强度
    val auroraBaseLight: Color, val auroraBaseDark: Color,
    val auroraBlobsLight: List<Color>, val auroraBlobsDark: List<Color>,
)
// ThemePreset 增加 val glass: GlassSpec? = null（仅 glass 预设非空）
// ThemeIds 增加 const val GLASS = "glass"
```

- `ThemePresets.glass`：name「液态玻璃」，注册进 `all`（第 6 位）；
- 浅/深两套 CourseColors：亮=浅冰（bg `#F2F6FD`、text 深蓝 `#17203A`、accent 冰蓝）、暗=深邃极光（bg `#0A0E28`、text 浅蓝白、accent 青蓝）；
- coursePalette：极光系宝石色（冰蓝/青/紫/粉/薄荷等），blockStyle 保持「透明填充 + 左色条」；
- radius：block 14 / card 20 / sheet 26；typography 无衬线。

## 7. 覆盖范围与文件清单

**新增**
- `core/ui/glass/AuroraField.kt`：极光场纯函数（Kotlin 侧：常量、漂移公式、blob 列表）+ 单测
- `core/ui/glass/GlassShaders.kt`：AGSL 源码生成（共享裸函数 + 常量注入）+ 能力判定 `supportsGlassShader()`
- `core/ui/glass/AuroraBackdrop.kt`：全屏背景 Composable（shader/渐变双档）+ 30fps 生命周期时钟
- `core/ui/glass/GlassSurface.kt`：`Modifier.glassSurface(shape, tint, spec, pressProvider, liftProvider)`

**修改**
- `core/theme/ThemePreset.kt`、`Presets.kt`：GlassSpec、glass 预设、注册
- `navigation/AppRoot.kt`：AuroraBackdrop + Scaffold 透明
- `navigation/IslandTabBar.kt`：浮岛容器与指示胶囊玻璃化
- `feature/schedule/components/CourseBlock.kt`：玻璃课块
- `feature/schedule/components/WeekGrid.kt`：网格线与今日底色适配（用预设 token，微调）
- `feature/schedule/ScheduleScreen.kt`：FAB 玻璃化
- `feature/schedule/WeekPickerSheet.kt`、`DayDetailSheet.kt`：弹层玻璃化（容器色 + 顶部高光）
- 二级页页底透明化（玻璃主题下）：`SecondaryTopBar.kt`、`GradesScreen.kt`、`SettingsScreen.kt`、`ImporterScreen.kt`、`JwImportScreen.kt`、`CourseEditorScreen.kt`
- 小部件不动（继承预设；颜色保持不透明可读）

**不改**
- `Motion.kt`、全部动效实现、`CapsuleNotifier`、WidgetData/widgets、其余 5 套主题数据。

## 8. 降级与兼容

- 非玻璃主题：`preset.glass == null` → 所有玻璃分支不进入，零回归；
- 玻璃主题在旧机/低端机：渲染降为光影档，视觉语言一致；
- 冷启动 `windowBackground` 仍为纸色（既有行为，所有主题一致，不做特例）；
- 小部件：玻璃预设 bg 保持不透明深色，课程色保持高饱和，可读性不受影响。

## 9. 测试与验证

1. **单测**：`AuroraFieldTest`（漂移确定性、幅度边界、blob 数量与 preset 一致性）；
2. **构建**：`.tmp\run-tests.bat :app:assembleRelease`；
3. **真机验证**（release 安装）：
   - 截图：暗色课表、亮色课表（切 darkMode）、浮岛、周面板、FAB、二级页；
   - 强制回退档预览（临时降级开关构建一次截图后还原）；
   - `dumpsys gfxinfo framestats`：滚动课表，观察 janky 帧占比；
4. **回归**：切回纸本暖白等主题，确认视觉零变化；进出二级页、弹层、周切换动效正常。

## 10. 非目标

- 不做 Apple 像素级复刻；不做逐像素真实背景捕获折射；不做模糊/磨砂；不做玻璃主题独立设置页；不引入第三方库。

## 11. 修订（2026-10-15 · 真机反馈后，已完成）

按真机反馈调整方向：

- **取消彩色极光背景**（AI 感过强）：背景改为纯色（暗＝石墨黑 `#0E0F13`、亮＝`#F6F7F9`）；极光层文件（AuroraBackdrop/AuroraField）删除；
- **玻璃范围收窄**：液态玻璃只保留在底部导航浮岛（容器 + 选中胶囊）；课块/今日行/FAB/弹层/页面透明化全部回退到常规渲染；
- **材质改为「清水玻璃」**：高透明主体（薄膜着色）+ 白色流动光带（两条错速斜向软带，实时流动）+ 呼吸式上沿高光 / 下沿液面波纹；中途修复「高光色跟随 tint → 深色主题下高光发暗」的合成 bug（主体色与高光色解耦，高光恒白）；
- **保留**：A13+ AGSL 实时渲染、旧机渐变光影档（细高光轮廓 + 滑动光带）、30fps 量化 + 生命周期暂停、零模糊零捕获。

真机验证：暗/亮玻璃浮岛 ✓、流动动画（间隔 3s 两帧 63% 像素变化）✓、降档预览 ✓、帧统计 janky 0.67%（P90 15ms）✓、其余 5 套主题零回归 ✓。
