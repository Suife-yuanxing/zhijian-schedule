package com.zhijian.schedule.core.jw.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScriptStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val script =
        """
        /** PAPER_SCRIPT_MANIFEST {"id":"demo","name":"演示","version":2,"hosts":["a.edu.cn"]} */
        globalThis.__paperScheduleScript = {}
        """.trimIndent()

    @Test
    fun installListsReadsAndPersists() {
        val store = ScriptStore(tmp.root)
        val r = store.install(script, origin = "local")
        assertTrue(r is ScriptStore.InstallResult.Ok)
        assertEquals(listOf("demo"), store.list().map { it.id })
        assertEquals("演示", store.list().first().name)
        assertEquals(2, store.list().first().version)
        assertTrue(store.read("demo")!!.contains("__paperScheduleScript"))
        val reloaded = ScriptStore(tmp.root)
        assertEquals(listOf("demo"), reloaded.list().map { it.id })
    }

    @Test
    fun installSameIdHigherVersionReplaces() {
        val store = ScriptStore(tmp.root)
        store.install(script, "local")
        val newer = script.replace("\"version\":2", "\"version\":3")
        val r = store.install(newer, "remote", onlyIfNewer = true)
        assertTrue(r is ScriptStore.InstallResult.Ok)
        assertEquals(3, store.list().first().version)
        assertTrue(store.read("demo")!!.contains("\"version\":3"))
    }

    @Test
    fun onlyIfNewerRejectsOldOrEqual() {
        val store = ScriptStore(tmp.root)
        store.install(script, "remote")
        val r = store.install(script, "remote", onlyIfNewer = true)
        assertTrue(r is ScriptStore.InstallResult.Rejected)
        assertTrue((r as ScriptStore.InstallResult.Rejected).reason.contains("已是最新"))
    }

    @Test
    fun removeAndSetEnabled() {
        val store = ScriptStore(tmp.root)
        store.install(script, "local")
        store.setEnabled("demo", false)
        assertFalse(store.list().first().enabled)
        store.remove("demo")
        assertEquals(0, store.list().size)
        assertNull(store.read("demo"))
        assertFalse(File(tmp.root, "demo.js").exists())
    }

    @Test
    fun brokenRegistrySelfHeals() {
        tmp.newFile("scripts.json").writeText("{broken")
        val store = ScriptStore(tmp.root)
        assertEquals(0, store.list().size)
        assertTrue(store.install(script, "local") is ScriptStore.InstallResult.Ok)
    }
}
