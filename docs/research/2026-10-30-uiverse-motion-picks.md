# uiverse.io 动效选型：对「课程表」可用的四个方案

- 日期：2026-10-30
- 目标项目：`D:\课程表`（Kotlin + Jetpack Compose，已有 `Motion.kt` 全局动效语言）
- 来源：uiverse.io（MIT，CSS/Tailwind 片段）
- 说明：uiverse 产出的是 Web CSS，**不能直接复制**。本文只取「动效参数与手法」，翻译为 Compose 实现。

## 0. 项目现状（选型依据）

实测 grep 得出动效分布：

| 文件 | 动效相关命中 | 角色 |
| --- | --- | --- |
| AppNavGraph.kt | 74 | 导航转场 |
| AppTransitions.kt | 36 | 页面切换动效 |
| Motion.kt | 30 | 全局时长/缓动常量 |
| IslandTabItem.kt | 19 | 灵动岛标签 |
| JellySwitch.kt | 17 | 果冻开关 |
| WeekCell.kt | 14 | 周视图单元格 |
| CardTilt.kt | 13 | 卡片 3D 倾斜 |

交互热点（onClick / expanded / loading 等）：CourseEditorScreen 37、OcrDraftEditSheet 17、ProfilePreferenceSections 16、GradesScreen 15、IslandTabItem 15、FeedbackButtons 15。

结论：**骨架动效已齐备，缺的是「局部微反馈」层**——按钮按下、状态切换、加载等待、条目勾选。

## 1. 多个同心圆涟漪扩散（Loader）

来源：`/Smit-Prajapati/tricky-turtle-16`（Loader，CSS）

原始参数：

```css
.loader { --size: 250px; --duration: 2s; }
.box { animation: ripple var(--duration) infinite ease-in-out; }
.box:nth-child(1) { inset: 40%; }
.box:nth-child(2) { inset: 30%; animation-delay: 0.2s; }
.box:nth-child(3) { inset: 20%; }   /* 依此类推，每层 +10% */
```

手法：多个同心圆，尺寸逐层放大、起始延迟逐层 +0.2s，无限循环 `ease-in-out`。视觉上是持续向外扩散的水波。

Compose 翻译：

```kotlin
@Composable
fun RippleLoader(modifier: Modifier = Modifier, rings: Int = 4) {
    val transition = rememberInfiniteTransition(label = "ripple")
    val progress by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(Motion.DurSlow * 5, easing = EaseInOut),  // 2000ms 对齐 --duration: 2s
            repeatMode = RepeatMode.Restart,
        ),
        label = "rippleProgress",
    )
    Canvas(modifier) {
        repeat(rings) { i ->
            // 每层延迟 0.2s / 2s = 0.1 相位偏移
            val phase = (progress - i * 0.1f).mod(1f)
            val radius = size.minDimension * (0.15f + 0.35f * phase)
            drawCircle(
                color = color.copy(alpha = 1f - phase),
                radius = radius,
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}
```

适配课程表：用于 `JwImportScreen`（教务导入等待）、`GradesScreen`（成绩刷新）、更新检查 `UpdateDialog`。现有 `JellySwitch.kt` 的果冻感与这套涟漪是同一路语言，风格不冲突。

## 2. 勾选态三色渐变填充（Checkbox）

来源：`/MikeAndrewDesigner/jolly-gecko-33`（Checkbox，CSS）

原始参数：

```css
.checkmark { transition: all 0.2s ease; opacity: 0.4; }
.container input:checked ~ .checkmark {
  background: linear-gradient(144deg, #af40ff, #5b42f3 50%, #00ddeb);
  opacity: 0.9;
}
.checkmark:after { transform: rotate(45deg); }  /* 对勾，用 border 拼 */
```

手法：未选中是半透明底；选中瞬间底色切成 144° 三色渐变（紫→蓝→青），透明度从 0.4 升到 0.9，全程 200ms。

Compose 翻译：

```kotlin
@Composable
fun GradientCheck(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val fill by animateFloatAsState(
        targetValue = if (checked) 1f else 0f,
        animationSpec = tween(Motion.DurFast + 50, easing = EaseOut),  // 200ms
        label = "checkFill",
    )
    val brush = Brush.linearGradient(
        colors = listOf(Color(0xFFAF40FF), Color(0xFF5B42F3), Color(0xFF00DDEB)),
        start = Offset.Zero,
        end = Offset.Infinite,  // 用 144° 折算的斜向
    )
    Box(
        Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(if (fill > 0f) brush else Color.White.copy(alpha = 0.24f))
            .alpha(0.4f + 0.5f * fill)
            .clickable { onCheckedChange(!checked) },
    ) {
        // 对勾用 Path 或 Icon，随 fill 做 scale + alpha
        val scale by animateFloatAsState(if (checked) 1f else 0.6f, tween(160), label = "tick")
        Icon(Icons.Default.Check, null, Modifier.scale(scale).alpha(fill), tint = Color.White)
    }
}
```

