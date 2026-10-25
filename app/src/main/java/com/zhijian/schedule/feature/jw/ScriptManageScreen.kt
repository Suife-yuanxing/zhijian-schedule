package com.zhijian.schedule.feature.jw

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.BuildConfig
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.jw.script.ScriptCatalog
import com.zhijian.schedule.core.jw.script.ScriptDownloader
import com.zhijian.schedule.core.jw.script.ScriptIndexFns
import com.zhijian.schedule.core.jw.script.ScriptOption
import com.zhijian.schedule.core.jw.script.ScriptStore
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.JellySwitch
import com.zhijian.schedule.core.ui.SecondaryTopBar
import com.zhijian.schedule.core.ui.TapIconButton
import com.zhijian.schedule.core.ui.TapTextButton
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ScriptManageUiState(
    val options: List<ScriptOption> = emptyList(),
    val indexUrl: String = "",
    val busy: Boolean = false,
    val message: String? = null,
)

@HiltViewModel
class ScriptManageViewModel @Inject constructor(
    private val catalog: ScriptCatalog,
    private val store: ScriptStore,
    private val downloader: ScriptDownloader,
    private val settingsStore: SettingsStore,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(ScriptManageUiState())
    val state: StateFlow<ScriptManageUiState> = _state

    init {
        refresh()
        viewModelScope.launch {
            _state.update { it.copy(indexUrl = settingsStore.settings.first().jwScriptIndexUrl) }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(options = catalog.all(null)) }
        }
    }

    fun setIndexUrl(url: String) {
        viewModelScope.launch {
            settingsStore.setJwScriptIndexUrl(url)
            _state.update {
                it.copy(indexUrl = url.trim(), message = if (url.isBlank()) "已清空脚本仓库地址" else "脚本仓库已保存")
            }
        }
    }

    fun toggle(id: String, enabled: Boolean) {
        store.setEnabled(id, enabled)
        refresh()
    }

    fun remove(id: String) {
        store.remove(id)
        refresh()
    }

    /** 本地导入 .js（spec §3.2）：信任来源 = 用户主动选择，无哈希要求 */
    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
            }
            val result = text?.let { store.install(it, origin = "local") }
                ?: ScriptStore.InstallResult.Rejected("无法读取所选文件")
            val message = when (result) {
                is ScriptStore.InstallResult.Ok ->
                    if (result.replaced) "已替换为「${result.script.name}」v${result.script.version}"
                    else "已安装「${result.script.name}」v${result.script.version}"
                is ScriptStore.InstallResult.Rejected -> result.reason
            }
            _state.update { it.copy(busy = false, message = message) }
            refresh()
        }
    }

    /** 检查更新（spec §3.1）：拉 index → 按 version/minAppVersion 过滤 → 下载 + SHA-256 → 安装 */
    fun checkUpdates() {
        viewModelScope.launch {
            val indexUrl = _state.value.indexUrl
            if (indexUrl.isBlank()) {
                _state.update { it.copy(message = "请先填写脚本仓库地址（index.json）") }
                return@launch
            }
            _state.update { it.copy(busy = true, message = null) }
            val indexJson = when (val r = downloader.fetchText(indexUrl)) {
                is ScriptDownloader.Result.Ok -> r.text
                is ScriptDownloader.Result.Failed -> {
                    _state.update { it.copy(busy = false, message = r.message) }
                    return@launch
                }
            }
            val index = ScriptIndexFns.parse(indexJson).getOrElse {
                _state.update { s -> s.copy(busy = false, message = "脚本清单格式错误：${it.message}") }
                return@launch
            }
            val installed = store.list().associateBy { it.id }
            var updated = 0
            val skipped = mutableListOf<String>()
            index.scripts.forEach { entry ->
                if (!ScriptIndexFns.atLeast(BuildConfig.VERSION_NAME, entry.minAppVersion)) {
                    skipped += "${entry.name}（需要 App ${entry.minAppVersion}）"
                    return@forEach
                }
                val current = installed[entry.id]
                if (current != null && entry.version <= current.version) return@forEach
                when (val r = downloader.fetchText(entry.url, entry.sha256)) {
                    is ScriptDownloader.Result.Ok -> {
                        val install = store.install(
                            r.text,
                            origin = "remote",
                            sha256 = entry.sha256,
                            onlyIfNewer = current != null,
                        )
                        if (install is ScriptStore.InstallResult.Ok) updated++
                        if (install is ScriptStore.InstallResult.Rejected) {
                            skipped += "${entry.name}（${install.reason}）"
                        }
                    }
                    is ScriptDownloader.Result.Failed -> skipped += "${entry.name}（${r.message}）"
                }
            }
            val parts = mutableListOf<String>()
            parts += if (updated > 0) "安装/更新 $updated 个脚本" else "已是最新"
            if (skipped.isNotEmpty()) parts += "跳过：${skipped.joinToString("、")}"
            _state.update { it.copy(busy = false, message = parts.joinToString("；")) }
            refresh()
        }
    }
}

