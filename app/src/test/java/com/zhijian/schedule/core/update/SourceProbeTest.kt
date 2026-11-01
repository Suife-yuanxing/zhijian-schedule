package com.zhijian.schedule.core.update

import com.zhijian.schedule.core.update.PROBE_MIN_THROUGHPUT_BPS
import com.zhijian.schedule.core.update.ProbeVerdict
import com.zhijian.schedule.core.update.decideProbeThroughput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 源探测吞吐判据的纯逻辑测试。
 *
 * 真实背景（2026-11-01 实测）：ghproxy.net 的 1 KB Range 探测能通过（206 / 0.23 s），
 * 但真拉 3 MB 只有 2 KB/s。只靠「连通」判据会把它排进候选，用户点下载后卡死等 25 秒 watchdog。
 */
class SourceProbeTest {
    @Test
    fun healthySourceIsUsable() {
        // 256 KiB / 1 s ≈ 262 KB/s，远高于 20 KB/s 下限
        val verdict = decideProbeThroughput(reachable = true, bodyBytes = 256L * 1024, elapsedMs = 1_000)
        assertTrue(verdict is ProbeVerdict.Usable)
        assertEquals(1_000L, (verdict as ProbeVerdict.Usable).latencyMs)
    }

    @Test
    fun crawlingSourceIsRejectedEvenThoughItResponds() {
        // 这就是 ghproxy.net 的形态：能回 206、能读出字节，但吞吐只有 2 KB/s
        val verdict = decideProbeThroughput(reachable = true, bodyBytes = 4L * 1024, elapsedMs = 2_000)
        assertTrue("响应成功不等于可用", verdict is ProbeVerdict.Unusable)
        assertTrue((verdict as ProbeVerdict.Unusable).reason.contains("KB/s"))
    }

    @Test
    fun unreachableSourceIsRejected() {
        assertTrue(decideProbeThroughput(reachable = false, bodyBytes = 0, elapsedMs = 5_000) is ProbeVerdict.Unusable)
    }

    @Test
    fun emptySampleIsRejected() {
        assertTrue(decideProbeThroughput(reachable = true, bodyBytes = 0, elapsedMs = 500) is ProbeVerdict.Unusable)
    }

    @Test
    fun veryFastCachedResponseIsNotPunished() {
        // 命中边缘缓存时几十毫秒就读完，这不是坏消息，不能因为「太快了」被判为不可用
        val verdict = decideProbeThroughput(reachable = true, bodyBytes = 256L * 1024, elapsedMs = 5)
        assertTrue(verdict is ProbeVerdict.Usable)
    }

    @Test
    fun throughputExactlyAtThresholdPasses() {
        // 边界：正好等于下限应当算可用（否则阈值语义会随机漂移）
        val verdict = decideProbeThroughput(reachable = true, bodyBytes = PROBE_MIN_THROUGHPUT_BPS, elapsedMs = 1_000)
        assertTrue(verdict is ProbeVerdict.Usable)
    }

    @Test
    fun zeroElapsedIsTreatedAsInstant() {
        // elapsed=0 不能引发除零；按 1 ms 处理并判为可用
        assertTrue(decideProbeThroughput(reachable = true, bodyBytes = 1024, elapsedMs = 0) is ProbeVerdict.Usable)
    }
}