适配课程表：`CourseEditorScreen`（选周次/节次）、`ProfilePreferenceSections`（偏好开关）、`OcrDraftEditSheet`（草稿字段确认）。项目已有 `JellySwitch`，这套可作为「单选/多选」的补充，配色建议换成主题色而非原版紫青，避免与 liquid-glass 基调打架。

## 3. 形态 + 色彩 + 内容三层同时过渡（按钮）

来源：`/vinodjangid07/smart-emu-83`（Button，CSS）

原始参数：

```css
.button { width: 50px; height: 50px; border-radius: 50%; transition-duration: .3s; overflow: hidden; }
.button:hover {
  width: 140px; border-radius: 50px;
  background-color: rgb(255, 69, 69); transition-duration: .3s;
}
.button::before { content: "Delete"; top: -20px; }
.button:hover .svgIcon { width: 50px; transform: translateY(60%); }
```

手法：静止是 50×50 圆形图标按钮；悬停时宽度、圆角、背景色、内部图标尺寸与位移、隐藏文字，**五件事同时用 300ms 过渡**，读起来是一个连贯的「展开」动作而非五个独立变化。

Compose 翻译（移动端用 press 而非 hover）：

```kotlin
@Composable
fun ExpandingActionButton(
    expanded: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val width by animateDpAsState(if (expanded) 140.dp else 50.dp, tween(300, easing = EaseInOut), label = "w")
    val corner by animateDpAsState(if (expanded) 26.dp else 25.dp, tween(300), label = "r")
    val bg by animateColorAsState(if (expanded) Color(0xFFFF4545) else Color(0xFF141414), tween(300), label = "c")
    Row(
        Modifier.height(50.dp).width(width).clip(RoundedCornerShape(corner))
            .background(bg).clickable { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = Color.White)
        AnimatedVisibility(expanded, enter = fadeIn(tween(200)) + expandHorizontally(), exit = fadeOut(tween(120))) {
            Text(label, color = Color.White, modifier = Modifier.padding(start = 8.dp))
        }
    }
}
```

适配课程表：`FeedbackButtons.kt`（反馈按钮）、`TodayClassCards`（卡片主操作按钮）、`IslandTabItem`（标签展开态）。这套「一动作多属性同步」正是 `Motion.kt` 注释里强调的和谐感，可直接并入现有语言。

## 4. 无限循环的「机构运转」母题（Loader）

来源：`/Nawsome/wet-mayfly-23`（Loader，CSS）

原始参数：

```css
.wheel-and-hamster { --dur: 1s; }
.hamster { animation: hamster var(--dur) ease-in-out infinite; transform-origin: 50% 0; }
.hamster__head { animation: hamsterHead var(--dur) ease-in-out infinite; }
.wheel { background: radial-gradient(...); }
```

手法：一个跑轮 + 仓鼠，多个部件共用同一 `--dur: 1s` 与 `ease-in-out`，通过不同的 `@keyframes` 产生联动错觉。核心是**统一节拍**——所有部件同频，才读成一个整体在转。

Compose 翻译要点：

- 用**一个** `rememberInfiniteTransition` + **一个** progress 作为驱动源，不要每个部件各起一个 transition
- 各部件用 `progress` 的不同函数映射（正弦、取模、分段），共享 `tween(1000, easing = EaseInOut)`
- 部件数量多时优先 `Canvas` + `drawWithCache`，避免每帧重组

适配课程表：`JwImportScreen` 的导入等待（可做成「文件→解析→入库」三段联动的机械感动画）、`RevealHost` 的加载态。⚠️ 但要注意：项目 `MotionPrefs.kt` 已实现 `ANIMATOR_DURATION_SCALE` 检测，**这类无限循环动画必须在 reduce-motion 下停住**，否则违反 WCAG 2.3.3 与项目既有约定。

## 5. 共性结论

| 观察 | 说明 |
| --- | --- |
| uiverse 的动效时长集中在 0.2s–2s | 与项目 `Motion.kt` 的 150/260/420/450ms 区间吻合，参数可直接采纳 |
| 缓动以 `ease` / `ease-in-out` 为主 | 对应 Compose 的 `FastOutSlowInEasing` / `EaseInOut` |
| 大量使用 `transition: all` 同步多属性 | 对应 Compose 的「多个 animate*AsState 共用同一 tween 时长」——这正是好的动效观感来源 |
| 少见 spring 物理 | uiverse 偏「可控曲线」，而项目 `Motion.kt` 用 spring 较多；两者可互补：追求果冻感用 spring，追求精确节奏用 tween |
| 无限循环动画普通存在 | 必须全部补 reduce-motion 护栏，这是项目已有的优点，别在国内站点抄代码时丢掉 |

## 6. 落地建议（优先级）

