# 果冻胶囊 · 动效规范与灵感调研

日期：2026-10-28
用途：为「页面切换胶囊」的果冻效果提供设计依据与真机验证数据。
结论全部落到了代码里（`core/ui/island/IslandIndicator.kt` 的 `islandJellyWave`、
`navigation/IslandTabBar.kt` 的 `rememberJellyTravel`），本文留作参数溯源。

## 1. 调研口径

桌面调研，只取「能做规范、能被单测钉住」的部分，逐条标证据等级：

- **【规范】**：官方设计文档 / 官方工程文档里的明示规则；
- **【样本】**：实现级参考（库、教程、可运行的实例代码）；
- **【推导】**：本项目的数学推导与逐帧积分复核，非外部出处；
- **【实测】**：本机一加 PKG110 / Android 16 上的逐帧采样结果。

检索面：Apple HIG（motion / reduce motion）、Material 3 与 M3 Expressive（easing-duration
tokens、shape morph、spring 物理）、Nielsen Norman Group（动画时长与频率）、
迪士尼十二条原则（squash & stretch）、Framer Motion（`layoutId` 共享布局）、
以及「Magic Navigation Menu Indicator」这一族的社区实作。

## 2. 需求校准：果冻发生在**移动途中**

首版实现把果冻做成了「滑到位置之后再原地回弹 400ms」。真机上看是
「滑过去 → 站定 → 蹦两下」——那是两件事，不是果冻。

要求的效果是：**移动的时候先拉长，然后回收**。也就是说拉长与回收是同一段位移的两半，
抵达那一刻形状已经归位。这一条决定了整个设计：形变不能由速度驱动（速度在落位前就归零，
形变必然跟着归零），必须由**位移进度**驱动。

## 3. 六条能直接落地的规范

### 3.1 「越常见的动效，越要短、越要克制」【规范 · NN/g】

切 tab 是全 App 最高频的交互之一，目标区间 **100~200ms**；装饰性、低频的重演出可以更慢。
这一条决定了**位移**那一半的取值：`Motion.FluidSlide` = ζ 0.9 / k 600，落位约 217ms，
过冲 0.2%（肉眼读作「干净地停住」）。

### 3.2 高频操作不要靠过冲表达「弹性」【规范 + 推导】

旧实现用 ζ 0.72 让位移过冲 3.8%，观感是「整条底栏在抖」。M3 与 NN/g 都指向同一个结论：
**位移负责准，形变负责活**。所以弹性不进位移弹簧，而是全部交给形变通道。

### 3.3 squash & stretch 是「形变守恒」，不是「变大变小」【规范 · 迪士尼第一条】

标准表述：物体在运动方向拉长、垂直方向压扁；被挤压时反过来（横短竖长）。
关键在于**两个轴同时、反向**地变化，而且近似保面积。
落到胶囊上：赶路时横长竖扁；到站收住时横短竖高。

### 3.4 follow-through 与 anticipation 都发生在**行程内部**【规范 · 动画十二原则】

一次位移不该只有「起步—停止」两个状态。拉长是 anticipation（蓄势出发），
回收是 follow-through（收势停住）。两者都必须落在位移的时间窗里，
落到位移窗之外就成了独立动画——那正是首版失败的地方。

### 3.5 Reduce Motion 是硬要求，而且要到**每一拍**都生效【规范】

- WCAG 2.3.3（AAA）把「由交互触发的运动」列为需要用户可控；
- Apple HIG / tvOS ACCESS-04：「Parallax effects and other animations must be reduced or
  disabled when the user enables Reduce Motion」；
- Android 的统一开关是 `Settings.Global.ANIMATOR_DURATION_SCALE == 0`
  （开发者选项 Animator duration scale 关掉、无障碍「移除动画」都会写它）。

本项目由 `rememberMotionAllowed()` / `rememberAnimationsDisabled()` 统一读；
关掉动画时 `rememberJellyTravel` 直接 `snapTo` 落位，形变为原形。

### 3.6 弹簧参数该用「弹一下多少 + 多久」描述，而不是 mass/stiffness/damping【规范 · Apple WWDC23】

WWDC23《Animate with springs》把 spring 拆成 *perceptual duration* 与 *bounce* 两个旋钮；
Figma 的 spring 面板同样只给 bounce + duration。理由是 mass/stiffness/damping 三者耦合，
调一个必然牵动另外两个。

本项目的对应做法：位移仍用 spring（有明确终值，弹簧语义天然吻合），
**形变用「两条各自半程的正弦瓣」**——幅度与相位分开，各管一头，见 §4。

## 4. 波形推导【推导】

### 4.1 形式

```
w(u) = LEAD · sin²(π · u/S)  −  TRAIL · sin²(π · (u−S)/(1−S)),   u ∈ [0,1], S = 0.5
```

`w` 是**带符号的拉伸单位**：正 = 比静止更宽（拉长赶路），负 = 比静止更窄（到站回收）。
`LEAD = 1.0`、`TRAIL = 0.55`。

### 4.2 为什么是两条瓣而不是一条正弦

单条 `sin(2π·u)` 的正峰在 u=0.25、负谷在 u=0.75，位置对，但两峰**高度被锁死相等**（1 与 −1）。
果冻需要「拉得开、收得轻」：拉长是主戏，回收只是把拉出去的量收回来的余势。
拆成两条各占半程的瓣之后，幅度可以分别给。

### 4.3 为什么用平方

一次幂 `sin(π·x)` 在分段边界（u=0.5）斜率不为零，两段拼起来会在中点留一个折角，
读作「拉伸突然换挡」。平方后每段两端导数都是 0，接缝天然平滑（逐点复核差值 ±0.002）。

