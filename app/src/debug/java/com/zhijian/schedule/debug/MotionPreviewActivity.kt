package com.zhijian.schedule.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.ui.AnimatedGradientCheckMark
import com.zhijian.schedule.core.ui.RippleLoader
import com.zhijian.schedule.core.ui.accentGradient

/**
 * 仅 debug 构建的动效预览页：在真机上肉眼验证 RippleLoader 与 GradientCheckMark。
 * 不参与 release 打包（位于 app/src/debug，release 源集不包含它）。
 *
 * 每个样本下面都标了它验证的是哪一条约束——没有标注的样本等于没验证：
 * 尺寸、主题派生、未选中可见性、浅底对比，四件事各有自己的样本。
 */
class MotionPreviewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MotionPreviewScreen() }
    }
}

private val PAPER_BG = Color(0xFFFBF7F2)
private val PAPER_TEXT = Color(0xFF8A7566)
private val THEME_ACCENT = Color(0xFFC0412C)
private val SUCCESS_GREEN = Color(0xFF2BB673)

@Composable
private fun MotionPreviewScreen() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(PAPER_BG)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Section("RippleLoader · 96dp（组件默认尺寸）")
        RippleLoader(modifier = Modifier.size(96.dp), color = THEME_ACCENT, rings = 4)

        Section("RippleLoader · 72dp（ParsingContent 用的尺寸）")
        RippleLoader(modifier = Modifier.size(72.dp), color = THEME_ACCENT, rings = 3)

        Section("RippleLoader · 160dp（放大看层间距是否均匀）")
        RippleLoader(modifier = Modifier.size(160.dp), color = THEME_ACCENT, rings = 4)

        Section("GradientCheckMark · 未选（带描边）/ 选中 / 主题派生 48dp")
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            // 这一枚专门验证「浅色底上未选中必须可见」：不传 idleRingColor 时它几乎会消失
            AnimatedGradientCheckMark(checked = false, idleRingColor = THEME_ACCENT)
            AnimatedGradientCheckMark(checked = true, gradientColors = accentGradient(THEME_ACCENT))
            AnimatedGradientCheckMark(
                checked = true,
                markSize = 48.dp,
                gradientColors = accentGradient(SUCCESS_GREEN),
                idleRingColor = SUCCESS_GREEN,
            )
        }

        Section("GradientCheckMark · 原作紫青配色（对照，仅预览用）")
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
            AnimatedGradientCheckMark(checked = false)
            AnimatedGradientCheckMark(checked = true)
        }

        Spacer(Modifier.height(48.dp))
        Text(
            text = "关掉系统动画后再看一遍：涟漪应停在静止同心圆环，勾应直接落位不画线。",
            color = PAPER_TEXT,
            modifier = Modifier.padding(bottom = 32.dp),
        )
    }
}

@Composable
private fun Section(label: String) {
    Spacer(Modifier.height(32.dp))
    Text(text = label, color = PAPER_TEXT)
    Spacer(Modifier.height(14.dp))
}
