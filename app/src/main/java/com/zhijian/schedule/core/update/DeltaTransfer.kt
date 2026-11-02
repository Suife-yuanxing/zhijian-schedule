// 本文件名为 DeltaTransfer（= 增量传输这件事），内含结论类型与两个同主题扩展函数；
// detekt 的 MatchingDeclarationName 要求文件名等于唯一的顶层声明名，这里按文件名概括
// 整体语义更准确，故显式放行。
@file:Suppress("MatchingDeclarationName")

package com.zhijian.schedule.core.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile

/*
 * 增量更新的传输路径：下载 patch.bin → 校验 → 用本地基线拼装 → 整包校验。
 *
 * 与全量路径的关系：
 *  · 全量走 [ApkDownloader.download] 的源链 + 分片并行（ChunkedTransfer）；
 *  · 增量走这里——它只需要下几 MB 的一个文件，分片并行的收益远小于复杂度，所以是单连接。
 *
 * **任何一步失败都回退全量**：这是整个方案的安全边界。宁可多下 30 MB，也不装一个来路不明的包。
 */

/** 增量路径的结论 */
internal sealed interface DeltaOutcome {
    /** 拼装并校验通过；[apk] 是可直接安装的文件 */
    data class Ready(
        val apk: File,
        val sizeBytes: Long,
    ) : DeltaOutcome

    /** 走不了增量；[reason] 进日志，调用方转全量下载 */
    data class Fallback(
        val reason: String,
    ) : DeltaOutcome

    /** 用户取消；不转全量，状态落 Paused */
    data object Cancelled : DeltaOutcome
}

/**
 * 下载 patch.bin 并在本地拼装新包。
 *
 * 捕 Exception 是有意的：本函数是「增量 vs 全量」的分叉点，任何一步失败都必须转成
 * Fallback 交给全量下载，绝不能把异常抛给上层中断整个更新流程。异常文本要进 reason
 * 供日志排查，所以不能收窄成具体的 IO 异常。
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun DownloadSession.runDeltaUpdate(
    request: DownloadRequest,
    strategy: DownloadStrategy.Delta,
): DeltaOutcome =
    withContext(Dispatchers.IO) {
        val descriptor = strategy.descriptor
        val baselineVersion =
            store.baselineVersion()
                ?: return@withContext DeltaOutcome.Fallback("本地没有基线包")
        val baselineFile = store.baselineFile(baselineVersion)
        if (!baselineFile.isFile) return@withContext DeltaOutcome.Fallback("基线包文件缺失")

        val patchFile = store.patchFile(request.version)
        val downloadError =
            try {
                downloadPatchFile(descriptor, patchFile)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "增量数据下载失败：" + (e.message ?: e::class.simpleName ?: "IO 错误")
            }
        if (cancelRequested.get()) return@withContext DeltaOutcome.Cancelled
        if (downloadError != null) return@withContext DeltaOutcome.Fallback(downloadError)

        // patch.bin 自身也要按清单里的摘要核对：它是拼装的输入，坏了不该继续
        val patchDigest =
            ResumableDownload.normalizeDigest(descriptor.patchSha256)
                ?: return@withContext DeltaOutcome.Fallback("清单里的增量摘要非法")
        if (UpdateDigest.sha256Of(patchFile) != patchDigest) {
            patchFile.delete()
            return@withContext DeltaOutcome.Fallback("增量数据校验失败")
        }

        val output = store.apkFile(request.version)
        val assembled = ApkAssembler.assemble(baselineFile, patchFile, descriptor, output)
        patchFile.delete()
        when (assembled) {
            is PatchResult.Failed -> DeltaOutcome.Fallback(assembled.reason)
            is PatchResult.Ok -> {
                // 最后一道闸：整包摘要必须与 Release 里的一致。对不上就删掉，绝不安装
                if (ApkAssembler.verify(assembled.file, descriptor)) {
                    DeltaOutcome.Ready(assembled.file, assembled.sizeBytes)
                } else {
                    assembled.file.delete()
                    DeltaOutcome.Fallback("拼装结果校验失败")
                }
            }
        }
    }

/**
 * 单连接下载 patch.bin（支持断点续传）。
 *
 * @return null = 成功；非 null = 失败原因
 */
@Suppress("TooGenericExceptionCaught")
private suspend fun DownloadSession.downloadPatchFile(
    descriptor: PatchDescriptor,
    target: File,
): String? {
    val existing = if (target.isFile) target.length() else 0L
    val resumeFrom = if (existing > 0L && existing <= descriptor.patchSize) existing else 0L
    if (resumeFrom == descriptor.patchSize) return null
    val builder =
        Request
            .Builder()
            .url(descriptor.patchUrl)
            .header("User-Agent", USER_AGENT)
            .header("Accept-Encoding", "identity")
    if (resumeFrom > 0L) builder.header("Range", "bytes=" + resumeFrom + "-")
    val call = client.newCall(builder.build())
    activeCalls.add(call)
    var failure: String? = null
    try {
        call.execute().use { response ->
            if (!response.isSuccessful) {
                failure = "增量下载失败（HTTP " + response.code + "）"
                return@use
            }
            val body = response.body
            if (body == null) {
                failure = "增量下载响应为空"
                return@use
            }
            // 续传被服务端忽略（回了 200 整份）时从 0 重写
            val append = response.code == 206
            RandomAccessFile(target, "rw").use { out ->
                if (!append) out.setLength(0L)
                out.seek(if (append) resumeFrom else 0L)
                val buffer = ByteArray(BUFFER_BYTES)
                body.byteStream().use { input ->
                    while (true) {
                        if (cancelRequested.get()) {
                            failure = "已取消"
                            return@use
                        }
                        val read = input.read(buffer)
                        if (read < 0) break
                        out.write(buffer, 0, read)
                    }
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        failure = "增量下载中断：" + (e.message ?: e::class.simpleName ?: "IO 错误")
    } finally {
        activeCalls.remove(call)
    }
    if (failure != null) return failure
    if (target.length() != descriptor.patchSize) {
        return "增量数据长度不符（" + target.length() + " / " + descriptor.patchSize + "）"
    }
    return null
}
