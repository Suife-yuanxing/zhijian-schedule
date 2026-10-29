package com.zhijian.schedule.core.jw.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JwScriptManifestTest {
    private val okScript =
        """
        /**
         * PAPER_SCRIPT_MANIFEST
         * {
         *   "id": "qiangzhi-jsxsd",
         *   "name": "强智教务（通用）",
         *   "version": 1,
         *   "apiVersion": 1,
         *   "hosts": ["jw.example.edu.cn"],
         *   "entryHint": "打开学生课表页面"
         * }
         */
        globalThis.__paperScheduleScript = { fetchSchedule: async () => ({ courses: [] }) }
        """.trimIndent()

    @Test
    fun parsesManifestFromHeaderComment() {
        val r = JwScriptManifest.parse(okScript)
        assertTrue(r is ManifestResult.Ok)
        r as ManifestResult.Ok
        assertEquals("qiangzhi-jsxsd", r.manifest.id)
        assertEquals("强智教务（通用）", r.manifest.name)
        assertEquals(listOf("jw.example.edu.cn"), r.manifest.hosts)
        assertEquals("打开学生课表页面", r.manifest.entryHint)
    }

    @Test
    fun fillsDefaults() {
        val r = JwScriptManifest.parse("/** PAPER_SCRIPT_MANIFEST {\"id\":\"a\",\"name\":\"B\"} */ var x = 1")
        assertTrue(r is ManifestResult.Ok)
        assertEquals(1, (r as ManifestResult.Ok).manifest.version)
        assertEquals(1, r.manifest.apiVersion)
        assertEquals(emptyList<String>(), r.manifest.hosts)
    }

    @Test
    fun rejectsMissingMarker() {
        val r = JwScriptManifest.parse("var x = 1")
        assertTrue(r is ManifestResult.Invalid)
        assertTrue((r as ManifestResult.Invalid).reason.contains("PAPER_SCRIPT_MANIFEST"))
    }

    @Test
    fun rejectsBadIdAndFutureApi() {
        assertTrue(
            JwScriptManifest.parse("/** PAPER_SCRIPT_MANIFEST {\"id\":\"A!\",\"name\":\"B\"} */")
                is ManifestResult.Invalid,
        )
        val r = JwScriptManifest.parse("/** PAPER_SCRIPT_MANIFEST {\"id\":\"a\",\"name\":\"B\",\"apiVersion\":99} */")
        assertTrue(r is ManifestResult.Invalid)
        assertTrue((r as ManifestResult.Invalid).reason.contains("更新版本"))
    }

    @Test
    fun normalizesHostsToLowercase() {
        val r =
            JwScriptManifest.parse(
                "/** PAPER_SCRIPT_MANIFEST {\"id\":\"a\",\"name\":\"B\",\"hosts\":[\"JW.Example.EDU.cn\"]} */",
            )
        assertEquals(listOf("jw.example.edu.cn"), (r as ManifestResult.Ok).manifest.hosts)
    }
}
