package com.zhijian.schedule.core.update

import java.io.File

/**
 * 下载完成后的校验与安装交接：校验长度/摘要，通过后把 `.part` 改名为 `.apk` 交给
 * [DownloadState.Ready]（安装器只认这个状态）。
 *
 * 只依赖 [UpdateStore] 与请求本身，不碰下载状态机，因此两条传输路径共用同一份。
 */

/** 全部片齐了才校验；长度不符保留分片（下次只补缺的片），摘要不符必须整份重下 */
internal fun verifyAndClearChunks(
    store: UpdateStore,
    request: DownloadRequest,
    part: File,
    total: Long,
): Notice {
    val expectedBytes = request.sizeBytes?.takeIf { it > 0 } ?: total
    return when (ResumableDownload.verifyFile(part, expectedBytes, request.digest)) {
        VerifyResult.Ok -> {
            val apk = store.apkFile(request.version)
            apk.delete()
            if (part.renameTo(apk)) {
                store.deleteMeta(request.version)
                Notice.Done(DownloadState.Ready(request.version, apk, apk.length()))
            } else {
                store.deleteAll(request.version)
                Notice.Done(DownloadState.Failed(request.version, "安装包保存失败，请重试", canResume = false))
            }
        }
        // 长度不符多半是还有片没写完，保留分片让用户续传
        VerifyResult.LengthMismatch -> Notice.Done(
            DownloadState.Failed(
                request.version,
                "下载未完成（已收到 ${ResumableDownload.formatBytes(part.length())}），可继续下载",
                canResume = true,
            )
        )
        VerifyResult.DigestMismatch -> {
            store.deleteAll(request.version)
            Notice.Done(DownloadState.Failed(request.version, "安装包校验失败，请重新下载", canResume = false))
        }
    }
}