@Composable
fun ScriptManageScreen(
    onBack: () -> Unit,
    viewModel: ScriptManageViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = LocalCourseColors.current
    var indexDraft by remember(state.indexUrl) { mutableStateOf(state.indexUrl) }
    var pendingDelete by remember { mutableStateOf<ScriptOption?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let(viewModel::importFromUri)
    }

    Column(Modifier.fillMaxSize().background(colors.bg)) {
        SecondaryTopBar(title = "课程脚本", onBack = onBack)

        LazyColumn(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, top = 8.dp, bottom = 12.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "intro") {
                Text(
                    text = "脚本负责把各学校的教务页面解析成课表；安装后可在「教务系统导入」页右上角选择运行。",
                    color = colors.textMuted,
                    fontSize = 12.sp,
                )
            }
            if (state.options.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = "还没有脚本。点下方「导入 .js」安装，或配置仓库后「检查更新」。",
                        color = colors.textMuted,
                        fontSize = 13.sp,
                    )
                }
            }
            items(state.options, key = { it.id }) { option ->
                ScriptRow(
                    option = option,
                    onToggle = { viewModel.toggle(option.id, it) },
                    onDelete = { pendingDelete = option },
                )
            }
        }

        state.message?.let { message ->
            Text(
                text = message,
                color = colors.accent,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp),
            )
        }

        Column(
            Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(horizontal = 20.dp, vertical = 10.dp),
        ) {
            Text("脚本仓库", color = colors.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(
                text = "指向 index.json 的 https 地址；留空则只能用内置与本地导入",
                color = colors.textMuted,
                fontSize = 11.sp,
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = indexDraft,
                    onValueChange = { indexDraft = it },
                    placeholder = { Text("https://…/index.json", fontSize = 12.sp) },
                    textStyle = MaterialTheme.typography.bodySmall,
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TapTextButton(onClick = { viewModel.setIndexUrl(indexDraft) }) { Text("保存") }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TapTextButton(
                    onClick = viewModel::checkUpdates,
                    enabled = !state.busy,
                ) { Text("检查更新", fontSize = 13.sp) }
                TapTextButton(
                    onClick = { filePicker.launch("*/*") },
                    enabled = !state.busy,
                ) { Text("导入 .js", fontSize = 13.sp) }
            }
        }
    }

    pendingDelete?.let { option ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除脚本") },
            text = { Text("确定删除「${option.name}」吗？内置脚本删除后可随时重装恢复。") },
            confirmButton = {
                TapTextButton(onClick = {
                    viewModel.remove(option.id)
                    pendingDelete = null
                }) { Text("删除") }
            },
            dismissButton = {
                TapTextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ScriptRow(
    option: ScriptOption,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val colors = LocalCourseColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(colors.surface)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(option.name, color = colors.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Text(option.subtitle(), color = colors.textMuted, fontSize = 11.sp)
            if (option.entryHint.isNotBlank()) {
                Text(option.entryHint, color = colors.textMuted, fontSize = 11.sp)
            }
        }
        if (option.origin == "builtin") {
            Text(
                text = "内置",
                color = colors.accent,
                fontSize = 11.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.accent.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
            Spacer(Modifier.width(8.dp))
        } else {
            TapIconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", tint = colors.textMuted)
            }
        }
        JellySwitch(checked = option.enabled, onCheckedChange = onToggle)
    }
}
