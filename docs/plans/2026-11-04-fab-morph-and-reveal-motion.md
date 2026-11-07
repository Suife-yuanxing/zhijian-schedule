# 加号形变与页面扩散转场 · 实施计划

> 日期：2026-11-04 · 设计：[2026-11-04-fab-morph-and-reveal-motion-design](../specs/2026-11-04-fab-morph-and-reveal-motion-design.md)

## 目标

1. 点「+」后加号顺时针转 45° 变成「×」；
2. 页面扩散曲线改为「由慢到快」，返回用它的严格时间镜像；
3. 空课块 / 空状态卡 / 加号三个入口统一为「从被点控件长出来」；
4. 补齐扩散路径缺失的 reduce-motion 分支与素墨主题的「不做旋转」约束；
5. **（第二版）页面沿加号方块展开**：边界起手就是方块的实测圆角矩形，之后逐帧等距外扩；
6. **（第二版）方块与页面展开同时缩小离场**：方块浮在裁剪层之上，与边界共用同一条曲线与时长；
7. **（第三版）退场改为「被吃掉」**：方形内缩 + 圆角朝半边化，与页面边界同族同向；三层按
   container transform 的 `fadeMode = OUT` 排序；方块颜色以一条 2dp 描边移交给页面边界。

## 修改边界

| 文件 | 改动 |
| --- | --- |
| `core/ui/Motion.kt` | 正向 (0.3,0,1,1)→(0.5,0,1,1)@400；反向镜像 (0,0,.7,1)→(0,0,.5,1)@340；新增 `FAB_MORPH_DEGREES` / `DUR_FAB_MORPH` / `SeedMotion` / `SeedShrink` / `SeedFade`；删除 `FAB_REVEAL_HANDOFF_MS` 与两个独立的离场时长。**第三版**：`SeedMotion.scaleOut` → `roundOut`（相似变换整条删掉） |
| `core/ui/FabMorph.kt` | 新增：转角↔进度互换（纯逻辑）；删除 `handoffWaitMillis` |
| `core/ui/RevealMath.kt` | 新增 `SeedShape` / `SeedBadge` / `RevealShape` / `dilationToCover` / `dilatedRound` / `seedHighlight`；保留换主题用的 `revealRadius`。**第三版**删 `seedScale`，加 `seedRoundness` / `seedInsetProgress` / `shrunkenRound` / `seedGlyphAlpha` / `seedEdgeAlpha`，`seedAlpha` 窗口改 0.18→0.62 |
| `core/ui/RevealState.kt` | 种子从矩形升级为 `RevealShape`（Circle / Seed）；新增 `seedExit` 进度与 `seed` 读出 |
| `core/ui/RevealHost.kt` | 裁剪改为按形状分支；新增 `SeedOverlay`（方块副本 + 离场）；驱动把两条进度写在同一帧回调。**第三版**：副本改为整屏画布 + `shrunkenRound`，先画边界橙色描边再画方块，字形与本体分开两档透明度 |
| `feature/schedule/ScheduleFab.kt` | 交出 `SeedShape`（含实测角半径与颜色副本）；拆 `FabMetrics` / `FabSurface` / `FabPlain` / `FabGlyph` |
| `feature/schedule/components/EmptyCellGeometry.kt` | 新增：列框 + 段内下标 → 格子矩形（纯逻辑） |
| `feature/schedule/components/WeekGridBands.kt` | 列框 `onGloballyPositioned`；回调交出 `SeedShape`；抽 `BandTimeAxis` |
| `feature/schedule/components/WeekGrid.kt` | `onEmptyTap` 签名交出 `SeedShape` |
| `feature/schedule/ScheduleScreen.kt` | `onAddCourse` 签名交出 `SeedShape`；拆 `EmptySchedulePane` / `WeekPager` / `ScheduleSheets` / `rememberWeekSync` |
| `feature/schedule/ScheduleEmptyState.kt` | 「添加课程」按钮交出自身轮廓（胶囊角半径） |
| `navigation/AppNavGraph.kt` | 三个入口统一走 `reveal.requestNavigation`；返回时触发 `requestFabReset` |
| `navigation/AppNavHostState.kt` | 新增 `fabResetTick` / `requestFabReset`；`fabOrigin` 改为 `seedOrigin: SeedShape?` |
| `core/theme/ThemePreset.kt` | 新增 `MotionProfile` 与 `ThemePreset.motion` |
| `core/theme/ThemePresets.kt` | 素墨 `motion = Still` |

