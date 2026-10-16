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
    /** 分片大小；0 = v1 写的旧元数据，按「单连接顺序续传」处理 */
    val chunkSize: Long = 0L,
    /** 已完成的分片序号；只有这里的序号才算下载完成 */
    val completedChunks: List<Int> = emptyList(),
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

    /** 分片大小 4 MiB：46 MB 的安装包切成 12 片（实测 8 并发把吞吐提到 5.89 倍） */
    const val DEFAULT_CHUNK_BYTES = 4L * 1024L * 1024L

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

    // ---------- 降级判据 ----------

    /**
     * 分片请求的响应该当成什么。**只有 200 触发降级** —— 那是服务端在说「我不认 Range」，
     * 继续并发就是 8 份重复的整包。[doneChunks] 与 [chunkCount] 用于区分 416 的两种含义。
     * 500 与 IO 异常不降级：那是网络问题，按 v1 的失败+重试处理。
     */
    fun decideRangeSupport(code: Int, chunkCount: Int, doneChunks: Int): RangeSupport = when {
        code == 206 -> RangeSupport.Supported
        code == 200 -> RangeSupport.Unsupported
        code == 416 -> if (doneChunks >= chunkCount) RangeSupport.AlreadyComplete else RangeSupport.Corrupt
        code in 400..499 -> RangeSupport.Fail("下载失败（HTTP $code），请重新下载", canResume = false)
        else -> RangeSupport.Fail("服务端错误（HTTP $code），可重试", canResume = true)
    }

    // ---------- 分片规划 ----------

    /**
     * 把整份文件切成固定大小的分片。`total` 未知 / 非正，或 `chunkSize` 非正时返回 null ——
     * 调用方一律退回单连接顺序路径。共享分片队列的调度策略也在这里定：只有分片数量 > 1 才值得并行。
     */
    fun planChunks(totalBytes: Long, chunkSize: Long = DEFAULT_CHUNK_BYTES): ChunkPlan? {
        if (totalBytes <= 0L || chunkSize <= 0L) return null
        val count = ((totalBytes + chunkSize - 1L) / chunkSize).toInt()
        if (count <= 0) return null
        val chunks = (0 until count).map { index ->
            val start = index.toLong() * chunkSize
            Chunk(index, start, minOf(start + chunkSize, totalBytes) - 1L)
        }
        return ChunkPlan(totalBytes, chunkSize, chunks)
    }

    // ---------- 续传点 ----------

    /** 已经连着写了 [writtenBytes] 字节时，其中有几个完整的 [chunkSize] 分片（尾部半截不算） */
    fun chunkCountOf(writtenBytes: Long, chunkSize: Long): Int? {
        if (chunkSize <= 0L) return null
        if (writtenBytes <= 0L) return 0
        return (writtenBytes / chunkSize).toInt()
    }

    /**
     * 从磁盘现状推出这次该走哪条路。
     *
     * v1 元数据由 `readMeta` / `parseMeta` 统一归一化（缺的 `chunkSize` 补默认值），所以这里的
     * `meta.chunkSize` 永远已是正值。v1 遗留的 `.part` 是连续前缀，会被 `chunkCountOf(partBytes, chunkSize)`
     * 折算成前 N 个整片（最多重下不到一片），剩余分片照常并行 —— 因此不再保留单连接顺序续传分支。
     *
     * [Fresh] 在两种情况下出现，都带上分片计划方便调用方直接用：
     * - 磁盘上什么都没有 → `plan` 由 `knownTotal` 算出来（`knownTotal` 未知时是 null）；
     * - 有 `.part` 但元数据认不出这个源（`meta.url != url`）→ 必须清空重下。两个源的同名文件
     *   可能内容不同，混着拼必然坏，事后虽然有 digest 兜住，但代价是白下 46 MB。
     */
    fun resolveResumePoint(
        version: String,
        url: String,
        totalBytes: Long?,
        partBytes: Long,
        rawMeta: PartMeta?,
    ): ResumePoint {
        val meta = rawMeta?.takeIf { it.version == version }
        val plan = planChunks(
            totalBytes = totalBytes ?: meta?.totalBytes ?: 0L,
            chunkSize = meta?.chunkSize?.takeIf { it > 0L } ?: DEFAULT_CHUNK_BYTES,
        )
        if (partBytes <= 0L) return ResumePoint.Fresh(plan)

        if (meta == null || meta.url != url || plan == null) return ResumePoint.Fresh(plan)

        // v1 的 `.part` 是连续前缀，可以直接认成前 N 个整片，半截那片重下即可
        val completed = meta.completedChunks
            .ifEmpty { (0 until (chunkCountOf(partBytes, plan.chunkSize) ?: 0)).toList() }
            .filter { it in 0 until plan.chunkCount }
            .distinct()
            .sorted()

        // 分片进度唯一权威在 meta；它一片都没记账、磁盘上却已经有东西时，按 `.part` 重新规划
        return if (completed.isEmpty()) {
            val safe = chunkCountOf(partBytes, plan.chunkSize) ?: 0
            ResumePoint.Chunked(plan, (0 until minOf(safe, plan.chunkCount)).toList())
        } else {
            ResumePoint.Chunked(plan, completed)
        }
    }

    // ---------- 校验 ----------

    // ---------- 元数据兼容 ----------

    /**
     * `.meta` 的兼容解析（v1 ↔ v2）：
     * 1. `version` 对不上就当作没有断点；
     * 2. 缺 `chunkSize`/`completedChunks` 的 v1 元数据一律补成默认分片大小 + 空列表 —— **不臆造分片进度**；
     * 3. 新写的 v2 元数据补上分片大小。
     */
    fun parseMeta(version: String, raw: PartMeta?): PartMeta? {
        val meta = raw ?: return null
        if (meta.version != version) return null
        val chunkSize = if (meta.chunkSize > 0L) meta.chunkSize else DEFAULT_CHUNK_BYTES
        return meta.copy(
            chunkSize = chunkSize,
            completedChunks = meta.completedChunks.distinct().sorted(),
        )
    }

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

    // ---------- 文件清理 ----------

    /** `<version>.apk[.part|.meta]` 里的 `<version>`；后缀不认识或版本段不是纯数字时返回 null */
    fun versionOf(fileName: String): String? {
        val suffix = FILE_SUFFIXES.firstOrNull { fileName.endsWith(it) } ?: return null
        val version = fileName.removeSuffix(suffix)
        if (version.isEmpty()) return null
        if (version.split(".").any { it.isEmpty() || it.toIntOrNull() == null }) return null
        return version
    }

    /** 纯数字逐段比较（缺失段视为 0）：a<b 返回 -1，相等返回 0，a>b 返回 1 */
    fun compareNumericVersion(a: String, b: String): Int {
        val left = a.split(".").map { it.toIntOrNull() ?: 0 }
        val right = b.split(".").map { it.toIntOrNull() ?: 0 }
        for (index in 0 until maxOf(left.size, right.size)) {
            val x = left.getOrElse(index) { 0 }
            val y = right.getOrElse(index) { 0 }
            if (x != y) return if (x < y) -1 else 1
        }
        return 0
    }

    /**
     * 启动清理判据：文件名是本应用写下的更新文件，且版本 <= 当前已安装版本（已经装过，留着没用）。
     * 认不出来的文件名一律返回 false —— 宁可留着也不误删用户的东西。
     */
    fun shouldClean(fileName: String, currentVersion: String): Boolean {
        val version = versionOf(fileName) ?: return false
        return compareNumericVersion(version, currentVersion) <= 0
    }
}

