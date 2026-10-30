package com.zhijian.schedule.core.ui

import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.theme.LocalCoursePreset
import com.zhijian.schedule.core.ui.metal.LocalMetalClock
import com.zhijian.schedule.core.ui.metal.metalSurface

/** 二级页面统一顶栏：18sp 半粗标题 + 返回箭头 + 可选操作区 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecondaryTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalCourseColors.current
    val metal = LocalCoursePreset.current.metal
    val metalClock = LocalMetalClock.current
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp) },
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
