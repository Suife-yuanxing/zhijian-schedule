package com.zhijian.schedule.feature.importer.parsers

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

/**
 * 口令 / 粘贴文本的容错解析：
 * 1) 直接是 JSON；2) 直接是表格文本（CSV/制表符）；3) 内嵌 Base64 段（zlib/deflate/gzip）解压后再试。
 */
object WakeUpParser : TimetableParser {
    override val format = TimetableFormat.WAKEUP

    /** 兜底解析器：粘贴文本通道的最后手段，内部依次尝试 JSON / 表格 / Base64。 */
    override val channels = setOf(ParserChannel.PASTED_TEXT)

    override fun canParse(input: ParserInput): Boolean = true

    override fun parse(
        input: ParserInput,
        context: ParseContext,
    ): ParseResult = parse(input.text, context.totalWeeks)

    /** 直连入口：粘贴口令 / 文本 + 总周数。 */
    fun parse(
        text: String,
        totalWeeks: Int,
    ): ParseResult =
        parseOrNull(text.trim(), totalWeeks)
            ?: ParseResult(
                emptyList(),
                0,
                listOf("无法识别的口令格式，请改用「文件导入」或粘贴标准表格文本"),
            )

    /** 三种入口依次尝试：直接 JSON/表格 → 文本里的 Base64 段解码后再试一遍。全不成返回 null。 */
    private fun parseOrNull(
        trimmed: String,
        totalWeeks: Int,
    ): ParseResult? {
        if (trimmed.isEmpty()) return ParseResult.error("内容为空")
        // 1. 直接 JSON、2. 直接表格文本
        directParse(trimmed, totalWeeks)?.let { return it }
        // 3. 提取 Base64 段并解压
        return base64Payloads(trimmed).firstNotNullOfOrNull { payload -> payloadParse(payload, totalWeeks) }
    }

    /** 把一段文本当 JSON / CSV 解析；解析不出课程即视为不是这种格式 */
    private fun directParse(
        text: String,
        totalWeeks: Int,
    ): ParseResult? {
        if (text.startsWith("{") || text.startsWith("[")) {
            JsonParser.parse(text, totalWeeks).takeIf { it.drafts.isNotEmpty() }?.let { return it }
        }
        return CsvParser.parse(text, totalWeeks).takeIf { it.drafts.isNotEmpty() }
    }

    /** 解出来的明文同样可能是 JSON 或表格（表格只在出现分隔符时才当 CSV，避免把单列文本吃成课程） */
    private fun payloadParse(
        payload: String,
        totalWeeks: Int,
    ): ParseResult? {
        if (payload.startsWith("{") || payload.startsWith("[")) {
            JsonParser.parse(payload, totalWeeks).takeIf { it.drafts.isNotEmpty() }?.let { return it }
        }
        if (!payload.contains(",") && !payload.contains("\t")) return null
        return CsvParser.parse(payload, totalWeeks).takeIf { it.drafts.isNotEmpty() }
    }

    /** 文本里所有能解出 UTF-8 明文的 Base64 段 */
    private fun base64Payloads(text: String): List<String> =
        Regex("""[A-Za-z0-9+/=]{32,}""")
            .findAll(text)
            .mapNotNull { match ->
                val decoded = decodeBase64(match.value) ?: return@mapNotNull null
                val inflated = tryInflate(decoded) ?: decoded
                runCatching { String(inflated, Charsets.UTF_8) }.getOrNull()
            }.toList()

    private fun decodeBase64(text: String): ByteArray? =
        runCatching {
            Base64.decode(text, Base64.DEFAULT)
        }.getOrNull()

    private fun tryInflate(data: ByteArray): ByteArray? {
        // zlib 包装
        inflate(data, false)?.let { return it }
        // 原始 deflate
        inflate(data, true)?.let { return it }
        // gzip
        return runCatching {
            GZIPInputStream(data.inputStream()).use { it.readBytes() }
        }.getOrNull()
    }

    private fun inflate(
        data: ByteArray,
        nowrap: Boolean,
    ): ByteArray? =
        runCatching {
            val inflater = Inflater(nowrap)
            inflater.setInput(data)
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && inflater.needsInput()) break
                out.write(buffer, 0, n)
            }
            inflater.end()
            out.toByteArray().takeIf { it.isNotEmpty() }
        }.getOrNull()
}
