package com.zhijian.schedule.core.update

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 下载前源探测的**总**超时：超过这个时间就当该源不可达。
 *
 * 与 [PROBE_HARD_CAP_MS] 取同一个值：读样本那边到点主动收手，这里兜住「连都连不上」的情况
 * （建连卡住时读循环根本进不去，只能靠 callTimeout 把它掐掉）。
 * 旧值 5s、后来 3s，在「直连被黑洞」的真实网络下都会把点击后的首屏拖满整段超时——
 * 而那段正是用户已经点完按钮、在等第一个字节的时间。
 */
private const val PROBE_TIMEOUT_MS = 1_500L

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

/**
 * 探测的提前收手阈值：每读一小口就回头看一眼还值不值得读。
 *
 * 为什么需要：探测原本要**读完 256 KiB 或等到 5s callTimeout** 才下结论，
 * 而点「下载更新」之后用户就是在等这一段。一个黑洞源（连得上、跑不动）
 * 会把整个探测拖满 5 秒——真正的传输一个字节都还没开始。
 * 现在只要在 150ms 之后观测到的吞吐不到下限的两倍，就当场判死、立刻换下一个候选。
 * 判据与最终裁决同源（都是吞吐），只是把「什么时候能确定」提前了：
 * 好源在这 150ms 里已经跑出几十 KB，坏源再等 4.8 秒也追不回来。
 */
internal const val PROBE_EARLY_CHECK_MS = 150L

/**
 * 探测的硬上限：到点一律收手。
 *
 * 实测（2026-11-04，PKG110 + 移动网络）：直连 GitHub 走黑洞时，那一条 Range 请求会把
 * 建连超时吃满，3 s 后才判「不可达」，而这 3 s 全部落在用户点完按钮之后的等待里。
 * 1.5 s 已经足够让一个能跑的源跑出几十 KB（判据只要 40 KB/s 就通过），
 * 再等下去只是在替黑洞源数秒。
 */
internal const val PROBE_HARD_CAP_MS = 1_500L

/** 提前收手的吞吐要求：下限的两倍，留出抖动余量后再判死 */
internal const val PROBE_EARLY_MIN_BPS = PROBE_MIN_THROUGHPUT_BPS * 2L

/**
 * 读样本的过程中是否该立刻放弃这个源。纯函数，可直接单测。
 *
 * @param elapsedMs 从发起请求到此刻的毫秒数
 * @param bytesRead 目前读到的字节数
 */
internal fun shouldAbortProbe(
    elapsedMs: Long,
    bytesRead: Long,
): Boolean {
    if (elapsedMs >= PROBE_HARD_CAP_MS) return true
    if (elapsedMs < PROBE_EARLY_CHECK_MS) return false
    val throughput = bytesRead * MILLIS_PER_SECOND / elapsedMs.coerceAtLeast(1L)
    return throughput < PROBE_EARLY_MIN_BPS
}

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
    //
    // 直连**不参与探测**：它排在链首是既定事实（browser_download_url 就是官方地址，
    // 没有别的源能替代它的权威性），探测它只能得到两个结果——「可用」（那这次探测纯属白等）
    // 或「不可达」（那还得回退去试它，本次探测同样白等）。
    // 实测：直连被黑洞时这一条探测会把 callTimeout 吃满（3 s），而这 3 s 全在用户点完按钮之后。
    // 现在只探测镜像候选（快者优先），直连固定留在链首；它真的不通，由 watchdog 换源兜住。
    val probes = sources.filter { it.id != DownloadSourceSet.DIRECT_ID }
    if (probes.isEmpty() || cancelRequested.get()) return sources
    //
    // **只有一条镜像时探测没有意义**：探测的唯一产出是「按延迟给候选排序」，
    // 一条候选的排序结果就是它自己。不探，省下的正是点击后那 1.5 秒
    // （实测 gh-proxy 的探测全程 1.49 s，几乎全是建连 + TLS，与吞吐无关）。
    // 代价是这条镜像真的不通时要等 watchdog 的首字节早判（6 s）才换源——
    // 而那只会发生在直连也不通的时候，那时界面已经在走「正在下载」的进度条。
    if (probes.size <= 1) {
        UpdateTrace.log { "只有一条镜像，跳过探测：" + sources.joinToString(",") { it.id } }
        return sources
    }
    val verdicts =
        coroutineScope {
            probes
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
                                    bodyBytes = readSample(response, PROBE_SAMPLE_BYTES, started)
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
    // 直连固定排第一（它没被探测，也没必要被探测），其余按实测延迟排序
    val directFirst = sources.filter { it.id == DownloadSourceSet.DIRECT_ID }
    val ordered = DownloadSourceSet.orderByProbe(sources, latencies)
    val final = directFirst + ordered.filter { it.id != DownloadSourceSet.DIRECT_ID }
    val detail =
        verdicts.entries.joinToString(separator = ", ") { (id, verdict) ->
            when (verdict) {
                is ProbeVerdict.Usable -> id + "=" + verdict.latencyMs + "ms"
                is ProbeVerdict.Unusable -> id + "=不可用(" + verdict.reason + ")"
            }
        }
    UpdateTrace.log { "镜像探测 " + detail + " → " + final.map { it.id } }
    return final
}

/**
 * 读最多 [limit] 字节用来量吞吐；读完就扔，不落盘。
 *
 * 每读完一小口就问一次 [shouldAbortProbe]：坏源不必等满样本就把坑让出来。
 * 提前放弃时返回的是**已经读到的字节数**，最终裁决照常用它算吞吐，所以结论仍是一致的——
 * 只是这个结论来得早了。
 */
private fun readSample(
    response: Response,
    limit: Long,
    startedAt: Long,
): Long {
    val body = response.body ?: return 0L
    var read = 0L
    val buffer = ByteArray(BUFFER_BYTES)
    body.byteStream().use { input ->
        // 单个退出条件：读完 / 流结束 / 该提前收手，三种情况都靠 while 条件收口，
        // 循环体里不再出现 break —— 两个 break 会让「到底为什么停」变得难读。
        var streaming = true
        while (streaming && read < limit) {
            val want = minOf(buffer.size.toLong(), limit - read).toInt()
            val n = input.read(buffer, 0, want)
            if (n < 0) {
                streaming = false
            } else {
                read += n
                streaming = !shouldAbortProbe(SystemClock.elapsedRealtime() - startedAt, read)
            }
        }
    }
    return read
}
