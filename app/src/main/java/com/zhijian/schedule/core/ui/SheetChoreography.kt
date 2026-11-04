package com.zhijian.schedule.core.ui

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.math.sin

/**
 * 底部面板（ModalBottomSheet）内容入场的**节拍表**。
 *
 * 为什么需要一张独立于面板自身的节拍表：面板的上升是容器动画（M3 自己的 show spec，
 * 真机约 300ms 量级），内容的入场是第二层动画。两层若各播各的，内容那层会在面板还没露头时
 * 就自己跑完——用户看到的是「面板滑上来，内容早就摆好了」，错峰等于没有。
 * 所以内容入场必须挂在**面板开始显示的那一刻**起播（见 [SheetArrival]），
 * 并把节拍铺在面板行程上：容器领先、内容跟随、最后一拍落在面板落定之后几十毫秒，
 * 观感是「面板托着内容一起落位」，而不是「先开一个空抽屉，再往里摆东西」。
 *
 * 为什么是纯逻辑、不 import android：节拍是「第几拍、错开多少、总长多少」的算术，
 * 抽出来就能进本机单测（项目约定见 CONTRIBUTING 第 3 节：可测逻辑与 Android 解耦）。
 * 输错不会崩、只会让动画看着廉价，而这类问题最难靠肉眼复现，必须有断言钉住。
 *
 * 槽位（slot）是**浮点拍号**而不是毫秒：调用点的本意是「这一块排在第几拍」，
 * 写成毫秒就得在每个调用点数一遍 60 + 45 × n，改一个常量要改十几处。
 * 浮点是为了让同一层里的兄弟元素（两枚 pill、几行信息）共用一拍再各错开一小格。
 */
object SheetChoreography {
    /** 面板起播到第一拍：留出「容器先动」的量，内容不与面板边沿同帧出现 */
    const val LEAD_MS = 60f

    /**
     * 相邻槽位的间隔。
     *
     * 45ms 是「看得见次序、又不觉得在排队」的档位：再密（30 以下）读成一块整体在抖，
     * 再疏（60 以上）最后一块落地已经是 600ms 开外，用户早就在读内容了。
     */
    const val STEP_MS = 45f

    /**
     * 单个槽位自身的行程。
     *
     * 240ms 比 [Motion.DUR_MEDIUM] 短一档：错峰的总量已经很长，单段再长会让最后一块
     * 落地太晚（人已经开始看内容了，按钮还在飘）。读作「跟上来了」而不是「飞过来了」。
     */
    const val SLOT_MS = 240f

    /** 头卡：面板的视觉容器，最先落位 */
    const val HERO = 0f

    /** 色点：紧贴头卡，但要有自己的一下弹 */
    const val CHIP = 0.25f

    /** 课程名：跟随色点 */
    const val TITLE = 0.6f

    /** 第一枚 pill（星期 · 节次） */
    const val PILL = 1.05f

    /** pill 之间的微错位：两枚 pill 是同一层信息，不该各占一整拍 */
    const val PILL_STEP = 0.25f

    /** 信息卡容器 */
    const val DETAILS = 1.7f

    /** 第一条信息行 */
    const val ROW = 2.1f

    /** 信息行之间的间隔：行数不定（教室 / 教师 / 备注），按条数累加 */
    const val ROW_STEP = 0.65f

    /**
     * 收起（出场）时长：点编辑 / 删除后内容先收一下，再让宿主换屏。
     *
     * 130ms 与 [Motion.DUR_FAST] 同档：这是用户已经做完决定之后的事，
     * 任何停顿都在拖慢操作，只留一个「收」的余韵。
     */
    const val DEPART_MS = 130

    /** 按钮行：整张面板的最后一块 */
    const val ACTIONS = 4.1f

    /** 没有信息卡时按钮提前的量（拍）：比整拍小一点，读作「顺势跟上」而不是新开一段 */
    const val ACTIONS_EARLY = 0.75f

    /**
     * 收起时的缩放幅度。3% 小到看不出是「缩」，只读作「退开一步」；
     * 再大就会让人以为面板在往回缩、手却已经点下去了。
     */
    const val DEPART_SCALE = 0.03f

    /** 收起时的淡出幅度。剩下的亮度交给换屏接走，不会留下一个空心卡片 */
    const val DEPART_FADE = 0.42f

    /**
     * 等「面板开始显示」的超时上限（毫秒）。
     *
     * 正常两三帧内就会满足；给上限是因为 Dialog 的 attach 偶发会晚，
     * 而等待协程若无限挂起，内容就会一直停在 alpha=0（比不播动画更糟）。
     */
    const val SHOW_TIMEOUT_MS = 400L

    /** 起播到某个槽位开始的毫秒数 */
    fun startOf(slot: Float): Float = LEAD_MS + slot * STEP_MS

    /**
     * 整条时间轴的总长（毫秒）。
     *
     * 时间轴本身是「已经过去多少毫秒」，跑满这个值即全部落位。
     * 用**一条**时间轴驱动所有槽位，任意两块的相对次序就不会因为各自起协程而漂移——
     * 各自 delay + animate 的写法在低端机上会因为协程调度抖动而让「先色点后标题」偶发倒序。
     *
     * 向上取整而不是截断：截断会让时间轴比最后一拍短半毫秒，最后一块永远停在 0.9999
     * （肉眼看不到，但「落位后还有一丝透明/位移」在慢动画设置下会显得没对齐）。
     */
    fun totalMs(lastSlot: Float = ACTIONS): Int = ceil(startOf(lastSlot) + SLOT_MS).toInt()

