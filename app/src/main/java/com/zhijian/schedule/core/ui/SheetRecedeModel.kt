package com.zhijian.schedule.core.ui

/**
 * 下拉关闭时内容「退开」的量表。
 *
 * 为什么需要这张表：M3 的 ModalBottomSheet 只有两个锚点（展开 / 隐藏），拖拽过程中动的是
 * **容器**，内容原样跟着平移——观感是一整块板子被推下去。可手势的语义其实是「把这份内容
 * 收起来」：手往下拉，内容应当同时退后，这样松手无论落回展开还是消失，两个方向的过渡才是
 * 连续的。点遮罩、按返回走的是同一个 hide 动画（偏移同样从停稳位增到隐藏位），
 * 所以同一个模型顺带也兜住这两条路径——不分别给三条路径写动效，那会得到三套略有出入的手感。
 *
 * 为什么是纯逻辑、不 import android：下面这些公式与常量是本文件唯一的产出，
 * 抽出来就能进本机单测（见 CONTRIBUTING 第 3 节）。参数调错不会崩，只会让下拉变得
 * 迟钝或过火，而这类问题在真机上极难复现——必须有断言钉住。
 */
object SheetRecedeModel {
    /**
     * 拉到底时内容最多缩到多小。
     *
     * 6% 是「看得见退后、又不至于像被缩小镜处理过」的档位。再大，内容与面板底板之间的
     * 缝隙就开始读作「两块东西」。缩放只承担「深度」，不承担「离开」——那是淡出的活。
     */
    const val MAX_SHRINK = 0.06f

    /**
     * 拉到底时内容最多淡到多少。
     *
     * 0.45 意味着最淡时还剩 55% 不透明度。刻意不淡到 0：遮罩本身已经在压暗，
     * 再淡成透明会让内容先于容器消失，反而暴露「下面什么都没有」。
     */
    const val MAX_FADE = 0.45f

    /**
     * 缩放原点：横向居中、纵向贴顶。
     *
     * 内容往下收时上沿要贴住拖柄，从中心缩会变成「一小块往中间聚」——
     * 那读作内容自己塌了，而不是被推走。
     */
    const val ORIGIN_X = 0.5f
    const val ORIGIN_Y = 0f

    /** 胶囊圆角占比：50% 即两端半圆，拖柄拉伸时圆头形状不变 */
    const val HANDLE_CORNER = 50

    /** 拖柄静止时的宽度（dp）：与 M3 默认把手同宽，替换后不改变面板外观 */
    const val HANDLE_MIN_DP = 32f

    /** 拖柄被拉动时的宽度（dp）：变长是「抓住了」的信号，比变粗更容易读 */
    const val HANDLE_MAX_DP = 56f

    /** 拖柄高度（dp） */
    const val HANDLE_HEIGHT_DP = 4f

    /**
     * 拖柄占位高度（dp）：22 + 4 + 22。
     *
     * 必须与 M3 默认把手的占位完全一致，否则换成自绘把手会把整张面板顶高或压低，
     * 连带影响下面所有内容的落位与滚动手感。
     */
    const val HANDLE_SLOT_DP = 48f

    /** 静止时拖柄的不透明度：比正文弱，不抢视线 */
    const val HANDLE_ALPHA = 0.5f

    /** 拉到底时的不透明度：加深读作「已被抓住」 */
    const val HANDLE_ALPHA_ACTIVE = 0.92f

    /**
     * 触发「松手就会关掉」提示的位置（下拉进度的比例）。
     *
     * 与 M3 的 positional threshold 无关——那个是「松手判定的位置」，这里只是**告知**。
     * 取 0.35 而不是 0.5：M3 的松手判定里，「拖过一半」本来就已经会关，
     * 等到一半才提示等于马后炮。三成五正好是内容已经明显退开、还能反悔的位置。
     */
    const val DISMISS_HINT_AT = 0.35f

    /** 提示位的回滞下限：回到这条线以下才允许再次提示，避免在阈值上蹭出连续震动 */
    const val HINT_RESET_BELOW = 0.2f

