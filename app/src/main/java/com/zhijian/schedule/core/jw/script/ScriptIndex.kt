package com.zhijian.schedule.core.jw.script

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/** 远程脚本仓库清单（spec §3.1）；url 必须 https、sha256 必须 64 位 hex */
@Serializable
data class ScriptIndex(
    val indexVersion: Int = 1,
    val scripts: List<ScriptIndexEntry> = emptyList(),
)

@Serializable
data class ScriptIndexEntry(
    val id: String,
    val name: String,
    val version: Int,
    val url: String,
    val sha256: String,
    val hosts: List<String> = emptyList(),
    val minAppVersion: String = "",
    val entryHint: String = "",
)

object ScriptIndexFns {
    private val json = Json { ignoreUnknownKeys = true }
    private val HEX64 = Regex("""[0-9a-f]{64}""")

    fun parse(raw: String): Result<ScriptIndex> =
        runCatching {
            val index = json.decodeFromString<ScriptIndex>(raw)
            index.scripts.forEach { e ->
                require(e.url.startsWith("https://")) { "${e.id}: 脚本地址必须为 https" }
                require(HEX64.matches(e.sha256)) { "${e.id}: sha256 非法" }
                require(e.version >= 1) { "${e.id}: version 非法" }
            }
            index
        }

    fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** 语义化版本逐段比较：current 是否 >= min；缺失段按 0 */
    fun atLeast(
        current: String,
        min: String,
    ): Boolean {
        val a = current.split(".").map { it.trim().toIntOrNull() ?: 0 }
        val b = min.split(".").map { it.trim().toIntOrNull() ?: 0 }
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return true
    }
}
