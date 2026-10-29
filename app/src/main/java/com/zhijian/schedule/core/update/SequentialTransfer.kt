package com.zhijian.schedule.core.update

import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/*
 * 单连接顺序路径（v1，保留为 Range 不可用时的降级路径）。
 *
 * 与分片路径的区别只有一个：整份文件由一个连接从头写到尾，进度就是 `.part` 的长度。
 * 两条路径共用同一份校验与安装交接（见 DownloadVerification.kt）。
 */

/**
 * 服务端不支持 Range，或只有一两个分片时：回到 v1 已验证过的单连接顺序下载。
 * [fromScratch] 为 true 时必须清掉元数据，否则顺序路径会读着指向镜像的 `.meta` 判续传，
 * 把带 Range 的请求打在镜像上；为 false（v1 元数据 + 同源前缀）时保留元数据，让顺序路径正常追加。
 */
internal suspend fun DownloadSession.downloadSequential(
    request: DownloadRequest,
    source: UpdateSource,
    fromScratch: Boolean,
): Notice {
    if (fromScratch) store.deleteMeta(request.version)
    val part = store.partFile(request.version)
    val builder =
        Request
            .Builder()
            .url(source.url)
            .header("Accept-Encoding", "identity")
            .header("User-Agent", USER_AGENT)
    // 顺序续传时带上 Range/If-Range，语义与 v1 完全一致
    if (!fromScratch) {
        val meta = store.readMeta(request.version)
        val partBytes = store.partBytes(request.version)
        if (partBytes > 0L && meta != null && meta.url == source.url) {
            builder.header("Range", "bytes=$partBytes-")
            meta.etag?.let { builder.header("If-Range", it) }
        }
    }
    val call = client.newCall(builder.build())
    activeCalls.add(call)
    val response =
        try {
            call.execute()
        } catch (e: IOException) {
            activeCalls.remove(call)
            return when {
                cancelRequested.get() -> Notice.Paused
                sourceAbort.get() -> Notice.Failed("网络停滞或过慢，可继续下载", canResume = true)
                else ->
                    Notice.Done(
                        DownloadState.Failed(request.version, "网络中断（${e.message ?: "IO 错误"}）", canResume = true),
                    )
            }
        }
    return try {
        response.use { handleSequential(request, source, it, part, fromScratch) }
    } finally {
        activeCalls.remove(call)
    }
}

/** v1 的下载体：从偏移处顺序写到底，中途被杀靠 `.meta` 的 totalBytes 兜进度分母 */
private fun DownloadSession.handleSequential(
    request: DownloadRequest,
    source: UpdateSource,
    response: Response,
    part: File,
    fromScratch: Boolean,
): Notice {
    if (response.code !in 200..299) {
        return Notice.Done(
            DownloadState.Failed(
                request.version,
                "下载失败（HTTP ${response.code}），请重试",
                canResume = response.code in 500..599,
            ),
        )
    }
    val contentRange = ResumableDownload.parseContentRange(response.header("Content-Range"))
    val partBytes = if (fromScratch) 0L else store.partBytes(request.version)
    val appendFrom =
        when {
            fromScratch -> 0L
            // 服务端不认 Range、或 If-Range 判定资源已变：这次响应是整份，从头覆盖
            response.code == 200 -> 0L
            contentRange != null && contentRange.start == partBytes -> partBytes
            else -> 0L
        }
    val body =
        response.body
            ?: return Notice.Done(DownloadState.Failed(request.version, "响应无内容，请重试", canResume = true))
    val total = ResumableDownload.resolveTotalBytes(body.contentLength(), contentRange, request.sizeBytes)

    // 先落元数据再写数据：中途被杀也能靠 .meta 记住进度分母
    store.writeMeta(
        PartMeta(
            version = request.version,
            url = source.url,
            etag = response.header("ETag"),
            lastModified = response.header("Last-Modified"),
            totalBytes = total ?: 0L,
            digest = request.digest,
        ),
    )

    val meter = SpeedMeter()
    var written = appendFrom
    meter.record(System.currentTimeMillis(), written)
    publish(request, source, written, total, meter)

    RandomAccessFile(part, "rw").use { file ->
        if (appendFrom == 0L) file.setLength(0L)
        file.seek(appendFrom)
        val buffer = ByteArray(BUFFER_BYTES)
        body.byteStream().use { input ->
            while (true) {
                if (cancelRequested.get()) return Notice.Paused
                if (sourceAbort.get()) return Notice.Failed("网络停滞或过慢，可继续下载", canResume = true)
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) continue
                file.write(buffer, 0, read)
                written += read
                meter.record(System.currentTimeMillis(), written)
                publish(request, source, written, total, meter)
            }
        }
        file.fd.sync()
    }

    if (total != null && written != total) {
        return Notice.Done(
            DownloadState.Failed(
                request.version,
                "下载未完成（已收到 ${ResumableDownload.formatBytes(written)}），可继续下载",
                canResume = true,
            ),
        )
    }
    return verifyAndClearChunks(store, request, part, total ?: part.length())
}
