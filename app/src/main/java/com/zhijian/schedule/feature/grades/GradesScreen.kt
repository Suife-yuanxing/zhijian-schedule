package com.zhijian.schedule.feature.grades

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zhijian.schedule.core.jw.GradeParser
import com.zhijian.schedule.core.jw.GradeRecord
import com.zhijian.schedule.core.jw.GradeStats
import com.zhijian.schedule.core.jw.GradeTrend
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.SecondaryTopBar
import com.zhijian.schedule.core.ui.TapIconButton
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.buttonFeedback
import com.zhijian.schedule.core.ui.selectedPop
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val FAIL_COLOR = Color(0xFFD35339)
private val UPDATE_FMT = DateTimeFormatter.ofPattern("M月d日 HH:mm")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GradesScreen(
    onBack: () -> Unit,
    onOpenJwImport: () -> Unit,
    viewModel: GradesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = LocalCourseColors.current

    val term = state.selectedTerm?.takeIf { it in state.terms }
    val filtered = remember(state.records, term) {
        if (term == null) state.records else state.records.filter { it.term == term }
    }
    val stats = remember(filtered) { GradeParser.stats(filtered) }
    val trend = remember(state.records) { GradeTrend.perTerm(state.records) }
    val latestTerm = remember(state.records) { GradeTrend.latest(state.records) }
    val groups = remember(filtered) {
        filtered.groupBy { it.term }.toSortedMap(compareByDescending { it })
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(colors.bg),
    ) {
        SecondaryTopBar(
            title = "成绩查询",
            onBack = onBack,
            actions = {
                TapIconButton(onClick = viewModel::refresh) {
                    if (state.loading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = colors.accent,
                        )
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新", tint = colors.text)
                    }
                }
            },
        )

        when {
            state.records.isEmpty() && state.loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent)
                }
            }

            state.records.isEmpty() -> EmptyState(
                needsLogin = state.needsLogin,
                error = state.error,
                onRetry = viewModel::refresh,
                onOpenJwImport = onOpenJwImport,
            )

            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 20.dp, end = 20.dp, top = 4.dp, bottom = 40.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "summary") {
                    Spacer(Modifier.height(4.dp))
                    SummaryCard(stats, term, state.fetchedAt, state.error != null || state.needsLogin, latestTerm)
                }
                if (trend.size >= 2) {
                    item(key = "trend") { TermTrendCard(trend) }
                }
                item(key = "chips") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TermChip(label = "全部", selected = term == null) {
                            viewModel.selectTerm(null)
                        }
                        state.terms.forEach { t ->
                            TermChip(label = t, selected = term == t) {
                                viewModel.selectTerm(t)
                            }
                        }
                    }
                }
                groups.forEach { (groupTerm, list) ->
                    item(key = "h_$groupTerm") {
                        GroupHeader(groupTerm, GradeParser.stats(list))
                    }
                    items(list, key = { "${it.term}|${it.code}|${it.name}|${it.score}|${it.retakeTerm}" }) { record ->
                        GradeRow(record)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(
    stats: GradeStats,
    term: String?,
    fetchedAt: Long,
    failed: Boolean,
    latestTerm: GradeTrend.TermSummary?,
) {
    val colors = LocalCourseColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Row {
            BigStat(format2(stats.weightedGpa), "加权绩点", colors.accent)
            Spacer(Modifier.width(28.dp))
            BigStat(format1(stats.totalCredits), "已修学分", colors.text)
            Spacer(Modifier.width(28.dp))
            BigStat("${stats.courseCount}", "门课程", colors.text)
            if (stats.failCount > 0) {
                Spacer(Modifier.width(28.dp))
                BigStat("${stats.failCount}", "挂科", FAIL_COLOR)
            }
        }
        // 「全部学期」口径不含补考重修与等级制课程，单列最新学期供快速对照
        if (term == null && latestTerm != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "最新学期 ${latestTerm.term}：绩点 ${format2(latestTerm.gpa)} · " +
                    "${format1(latestTerm.credits)} 学分",
                color = colors.textMuted,
                fontSize = 12.sp,
            )
        }
        stats.weightedAvg?.let {
            Spacer(Modifier.height(6.dp))
            Text("加权平均分 ${format1(it)}", color = colors.textMuted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        val updateText = when {
            fetchedAt <= 0L -> "尚未同步，点右上角刷新"
            failed -> "刷新失败，显示上次缓存 · ${formatTime(fetchedAt)}"
            else -> "更新于 ${formatTime(fetchedAt)}"
        }
        Text(
            text = "${term ?: "全部学期"} · $updateText",
            color = if (failed) FAIL_COLOR else colors.textMuted,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun TermTrendCard(summaries: List<GradeTrend.TermSummary>) {
    val colors = LocalCourseColors.current
    val maxGpa = summaries.mapNotNull { it.gpa }.maxOrNull() ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Text("学期绩点趋势", color = colors.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "只计有绩点课程 · 补考重修不参与",
            color = colors.textMuted,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(Modifier.height(10.dp))
        summaries.forEach { s ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    s.term,
                    color = colors.textMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.width(88.dp),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(colors.border.copy(alpha = 0.4f)),
                ) {
                    val fraction = ((s.gpa ?: 0.0) / maxGpa.coerceAtLeast(0.01)).toFloat()
                    Box(
                        Modifier
                            .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(colors.accent),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    s.gpa?.let { format2(it) } ?: "—",
                    color = colors.text,
                    fontSize = 11.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(34.dp),
                )
            }
        }
    }
}

@Composable
private fun BigStat(value: String, label: String, color: Color) {
    val colors = LocalCourseColors.current
    Column {
        Text(value, color = color, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(label, color = colors.textMuted, fontSize = 11.sp)
    }
}

@Composable
private fun GroupHeader(term: String, stats: GradeStats) {
    val colors = LocalCourseColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(term, color = colors.textMuted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.weight(1f))
        val gpa = stats.weightedGpa
        Text(
            text = "绩点 ${format2(gpa)} · ${format1(stats.totalCredits)} 学分",
            color = colors.textMuted,
            fontSize = 11.sp,
        )
    }
}

@Composable
private fun GradeRow(record: GradeRecord) {
    val colors = LocalCourseColors.current
    val failed = GradeParser.isFail(record)
    val pillColor = if (failed) FAIL_COLOR else colors.accent
    Row(
        Modifier
            .fillMaxWidth()
            .background(colors.surface, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(record.name, color = colors.text, fontSize = 15.sp)
            val tags = buildList {
                if (record.retakeTerm.isNotBlank() || record.scoreFlag.isNotBlank()) add("补重修")
                if (record.category.isNotBlank()) add(record.category)
                if (record.attr.isNotBlank()) add(record.attr)
            }
            if (tags.isNotEmpty()) {
                Text(tags.joinToString(" · "), color = colors.textMuted, fontSize = 11.sp)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Box(
                Modifier
                    .widthIn(min = 46.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(pillColor.copy(alpha = 0.14f))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(record.score, color = pillColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            val gpaText = record.gpa?.let { " · 绩点 ${format1(it)}" } ?: ""
            Text("${format1(record.credit)} 学分$gpaText", color = colors.textMuted, fontSize = 11.sp)
        }
    }
}

@Composable
private fun TermChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    FilterChip(
        selected = selected,
        onClick = {
            onClick()
        },
        label = { Text(label, fontSize = 12.sp) },
        interactionSource = source,
        modifier = Modifier
            .buttonFeedback(source, pressedScale = 0.94f)
            .selectedPop(selected),
    )
}

@Composable
private fun EmptyState(
    needsLogin: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onOpenJwImport: () -> Unit,
) {
    val colors = LocalCourseColors.current
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = when {
                    needsLogin -> "尚未登录教务，或登录会话已过期"
                    error != null -> error
                    else -> "还没有成绩数据"
                },
                color = colors.textMuted,
                fontSize = 14.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = if (needsLogin) "先在「教务导入」里登录一次，再回来刷新" else "成绩来自教务系统的「学生个人考试成绩」",
                color = colors.textMuted,
                fontSize = 12.sp,
            )
            Spacer(Modifier.height(14.dp))
            Row {
                if (needsLogin) {
                    TapTextButton(onClick = onOpenJwImport) { Text("去教务导入", color = colors.accent) }
                }
                TapTextButton(onClick = onRetry) { Text("刷新", color = colors.accent) }
            }
        }
    }
}

private fun format2(v: Double?): String = v?.let { trimmed(round2(it)) } ?: "—"

private fun format1(v: Double): String = trimmed(round2(v))

private fun round2(v: Double): Double = Math.round(v * 100) / 100.0

private fun trimmed(v: Double): String =
    if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()

private fun formatTime(epoch: Long): String =
    Instant.ofEpochMilli(epoch).atZone(ZoneId.systemDefault()).toLocalDateTime().format(UPDATE_FMT)
