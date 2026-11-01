package com.zhijian.schedule.core.update

import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

/*
 * 条目级增量更新的**拼装层**：按服务端下发的「字节区间表」严格重建新 APK。
 *
 * 最关键的一条认知：**客户端不能自己决定条目的摆放位置**。
 * APK 的 v2/v3 签名覆盖整个归档字节流，而 zip 的每个条目在文件里的偏移是写死在 central directory 里的。
 * 如果客户端把变化条目「追加到末尾」而不是放回它在官方新包里的原位置，产出的字节就与官方包不同，
 * 签名立刻失效、装不上——而且整包 sha256 也必然对不上。
 *
 * 所以正确的分工是：
 *   · **服务端**（发版时，手上有新旧两个完整包）算出「新包的每一段字节从哪来」：
 *       要么来自旧包的某个偏移（未变化），要么随接口一起下发（变化），要么是零填充（对齐空洞）。
 *     这就是 [PatchDescriptor] 里的区间表。
 *   · **客户端**只做一件事：严格按表把字节搬到位，一个字节都不多想。
 *
 * 这样产出的文件与官方新包**逐字节相同**，签名自然有效。
 * 最后仍然要过整包 sha256（[DownloadVerification] 那条闸）——它是这个方案唯一的安全边界。
 */

/** 拼装结果 */
sealed interface PatchResult {
    /** 拼装成功；[file] 已落盘（摘要校验由调用方做，那是整个方案的安全边界） */
    data class Ok(
        val file: File,
        val sizeBytes: Long,
    ) : PatchResult

    /** 拼装失败；[reason] 只进日志与诊断 */
    data class Failed(
        val reason: String,
    ) : PatchResult
}

/** 一段字节的来源 */
sealed interface SegmentSource {
    /** 从本机旧包（基线）的 [offset] 处复制 [length] 字节 */
    data class Base(
        val offset: Long,
    ) : SegmentSource

    /** 从随接口下发的增量文件（[PatchDescriptor.patchUrl]）的 [offset] 处复制 [length] 字节 */
    data class Patch(
        val offset: Long,
    ) : SegmentSource

    /** 零填充：对齐空洞或稀疏区间 */
    data object Zero : SegmentSource
}

/** 目标文件里的一段：从 [destOffset] 起写 [length] 字节，内容来自 [source] */
data class PatchSegment(
    val destOffset: Long,
    val length: Long,
    val source: SegmentSource,
)

/**
 * 服务端下发的一次增量更新描述。
 *
 * [segments] 必须**按 destOffset 升序、互不重叠、且能完整覆盖 [newSize]**；
 * 三条件任一不满足就拒绝拼装——宁可回退全量，也不产出可疑的安装包。
 */
data class PatchDescriptor(
    /** 这次增量针对的版本（即新包版本号） */
    val version: String,
    /** 新包总字节数 */
    val newSize: Long,
    /** 新包的 sha256（与 Release 的 digest 一致，用于最终校验） */
    val newSha256: String,
    /** 基线包（旧包）的 sha256：客户端本地的包必须与之一致才敢用 */
    val baseSha256: String,
    /** 增量数据文件的下载地址 */
    val patchUrl: String,
    /** 增量数据文件的大小与摘要 */
    val patchSize: Long,
    val patchSha256: String,
    val segments: List<PatchSegment>,
)

object ApkAssembler {
    /**
     * 按 [descriptor] 把 [baseApk] 与 [patchFile] 拼成新包写入 [output]。
     *
     * 只做区间搬运，不做任何 zip 层面的「理解」——这是刻意的：越少假设越不容易拼坏。
     */
    fun assemble(
        baseApk: File,
        patchFile: File,
        descriptor: PatchDescriptor,
        output: File,
    ): PatchResult {
        if (!baseApk.isFile) return PatchResult.Failed("本地基线不存在")
        if (!patchFile.isFile) return PatchResult.Failed("增量数据不存在")
        if (descriptor.newSize <= 0L) return PatchResult.Failed("新包大小无效")
        val invalid = validateSegments(descriptor.segments, descriptor.newSize)
        if (invalid != null) return PatchResult.Failed(invalid)
        // 基线本身是不是「同一个包」由 DeltaPolicy 用 sha256 判过；这里只负责按表搬运，
        // 具体每一段是否越界在下面的循环里逐个核对（那才是唯一准确的判断点）。

        return runCatching {
            output.delete()
            RandomAccessFile(baseApk, "r").use { base ->
                RandomAccessFile(patchFile, "r").use { patch ->
                    RandomAccessFile(output, "rw").use { out ->
                        out.setLength(descriptor.newSize)
                        val buffer = ByteArray(COPY_BUFFER)
                        for (segment in descriptor.segments) {
                            if (segment.source is SegmentSource.Zero) continue // 稀疏文件本来就是 0
                            val from =
                                when (val source = segment.source) {
                                    is SegmentSource.Base -> source.offset
                                    is SegmentSource.Patch -> source.offset
                                    SegmentSource.Zero -> continue
                                }
                            val reader = if (segment.source is SegmentSource.Base) base else patch
                            if (from < 0L || from + segment.length > reader.length()) {
                                return PatchResult.Failed(
                                    "增量清单越界：偏移 " + from + " + 长度 " + segment.length + " 超出了" +
                                        (if (segment.source is SegmentSource.Base) "基线包" else "增量数据") + "大小",
                                )
                            }
                            var remaining = segment.length
                            var readAt = from
                            var writeAt = segment.destOffset
                            while (remaining > 0L) {
                                val want = minOf(buffer.size.toLong(), remaining).toInt()
                                reader.seek(readAt)
                                val read = reader.read(buffer, 0, want)
                                if (read <= 0) return PatchResult.Failed("读取源数据时提前结束")
                                out.seek(writeAt)
                                out.write(buffer, 0, read)
                                remaining -= read
                                readAt += read
                                writeAt += read
                            }
                        }
                    }
                }
            }
            PatchResult.Ok(output, output.length())
        }.getOrElse { e ->
            output.delete()
            PatchResult.Failed("拼装失败：" + (e.message ?: e::class.simpleName ?: "未知错误"))
        }
    }

    /**
     * 区间表自检：升序、不重叠、无空洞、不越界。
     * 任何一条不满足都返回原因——宁可回退全量，也不产出可能坏掉的安装包。
     */
    internal fun validateSegments(
        segments: List<PatchSegment>,
        newSize: Long,
    ): String? {
        if (segments.isEmpty()) return "增量清单为空"
        var expected = 0L
        for (segment in segments) {
            if (segment.length <= 0L) return "存在长度非正的区间"
            if (segment.destOffset != expected) {
                return "区间不连续：期望从 " + expected + " 开始，实际 " + segment.destOffset
            }
            expected += segment.length
        }
        if (expected != newSize) {
            return "区间总长 " + expected + " 与新包大小 " + newSize + " 不符"
        }
        return null
    }

    /** 拼装结果的整包校验；与 [ResumableDownload.verifyFile] 同一套判据 */
    fun verify(
        file: File,
        descriptor: PatchDescriptor,
    ): Boolean {
        if (!file.isFile || file.length() != descriptor.newSize) return false
        val expected = ResumableDownload.normalizeDigest(descriptor.newSha256) ?: return false
        val actual =
            MessageDigest.getInstance("SHA-256").run {
                file.inputStream().use { input ->
                    val buffer = ByteArray(COPY_BUFFER)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        update(buffer, 0, read)
                    }
                }
                digest().joinToString("") { "%02x".format(it) }
            }
        return actual == expected
    }

    private const val COPY_BUFFER = 64 * 1024
}