/** 一个分片：`[start, endInclusive]` 闭区间（HTTP `Range: bytes=start-end` 的语义） */
data class Chunk(val index: Int, val start: Long, val endInclusive: Long)

/**
 * 分片计划：[totalBytes] 未知时根本不会构造出来，[chunks] 按序号升序且最后一片可能更短。
 * 计划只描述「文件被切成什么样」，不描述进度。
 */
data class ChunkPlan(
    val totalBytes: Long,
    val chunkSize: Long,
    val chunks: List<Chunk>,
) {
    val chunkCount: Int get() = chunks.size

    /** 第 [index] 片的字节数（末片更短） */
    fun bytesOf(index: Int): Long {
        val chunk = chunks.getOrNull(index) ?: return 0L
        return chunk.endInclusive - chunk.start + 1L
    }
}

/** 本次下载该从哪里接着写 */
sealed interface ResumePoint {
    /**
     * 不能续传：从第 0 字节重来。
     * [plan] 是按本次已知总长算好的分片计划（总长未知时为 null），调用方不必重复计算。
     */
    data class Fresh(val plan: ChunkPlan?) : ResumePoint

    /** v2 元数据或可升级的裸 `.part`：按 [plan] 的 [completed] 片续传，只补剩下的片 */
    data class Chunked(val plan: ChunkPlan, val completed: List<Int>) : ResumePoint
}

/** 分片响应的处理结论 */
sealed interface RangeSupport {
    /** 206：服务端支持 Range，继续并行 */
    data object Supported : RangeSupport

