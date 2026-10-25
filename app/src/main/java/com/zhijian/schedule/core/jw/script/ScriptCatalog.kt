package com.zhijian.schedule.core.jw.script

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** 展示用脚本条目：内置与已安装的合并视图 */
data class ScriptOption(
    val id: String,
    val name: String,
    val version: Int,
    val hosts: List<String>,
    val entryHint: String,
    /** builtin / local / remote */
    val origin: String,
    val enabled: Boolean,
    /** 当前页面 host 是否命中 hosts（hosts 为空的通用脚本不算命中，排序居中） */
    val matched: Boolean,
    /** 是否可运行：已启用 + hosts 为空或命中 */
    val runnable: Boolean,
    val manifest: JwScriptManifest,
) {
    /** 列表里的一行说明 */
    fun subtitle(): String = buildString {
        append("v$version · ")
        append(
            when (origin) {
                "builtin" -> "内置"
                "remote" -> "远程"
                else -> "本地"
            },
        )
        if (hosts.isNotEmpty()) append(" · 仅 ${hosts.joinToString("、")}")
    }
}

/**
 * 脚本目录：内置（assets/jw/scripts/，文件名见 [BUILTIN_FILES]）+ 本地安装。
 * 同 id 时已安装版本优先（用户可覆盖内置脚本）。
 */
@Singleton
class ScriptCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val store: ScriptStore,
) {

    suspend fun all(pageHost: String?): List<ScriptOption> {
        val installed = store.list()
        val options = mutableListOf<ScriptOption>()
        BUILTIN_FILES.forEach { asset ->
            val source = readAsset(asset) ?: return@forEach
            when (val r = JwScriptManifest.parse(source)) {
                is ManifestResult.Ok ->
                    if (installed.none { it.id == r.manifest.id }) {
                        options += r.manifest.toOption(origin = ORIGIN_BUILTIN, enabled = true)
                    }
                is ManifestResult.Invalid -> Unit // 内置损坏直接跳过
            }
        }
        installed.forEach {
            options += JwScriptManifest(
                id = it.id,
                name = it.name,
                version = it.version,
                apiVersion = it.apiVersion,
                hosts = it.hosts,
                entryHint = it.entryHint,
            ).toOption(origin = it.origin, enabled = it.enabled)
        }
        return options
            .map {
                val matched = it.matchedHost(pageHost)
                // hosts 为空 = 通用脚本，任何页面都可运行；matched 仅用于排序与站点徽章
                it.copy(matched = matched, runnable = it.enabled && (it.hosts.isEmpty() || matched))
            }
            .sortedWith(
                compareByDescending<ScriptOption> { it.matched }
                    .thenBy { if (it.hosts.isEmpty()) 1 else 0 }
                    .thenBy { it.name },
            )
    }

    /** 脚本源码：已安装优先（可覆盖内置），否则回内置 assets */
    suspend fun readSource(id: String): String? {
        store.read(id)?.let { return it }
        return BUILTIN_FILES.firstOrNull { it.substringBeforeLast('.') == id }?.let { readAsset(it) }
    }

    private fun JwScriptManifest.toOption(origin: String, enabled: Boolean) = ScriptOption(
        id = id, name = name, version = version, hosts = hosts, entryHint = entryHint,
        origin = origin, enabled = enabled, matched = false, runnable = false, manifest = this,
    )

    private fun ScriptOption.matchedHost(pageHost: String?): Boolean {
        if (pageHost.isNullOrBlank() || hosts.isEmpty()) return false
        return pageHost.lowercase() in hosts
    }

    private fun readAsset(name: String): String? = runCatching {
        context.assets.open("jw/scripts/$name").bufferedReader().use { it.readText() }
    }.getOrNull()

    private companion object {
        const val ORIGIN_BUILTIN = "builtin"
        val BUILTIN_FILES = listOf(
            "qiangzhi-jsxsd.js",
            "zhengfang-new.js",
            "zhengfang-old.js",
            "wisedu-ehall.js",
            "generic-table.js",
        )
    }
}
