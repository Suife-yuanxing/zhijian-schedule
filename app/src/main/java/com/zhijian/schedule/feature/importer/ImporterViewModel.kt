package com.zhijian.schedule.feature.importer

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.core.model.CourseDraft
import com.zhijian.schedule.core.model.ImportPreview
import com.zhijian.schedule.core.model.ImportSource
import com.zhijian.schedule.core.repository.ScheduleRepository
import com.zhijian.schedule.core.util.TimeUtils
import com.zhijian.schedule.feature.importer.ocr.ImageOcr
import com.zhijian.schedule.feature.importer.ocr.OcrLine
import com.zhijian.schedule.feature.importer.ocr.OcrTimetableParser
import com.zhijian.schedule.feature.importer.parsers.CsvParser
import com.zhijian.schedule.feature.importer.parsers.IcsParser
import com.zhijian.schedule.feature.importer.parsers.JsonParser
import com.zhijian.schedule.feature.importer.parsers.TableMapping
import com.zhijian.schedule.feature.importer.parsers.WakeUpParser
import com.zhijian.schedule.feature.importer.parsers.XlsxParser
import com.zhijian.schedule.feature.importer.qr.QrPayload
import com.zhijian.schedule.feature.importer.qr.QrShareState
import com.zhijian.schedule.feature.importer.qr.renderQrBitmap
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ImportUiState {
    data object Idle : ImportUiState
    data object Parsing : ImportUiState
    data object OcrReview : ImportUiState
    data object QrScan : ImportUiState
    data object QrShare : ImportUiState
    data class Preview(val preview: ImportPreview) : ImportUiState
    data class Done(val summary: String) : ImportUiState
    data class Error(val message: String) : ImportUiState
}

data class OcrReviewState(
    val drafts: List<CourseDraft>,
    val warnings: List<String>,
    val totalWeeks: Int = 20,
)

@HiltViewModel
class ImporterViewModel @Inject constructor(
    private val repository: ScheduleRepository,
    private val imageOcr: ImageOcr,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    var state by mutableStateOf<ImportUiState>(ImportUiState.Idle)
        private set

    var applyConflicts by mutableStateOf(false)
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
                qrShare = QrShareState(
                    courseCount = courses.size,
                    message = "课程较多（${courses.size} 门），一张二维码装不下；" +
                        "可改用「备份与恢复」导出 JSON 文件分享",
                )
                return@launch
            }
            val bitmap = withContext(Dispatchers.Default) {
                runCatching { renderQrBitmap(payload, 1024) }.getOrNull()
            }
            qrShare = if (bitmap != null) {
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
            val totalSections = repository.activeSections.first().size.coerceAtLeast(1)
            val pages = mutableListOf<List<OcrLine>>()
            val warnings = mutableListOf<String>()
            uris.forEachIndexed { index, uri ->
                parsingNote = "正在识别第 ${index + 1}/${uris.size} 张截图…"
                val lines = runCatching { imageOcr.recognize(uri) }
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
            val result = withContext(Dispatchers.Default) {
                OcrTimetableParser.parse(pages, totalWeeks, totalSections)
            }
            parsingNote = ""
            if (result.drafts.isEmpty()) {
                warnings += result.warnings
                state = ImportUiState.Error(
                    warnings.firstOrNull() ?: "没能识别出课程，试试带上星期表头的完整课表截图",
                )
                return@launch
            }
            ocrReview = OcrReviewState(result.drafts, warnings + result.warnings, totalWeeks)
            state = ImportUiState.OcrReview
        }
    }

    fun updateOcrDraft(index: Int, draft: CourseDraft) {
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
            val preview = repository.buildPreview(review.drafts, ImportSource.OCR)
            state = ImportUiState.Preview(preview)
        }
    }

    // ---------- 文本 / 文件 ----------

    fun importFromText(text: String) {
        viewModelScope.launch {
            state = ImportUiState.Parsing
            parsingNote = ""
            val totalWeeks = repository.activeTimetable.first()?.totalWeeks ?: 20
            val isIcs = looksLikeIcs(text)
            val result = withContext(Dispatchers.Default) {
                if (isIcs) parseIcs(text, totalWeeks) else WakeUpParser.parse(text, totalWeeks)
            }
            handleResult(result, if (isIcs) ImportSource.ICS else ImportSource.WAKEUP)
        }
    }

    fun importFromUri(uri: Uri) {
        viewModelScope.launch {
            state = ImportUiState.Parsing
            parsingNote = ""
            val totalWeeks = repository.activeTimetable.first()?.totalWeeks ?: 20
            val source = sourceOf(uri)
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        ?: return@runCatching TableMapping.Result(emptyList(), 0, listOf("无法读取文件"))
                    val text = decodeText(bytes)
                    when {
                        looksLikeIcs(text) -> parseIcs(text, totalWeeks)
                        isZip(bytes) -> XlsxParser.parse(ByteArrayInputStream(bytes), totalWeeks)
                        looksLikeJson(bytes) -> JsonParser.parse(text, totalWeeks)
                        else -> CsvParser.parse(text, totalWeeks)
                    }
                }.getOrElse { e ->
                    TableMapping.Result(emptyList(), 0, listOf("读取失败：${e.message}"))
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

    private suspend fun parseIcs(text: String, totalWeeks: Int): TableMapping.Result {
        val timetable = repository.activeTimetable.first()
        val sections = repository.activeSections.first()
        return IcsParser.parse(
            text = text,
            totalWeeks = totalWeeks,
            semesterStart = TimeUtils.parseDate(timetable?.startDate),
            sections = sections,
        )
    }

    private suspend fun handleResult(result: TableMapping.Result, source: ImportSource) {
        if (result.drafts.isEmpty()) {
            state = ImportUiState.Error(result.warnings.firstOrNull() ?: "未解析到课程数据")
            return
        }
        val preview = repository.buildPreview(result.drafts, source)
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

    private fun looksLikeIcs(text: String): Boolean =
        text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
            .startsWith("BEGIN:VCALENDAR", ignoreCase = true)

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size > 2 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    private fun looksLikeJson(bytes: ByteArray): Boolean {
        val head = String(bytes.copyOfRange(0, minOf(64, bytes.size)), Charsets.UTF_8).trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        return head.startsWith("{") || head.startsWith("[")
    }

    /** UTF-8 优先，失败回退 GBK（国内 Excel 导出常见编码） */
    private fun decodeText(bytes: ByteArray): String {
        return try {
            Charset.forName("UTF-8").newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: Exception) {
            runCatching { Charset.forName("GBK").decode(ByteBuffer.wrap(bytes)).toString() }
                .getOrElse { String(bytes, Charsets.UTF_8) }
        }
    }
}
