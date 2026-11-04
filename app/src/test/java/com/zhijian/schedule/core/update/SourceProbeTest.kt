package com.zhijian.schedule.core.update

import com.zhijian.schedule.core.update.PROBE_EARLY_CHECK_MS
import com.zhijian.schedule.core.update.PROBE_HARD_CAP_MS
import com.zhijian.schedule.core.update.PROBE_MIN_THROUGHPUT_BPS
import com.zhijian.schedule.core.update.ProbeVerdict
import com.zhijian.schedule.core.update.decideProbeThroughput
import com.zhijian.schedule.core.update.shouldAbortProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ---------- 提前收手：这一段决定了「点下载」之后用户要等多久 ----------

    @Test
    fun probeKeepsReadingBeforeTheEarlyCheckPoint() {
        // 前 150ms 一律不打断：样本太短量不出吞吐，早断会把好源也判死
        assertFalse(shouldAbortProbe(elapsedMs = 0, bytesRead = 0))
        assertFalse(shouldAbortProbe(elapsedMs = PROBE_EARLY_CHECK_MS - 1, bytesRead = 0))
    }

    @Test
    fun stalledSourceIsAbortedRightAfterEarlyCheck() {
        // 150ms 只读到 1 KB ≈ 6.8 KB/s，远低于下限的两倍：立刻让位给下一个候选
        assertTrue(shouldAbortProbe(elapsedMs = PROBE_EARLY_CHECK_MS, bytesRead = 1_024))
    }

    @Test
    fun fastSourceIsNotAborted() {
        // 150ms 读到 64 KB ≈ 437 KB/s：好源必须让它继续，不能把能跑的源打断
        assertFalse(shouldAbortProbe(elapsedMs = PROBE_EARLY_CHECK_MS, bytesRead = 64L * 1024))
    }

    @Test
    fun hardCapAlwaysAborts() {
        // 无论读得多快，到硬上限必须收手：探测不能把点击后的首屏拖过 3 秒
        assertTrue(shouldAbortProbe(elapsedMs = PROBE_HARD_CAP_MS, bytesRead = 10L * 1024 * 1024))
    }

    @Test
    fun abortedSampleStillYieldsSameVerdict() {
        // 提前放弃时返回的是已读字节数，最终裁决仍按吞吐算，结论与读满样本时一致
        val aborted = decideProbeThroughput(reachable = true, bodyBytes = 1_024, elapsedMs = PROBE_EARLY_CHECK_MS)
        assertTrue("提前放弃的源必须判为不可用", aborted is ProbeVerdict.Unusable)
    }
}
