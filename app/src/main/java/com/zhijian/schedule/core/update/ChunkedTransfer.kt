package com.zhijian.schedule.core.update

import java.io.InputStream
import java.io.RandomAccessFile
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import okhttp3.Response

/** 实测 8 并发把单连接吞吐提到 5.89 倍；单个常量，日后好调 */
private const val MAX_CONNECTIONS = 8

private const val MAX_CHUNK_ATTEMPTS = 3
private const val RETRY_BASE_MILLIS = 500L

/** 每写这么多字节刷新一次进度 */
private const val PROGRESS_STEP_BYTES = 64L * 1024L

/**
 * 分片并行传输：一次源尝试的入口，以及并行路径本身。
 *
 * [ResumableDownload] 决定「从哪里接着写」；这里负责把分片真的写进 `.part`：
 * `min(8, chunkCount)` 个 worker 抢同一个分片队列，各写自己那一段。**不开分片文件、不需要事后
 * 拼接**：所有 worker 共用同一个 `RandomAccessFile`，`seek` 到各自分片起点后顺序写。
 * 每片写完的顺序是「`fd.sync()` → 记账 → 重写 `.meta`」：先落盘再记账，崩溃后才不会把没写完的片当成完成片。
 *
 * 服务端不认 Range（200）时改为单连接顺序路径，见 SequentialTransfer.kt；
 * 全部函数以 [DownloadSession] 为接收者，共享同一次下载的取消/换源标志与进度发布。
 */

/** 用单个源下载：按磁盘现状选「分片续传」或「顺序续传」，分片进度唯一权威是 `.meta.completedChunks` */
internal suspend fun DownloadSession.downloadSource(request: DownloadRequest, source: UpdateSource): Notice {
    val meta = store.readMeta(request.version)
    val partBytes = store.partBytes(request.version)
    val knownTotal = request.sizeBytes?.takeIf { it > 0 } ?: meta?.totalBytes?.takeIf { it > 0 }
    val resume = ResumableDownload.resolveResumePoint(
        version = request.version,
        url = source.url,
        totalBytes = knownTotal,
        partBytes = partBytes,
        rawMeta = meta,
    )

    // 续传要的只有「缺的那部分」，不再要求一整份的空闲空间（设计 §2.6）
    val missing = when (resume) {
        is ResumePoint.Chunked -> resume.plan.totalBytes -
            ResumableDownload.downloadedBytes(resume.plan, resume.completed, resume.partial)
        is ResumePoint.Fresh -> knownTotal ?: 0L
    }
    if (missing > 0L && store.freeSpaceBytes() < missing) {
        return Notice.Done(
            DownloadState.Failed(
                request.version,
                "存储空间不足，还需 ${ResumableDownload.formatBytes(missing)}",
                canResume = false,
            )
        )
    }

    return when (resume) {
        is ResumePoint.Fresh -> {
            // 来源变了或压根没有断点：清掉已下的字节与元数据，两个源的同名文件不能混着拼
            store.deleteAll(request.version)
            val plan = resume.plan ?: return downloadSequential(request, source, fromScratch = true)
            // 只有一个分片：多开连接只会被服务端限速，不如走单连接
            if (plan.chunkCount <= 1) return downloadSequential(request, source, fromScratch = true)
            // 先把「这次是按哪个源、哪个分片大小在拼」锚进 `.meta`：中途被杀时下次才对得上。
            // 先建空文件再写元数据、且 etag/lastModified 一律留空 —— 两个细节都是为了「已经
            // 没有断点了、这次真的要从 0 重来」：旧元数据里那对校验器属于上一次会话，照抄会让
            // If-Range 拿过期校验器去问服务端。真正的校验器由第一片写完时从响应头捕获（见 writeChunk）。
            store.deleteChunks(request.version)
            store.writeMeta(
                PartMeta(
                    version = request.version,
                    url = source.url,
                    totalBytes = plan.totalBytes,
                    digest = request.digest,
                    chunkSize = plan.chunkSize,
                    // 标明这是稀疏写：`.part` 的长短从此不再代表进度
                    sparseChunks = true,
                )
            )
            parallelDownload(request, source, plan, emptyList())
        }
        is ResumePoint.Chunked -> {
            val plan = resume.plan
            // 续传这一支保留旧元数据里的校验器：`.meta.url` 与当前源一致，说明这确实还是同一份文件
            store.writeMeta(
                PartMeta(
                    version = request.version,
                    url = source.url,
                    etag = meta?.etag,
                    lastModified = meta?.lastModified,
                    totalBytes = plan.totalBytes,
                    digest = request.digest,
                    chunkSize = plan.chunkSize,
                    completedChunks = resume.completed,
                    partialChunks = resume.partial,
                    sparseChunks = true,
                )
            )
            parallelDownload(request, source, plan, resume.completed, resume.partial)
        }
    }
}