    /**
     * 某个槽位在 [elapsedMs] 时刻的**几何进度**（0..1）。
     *
     * 缓动是缓出（起步快、收尾缓）。不能用先慢后快的曲线：内容是从面板里「跟上来」的，
     * 起步那一帧若无位移，读起来就是「面板停了，内容才开始」，正是这次要消灭的观感。
     */
    fun progressAt(
        elapsedMs: Float,
        slot: Float,
    ): Float = easeOutCubic(((elapsedMs - startOf(slot)) / SLOT_MS).coerceIn(0f, 1f))

    /**
     * 某个槽位在 [elapsedMs] 时刻的**不透明度**（由 [progressAt] 按 spec 的指数折算）。
     *
     * 指数大于 1 时透明度**落后**于位移：元素先以一个虚影出现、再在落位过程中变实。
     * 大块区域（头卡、信息卡）必须走这条——它们的位移很小，若 alpha 与位移同步到位，
     * 读起来是「一块实心色块平移了一下」，而先虚后实才有「浮上来」的层次。
     * 小元素（色点、图标）反过来要 alpha 领先（指数小于 1），先看见、再弹到位。
     */
    fun alphaAt(
        elapsedMs: Float,
        slot: Float,
        spec: EnterSpec,
    ): Float = progressAt(elapsedMs, slot).pow(spec.alphaPower).coerceIn(0f, 1f)

    /**
     * 过冲鼓包：在 [progress] 之上叠一个单峰位移，两端仍为 0 / 1。
     *
     * 形状是 p + amount·sin(πp)：峰值在 p = 0.5 附近、幅度约 amount 的一半，
     * 到 1 时自动回到 1，所以**不会让元素停在过冲位置**——那正是「用弹簧做入场、
     * 结果元素永远比设计稿大一圈」的翻车方式。
     * 只给色点、图标这类小面积元素用；头卡与文字块不加，过冲一大就变成抖动。
     */
    fun bounceAt(
        progress: Float,
        amount: Float,
    ): Float {
        if (amount == 0f || progress <= 0f || progress >= 1f) return progress
        return progress + amount * sin(PI.toFloat() * progress)
    }

    /**
     * 按钮行该排在第几拍。
     *
     * 没有信息卡时（教室 / 教师 / 备注全空），按钮不该还空等信息行那几拍——
     * 面板会先停住、按钮才冒出来，读作「卡了一下」。这时让它接在信息卡该在的位置之后。
     */
    fun actionsSlot(hasDetails: Boolean): Float = if (hasDetails) ACTIONS else DETAILS + ACTIONS_EARLY

    /** 标准缓出：25% 走完 57.8%、50% 走完 87.5%、75% 走完 98.4% */
    private fun easeOutCubic(t: Float): Float {
        val inv = 1f - t
        return 1f - inv * inv * inv
    }
}

/**
 * 单个槽位的**入场配方**：位移、起始缩放、过冲、透明度指数。
 *
 * 四个量必须成套地读：位移大而透明度慢 → 从远处飘来；位移小而过冲大 → 原地弹一下；
 * 位移为 0 且透明度为 1 → 只有缩放（头卡就是这样，它必须「贴在面板上」而不是飞进来）。
 * 分散在每个调用点写死 float，改一次要改七八处，而且没法单测。
 */
data class EnterSpec(
    /** 起始上浮距离（dp），落位时归零 */
    val riseDp: Float,
    /** 起始缩放（1 = 不缩放） */
    val scaleFrom: Float = 1f,
    /** 过冲幅度（0 = 无过冲），见 [SheetChoreography.bounceAt] */
    val bounce: Float = 0f,
    /** 透明度相对几何进度的指数，见 [SheetChoreography.alphaAt] */
    val alphaPower: Float = 1f,
) {
    companion object {
        /**
         * 头卡（面板里最大的一块面）：**只缩放、不上浮**。
         *
         * 它必须读作「从面板底板上长出来」，而不是「从下面飞进来、后面留一块空底板」。
         * 0.965 的起点在 240ms 内几乎看不出是缩放，只读到卡片的边界先收、后放。
         */
        val Hero = EnterSpec(riseDp = 0f, scaleFrom = 0.965f, alphaPower = 1.6f)

        /** 色点：小面积，允许明显过冲，兑现「弹一下」 */
        val Chip = EnterSpec(riseDp = 0f, scaleFrom = 0.3f, bounce = 0.35f, alphaPower = 0.7f)

        /** 文字：只上浮不缩放——缩放文字会糊 */
        val Text = EnterSpec(riseDp = 8f, alphaPower = 1f)

        /** pill：小胶囊，自带一点弹性 */
        val Pill = EnterSpec(riseDp = 10f, scaleFrom = 0.9f, bounce = 0.12f, alphaPower = 0.85f)

        /** 信息卡：大块底板，先虚后实 */
        val Card = EnterSpec(riseDp = 18f, scaleFrom = 0.985f, alphaPower = 1.5f)

        /** 信息行：只在容器内部微浮，幅度必须远小于卡片本身，否则读成两层在动 */
        val Row = EnterSpec(riseDp = 6f, alphaPower = 1f)

        /** 按钮行：收尾的一块，短距离、干脆 */
        val Action = EnterSpec(riseDp = 12f, alphaPower = 1.1f)
    }
}