    /** 200：服务端忽略了 Range，放弃并行、清掉分片改用单连接顺序路径 */
    data object Unsupported : RangeSupport

    /** 416 且所有分片都已写完：直接进校验 */
    data object AlreadyComplete : RangeSupport

    /** 416 但分片没写完：分片集合损坏，清空重下 */
    data object Corrupt : RangeSupport

    /** 由 [RangeSupport.Fail.canResume] 决定卡片给「重试」还是「重新下载」 */
    data class Fail(val reason: String, val canResume: Boolean) : RangeSupport
}

/**
 * 一次进度快照：把「分片计划 + 已完成序号 + 各片已写字节」聚合成 UI 需要的数字。
 *
 * 进度 = Σ(已完成片的字节数) + Σ(在飞片的已写字节)，其中在飞片的已写字节按该片长度封顶 ——
 * 换源或崩溃后重下某片时，[resetChunk] 会把那片清零，于是进度诚实地**往回跳一点**（设计 §2.3）。
 * 这里不持有文件、不碰状态，只做算术；Task 6 每次要发布进度都是重新构造一个快照。
 */
class ChunkProgress(
    private val plan: ChunkPlan,
    completed: List<Int>,
    written: LongArray,
) {
    private val done: MutableSet<Int> = completed.filter { it in 0 until plan.chunkCount }.toMutableSet()
    private val bytes: LongArray = written.copyOf()

    /** 已完成片 + 在飞片已写字节（按片长封顶），即当前进度 */
    val downloadedBytes: Long
        get() = done.sumOf { plan.bytesOf(it) } +
            (0 until plan.chunkCount)
                .filter { it !in done }
                .sumOf { minOf(bytes.getOrElse(it) { 0L }, plan.bytesOf(it)) }

    /** 还没写完的分片序号（共享待办队列的初始内容） */
    fun neededChunks(): List<Int> = (0 until plan.chunkCount).filter { it !in done }

    /** 还差多少字节（磁盘预检用） */
    fun remainingBytes(): Long = plan.totalBytes - downloadedBytes

    /** 把第 [index] 片退回未完成、已写字节清零（重下该片时调用） */
    fun resetChunk(index: Int) {
        done.remove(index)
        if (index in bytes.indices) bytes[index] = 0L
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

/**
 * 滑动窗口速率估算：只保留窗口内的采样点。
 * 样本不足（不足 2 个点，或两点时间相同）时返回 null —— UI 显示不确定进度，不显示假速度。
 */
class SpeedMeter(private val windowMillis: Long = 5_000L) {

    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun reset() = samples.clear()

    fun record(timestampMillis: Long, totalBytes: Long) {
        samples.addLast(timestampMillis to totalBytes)
        val floor = timestampMillis - windowMillis
        while (samples.size > 2 && samples.first().first < floor) samples.removeFirst()
    }

    fun bytesPerSecond(): Long? {
        if (samples.size < 2) return null
        val (firstAt, firstBytes) = samples.first()
        val (lastAt, lastBytes) = samples.last()
        val elapsed = lastAt - firstAt
        if (elapsed <= 0L) return null
        return ((lastBytes - firstBytes) * 1000L) / elapsed
    }

    /** 剩余秒数（向上取整）；total 为 null 或速度不可用时返回 null */
    fun etaSeconds(downloadedBytes: Long, totalBytes: Long?): Long? {
        val total = totalBytes ?: return null
        val speed = bytesPerSecond()?.takeIf { it > 0 } ?: return null
        val remaining = (total - downloadedBytes).coerceAtLeast(0L)
        return (remaining + speed - 1) / speed
    }
}

/**
 * 通知栏进度节流：距上次刷新 >= [minIntervalMillis] **且**整数百分比变化时才返回 true。
 * [percent] 为 null（响应没有长度信息）时只按时间节流；[force] 用于结束时无条件刷新最终态。
 */
class ProgressThrottle(private val minIntervalMillis: Long = 500L) {

    private var lastAtMillis = Long.MIN_VALUE
    private var lastPercent: Int? = null

    fun shouldRefresh(timestampMillis: Long, percent: Int?, force: Boolean = false): Boolean {
        if (force) {
            lastAtMillis = timestampMillis
            lastPercent = percent
            return true
        }
        if (percent != null && percent == lastPercent) return false
        if (lastAtMillis != Long.MIN_VALUE && timestampMillis - lastAtMillis < minIntervalMillis) return false
        lastAtMillis = timestampMillis
        lastPercent = percent
        return true
    }
}
