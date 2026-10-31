package com.zhijian.schedule.feature.importer

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.core.jw.SectionTimeDraft
import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.model.ImportPreview
import com.zhijian.schedule.core.model.ImportSource
import com.zhijian.schedule.core.repository.ScheduleRepository
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.feature.importer.ocr.ImageOcr
import com.zhijian.schedule.feature.importer.ocr.OcrLine
import com.zhijian.schedule.feature.importer.ocr.OcrTimetableParser
import com.zhijian.schedule.feature.importer.parsers.ParseContext
import com.zhijian.schedule.feature.importer.parsers.ParseResult
import com.zhijian.schedule.feature.importer.parsers.ParserChannel
import com.zhijian.schedule.feature.importer.parsers.ParserInput
import com.zhijian.schedule.feature.importer.parsers.TextDecoding
import com.zhijian.schedule.feature.importer.parsers.TimetableFormat
import com.zhijian.schedule.feature.importer.parsers.TimetableParsers
import com.zhijian.schedule.feature.importer.qr.QrPayload
import com.zhijian.schedule.feature.importer.qr.QrShareState
import com.zhijian.schedule.feature.importer.qr.renderQrBitmap
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface ImportUiState {
    data object Idle : ImportUiState

    data object Parsing : ImportUiState

    data object OcrReview : ImportUiState

    data object QrScan : ImportUiState

    data object QrShare : ImportUiState

    data class Preview(
        val preview: ImportPreview,
    ) : ImportUiState

    data class Done(
        val summary: String,
    ) : ImportUiState

    data class Error(
        val message: String,
    ) : ImportUiState
}

data class OcrReviewState(
    val drafts: List<CourseDraft>,
    val warnings: List<String>,
    val totalWeeks: Int = 20,
    /** 截图时间轴上读到的作息 */
    val sectionTimes: List<SectionTimeDraft> = emptyList(),
)

