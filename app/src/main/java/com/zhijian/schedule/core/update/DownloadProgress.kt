package com.zhijian.schedule.core.update

/**
 * 进度与速率：把「分片计划 + 已完成序号 + 各片已写字节」算成 UI 需要的数字，
 * 并给通知栏的刷新做节流。全部是纯逻辑（不持有文件、不碰状态机），可直接用 JUnit 跑。
 */

/**
 * 一次进度快照：把「分片计划 + 已完成序号 + 各片已写字节」聚合成 UI 需要的数字。
 *
 * 进度 = Σ(已完成片的字节数) + Σ(在飞片的已写字节)，其中在飞片的已写字节按该片长度封顶 ——
 * 换源或崩溃后重下某片时，[resetChunk] 会把那片清零，于是进度诚实地**往回跳一点**（设计 §2.3）。
 * 这里不持有文件、不碰状态，只做算术；Task 6 每次要发布进度都是重新构造一个快照。
 */
class ChunkProgress(
    private val plan: ChunkPlan,
    completed: List<Int>,
    written: LongArray,
) {
    private val done: MutableSet<Int> = completed.filter { it in 0 until plan.chunkCount }.toMutableSet()
    private val bytes: LongArray = written.copyOf()

    /** 已完成片 + 在飞片已写字节（按片长封顶），即当前进度 */
    val downloadedBytes: Long
        get() = done.sumOf { plan.bytesOf(it) } +
            (0 until plan.chunkCount)
                .filter { it !in done }
                .sumOf { minOf(bytes.getOrElse(it) { 0L }, plan.bytesOf(it)) }

    /** 还没写完的分片序号（共享待办队列的初始内容） */
    fun neededChunks(): List<Int> = (0 until plan.chunkCount).filter { it !in done }

    /** 还差多少字节（磁盘预检用） */
    fun remainingBytes(): Long = plan.totalBytes - downloadedBytes

    /** 把第 [index] 片退回未完成、已写字节清零（重下该片时调用） */
    fun resetChunk(index: Int) {
        done.remove(index)
        if (index in bytes.indices) bytes[index] = 0L
    }
}

/**
 * 滑动窗口速率估算：只保留窗口内的采样点。
 * 样本不足（不足 2 个点，或两点时间相同）时返回 null —— UI 显示不确定进度，不显示假速度。
 */
class SpeedMeter(private val windowMillis: Long = 5_000L) {

    private val samples = ArrayDeque<Pair<Long, Long>>()

    fun reset() = samples.clear()

    fun record(timestampMillis: Long, totalBytes: Long) {
        samples.addLast(timestampMillis to totalBytes)
        val floor = timestampMillis - windowMillis
        while (samples.size > 2 && samples.first().first < floor) samples.removeFirst()
    }

    fun bytesPerSecond(): Long? {
        if (samples.size < 2) return null
        val (firstAt, firstBytes) = samples.first()
        val (lastAt, lastBytes) = samples.last()
        val elapsed = lastAt - firstAt
        if (elapsed <= 0L) return null
        return ((lastBytes - firstBytes) * 1000L) / elapsed
    }

    /** 剩余秒数（向上取整）；total 为 null 或速度不可用时返回 null */
    fun etaSeconds(downloadedBytes: Long, totalBytes: Long?): Long? {
        val total = totalBytes ?: return null
        val speed = bytesPerSecond()?.takeIf { it > 0 } ?: return null
        val remaining = (total - downloadedBytes).coerceAtLeast(0L)
        return (remaining + speed - 1) / speed
    }
}

/**
 * 通知栏进度节流：距上次刷新 >= [minIntervalMillis] **且**整数百分比变化时才返回 true。
 * [percent] 为 null（响应没有长度信息）时只按时间节流；[force] 用于结束时无条件刷新最终态。
 */
class ProgressThrottle(private val minIntervalMillis: Long = 500L) {

    private var lastAtMillis = Long.MIN_VALUE
    private var lastPercent: Int? = null

    fun shouldRefresh(timestampMillis: Long, percent: Int?, force: Boolean = false): Boolean {
        if (force) {
            lastAtMillis = timestampMillis
            lastPercent = percent
            return true
        }
        if (percent != null && percent == lastPercent) return false
        if (lastAtMillis != Long.MIN_VALUE && timestampMillis - lastAtMillis < minIntervalMillis) return false
        lastAtMillis = timestampMillis
        lastPercent = percent
        return true
    }
}
