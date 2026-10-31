package com.zhijian.schedule.core.ui
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.IntOffset

/**
 * 全局动效语言：灵动有弹性。
 * 所有时长 / 弹簧 / 缓动集中于此，保证各页面观感一致（和谐、自然）。
 */
object Motion {
    /** 快速反馈：水波纹、渐隐 */
    const val DUR_FAST = 150

    /** 标准过渡：淡入、局部布局切换 */
    const val DUR_MEDIUM = 260

    /** 慢速登场：交错入场、进度扫描 */
    const val DUR_SLOW = 420

    /**
     * **页面**扩散（加号 → 课程编辑页）的正向时长，缓动是 [EaseRevealAccelerate]。
     *
     * 为什么比 [DUR_SLOW] 长：被裁的是一整屏旧帧，圆要一直长到屏幕最远角（见 revealRadius），
     * 收尾太快会读成「圆还没铺满就切好了」。
     *
     * 为什么是 450 而不是原来的 560——换曲线必须重新补时长。加速曲线的行程「后重前轻」：
     * 半径走到 77.9%（画面读作已铺满）的时刻，从旧曲线的 50% 时间点推迟到 83.7% 时间点。
     * 时长不动就从 280ms 拖到 469ms（晚近 190ms，起步那一段会被判成卡顿）；压到 450ms 后
     * 落在 377ms，只晚约 100ms，末端加速的观感拿得到，也不至于让人以为点空了。
     * 页面扩散与换主题动的是同一个几何量（半径）、用的是同一条曲线，所以补偿系数相同，
     * 两个常量取到同一个值是这条推导的结果，不是巧合、也不是互相抄（推导见 [DUR_REVEAL_THEME]）。
     *
     * 保留两个名字而不是合成一个：它们是两条独立的时间线（页面转场 / 换皮肤），
     * 现在同值不等于以后必须同值，将来任何一条要单独调时不该被另一条拽着走。
     */
    const val DUR_REVEAL = 450

    /**
     * 反向扩散（页面收回被点按钮），缓动是 [EaseRevealDecelerate]。
     *
     * 缓动：正向曲线的**严格时间镜像**（`mirror(t) == 1 - accelerate(1 - t)`）。
     * 正向「慢起快收」，反向「快起慢收」——方向相反，但同一进度点上互为倒放，不会打架。
     *
     * 为什么不再比正向短一截：旧版本用「反向更短」表达「东西在离开」，那是在补旧曲线
     * （EaseReveal）的慢起步——它 100ms 时才收掉 16.5% 的半径，看着像按下去没反应，
     * 只能靠砍时长把速度找回来。镜像曲线天生前段重：100ms 已经收掉 35%，
     * 「离开」的感觉由曲线给出，不必再砍时长。
     *
     * 为什么是 370：按「读作收回完毕」的时刻对齐。半径走完 90% 时圆只剩约 1.4% 的屏幕面积
     * （0.9 × 半径的圆面积 / 满屏），可以算收回了；旧反向（EaseReveal @460ms）在
     * 0.633 × 460 ≈ 291ms 走到这一步，镜像曲线要 78.6% 的时长才走到，291 / 0.786 ≈ 370ms。
     * 落地时刻不变而前半程更快，收回的「清脆感」来自曲线，不来自赶时间。
     * 370 < [DUR_REVEAL]，「收回比张开短一截」的既定节奏仍然成立。
     */
    const val DUR_REVEAL_BACK = 370

    /**
     * **换主题**的扩散时长。
     *
     * 450 是给 [EaseRevealAccelerate] 的时长补偿：加速型的行程「后重前轻」，半径走到 77.9%
     * （画面读作换完）的时刻从旧曲线的 50% 时间点推迟到 83.7% 时间点。旧曲线 560ms 时这一步
     * 落在 280ms，时长不动的话要 560 × 0.837 ≈ 469ms 才「读作换完」，晚近 190ms，
     * 起步那一段就会被判成卡顿。压到 450ms 后落在 377ms，只晚约 100ms——末端加速的观感
     * 拿得到，又不至于让人以为点空了。
     * 450 = M3 duration token `long1`（material-components-android docs/theming/Motion.md）。
     *
     * 与页面扩散的 [DUR_REVEAL] 同值：两条时间线动的是同一个几何量、用同一条曲线、
     * 按同一条公式补偿，取到同一个数是推导的结果（细节见 [DUR_REVEAL]）。
     */
    const val DUR_REVEAL_THEME = 450

