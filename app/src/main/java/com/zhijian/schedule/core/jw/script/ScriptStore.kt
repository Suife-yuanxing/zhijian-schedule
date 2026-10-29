package com.zhijian.schedule.core.jw.script

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 用户安装脚本的落盘登记（spec §2）：`<dir>/<id>.js` + `<dir>/scripts.json`。
 * 内置脚本不经此存储，由 [ScriptCatalog] 直接从 assets 读取。
 */
class ScriptStore(
    private val dir: File,
) {
    @Serializable
    data class InstalledScript(
        val id: String,
        val name: String,
        val version: Int,
        val apiVersion: Int,
        val hosts: List<String>,
        val entryHint: String,
        /** local / remote */
        val origin: String,
        val sha256: String = "",
        val enabled: Boolean = true,
        val installedAt: Long,
    )

    sealed interface InstallResult {
        data class Ok(
            val script: InstalledScript,
            val replaced: Boolean,
        ) : InstallResult

        data class Rejected(
            val reason: String,
        ) : InstallResult
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    private val registryFile: File get() = File(dir, "scripts.json")

    @Synchronized
    fun list(): List<InstalledScript> =
        runCatching {
            if (!registryFile.exists()) return emptyList()
            json.decodeFromString<List<InstalledScript>>(registryFile.readText())
        }.getOrDefault(emptyList())

    @Synchronized
    fun read(id: String): String? = File(dir, "$id.js").takeIf { it.exists() }?.readText()

    @Synchronized
    fun install(
        sourceText: String,
        origin: String,
        sha256: String = "",
        onlyIfNewer: Boolean = false,
    ): InstallResult {
        val manifest =
            when (val r = JwScriptManifest.parse(sourceText)) {
                is ManifestResult.Ok -> r.manifest
                is ManifestResult.Invalid -> return InstallResult.Rejected(r.reason)
            }
        val existing = list().firstOrNull { it.id == manifest.id }
        if (onlyIfNewer && existing != null && manifest.version <= existing.version) {
            return InstallResult.Rejected("「${existing.name}」已是最新（v${existing.version}）")
        }
        dir.mkdirs()
        File(dir, "${manifest.id}.js").writeText(sourceText)
        val entry =
            InstalledScript(
                id = manifest.id,
                name = manifest.name,
                version = manifest.version,
                apiVersion = manifest.apiVersion,
                hosts = manifest.hosts,
                entryHint = manifest.entryHint,
                origin = origin,
                sha256 = sha256,
                enabled = existing?.enabled ?: true,
                installedAt = System.currentTimeMillis(),
            )
        save(list().filter { it.id != manifest.id } + entry)
        return InstallResult.Ok(entry, replaced = existing != null)
    }

    @Synchronized
    fun remove(id: String) {
        save(list().filter { it.id != id })
        File(dir, "$id.js").delete()
    }

    @Synchronized
    fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        save(list().map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    private fun save(entries: List<InstalledScript>) {
        dir.mkdirs()
        registryFile.writeText(json.encodeToString(entries))
    }
}