    /**
     * 下拉进度 0..1（几何量，未加曲线）。
     *
     * @param offsetPx 面板当前纵向偏移（屏幕坐标系，越大越靠下）
     * @param restPx 面板停稳时的偏移（基线；拖拽中只会变大）
     * @param travelPx 拉到底所需的行程。取面板自身高度：M3 的隐藏锚点在**父容器下沿**，
     *   展开锚点在「父容器下沿 − 面板高度」，两者之差正好是面板高度——用量出来的面板高度
     *   比自己算「父容器减停稳位」更省一次布局回读，也不会因底部内边距差出几十像素。
     *
     * 任何非有限值都按 0 处理：面板还没测量完时偏移是 NaN，
     * 让它漏进来会算出 NaN 缩放，图层会在那一帧整块不绘制。
     */
    fun progressOf(
        offsetPx: Float,
        restPx: Float,
        travelPx: Float,
    ): Float {
        if (!offsetPx.isFinite() || !restPx.isFinite() || travelPx <= 0f) return 0f
        return ((offsetPx - restPx) / travelPx).coerceIn(0f, 1f)
    }

    /**
     * 退开量随下拉进度的**曲线**：一比一，不加缓动。
     *
     * 一开始写的是平方（前段压住、后段加速），真机上否掉了：面板是 1:1 跟手走的，
     * 内容却要「先不动、越拖越快」，两者速度曲线不一致，读起来像内容被手指拖住不放。
     * 位移与退开量用同一条比例，才是「我推走它、它就退」的物理感。
     *
     * 那手指杂动怎么办？不需要专门处理：进度是按面板高度归一的（这块面板约 1287px），
     * 十几像素的抖动只折出 1% 的退开量，肉眼读不到。
     *
     * 保留这个函数而不是直接写 p：它是唯一能整体换曲线的地方，
     * 将来若要试别的形状（比如触底前收得更狠），改这一处就够。
     */
    fun curveOf(progress: Float): Float {
        if (!progress.isFinite()) return 0f
        return progress.coerceIn(0f, 1f)
    }

    /**
     * 某个下拉进度下的「退开」配方。
     *
     * 整块面板用**同一组参数**，不按内容分层。试过给每块内容不同的退开量
     * （前面的留下、后面的塌更多），真机上读作「各块自己在缩」：这块面板只有三块内容，
     * 层数太少，叠不出纵深、只叠出参差。纵深改由拖柄的拉伸与头卡的色相承担。
     */
    fun specAt(progress: Float): RecedeSpec {
        val eased = curveOf(progress)
        return RecedeSpec(
            scale = 1f - MAX_SHRINK * eased,
            alpha = 1f - MAX_FADE * eased,
        )
    }

    /** 拖柄在某个下拉进度下的宽度（dp） */
    fun handleWidthDp(progress: Float): Float = HANDLE_MIN_DP + (HANDLE_MAX_DP - HANDLE_MIN_DP) * curveOf(progress)

    /** 拖柄在某个下拉进度下的不透明度 */
    fun handleAlpha(progress: Float): Float = HANDLE_ALPHA + (HANDLE_ALPHA_ACTIVE - HANDLE_ALPHA) * curveOf(progress)

    /**
     * 这一次读数是否该给一次「松手就会关掉」的轻震。
     *
     * [hinted] 由调用方持有并在 [hintShouldReset] 为真时清零：一次拖拽只提示一次，
     * 否则手指在阈值附近来回蹭会把触觉变成噪音。
     */
    fun shouldHint(
        progress: Float,
        hinted: Boolean,
    ): Boolean {
        val p = if (progress.isFinite()) progress else 0f
        return !hinted && p >= DISMISS_HINT_AT
    }

    /** 提示是否该复位（回到回滞下限以下，下次拖拽才会再提示） */
    fun hintShouldReset(progress: Float): Boolean {
        val p = if (progress.isFinite()) progress else 0f
        return p < HINT_RESET_BELOW
    }
}

/**
 * 下拉时内容图层的两个量：缩放与不透明度。
 *
 * 合成一个 data class 而不是两个函数：调用点在 graphicsLayer 里一次要取两个值，
 * 分开算会让「同一个进度」被求值两遍，也让单测要各自钉一遍边界。
 */
data class RecedeSpec(
    val scale: Float,
    val alpha: Float,
) {
    companion object {
        /** 没在拖：原样 */
        val None = RecedeSpec(scale = 1f, alpha = 1f)
    }
}
