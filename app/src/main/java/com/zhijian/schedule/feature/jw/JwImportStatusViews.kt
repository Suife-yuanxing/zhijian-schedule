package com.zhijian.schedule.feature.jw

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.jw.AdapterSteps
import com.zhijian.schedule.core.theme.CourseColors
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.RippleLoader
import com.zhijian.schedule.core.ui.TapButton
import com.zhijian.schedule.core.ui.TapIconButton
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.island.Contrast
import com.zhijian.schedule.core.ui.metal.CardTone
import com.zhijian.schedule.core.ui.metal.metalCard

/** WCAG 2.1 AA 对正文（非大字）的对比度门槛：本页所有次要文字都按它求解 */
private const val MIN_BODY_CONTRAST = 4.5

// 教务导入流程的「状态与反馈」区块：步骤进度、登录指引、加载进度、失败浮层、WAF 提示条。
//
// 这些都是叠在 WebView 之上的薄壳，只读状态 + 回调，自己不持有业务状态
// （导入流程的步骤划分见 JwImportScreen.kt 顶部注释）。

/**
 * 本页所有「次要文字」的取值口径：从 [CourseColors.textMuted] 往 [CourseColors.text] 混到
 * 与底色达到 WCAG AA 4.5:1 为止。
 *
 * 为什么不能直接用 textMuted：六套预设里 muted 压在 bg 上的实测对比度是
 * 纸本 3.53 / 清爽极简 3.26 / 多彩 3.62 / 墨夜 4.34 / 玻璃 4.38——只有动态取色（4.40）
 * 勉强擦线，其余全部低于正文门槛。本页的状态行是**用户唯一照着做的下一步**，
 * 它是提示而不是装饰，压不达标就等于把指引藏起来。
 *
 * 达标即停（[Contrast.mixUntil] 的语义）：多混一步，次要信息就会抢主信息的权重。
 */
@Composable
internal fun jwMuted(colors: CourseColors): Color =
    Contrast.mixUntil(colors.textMuted, colors.text, colors.bg, MIN_BODY_CONTRAST)

/**
 * 步骤进度 + 下面一步提示：本页唯一常驻的「现在该做什么」。
 *
 * ## 为什么从三行文字压成「一行进度 + 两行提示」
 * 改造前这段是两段纯文字：一段「① 登录 → ② 学生课表 → ③ 抓取课表」用 12sp 排成一句，
 * 一段 11sp 的整句状态（「请用学号 / 密码登录；校外网络请先连校园 WiFi…」）。
 * 实测占 84dp，却只表达「我在第几步、下一步做什么」。
 *
 * 现在同样的信息交给三个通道：
 * - **进度点**给出位置（已完成打勾、当前实心高亮、未开始留灰点）；
 * - **字重**区分当前步与其余步，读者扫一眼就知道停在哪；
 * - **两行状态**先说要做什么（正文色 + Medium），再说前置条件（达标次要色 + 小一号）。
 *
 * 版式换了、信息一条没少：四档文案逐字沿用改造前实现（见 [jwStatusText]）。
 *
 * ## 为什么进度条用两字标签
 * 各校菜单名不同（强智「学生课表」、eams5「我的课表」、URP「本学期课表」），
 * 长标签会把主进度条撑成三种宽度、整行跟着跳。所以固定两字，真实页面名留在状态行里，
 * 并另外用右侧徽标把「识别到哪家教务」显示出来。
 */
