package com.zhijian.schedule.core.jw.script

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** manifest 解析结果；失败原因均为用户可读中文 */
sealed interface ManifestResult {
    data class Ok(val manifest: JwScriptManifest) : ManifestResult
    data class Invalid(val reason: String) : ManifestResult
}

/**
 * 教务脚本 manifest：脚本头部块注释中的 JSON（marker: PAPER_SCRIPT_MANIFEST）。
 * 详见 docs/superpowers/specs/2026-10-25-jw-script-engine-design.md §1.1。
 */
@Serializable
data class JwScriptManifest(
    val id: String,
    val name: String,
    val version: Int = 1,
    @SerialName("apiVersion") val apiVersion: Int = 1,
    val hosts: List<String> = emptyList(),
    val entryHint: String = "",
) {
    companion object {
        const val SUPPORTED_API_VERSION = 1
        private const val MARKER = "PAPER_SCRIPT_MANIFEST"
        private val json = Json { ignoreUnknownKeys = true }
        private val ID = Regex("""[a-z0-9][a-z0-9-]{0,39}""")
        private val HOST = Regex("""[a-z0-9]([a-z0-9.-]*[a-z0-9])?""")

        /** 解析失败一律给用户可读的中文原因；未知字段忽略（向前兼容） */
        fun parse(source: String): ManifestResult {
            val block = manifestComment(source)
                ?: return ManifestResult.Invalid("脚本缺少 $MARKER 头部说明，无法识别")
            // 多行块注释的每行常带 " * " 装饰，剥掉后才是干净 JSON
            val cleaned = block.lines().joinToString("\n") { line ->
                line.trim().removePrefix("*").trim()
            }
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start < 0 || end <= start) return ManifestResult.Invalid("脚本 manifest 不是合法 JSON")
            val m = runCatching { json.decodeFromString<JwScriptManifest>(cleaned.substring(start, end + 1)) }
                .getOrElse { return ManifestResult.Invalid("脚本 manifest 解析失败：${it.message}") }
            if (!ID.matches(m.id)) return ManifestResult.Invalid("脚本 id 非法（只允许小写字母数字与连字符）：${m.id}")
            val name = m.name.trim()
            if (name.isEmpty() || name.length > 30) return ManifestResult.Invalid("脚本名称须为 1~30 字")
            if (m.version < 1) return ManifestResult.Invalid("脚本版本号非法")
            when {
                m.apiVersion > SUPPORTED_API_VERSION ->
                    return ManifestResult.Invalid("该脚本需要更新版本的 App（apiVersion=${m.apiVersion}）")
                m.apiVersion < 1 -> return ManifestResult.Invalid("脚本 apiVersion 非法")
            }
            val hosts = m.hosts.map { it.lowercase() }
            hosts.forEach { h ->
                if (!HOST.matches(h)) return ManifestResult.Invalid("脚本 hosts 含非法域名：$h")
            }
            return ManifestResult.Ok(m.copy(name = name, hosts = hosts))
        }

        /** 取第一个含 marker 的块注释 */
        private fun manifestComment(source: String): String? {
            var i = 0
            while (i < source.length) {
                val start = source.indexOf("/*", i)
                if (start < 0) return null
                val end = source.indexOf("*/", start + 2)
                val block = if (end < 0) source.substring(start + 2) else source.substring(start + 2, end)
                if (block.contains(MARKER)) return block
                if (end < 0) return null
                i = end + 2
            }
            return null
        }
    }
}
