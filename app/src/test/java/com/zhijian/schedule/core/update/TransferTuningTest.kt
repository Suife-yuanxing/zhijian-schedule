package com.zhijian.schedule.core.update

import com.zhijian.schedule.core.update.GROW_THRESHOLD_PERCENT
import com.zhijian.schedule.core.update.INITIAL_CONNECTIONS
import com.zhijian.schedule.core.update.MAX_CHUNK_BYTES
import com.zhijian.schedule.core.update.MAX_CONNECTIONS
import com.zhijian.schedule.core.update.MIN_CHUNK_BYTES
import com.zhijian.schedule.core.update.MIN_CONNECTIONS
import com.zhijian.schedule.core.update.adaptConnections
import com.zhijian.schedule.core.update.chunkSizeFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 传输调参的纯逻辑测试。
 *
 * 这组断言的依据是 2026-11-01 的真机/PC 实测（见 docs/research/2026-11-01-update-distribution-channels.md）：
 * 1/4/8/16 条并发聚合吞吐 503/629/586/582 KB/s —— 甜点在 4，8 条开始回退。
 */
class TransferTuningTest {
    // ---------- 分片大小随包体联动 ----------

    @Test
    fun chunkSizeKeepsEnoughChunksForInitialConcurrency() {
        // 69 MiB 的包：每片应远大于 4 MiB，让 4 个 worker 各分到 3 片
        val size = chunkSizeFor(69L * 1024 * 1024)
        val chunks = (69L * 1024 * 1024 + size - 1) / size
        assertEquals(INITIAL_CONNECTIONS * 3L, chunks)
        assertTrue(size in MIN_CHUNK_BYTES..MAX_CHUNK_BYTES)
    }

    @Test
    fun trimmedApkStillGetsEnoughChunksAfterDroppingX86() {
        // 砍掉 x86 后包约 31.84 MiB：固定 4 MiB 只剩 8 片，8 个 worker 一次发完。
        // 联动后必须仍然是 12 片，并发才不会名存实亡。
        val size = (31.84 * 1024 * 1024).toLong()
        val chunk = chunkSizeFor(size)
        val chunks = (size + chunk - 1) / chunk
        assertEquals(INITIAL_CONNECTIONS * 3L, chunks)
        assertTrue("联动后每片应小于旧的 4 MiB 常量", chunk < ResumableDownload.DEFAULT_CHUNK_BYTES)
    }

    @Test
    fun chunkSizeIsClampedAtBothEnds() {
        // 极小的包：不能切出小于 1 MiB 的碎片
        assertEquals(MIN_CHUNK_BYTES, chunkSizeFor(3L * 1024 * 1024))
        // 极大的包：不能切出大于 8 MiB 的巨片（尾片空洞会拖长收尾）
        assertEquals(MAX_CHUNK_BYTES, chunkSizeFor(10_000L * 1024 * 1024))
    }

    @Test
    fun unknownTotalFallsBackToLegacyChunkSize() {
        assertEquals(ResumableDownload.DEFAULT_CHUNK_BYTES, chunkSizeFor(0L))
        assertEquals(ResumableDownload.DEFAULT_CHUNK_BYTES, chunkSizeFor(-1L))
    }

    @Test
    fun chunkSizeRespectsConnectionBounds() {
        // 越界的并发数先被夹进合法区间，再参与计算
        assertEquals(chunkSizeFor(50L * 1024 * 1024, MIN_CONNECTIONS), chunkSizeFor(50L * 1024 * 1024, 0))
        assertEquals(chunkSizeFor(50L * 1024 * 1024, MAX_CONNECTIONS), chunkSizeFor(50L * 1024 * 1024, 999))
    }

    // ---------- 自适应并发 ----------

    @Test
    fun growingThroughputRaisesConcurrencyByOne() {
        val next = adaptConnections(4, previousBps = 500_000L, latestBps = 600_000L)
        assertEquals(5, next)
        assertTrue("20% 的增长应触发上调", (600_000L - 500_000L) * 100L / 500_000L >= GROW_THRESHOLD_PERCENT)
    }

    @Test
    fun flatThroughputFreezesConcurrency() {
        assertEquals(4, adaptConnections(4, previousBps = 500_000L, latestBps = 510_000L))
        assertEquals(4, adaptConnections(4, previousBps = 500_000L, latestBps = 500_000L))
    }

    @Test
    fun shrinkingThroughputFreezesInsteadOfShrinking() {
        // 只增不降：下降时就停在当前档，不掐断在飞连接（那些分片的下半截会白下）
        assertEquals(5, adaptConnections(5, previousBps = 600_000L, latestBps = 300_000L))
        assertEquals(MIN_CONNECTIONS, adaptConnections(MIN_CONNECTIONS, previousBps = 900_000L, latestBps = 100_000L))
    }

    @Test
    fun concurrencyNeverExceedsCap() {
        assertEquals(MAX_CONNECTIONS, adaptConnections(MAX_CONNECTIONS, previousBps = 100_000L, latestBps = 900_000L))
    }

    @Test
    fun missingSamplesLeaveConcurrencyUntouched() {
        assertEquals(4, adaptConnections(4, previousBps = null, latestBps = 900_000L))
        assertEquals(4, adaptConnections(4, previousBps = 500_000L, latestBps = null))
        assertEquals(4, adaptConnections(4, previousBps = 0L, latestBps = 900_000L))
        assertEquals(4, adaptConnections(4, previousBps = 500_000L, latestBps = 0L))
    }

    @Test
    fun outOfRangeCurrentIsNormalizedFirst() {
        // 非法输入不该把状态机带歪：先夹进 [MIN_CONNECTIONS, MAX_CONNECTIONS] 再判断
        assertEquals(MIN_CONNECTIONS, adaptConnections(0, previousBps = 100L, latestBps = 100L))
        assertEquals(MAX_CONNECTIONS, adaptConnections(99, previousBps = 100L, latestBps = 100L))
    }
}