**不在范围内**：加号改成展开菜单（speed dial）；编辑已有课程（课块 → 详情 → 编辑）的转场；
换主题扩散的形状与时长（仍是圆、仍是 450ms）。

## 任务拆解

- [x] T1 Motion 常量与推导注释（曲线 + 时长 + 形变三个 token）
- [x] T2 `FabMorph` 纯逻辑 + 单测（转角↔进度、无障碍阈值）
- [x] T3 `EmptyCellGeometry` 纯逻辑 + 单测（5 条）
- [x] T4 `ScheduleFab` 形变实现
- [x] T5 空课块原点采集与签名涟漪（Bands → Grid → Screen → NavGraph）
- [x] T6 空状态卡按钮交出轮廓，三个入口统一
- [x] T7 返回时 `fabResetTick` 触发 × → +
- [x] T8 `RevealHost` / `ScheduleFab` 接 reduce-motion
- [x] T9 `MotionProfile` 与素墨 `Still`
- [x] T10 更新 `RevealEasingTest` / `RevealReverseHandoffTest` 的解析值
- [x] T11 detekt / ktlint 通过（含把 `ScheduleFab` / `ScheduleScreen` / `BandBody` 拆到阈值内）
- [x] T12 `RevealMath` 增加种子几何（外扩距离 / 圆角同步 / 缩放 / 淡出）+ 单测（9 条）
- [x] T13 `RevealState` 升级为 `RevealShape` 两种口径，保留换主题的圆
- [x] T14 `RevealHost` 新增 `SeedOverlay`，方块副本盖在裁剪层之上
- [x] T15 删除 `FAB_REVEAL_HANDOFF_MS` 与 `handoffWaitMillis`（形变不再挡扩散）
- [x] T16 编排放进同一帧回调（progress 与 seedExit 共用一个 value）
- [x] T17 淡出起点从 0.8 调到 0.6（真机看到不透明小色块飘在新页面上）
- [x] T18 三个入口的矩形全部升级为 `SeedShape`（`fabOrigin` → `seedOrigin`）
- [x] T19 退场改用 `shrunkenRound`（内缩 + 圆形化），删除 `seedScale` 与 `SeedMotion.scaleOut`
- [x] T20 三层按 `fadeMode = OUT` 排序：字形 0.24→0.40、本体 0.18→0.62、描边 0→0.5 行程
- [x] T21 颜色移交：`SeedOverlay` 先画 2dp 橙色边界描边（路径 = 当帧 `boundaryPath`）
- [x] T22 单测替换：`seedExitScaleAndAlpha` → 5 条（接缝相等 / 同族圆化 / 四边内缩 / 素墨保形 / 三层顺序 / 颜色移交）
- [x] T23 真机复验（20x 连拍 + 墙钟拟合），九项全部通过
- [x] T24 修 `SeedFrameProbe` 泄漏：`stop()` 挪进 `finally`；`watch()` 包进 `LaunchedEffect(runId)`

## 验收

### 已执行（2026-11-05，本机 · 第三版）

| 命令 | 结果 |
| --- | --- |
| `:app:compileDebugKotlin` | 通过 |
| `:app:testDebugUnitTest`（全量） | **1039/1039 通过** |
| `:app:ktlintCheck` | 0 条 |
| `:app:detekt` | 0 条（新增 0、存量未增加） |
| `:app:assembleDebug` | 通过，app-debug.apk 47.87 MiB |
| `:app:installDebug`（真机） | 已安装到 3B6F6DE7EB6P9WV5 |

> 第二版当时记录为 1028/1028、`arch_check` 305 个 .kt / 53335 行；第三版新增 11 条单测
> （去掉 1 条 `seedExitScaleAndAlpha`、加 6 条几何与 1 条颜色移交，其余为既有用例）。
> T24 修完探针生命周期后重跑：compile 通过、1039/1039、ktlint 0、detekt 0。

### 真机验收（第二版，2026-11-04，PKG110 / 3B6F6DE7EB6P9WV5，debug 包）

> **以下是第二版（缩放离场）的结论，第三版尚未重新连拍** —— 见本节末尾的缺口说明。

