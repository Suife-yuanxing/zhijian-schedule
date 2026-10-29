package com.zhijian.schedule.feature.exam

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.core.data.db.ExamEntity
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.jw.ExamFetcher
import com.zhijian.schedule.core.jw.ExamParser
import com.zhijian.schedule.core.jw.JwAdapterRegistry
import com.zhijian.schedule.core.jw.JwException
import com.zhijian.schedule.core.jw.QiangZhiAdapter
import com.zhijian.schedule.core.model.ExamDraft
import com.zhijian.schedule.core.repository.ScheduleRepository
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
class ExamViewModel
    @Inject
    constructor(
        private val repository: ScheduleRepository,
        private val client: OkHttpClient,
        private val settingsStore: SettingsStore,
    ) : ViewModel() {
        data class UiState(
            val exams: List<ExamEntity> = emptyList(),
            val importing: Boolean = false,
            val message: String? = null,
            val needsLogin: Boolean = false,
            /** 当前教务为强智（考试抓取仅强智可用） */
            val jwAvailable: Boolean = false,
        )

        private val _state = MutableStateFlow(UiState())
        val state: StateFlow<UiState> = _state.asStateFlow()

        init {
            viewModelScope.launch {
                repository.activeExams.collect { exams -> _state.update { it.copy(exams = exams) } }
            }
            viewModelScope.launch {
                val url = settingsStore.settings.first().jwUrl
                _state.update {
                    it.copy(
                        jwAvailable =
                            url.isNotBlank() &&
                                JwAdapterRegistry.forUrl(url)?.id == QiangZhiAdapter.id,
                    )
                }
            }
        }

        fun save(
            name: String,
            room: String,
            seat: String,
            examTime: Long,
            note: String,
            examId: Long?,
        ) {
            viewModelScope.launch { repository.saveExam(name, room, seat, examTime, note, examId) }
        }

        fun delete(examId: Long) {
            viewModelScope.launch { repository.deleteExam(examId) }
        }

        fun importFromJw() {
            if (_state.value.importing) return
            viewModelScope.launch {
                _state.update { it.copy(importing = true, message = null, needsLogin = false) }
                val url = settingsStore.settings.first().jwUrl
                if (url.isBlank()) {
                    _state.update { it.copy(importing = false, needsLogin = true) }
                    return@launch
                }
                runCatching { ExamFetcher.fetch(client, url) }
                    .onSuccess { records ->
                        val drafts =
                            records.map {
                                ExamDraft(
                                    name = it.name,
                                    room = it.room,
                                    seat = it.seat,
                                    examTime = ExamParser.epochMillis(it.examTime),
                                    note = it.note,
                                )
                            }
                        val result = repository.applyExamMerge(drafts)
                        _state.update {
                            it.copy(importing = false, message = result?.summaryText() ?: "没有活动课表")
                        }
                    }.onFailure { e ->
                        val needsLogin = e is JwException.NeedsLogin
                        _state.update {
                            it.copy(
                                importing = false,
                                needsLogin = needsLogin,
                                message = if (needsLogin) null else (e.message ?: "抓取失败"),
                            )
                        }
                    }
            }
        }

        fun consumeMessage() = _state.update { it.copy(message = null, needsLogin = false) }
    }
