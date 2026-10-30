# 水银镜面主题 · 光效与光路

日期：2026-10-29 ｜ 状态：真机验证通过（设备 API 36，1080×2376）

## 1. 一句话

把「液态玻璃」整套材质换成**水银镜面**：玻璃靠「透明 + 边缘折射」定义自己，金属靠「不透 + 反射方向 + 各向异性高光」定义自己——两者物理互斥，所以这不是换色，是把材质模型整个换掉。

## 2. 光路模型（本轮打磨的核心）

### 2.1 从「球冠」到「平板 + 倒角」

第一版用的是球冠法线（中心朝前、四周倒向侧面），结果整块面每个像素都反射到不同环境，颜色被洗成脏铁——真机截图一眼可见。

现在的模型：

| 区域 | 法线 | 视觉表现 |
| --- | --- | --- |
| 平面区（距边 > 7px） | 几乎恒定朝前（`qn * 0.10`） | 保住材质本色，只有微量环境反照 |
| 倒角带（距边 0–7px） | 沿外法线急速外翻（`pow(bevel, 0.62) * 1.55`） | 一圈真正的镜面斜面 |
| 刃光（距边 0–2.2px） | — | 一道极窄亮线，金属读作「薄板」的关键 |
| 接触阴影（距边 0–3.2px） | — | 贴边压暗 42%，金属「压」在桌面上 |

### 2.2 光源：加了一盏长条柔光箱

点光源只能打出圆斑（读作塑料）；**条形光才会让金属表面有方向**。环境里新增一盏条形柔光箱（`boxN = (0.12, 0.62, 0.78)`，宽 0.62 / 高 0.14），在金属上形成一道横向亮带——这是 FAB 与浮岛上那道亮光的来源。

环境构成：天顶冷灰 + 地面暖黑 + 主灯（左上）+ 轮廓灯（右后）+ 环形柔光带（弱）+ **条形柔光箱（强）**。

### 2.3 合成链路

```
面心 = 哑光本体 × (0.88 + 0.30·envLuma) + env×0.22·(0.4 + 0.6·chrome)
倒角 = env × F0 + 拉丝高光 + 虹彩
刃光 = 最外 2.2px × 光源方向权重 × 1.15
触摸 = 亮核(exp·30) + 柔晕(exp·4.2)
合成 = mix(面心, 倒角, chrome·0.42 + bevel^1.25·chrome·2.1)
辉光 = 亮部外溢（luma > 0.55 起）
颗粒 = 0.035 白噪声（金属最怕「塑料光」）
```

面心从「全哑光」改成「也吃 42% 镜面」——这是「抛光平板」与「描了一圈边」的分界。

## 3. 踩过的坑（都在真机上确认过）

### 3.1 AGSL 里不能有中文注释

着色器源码里的 `// 中文说明` 会让 `RuntimeShader` 构造失败，而失败是**静默**的：元件直接走降档分支，看起来「像是没生效」。

现在的纪律：着色器内**只允许 ASCII 注释**（或干脆不写），`MetalRuntime.verify()` 会在首次编译时把失败打进 logcat。

### 3.2 拉丝频率必须按像素算

第一版 `sin(q.y * 420.0)` 在高分屏上混叠成「布纹/水波」，整块金属读作压花塑料。改成 `sin(q.y * 1.9)` 后才是细密拉丝。

### 3.3 选中珠太亮会吃掉文字

金属珠的球面高光会把压在上面的 tab 文字盖住（真机抓到过）。现在选中珠 `chromeOverride = 0.55`（低于浮岛本体的 0.62），文字改为深墨 `#05070B`，对比度 ≥ 4.5:1（有单测钉住）。

### 3.4 未选中文字在暗金属上看不清

`muted` 求解的 `under` 不能用 `surface`——金属面是着色器算出来的，暗色下实测落在 `#22262F` 附近。取错会让未选中 tab 读不出来。

## 4. 改动范围

**新增**

| 文件 | 职责 |
| --- | --- |
| `core/ui/metal/MetalShaders.kt` | 三个 AGSL：元件表面 / 屏幕空间反射 / 全屏环境 |
| `core/ui/metal/MetalRuntime.kt` | 能力判定 + shader 工厂 + uniform 配置 + 编译校验 |
| `core/ui/metal/MetalSurface.kt` | `Modifier.metalSurface`（含降档）与触摸状态 |
| `core/ui/metal/MetalMirrorSurface.kt` | 浮岛的屏幕空间反射（镜像 glassBackdropSurface） |
| `core/ui/metal/MetalBackdrop.kt` | 内容图层录制（供反射采样） |
| `core/ui/metal/MetalAmbient.kt` | 全屏环境底 |
| `core/ui/metal/MetalCard.kt` | 卡面统一入口（SURFACE / RAISED / INSET 三档） |
| `core/ui/metal/MetalFields.kt` | 表单控件外观 |
| `core/ui/metal/MetalClock.kt` | 逐帧时钟 + `LocalMetalClock` / `LocalMetalGyro` |
| `core/ui/metal/MetalGyro.kt` | 倾角信号（金属高光随重力滑动） |
| `core/theme/MetalSpec.kt` | 材质参数数据类 |
| `core/ui/metal/MetalThemeTest.kt` | 契约测试（6 例） |

**删除**：`core/ui/glass/` 整个包（6 个文件）、`GlassSpec`、`ThemeIds.GLASS`、`ThemePresets.glass`、`GlassThemeTest`

**修改**：`ThemePreset` / `ThemePresets`（新增 chrome 预设并放到原 glass 位置）、`AppRoot`（时钟/陀螺仪/环境/反射录制）、`IslandTabBar` / `IslandIndicator`（金属浮岛与选中珠）、`IslandTokens`（金属档配色求解）、`CardTiltStyle`（金属倾动参数）、`FeedbackButtons`（`TapButton` / `TapIconButton` 自动走金属）、`SecondaryTopBar`、`CourseBlock`、`ScheduleFab`、`TodayClassCards`、`ProfileSemesterOverview`、`ProfileEntryCards`、`SettingsSections`，以及 8 个文件的 `OutlinedTextField` 配色注入。

## 5. 验证

| 项 | 结果 |
| --- | --- |
| 单元测试 | 579 例全绿（含新增 `MetalThemeTest` 6 例、改造后 `IslandContrastTest` 8 例） |
| 构建 | `assembleRelease` 通过 |
| 真机 | API 36 设备安装运行，着色器编译 OK（`surface` / `ssr` / `ambient`） |
| 回退 | 非 A13 / 低端机走降档支路（哑光底 + 倒角亮圈 + 斜向拉丝），结构一致 |
| 玻璃残留 | 主源码 `grep glass` = 0 |

## 6. 未做

- 浮岛的屏幕空间反射在快速滚动时有一帧延迟（录制与绘制同拍，30fps 尚未提高）；
- 亮色模式下金属对比度偏柔（哑光面占比大），若要更硬可以调高 `MetalSpec.chrome`；
- 陀螺仪高光在模拟器上无效，只在真机可看。