在 `animator_duration_scale = 10` 下用设备端 `screencap` 连拍，逐帧确认：

| 项 | 结论 |
| --- | --- |
| 边界形状 | **圆角矩形**逐帧外扩，不是圆（`.tmp/s4/04..12.png`） |
| 方块存活 | 页面开始铺开时方块仍在原位且完整可见 |
| 同时离场 | 方块缩小与边界外扩**同时**发生，铺满那一帧方块已消失（`s4/09..12`） |
| 无二次动作 | 铺满之后没有任何残留运动 |
| 返回 | 编辑页按轮廓反向收回，**不出现第二个方块**（`.tmp/r4/03..06`） |
| 空课块 | 轮廓从被点那一格长出，编辑页预填对应星期 / 节次 |
| 素墨 | 不旋转、不缩放，只淡出 |
| 关动画 | `animator_duration_scale = 0` 时一步到位，无 400ms 擦除 |

### 真机验收（第三版，2026-11-05，PKG110 / 3B6F6DE7EB6P9WV5，debug 包）

`screencap` 单张约 400ms，60fps 连拍必然漏帧；把 `animator_duration_scale` 提到 20
（400ms → 8s）后漏帧降到可接受，再用设备端墙钟给每张图打时间戳反算进度。
坐标系拟合得 `D = 8036ms`、`t0 = -109ms`，残差 ≤ 0.002（进度单位），理论 8000ms ⇒ 缩放 20.09×。

| 项 | 结论 | 证据 |
| --- | --- | --- |
| 内缩 = 被吃掉（非等比缩小） | 通过 | 宽 144→140→134→122→108→92→78，四边同退，中心恒定；与内缩模型误差 ≤ 1.0px |
| 圆角随内缩同步变大 | 通过 | 拟合 r/w 0.322→0.481，模型误差 ≤ 0.018，末段收敛 0.5（正圆） |
| 边界是圆角矩形等距外扩 | 通过 | 外扩 151→219→289→375→475→580→694px，斜向半径 1:1 同增 |
| 橙色描边颜色移交 | 通过 | 描边 R−B 38→63→102→110(稳)→91；厚度 6~7px ≈ 2dp@3x |
| 第 0 帧无多余描边 | 通过 | f0/f1 左侧逐像素扫描零暖色命中 |
| 三层先后（字形先于本体） | 通过 | f2 起主轴切到对角（×）；本体在 p≈0.37 仍 0.585 |
| 素墨退化 | 通过 | 方块 144×144 恒定、中心颜色全程不变，p≈0.58 才开始淡；无转角 |
| 返回不出现第二个方块 | 通过 | 20 张反向连拍，FAB 格橙色像素恒为 0 |
| 关动画一步到位 | 通过 | scale=0 下 2s 后已在编辑页 |

证据帧：`.tmp/v3/b8/v8/f0..f23.png`（正向，20x）、`.tmp/v3/b9/v9/`（反向）、
`.tmp/v3/b10/v10/`（素墨）、`.tmp/v3/eat.png`（放大对照）、`.tmp/v3/zoom_e.png`（含描边）。

### 未执行（据实标注）

- **未做逐帧时间戳对齐**：连拍是「抓得到就看」的定性证据，没有记录每帧的
  `Choreographer` 时间戳，因此「两条进度是否严格同帧」由代码结构（同一个 `animate` 回调）
  保证，不是实测确认。
- **未压测低端机取帧耗时**：`RevealHost` 取帧超时 400ms 与新时长同量级。
- **未采样 60Hz / 120Hz 差异**：本轮统一在 10x 缩放下拍摄。
- **未在素墨主题下复验「同时离场」**：素墨路径只改了 `SeedFade`（不缩放），
  但「同时」这条时间线未单独在该主题下连拍确认。
- ~~第三版未做真机连拍~~ → **已于 2026-11-05 完成**，见下节「真机验收（第三版）」。
- **未定位的掉帧**：探针在 1x 下报 `maxGapMs` 177~288ms，固定落在进度 0.01~0.34（旧帧铺底那段）。
  既不属第三版改动，也没有对比基线（第二版未跑过探针），本轮只记录不修。
  下一步应看 `dumpsys gfxinfo` 定位该段的帧耗时构成。
- **未在 120Hz 下复测**：本轮所有连拍都在 90Hz（中位帧间隔 11.1ms）下完成。
