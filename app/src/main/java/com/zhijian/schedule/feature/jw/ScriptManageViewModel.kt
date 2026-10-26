package com.zhijian.schedule.feature.jw

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.BuildConfig
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.jw.script.ScriptCatalog
import com.zhijian.schedule.core.jw.script.ScriptDownloader
import com.zhijian.schedule.core.jw.script.ScriptIndexFns
import com.zhijian.schedule.core.jw.script.ScriptOption
import com.zhijian.schedule.core.jw.script.ScriptStore
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

/** 课程脚本管理页的界面状态（列表、仓库地址、进行中、反馈文案） */
internal data class ScriptManageUiState(
    val options: List<ScriptOption> = emptyList(),
    val indexUrl: String = "",
    val busy: Boolean = false,
    val message: String? = null,
)

/**
 * 课程脚本管理页的状态机：内置脚本目录、本地安装/删除/启停、仓库检查更新。
 *
 * 脚本的读写全在 [ScriptStore] 里（含哈希与版本校验），这里只做编排与文案。
 */
@HiltViewModel
internal class ScriptManageViewModel @Inject constructor(
    private val catalog: ScriptCatalog,
    private val store: ScriptStore,
    private val downloader: ScriptDownloader,
    private val settingsStore: SettingsStore,
    @param:ApplicationContext private val context: Context,
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
