package com.zhijian.schedule.core.update

/*
 * 增量更新能不能走、值不值得走的**决策层**（纯逻辑，可直接单测）。
 *
 * 真正搬运字节的是 [ApkAssembler]，这里只回答一个问题：
 * 「这次更新该走增量还是全量？」
 *
 * 判断依据全部来自实测（docs/research/2026-11-01-update-distribution-channels.md）：
 * v1.0.15 -> v1.0.16 之间 94.3% 的字节没变，一次常规更新只需传 3.72 MiB 而不是 69 MB。
 * 但增量还有额外成本（清单请求、基线包要留着、拼装要读两个文件），所以省不够多就不值。
 */

/** 这次更新该怎么下 */
sealed interface DownloadStrategy {
    /** 走增量：只需下载 [patchSize] 字节，再用本地基线拼装 */
    data class Delta(
        val descriptor: PatchDescriptor,
        val patchSize: Long,
        /** 全量路径要传的字节数，用于展示「省了多少」 */
        val fullSize: Long,
    ) : DownloadStrategy

    /** 走全量；[reason] 只进日志 */
    data class Full(
        val reason: String,
    ) : DownloadStrategy
}

/** 策略的日志文案；[DownloadStrategy.Delta] 没有「为什么选它」可讲，返回成功说明 */
internal val DownloadStrategy.reasonText: String
    get() =
        when (this) {
            is DownloadStrategy.Delta -> "增量为最优"
            is DownloadStrategy.Full -> reason
        }

object DeltaPolicy {
    /**
     * 增量体积超过全量的这个比例就不值得走。
     * 省不到一半时，多出来的清单往返、基线校验、拼装读盘都不划算。
     */
    internal const val MAX_PATCH_RATIO_PERCENT = 50L

    /**
     * 决定下载策略。
     *
     * @param descriptor 服务端下发（或客户端主动查询）的增量清单；null 表示没有可用增量
     * @param baseApkBytes 本地基线包的大小；null 表示没有基线（首次安装 / 已清理缓存）
     * @param baseSha256 本地基线包的 sha256；与清单里记录的 baseSha256 不一致就不能用
     * @param fullSize 全量安装包大小
     */
    fun decide(
        descriptor: PatchDescriptor?,
        baseApkBytes: Long?,
        baseSha256: String?,
        fullSize: Long?,
    ): DownloadStrategy {
        if (descriptor == null) return DownloadStrategy.Full("没有可用的增量清单")
        if (baseApkBytes == null || baseApkBytes <= 0L) return DownloadStrategy.Full("没有本地基线，只能全量")
        if (baseSha256.isNullOrBlank()) return DownloadStrategy.Full("本地基线缺少摘要，无法确认是不是同一个包")
        val expectedBase =
            ResumableDownload.normalizeDigest(descriptor.baseSha256)
                ?: return DownloadStrategy.Full("增量清单里的基线摘要格式非法")
        if (expectedBase != ResumableDownload.normalizeDigest(baseSha256)) {
            return DownloadStrategy.Full("本地基线与增量清单不是同一个包")
        }
        if (ResumableDownload.normalizeDigest(descriptor.newSha256) == null) {
            // 没有新包摘要就没法做最后那道校验，增量方案的安全性不成立
            return DownloadStrategy.Full("增量清单缺少新包摘要，无法校验")
        }
        if (descriptor.patchSize <= 0L) return DownloadStrategy.Full("增量数据大小无效")
        val full = fullSize?.takeIf { it > 0L } ?: descriptor.newSize
        if (descriptor.patchSize * PERCENT > full * MAX_PATCH_RATIO_PERCENT) {
            return DownloadStrategy.Full(
                "增量占全量 " + (descriptor.patchSize * PERCENT / full.coerceAtLeast(1L)) + "%，不值得走增量",
            )
        }
        return DownloadStrategy.Delta(descriptor, descriptor.patchSize, full)
    }

    /** 百分比换算基数 */
    private const val PERCENT = 100L
}