/** 分片并行调度的结果 */
private sealed interface ChunkOutcome {
    /** 队列被抢空 */
    data object Completed : ChunkOutcome

    /** 某个 worker 发现服务端忽略 Range */
    data object Unsupported : ChunkOutcome

    /** 用户取消 / 超时 */
    data object Cancelled : ChunkOutcome

    /** 源监视器中止（停滞 / 过慢）：交给源链换下一个源 */
    data object Aborted : ChunkOutcome

    data class Error(val reason: String) : ChunkOutcome
}

/**
 * `min(8, chunkCount)` 个 worker 抢同一个分片队列，各写自己那一段。**不开分片文件、不需要事后拼接**：
 * 所有 worker 共用同一个 `RandomAccessFile`，`seek` 到各自分片起点后顺序写。
 * 每片写完的顺序是「`fd.sync()` → 记账 → 重写 `.meta`」：先落盘再记账，崩溃后才不会把没写完的片当成完成片。
 */
private suspend fun DownloadSession.parallelDownload(
    request: DownloadRequest,
    source: UpdateSource,
    plan: ChunkPlan,
    completed: List<Int>,
    partial: Map<Int, Long> = emptyMap(),
): Notice {
    val part = store.partFile(request.version)
    val done = completed.toMutableList()
    val written = LongArray(plan.chunkCount)
    // 续传时已完成的片按满长度记账，进度才不会从更小的数字开始
    completed.forEach { index -> if (index in 0 until plan.chunkCount) written[index] = plan.bytesOf(index) }
    // 暂停时切到一半的片从记录的偏移接着写：既保住已经落盘的字节，进度也不会来回跳
    partial.forEach { (index, bytes) ->
        if (index in 0 until plan.chunkCount && index !in done) {
            written[index] = bytes.coerceIn(0L, plan.bytesOf(index))
        }
    }
    val lock = Any()
    val meter = SpeedMeter()

    /** 把「已完成的片 + 在飞片写到哪」落盘：暂停/中止/被杀之后，下一次续传才接得上 */
    fun persistProgress() {
        synchronized(lock) {
            val meta = store.readMeta(request.version)
            store.writeMeta(
                PartMeta(
                    version = request.version,
                    url = source.url,
                    etag = meta?.etag,
                    lastModified = meta?.lastModified,
                    totalBytes = plan.totalBytes,
                    digest = request.digest,
                    chunkSize = plan.chunkSize,
                    completedChunks = done.sorted(),
                    partialChunks = partialOffsets(plan, done, written),
                    sparseChunks = true,
                )
            )
        }
    }

    val initial = ChunkProgress(plan, done.sorted(), written).downloadedBytes
    meter.record(System.currentTimeMillis(), initial)
    publish(request, source, initial, plan.totalBytes, meter)

    val outcome = try {
        coroutineScope {
            RandomAccessFile(part, "rw").use { file ->
                // 只有什么都不保留时才截断：有 partial 偏移说明已落盘的字节还要接着写，
                // 截成 0 会把它们抹成空洞
                if (completed.isEmpty() && partial.isEmpty()) file.setLength(0L)
                val queue = kotlinx.coroutines.channels.Channel<Int>(
                    kotlinx.coroutines.channels.Channel.UNLIMITED
                )
                (0 until plan.chunkCount).filter { it !in completed }.forEach { queue.trySend(it) }
                queue.close()

                val workers = List(minOf(MAX_CONNECTIONS, plan.chunkCount)) {
                    // 显式钉住 async 的返回类型，且循环用 break 收尾：协程体的末表达式必须是
                    // ChunkOutcome，否则下面按 ChunkOutcome 分派的 when 会因类型是 Any 而不再穷尽
                    async<ChunkOutcome>(Dispatchers.IO) {
                        try {
                            while (true) {
                                coroutineContext.ensureActive()
                                if (cancelRequested.get()) return@async ChunkOutcome.Cancelled
                                if (sourceAbort.get()) return@async ChunkOutcome.Aborted
                                val index = queue.tryReceive().getOrNull() ?: break
                                val result = downloadChunk(request, source, plan, index, file, written, plan.chunkCount, done, lock, meter)
                                // 某片发现服务端不认 Range 就没必要把剩下的片也拉下来，立刻收手
                                if (result !is ChunkOutcome.Completed) return@async result
                            }
                            // 队列被抢空：这个 worker 正常收工
                            ChunkOutcome.Completed
                        } catch (e: Exception) {
                            // 防御纵深：writeChunk 已按裁决兜住读取期 IOException，这里是最后一道闸 ——
                            // 任何漏网异常（close 期 IO、分派竞态）都不允许穿出 async 炸掉 coroutineScope
                            // 把前台服务拖闪退（真机回归 2026-10-16）。CancellationException 必须原样上抛，
                            // 否则结构化并发的取消语义被破坏。
                            if (e is CancellationException) throw e
                            if (cancelRequested.get()) ChunkOutcome.Cancelled
                            else ChunkOutcome.Error("网络中断（${e.message ?: e::class.simpleName ?: "IO 错误"}），可继续下载")
                        }
                    }
                }
                // awaitAll 让所有 worker 收尾；只要有一个非 Completed 就取它
                val results = workers.awaitAll()
                results.firstOrNull { it !is ChunkOutcome.Completed } ?: ChunkOutcome.Completed
            }
        }
    } catch (e: CancellationException) {
        // 服务被回收时协程是硬取消的：先把在飞进度落盘再上抛，否则下次续传只能重下整片
        runCatching { persistProgress() }
        throw e
    }

    if (outcome !is ChunkOutcome.Completed) {
        // 「取消/停滞/失败」都要带着真实进度退出，续传与暂停卡片上的数字才对得上
        runCatching { persistProgress() }
    }
    return when (outcome) {
        ChunkOutcome.Completed -> verifyAndClearChunks(store, request, part, plan.totalBytes)
        ChunkOutcome.Unsupported -> Notice.Fallback
        ChunkOutcome.Cancelled -> Notice.Paused
        // 源监视器判了停滞/过慢：本次尝试作废，交给源链。分片仍在，单源收场时还能续传
        ChunkOutcome.Aborted -> Notice.Failed("网络停滞或过慢，可继续下载", canResume = true)
        // 片内重试 3 次仍失败（设计 §2.2）：这是「这个源没跑通」，交给源链决定是换下一个源
        // 还是收场。已完成的片留在磁盘上，单源时 canResume = true 才有东西可续。
        is ChunkOutcome.Error -> Notice.Failed(outcome.reason, canResume = true)
    }
}

