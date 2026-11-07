package com.zhijian.schedule.feature.schedule
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.SeedShape
import com.zhijian.schedule.core.ui.TapButton
import com.zhijian.schedule.core.ui.TapTextButton
import kotlin.math.min

/**
 * 空课表的引导卡片。
 *
 * [onAdd] 带上按钮自身在根坐标系里的矩形：这条路和「点空课块」是同一件事（都从被点的
 * 控件长出来进编辑页），不该因为入口长得不一样就换成横滑。退化时给 [Rect.Zero]，
 * 由调用方决定跳过扩散直接导航。
 */
@Composable
internal fun EmptyScheduleCard(
    onAdd: (seed: SeedShape) -> Unit,
    onImport: () -> Unit,
) {
    val colors = LocalCourseColors.current
    // 「添加课程」按钮的位置：页面从它长出来。角半径取按钮胶囊半径（短边一半），
    // 于是轮廓是「被点按钮本身」而不是一个外接圆。
    var addBounds by remember { mutableStateOf(Rect.Zero) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .padding(32.dp)
                .fillMaxWidth()
                .background(colors.surface, RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "还没有课程",
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "手动添加，或从教务 / Excel / CSV 一键导入",
                style = MaterialTheme.typography.bodyMedium,
                color = colors.textMuted,
            )
            Spacer(Modifier.height(18.dp))
            Row {
                TapButton(
                    onClick = {
                        val corner = min(addBounds.width, addBounds.height) / 2f
                        onAdd(SeedShape(bounds = addBounds, cornerRadius = corner))
                    },
                    modifier = Modifier.onGloballyPositioned { addBounds = it.boundsInRoot() },
                ) { Text("添加课程") }
                Spacer(Modifier.width(12.dp))
                TapTextButton(onClick = onImport) { Text("导入课表") }
            }
        }
    }
}
