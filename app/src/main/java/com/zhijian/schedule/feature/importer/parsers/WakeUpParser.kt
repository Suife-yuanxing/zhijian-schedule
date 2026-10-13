package com.zhijian.schedule.feature.importer.parsers

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.Inflater

/**
 * 口令 / 粘贴文本的容错解析：
 * 1) 直接是 JSON；2) 直接是表格文本（CSV/制表符）；3) 内嵌 Base64 段（zlib/deflate/gzip）解压后再试。
 */
object WakeUpParser {

    fun parse(text: String, totalWeeks: Int): TableMapping.Result {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return TableMapping.Result(emptyList(), 0, listOf("内容为空"))

        // 1. 直接 JSON
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val r = JsonParser.parse(trimmed, totalWeeks)
            if (r.drafts.isNotEmpty()) return r
        }

        // 2. 直接表格文本
        val csvResult = CsvParser.parse(trimmed, totalWeeks)
        if (csvResult.drafts.isNotEmpty()) return csvResult

        // 3. 提取 Base64 段并解压
        Regex("""[A-Za-z0-9+/=]{32,}""").findAll(trimmed).forEach { match ->
            val decoded = decodeBase64(match.value) ?: return@forEach
            val inflated = tryInflate(decoded) ?: decoded
            val payload = runCatching { String(inflated, Charsets.UTF_8) }.getOrNull() ?: return@forEach
            if (payload.startsWith("{") || payload.startsWith("[")) {
                val r = JsonParser.parse(payload, totalWeeks)
                if (r.drafts.isNotEmpty()) return r
            }
            if (payload.contains(",") || payload.contains("\t")) {
                val r = CsvParser.parse(payload, totalWeeks)
                if (r.drafts.isNotEmpty()) return r
            }
        }

        return TableMapping.Result(
            emptyList(), 0,
            listOf("无法识别的口令格式，请改用「文件导入」或粘贴标准表格文本"),
        )
    }

    private fun decodeBase64(text: String): ByteArray? = runCatching {
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

    private fun inflate(data: ByteArray, nowrap: Boolean): ByteArray? = runCatching {
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
