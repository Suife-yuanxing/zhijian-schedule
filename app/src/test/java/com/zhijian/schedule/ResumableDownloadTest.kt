package com.zhijian.schedule

import com.zhijian.schedule.update.Chunk
import com.zhijian.schedule.update.ChunkProgress
import com.zhijian.schedule.update.ContentRange
import com.zhijian.schedule.update.PartMeta
import com.zhijian.schedule.update.RangeDecision
import com.zhijian.schedule.update.RangeSupport
import com.zhijian.schedule.update.ReadVerdict
import com.zhijian.schedule.update.ResumableDownload
import com.zhijian.schedule.update.ResumePoint
import com.zhijian.schedule.update.VerifyResult
import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumableDownloadTest {

    // ---------- Content-Range ----------

    @Test
    fun contentRangeParsesStartEndAndTotal() {
        val range = ResumableDownload.parseContentRange("bytes 1234-46920381/46920382")
        assertEquals(ContentRange(1234L, 46920381L, 46920382L), range)
    }

    @Test
    fun contentRangeAcceptsUnknownTotal() {
        val range = ResumableDownload.parseContentRange("bytes 0-99/*")
        assertEquals(ContentRange(0L, 99L, null), range)
    }

    @Test
    fun contentRangeReturnsNullWhenMalformedOrAbsent() {
        assertNull(ResumableDownload.parseContentRange(null))
        assertNull(ResumableDownload.parseContentRange(""))
        assertNull(ResumableDownload.parseContentRange("bytes 1234-"))
        assertNull(ResumableDownload.parseContentRange("bytes -99/100"))
        assertNull(ResumableDownload.parseContentRange("items 1-2/3"))
    }

    // ---------- 进度分母 ----------

    @Test
    fun totalPrefersContentRangeOverEverything() {
        val range = ContentRange(1000L, 1999L, 5000L)
        assertEquals(5000L, ResumableDownload.resolveTotalBytes(1000L, range, 4000L))
    }

    @Test
    fun totalIgnoresRemainingContentLengthOn206() {
        // 206 的 Content-Length 只是剩余字节，必须靠 Content-Range 的 total
        val range = ContentRange(1000L, 4999L, 5000L)
        assertEquals(5000L, ResumableDownload.resolveTotalBytes(4000L, range, null))
    }

    @Test
    fun totalFallsBackToKnownSizeWhenRangeTotalIsStar() {
        val range = ContentRange(1000L, 4999L, null)
        assertEquals(5000L, ResumableDownload.resolveTotalBytes(4000L, range, 5000L))
    }

    @Test
    fun totalUsesContentLengthOnFullResponse() {
        assertEquals(46920382L, ResumableDownload.resolveTotalBytes(46920382L, null, null))
    }

    @Test
    fun totalFallsBackToKnownSizeWhenNoLengthHeader() {
        assertEquals(46920382L, ResumableDownload.resolveTotalBytes(null, null, 46920382L))
        assertEquals(null, ResumableDownload.resolveTotalBytes(null, null, null))
        assertEquals(null, ResumableDownload.resolveTotalBytes(0L, null, 0L))
    }

    // ---------- Range 决策 ----------

    @Test
    fun partialContentAppendsFromExistingBytes() {
        assertEquals(RangeDecision.Append(1000L), ResumableDownload.decideRange(206, 1000L, 5000L))
    }

    @Test
    fun okResponseRestartsFromScratch() {
        assertEquals(RangeDecision.Restart, ResumableDownload.decideRange(200, 1000L, 5000L))
    }

    @Test
    fun rangeNotSatisfiableWithFullBytesIsAlreadyComplete() {
        assertEquals(RangeDecision.AlreadyComplete, ResumableDownload.decideRange(416, 5000L, 5000L))
    }

    @Test
    fun rangeNotSatisfiableWithMismatchedBytesIsCorrupt() {
        assertEquals(RangeDecision.Corrupt, ResumableDownload.decideRange(416, 6000L, 5000L))
        assertEquals(RangeDecision.Corrupt, ResumableDownload.decideRange(416, 0L, null))
    }

    @Test
    fun clientErrorDoesNotOfferResume() {
        val decision = ResumableDownload.decideRange(404, 1000L, 5000L)
        assertTrue(decision is RangeDecision.Fail)
        assertEquals(false, (decision as RangeDecision.Fail).canResume)
        assertTrue(decision.reason.contains("404"))
    }

    @Test
    fun serverErrorOffersResume() {
        val decision = ResumableDownload.decideRange(502, 1000L, 5000L)
        assertEquals(true, (decision as RangeDecision.Fail).canResume)
    }

    // ---------- 断点元数据 ----------

    @Test
    fun partMetaRoundTripsThroughJson() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val meta = PartMeta(
            version = "1.0.3",
            url = "https://example.com/a.apk",
            etag = "\"abc\"",
            lastModified = "Wed, 15 Oct 2026 10:00:00 GMT",
            totalBytes = 46920382L,
            digest = "sha256:d3dba925",
        )
        assertEquals(meta, json.decodeFromString<PartMeta>(json.encodeToString(meta)))
    }

    @Test
    fun partMetaKeepsOptionalsNullOnRoundTrip() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val meta = PartMeta(version = "1.0.3", url = "https://example.com/a.apk")
        val decoded = json.decodeFromString<PartMeta>(json.encodeToString(meta))
        assertEquals(meta, decoded)
        assertNull(decoded.etag)
        assertEquals(0L, decoded.totalBytes)
    }

    @Test
    fun partMetaToleratesUnknownKeysAndMissingOptionals() {
        val json = Json { ignoreUnknownKeys = true }
        val decoded = json.decodeFromString<PartMeta>(
            """{"version":"1.0.3","url":"https://example.com/a.apk","futureField":42}"""
        )
        assertEquals("1.0.3", decoded.version)
        assertNull(decoded.etag)
        assertEquals(0L, decoded.totalBytes)
    }

    // ---------- 摘要与校验 ----------

    @Test
    fun digestNormalizesSha256PrefixAndCase() {
        assertEquals(SHA256_ABC, ResumableDownload.normalizeDigest("sha256:$SHA256_ABC"))
        assertEquals(SHA256_ABC, ResumableDownload.normalizeDigest("SHA256:${SHA256_ABC.uppercase()}"))
    }

    @Test
    fun digestReturnsNullWhenAbsentOrUnusable() {
        assertNull(ResumableDownload.normalizeDigest(null))
        assertNull(ResumableDownload.normalizeDigest(""))
        assertNull(ResumableDownload.normalizeDigest("md5:$SHA256_ABC"))
        assertNull(ResumableDownload.normalizeDigest("sha256:abc"))          // 长度不对
        assertNull(ResumableDownload.normalizeDigest("sha256:" + "z".repeat(64))) // 非十六进制
    }

    @Test
    fun sha256MatchesKnownVector() {
        assertEquals(SHA256_ABC, ResumableDownload.sha256Hex("abc".toByteArray().inputStream()))
    }

    @Test
    fun sha256HandlesEmptyInput() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ResumableDownload.sha256Hex(ByteArray(0).inputStream()),
        )
    }

    @Test
    fun verifyAcceptsMatchingFile() {
        val file = tempFile("abc")
        assertEquals(VerifyResult.Ok, ResumableDownload.verifyFile(file, 3L, "sha256:$SHA256_ABC"))
    }

    @Test
    fun verifyRejectsWrongLength() {
        assertEquals(
            VerifyResult.LengthMismatch,
            ResumableDownload.verifyFile(tempFile("abc"), 4L, null),
        )
    }

    @Test
    fun verifyRejectsWrongDigest() {
        assertEquals(
            VerifyResult.DigestMismatch,
            ResumableDownload.verifyFile(tempFile("abc"), 3L, "sha256:" + "0".repeat(64)),
        )
    }

    @Test
    fun verifySkipsHashWhenDigestMissingOrUnusable() {
        val file = tempFile("abc")
        assertEquals(VerifyResult.Ok, ResumableDownload.verifyFile(file, 3L, null))
        assertEquals(VerifyResult.Ok, ResumableDownload.verifyFile(file, null, "not-a-digest"))
    }

    @Test
    fun verifySkipsLengthWhenExpectedBytesUnknown() {
        assertEquals(
            VerifyResult.Ok,
            ResumableDownload.verifyFile(tempFile("abc"), null, "sha256:$SHA256_ABC"),
        )
    }

    // ---------- 展示 ----------

    @Test
    fun formatBytesUsesBinarySteps() {
        assertEquals("0 B", ResumableDownload.formatBytes(0L))
        assertEquals("1023 B", ResumableDownload.formatBytes(1023L))
        assertEquals("1.0 KB", ResumableDownload.formatBytes(1024L))
        assertEquals("44.7 MB", ResumableDownload.formatBytes(46920382L))
        assertEquals("1.0 GB", ResumableDownload.formatBytes(1024L * 1024L * 1024L))
        assertEquals("0 B", ResumableDownload.formatBytes(-5L))
    }

    @Test
    fun etaTextFormatsAndGuards() {
        assertNull(ResumableDownload.etaText(null))
        assertEquals("约剩 0 秒", ResumableDownload.etaText(0L))
        assertEquals("约剩 30 秒", ResumableDownload.etaText(30L))
        assertEquals("约剩 1 分", ResumableDownload.etaText(60L))
        assertEquals("约剩 2 分", ResumableDownload.etaText(90L))
        assertEquals("约剩 1 小时", ResumableDownload.etaText(3600L))
    }

    // ---------- 清理规则 ----------

    @Test
    fun versionOfStripsKnownSuffixes() {
        assertEquals("1.0.2", ResumableDownload.versionOf("1.0.2.apk"))
        assertEquals("1.0.2", ResumableDownload.versionOf("1.0.2.apk.part"))
        assertEquals("1.0.2", ResumableDownload.versionOf("1.0.2.apk.meta"))
        assertEquals("1.0", ResumableDownload.versionOf("1.0.apk"))
    }

    @Test
    fun versionOfRejectsUnknownOrNonNumericNames() {
        assertNull(ResumableDownload.versionOf("readme.txt"))
        assertNull(ResumableDownload.versionOf(".apk"))
        assertNull(ResumableDownload.versionOf("backup.apk.bak"))
        assertNull(ResumableDownload.versionOf("1.0.2-beta.apk"))
        assertNull(ResumableDownload.versionOf("v1.0.2.apk"))
    }

    @Test
    fun compareNumericVersionComparesNumericallyNotLexically() {
        assertEquals(0, ResumableDownload.compareNumericVersion("1.0.2", "1.0.2"))
        assertEquals(0, ResumableDownload.compareNumericVersion("1.0", "1.0.0"))
        assertEquals(-1, ResumableDownload.compareNumericVersion("1.0.9", "1.0.10"))
        assertEquals(1, ResumableDownload.compareNumericVersion("2.0.0", "1.9.9"))
        assertEquals(1, ResumableDownload.compareNumericVersion("1.1", "1.0.9"))
    }

    @Test
    fun shouldCleanRemovesAlreadyInstalledVersions() {
        assertTrue(ResumableDownload.shouldClean("1.0.2.apk", "1.0.2"))
        assertTrue(ResumableDownload.shouldClean("1.0.1.apk.part", "1.0.2"))
        assertTrue(ResumableDownload.shouldClean("1.0.1.apk.meta", "1.0.2"))
        assertTrue(ResumableDownload.shouldClean("1.0.9.apk", "1.0.10"))
    }

    @Test
    fun shouldCleanKeepsNewerAndUnknownFiles() {
        assertEquals(false, ResumableDownload.shouldClean("1.0.3.apk", "1.0.2"))
        assertEquals(false, ResumableDownload.shouldClean("1.0.3.apk.part", "1.0.2"))
        assertEquals(false, ResumableDownload.shouldClean("2.0.0.apk", "1.9.9"))
        assertEquals(false, ResumableDownload.shouldClean("random.txt", "1.0.2"))
        assertEquals(false, ResumableDownload.shouldClean("1.0.2-beta.apk", "1.0.2"))
    }

    // ---------- 分片规划 ----------

    @Test
    fun planChunksSplitsWithShorterLastChunk() {
        val plan = ResumableDownload.planChunks(10L, chunkSize = 4L)!!
        assertEquals(3, plan.chunkCount)
        assertEquals(
            listOf(
                Chunk(0, 0L, 3L),
                Chunk(1, 4L, 7L),
                Chunk(2, 8L, 9L),
            ),
            plan.chunks,
        )
        assertEquals(4L, plan.bytesOf(0))
        assertEquals(4L, plan.bytesOf(1))
        assertEquals(2L, plan.bytesOf(2))
    }

    @Test
    fun planChunksKeepsSingleChunkWhenTotalIsSmallerThanChunkSize() {
        val plan = ResumableDownload.planChunks(100L, chunkSize = 4096L)!!
        assertEquals(1, plan.chunkCount)
        assertEquals(listOf(Chunk(0, 0L, 99L)), plan.chunks)
    }

    @Test
    fun planChunksExactMultipleHasNoRemainder() {
        val plan = ResumableDownload.planChunks(8L, chunkSize = 4L)!!
        assertEquals(2, plan.chunkCount)
        assertEquals(listOf(Chunk(0, 0L, 3L), Chunk(1, 4L, 7L)), plan.chunks)
        assertEquals(8L, plan.chunks.sumOf { plan.bytesOf(it.index) })
    }

    @Test
    fun planChunksSplitsARealApkIntoTwelve() {
        val plan = ResumableDownload.planChunks(46920382L, chunkSize = 4L * 1024L * 1024L)!!
        assertEquals(12, plan.chunkCount)
        assertEquals(11, plan.chunks.last().index)
        assertEquals(46920382L - 11 * 4L * 1024L * 1024L, plan.bytesOf(11))
    }

    @Test
    fun planChunksRejectsInvalidInput() {
        assertNull(ResumableDownload.planChunks(0L, 4L))
        assertNull(ResumableDownload.planChunks(-1L, 4L))
        assertNull(ResumableDownload.planChunks(100L, 0L))
        assertNull(ResumableDownload.planChunks(100L, -4L))
    }

    // ---------- 进度聚合 ----------

    @Test
    fun progressSumsFinishedChunksAndInFlightBytes() {
        val plan = ResumableDownload.planChunks(10L, chunkSize = 4L)!!
        val progress = ChunkProgress(plan = plan, completed = listOf(0), written = longArrayOf(4L, 1L, 0L))
        assertEquals(5L, progress.downloadedBytes)
    }

    @Test
    fun progressGoesBackwardsWhenAChunkIsRedone() {
        val plan = ResumableDownload.planChunks(10L, chunkSize = 4L)!!
        val progress = ChunkProgress(plan = plan, completed = listOf(0), written = longArrayOf(4L, 3L, 0L))
        assertEquals(7L, progress.downloadedBytes)
        progress.resetChunk(1)
        assertEquals(4L, progress.downloadedBytes)
    }

    @Test
    fun progressNeededChunksSkipsCompletedOnes() {
        val plan = ResumableDownload.planChunks(10L, chunkSize = 4L)!!
        val progress = ChunkProgress(plan = plan, completed = listOf(0, 2), written = longArrayOf(4L, 0L, 2L))
        assertEquals(listOf(1), progress.neededChunks())
    }

    @Test
    fun remainingBytesCountsOnlyWhatIsStillMissing() {
        val plan = ResumableDownload.planChunks(10L, chunkSize = 4L)!!
        val progress = ChunkProgress(plan = plan, completed = listOf(0), written = longArrayOf(4L, 0L, 0L))
        assertEquals(6L, progress.remainingBytes())
    }

    // ---------- 续传点 ----------

    @Test
    fun resumePointReusesPlanWhenSourceAndTotalMatch() {
        val plan = ResumableDownload.planChunks(10L, chunkSize = 4L)!!
        val meta = PartMeta(
            version = "1.0.3",
            url = "https://example.com/a.apk",
            totalBytes = 10L,
            chunkSize = 4L,
            completedChunks = listOf(0),
        )
        val point = ResumableDownload.resolveResumePoint(
            version = "1.0.3",
            url = "https://example.com/a.apk",
            totalBytes = 10L,
            partBytes = 6L,
            rawMeta = meta,
        )
        assertEquals(ResumePoint.Chunked(plan, listOf(0)), point)
    }

    @Test
    fun resumePointUpgradesV1PartFileByReplanningChunks() {
        // v1 写的元数据没有 chunkSize/completedChunks（取默认值 0 与空表），分片大小归一化成 4 MiB。
        // 这里用真实的 4 MiB 分片、只是把总长缩到 16 MiB —— 才是在测生产路径，而不是靠一个假的小分片。
        val total = 16L * 1024L * 1024L
        val meta = PartMeta(
            version = "1.0.3",
            url = "https://example.com/a.apk",
            totalBytes = total,
        )
        val plan = ResumableDownload.planChunks(total)!!
        val point = ResumableDownload.resolveResumePoint(
            version = "1.0.3",
            url = "https://example.com/a.apk",
            totalBytes = total,
            partBytes = 10L * 1024L * 1024L,
            rawMeta = meta,
        )
        // 10 MiB = 前 2 片（各 4 MiB）完整 + 第 3 片半截，只有整片算数，半截那片重下
        assertEquals(ResumePoint.Chunked(plan, listOf(0, 1)), point)
    }

    @Test
    fun resumePointStartsFreshWhenSourceChangedAndKeepsThePlan() {
        val plan = ResumableDownload.planChunks(16L, chunkSize = 4L)!!
        val meta = PartMeta(
            version = "1.0.3",
            url = "https://mirror.example.com/a.apk",
            totalBytes = 16L,
            chunkSize = 4L,
            completedChunks = listOf(0),
        )
        val point = ResumableDownload.resolveResumePoint(
            version = "1.0.3",
            url = "https://example.com/a.apk",
            totalBytes = 16L,
            partBytes = 10L,
            rawMeta = meta,
        )
        // 换了源：已下的字节必须作废，但分片计划要一起带出来，调用方不必再算一遍
        assertEquals(ResumePoint.Fresh(plan), point)
    }

    @Test
    fun resumePointStartsFreshWithoutAnyPartFile() {
        val total = 16L * 1024L * 1024L
        val plan = ResumableDownload.planChunks(total)!!
        assertEquals(
            ResumePoint.Fresh(plan),
            ResumableDownload.resolveResumePoint("1.0.3", "https://example.com/a.apk", total, 0L, null),
        )
        // 总长未知时照样是 Fresh，只是计划为 null（调用方据此走单连接）
        assertEquals(
            ResumePoint.Fresh(null),
            ResumableDownload.resolveResumePoint("1.0.3", "https://example.com/a.apk", null, 0L, null),
        )
    }

    @Test
    fun chunkCountOfConvertsWrittenBytesIntoWholeChunks() {
        assertEquals(0, ResumableDownload.chunkCountOf(0L, 4L))
        assertEquals(0, ResumableDownload.chunkCountOf(3L, 4L))
        assertEquals(1, ResumableDownload.chunkCountOf(4L, 4L))
        assertEquals(1, ResumableDownload.chunkCountOf(7L, 4L))
        assertEquals(2, ResumableDownload.chunkCountOf(10L, 4L))
        assertEquals(2, ResumableDownload.chunkCountOf(11L, 4L))
        assertNull(ResumableDownload.chunkCountOf(10L, 0L))
        assertNull(ResumableDownload.chunkCountOf(10L, -1L))
    }

    // ---------- 降级判据 ----------

    @Test
    fun partialContentMeansTheServerHonoursRange() {
        assertEquals(RangeSupport.Supported, ResumableDownload.decideRangeSupport(206, 3, 2))
    }

    @Test
    fun okResponseMeansTheServerIgnoresRange() {
        // 服务端忽略 Range：并行分片毫无意义，立刻回到单连接
        assertEquals(RangeSupport.Unsupported, ResumableDownload.decideRangeSupport(200, 3, 0))
    }

    @Test
    fun partialContentStillNeedsTheLastChunkInFlight() {
        // 还差最后一片：不能当成「已经下完」，否则会误进校验
        assertEquals(RangeSupport.Supported, ResumableDownload.decideRangeSupport(206, 3, 1))
    }

    @Test
    fun rangeNotSatisfiableWithAllChunksDoneIsAlreadyComplete() {
        assertEquals(RangeSupport.AlreadyComplete, ResumableDownload.decideRangeSupport(416, 3, 3))
    }

    @Test
    fun serverAndClientErrorsAreNotADowngrade() {
        assertTrue(ResumableDownload.decideRangeSupport(502, 3, 0) is RangeSupport.Fail)
        val fail = ResumableDownload.decideRangeSupport(404, 3, 0) as RangeSupport.Fail
        assertEquals(false, fail.canResume)
    }

    // ---------- 读取中途 IO 失败的判停（真机闪退回归：HTTP/2 stream was reset: CANCEL）----------

    @Test
    fun readFailureWhileCancellingMeansStop() {
        // 用户点了取消，流被 OkHttp 重置（HTTP/2 RST_STREAM）从 read() 冒出来：
        // 必须判停（Paused），不能当普通网络失败去重试 3 次
        val e = java.io.IOException("stream was reset: CANCEL")
        val verdict = ResumableDownload.resolveReadFailure(e, cancelRequested = true)
        assertTrue(verdict is ReadVerdict.StopCancelled)
    }

    @Test
    fun readFailureWithoutCancelMeansRetry() {
        // 单个分片的连接被掐（飞行模式、弱网）：这一片整片重试，不是全局失败
        val e = java.io.IOException("connection reset")
        val verdict = ResumableDownload.resolveReadFailure(e, cancelRequested = false)
        assertTrue(verdict is ReadVerdict.Retry)
        assertEquals("网络中断（connection reset）", (verdict as ReadVerdict.Retry).reason)
    }

    @Test
    fun cancellationExceptionStaysUntouched() {
        // 协程取消必须原样上抛，吞掉它会破坏结构化并发
        val e = java.util.concurrent.CancellationException("job was cancelled")
        val verdict = ResumableDownload.resolveReadFailure(e, cancelRequested = false)
        assertTrue(verdict is ReadVerdict.Rethrow)
    }

    private fun tempFile(content: String): File {
        val file = File.createTempFile("part", ".bin")
        file.writeBytes(content.toByteArray(Charsets.UTF_8))
        file.deleteOnExit()
        return file
    }

    private companion object {
        const val SHA256_ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
    }
}
