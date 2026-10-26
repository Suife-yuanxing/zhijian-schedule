package com.zhijian.schedule.core.update

import android.content.Context
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.json.Json

/**
 * `filesDir/updates/` 的目录布局、元数据读写与占用统计：
 *
 * ```
 * <version>.apk.part   下载中的数据（用 RandomAccessFile 追加）
 * <version>.apk.meta   断点元数据（PartMeta 的 JSON）
 * <version>.apk        校验通过、待安装
 * ```
 *
 * 全部落在内部存储，不需要任何存储权限。只做文件系统与 JSON，不碰网络与状态。
 */
@Singleton
class UpdateStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {

    /** 更新文件目录；首次访问时创建 */
    val dir: File
        get() = File(context.filesDir, DIR).apply { if (!isDirectory) mkdirs() }

    fun partFile(version: String): File = File(dir, version + SUFFIX_PART)

    fun metaFile(version: String): File = File(dir, version + SUFFIX_META)

    fun apkFile(version: String): File = File(dir, version + SUFFIX_APK)

    /** 已下载的分片字节数；没有分片时返回 0 */
    fun partBytes(version: String): Long = partFile(version).let { if (it.isFile) it.length() else 0L }

    /**
     * 读断点元数据。文件缺失、JSON 损坏、版本对不上时返回 null，调用方一律按「没有断点」处理，
     * 也就是不带 Range 重新下 —— 绝不因为一个坏 meta 就卡住用户。
     * v1 的旧元数据（没有 `chunkSize`/`completedChunks`）由 [ResumableDownload.parseMeta] 统一归一化：
     * 缺失的 `chunkSize` 补成默认分片大小（不是 0），v1 遗留的连续 `.part` 会被折算成整片，不会被丢弃。
     */
    fun readMeta(version: String): PartMeta? {
        val file = metaFile(version)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<PartMeta>(file.readText()) }
            .getOrNull()
            ?.let { ResumableDownload.parseMeta(version, it) }
    }

    /** 写断点元数据；写失败只当没有断点，不影响这次下载继续跑 */
    fun writeMeta(meta: PartMeta) {
        runCatching { metaFile(meta.version).writeText(json.encodeToString(meta)) }
    }

    fun deleteMeta(version: String) {
        metaFile(version).delete()
    }

    /**
     * 清掉分片文件但保留元数据：换源重下、或降级到单连接时用。
     * 必须把文件截断成 0 而不是删除 —— 元数据已经被清空，留下任何一个字节都会被当成「已有断点」。
     */
    fun deleteChunks(version: String) {
        val part = partFile(version)
        if (part.isFile) part.outputStream().use { }
    }

    /** 删除某个版本的分片与元数据（待安装的 `.apk` 不动） */
    fun deleteAll(version: String) {
        partFile(version).delete()
        metaFile(version).delete()
    }

    /** `updates/` 当前占用的总字节数（含待安装安装包与断点） */
    fun cacheBytes(): Long = dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    /** 清空 `updates/` 的全部内容（保留目录），返回删除的文件数 */
    fun clearCache(): Int {
        var deleted = 0
        dir.listFiles()?.forEach { if (it.delete()) deleted++ }
        return deleted
    }

    /** 应用启动时清理「版本 <= 当前已安装版本」的残留，返回删除的文件数 */
    fun cleanupOutdated(currentVersion: String): Int {
        var deleted = 0
        dir.listFiles()?.forEach { file ->
            if (file.isFile &&
                ResumableDownload.shouldClean(file.name, currentVersion) &&
                file.delete()
            ) {
                deleted++
            }
        }
        return deleted
    }

    /** 剩余可用空间（字节）。查询失败时返回 Long.MAX_VALUE，避免把查询失败误判成「空间不足」 */
    fun freeSpaceBytes(): Long =
        runCatching { StatFs(dir.absolutePath).availableBytes }.getOrDefault(Long.MAX_VALUE)

    private companion object {
        const val DIR = "updates"
        const val SUFFIX_PART = ".apk.part"
        const val SUFFIX_META = ".apk.meta"
        const val SUFFIX_APK = ".apk"
    }
}
