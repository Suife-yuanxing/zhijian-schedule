package com.zhijian.schedule.core.ui.island

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.zhijian.schedule.core.theme.CourseColors

/**
 * 一条要走浮岛通道的提示：**刚刚发生了什么**，五秒后自己收起。
 *
 * ## 为什么从「教务凭据回填专用」提出来
 * 这条通道最初只服务一件事（回填保存的教务账号），于是类型叫
 * `JwCredentialFillNotice`、挂在 `JwImportViewModel` 上。但浮岛本身没有
 * 任何「凭据」语义——它是全站唯一一条「说完就收」的提示通道。
 * 第二个用它的人（开学日期缺来源的提醒）出现时，若照着抄一个
 * `StartDateNotice` + 第二个 overlay，同一条通道就会有两套位置、两套时长。
 *
 * 所以数据与挂载点都提到这里：谁有事要说，就 post 一条 [IslandNotice]。
 *
 * ## 为什么带 [at]
 * 它同时是这条提示的**身份**与进度条的重置令牌（见 [IslandHint] 的 resetKey）。
 * 同一件事再次发生时（用户又登录了一次、又存了一次课表信息），
 * 令牌必须变，否则进度条不重走，用户会以为那一下没反应。
 * 用时间戳而不是布尔值：布尔值上「又发生了一次」与「还挂着上一轮」没有区别。
 */
data class IslandNotice(
    /** 语气；决定图标（对勾 / 叉号）、颜色，以及失败时那一下短震与抖动 */
    val tone: IslandHintTone,
    /** 一行说明，直接上屏 */
    val message: String,
    /** 这一次结局发生的时刻；见类注释 */
    val at: Long,
)

/**
 * 浮岛提示的唯一挂载点。
 *
 * ## 为什么单独一个组件，而不是各处直接写 [IslandHint]
 * [IslandHint] 有五个参数（可见性、文案、重置令牌、结束回调、颜色），其中三个是
 * 「这次要说什么」的包装。散着写的话，每个调用点都要重复一遍
 * `notice?.message.orEmpty()` / `notice?.at` / `notice?.tone ?: Done` 这三段可空取值，
 * 而任何一处写错（例如把 tone 的默认值写成 Failed）都不会报错，只是那条提示
 * 长期带叉号。
 *
 * @param notice 要显示的内容；null 时只保证不崩（正常流程里 visible 同时为 false）
 * @param visible 与 [notice] 分开是**为了退场动画有内容可演**：进度条走完时若直接
 *   把数据清空，胶囊会在 150ms 的淡出里变成一个只剩图标和进度条的空壳
 * @param onFinished 进度走完，调用方据此把 [visible] 置 false
 */
@Composable
fun IslandNoticeHost(
    notice: IslandNotice?,
    visible: Boolean,
    onFinished: () -> Unit,
    colors: CourseColors,
    modifier: Modifier = Modifier,
) {
    IslandHint(
        visible = visible,
        text = notice?.message.orEmpty(),
        resetKey = notice?.at,
        onFinished = onFinished,
        colors = colors,
        tone = notice?.tone ?: IslandHintTone.Done,
        modifier = modifier,
    )
}