@Composable
internal fun GuideBar(
    colors: CourseColors,
    onLoginPage: Boolean,
    onLoggedInPage: Boolean,
    onAuthserverPage: Boolean,
    steps: AdapterSteps?,
    adapterName: String? = null,
) {
    val page = steps?.timetablePage ?: DEFAULT_TIMETABLE_PAGE
    val stage =
        jwStageOf(
            onLoginPage = onLoginPage,
            onAuthserverPage = onAuthserverPage,
            onLoggedInPage = onLoggedInPage,
        )
    val muted = jwMuted(colors)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            JwStepProgress(colors = colors, muted = muted, stage = stage)
            Spacer(Modifier.weight(1f))
            Text(
                text = adapterLabel(adapterName),
                color = if (adapterName != null) colors.accent else muted,
                fontSize = 10.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Spacer(Modifier.height(3.dp))
        val status = jwStatusText(stage, page)
        Text(
            text = status.primary,
            // 当前阶段已就绪（FETCH）时这句话不再是命令而是确认，交给强调色收尾；
            // 其余阶段是行动指令，用正文色保证在任何预设下都读得清。
            color = if (stage == JwStage.FETCH) colors.accent else colors.text,
            fontSize = 12.5.sp,
            lineHeight = 17.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (status.secondary.isNotEmpty()) {
            Text(
                text = status.secondary,
                color = muted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 三个进度点：已完成 → 打勾；当前 → 实心强调色；未开始 → 描边灰点。
 *
 * 点与点之间用 10dp 的 1dp 短横连接，读作「一条流程」而不是三个并列标签；
 * 连接线在「已完成」那一段换成强调色，进度因此可以从左往右一眼扫出来。
 */
@Composable
private fun JwStepProgress(
    colors: CourseColors,
    muted: Color,
    stage: JwStage,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        JwStepLabels.forEachIndexed { index, label ->
            if (index > 0) {
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .size(width = 9.dp, height = 1.dp)
                        .background(if (index <= stage.stepIndex) colors.accent else colors.border),
                )
            }
            val done = index < stage.stepIndex
            val active = index == stage.stepIndex
            if (done) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = colors.accent,
                    modifier = Modifier.size(10.dp),
                )
            } else {
                Box(
                    Modifier
                        .size(if (active) 7.dp else 6.dp)
                        .clip(RoundedCornerShape(percent = 50))
                        .background(if (active) colors.accent else colors.border),
                )
            }
            Spacer(Modifier.size(4.dp))
            Text(
                text = label,
                color = if (done || active) colors.text else muted,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    }
}

/** 未识别教务时的通用叫法，与改造前的文案保持一致 */
internal const val DEFAULT_TIMETABLE_PAGE = "学生课表"

/**
 * 一句话说明「这家教务被识别成什么」。
 *
 * 未识别时给出支持列表——改造前这一行是「未识别教务类型（当前支持：强智、URP、…）」，
 * 现在改成「可继续登录后手动抓取」，因为**能抓的学校远多于适配器列表**（还有脚本引擎），
 * 把「支持哪些适配器」摆在首页只会让用户误以为自己没戏。
 */
internal fun adapterLabel(adapterName: String?): String =
    if (adapterName != null) "识别为 " + adapterName else "未识别教务类型，仍可登录后直接抓取"

/** WebView 顶部加载进度条：淡入淡出，不打断网页本身 */
@Composable
internal fun TopProgressBar(
    visible: Boolean,
    progress: Float,
    colors: CourseColors,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(durationMillis = Motion.DUR_FAST)),
        exit = fadeOut(tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut)),
        modifier = modifier,
    ) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = colors.accent,
            trackColor = colors.border,
        )
    }
}

/** 网页加载失败浮层 */
@Composable
internal fun PageErrorOverlay(
    error: String?,
    onReload: () -> Unit,
    colors: CourseColors,
) {
    AnimatedVisibility(
        visible = error != null,
        enter = fadeIn(tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut)),
        exit = fadeOut(tween(durationMillis = Motion.DUR_FAST)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.bg.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 32.dp),
            ) {
                Text("网页加载失败", color = colors.text, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = error.orEmpty(),
                    color = jwMuted(colors),
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                TapButton(onClick = onReload) { Text("重新加载") }
            }
        }
    }
}

/** 阻塞式加载浮层 */
@Composable
internal fun LoadingOverlay(
    visible: Boolean,
    colors: CourseColors,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut)),
        exit = fadeOut(tween(durationMillis = Motion.DUR_FAST)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.bg.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            // 涟漪加载态：四层同心圆共用 2s 周期、每层相位差 0.1，读成一片向外扩散的水波。
            // 选它替代 CircularProgressIndicator 的理由：这是全屏阻塞遮罩，用户会盯着看数秒，
            // 而系统转圈是匀速旋转、无节拍变化；涟漪有明确的扩散节奏，等待感更短。
            // reduce-motion 由 RippleLoader 内部处理，关闭动画时自动退化为静止同心圆环。
            RippleLoader(
                modifier = Modifier.size(96.dp),
                color = colors.accent,
                rings = 4,
            )
        }
    }
}

/** 校外直连被 WAF 拦截时的顶部提示横幅：从上滑入，可关闭 */
@Composable
internal fun HttpErrorBanner(
    code: Int,
    onDismiss: () -> Unit,
    colors: CourseColors,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = code >= 400,
        enter =
            slideInVertically(
                animationSpec = tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut),
            ) { -it } + fadeIn(tween(durationMillis = Motion.DUR_MEDIUM)),
        exit =
            slideOutVertically(tween(durationMillis = Motion.DUR_MEDIUM)) { -it } +
                fadeOut(tween(durationMillis = Motion.DUR_FAST)),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.surfaceVariant)
                .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text =
                    "学校服务器返回 " + code +
                        "：源站暂时不可用或校外网络被拦截。可改用统一认证登录（见上方），" +
                        "或连校园 WiFi / WebVPN 后重试",
                color = colors.text,
                fontSize = 12.sp,
                lineHeight = 17.sp,
                modifier = Modifier.weight(1f),
            )
            TapTextButton(onClick = onDismiss) { Text("知道了", fontSize = 12.sp) }
        }
    }
}

