package com.zhijian.schedule.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.ui.metal.LocalMetalClock
import com.zhijian.schedule.core.ui.metal.metalSurface

/**
 * 二级页面统一顶栏：18sp 半粗标题 + 返回箭头 + 可选操作区。
 *
 * ## 参数顺序是语义，不是排版
 *
 * [titleContent] 在 [actions] **之前**，[actions] 是最后一个参数。这不是随手排的：
 * Kotlin 的尾随 lambda 绑定的是**最后一个参数**。上一版把 [titleContent] 放在最后，
 * 于是课程编辑页那句
 *
 * ```
 * SecondaryTopBar(title = ..., onBack = ...) { ...删除按钮... }
 * ```
 *
 * 里的 lambda 被绑到了 [titleContent] 而不是 [actions]——标题在整个页面里**从未被画出来**，
 * 顶栏只剩一个返回箭头。它在真机上的表现就是「这一页没有任何文字说明自己是干什么的」，
 * 而代码看起来毫无问题（编译通过、删除按钮在编辑态也确实出现了）。
 *
 * 把 [actions] 放到最后之后，尾随 lambda 的默认语义就是「这一页的右上角动作」，
 * 与 Compose 生态里 `Scaffold` / `TopAppBar` 的习惯一致；要自定义标题必须显式写
 * `titleContent = {...}`，那正是我们想要的显式性。
 *
 * ## 三个标题概念的分工
 *
 * - [title]：这一页**在做什么**（添加课程 / 编辑课程 / 成绩查询…），必答项；
 * - [caption]：标题下的一行小字，补一句**这一页拿来做完什么**（例如「填写课程信息，保存后回到课表」）。
 *   只在需要时才给：顶栏每多一行字，正文就少一行——它不是所有页面的默认配置；
 * - [titleContent]：需要**行为**的标题（目前只有教务导入页的隐藏调试入口），默认 null。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecondaryTopBar(
    title: String,
    onBack: () -> Unit,
    caption: String? = null,
    titleContent: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalCourseColors.current
    val metal = LocalCoursePreset.current.metal
    val metalClock = LocalMetalClock.current
    TopAppBar(
        title = {
            if (titleContent != null) {
                titleContent()
            } else {
                Column {
                    Text(
                        text = title,
                        color = colors.text,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 18.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (caption != null) {
                        Spacer(Modifier.height(1.dp))
                        Text(
                            text = caption,
                            color = colors.textMuted,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        },
        navigationIcon = {
            TapIconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.text,
                )
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bg),
        modifier =
            if (metal != null && metalClock != null) {
                // 顶栏是页面上第一块金属：横向拉丝（brushAngle 默认 0° 就是横拉丝），
                // 滚动时内容从它下方穿过，它是"压在纸面上的金属压条"
                Modifier.metalSurface(
                    shape = RoundedCornerShape(bottomStart = 18.dp, bottomEnd = 18.dp),
                    clock = metalClock,
                    chromeOverride = 0.42f,
                    faceOverride = colors.bg,
                )
            } else {
                Modifier
            },
    )
}
