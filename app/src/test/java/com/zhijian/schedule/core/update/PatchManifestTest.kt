package com.zhijian.schedule.core.update

import com.zhijian.schedule.core.update.PatchManifest
import com.zhijian.schedule.core.update.SegmentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 增量清单（patch.json）解析测试：坏清单必须安静地回退全量，绝不能抛异常拖垮更新 */
class PatchManifestTest {
    private val valid =
        """
        {
          "version": "1.0.17",
          "newSize": 100,
          "newSha256": "sha256:""" + "a".repeat(64) + """",
          "baseSha256": "sha256:""" + "b".repeat(64) + """",
          "patchSize": 30,
          "patchSha256": "sha256:""" + "c".repeat(64) +
            """
            ",
              "segments": [
                { "dest": 0,  "length": 50, "kind": "base",  "src": 100 },
                { "dest": 50, "length": 30, "kind": "patch", "src": 0 },
                { "dest": 80, "length": 20, "kind": "zero",  "src": 0 }
              ]
            }
            """.trimIndent()

    @Test
    fun validManifestIsParsed() {
        val descriptor = PatchManifest.parse(valid, "https://example.com/patch.bin")
        assertTrue(descriptor != null)
        val d = descriptor!!
        assertEquals("1.0.17", d.version)
        assertEquals(100L, d.newSize)
        assertEquals(30L, d.patchSize)
        assertEquals(3, d.segments.size)
        assertEquals(SegmentSource.Base(100L), d.segments[0].source)
        assertEquals(SegmentSource.Patch(0L), d.segments[1].source)
        assertEquals(SegmentSource.Zero, d.segments[2].source)
    }

    @Test
    fun brokenJsonYieldsNullInsteadOfThrowing() {
        assertNull(PatchManifest.parse("{ not json", "u"))
        assertNull(PatchManifest.parse("", "u"))
        assertNull(PatchManifest.parse("[]", "u"))
    }

    @Test
    fun unknownSegmentKindIsRejected() {
        val bad = valid.replace("\"kind\": \"zero\"", "\"kind\": \"magic\"")
        assertNull(PatchManifest.parse(bad, "u"))
    }

    @Test
    fun nonPositiveLengthIsRejected() {
        val bad = valid.replace("\"length\": 20", "\"length\": 0")
        assertNull(PatchManifest.parse(bad, "u"))
    }

    @Test
    fun unknownFieldsAreIgnored() {
        val withExtra = valid.replace("\"version\"", "\"futureField\": 1, \"version\"")
        assertTrue(PatchManifest.parse(withExtra, "u") != null)
    }
}