### 4.4 关键取值

| 进度 | 波形 | 88×48dp 胶囊上的几何 |
| --- | --- | --- |
| 0 | 0 | 88.0 × 48.0（起步原形） |
| 0.10 | +0.345 | 92.9 × 46.2 |
| 0.25 | **+1.000** | **102.1 × 42.7**（拉长峰值 +16% / −11%） |
| 0.40 | +0.345 | 92.9 × 46.2 |
| 0.50 | 0 | 88.0 × 48.0（中点归位） |
| 0.60 | −0.190 | 85.3 × 49.0 |
| 0.75 | **−0.550** | **80.3 × 50.9**（回收谷值 −8.8% / +6%） |
| 0.90 | −0.190 | 85.3 × 49.0 |
| 1.00 | 0 | 88.0 × 48.0（抵达即归位） |

两个峰都在**行程之内**，抵达那一帧形状已经归位——这是「不是停下来再晃」的几何证明，
也是 `IslandJellyWaveTest` 里 `stretches before it retracts, both inside the trip` 钉住的东西。

## 5. 与既有实现的契约

| 通道 | 驱动量 | 单位 | 变化 |
| --- | --- | --- | --- |
| 位移 | `slide`（Animatable） | tab 下标 | `Motion.FluidSlide`，217ms，过冲 0.2% |
| 形变 | `progress = (slide−from)/(to−from)` | 0..1 进度 | 带符号 −0.55..+1.0 |

关键：**形变不另起一条时间线**。进度是从位移的实时值反算的，
所以「同一条时间线」不是靠对齐来保证，而是结构上只有一条。
方向同样由 `from`/`to` 给出（`sign(to−from)`），不读 `slide.velocity`——
停稳瞬间速度归零、符号会丢，而一次行程的方向从头到尾不变。

## 6. 真机实测【实测】

设备：一加 PKG110 / Android 16 / 1264×2780。方法：`animator_duration_scale = 10` 慢放
（Compose 动画随该开关等比放慢），`screencap` 逐帧采样，按胶囊填充色
`rgb(206,217,244)` 匹配包围盒。

| 帧 | 相对时间 | 宽度 | 高度 | 中心 x | 判读 |
| --- | --- | --- | --- | --- | --- |
| 5 | 923ms | 312 | 125 | 847.5 | 起步，形变刚起 |
| 6 | 1146ms | **357** | 141 | 800.0 | **拉长峰值 +15.5%**，仍在移动 |
| 7 | 1365ms | 320 | 164 | 759.5 | 回收中 |
| 8 | 1584ms | 299 | 172 | 721.0 | 继续回收 |
| 9 | 1815ms | **282** | **178** | 687.5 | **回收谷值 −8.7%、最高 +6%** |
| 10 | 2056ms | 293 | 174 | 664.0 | 贴回原形 |
| 12 | 2560ms | 308 | 168 | 639.5 | 接近静止 |
| 13+ | 2799ms→ | 309 | 168 | 631.0 | 静止（原形） |

三条结论：

1. **拉长与回收都发生在位移途中**：宽度峰（帧 6，x=800）与宽度谷（帧 9，x=687.5）
   都落在 x 从 847.5 走到 631 的区间里，抵达时（帧 13）已经是 309 的原形。
2. **数值与推导吻合**：实测峰 +15.5% / 谷 −8.7%，推导值 +16.0% / −8.8%。
3. **体积守恒成立**：宽度收窄时高度同步变大（282×178 ↔ 309×168），不是等比缩放。

按 `animator_duration_scale=10` 折算，整段行程约 164ms，与 `Motion.FluidSlide` 的 217ms 同档。

## 7. 连点与打断

快速连点会取消上一段 coroutine。`rememberJellyTravel` 每次新行程都把 `from` 重记为
**当前位置**、`to` 重记为目标，于是进度从 0 重新起算，形变也从原形重新出发——
不会驮着半截形变上路。首帧用 `armed` 标记吞掉，避免进应用第一帧凭空涨一下。

## 8. 检索到的参考样本（延伸阅读）

- Apple WWDC23《Animate with springs》——perceptual duration 与 bounce 两参数模型；
- Framer Motion `layoutId` 共享布局动画——「同一个元素在两组布局间插值」的范式，
  本项目的胶囊等价物（不换 DOM，只换几何）；
- 「Magic Navigation Menu Indicator」社区实作——小球滑到 tab 下面并「膨」一下，
  多数版本只做位移 + 缩放，没有体积守恒，拉长与回收也不是同一条曲线；
- Josh Comeau《Squash and Stretch》——箭头拉长的实例强调「变长的同时变细」，
  与本项目把 grow 与 squash 绑定在同一个 `jelly` 上同源；
- M3 Expressive shape morph——官方把「形变」作为独立的状态通道（shape as a state channel），
  与这里「同一个胶囊用形变表达赶路与到站」的判断一致。

## 9. 未取到的部分（据实标注）

- M3 官方 `m3.material.io/styles/motion/easing-and-duration` 与 `shape/shape-morph` 两页
  未能取到正文（页面为前端渲染，抓取返回空）——文中关于 M3 token 的表述取自
  `material-components-android` 的 `docs/theming/Motion.md`（项目内既有注释已引用该来源）；
- Apple HIG 的 motion 页面同样只取到索引页，`Reduce Motion` 条文依据 tvOS ACCESS-04
  与 WCAG 2.3.3 的公开文本，未逐字引用 HIG 正文。