/** 未完成片的已写偏移；写满但没记账的片不参与（下次整片重下，避免越界 Range） */
private fun partialOffsets(plan: ChunkPlan, done: List<Int>, written: LongArray): Map<Int, Long> {
    val finished = done.toSet()
    val offsets = mutableMapOf<Int, Long>()
    for (index in 0 until plan.chunkCount) {
        if (index in finished) continue
        val bytes = written.getOrElse(index) { 0L }
        if (bytes > 0L && bytes < plan.bytesOf(index)) offsets[index] = bytes
    }
    return offsets
}

/** 下载一个分片：200 触发降级、416 触发完成/损坏、其余失败重试 3 次（线性退避） */
private suspend fun DownloadSession.downloadChunk(
    request: DownloadRequest,
    source: UpdateSource,
    plan: ChunkPlan,
    index: Int,
    file: RandomAccessFile,
    written: LongArray,
    chunkCount: Int,
    done: MutableList<Int>,
    lock: Any,
    meter: SpeedMeter,
): ChunkOutcome {
    val chunk = plan.chunks[index]
    var attempt = 0
    while (attempt < MAX_CHUNK_ATTEMPTS) {
        coroutineContext.ensureActive()
        if (cancelRequested.get()) return ChunkOutcome.Cancelled
        if (sourceAbort.get()) return ChunkOutcome.Aborted
        attempt++

        // 接着这片已经写到的偏移继续：重试与续传都不从头覆盖已落盘的部分
        val from = synchronized(lock) { written[index] }
            .coerceIn(0L, (plan.bytesOf(index) - 1L).coerceAtLeast(0L))
        val etag = store.readMeta(request.version)?.etag
        val call = client.newCall(chunkRequest(source, chunk, etag, from))
        activeCalls.add(call)
        val response = try {
            call.execute()
        } catch (e: java.io.IOException) {
            activeCalls.remove(call)
            if (cancelRequested.get()) return ChunkOutcome.Cancelled
            if (sourceAbort.get()) return ChunkOutcome.Aborted
            if (attempt >= MAX_CHUNK_ATTEMPTS) {
                return ChunkOutcome.Error("网络中断（${e.message ?: "IO 错误"}），可继续下载")
            }
            delay(RETRY_BASE_MILLIS * attempt)
            continue
        }

        val settled = try {
            response.use {
                applyChunkResponse(request, source, plan, index, file, it, written, chunkCount, done, lock, meter, from)
            }
        } finally {
            activeCalls.remove(call)
        }

        when (settled) {
            Stop.None -> return ChunkOutcome.Completed
            Stop.Unsupported -> return ChunkOutcome.Unsupported
            Stop.Cancelled -> return ChunkOutcome.Cancelled
            Stop.Abort -> return ChunkOutcome.Aborted
            // 416 说明服务端认为整份都在了，直接交校验去判断
            is Stop.AlreadyComplete -> return ChunkOutcome.Completed
            is Stop.Corrupt -> return ChunkOutcome.Error(settled.reason)
            is Stop.Fail -> return ChunkOutcome.Error(settled.reason)
            is Stop.Retry -> {
                if (sourceAbort.get()) return ChunkOutcome.Aborted
                if (attempt >= MAX_CHUNK_ATTEMPTS) {
                    return ChunkOutcome.Error("下载失败（HTTP ${settled.code}），可继续下载")
                }
            }
        }
        delay(RETRY_BASE_MILLIS * attempt)
    }
    return ChunkOutcome.Error("分片下载失败，可继续下载")
}

