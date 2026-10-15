package com.zhijian.schedule.update

import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * 断点续传与下载结果的**纯逻辑**：不依赖任何 Android API，可直接用 JUnit 跑（项目没有 Robolectric）。
 * 碰 Context / 网络 / 状态机的部分在 UpdateStore、ApkDownloader 与 ApkDownloadService。
 */

/** 断点元数据，与 `<version>.apk.part` 同目录共存（写成 `<version>.apk.meta`，JSON） */
@Serializable
data class PartMeta(
    val version: String,
    val url: String,
    val etag: String? = null,
    val lastModified: String? = null,
    val totalBytes: Long = 0L,
    val digest: String? = null,
)

/** `Content-Range: bytes 1234-46920381/46920382` 的解析结果；total 为 `*` 时是 null */
data class ContentRange(val start: Long, val endInclusive: Long, val total: Long?)

/** 服务端响应 → 该怎么写 `.part` */
sealed interface RangeDecision {
    /** 206：服务端支持续传且资源未变，从 offset 追加 */
    data class Append(val offset: Long) : RangeDecision

    /** 200：服务端忽略了 Range，或 If-Range 判定资源已变，清空 `.part` 从头写 */
    data object Restart : RangeDecision

    /** 416 且已有分片正好等于目标长度：已经下完整了，直接进校验 */
    data object AlreadyComplete : RangeDecision

    /** 416 但长度对不上：分片损坏，清空重下 */
    data object Corrupt : RangeDecision

    /** 直接失败；canResume 决定卡片给「重试（续传）」还是「重新下载」 */
    data class Fail(val reason: String, val canResume: Boolean) : RangeDecision
}

object ResumableDownload {

    /** `.apk[.part|.meta]` 后缀；长的必须排在前面（判定要覆盖 `.apk.part` 而非 `.apk`） */
    private val FILE_SUFFIXES = listOf(".apk.part", ".apk.meta", ".apk")

    private const val HEX = "0123456789abcdef"

    // ---------- 响应判定 ----------

    /** 解析 `bytes <start>-<end>/<total>`；格式不认识一律返回 null，调用方按「无 Content-Range」处理 */
    fun parseContentRange(header: String?): ContentRange? {
        val text = header?.trim().orEmpty()
        if (text.length <= 6 || !text.regionMatches(0, "bytes ", 0, 6, ignoreCase = true)) return null
        val spec = text.substring(6).trim()
        val slash = spec.indexOf('/')
        if (slash <= 0) return null
        val range = spec.substring(0, slash).trim()
        val totalText = spec.substring(slash + 1).trim()
        val dash = range.indexOf('-')
        if (dash <= 0) return null
        val start = range.substring(0, dash).trim().toLongOrNull() ?: return null
        val end = range.substring(dash + 1).trim().toLongOrNull() ?: return null
        val total = if (totalText == "*") null else totalText.toLongOrNull() ?: return null
        return ContentRange(start, end, total)
    }

    /**
     * 进度分母。优先级：Content-Range 的 total（206 的权威值）> Content-Length（只有完整响应才等于整份大小）
     * > 已知大小（GitHub API 的 `size` 或元数据）。
     * 206 的 Content-Length 只是**剩余**字节，绝不能当分母。
     */
    fun resolveTotalBytes(contentLength: Long?, contentRange: ContentRange?, knownTotal: Long?): Long? {
        contentRange?.total?.takeIf { it > 0 }?.let { return it }
        // 206 但 total 是 `*`（少见）：Content-Length 不可用，退回已知大小
        if (contentRange != null) return knownTotal?.takeIf { it > 0 }
        contentLength?.takeIf { it > 0 }?.let { return it }
        return knownTotal?.takeIf { it > 0 }
    }

    /** 按响应码与已有分片大小决定写入方式 */
    fun decideRange(code: Int, partBytes: Long, totalBytes: Long?): RangeDecision = when {
        code == 206 -> RangeDecision.Append(partBytes)
        code == 200 -> RangeDecision.Restart
        code == 416 -> if (totalBytes != null && partBytes == totalBytes) {
            RangeDecision.AlreadyComplete
        } else {
            RangeDecision.Corrupt
        }
        code in 400..499 -> RangeDecision.Fail("下载失败（HTTP $code），请重新下载", canResume = false)
        code in 500..599 -> RangeDecision.Fail("服务端错误（HTTP $code），可重试", canResume = true)
        else -> RangeDecision.Fail("下载失败（HTTP $code），可重试", canResume = true)
    }

    // ---------- 校验 ----------

    /** `sha256:abc…` → 小写十六进制；非 SHA-256、缺失或不是 64 位十六进制时返回 null（当作没有摘要） */
    fun normalizeDigest(digest: String?): String? {
        val text = digest?.trim().orEmpty()
        if (!text.startsWith("sha256:", ignoreCase = true)) return null
        val hex = text.substring(7).trim().lowercase(Locale.US)
        if (hex.length != 64) return null
        if (hex.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        return hex
    }

    /** 流式 SHA-256（64 KiB 缓冲），返回 64 位小写十六进制。46 MB 约 0.2 秒 */
    fun sha256Hex(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
        val bytes = digest.digest()
        val out = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val value = byte.toInt() and 0xFF
            out.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return out.toString()
    }

    /**
     * 校验下载结果：长度必须等于 [expectedBytes]（为 null 时跳过）；
     * [expectedDigest] 是合法 `sha256:` 时流式计算 SHA-256 并比对。
     * 哈希是纵深防御：长度对但内容坏（CDN 分片损坏、中间篡改）只有哈希能发现。
     */
    fun verifyFile(file: File, expectedBytes: Long?, expectedDigest: String?): VerifyResult {
        if (expectedBytes != null && file.length() != expectedBytes) return VerifyResult.LengthMismatch
        val expected = normalizeDigest(expectedDigest) ?: return VerifyResult.Ok
        val actual = file.inputStream().use { sha256Hex(it) }
        return if (actual == expected) VerifyResult.Ok else VerifyResult.DigestMismatch
    }

    // ---------- 展示 ----------

    /** 1024 进位、一位小数；不足 1 KB 显示整数 B（46920382 → "44.7 MB"） */
    fun formatBytes(bytes: Long): String {
        val value = bytes.coerceAtLeast(0L)
        return when {
            value < 1024L -> "$value B"
            value < 1024L * 1024L -> oneDecimal(value / 1024.0, "KB")
            value < 1024L * 1024L * 1024L -> oneDecimal(value / (1024.0 * 1024.0), "MB")
            else -> oneDecimal(value / (1024.0 * 1024.0 * 1024.0), "GB")
        }
    }

    private fun oneDecimal(value: Double, unit: String): String =
        String.format(Locale.US, "%.1f %s", value, unit)

    /** "约剩 30 秒"；无法估算（null 或负数）时返回 null，调用方直接不显示 */
    fun etaText(seconds: Long?): String? {
        val value = seconds?.takeIf { it >= 0 } ?: return null
        return when {
            value < 60 -> "约剩 $value 秒"
            value < 3600 -> "约剩 ${(value + 59) / 60} 分"
            else -> "约剩 ${(value + 3599) / 3600} 小时"
        }
    }
}

/** 下载结果校验结论 */
sealed interface VerifyResult {
    data object Ok : VerifyResult

    /** 字节数与预期不符：分片大概没写完，保留后可续传 */
    data object LengthMismatch : VerifyResult

    /** 字节数对但摘要不符：内容被换过或损坏，必须重下 */
    data object DigestMismatch : VerifyResult
}
