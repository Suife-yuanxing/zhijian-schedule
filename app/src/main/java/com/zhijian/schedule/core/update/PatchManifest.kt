package com.zhijian.schedule.core.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * 增量清单（patch.json）的序列化形态与 [PatchDescriptor] 的映射。
 *
 * 清单由 scripts/make-apk-patch.py 在发版时生成，字段名与那个脚本一一对应；
 * 解析失败一律返回 null，调用方据此回退全量下载——清单坏了不该拖垮更新。
 */

/** patch.json 的原始形态（字段名与生成脚本保持一致，不做驼峰转换） */
@Serializable
internal data class PatchManifestJson(
    val version: String = "",
    val newSize: Long = 0L,
    val newSha256: String = "",
    val baseSha256: String = "",
    val patchSize: Long = 0L,
    val patchSha256: String = "",
    val segments: List<SegmentJson> = emptyList(),
)

/** 一个区间；[kind] 是 base/patch/zero，[src] 是源偏移 */
@Serializable
internal data class SegmentJson(
    val dest: Long = 0L,
    val length: Long = 0L,
    val kind: String = "",
    val src: Long = 0L,
)

object PatchManifest {
    /** 解析清单文本；任何异常都返回 null（调用方按「没有增量」处理） */
    fun parse(
        text: String,
        patchUrl: String,
    ): PatchDescriptor? =
        runCatching {
            val json = Json { ignoreUnknownKeys = true }
            val raw = json.decodeFromString<PatchManifestJson>(text)
            val segments = raw.segments.map { it.toSegment() }
            if (segments.any { it == null }) return null
            PatchDescriptor(
                version = raw.version,
                newSize = raw.newSize,
                newSha256 = raw.newSha256,
                baseSha256 = raw.baseSha256,
                patchUrl = patchUrl,
                patchSize = raw.patchSize,
                patchSha256 = raw.patchSha256,
                segments = segments.filterNotNull(),
            )
        }.getOrNull()

    /** 单个区间：不认识的 kind 一律判为非法，宁可回退全量也不猜 */
    private fun SegmentJson.toSegment(): PatchSegment? {
        if (length <= 0L) return null
        val source =
            when (kind) {
                KIND_BASE -> SegmentSource.Base(src)
                KIND_PATCH -> SegmentSource.Patch(src)
                KIND_ZERO -> SegmentSource.Zero
                else -> return null
            }
        return PatchSegment(dest, length, source)
    }

    private const val KIND_BASE = "base"
    private const val KIND_PATCH = "patch"
    private const val KIND_ZERO = "zero"
}