/** 分片请求；`Accept-Encoding: identity` 不能少，否则 Content-Length 是压缩后大小，长度校验会全错 */
private fun chunkRequest(source: UpdateSource, chunk: Chunk, etag: String?, from: Long): Request {
    val builder = Request.Builder()
        .url(source.url)
        .header("Accept-Encoding", "identity")
        .header("User-Agent", USER_AGENT)
        .header("Range", "bytes=${chunk.start + from}-${chunk.endInclusive}")
    // 交给服务端判断「资源没变才给 206」，能挡住资产被替换导致的新旧分片拼接损坏
    etag?.let { builder.header("If-Range", it) }
    return builder.build()
}

/** 单次分片响应的处理结论 */
private sealed interface Stop {
    /** 这一片写完了 */
    data object None : Stop

    /** 服务端忽略 Range（200） */
    data object Unsupported : Stop

    /** 用户取消 / 超时 */
    data object Cancelled : Stop

    /** 源监视器中止本源的连接（停滞 / 过慢） */
    data object Abort : Stop

    /** 416：服务端认为整份文件都在了，交校验判断 */
    data class AlreadyComplete(val total: Long) : Stop

    /** 分片集合坏了：起点对不上或没写完的 416 */
    data class Corrupt(val reason: String) : Stop

    /** 不可重试的失败 */
    data class Fail(val reason: String) : Stop

    /** 可重试的状态码（5xx / 429 / 响应无 body） */
    data class Retry(val code: Int) : Stop
}

private fun DownloadSession.applyChunkResponse(
    request: DownloadRequest,
    source: UpdateSource,
    plan: ChunkPlan,
    index: Int,
    file: RandomAccessFile,
    response: Response,
    written: LongArray,
    chunkCount: Int,
    done: MutableList<Int>,
    lock: Any,
    meter: SpeedMeter,
    from: Long,
): Stop {
    val contentRange = ResumableDownload.parseContentRange(response.header("Content-Range"))
    val total = contentRange?.total ?: plan.totalBytes
    val support = ResumableDownload.decideRangeSupport(response.code, chunkCount, done.size)
    return when (support) {
        RangeSupport.Unsupported -> Stop.Unsupported
        RangeSupport.AlreadyComplete -> Stop.AlreadyComplete(total)
        RangeSupport.Corrupt -> Stop.Corrupt("分片集合损坏，请重新下载")
        // 500 与 IO 不降级：那是网络问题，重试即可；4xx 是没救了，直接换下一个源
        is RangeSupport.Fail -> if (support.canResume) {
            Stop.Retry(response.code)
        } else {
            Stop.Fail(support.reason)
        }
        RangeSupport.Supported -> {
            // 206 的起点必须就是这一片这次要写的起点，否则拼出来的 APK 是坏的
            if (contentRange != null && contentRange.start != plan.chunks[index].start + from) {
                return Stop.Corrupt("分片起点不匹配，请重新下载")
            }
            val body = response.body ?: return Stop.Retry(response.code)
            body.byteStream().use { input ->
                writeChunk(request, source, plan, index, file, input, response, written, done, lock, meter, total, from)
            }
        }
    }
}

