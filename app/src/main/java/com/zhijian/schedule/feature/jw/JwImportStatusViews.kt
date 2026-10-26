package com.zhijian.schedule.feature.jw

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.CourseColors
import com.zhijian.schedule.core.ui.Motion
import com.zhijian.schedule.core.ui.TapButton
import com.zhijian.schedule.core.ui.TapTextButton

/**
 * 教务导入流程的「状态与反馈」区块：登录指引、加载进度、失败浮层、WAF 提示条。
 *
 * 这些都是叠在 WebView 之上的薄壳，只读状态 + 回调，自己不持有业务状态
 * （导入流程的步骤划分见 JwImportScreen.kt 顶部注释）。
 */

/** 步骤引导条：① 登录 → ② 学生课表 → ③ 抓取课表，并按当前落脚页给出下一步提示 */
@Composable
internal fun GuideBar(
    colors: CourseColors,
    onLoginPage: Boolean,
    onLoggedInPage: Boolean,
    onAuthserverPage: Boolean,
) {
    val status = when {
        onLoggedInPage -> "已进入教务系统，点右下「抓取课表」" to colors.accent
        onAuthserverPage -> "请在「统一身份认证」页用学号 + 密码登录" to colors.accent
        onLoginPage -> "请用学号 / 密码登录；校外网络请先连校园 WiFi，或经 WebVPN 访问" to colors.accent
        else -> "登录后打开「学生课表」页面，再点「抓取课表」" to colors.textMuted
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("① 登录", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(" → ", color = colors.textMuted, fontSize = 12.sp)
            Text("② 学生课表", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text(" → ", color = colors.textMuted, fontSize = 12.sp)
            Text("③ 抓取课表", color = colors.text, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Text(status.first, color = status.second, fontSize = 11.sp)
    }
}

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
        enter = fadeIn(tween(durationMillis = Motion.DurFast)),
        exit = fadeOut(tween(durationMillis = Motion.DurMedium, easing = Motion.EaseOut)),
        modifier = modifier,
    ) {
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth(),
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
        enter = fadeIn(tween(durationMillis = Motion.DurMedium, easing = Motion.EaseOut)),
        exit = fadeOut(tween(durationMillis = Motion.DurFast)),
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
                    color = colors.textMuted,
                    fontSize = 12.sp,
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
internal fun LoadingOverlay(visible: Boolean, colors: CourseColors) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(durationMillis = Motion.DurMedium, easing = Motion.EaseOut)),
        exit = fadeOut(tween(durationMillis = Motion.DurFast)),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(colors.bg.copy(alpha = 0.55f)),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(color = colors.accent)
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
        enter = slideInVertically(
            animationSpec = tween(durationMillis = Motion.DurMedium, easing = Motion.EaseOut),
        ) { -it } + fadeIn(tween(durationMillis = Motion.DurMedium)),
        exit = slideOutVertically(tween(durationMillis = Motion.DurMedium)) { -it } +
            fadeOut(tween(durationMillis = Motion.DurFast)),
        modifier = modifier,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.accent.copy(alpha = 0.14f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "学校服务器返回 $code：源站暂时不可用或校外网络被拦截。可改用统一认证登录（见上方），或连校园 WiFi / WebVPN 后重试",
                color = colors.text,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f),
            )
            TapTextButton(onClick = onDismiss) { Text("知道了", fontSize = 12.sp) }
        }
    }
}
