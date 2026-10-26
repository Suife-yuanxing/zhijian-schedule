package com.zhijian.schedule.core.update

/** 一个分片：`[start, endInclusive]` 闭区间（HTTP `Range: bytes=start-end` 的语义） */
data class Chunk(val index: Int, val start: Long, val endInclusive: Long)

/**
 * 分片计划：[totalBytes] 未知时根本不会构造出来，[chunks] 按序号升序且最后一片可能更短。
 * 计划只描述「文件被切成什么样」，不描述进度。
 */
data class ChunkPlan(
    val totalBytes: Long,
    val chunkSize: Long,
    val chunks: List<Chunk>,
) {
    val chunkCount: Int get() = chunks.size

    /** 第 [index] 片的字节数（末片更短） */
    fun bytesOf(index: Int): Long {
        val chunk = chunks.getOrNull(index) ?: return 0L
        return chunk.endInclusive - chunk.start + 1L
    }
}

/** 本次下载该从哪里接着写 */
sealed interface ResumePoint {
    /**
     * 不能续传：从第 0 字节重来。
     * [plan] 是按本次已知总长算好的分片计划（总长未知时为 null），调用方不必重复计算。
     */
    data class Fresh(val plan: ChunkPlan?) : ResumePoint

    /** v2 元数据或可升级的裸 `.part`：按 [plan] 的 [completed] 片续传，只补剩下的片；
     * [partial] 是未完成片已写到的分片内偏移，续传从这些偏移接着写，不从头覆盖 */
    data class Chunked(
        val plan: ChunkPlan,
        val completed: List<Int>,
        val partial: Map<Int, Long> = emptyMap(),
    ) : ResumePoint
}