/** 把这一片写进 `.part`；每 64 KiB 刷新一次进度。片没读完（连接被掐）当作这一片失败重试 */
private fun DownloadSession.writeChunk(
    request: DownloadRequest,
    source: UpdateSource,
    plan: ChunkPlan,
    index: Int,
    file: RandomAccessFile,
    input: InputStream,
    response: Response,
    written: LongArray,
    done: MutableList<Int>,
    lock: Any,
    meter: SpeedMeter,
    total: Long,
    from: Long,
): Stop {
    val chunk = plan.chunks[index]
    val buffer = ByteArray(BUFFER_BYTES)
    var offset = chunk.start + from
    var sinceUpdate = 0L
    // 读取循环必须兜 IOException：用户取消时 OkHttp 会把 HTTP/2 流重置
    // （StreamResetException，IOException 子类）从阻塞中的 read() 抛出；不接住的话
    // 异常穿出 async worker → coroutineScope 连锁取消 → 前台服务崩 → 应用闪退
    // （真机回归 2026-10-16，见 ResumableDownload.resolveReadFailure 的裁决说明）。
    try {
        while (offset <= chunk.endInclusive) {
            if (cancelRequested.get()) return Stop.Cancelled
            if (sourceAbort.get()) return Stop.Abort
            val want = minOf(BUFFER_BYTES.toLong(), chunk.endInclusive - offset + 1L).toInt()
            val read = input.read(buffer, 0, want)
            if (read < 0) break
            if (read == 0) continue
            // seek + write 必须原子：8 个 worker 共用同一个 RandomAccessFile，文件指针是共享状态。
            // 不锁的话「A 先 seek、被抢占、B 又 seek、A 再 write」会把 A 的数据写到 B 的偏移上，
            // 拼出来的 APK 静默损坏（长度对得上，只有整包 SHA-256 能发现）。锁与进度记账共用同一把。
            synchronized(lock) {
                file.seek(offset)
                file.write(buffer, 0, read)
            }
            offset += read
            sinceUpdate += read
            if (sinceUpdate >= PROGRESS_STEP_BYTES) {
                sinceUpdate = 0L
                synchronized(lock) {
                    written[index] = offset - chunk.start
                    val now = ChunkProgress(plan, done.sorted(), written).downloadedBytes
                    meter.record(System.currentTimeMillis(), now)
                    publish(request, source, now, total, meter)
                }
            }
        }
    } catch (e: java.io.IOException) {
        // 源监视器中止时连接的取消同样会以 IO 异常形式抛出，优先按「换源」裁决
        if (sourceAbort.get()) return Stop.Abort
        when (val verdict = ResumableDownload.resolveReadFailure(e, cancelRequested.get())) {
            is ReadVerdict.StopCancelled -> return Stop.Cancelled
            is ReadVerdict.Retry -> {
                // 片没读完：交给上层按片重试，下一次从已经写到的偏移接着写
                return Stop.Retry(0)
            }
            is ReadVerdict.Rethrow -> throw e
        }
    }
    // 片没读完（连接被掐）：交给上层重试，下一次从已经写到的偏移接着写
    if (offset <= chunk.endInclusive) return Stop.Retry(0)

    file.fd.sync()
    synchronized(lock) {
        written[index] = plan.bytesOf(index)
        if (index !in done) done.add(index)
        // 校验器：先从响应头捕获（第一片写完时把真正的 ETag/Last-Modified 记下来，
        // 后续片的 If-Range 才拿得到有效值），再退回元数据里已有的值
        val etag = response.header("ETag") ?: store.readMeta(request.version)?.etag
        val lastModified = response.header("Last-Modified") ?: store.readMeta(request.version)?.lastModified
        store.writeMeta(
            PartMeta(
                version = request.version,
                url = source.url,
                etag = etag,
                lastModified = lastModified,
                totalBytes = total,
                digest = request.digest,
                chunkSize = plan.chunkSize,
                completedChunks = done.sorted(),
                // 在飞片的偏移也要留着：只记完成片的话，下一次暂停/续传会把这些字节当没下过
                partialChunks = partialOffsets(plan, done, written),
                sparseChunks = true,
            )
        )
        val now = ChunkProgress(plan, done.sorted(), written).downloadedBytes
        meter.record(System.currentTimeMillis(), now)
        publish(request, source, now, total, meter)
    }
    return Stop.None
}