| 优先级 | 动效 | 落点 | 理由 |
| --- | --- | --- | --- |
| P0 | 三色渐变勾选 | CourseEditor / ProfilePreference / OcrDraft | 交互高频，改动小，收益直接 |
| P0 | 一动作多属性同步按钮 | FeedbackButtons / TodayClassCards | 已有 Pressable 基础，只需统一时长 |
| P1 | 同心圆涟漪 loader | JwImport / Grades / Update | 等待场景明确，替换系统 ProgressIndicator |
| P2 | 机构联动 loader | JwImport 三段流程 | 制作成本高，且必须做 reduce-motion 护栏 |

## 7. 抓取方法留档（可复用）

```bash
# 分类页 → 元素列表（每类首屏 34 条）
curl.exe -sL -A 'Mozilla/5.0 ... Chrome/131.0' https://uiverse.io/loaders -o loaders.html
grep -oE 'href="/[A-Za-z0-9_-]+/[a-z0-9_-]+-[0-9]+"' loaders.html | sort -u

# 元素页 → CSS 片段（HTML 实体转义后正则取 /* CSS */ 之后）
curl.exe -sL -A '...' https://uiverse.io/Smit-Prajapati/tricky-turtle-16 -o el.html
# 站点为 Next.js，代码块以 &#x2F; &quot; 等实体内嵌，需先反转义
```

已验证可用的分类路径：`buttons` `loaders` `cards` `inputs` `checkboxes` `switches` `tooltips` `elements`；返回 404 的：`ui` `toggles` `toggle` `radios` `notifications` `checkbox` `switch` `badge`。

## 8. 落地记录（2026-10-30 完成）

四项里的三项已进生产代码，P2「机构联动 loader」按原判断继续搁置（成本高、收益与涟漪重叠）。

### 8.1 已落地

| 动效 | 文件 | 落点 | 状态 |
| --- | --- | --- | --- |
| 同心圆涟漪 loader | `core/ui/RippleLoader.kt` | 教务导入遮罩、导入解析中、成绩首屏、设置备份遮罩、二维码生成 | 真机验证 |
| 三色渐变勾选 | 同上 | 导入完成页（48dp）、教务抓取完成弹窗（22dp） | 真机验证 |
| 一动作多属性同步 | `feature/schedule/WeekCell.kt` | 周选择面板的周格 | 真机验证 |

### 8.2 落地时被真机与单测抓出来的三个问题

写代码时看不出来、跑起来才暴露的，记下来免得下次再犯：

1. **未选中态在浅色底上等于不存在。** 原作只有一层「白色 24%」的半透明底，压在纸本暖白（#FBF7F2）上几乎同色。真机截图里那枚标记整个消失。解法是加 `idleRingColor` 参数并强制造型上要求浅色底必须传——不传就是一颗看不见的圆。
2. **白勾在浅色渐变上只有 2.24:1。** 起初按「平均亮度」选勾色，单测现场就红：勾是从左下穿到右上的长笔画，它同时压在三停上，**最亮那一停**才是决定性的。改成遍历全部色停取最差落点后，纸本档过了。
3. **水银蓝两端都不达标（白勾 2.95、深勾更差）。** 这不是选色能解决的——说明渐变本身太宽。最终让`accentGradient`自适应：勾读不清就逐步收窄色阶（0.26 → 0.156 → 0.094 → 0.056），收到能读为止。**渐变是装饰，勾是信号，该让路的是渐变。** 三条都写成了 `GradientCheckMarkTest` 的用例。

### 8.3 reduce-motion 从「约定」变成「实测」

改造前只有 `RippleLoader` 自己查 `ANIMATOR_DURATION_SCALE`，另有两条无限循环动画漏了护栏：

- `feature/schedule/components/NowMarker.kt`：当前时间线的呼吸端点。这是**静置就在闪**的效果，前庭敏感用户会持续不适。关动画时停在 0.78 而不是删掉——「现在几点」是事实，不该因关动画而消失。
- `feature/today/TodayClassCards.kt`：「正在上课」色点脉冲。同上，停在 0.85。

实测方式：`adb shell settings put global animator_duration_scale 0` 后连抓两帧，涟漪区域**逐字节相同**（PIL 裁剪比对），证明它真的停住了；恢复设为 1 后重跑一遍确认动画回来。

### 8.4 WeekCell 为什么要重写

原来是四条独立的 `animateColorAsState` / `animateFloatAsState`，各自持一份目标值与时长（150ms 与 260ms 混用）。任何一处时长写岔，点下去就会先看到「描边已经没了、底色还在渐入」的半帧错位。现在四条通道挂在同一个 `updateTransition` 上，只留一份进度——物理上不可能不同步。缩放仍走弹簧（它是「点中了」的物理反馈），颜色走 tween（它是状态迁移），两条时钟同时跑是刻意的。

> 踩坑：`rememberTransition` 在 Compose 1.9 里签名变了，直接传状态会报 `TransitionState<T>` 类型不匹配。用 `updateTransition(targetState, label)` 即可。
