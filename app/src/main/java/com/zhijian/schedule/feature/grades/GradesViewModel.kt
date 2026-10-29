package com.zhijian.schedule.feature.grades

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.core.data.db.GradeDao
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.jw.GradeFetcher
import com.zhijian.schedule.core.jw.GradeRecord
import com.zhijian.schedule.core.jw.JwAdapterRegistry
import com.zhijian.schedule.core.jw.JwException
import com.zhijian.schedule.core.jw.QiangZhiAdapter
import com.zhijian.schedule.core.jw.toEntity
import com.zhijian.schedule.core.jw.toRecord
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltViewModel
class GradesViewModel
    @Inject
    constructor(
        private val client: OkHttpClient,
        private val gradeDao: GradeDao,
        private val settingsStore: SettingsStore,
    ) : ViewModel() {
        data class UiState(
            val loading: Boolean = false,
            val records: List<GradeRecord> = emptyList(),
            val terms: List<String> = emptyList(),
            val selectedTerm: String? = null,
            val error: String? = null,
            val needsLogin: Boolean = false,
            val fetchedAt: Long = 0L,
        )

        private val _state = MutableStateFlow(UiState())
        val state: StateFlow<UiState> = _state.asStateFlow()

        init {
            viewModelScope.launch {
                gradeDao.observeAll().collect { rows ->
                    val records = rows.map { it.toRecord() }
                    _state.update {
                        it.copy(
                            records = records,
                            terms = records.map { r -> r.term }.distinct().sortedDescending(),
                        )
                    }
                }
            }
            viewModelScope.launch {
                settingsStore.settings.collect { s -> _state.update { it.copy(fetchedAt = s.gradesFetchedAt) } }
            }
            refresh()
        }

        fun selectTerm(term: String?) {
            _state.update { it.copy(selectedTerm = term) }
        }

        fun refresh() {
            if (_state.value.loading) return
            viewModelScope.launch {
                _state.update { it.copy(loading = true, error = null, needsLogin = false) }
                val url = settingsStore.settings.first().jwUrl
                if (url.isBlank()) {
                    _state.update { it.copy(loading = false, needsLogin = true) }
                    return@launch
                }
                if (JwAdapterRegistry.forUrl(url)?.id != QiangZhiAdapter.id) {
                    _state.update {
                        it.copy(loading = false, error = "当前教务系统暂不支持成绩抓取")
                    }
                    return@launch
                }
                runCatching { GradeFetcher.fetch(client, url) }
                    .onSuccess { records ->
                        gradeDao.replaceAll(records.map { it.toEntity() })
                        settingsStore.setGradesFetchedAt(System.currentTimeMillis())
                        _state.update { it.copy(loading = false) }
                    }.onFailure { e ->
                        val needsLogin = e is JwException.NeedsLogin
                        _state.update {
                            it.copy(
                                loading = false,
                                needsLogin = needsLogin,
                                error = if (needsLogin) null else (e.message ?: "抓取失败"),
                            )
                        }
                    }
            }
        }
    }
