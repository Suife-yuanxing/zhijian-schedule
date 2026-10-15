package com.zhijian.schedule

import com.zhijian.schedule.update.ContentRange
import com.zhijian.schedule.update.PartMeta
import com.zhijian.schedule.update.RangeDecision
import com.zhijian.schedule.update.ResumableDownload
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