    /** 标准缓动：快速起步、缓慢收尾 */
    val EaseOut = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /**
     * **整屏扩散**的加速缓动：起步慢、末端最快。页面扩散与换主题**共用这一条**。
     *
     * 取值就是 M3 `standard.accelerate` token 的 `cubic-bezier(0.3, 0, 1, 1)`
     * （material-components-android `docs/theming/Motion.md` 的
     * `motionEasingStandardAccelerateInterpolator`）。起点切线水平 → 静止起步；
     * 终点切线斜率 (1-0)/(1-0.3) ≈ 1.43 → 收尾时速度最快，正好是 [EaseRevealDecelerate] 的反面。
     *
     * 为什么不是别的候选（同一组「加速型」里最靠前的行程才不会被读成卡顿）：
     * - Compose 预设 `FastOutLinearInEasing` = (0.4, 0, 1, 1)：半程只走到 32.6%；
     * - M3 `emphasized.accelerate` = (0.3, 0, 0.8, 0.15)：半程只走到 15.5%，整屏大半时间纹丝不动。
     * 本曲线 25% / 50% / 75% 时间点的半径行程 ≈ 13.0% / 37.2% / 66.8%，是本族里前段最满的一条。
     *
     * 也不能用 [EaseOut]：那条曲线在 50% 时间点已走完 87.8% 的行程，而扩散的行程是「圆半径」，
     * 半径一旦到 87.8% 就已经盖满整屏，剩下的时间全花在屏幕最远的那个角落里——
     * 观感上等于**瞬切**（真机实测就是这么「看不出效果」的）。
     *
     * 规范口径备注：Material 把 accelerate 族标给「离开屏幕」的元素，而这里的两次扩散
     * （页面进来 / 换皮肤）在语义上都是**新内容进入**（若严格照规范，入场该用 decelerate 族）。
     * 按需求选加速型的依据是：圆形扩散真正在动的是一次**抹除**——旧画面被圆边界吃掉，
     * 最后那一段是旧画面离开，加速收尾讲得通。代价是起步段的运动量只有旧曲线的一半左右，
     * 由 [DUR_REVEAL] / [DUR_REVEAL_THEME] 压缩时长来补。
     */
    val EaseRevealAccelerate = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    /**
     * **反向收回**的缓动：[EaseRevealAccelerate] 的严格时间镜像。
     *
     * 把一条三次贝塞尔 (x1, y1, x2, y2) 按时间倒放，得到 (1-x2, 1-y2, 1-x1, 1-y1)：
     * (0.3, 0, 1, 1) 倒放就是 (0, 0, 0.7, 1)。于是 `mirror(t) == 1 - accelerate(1 - t)`
     * 对任意 t 成立（单测把这条恒等式钉住了），反向就是正向的倒放：
     * 起步最快（按下立刻有反馈）、末端最慢（圆稳稳落在加号上，不留硬边）。
     *
     * 为什么反向不复用 [EaseRevealAccelerate]：那会让半径在满屏附近磨蹭一百多毫秒，
     * 读成「按了返回没反应」；也不能用 [EaseOut]（半程已走完 87.8%，等于瞬间收完）。
     */
    val EaseRevealDecelerate = CubicBezierEasing(0f, 0f, 0.7f, 1f)

    /** 按压回弹：按下干脆、松手轻微回弹 */
    val PressSpring = spring<Float>(dampingRatio = 0.62f, stiffness = 620f)

    /** 选中弹跳：chip / 图标选中时的俏皮放大 */
    val PopSpring = spring<Float>(dampingRatio = 0.5f, stiffness = 480f)

    /** 选中弹起：快速吸附、轻微过冲——课程块「被拿起」，与详情面板入场连成一体 */
    val SelectionEnter = spring<Float>(dampingRatio = 0.52f, stiffness = 520f)

    /** 选中恢复：干脆回落不拖尾——与详情面板滑出并行播完，消除「等面板走完才复位」的滞后感 */
    val SelectionExit = tween<Float>(durationMillis = 130, easing = EaseOut)

