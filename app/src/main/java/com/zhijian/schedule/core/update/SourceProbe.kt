package com.zhijian.schedule.core.update

import android.os.SystemClock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Request

/** 下载前源探测超时：超过这个时间就当该源不可达 */
private const val PROBE_TIMEOUT_MS = 5_000L

/**
 * 下载前用 1KB Range 请求探测候选源，按延迟排序。
 * 直连被黑洞时不用等源监视器 25s 才换镜像；探测失败的源保留在链尾，仍可作为退路。
 * 同时校验响应长度，避免把「200 的镜像错误页」当成可用源。
 */
internal suspend fun DownloadSession.probeSources(
    sources: List<UpdateSource>,
    expectedSize: Long?,
): List<UpdateSource> {
    // 已经点了取消就别再花几秒探测候选源了
    if (sources.size <= 1 || cancelRequested.get()) return sources
    val probeClient = client.newBuilder()
        .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .build()
    val latencies = coroutineScope {
        sources.map { source ->
            async(Dispatchers.IO) {
                val started = SystemClock.elapsedRealtime()
                val ok = runCatching {
                    val request = Request.Builder()
                        .url(source.url)
                        .header("Range", "bytes=0-1023")
                        .header("Accept-Encoding", "identity")
                        .header("User-Agent", USER_AGENT)
                        .build()
                    probeClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use false
                        val total = ResumableDownload
                            .parseContentRange(response.header("Content-Range"))?.total
                            ?: response.body?.contentLength()
                        total == null || expectedSize == null || total == expectedSize
                    }
                }.getOrDefault(false)
                source.id to if (ok) SystemClock.elapsedRealtime() - started else null
            }
        }.awaitAll().toMap()
    }
    val ordered = DownloadSourceSet.orderByProbe(sources, latencies)
    UpdateTrace.log { "源探测延迟(ms) $latencies → ${ordered.map { it.id }}" }
    return ordered
}
