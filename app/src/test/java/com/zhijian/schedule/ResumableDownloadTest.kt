package com.zhijian.schedule

import com.zhijian.schedule.update.ContentRange
import com.zhijian.schedule.update.PartMeta
import com.zhijian.schedule.update.RangeDecision
import com.zhijian.schedule.update.ResumableDownload
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
}
