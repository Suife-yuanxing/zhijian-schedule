package com.zhijian.schedule.core.update

import com.zhijian.schedule.core.update.ApkAssembler
import com.zhijian.schedule.core.update.DeltaPolicy
import com.zhijian.schedule.core.update.DeltaPolicy.MAX_PATCH_RATIO_PERCENT
import com.zhijian.schedule.core.update.DownloadStrategy
import com.zhijian.schedule.core.update.PatchDescriptor
import com.zhijian.schedule.core.update.PatchResult
import com.zhijian.schedule.core.update.PatchSegment
import com.zhijian.schedule.core.update.SegmentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 增量更新的决策层与拼装层测试。
 *
 * 拼装层用的是**真实字节**（临时目录里造两个小文件），因为它唯一的验收标准就是
 * 「产出的字节与期望完全一致」——用 mock 测不出来。
 */
class DeltaUpdateTest {
    private val sha = "sha256:" + "a".repeat(64)

    private fun descriptor(
        newSize: Long = 1_000L,
        patchSize: Long = 100L,
        baseSha: String = sha,
        newSha: String = sha,
        segments: List<PatchSegment> = emptyList(),
    ) = PatchDescriptor(
        version = "1.0.17",
        newSize = newSize,
        newSha256 = newSha,
        baseSha256 = baseSha,
        patchUrl = "https://example.com/patch.bin",
        patchSize = patchSize,
        patchSha256 = sha,
        segments = segments,
    )

    // ---------- 决策层 ----------

    @Test
    fun noDescriptorMeansFullDownload() {
        assertTrue(DeltaPolicy.decide(null, 1_000L, sha, 1_000L) is DownloadStrategy.Full)
    }

    @Test
    fun missingBaselineMeansFullDownload() {
        val d = descriptor()
        assertTrue(DeltaPolicy.decide(d, null, sha, 1_000L) is DownloadStrategy.Full)
        assertTrue(DeltaPolicy.decide(d, 0L, sha, 1_000L) is DownloadStrategy.Full)
    }

    @Test
    fun baselineDigestMismatchMeansFullDownload() {
        val d = descriptor()
        val other = "sha256:" + "b".repeat(64)
        assertTrue(DeltaPolicy.decide(d, 1_000L, other, 1_000L) is DownloadStrategy.Full)
    }

    @Test
    fun missingBaselineDigestMeansFullDownload() {
        val d = descriptor()
        assertTrue(DeltaPolicy.decide(d, 1_000L, null, 1_000L) is DownloadStrategy.Full)
        assertTrue(DeltaPolicy.decide(d, 1_000L, "", 1_000L) is DownloadStrategy.Full)
    }

    @Test
    fun missingNewDigestMeansFullDownload() {
        val d = descriptor(newSha = "md5:abc")
        assertTrue(DeltaPolicy.decide(d, 1_000L, sha, 1_000L) is DownloadStrategy.Full)
    }

    @Test
    fun patchTooLargeForTheSavingMeansFullDownload() {
        val d = descriptor(newSize = 1_000L, patchSize = 600L)
        assertTrue(DeltaPolicy.decide(d, 1_000L, sha, 1_000L) is DownloadStrategy.Full)
    }

    @Test
    fun reasonablePatchIsAccepted() {
        val d = descriptor(newSize = 33_000_000L, patchSize = 3_900_000L)
        val strategy = DeltaPolicy.decide(d, 33_000_000L, sha, 33_000_000L)
        assertTrue(strategy is DownloadStrategy.Delta)
        assertEquals(3_900_000L, (strategy as DownloadStrategy.Delta).patchSize)
    }

    @Test
    fun ratioThresholdIsSane() {
        assertTrue(MAX_PATCH_RATIO_PERCENT in 20L..80L)
    }

    // ---------- 拼装层：区间表自检 ----------

    @Test
    fun segmentValidationRejectsGaps() {
        val segments =
            listOf(
                PatchSegment(0L, 10L, SegmentSource.Zero),
                PatchSegment(20L, 10L, SegmentSource.Zero),
            )
        assertTrue(ApkAssembler.validateSegments(segments, 30L)!!.contains("不连续"))
    }

    @Test
    fun segmentValidationRejectsWrongTotal() {
        val segments = listOf(PatchSegment(0L, 10L, SegmentSource.Zero))
        assertTrue(ApkAssembler.validateSegments(segments, 30L)!!.contains("不符"))
    }