@HiltViewModel
class ImporterViewModel
    @Inject
    constructor(
        private val repository: ScheduleRepository,
        private val imageOcr: ImageOcr,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        var state by mutableStateOf<ImportUiState>(ImportUiState.Idle)
            private set

        /** 手动导入默认连手动编辑过的课程一起覆盖（导入 = 换课表）；开关可关回「跳过冲突」 */
        var applyConflicts by mutableStateOf(true)
            private set

        var ocrReview by mutableStateOf<OcrReviewState?>(null)
            private set

        var qrShare by mutableStateOf<QrShareState?>(null)
            private set

        var parsingNote by mutableStateOf("")
            private set

        fun updateApplyConflicts(value: Boolean) {
            applyConflicts = value
        }

        fun reset() {
            state = ImportUiState.Idle
            ocrReview = null
            qrShare = null
            parsingNote = ""
        }

        // ---------- 二维码互传 ----------

        fun openQrScan() {
            qrShare = null
            state = ImportUiState.QrScan
        }

        fun openQrShare() {
            qrShare = null
            state = ImportUiState.QrShare
            viewModelScope.launch {
                val courses = repository.activeCourses.first()
                if (courses.isEmpty()) {
                    qrShare = QrShareState(message = "当前课表还没有课程，先导入或添加课程再分享")
                    return@launch
                }
                val payload = withContext(Dispatchers.Default) { QrPayload.encode(courses) }
                if (payload.size > QrPayload.MAX_QR_BYTES) {
                    qrShare =
                        QrShareState(
                            courseCount = courses.size,
                            message =
                                "课程较多（${courses.size} 门），一张二维码装不下；" +
                                    "可改用「备份与恢复」导出 JSON 文件分享",
                        )
                    return@launch
                }
                val bitmap =
                    withContext(Dispatchers.Default) {
                        runCatching { renderQrBitmap(payload, 1024) }.getOrNull()
                    }
                qrShare =
                    if (bitmap != null) {
                        QrShareState(bitmap = bitmap, courseCount = courses.size, payload = payload)
                    } else {
                        QrShareState(message = "二维码生成失败，请重试")
                    }
            }
        }

        fun importFromQr(raw: ByteArray) {
            viewModelScope.launch {
                state = ImportUiState.Parsing
                parsingNote = "正在解析分享码…"
                val totalWeeks = repository.activeTimetable.first()?.totalWeeks ?: 20
                val result = withContext(Dispatchers.Default) { QrPayload.decode(raw, totalWeeks) }
                handleResult(result, ImportSource.QR)
            }
        }

        // ---------- 截图识别 ----------

        fun importFromImages(uris: List<Uri>) {
            if (uris.isEmpty()) return
            viewModelScope.launch {
                state = ImportUiState.Parsing
                ocrReview = null
                val timetable = repository.activeTimetable.first()
                val totalWeeks = timetable?.totalWeeks ?: 20
                val totalSections =
                    repository.activeSections
                        .first()
                        .size
                        .coerceAtLeast(1)
                val pages = mutableListOf<List<OcrLine>>()
                val warnings = mutableListOf<String>()
                uris.forEachIndexed { index, uri ->
                    parsingNote = "正在识别第 ${index + 1}/${uris.size} 张截图…"
                    val lines =
                        runCatching { imageOcr.recognize(uri) }
                            .getOrElse {
                                warnings += "第 ${index + 1} 张图片读取失败"
                                emptyList()
                            }
                    if (lines.isNotEmpty()) pages += lines
                }
                parsingNote = "正在解析课表结构…"
                if (pages.isEmpty()) {
                    parsingNote = ""
                    state = ImportUiState.Error("没能从图片中识别出文字，换张清晰的截图再试")
                    return@launch
                }
                val result =
                    withContext(Dispatchers.Default) {
                        OcrTimetableParser.parse(pages, totalWeeks, totalSections)
                    }
                parsingNote = ""
                if (result.drafts.isEmpty()) {
                    warnings += result.warnings
                    state =
                        ImportUiState.Error(
                            warnings.firstOrNull() ?: "没能识别出课程，试试带上星期表头的完整课表截图",
                        )
                    return@launch
                }
                ocrReview =
                    OcrReviewState(
                        result.drafts,
                        warnings + result.warnings,
                        totalWeeks,
                        result.sectionTimes,
                    )
                state = ImportUiState.OcrReview
            }
        }

        fun updateOcrDraft(
            index: Int,
            draft: CourseDraft,
        ) {
            val review = ocrReview ?: return
            if (index !in review.drafts.indices) return
            ocrReview = review.copy(drafts = review.drafts.toMutableList().also { it[index] = draft })
        }

        fun removeOcrDraft(index: Int) {
            val review = ocrReview ?: return
            if (index !in review.drafts.indices) return
            ocrReview = review.copy(drafts = review.drafts.toMutableList().also { it.removeAt(index) })
        }

        fun confirmOcrReview() {
            val review = ocrReview ?: return
            if (review.drafts.isEmpty()) {
                state = ImportUiState.Error("没有可导入的课程")
                return
            }
            viewModelScope.launch {
                // 截图上的时间轴比本地默认作息权威：跟着课表一起更新
                val preview =
                    repository.buildPreview(
                        review.drafts,
                        ImportSource.OCR,
                        sectionTimes = review.sectionTimes,
                    )
                state = ImportUiState.Preview(preview)
            }
        }

        // ---------- 文本 / 文件 ----------

        fun importFromText(text: String) {
            viewModelScope.launch {
                state = ImportUiState.Parsing
                parsingNote = ""
                val ctx = parseContext()
                val parsed =
                    withContext(Dispatchers.Default) {
                        TimetableParsers.parse(ParserInput.ofText(text), ctx, ParserChannel.PASTED_TEXT)
                    }
                // 粘贴通道只有两种来源：日历文本记 ICS，其余归入口令
                val source = if (parsed.format == TimetableFormat.ICS) ImportSource.ICS else ImportSource.WAKEUP
                handleResult(parsed.result, source)
            }
        }

        fun importFromUri(uri: Uri) {
            viewModelScope.launch {
                state = ImportUiState.Parsing
                parsingNote = ""
                val ctx = parseContext()
                val source = sourceOf(uri)
                val result =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            val bytes =
                                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                                    ?: return@runCatching ParseResult.error("无法读取文件")
                            val input = ParserInput.ofFile(bytes, TextDecoding.decode(bytes), uri.lastPathSegment)
                            TimetableParsers.parse(input, ctx, ParserChannel.FILE).result
                        }.getOrElse { e ->
                            ParseResult.error("读取失败：${e.message}")
                        }
                    }
                handleResult(result, source)
            }
        }

        fun apply() {
            val preview = (state as? ImportUiState.Preview)?.preview ?: return
            viewModelScope.launch {
                repository.applyImport(preview, applyConflicts)
                state = ImportUiState.Done(preview.summary())
            }
        }

        /** 解析上下文：一次取齐总周数、开学日期与作息节次，各解析器按需取用。 */
        private suspend fun parseContext(): ParseContext {
            val timetable = repository.activeTimetable.first()
            return ParseContext(
                totalWeeks = timetable?.totalWeeks ?: 20,
                semesterStart = TimeUtils.parseDate(timetable?.startDate),
                sections = repository.activeSections.first(),
            )
        }

        private suspend fun handleResult(
            result: ParseResult,
            source: ImportSource,
        ) {
            if (result.drafts.isEmpty()) {
                state = ImportUiState.Error(result.warnings.firstOrNull() ?: "未解析到课程数据")
                return
            }
            val preview =
                repository.buildPreview(
                    result.drafts,
                    source,
                    sectionTimes = result.sectionTimes,
                )
            state = ImportUiState.Preview(preview)
        }

        private fun sourceOf(uri: Uri): ImportSource {
            val name = (uri.lastPathSegment ?: "").lowercase()
            return when {
                name.endsWith(".xlsx") || name.endsWith(".xls") -> ImportSource.XLSX
                name.endsWith(".json") -> ImportSource.JSON
                name.endsWith(".ics") -> ImportSource.ICS
                else -> ImportSource.CSV
            }
        }
    }
