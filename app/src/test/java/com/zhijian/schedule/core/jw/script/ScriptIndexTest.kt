package com.zhijian.schedule.core.jw.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScriptIndexTest {

    private val index = """
        {"indexVersion":1,"scripts":[
          {"id":"qiangzhi-jsxsd","name":"强智教务（通用）","version":2,
           "url":"https://example.com/qiangzhi-jsxsd.js","sha256":"${"a".repeat(64)}",
           "hosts":[],"minAppVersion":"1.0.10","entryHint":"打开课表页"}
        ]}
    """.trimIndent()

    @Test
    fun parsesIndex() {
        val r = ScriptIndexFns.parse(index)
        assertTrue(r.isSuccess)
        val entry = r.getOrThrow().scripts.single()
        assertEquals("qiangzhi-jsxsd", entry.id)
        assertEquals(2, entry.version)
        assertEquals("a".repeat(64), entry.sha256)
    }

    @Test
    fun rejectsInsecureUrlOrBadSha() {
        val bad = index.replace("https://", "http://")
        assertTrue(ScriptIndexFns.parse(bad).isFailure)
        assertTrue(ScriptIndexFns.parse(index.replace("a".repeat(64), "zz")).isFailure)
    }

    @Test
    fun sha256HexMatchesKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ScriptIndexFns.sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun versionComparison() {
        assertTrue(ScriptIndexFns.atLeast("1.0.11", "1.0.10"))
        assertTrue(ScriptIndexFns.atLeast("1.1.0", "1.0.11"))
        assertFalse(ScriptIndexFns.atLeast("1.0.11", "1.1.0"))
        assertFalse(ScriptIndexFns.atLeast("1.0.9", "1.0.10"))
        assertTrue(ScriptIndexFns.atLeast("2.0", "1.99.99"))
    }
}