    /** 整屏横滑转场：无过冲，保证大位移干净利落 */
    val SlideSpring =
        spring<IntOffset>(
            dampingRatio = 1f,
            stiffness = 420f,
            visibilityThreshold = IntOffset(1, 1),
        )

    /**
     * 逐个入场：每个课块的落位弹簧（轻微过冲、收尾干净）。
     *
     * 0.7 / 900：ω ≈ 30 rad/s、ζ = 0.7 ⇒ 过冲约 4.6%、约 220ms 落位。
     * 旧值 0.62 / 460 要约 345ms 才停——配上 70ms 的逐个节拍时，任何一帧都有五六个块
     * 在飞，读起来是一整片涟漪；收紧到 220ms 之后，同一时刻只有两三个块在动，
     * 每一次落位才数得清。
     */
    val CascadeEnter = spring<Float>(dampingRatio = 0.7f, stiffness = 900f)

    /**
     * 底部浮岛选中胶囊滑动：**无过冲、约 210ms 落位**。
     *
     * 这是被 NN/g 的可用性研究改掉的。旧值 0.72/480 有 3.8% 过冲、292ms 才停，
     * 而切 tab 是全 App 最高频的交互之一；NN/g《Executing UX Animations》写着
     * 「越常见的动效，越要短、越要克制」，高频交互动效目标区间是 100~200ms。
     *
     * 为什么选 0.9/600：
     * - 过冲 e^(-πζ/√(1-ζ²)) ≈ 0.2%，肉眼读作「干净地停住」，不再有回弹余韵；
     * - 落位 ≈ 210ms（4.6 个时间常数），落在 200ms 档，与 M3 选择类控件的
     *   short2 = 200ms 同档；
     * - 不再需要「过冲代表液体惯性」那套隐喻——胶囊本身已是液滴造型，
     *   位移再带回弹就是同一件事说两遍。
     *
     * **单位是 tab 下标（0 / 1 / 2），不是 dp。** 这是果冻拉伸的前提：拉伸量要把速度折算成
     * 「相对一次跨度」的比例，用下标才与屏宽、tab 数无关——平板上同样一下，不会因为走得远
     * 就显得更急。逐帧积分复核：峰值速度 9.51 /s 出现在 50ms，过冲 0.10%，217ms 落到 1% 内。
     *
     * 形变不在这条弹簧里：它由速度实时折算（见 `island/IslandIndicator.kt`）。当年删掉形变
     * 的三条理由——「与位移无关的独立关键帧」「把切换拖到 339ms」「边界被拉糊」——在这里
     * 分别由「速度驱动」「不新增时间线」「16% 幅度 + 描边随形状重画」逐条消解，
     * 所以这版不是把旧做法搬回来。
     */
    val FluidSlide = spring<Float>(dampingRatio = 0.9f, stiffness = 600f)

    /**
     * 果冻开关的滑行弹簧：阻尼比 0.55，单位行程上约 12.6% 过冲。
     *
     * 为什么不用临界阻尼（[SlideSpring] 的 1f）：开关是「点一下就换边」的离散动作，
     * 位移本身其实不需要过冲；但果冻的形变是从**速度**里长出来的（见 `JellySwitch.kt`），
     * 没有过冲就没有落位后那一下回弹，胶囊会一路缩成圆就停住、缺一口气。
     *
     * 为什么是 0.55 / 420：过冲幅度 = e^(-πζ/√(1-ζ²)) ≈ 12.6%，在开关 20dp 的行程上约 2.5dp，
     * 正好小于轨道 4dp 的内边距——回弹看得见，手柄又顶不出轨道（囊形鼻尖离轨道壁约 1.5dp，
     * 是「差一点就顶到」的观感；再大就会撞墙夹紧，那一下会读成被挡住而不是回弹）。
     * 刚度 420 ⇒ ω ≈ 20.5 rad/s，半程约 90ms、落位约 350ms，与 M3 开关的手感同档，
     * 不会因为要「演出形变」而把一次点击拖成一次转场。
     */
    val JellySlide = spring<Float>(dampingRatio = 0.55f, stiffness = 420f)

