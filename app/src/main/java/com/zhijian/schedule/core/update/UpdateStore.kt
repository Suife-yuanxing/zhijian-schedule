package com.zhijian.schedule.core.update

import android.content.Context
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基线包摘要旁注的序列化形态。
 *
 * 带长度与 mtime 是刻意的：基线包是原地覆盖（saveBaseline 用 copyTo(overwrite)），
 * 只记摘要的话，换了包但版本号没变（重装同一版本）时会把旧摘要当成新的，
 * 而增量判据一旦拿错摘要就永远失配——表现是「增量永远走不通」。
 */
@Serializable
internal data class BaselineDigestNote(
    val digest: String,
    val length: Long,
    val modifiedAt: Long,
)

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
class UpdateStore
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val json: Json,
    ) {
        /** 更新文件目录；首次访问时创建 */
        val dir: File
            get() = File(context.filesDir, DIR).apply { if (!isDirectory) mkdirs() }

        fun partFile(version: String): File = File(dir, version + SUFFIX_PART)

        fun metaFile(version: String): File = File(dir, version + SUFFIX_META)

        fun apkFile(version: String): File = File(dir, version + SUFFIX_APK)

        /** 增量数据文件（patch.bin）的暂存位置 */
        fun patchFile(version: String): File = File(dir, version + SUFFIX_PATCH)

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

        // ---------- 增量更新的基线包 ----------

        /**
         * 增量更新用的基线包：把「当前已安装版本」的 APK 留一份在这里。
         *
         * 为什么需要它：条目级增量是「拿旧包的同位置字节 + 少量新字节拼出新包」，
         * 没有与清单里 baseSha256 对得上的旧包，就只能老老实实下全量。
         *
         * 为什么单独放一个目录而不是复用 updates/：updates/ 是下载缓存，用户点「清理下载缓存」
         * 或者应用启动清理旧版本残留时会被清掉；基线包必须活得更久，否则每次清理都会让下一次更新
         * 退化回全量。
         */
        val baselineDir: File
            get() = File(context.filesDir, DIR_BASELINE).apply { if (!isDirectory) mkdirs() }

        fun baselineFile(version: String): File = File(baselineDir, version + SUFFIX_APK)

        fun baselineVersion(): String? =
            baselineDir
                .listFiles()
                ?.filter { it.isFile && it.name.endsWith(SUFFIX_APK) }
                ?.maxByOrNull { it.lastModified() }
                ?.name
                ?.removeSuffix(SUFFIX_APK)

        /**
         * 记录一份基线包。**只保留一份**：换版本后旧基线就没用了（新包的增量是相对新基线算的），
         * 留着只会白占几十 MB。
         *
         * @param source 已安装版本的 APK 来源
         */
        fun saveBaseline(
            version: String,
            source: File,
        ): Boolean =
            runCatching {
                if (!source.isFile) return false
                clearBaseline(except = version)
                val target = baselineFile(version)
                if (target.isFile && target.length() == source.length()) return true
                source.copyTo(target, overwrite = true)
                true
            }.getOrDefault(false)

        /** 清掉除 [except] 之外的全部基线包 */
        fun clearBaseline(except: String? = null) {
            val keep = if (except == null) null else except + SUFFIX_APK
            baselineDir.listFiles()?.forEach { file ->
                if (file.isFile && file.name != keep) file.delete()
            }
        }

        /** 基线包占用的字节数 */
        fun baselineBytes(): Long = baselineDir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

        private companion object {
            const val DIR = "updates"

            /** 基线包目录：与下载缓存分开，清理缓存不会把它带走 */
            const val DIR_BASELINE = "update-baseline"
            const val SUFFIX_PART = ".apk.part"
            const val SUFFIX_META = ".apk.meta"
            const val SUFFIX_APK = ".apk"

            /** 增量数据文件后缀：与 .apk 区分，清理缓存时一并删除 */
            const val SUFFIX_PATCH = ".patch.bin"

            /** 基线包摘要旁注的后缀（与基线包并列放在 update-baseline/ 里） */
            const val SUFFIX_BASELINE_DIGEST = ".digest.json"
        }
    }
