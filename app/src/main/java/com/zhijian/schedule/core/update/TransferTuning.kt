package com.zhijian.schedule.core.update

/*
 * 传输调参的**纯逻辑**：分片大小随包体联动、并发数随实测速率自适应。
 * 不依赖 Android API、不碰网络，可直接用 JUnit 跑。
 *
 * 为什么要有这个文件（2026-11-01 实测依据，见 docs/research/2026-11-01-update-distribution-channels.md）：
 *  · 固定 8 连接是 2026-10-15 那条代理线路的结论；同一台机器今天复测 1/4/8/16 条并发是
 *    503 / 629 / 586 / 582 KB/s —— **甜点在 4**，8 条开始回退。写死常量必然随线路漂移而过时。
 *  · 固定 4 MiB 每片时，69 MiB 的包切成 17 片还能喂饱 8 个 worker；但砍掉 x86 后包只有 31.84 MiB，
 *    17 片变 8 片，8 个 worker 一次就发完，**并发直接失效**。所以分片大小必须跟着包体走。
 */

/** 并发数的下限：低于 2 就不叫并发了，直接走单连接顺序路径更省事 */
internal const val MIN_CONNECTIONS = 2

/**
 * 并发数的硬上限。**不是 8**：实测 8 条已经开始回退（586 KB/s 低于 4 条的 629 KB/s），
 * 继续加只是增加源站限流与连接争抢。16 条实测 582 KB/s，与 8 条无差别。
 */
internal const val MAX_CONNECTIONS = 6

/** 起手并发：实测甜点 4，先按这个发，再按实测速率增删 */
internal const val INITIAL_CONNECTIONS = 4

/** 分片大小的上下限：太小则请求开销压过收益，太大则尾片空洞拉长收尾 */
internal const val MIN_CHUNK_BYTES = 1L * 1024L * 1024L
internal const val MAX_CHUNK_BYTES = 8L * 1024L * 1024L

/** 每个 worker 期望分到几片：低于这个数就会出现「worker 抢完一轮没活干」，并发名存实亡 */
private const val CHUNKS_PER_WORKER = 3

/**
 * 按包体与目标并发算分片大小：让总分片数至少是 [CHUNKS_PER_WORKER] 倍的并发数，
 * 并把结果夹在 [MIN_CHUNK_BYTES] 与 [MAX_CHUNK_BYTES] 之间。
 *
 * 举例：
 *  · 69.1 MB + 4 并发 → 5.7 MiB 每片 → 12 片
 *  · 31.8 MB + 4 并发 → 2.6 MiB 每片 → 12 片
 *  · 8 MB + 4 并发 → 夹到 1 MiB 下限 → 8 片
 *  · 总长未知（<= 0）→ 退回 [ResumableDownload.DEFAULT_CHUNK_BYTES]，调用方本来也会走顺序路径
 */
internal fun chunkSizeFor(
    totalBytes: Long,
    connections: Int = INITIAL_CONNECTIONS,
): Long {
    if (totalBytes <= 0L) return ResumableDownload.DEFAULT_CHUNK_BYTES
    val workers = connections.coerceIn(MIN_CONNECTIONS, MAX_CONNECTIONS)
    val target = workers.toLong() * CHUNKS_PER_WORKER
    val ideal = (totalBytes + target - 1L) / target
    return ideal.coerceIn(MIN_CHUNK_BYTES, MAX_CHUNK_BYTES)
}

/**
 * 自适应并发的下一个取值。纯函数，调用方每攒够一个观测窗口算一次。
 *
 * 规则（与实测曲线对齐）：
 *  · 速率相对上一窗口**明显增长**（>= +12%）且未到上限 → +1；
 *  · 速率**不再增长**（下降，或变化落在噪声带里）→ 冻结在当前档，不再往上加。
 *
 * **单次下载内只增不降**，这是刻意的取舍：
 * 收缩意味着要把已经在飞的连接掐掉，那些分片的下半截白下；而本策略的上限只比初始多
 * [MAX_CONNECTIONS] - [INITIAL_CONNECTIONS] = 2 条，多开两条连接的成本远低于掐断重下的成本。
 * 队列被抢空后 worker 自然收工，不需要任何收缩状态机。下一次下载会从 [INITIAL_CONNECTIONS] 重新开始。
 *
 * @param current 当前并发
 * @param previousBps 上一窗口的字节/秒（null = 还没有上一窗口，不动）
 * @param latestBps 本窗口的字节/秒（null = 样本不足，不动）
 */
internal fun adaptConnections(
    current: Int,
    previousBps: Long?,
    latestBps: Long?,
): Int {
    val base = current.coerceIn(MIN_CONNECTIONS, MAX_CONNECTIONS)
    if (previousBps == null || latestBps == null) return base
    if (previousBps <= 0L || latestBps <= 0L) return base
    val delta = (latestBps - previousBps) * 100L / previousBps
    return if (delta >= GROW_THRESHOLD_PERCENT && base < MAX_CONNECTIONS) base + 1 else base
}

/** 速率增长到这个百分比才加连接 */
internal const val GROW_THRESHOLD_PERCENT = 12L
