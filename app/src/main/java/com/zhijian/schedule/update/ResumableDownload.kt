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
}
