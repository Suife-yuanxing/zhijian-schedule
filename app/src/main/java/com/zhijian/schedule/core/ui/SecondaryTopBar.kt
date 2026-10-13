package com.zhijian.schedule.core.ui

import androidx.compose.foundation.layout.RowScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.theme.LocalCourseColors

/** 二级页面统一顶栏：18sp 半粗标题 + 返回箭头 + 可选操作区 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecondaryTopBar(
    title: String,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = LocalCourseColors.current
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold, fontSize = 18.sp) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "返回",
                    tint = colors.text,
                )
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(containerColor = colors.bg),
    )
}