    /**
     * 果冻开关的**手柄尺寸**弹簧：选中 / 未选中之间 16↔24dp 的「呼吸」。
     *
     * 为什么比 [JellySlide] 硬（620 vs 420）：尺寸变化是**状态**信号，要在形变高峰之前收口。
     * 逐帧积分下 50ms（拉伸峰值）时直径已从 16 走到 19.7dp，120ms 落定；
     * 若跟着位置弹簧走，尺寸要到 180ms 才稳，那时胶囊早已缩回圆形，会读成
     * 「先飞过去、再在原地长大一下」两个动作，而不是「小水滴飞过去长成一枚球」。
     *
     * 阻尼比 0.62 与 [PressSpring] 同档：允许 9.5% 的过冲（24 → 26.2dp）再收回，
     * 落位时手柄「鼓一下」，与滑行弹簧的回弹错开约 60ms，是两次呼吸而不是一次抖动。
     */
    val JellyGrow = spring<Float>(dampingRatio = 0.62f, stiffness = 620f)

    /**
     * 触摸倾动的**指腹跟随**弹簧：倾角要贴着手指走，但不能瞬移。
     *
     * 为什么不是直接 snapTo：触摸事件是一帧一跳的离散采样，直接赋值等于把采样抖动原样
     * 交给画面；拖动越快，跳越大。业界无一例外都在这一层加阻尼——vanilla-tilt 用
     * 300ms 的 CSS transition（`speed`），beUI/Framer 用 `SPRING_MOUSE`
     * （stiffness 200 / mass 0.3 ⇒ ω ≈ 26 rad/s，damping 15 ⇒ ζ ≈ 0.97），
     * Atropos 的 `duration` 也是 300ms。
     *
     * 为什么取 900 / 0.88：Compose 的 stiffness 就是 ω²，900 ⇒ ω = 30 rad/s，
     * 时间常数约 33ms——仍是「指哪打哪」，但一帧一跳被抹平的同时留了一点追手感
     * （ζ 0.88 的过冲约 0.3%，肉眼读不到抖动，只读得到"软"）。
     */
    val TiltFollow = spring<Float>(dampingRatio = 0.88f, stiffness = 900f)

    /**
     * 触摸倾动回正：松手后带一次明显过冲再落位，阻尼比低于 1 才有「纸片弹回」的手感。
     *
     * 0.38 / 460：ω ≈ 21.4 rad/s，过冲约 27%、周期约 0.33s，约 0.5s 落定——
     * 比最初 0.55 / 420（过冲约 12%）在 17° 的大卡上肉眼可见地更"弹"。
     * 这是"夸张"最省力也最有效的来源：角度再大，回正太干脆就还是块平板。
     */
    val TiltReturn = spring<Float>(dampingRatio = 0.38f, stiffness = 460f)

    /**
     * 按下进度（反光不透明度与抬升高度**共用**）的加压弹簧：0 → 1 带一次小鼓胀。
     *
     * 用弹簧而不是 tween：tween 是"匀速爬到 1"，读成一次状态变化；
     * 弹簧约 110ms（峰值时刻 π/ω_d）冲到 1.095 再收口，读成"被指腹压了一下"。
     * 过冲量与 [PressSpring] 接近（9.5% vs 8.4%），不会看起来像在抖。
     */
    val PressIn = spring<Float>(dampingRatio = 0.60f, stiffness = 1200f)

    /**
     * 入口卡「接住」返回画面的着地弹簧：快照落进卡片那一帧 snapTo 到 1.03，
     * 再由这条弹簧软着陆回 1，过冲冲到 1 下方约 0.4% 再收口，读作卡片把页面「垫了一下」。
     *
     * 0.55 / 520：ω ≈ 22.8 rad/s，半程约 70ms、落位约 300ms；阻尼比 0.55 与
     * [JellySlide] 同档——着地要有果冻的鼓动，但幅度只有 3% 行程，
     * 过冲折到卡片上约 0.12%，眼睛读得到「软」，手不会觉得它在抖。
     */
    val CatchSpring = spring<Float>(dampingRatio = 0.55f, stiffness = 520f)

    /** 倾动高光淡出：松手后留一点余晖，但不要拖住回正（比旧版 150ms 更"余韵"） */
    const val DUR_TILT_GLARE_OUT = 200
}
