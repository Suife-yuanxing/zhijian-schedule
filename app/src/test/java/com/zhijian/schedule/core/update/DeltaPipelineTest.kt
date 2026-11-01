package com.zhijian.schedule.core.update

import com.zhijian.schedule.core.update.DeltaPolicy
import com.zhijian.schedule.core.update.DownloadStrategy
import com.zhijian.schedule.core.update.PatchDescriptor
import com.zhijian.schedule.core.update.PatchManifest
import com.zhijian.schedule.core.update.PatchSegment
import com.zhijian.schedule.core.update.SegmentSource
import com.zhijian.schedule.core.update.UpdateDigest
import com.zhijian.schedule.core.update.reasonText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 增量链路端到端决策测试：从清单解析到策略判定，覆盖「什么时候必须回退全量」 */
class DeltaPipelineTest {
    private val shaA = "sha256:" + "a".repeat(64)
    private val shaB = "sha256:" + "b".repeat(64)

    private fun descriptor(
        newSize: Long = 33_000_000L,
        patchSize: Long = 3_900_000L,
        baseSha: String = shaA,
        newSha: String = shaB,
    ) = PatchDescriptor(
        version = "1.0.17",
        newSize = newSize,
        newSha256 = newSha,
        baseSha256 = baseSha,
        patchUrl = "https://example.com/patch.bin",
        patchSize = patchSize,
        patchSha256 = shaA,
        segments = listOf(PatchSegment(0L, newSize, SegmentSource.Patch(0L))),
    )

    @Test
    fun happyPathProducesDelta() {
        val strategy =
            DeltaPolicy.decide(
                descriptor = descriptor(),
                baseApkBytes = 69_000_000L,
                baseSha256 = shaA,
                fullSize = 33_000_000L,
            )
        assertTrue(strategy is DownloadStrategy.Delta)
        val delta = strategy as DownloadStrategy.Delta
        assertEquals(3_900_000L, delta.patchSize)
        assertEquals(33_000_000L, delta.fullSize)
    }

    @Test
    fun everyRejectionExplainsItself() {
        // 每种回退都必须有可读理由，否则线上排查只能靠猜
        val cases =
            listOf(
                DeltaPolicy.decide(null, 1L, shaA, 1L),
                DeltaPolicy.decide(descriptor(), null, shaA, 1L),
                DeltaPolicy.decide(descriptor(), 1L, null, 1L),
                DeltaPolicy.decide(descriptor(), 1L, shaB, 1L),
                DeltaPolicy.decide(descriptor(patchSize = 30_000_000L), 1L, shaA, 33_000_000L),
            )
        for (rejection in cases) {
            assertTrue(rejection is DownloadStrategy.Full)
            assertTrue((rejection as DownloadStrategy.Full).reason.isNotBlank())
            assertTrue(rejection.reasonText.isNotBlank())
        }
    }

    @Test
    fun deltaRatioUsesFullSizeWhenKnown() {
        // 全量大小已知时比例按它算：3.9/33 约 12%，应通过
        assertTrue(DeltaPolicy.decide(descriptor(), 1L, shaA, 33_000_000L) is DownloadStrategy.Delta)
        // 若声称全量只有 5 MB，则 3.9 MB 的增量占 78%，应被拒
        assertTrue(DeltaPolicy.decide(descriptor(), 1L, shaA, 5_000_000L) is DownloadStrategy.Full)
    }

    @Test
    fun missingFullSizeFallsBackToDescriptorSize() {
        // Release 没给 size 时用清单里的 newSize 当分母，不能因此判成「不值得」
        assertTrue(DeltaPolicy.decide(descriptor(), 1L, shaA, null) is DownloadStrategy.Delta)
    }

    @Test
    fun manifestRoundTripsIntoPolicy() {
        val json =
            """
            {
              "version": "1.0.17",
              "newSize": 33664379,
              "newSha256": "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
              "baseSha256": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "patchSize": 4021300,
              "patchSha256": "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "segments": [{ "dest": 0, "length": 33664379, "kind": "patch", "src": 0 }]
            }
            """.trimIndent()
        val descriptor = PatchManifest.parse(json, "https://example.com/patch.bin")
        assertTrue(descriptor != null)
        val strategy = DeltaPolicy.decide(descriptor, 69_105_445L, shaA, 33_664_379L)
        assertTrue(strategy is DownloadStrategy.Delta)
        assertEquals(4_021_300L, (strategy as DownloadStrategy.Delta).patchSize)
    }

    @Test
    fun brokenManifestCannotProduceDelta() {
        assertNull(PatchManifest.parse("{", "u"))
        val strategy = DeltaPolicy.decide(null, 69_000_000L, shaA, 33_000_000L)
        assertTrue(strategy is DownloadStrategy.Full)
    }

    @Test
    fun digestMatchesKnownValue() {
        // sha256("abc") 是标准测试向量，用它确认十六进制格式化没写错
        val dir = Files.createTempDirectory("digest").toFile()
        try {
            val file = File(dir, "abc.bin").apply { writeBytes("abc".toByteArray()) }
            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                UpdateDigest.sha256Of(file),
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun digestIsLowercaseHex() {
        val dir = Files.createTempDirectory("digest2").toFile()
        try {
            val file = File(dir, "x.bin").apply { writeBytes(ByteArray(1024) { i -> (i % 251).toByte() }) }
            val hex = UpdateDigest.sha256Of(file)
            assertEquals(64, hex.length)
            assertTrue(hex.all { c -> c in '0'..'9' || c in 'a'..'f' })
        } finally {
            dir.deleteRecursively()
        }
    }
}
