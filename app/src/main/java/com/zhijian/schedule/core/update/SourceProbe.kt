package com.zhijian.schedule.core.update

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/** 下载前源探测超时：超过这个时间就当该源不可达 */
private const val PROBE_TIMEOUT_MS = 5_000L

/** 吞吐探测要读到的字节数：256 KiB。够了才敢判断「这条源真能跑」，又不至于为探测白下几 MB */
internal const val PROBE_SAMPLE_BYTES = 256L * 1024L

/**
 * 吞吐下限：低于 20 KB/s 判为该源不可用（见 [decideProbeThroughput]）。
 *
 * 为什么不能「连通就算可用」：2026-11-01 实测 ghproxy.net 的 1 KB Range 探测**能通过**（206 / 0.23 s），
 * 但真拉 3 MB 只有 **2 KB/s**；用户会掉进「看起来成功、实际卡住」的支路，等 25 秒 watchdog 才换源。
 * 小样本的延迟骗得过探测，骗不过吞吐，所以判据必须落在吞吐上。
 */
internal const val PROBE_MIN_THROUGHPUT_BPS = 20_000L

/**
 * 探测样本的最低耗时：低于它就不对吞吐下结论。
 *
 * 样本在 50 ms 内读完说明走的是本机/边缘缓存，这个速度不代表跨境链路的真实能力，
 * 但它**也不是坏消息**，所以不给「太快点」这种理由去否定一个能工作的源——统一判为可用。
 */
private const val PROBE_MIN_SAMPLE_MS = 50L

/** 每秒的毫秒数：吞吐换算与日志文案共用 */
private const val MILLIS_PER_SECOND = 1_000L

/** 每 KB 的字节数：只用于把吞吐写成「KB/s」给日志看 */
private const val BYTES_PER_KB = 1_024L

/** 单次探测的结论 */
internal sealed interface ProbeVerdict {
    /** 可连通、长度对得上、吞吐达标；[latencyMs] 参与排序 */
    data class Usable(
        val latencyMs: Long,
    ) : ProbeVerdict

    /** 不可用；[reason] 只进日志与诊断，不给用户看 */
    data class Unusable(
        val reason: String,
    ) : ProbeVerdict
}

/**
 * 把一次探测的原始观测值裁决成 [ProbeVerdict]。纯函数，可直接单测。
 *
 * @param reachable 响应是否成功且长度字段与预期相符
 * @param bodyBytes 实际读到的字节数
 * @param elapsedMs 从发起请求到读完样本的耗时（含建连）
 */
internal fun decideProbeThroughput(
    reachable: Boolean,
    bodyBytes: Long,
    elapsedMs: Long,
): ProbeVerdict {
    if (!reachable) return ProbeVerdict.Unusable("不可达或长度不符")
    if (bodyBytes <= 0L) return ProbeVerdict.Unusable("样本为空")
    val elapsed = elapsedMs.coerceAtLeast(1L)
    if (elapsed < PROBE_MIN_SAMPLE_MS) return ProbeVerdict.Usable(elapsed)
    val throughput = bodyBytes * MILLIS_PER_SECOND / elapsed
    if (throughput >= PROBE_MIN_THROUGHPUT_BPS) return ProbeVerdict.Usable(elapsed)
    return ProbeVerdict.Unusable("吞吐 " + (throughput / BYTES_PER_KB) + " KB/s 低于下限")
}

/**
 * 下载前用 Range 请求探测候选源，按延迟排序。
 * 直连被黑洞时不用等源监视器 25s 才换镜像；探测失败的源保留在链尾，仍可作为退路。
 *
 * 判据有两层：**长度对不对**（避免把「200 的镜像错误页」当成可用源）与**能不能跑**（[decideProbeThroughput]）。
 * 只做前一层的话，ghproxy.net 这类「206 但 2 KB/s」的源会被排进候选，用户一点下载就卡死。
 */
internal suspend fun DownloadSession.probeSources(
    sources: List<UpdateSource>,
    expectedSize: Long?,
): List<UpdateSource> {
    // 已经点了取消就别再花几秒探测候选源了
    if (sources.size <= 1 || cancelRequested.get()) return sources
    val probeClient =
        client
            .newBuilder()
            .callTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    val verdicts =
        coroutineScope {
            sources
                .map { source ->
                    async(Dispatchers.IO) {
                        val started = SystemClock.elapsedRealtime()
                        var bodyBytes = 0L
                        val reachable =
                            runCatching {
                                val request =
                                    Request
                                        .Builder()
                                        .url(source.url)
                                        .header("Range", "bytes=0-" + (PROBE_SAMPLE_BYTES - 1L))
                                        .header("Accept-Encoding", "identity")
                                        .header("User-Agent", USER_AGENT)
                                        .build()
                                probeClient.newCall(request).execute().use { response ->
                                    if (!response.isSuccessful) return@use false
                                    val total =
                                        ResumableDownload
                                            .parseContentRange(response.header("Content-Range"))
                                            ?.total
                                            ?: response.body?.contentLength()
                                    if (total != null && expectedSize != null && total != expectedSize) {
                                        return@use false
                                    }
                                    bodyBytes = readSample(response, PROBE_SAMPLE_BYTES)
                                    true
                                }
                            }.getOrDefault(false)
                        val verdict =
                            decideProbeThroughput(
                                reachable = reachable,
                                bodyBytes = bodyBytes,
                                elapsedMs = SystemClock.elapsedRealtime() - started,
                            )
                        source.id to verdict
                    }
                }.awaitAll()
                .toMap()
        }
    // 不可用的源：延迟记为 null（仍留在链尾当退路，但不参与「快者优先」）
    val latencies = verdicts.mapValues { (_, verdict) -> (verdict as? ProbeVerdict.Usable)?.latencyMs }
    val ordered = DownloadSourceSet.orderByProbe(sources, latencies)
    val detail =
        verdicts.entries.joinToString(separator = ", ") { (id, verdict) ->
            when (verdict) {
                is ProbeVerdict.Usable -> id + "=" + verdict.latencyMs + "ms"
                is ProbeVerdict.Unusable -> id + "=不可用(" + verdict.reason + ")"
            }
        }
    UpdateTrace.log { "源探测 " + detail + " → " + ordered.map { it.id } }
    return ordered
}

/** 读最多 [limit] 字节用来量吞吐；读完就扔，不落盘 */
private fun readSample(
    response: Response,
    limit: Long,
): Long {
    val body = response.body ?: return 0L
    var read = 0L
    val buffer = ByteArray(BUFFER_BYTES)
    body.byteStream().use { input ->
        while (read < limit) {
            val want = minOf(buffer.size.toLong(), limit - read).toInt()
            val n = input.read(buffer, 0, want)
            if (n < 0) break
            read += n
        }
    }
    return read
}
