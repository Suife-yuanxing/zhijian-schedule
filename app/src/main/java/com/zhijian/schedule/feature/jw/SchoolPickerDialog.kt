package com.zhijian.schedule.feature.jw

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zhijian.schedule.core.jw.SchoolCatalog
import com.zhijian.schedule.core.jw.SchoolProfile
import com.zhijian.schedule.core.jw.ensureLoaded
import com.zhijian.schedule.core.theme.LocalCourseColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 选校列表的检索上限：目录 1000+ 条，够用且不至于一次排版太多 */
private const val PICKER_LIMIT = 120
private const val SHEET_HEIGHT_FRACTION = 0.88f

private sealed interface SchoolRow {
    data class Header(
        val initial: Char,
    ) : SchoolRow

    data class Entry(
        val profile: SchoolProfile,
    ) : SchoolRow
}

/**
 * 「选择学校」底部弹层：检索 → 选中 → 自动填入教务地址。
 *
 * 目录来自 assets（`SchoolCatalogAssets.kt` 的 `ensureLoaded` 扩展），首次打开时在 IO 线程解一次。
 * 已验证（目录标记为「已实现配置」）与未实测条目同列，靠右侧标签区分——
 * 不隐藏未实测条目，但也不假装它们都能用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SchoolPickerDialog(
    onDismiss: () -> Unit,
    onPick: (SchoolProfile) -> Unit,
) {
    val colors = LocalCourseColors.current
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(SchoolCatalog.isLoaded) }

    LaunchedEffect(Unit) {
        if (!SchoolCatalog.isLoaded) {
            withContext(Dispatchers.IO) { SchoolCatalog.ensureLoaded(context) }
        }
        ready = true
    }

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(SHEET_HEIGHT_FRACTION)
                .padding(horizontal = 20.dp),
        ) {
            Text(
                text = "选择学校",
                style = MaterialTheme.typography.titleMedium,
                color = colors.text,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = "选中后自动填入教务地址，登录与抓取仍在下方网页里完成",
                color = colors.textMuted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜索学校 / 拼音 / 域名", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = colors.surface)
            val rows = remember(query, ready) { rowsFor(query) }
            if (!ready) {
                LoadingRow()
            } else if (rows.isEmpty()) {
                EmptyRow()
            } else {
                LazyColumn(Modifier.fillMaxWidth()) {
                    items(rows) { row -> SchoolRowItem(row, onPick) }
                }
            }
        }
    }
}

@Composable
private fun LoadingRow() {
    val colors = LocalCourseColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = colors.accent,
        )
    }
}

@Composable
private fun EmptyRow() {
    val colors = LocalCourseColors.current
    Text(
        text = "没有匹配的学校，可直接在上方地址栏手填教务地址",
        color = colors.textMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(vertical = 24.dp),
    )
}

@Composable
private fun SchoolRowItem(
    row: SchoolRow,
    onPick: (SchoolProfile) -> Unit,
) {
    val colors = LocalCourseColors.current
    when (row) {
        is SchoolRow.Header ->
            Text(
                text = row.initial.toString(),
                color = colors.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
            )
        is SchoolRow.Entry -> EntryRow(row.profile, onPick)
    }
}

@Composable
private fun EntryRow(
    profile: SchoolProfile,
    onPick: (SchoolProfile) -> Unit,
) {
    val colors = LocalCourseColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { onPick(profile) }
            .padding(vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = profile.name,
                color = colors.text,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (profile.verified) "已验证" else "未实测",
                color = if (profile.verified) colors.accent else colors.textMuted,
                fontSize = 10.sp,
            )
        }
        Text(
            text = profile.entry(),
            color = colors.textMuted,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 空检索按首字母分组，有关键词时走相关度排序（已验证优先） */
private fun rowsFor(query: String): List<SchoolRow> {
    if (query.isBlank()) {
        return SchoolCatalog.groups().flatMap { (initial, list) ->
            listOf(SchoolRow.Header(initial)) + list.map { SchoolRow.Entry(it) }
        }
    }
    return SchoolCatalog.search(query, PICKER_LIMIT).map { SchoolRow.Entry(it) }
}