/**
 * HTTP 明文地址提示：只在地址以 http:// 开头时出现。
 *
 * 一句话说清「谁看得到」与「该怎么做」，不展开成安全说教——用户在登录页上，
 * 再多的话也不会读。判断放在这里而不是调用点，是为了让 JwImportScreen 少一个分支
 * （那一屏的圈复杂度本来就贴着阈值）。
 */
@Composable
internal fun PlainHttpNotice(
    url: String,
    colors: CourseColors,
) {
    if (!url.trim().startsWith("http://", ignoreCase = true)) return
    Text(
        text = "该地址是 HTTP 明文，登录凭据可能被同一网络中的他人看到；学校支持时请改用 HTTPS",
        color = colors.accent,
        fontSize = 10.5.sp,
        lineHeight = 15.sp,
        maxLines = 2,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 2.dp),
    )
}

/**
 * 「已填入保存的教务账号」提示条。
 *
 * 只在**这一页确实有可回填的凭据、且当前停在登录页**时出现：凭据在、但已经进了课表页时
 * 再说「已填入」是错的（那里没有输入框）。
 *
 * 文案只说用户当下看得到的那一件事——**不写「已为你保存密码」**：回填与保存是两件事，
 * 保存要用户按气泡里的「记住」才发生，把两者说成一件事会让用户以为密码已经存好了。
 */
@Composable
internal fun CredentialFillHint(
    fill: JwCredentialFill?,
    visible: Boolean,
    colors: CourseColors,
) {
    if (fill == null || !visible) return
    Text(
        text = "已填入保存的教务账号（" + fill.username + "），确认无误后点登录",
        color = colors.textMuted,
        fontSize = 10.5.sp,
        lineHeight = 15.sp,
        maxLines = 2,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 2.dp),
    )
}

/**
 * 「记住这次登录？」确认气泡。
 *
 * ## 为什么是气泡不是对话框
 * 用户此刻正在做的是「登录 → 进课表页 → 抓取」，弹出 [AlertDialog] 会打断这条路径，
 * 而这个决定本身**不阻塞**任何后续操作。所以它贴着网页底部浮起来、随时可以不理它，
 * 关掉也不影响抓课表——拒绝一次保存不该有后果。
 *
 * ## 为什么带关闭叉
 * 气泡浮在网页上会盖住底部一小条，用户可能正要去点页面上的菜单。给一个能立刻消失的出口，
 * 比"必须二选一"舒服；叉与「不用」等价，都不写盘。
 *
 * ## 为什么只显示打码账号、不显示密码
 * 这个气泡要回答的是「记的是不是我这个号」。密码摆在屏幕上没有任何收益，
 * 只是多一处会被旁人看到的可能——明文留在 ViewModel 的私有字段里，到写盘为止都不出内存。
 *
 * 位置与动效沿用本页浮层的一致语言：从底部滑入 + 淡入，[Motion] 的时长与曲线，
 * 关闭动画比出现更短（快速消失读作"被打断了"，慢消失读作"还在考虑"）。
 */
@Composable
internal fun CredentialSaveBubble(
    prompt: JwCredentialPrompt?,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    colors: CourseColors,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = prompt != null,
        enter =
            slideInVertically(
                animationSpec = tween(durationMillis = Motion.DUR_MEDIUM, easing = Motion.EaseOut),
            ) { it } + fadeIn(tween(durationMillis = Motion.DUR_MEDIUM)),
        exit =
            slideOutVertically(tween(durationMillis = Motion.DUR_FAST)) { it } +
                fadeOut(tween(durationMillis = Motion.DUR_FAST)),
        modifier = modifier,
    ) {
        val muted = jwMuted(colors)
        val shape = RoundedCornerShape(16.dp)
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .clip(shape)
                // RAISED：这是浮在网页之上、等着被处理的一张卡，读作"焦点"。
                // 与设置页那些 SURFACE 档的分组卡拉开一档，用户一眼看出它是临时的
                .metalCard(shape = shape, color = colors.surface, tone = CardTone.RAISED)
                .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "记住这次登录？",
                    color = colors.text,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                TapIconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(
                        imageVector = Icons.Filled.Close,
                        contentDescription = "不记住本次登录",
                        tint = muted,
                        modifier = Modifier.size(13.dp),
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                // 账号打码 + 站点：够认出"是哪个号在哪所学校"，又不把学号原样摊在屏幕上
                text = "账号 " + prompt?.maskedUsername.orEmpty() + " · " + prompt?.siteLabel.orEmpty(),
                color = muted,
                fontSize = 11.5.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TapTextButton(onClick = onDismiss) { Text("不用", fontSize = 12.5.sp) }
                Spacer(Modifier.width(4.dp))
                TapButton(
                    onClick = onSave,
                    hapticType = HapticFeedbackType.Confirm,
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
                ) {
                    Text("记住", fontSize = 12.5.sp)
                }
            }
        }
    }
}