    @Test
    fun segmentValidationRejectsEmpty() {
        assertEquals("增量清单为空", ApkAssembler.validateSegments(emptyList(), 10L))
    }

    @Test
    fun segmentValidationAcceptsContiguousCoverage() {
        val segments =
            listOf(
                PatchSegment(0L, 10L, SegmentSource.Base(0L)),
                PatchSegment(10L, 5L, SegmentSource.Patch(0L)),
                PatchSegment(15L, 5L, SegmentSource.Zero),
            )
        assertEquals(null, ApkAssembler.validateSegments(segments, 20L))
    }

    // ---------- 拼装层：真实字节 ----------

    private fun tempDir(): File = Files.createTempDirectory("patch-test").toFile()

    @Test
    fun assemblyReproducesExpectedBytesExactly() {
        val dir = tempDir()
        try {
            // 基线：[0..9] = "ABCDEFGHIJ"
            val base = File(dir, "base.bin").apply { writeBytes("ABCDEFGHIJ".toByteArray()) }
            // 增量：只有 [4..7] 要换掉
            val patch = File(dir, "patch.bin").apply { writeBytes("WXYZ".toByteArray()) }
            // 目标：前 4 字节取自基线，中间 4 字节取自增量，后 2 字节继续取自基线
            val segments =
                listOf(
                    PatchSegment(0L, 4L, SegmentSource.Base(0L)),
                    PatchSegment(4L, 4L, SegmentSource.Patch(0L)),
                    PatchSegment(8L, 2L, SegmentSource.Base(8L)),
                )
            val out = File(dir, "out.bin")
            val result = ApkAssembler.assemble(base, patch, descriptor(newSize = 10L, segments = segments), out)
            assertTrue(result is PatchResult.Ok)
            assertEquals("ABCDWXYZIJ", out.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun zeroSegmentsLeaveHolesAtZero() {
        val dir = tempDir()
        try {
            val base = File(dir, "base.bin").apply { writeBytes("ABCDEFGHIJ".toByteArray()) }
            val patch = File(dir, "patch.bin").apply { writeBytes("WXYZ".toByteArray()) }
            val segments =
                listOf(
                    PatchSegment(0L, 2L, SegmentSource.Base(0L)),
                    PatchSegment(2L, 3L, SegmentSource.Zero),
                    PatchSegment(5L, 4L, SegmentSource.Patch(0L)),
                )
            val out = File(dir, "out.bin")
            val result = ApkAssembler.assemble(base, patch, descriptor(newSize = 9L, segments = segments), out)
            assertTrue(result is PatchResult.Ok)
            assertEquals("AB\u0000\u0000\u0000WXYZ", out.readText())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun outOfRangeSegmentIsRejected() {
        val dir = tempDir()
        try {
            val base = File(dir, "base.bin").apply { writeBytes("ABC".toByteArray()) }
            val patch = File(dir, "patch.bin").apply { writeBytes("WXYZ".toByteArray()) }
            // 从基线偏移 100 读 2 字节：越界
            val segments = listOf(PatchSegment(0L, 2L, SegmentSource.Base(100L)))
            val out = File(dir, "out.bin")
            val result = ApkAssembler.assemble(base, patch, descriptor(newSize = 2L, segments = segments), out)
            assertTrue(result is PatchResult.Failed)
            assertTrue((result as PatchResult.Failed).reason.contains("越界"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun missingFilesAreRejected() {
        val dir = tempDir()
        try {
            val base = File(dir, "base.bin").apply { writeBytes("ABC".toByteArray()) }
            val segments = listOf(PatchSegment(0L, 3L, SegmentSource.Base(0L)))
            val result =
                ApkAssembler.assemble(
                    base,
                    File(dir, "missing.bin"),
                    descriptor(newSize = 3L, segments = segments),
                    File(dir, "out.bin"),
                )
            assertTrue(result is PatchResult.Failed)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun verifyChecksRealDigest() {
        val dir = tempDir()
        try {
            val file = File(dir, "x.bin").apply { writeBytes("hello".toByteArray()) }
            // "hello" 的真实 sha256
            val real = "sha256:2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"
            assertTrue(ApkAssembler.verify(file, descriptor(newSize = 5L, newSha = real)))
            assertTrue(!ApkAssembler.verify(file, descriptor(newSize = 5L)))
            assertTrue(!ApkAssembler.verify(file, descriptor(newSize = 6L, newSha = real)))
        } finally {
            dir.deleteRecursively()
        }
    }
}
