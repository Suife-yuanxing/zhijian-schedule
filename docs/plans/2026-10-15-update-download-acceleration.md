# 应用内更新下载 · 加速（并发分片 + 镜像源链）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 v1 的单连接顺序下载升级成「8 路并发分片下载 + 仅当直连彻底跑不通时才用的镜像源链」，在服务端不支持 Range 时自动退回 v1 的单连接路径。

**Architecture:** 分片与源链的判定全部收进纯逻辑（`ResumableDownload.kt` 增分片规划/进度聚合/Range 支撑判据/续传点，新增 `DownloadSourceSet.kt` 管候选构造），零 Android 依赖可 JUnit 直跑；`UpdateStore.kt` 只做 `.meta` v2 的读写与版本兼容判定；`ApkDownloader.kt` 负责真正的调度 —— 一个 `RandomAccessFile("rw")` + 固定 8 个 worker 抢一个共享分片队列，`completedChunks` 是完成度的唯一权威，单连接顺序路径**保留**为降级路径；镜像开关走既有的 DataStore 设置项。

**Tech Stack:** Kotlin 2.2.21 / Jetpack Compose / Hilt / OkHttp 4.12.0 / kotlinx.serialization 1.9.0 / kotlinx.coroutines（`coroutineScope` + `async` + `Channel`）/ JUnit4（无 Robolectric）/ minSdk 26, targetSdk 36

**Spec:** [docs/specs/2026-10-15-update-download-acceleration-design.md](../specs/2026-10-15-update-download-acceleration-design.md)（v1 背景见 [2026-10-15-in-app-update-download-design.md](../specs/2026-10-15-in-app-update-download-design.md)，v1 实现计划见 [2026-10-15-in-app-update-download.md](./2026-10-15-in-app-update-download.md)）

---

## 开工前检查（preflight）

工作区根目录：`D:\课程表`（路径含中文，构建脚本已处理编码问题）。

- [ ] **P1: 确认基线全绿**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --console=plain"
```

预期：`BUILD SUCCESSFUL`，**12 个测试类 / 123 个用例**，0 失败。若不是，先停下来报告，不要继续。

- [ ] **P2: 确认工作区干净且 `docs/` 仍未被跟踪**

```bat
git -C "D:\课程表" status --short
```

预期：只有 `?? docs/`。本计划**所有提交都只 `git add` 明确列出的代码文件，永远不用 `git add -A` / `git add .` / `git add docs`** —— 用户明确要求 `docs/` 留在本地不提交，所以本文件（`docs/plans/2026-10-15-update-download-acceleration.md`）本身也在任何一次 `git add` 之外。

- [ ] **P3: 记住三条构建命令与输出噪音**

```bat
rem 单测（可加 --tests 过滤，类名里没有空格所以不用引号）
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.ResumableDownloadTest --console=plain"

rem 编译主源码（快速语法检查）
cmd /c ".tmp\run-tests.bat :app:compileDebugKotlin --console=plain"

rem 打 debug 包（验证资源与清单合并）
cmd /c ".tmp\run-tests.bat :app:assembleDebug --console=plain"
```

预期：`BUILD SUCCESSFUL`；失败时是 `BUILD FAILED`。
**命令输出的开头几行可能出现 `'Gradle' is not recognized` 或 GBK 乱码** —— 那是 `.tmp\run-tests.bat` 里中文注释/`-Dfile.encoding=GBK` 造成的历史噪音，与本次改动无关。判定只看结尾的 `BUILD SUCCESSFUL` / `BUILD FAILED` 与用例统计。

---

## 文件结构

**新建（主源码）**

| 文件 | 职责 |
|---|---|
| `app/src/main/java/com/zhijian/schedule/update/DownloadSourceSet.kt` | 下载源链的纯逻辑：`UpdateSource`/`DownloadSourceSet`（直连永远第一 + 镜像常量表 + 无 digest 强制禁用镜像 + 前缀替换 + 禁用理由文案） |

**修改（主源码）**

| 文件 | 改动 |
|---|---|
| `app/src/main/java/com/zhijian/schedule/update/ResumableDownload.kt` | 追加分片规划（`Chunk`/`ChunkPlan`/`planChunks`）、进度聚合与续传点（`ChunkProgress`/`ResumePoint`/`resolveResumePoint`/`chunkCountOf`）、降级判据（`RangeSupport`/`decideRangeSupport`）、`.meta` 兼容解析（`parseMeta`）。全部纯逻辑、零 Android 依赖 |
| `app/src/main/java/com/zhijian/schedule/update/UpdateStore.kt` | `readMeta` 改走 `ResumableDownload.parseMeta`；新增 `deleteChunks`（截断分片但不删元数据） |
| `app/src/main/java/com/zhijian/schedule/update/ApkDownloader.kt` | `DownloadRequest` 增 `mirrorsEnabled`、`DownloadState.Running` 增 `sourceLabel`；新增 `downloadSource`（按续传点分流）/`parallelDownload`（8 路调度）/`downloadChunk`/`writeChunk`/`verifyAndClearChunks`；v1 的顺序路径保留为 `downloadSequential` + `handleSequential`，同时是 Range 不可用时的降级路径 |
| `app/src/main/java/com/zhijian/schedule/update/UpdateNotification.kt` | 进度通知文案带来源标签 |
| `app/src/main/java/com/zhijian/schedule/update/ApkDownloadService.kt` | 进度通知传来源标签 |
| `app/src/main/java/com/zhijian/schedule/core/data/prefs/SettingsStore.kt` | 新增 `mirrorsEnabled`（默认 `false`）设置项 |
| `app/src/main/java/com/zhijian/schedule/feature/settings/SettingsViewModel.kt` | `downloadUpdate` 传镜像开关与「无摘要强制禁用」；新增 `setMirrorsEnabled` |
| `app/src/main/java/com/zhijian/schedule/feature/settings/SettingsScreen.kt` | 镜像加速开关 + 「无摘要已禁用镜像」提示 + 下载中的来源标签 |

**新建（测试）**

| 文件 | 覆盖 |
|---|---|
| `app/src/test/java/com/zhijian/schedule/DownloadSourceTest.kt` | 源链候选构造、模板拼 URL、无 digest 时禁用镜像 |

**修改（测试）**

| 文件 | 追加的覆盖 |
|---|---|
| `app/src/test/java/com/zhijian/schedule/ResumableDownloadTest.kt` | 分片规划边界、进度聚合与回退、Range 支撑判据、`.meta` v1↔v2 兼容、v1 `.part` 升级、续传点（33 → 52 例） |

### 与设计文档的 4 处差异（有意为之）

1. **新增纯逻辑文件 `DownloadSourceSet.kt`**：设计 §3 把候选构造写在 `ResumableDownload` 的说明里。单独放一个 ~50 行的文件，`ResumableDownload` 保持「协议与判定」单一职责，源表也能独立替换。
2. **`DownloadRequest.url` 的语义明确为「直连模板」**：设计 §3.1 说从 `browser_download_url` 模板化生成候选。URL 仍是 v1 的原始直连地址，镜像由 `DownloadSourceSet` 在下载器内部替换前缀后拼出。
3. **`.meta` 的兼容判定（`parseMeta`）与 `.part` 的升级规划（`resolveResumePoint`）是纯函数**：设计 §2.1 只写了「`chunkSize == 0` 走 v1 路径」。把这两段从 `UpdateStore` 里挪进 `ResumableDownload`，是为了能用单测覆盖兼容路径（`UpdateStore` 碰 `Context`，按项目约束不写单测）。
4. **镜像开关与来源标签在设置页展示**：设计 §3.5 只要求「开关 + 状态」；来源标签落在既有的 `UpdateStatusLine` 状态行里（而不是新增一行控件），保持卡片视觉密度与 v1 一致。

---

### Task 1: 分片规划的纯逻辑

**Files:**
- Modify: `app/src/test/java/com/zhijian/schedule/ResumableDownloadTest.kt`（在最后一个 `}` 之前追加测试）
- Modify: `app/src/main/java/com/zhijian/schedule/update/ResumableDownload.kt`

- [ ] **Step 1: 写失败的测试**

在 `ResumableDownloadTest.kt` 里、`private fun tempFile` **之前**插入：

```kotlin
    // ---------- 分片规划 ----------

    @Test
    fun planChunksSplitsWithShorterLastChunk() {
        val plan = ResumableDownload.planChunks(10L, chunkSize = 4L)!!
        assertEquals(3, plan.chunkCount)
        assertEquals(
            listOf(
                Chunk(0, 0L, 3L),
                Chunk(1, 4L, 7L),
                Chunk(2, 8L, 9L),
            ),
            plan.chunks,
        )
        assertEquals(4L, plan.bytesOf(0))
        assertEquals(4L, plan.bytesOf(1))
        assertEquals(2L, plan.bytesOf(2))
    }

    @Test
    fun planChunksKeepsSingleChunkWhenTotalIsSmallerThanChunkSize() {
        val plan = ResumableDownload.planChunks(100L, chunkSize = 4096L)!!
        assertEquals(1, plan.chunkCount)
        assertEquals(listOf(Chunk(0, 0L, 99L)), plan.chunks)
    }

    @Test
    fun planChunksExactMultipleHasNoRemainder() {
        val plan = ResumableDownload.planChunks(8L, chunkSize = 4L)!!
        assertEquals(2, plan.chunkCount)
        assertEquals(listOf(Chunk(0, 0L, 3L), Chunk(1, 4L, 7L)), plan.chunks)
        assertEquals(8L, plan.chunks.sumOf { plan.bytesOf(it.index) })
    }

    @Test
    fun planChunksSplitsARealApkIntoTwelve() {
        val plan = ResumableDownload.planChunks(46920382L, chunkSize = 4L * 1024L * 1024L)!!
        assertEquals(12, plan.chunkCount)
        assertEquals(11, plan.chunks.last().index)
        assertEquals(46920382L - 11 * 4L * 1024L * 1024L, plan.bytesOf(11))
    }

    @Test
    fun planChunksRejectsInvalidInput() {
        assertNull(ResumableDownload.planChunks(0L, 4L))
        assertNull(ResumableDownload.planChunks(-1L, 4L))
        assertNull(ResumableDownload.planChunks(100L, 0L))
        assertNull(ResumableDownload.planChunks(100L, -4L))
    }
```

同时在文件顶部的 import 区补上：

```kotlin
import com.zhijian.schedule.core.update.Chunk
```

- [ ] **Step 2: 跑测试确认失败**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.ResumableDownloadTest --console=plain"
```

预期：`BUILD FAILED`，`Unresolved reference: Chunk`（或 `planChunks`）。

- [ ] **Step 3: 写最小实现**

在 `ResumableDownload.kt` 的 `decideRange` 之后、`// ---------- 校验 ----------` 之前插入：

```kotlin
    // ---------- 分片规划 ----------

    /**
     * 把整份文件切成固定大小的分片。`total` 未知 / 非正，或 `chunkSize` 非正时返回 null ——
     * 调用方一律退回单连接顺序路径。共享分片队列的调度策略也在这里定：只有分片数量 > 1 才值得并行。
     */
    fun planChunks(totalBytes: Long, chunkSize: Long = DEFAULT_CHUNK_BYTES): ChunkPlan? {
        if (totalBytes <= 0L || chunkSize <= 0L) return null
        val count = ((totalBytes + chunkSize - 1L) / chunkSize).toInt()
        if (count <= 0) return null
        val chunks = (0 until count).map { index ->
            val start = index.toLong() * chunkSize
            Chunk(index, start, minOf(start + chunkSize, totalBytes) - 1L)
        }
        return ChunkPlan(totalBytes, chunkSize, chunks)
    }
```

在 `object ResumableDownload` 收尾 `}` **之后**、`VerifyResult` 之前追加：

```kotlin
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
```

再在 `object ResumableDownload` 内的 `private const val HEX` 下面补常量：

```kotlin
    /** 分片大小 4 MiB：46 MB 的安装包切成 12 片（实测 8 并发把吞吐提到 5.89 倍） */
    const val DEFAULT_CHUNK_BYTES = 4L * 1024L * 1024L
```

- [ ] **Step 4: 跑测试确认通过**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.ResumableDownloadTest --console=plain"
```

预期：`BUILD SUCCESSFUL`，38 个用例全过（原 33 + 新 5）。

- [ ] **Step 5: 提交**

```bat
git -C "D:\课程表" add app/src/main/java/com/zhijian/schedule/update/ResumableDownload.kt app/src/test/java/com/zhijian/schedule/ResumableDownloadTest.kt
git -C "D:\课程表" commit -m "新增加载分片规划：固定 4MiB 切片与末片取短（纯逻辑 + 单测）"
```

---

### Task 2: 进度聚合与续传点的纯逻辑

**Files:**
- Modify: `app/src/test/java/com/zhijian/schedule/ResumableDownloadTest.kt`（追加测试）
- Modify: `app/src/main/java/com/zhijian/schedule/update/ResumableDownload.kt`（追加实现）

- [ ] **Step 1: 写失败的测试**

在上一批测试的 `planChunksRejectsInvalidInput` 之后、`private fun tempFile` 之前追加：

```kotlin
    // ---------- 进度聚合 ----------

    @Test
    fun progressSumsFinishedChunksAndInFlightBytes() {
        val plan = ResumableDownload.planChunks(10L, chunkSize = 4L)!!
        val progress = ChunkProgress(plan = plan, completed = listOf(0), written = longArrayOf(4L, 1L, 0L))
        assertEquals(5L, progress.downloadedBytes)
    }

    @Test
    fun progressGoesBackwardsWhenAChunkIsRedone() {
        val plan = ResumableDownload.planChunks(10L, chunkSize = 4L)!!
        val progress = ChunkProgress(plan = plan, completed = listOf(0), written = longArrayOf(4L, 3L, 0L))
        assertEquals(7L, progress.downloadedBytes)
        progress.resetChunk(1)
        assertEquals(4L, progress.downloadedBytes)
    }

    @Test
    fun progressNeededChunksSkipsCompletedOnes() {
        val plan = ResumableDownload.planChunks(10L, chunkSize = 4L)!!
        val progress = ChunkProgress(plan = plan, completed = listOf(0, 2), written = longArrayOf(4L, 0L, 2L))
        assertEquals(listOf(1), progress.neededChunks())
    }

    @Test
    fun remainingBytesCountsOnlyWhatIsStillMissing() {
        val plan = ResumableDownload.planChunks(10L, chunkSize = 4L)!!
        val progress = ChunkProgress(plan = plan, completed = listOf(0), written = longArrayOf(4L, 0L, 0L))
        assertEquals(6L, progress.remainingBytes())
    }

    // ---------- 续传点 ----------

    @Test
    fun resumePointReusesPlanWhenSourceAndTotalMatch() {
        val plan = ResumableDownload.planChunks(10L, chunkSize = 4L)!!
        val meta = PartMeta(
            version = "1.0.3",
            url = "https://example.com/a.apk",
            totalBytes = 10L,
            chunkSize = 4L,
            completedChunks = listOf(0),
        )
        val point = ResumableDownload.resolveResumePoint(
            version = "1.0.3",
            url = "https://example.com/a.apk",
            totalBytes = 10L,
            partBytes = 6L,
            rawMeta = meta,
        )
        assertEquals(ResumePoint.Chunked(plan, listOf(0)), point)
    }

    @Test
    fun resumePointUpgradesV1PartFileByReplanningChunks() {
        // v1 写的元数据没有 chunkSize/completedChunks（取默认值 0 与空表），分片大小归一化成 4 MiB。
        // 这里用真实的 4 MiB 分片、只是把总长缩到 16 MiB —— 才是在测生产路径，而不是靠一个假的小分片。
        val total = 16L * 1024L * 1024L
        val meta = PartMeta(
            version = "1.0.3",
            url = "https://example.com/a.apk",
            totalBytes = total,
        )
        val plan = ResumableDownload.planChunks(total)!!
        val point = ResumableDownload.resolveResumePoint(
            version = "1.0.3",
            url = "https://example.com/a.apk",
            totalBytes = total,
            partBytes = 10L * 1024L * 1024L,
            rawMeta = meta,
        )
        // 10 MiB = 前 2 片（各 4 MiB）完整 + 第 3 片半截，只有整片算数，半截那片重下
        assertEquals(ResumePoint.Chunked(plan, listOf(0, 1)), point)
    }

    @Test
    fun resumePointStartsFreshWhenSourceChangedAndKeepsThePlan() {
        val plan = ResumableDownload.planChunks(16L, chunkSize = 4L)!!
        val meta = PartMeta(
            version = "1.0.3",
            url = "https://mirror.example.com/a.apk",
            totalBytes = 16L,
            chunkSize = 4L,
            completedChunks = listOf(0),
        )
        val point = ResumableDownload.resolveResumePoint(
            version = "1.0.3",
            url = "https://example.com/a.apk",
            totalBytes = 16L,
            partBytes = 10L,
            rawMeta = meta,
        )
        // 换了源：已下的字节必须作废，但分片计划要一起带出来，调用方不必再算一遍
        assertEquals(ResumePoint.Fresh(plan), point)
    }

    @Test
    fun resumePointStartsFreshWithoutAnyPartFile() {
        val total = 16L * 1024L * 1024L
        val plan = ResumableDownload.planChunks(total)!!
        assertEquals(
            ResumePoint.Fresh(plan),
            ResumableDownload.resolveResumePoint("1.0.3", "https://example.com/a.apk", total, 0L, null),
        )
        // 总长未知时照样是 Fresh，只是计划为 null（调用方据此走单连接）
        assertEquals(
            ResumePoint.Fresh(null),
            ResumableDownload.resolveResumePoint("1.0.3", "https://example.com/a.apk", null, 0L, null),
        )
    }

    @Test
    fun chunkCountOfConvertsWrittenBytesIntoWholeChunks() {
        assertEquals(0, ResumableDownload.chunkCountOf(0L, 4L))
        assertEquals(0, ResumableDownload.chunkCountOf(3L, 4L))
        assertEquals(1, ResumableDownload.chunkCountOf(4L, 4L))
        assertEquals(1, ResumableDownload.chunkCountOf(7L, 4L))
        assertEquals(2, ResumableDownload.chunkCountOf(10L, 4L))
        assertEquals(2, ResumableDownload.chunkCountOf(11L, 4L))
        assertNull(ResumableDownload.chunkCountOf(10L, 0L))
        assertNull(ResumableDownload.chunkCountOf(10L, -1L))
    }
```

同时在文件顶部的 import 区补上（`ChunkProgress` 与 `ResumePoint` 都被这批测试直接引用）：

```kotlin
import com.zhijian.schedule.core.update.ChunkProgress
import com.zhijian.schedule.core.update.ResumePoint
```

- [ ] **Step 2: 跑测试确认失败**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.ResumableDownloadTest --console=plain"
```

预期：`BUILD FAILED`（测试编译不过），报错含 `Unresolved reference: ChunkProgress`、`Unresolved reference: ResumePoint`、`chunkCountOf`，以及 `PartMeta` 的 `chunkSize` / `completedChunks` 具名参数不存在。

- [ ] **Step 3: 写最小实现**

在 `planChunks` 之后、`// ---------- 校验 ----------` 之前插入：

```kotlin
    // ---------- 续传点 ----------

    /** 已经连着写了 [writtenBytes] 字节时，其中有几个完整的 [chunkSize] 分片（尾部半截不算） */
    fun chunkCountOf(writtenBytes: Long, chunkSize: Long): Int? {
        if (chunkSize <= 0L) return null
        if (writtenBytes <= 0L) return 0
        return (writtenBytes / chunkSize).toInt()
    }

    /**
     * 从磁盘现状推出这次该走哪条路。
     *
     * v1 元数据由 `readMeta` / `parseMeta` 统一归一化（缺的 `chunkSize` 补默认值），所以这里的
     * `meta.chunkSize` 永远已是正值。v1 遗留的 `.part` 是连续前缀，会被 `chunkCountOf(partBytes, chunkSize)`
     * 折算成前 N 个整片（最多重下不到一片），剩余分片照常并行 —— 因此不再保留单连接顺序续传分支。
     *
     * [Fresh] 在两种情况下出现，都带上分片计划方便调用方直接用：
     * - 磁盘上什么都没有 → `plan` 由 `knownTotal` 算出来（`knownTotal` 未知时是 null）；
     * - 有 `.part` 但元数据认不出这个源（`meta.url != url`）→ 必须清空重下。两个源的同名文件
     *   可能内容不同，混着拼必然坏，事后虽然有 digest 兜住，但代价是白下 46 MB。
     */
    fun resolveResumePoint(
        version: String,
        url: String,
        totalBytes: Long?,
        partBytes: Long,
        rawMeta: PartMeta?,
    ): ResumePoint {
        val meta = rawMeta?.takeIf { it.version == version }
        val plan = planChunks(
            totalBytes = totalBytes ?: meta?.totalBytes ?: 0L,
            chunkSize = meta?.chunkSize?.takeIf { it > 0L } ?: DEFAULT_CHUNK_BYTES,
        )
        if (partBytes <= 0L) return ResumePoint.Fresh(plan)

        if (meta == null || meta.url != url || plan == null) return ResumePoint.Fresh(plan)

        // v1 的 `.part` 是连续前缀，可以直接认成前 N 个整片，半截那片重下即可
        val completed = meta.completedChunks
            .ifEmpty { (0 until (chunkCountOf(partBytes, plan.chunkSize) ?: 0)).toList() }
            .filter { it in 0 until plan.chunkCount }
            .distinct()
            .sorted()

        // 分片进度唯一权威在 meta；它一片都没记账、磁盘上却已经有东西时，按 `.part` 重新规划
        return if (completed.isEmpty()) {
            val safe = chunkCountOf(partBytes, plan.chunkSize) ?: 0
            ResumePoint.Chunked(plan, (0 until minOf(safe, plan.chunkCount)).toList())
        } else {
            ResumePoint.Chunked(plan, completed)
        }
    }
```

在 `object ResumableDownload` 收尾 `}` **之后**（`ChunkPlan` 之后）追加：

```kotlin
/** 本次下载该从哪里接着写 */
sealed interface ResumePoint {
    /**
     * 不能续传：从第 0 字节重来。
     * [plan] 是按本次已知总长算好的分片计划（总长未知时为 null），调用方不必重复计算。
     */
    data class Fresh(val plan: ChunkPlan?) : ResumePoint

    /** v2 元数据或可升级的裸 `.part`：按 [plan] 的 [completed] 片续传，只补剩下的片 */
    data class Chunked(val plan: ChunkPlan, val completed: List<Int>) : ResumePoint
}
```

在 `ResumePoint` 之后追加 `ChunkProgress`：

```kotlin
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
```

在 `// ---------- 校验 ----------` 之后追加：

```kotlin
    // ---------- 元数据兼容 ----------

    /**
     * `.meta` 的兼容解析（v1 ↔ v2）：
     * 1. `version` 对不上就当作没有断点；
     * 2. 缺 `chunkSize`/`completedChunks` 的 v1 元数据一律补成默认分片大小 + 空列表 —— **不臆造分片进度**；
     * 3. 新写的 v2 元数据补上分片大小。
     */
    fun parseMeta(version: String, raw: PartMeta?): PartMeta? {
        val meta = raw ?: return null
        if (meta.version != version) return null
        val chunkSize = if (meta.chunkSize > 0L) meta.chunkSize else DEFAULT_CHUNK_BYTES
        return meta.copy(
            chunkSize = chunkSize,
            completedChunks = meta.completedChunks.distinct().sorted(),
        )
    }
```

同时在 `PartMeta` 声明里补上 v2 的两个字段（其余字段不动）：

```kotlin
    /** 分片大小；0 = v1 写的旧元数据，按「单连接顺序续传」处理 */
    val chunkSize: Long = 0L,
    /** 已完成的分片序号；只有这里的序号才算下载完成 */
    val completedChunks: List<Int> = emptyList(),
```

- [ ] **Step 4: 跑测试确认通过**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.ResumableDownloadTest --console=plain"
```

预期：`BUILD SUCCESSFUL`，47 个用例全过（38 + 新 9）。

- [ ] **Step 5: 提交**

```bat
git -C "D:\课程表" add app/src/main/java/com/zhijian/schedule/update/ResumableDownload.kt app/src/test/java/com/zhijian/schedule/ResumableDownloadTest.kt
git -C "D:\课程表" commit -m "新增分片进度聚合、续传点判定与 .meta v1→v2 兼容解析（纯逻辑 + 单测）"
```

---

### Task 3: 降级判据的纯逻辑

**Files:**
- Modify: `app/src/test/java/com/zhijian/schedule/ResumableDownloadTest.kt`（追加测试）
- Modify: `app/src/main/java/com/zhijian/schedule/update/ResumableDownload.kt`（追加实现）

- [ ] **Step 1: 写失败的测试**

在 `chunkCountOfConvertsWrittenBytesIntoWholeChunks` 之后、`private fun tempFile` 之前追加：

```kotlin
    // ---------- 降级判据 ----------

    @Test
    fun partialContentMeansTheServerHonoursRange() {
        assertEquals(RangeSupport.Supported, ResumableDownload.decideRangeSupport(206, 3, 2))
    }

    @Test
    fun okResponseMeansTheServerIgnoresRange() {
        // 服务端忽略 Range：并行分片毫无意义，立刻回到单连接
        assertEquals(RangeSupport.Unsupported, ResumableDownload.decideRangeSupport(200, 3, 0))
    }

    @Test
    fun partialContentStillNeedsTheLastChunkInFlight() {
        // 还差最后一片：不能当成「已经下完」，否则会误进校验
        assertEquals(RangeSupport.Supported, ResumableDownload.decideRangeSupport(206, 3, 1))
    }

    @Test
    fun rangeNotSatisfiableWithAllChunksDoneIsAlreadyComplete() {
        assertEquals(RangeSupport.AlreadyComplete, ResumableDownload.decideRangeSupport(416, 3, 3))
    }

    @Test
    fun serverAndClientErrorsAreNotADowngrade() {
        assertTrue(ResumableDownload.decideRangeSupport(502, 3, 0) is RangeSupport.Fail)
        val fail = ResumableDownload.decideRangeSupport(404, 3, 0) as RangeSupport.Fail
        assertEquals(false, fail.canResume)
    }
```

同时在文件顶部的 import 区补上：

```kotlin
import com.zhijian.schedule.core.update.RangeSupport
```

- [ ] **Step 2: 跑测试确认失败**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.ResumableDownloadTest --console=plain"
```

预期：`BUILD FAILED`，`Unresolved reference: RangeSupport`。

- [ ] **Step 3: 写最小实现**

在 `decideRange` 之后、`// ---------- 分片规划 ----------` 之前插入：

```kotlin
    // ---------- 降级判据 ----------

    /**
     * 分片请求的响应该当成什么。**只有 200 触发降级** —— 那是服务端在说「我不认 Range」，
     * 继续并发就是 8 份重复的整包。[doneChunks] 与 [chunkCount] 用于区分 416 的两种含义。
     * 500 与 IO 异常不降级：那是网络问题，按 v1 的失败+重试处理。
     */
    fun decideRangeSupport(code: Int, chunkCount: Int, doneChunks: Int): RangeSupport = when {
        code == 206 -> RangeSupport.Supported
        code == 200 -> RangeSupport.Unsupported
        code == 416 -> if (doneChunks >= chunkCount) RangeSupport.AlreadyComplete else RangeSupport.Corrupt
        code in 400..499 -> RangeSupport.Fail("下载失败（HTTP $code），请重新下载", canResume = false)
        else -> RangeSupport.Fail("服务端错误（HTTP $code），可重试", canResume = true)
    }
```

在 `object ResumableDownload` 收尾 `}` **之后**（`ResumePoint` 之后）追加：

```kotlin
/** 分片响应的处理结论 */
sealed interface RangeSupport {
    /** 206：服务端支持 Range，继续并行 */
    data object Supported : RangeSupport

    /** 200：服务端忽略了 Range，放弃并行、清掉分片改用单连接顺序路径 */
    data object Unsupported : RangeSupport

    /** 416 且所有分片都已写完：直接进校验 */
    data object AlreadyComplete : RangeSupport

    /** 416 但分片没写完：分片集合损坏，清空重下 */
    data object Corrupt : RangeSupport

    /** 由 [RangeSupport.Fail.canResume] 决定卡片给「重试」还是「重新下载」 */
    data class Fail(val reason: String, val canResume: Boolean) : RangeSupport
}
```

- [ ] **Step 4: 跑测试确认通过**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.ResumableDownloadTest --console=plain"
```

预期：`BUILD SUCCESSFUL`，52 个用例全过（47 + 新 5）。

- [ ] **Step 5: 提交**

```bat
git -C "D:\课程表" add app/src/main/java/com/zhijian/schedule/update/ResumableDownload.kt app/src/test/java/com/zhijian/schedule/ResumableDownloadTest.kt
git -C "D:\课程表" commit -m "新增分片响应的降级判据：200 退回单连接，500/IO 不降级（纯逻辑 + 单测）"
```

---

### Task 4: 源链候选构造的纯逻辑

**Files:**
- Create: `app/src/test/java/com/zhijian/schedule/DownloadSourceTest.kt`
- Create: `app/src/main/java/com/zhijian/schedule/update/DownloadSourceSet.kt`

- [ ] **Step 1: 写失败的测试**

新建 `app/src/test/java/com/zhijian/schedule/DownloadSourceTest.kt`：

```kotlin
package com.zhijian.schedule

import com.zhijian.schedule.core.update.DownloadSourceSet
import com.zhijian.schedule.core.update.UpdateSource
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadSourceTest {

    private val ghUrl =
        "https://github.com/Suife-yuanxing/zhijian-schedule/releases/download/v1.0.3/PaperTimetable-v1.0.3.apk"
    private val mirrors = listOf(UpdateSource("gh-proxy", "镜像 gh-proxy.com", "https://gh-proxy.com/"))

    // ---------- 候选构造 ----------

    @Test
    fun candidatesStartWithDirectAndAppendMirrorsWhenDigestIsValid() {
        val sources = DownloadSourceSet.availableSources(ghUrl, "sha256:" + "a".repeat(64), mirrors)
        assertEquals(
            listOf("direct", "gh-proxy"),
            sources.map { it.id },
        )
        assertEquals(ghUrl, sources.first().url)
        assertEquals("https://gh-proxy.com/$ghUrl", sources.last().url)
    }

    @Test
    fun candidatesDropMirrorsWithoutDigest() {
        val sources = DownloadSourceSet.availableSources(ghUrl, null, mirrors)
        assertEquals(listOf("direct"), sources.map { it.id })
        assertEquals(ghUrl, sources.single().url)
    }

    @Test
    fun candidatesDropMirrorsWhenDigestIsMalformed() {
        assertEquals(listOf("direct"), DownloadSourceSet.availableSources(ghUrl, "", mirrors).map { it.id })
        assertEquals(listOf("direct"), DownloadSourceSet.availableSources(ghUrl, "md5:abc", mirrors).map { it.id })
        assertEquals(
            listOf("direct"),
            DownloadSourceSet.availableSources(ghUrl, "sha256:abc", mirrors).map { it.id },
        )
    }

    @Test
    fun candidatesSurviveAnEmptyMirrorTable() {
        assertEquals(listOf("direct"), DownloadSourceSet.availableSources(ghUrl, null, emptyList()).map { it.id })
    }

    // ---------- 模板 ----------

    @Test
    fun templateKeepsNonGithubUrlsUntouched() {
        val url = "https://cdn.example.com/a.apk"
        assertEquals(url, DownloadSourceSet.withPrefix("https://gh-proxy.com", url))
    }

    @Test
    fun templateHandlesPrefixTrailingSlashAndExtraPath() {
        // 期望值必须跟着上面的 ghUrl 走：这里测的是「前缀尾斜杠归一化」，不是某个固定的示例地址
        assertEquals(
            "https://gh-proxy.com/$ghUrl",
            DownloadSourceSet.withPrefix("https://gh-proxy.com/", ghUrl),
        )
        assertEquals(
            "https://gh-proxy.com/$ghUrl",
            DownloadSourceSet.withPrefix("https://gh-proxy.com", ghUrl),
        )
    }

    @Test
    fun mirrorDisabledCopyExplainsWhy() {
        assertEquals("镜像加速已关闭", DownloadSourceSet.mirrorDisabledReason(digest = "x".repeat(64), mirrorsEnabled = false))
        // 文案以设计 §3.2 为准（完整句「…，已禁用镜像」）
        assertEquals(
            "该版本未提供官方摘要，已禁用镜像",
            DownloadSourceSet.mirrorDisabledReason(digest = null, mirrorsEnabled = true),
        )
        assertEquals(
            "该版本未提供官方摘要，已禁用镜像",
            DownloadSourceSet.mirrorDisabledReason("md5:x", mirrorsEnabled = true),
        )
        assertEquals(null, DownloadSourceSet.mirrorDisabledReason("sha256:" + "a".repeat(64), mirrorsEnabled = true))
    }
}
```

- [ ] **Step 2: 跑测试确认它编译不过（目标类型还不存在）**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.DownloadSourceTest --console=plain"
```

预期：`BUILD FAILED`，`Unresolved reference: DownloadSourceSet`。

- [ ] **Step 3: 写最小实现**

新建 `app/src/main/java/com/zhijian/schedule/update/DownloadSourceSet.kt`：

```kotlin
package com.zhijian.schedule.core.update

/** 一个下载源；[id] 用于判重与日志，[label] 是给用户看的来源标签 */
data class UpdateSource(val id: String, val label: String, val url: String)

/**
 * 下载源链的**纯逻辑**：从 `browser_download_url` 模板化生成候选，不改 UpdateChecker、不加额外 API 调用。
 *
 * 硬性安全约束（设计 §3.2）：**没有可用 digest 时只保留直连**。
 * 判据是 [ResumableDownload.normalizeDigest] 而不是「非空」—— 格式非法的摘要等于没有摘要，
 * 那种情况下走镜像就是「第三方源 + 只校验长度」，必须在代码层禁掉。
 */
object DownloadSourceSet {

    /** 直连源；永远排第一，且它的 url 就是 request 里的原始地址 */
    const val DIRECT_ID = "direct"
    const val DIRECT_LABEL = "直连"

    private const val GITHUB_PREFIX = "https://github.com/"

    /**
     * 镜像常量表。实测 9 个主流加速站里只有 gh-proxy.com 活着（且只有 4.6 KB/s），
     * 所以这里只留一个、默认关闭、只当「直连彻底跑不通」时的最后退路。用户可自行修改本表。
     */
    val MIRRORS: List<UpdateSource> = listOf(
        UpdateSource("gh-proxy", "镜像 gh-proxy.com", "https://gh-proxy.com/"),
    )

    /**
     * 这次下载可以依次尝试的源。来源变了必须清空重下（设计 §3.3），
     * 所以候选里只有「同一个文件的不同前缀」，不会混进别的内容。
     */
    fun availableSources(
        directUrl: String,
        digest: String?,
        mirrors: List<UpdateSource>,
    ): List<UpdateSource> {
        val direct = UpdateSource(DIRECT_ID, DIRECT_LABEL, directUrl)
        if (ResumableDownload.normalizeDigest(digest) == null) return listOf(direct)
        return listOf(direct) + mirrors.map { it.copy(url = withPrefix(it.url, directUrl)) }
    }

    /** 把直连地址挂到镜像前缀后面（`gh-proxy.com/<完整原始 URL>`）；非 GitHub 地址原样返回 */
    fun withPrefix(prefix: String, url: String): String {
        val base = prefix.trimEnd('/')
        if (!url.startsWith(GITHUB_PREFIX)) return url
        return "$base/$url"
    }

    /**
     * 镜像被禁用时给用户看的理由；镜像可用时返回 null（不打扰默认路径）。
     * 开关关闭优先于摘要缺失 —— 先说明「是关掉的」，用户才知道该从哪里打开。
     */
    fun mirrorDisabledReason(digest: String?, mirrorsEnabled: Boolean): String? = when {
        !mirrorsEnabled -> "镜像加速已关闭"
        ResumableDownload.normalizeDigest(digest) == null -> "该版本未提供官方摘要，已禁用镜像"
        else -> null
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --tests com.zhijian.schedule.DownloadSourceTest --console=plain"
```

预期：`BUILD SUCCESSFUL`，7 个用例全过。

- [ ] **Step 5: 提交**

```bat
git -C "D:\课程表" add app/src/main/java/com/zhijian/schedule/update/DownloadSourceSet.kt app/src/test/java/com/zhijian/schedule/DownloadSourceTest.kt
git -C "D:\课程表" commit -m "新增下载源链候选构造：直连优先、无 digest 强制禁用镜像（纯逻辑 + 单测）"
```

---

### Task 5: UpdateStore 的 `.meta` v2 读写与分片清空

**Files:**
- Modify: `app/src/main/java/com/zhijian/schedule/update/UpdateStore.kt`

- [ ] **Step 1: 改成走纯逻辑的兼容解析**

把 `UpdateStore.kt` 的 `readMeta`（41-51 行）替换为：

```kotlin
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
```

- [ ] **Step 2: 新增分片清空（换源与降级都要用）**

在 `deleteMeta` 之后追加：

```kotlin
    /**
     * 清掉分片文件但保留元数据：换源重下、或降级到单连接时用。
     * 必须把文件截断成 0 而不是删除 —— 元数据已经被清空，留下任何一个字节都会被当成「已有断点」。
     */
    fun deleteChunks(version: String) {
        val part = partFile(version)
        if (part.isFile) part.outputStream().use { }
    }
```

- [ ] **Step 3: 编译确认**

```bat
cmd /c ".tmp\run-tests.bat :app:compileDebugKotlin --console=plain"
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 4: 跑全量单测确认没回归**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --console=plain"
```

预期：`BUILD SUCCESSFUL`，13 个测试类 / 149 个用例，0 失败。

- [ ] **Step 5: 提交**

```bat
git -C "D:\课程表" add app/src/main/java/com/zhijian/schedule/update/UpdateStore.kt
git -C "D:\课程表" commit -m "UpdateStore 支持 .meta v2 兼容解析与不删元数据的分片清空"
```

---

### Task 6: 并发分片下载与镜像源链

> 这是本次唯一的大改。`ApkDownloader.kt` 的改动分两步落地：先改数据模型与入口，再换下载体，中间任何一步都编译不过，所以**同一个任务、同一次提交**。

**Files:**
- Modify: `app/src/main/java/com/zhijian/schedule/update/ApkDownloader.kt`

- [ ] **Step 1: 扩数据模型**

把 `DownloadRequest`（26-32 行）替换为：

```kotlin
/** 一次下载请求：设置页构造，前台服务通过 Intent extras 传递 */
data class DownloadRequest(
    val version: String,
    /** 直连地址（GitHub `browser_download_url`），同时是镜像候选的模板 */
    val url: String,
    val sizeBytes: Long?,
    val digest: String?,
    /** 设置页的「镜像加速」开关；关闭时候选只有直连 */
    val mirrorsEnabled: Boolean = false,
)
```

把 `DownloadState.Running`（38-45 行）替换为：

```kotlin
    data class Running(
        val version: String,
        val downloadedBytes: Long,
        val totalBytes: Long?,
        val bytesPerSecond: Long,
        /** null = 样本不足或没有总长，UI 不显示剩余时间 */
        val etaSeconds: Long?,
        /** 当前源标签；直连时为 null（默认路径不打扰），走镜像时显示 */
        val sourceLabel: String? = null,
    ) : DownloadState
```

- [ ] **Step 2: 换掉调用与收尾逻辑**

把 import 区（3-24 行）替换为：

```kotlin
import android.content.Context
import com.zhijian.schedule.BuildConfig
import com.zhijian.schedule.di.DownloadClient
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
```

把 `activeCall`（78-79 行）替换为：

```kotlin
    /** 所有在飞的连接；取消时逐个 cancel。并发调度下会有 8 条同时在飞，所以用并发安全的集合 */
    private val activeCalls: MutableSet<Call> = ConcurrentHashMap.newKeySet()
```

把 `download`（81-100 行）替换为：

```kotlin
    /**
     * 开始或继续下载：按磁盘现状决定「按分片续传」还是「单连接顺序续传」；
     * 有多个候选源时一次尝试只用一个，失败且还有下一个就换源重试一次。
     * 必须在调用方（前台服务）的 scope 中执行。
     */
    suspend fun download(request: DownloadRequest) {
        cancelRequested.set(false)
        val sources = DownloadSourceSet.availableSources(
            directUrl = request.url,
            digest = request.digest,
            mirrors = if (request.mirrorsEnabled) DownloadSourceSet.MIRRORS else emptyList(),
        )
        try {
            val result = withContext(Dispatchers.IO) { runSourceChain(request, sources) }
            _state.value = result
        } catch (e: CancellationException) {
            // 服务被回收时协程会被取消：把状态落回 Paused（分片保留），再向上传播
            _state.value = paused(request.version)
            throw e
        }
    }
```

把 `cancel()`（102-108 行）与 `timeout()`（110-122 行）里对 `activeCall` 的引用改成遍历：

```kotlin
    /** 用户取消或服务被回收：中止网络读取并把状态落到 Paused，分片保留以便续传 */
    fun cancel() {
        cancelRequested.set(true)
        cancelActiveCalls()
        val current = _state.value
        if (current is DownloadState.Running) _state.value = paused(current.version)
    }

    /** 前台服务超时（Android 15+ 的 dataSync 累计上限）：中止并落到 Failed，分片仍然可续传 */
    fun timeout() {
        cancelRequested.set(true)
        cancelActiveCalls()
        val current = _state.value
        if (current is DownloadState.Running) {
            _state.value = DownloadState.Failed(
                current.version,
                "系统结束下载（前台服务超时），可继续下载",
                canResume = true,
            )
        }
    }

    private fun cancelActiveCalls() {
        activeCalls.toList().forEach { runCatching { it.cancel() } }
    }
```

- [ ] **Step 3: 用源链 + 分片调度器替换旧的下载体**

把从 `// ---------- 下载体 ----------`（157 行）到文件末尾 `}`（356 行）**整段**替换为：

```kotlin
    // ---------- 下载体 ----------

    /** 一次下载尝试的说到底怎么了 */
    private sealed interface Notice {
        data class Done(val state: DownloadState) : Notice

        /** 用户取消 / 超时：分片保留 */
        data object Paused : Notice

        /** 这个源没跑通（自己的重试已用尽）；还有下一个候选就换源，已是最后一个则按 [canResume] 收场 */
        data class Failed(val reason: String, val canResume: Boolean) : Notice

        /** 服务端不支持 Range：清空分片，改用单连接顺序路径重下一次 */
        data object Fallback : Notice
    }

    private fun paused(version: String): DownloadState = DownloadState.Paused(
        version = version,
        downloadedBytes = store.partBytes(version),
        totalBytes = store.readMeta(version)?.totalBytes?.takeIf { it > 0 },
    )

    /**
     * 依次尝试候选源。一次下载尝试内只用一个源；该源失败且还有下一个时自动换下一个重试一次
     * （对用户表现为「点一次下载」）。慢 ≠ 失败：直连虽然慢但能下就不切镜像。
     */
    private suspend fun runSourceChain(request: DownloadRequest, sources: List<UpdateSource>): DownloadState {
        for (index in sources.indices) {
            coroutineContext.ensureActive()
            if (cancelRequested.get()) return paused(request.version)
            val source = sources[index]
            when (val notice = downloadSource(request, source)) {
                is Notice.Done -> return notice.state
                Notice.Paused -> return paused(request.version)
                // 这个源失败了
                is Notice.Failed -> {
                    // 还有下一个候选就换源。换源必须清空分片（设计 §3.3）：两个源的同名文件可能
                    // 内容不同，混着拼必然坏，事后只能靠整包 SHA-256 发现，代价是白下 46 MB。
                    if (index != sources.lastIndex) {
                        store.deleteAll(request.version)
                        continue
                    }
                    // 已是最后一个源。分片还在、且 `.meta.url` 就是刚才这个源，所以续传仍然有效
                    // —— 只有真的换过源（sources.size > 1）才因为上面的清空而无物可续，那时才按
                    // 设计 §3.4 收场。镜像默认关闭时 sources 只有直连，这里的行为与 v1 完全一致。
                    if (sources.size > 1) {
                        store.deleteAll(request.version)
                        return DownloadState.Failed(request.version, "所有下载源都失败", canResume = false)
                    }
                    return DownloadState.Failed(request.version, notice.reason, notice.canResume)
                }
                // 服务端不认 Range（设计 §2.4）：分片集合已作废，清掉元数据改用单连接重下一次
                Notice.Fallback -> return downloadSequential(request, source, fromScratch = true)
                    .settle(request.version)
            }
        }
        // availableSources 至少返回直连一条，所以这里只是防御性收尾
        return DownloadState.Failed(request.version, "所有下载源都失败", canResume = false)
    }

    /**
     * 把「单个源的尝试结论」收敛成最终状态。保留 [Notice.Failed] 的 `canResume`，不一律压成 false
     * —— 单源且分片还在时，用户点「重试」是能接着下的。
     * 降级路径（[downloadSequential]）不可能再返回 [Notice.Fallback]（它进去就是要顺序下完），
     * 真出现只可能是逻辑写错，这里按不可续传的失败收场，不再递归降级。
     */
    private fun Notice.settle(version: String): DownloadState = when (this) {
        is Notice.Done -> state
        Notice.Paused -> paused(version)
        is Notice.Failed -> DownloadState.Failed(version, reason, canResume)
        Notice.Fallback -> DownloadState.Failed(version, "服务端不支持分片下载，请重试", canResume = false)
    }

    /** 用单个源下载：按磁盘现状选「分片续传」或「顺序续传」，分片进度唯一权威是 `.meta.completedChunks` */
    private suspend fun downloadSource(request: DownloadRequest, source: UpdateSource): Notice {
        val meta = store.readMeta(request.version)
        val partBytes = store.partBytes(request.version)
        val knownTotal = request.sizeBytes?.takeIf { it > 0 } ?: meta?.totalBytes?.takeIf { it > 0 }
        val resume = ResumableDownload.resolveResumePoint(
            version = request.version,
            url = source.url,
            totalBytes = knownTotal,
            partBytes = partBytes,
            rawMeta = meta,
        )

        // 续传要的只有「缺的那部分」，不再要求一整份的空闲空间（设计 §2.6）
        val missing = when (resume) {
            is ResumePoint.Chunked -> resume.plan.totalBytes - resume.completed.sumOf { resume.plan.bytesOf(it) }
            is ResumePoint.Fresh -> knownTotal ?: 0L
        }
        if (missing > 0L && store.freeSpaceBytes() < missing) {
            return Notice.Done(
                DownloadState.Failed(
                    request.version,
                    "存储空间不足，还需 ${ResumableDownload.formatBytes(missing)}",
                    canResume = false,
                )
            )
        }

        when (resume) {
            is ResumePoint.Fresh -> {
                // 来源变了或压根没有断点：清掉已下的字节与元数据，两个源的同名文件不能混着拼
                store.deleteAll(request.version)
                val plan = resume.plan ?: return downloadSequential(request, source, fromScratch = true)
                // 只有一个分片：多开连接只会被服务端限速，不如走单连接
                if (plan.chunkCount <= 1) return downloadSequential(request, source, fromScratch = true)
                // 先把「这次是按哪个源、哪个分片大小在拼」锚进 `.meta`：中途被杀时下次才对得上。
                // 先建空文件再写元数据、且 etag/lastModified 一律留空 —— 两个细节都是为了「已经
                // 没有断点了、这次真的要从 0 重来」：旧元数据里那对校验器属于上一次会话，照抄会让
                // If-Range 拿过期校验器去问服务端。真正的校验器由第一片写完时从响应头捕获（见 writeChunk）。
                store.deleteChunks(request.version)
                store.writeMeta(
                    PartMeta(
                        version = request.version,
                        url = source.url,
                        totalBytes = plan.totalBytes,
                        digest = request.digest,
                        chunkSize = plan.chunkSize,
                    )
                )
                parallelDownload(request, source, plan, emptyList())
            }
            is ResumePoint.Chunked -> {
                val plan = resume.plan
                // 续传这一支保留旧元数据里的校验器：`.meta.url` 与当前源一致，说明这确实还是同一份文件
                store.writeMeta(
                    PartMeta(
                        version = request.version,
                        url = source.url,
                        etag = meta?.etag,
                        lastModified = meta?.lastModified,
                        totalBytes = plan.totalBytes,
                        digest = request.digest,
                        chunkSize = plan.chunkSize,
                        completedChunks = resume.completed,
                    )
                )
                parallelDownload(request, source, plan, resume.completed)
            }
        }
    }

    /** 分片并行调度的结果 */
    private sealed interface ChunkOutcome {
        /** 队列被抢空 */
        data object Completed : ChunkOutcome

        /** 某个 worker 发现服务端忽略 Range */
        data object Unsupported : ChunkOutcome

        /** 用户取消 / 超时 */
        data object Cancelled : ChunkOutcome

        data class Error(val reason: String) : ChunkOutcome
    }

    /**
     * `min(8, chunkCount)` 个 worker 抢同一个分片队列，各写自己那一段。**不开分片文件、不需要事后拼接**：
     * 所有 worker 共用同一个 `RandomAccessFile`，`seek` 到各自分片起点后顺序写。
     * 每片写完的顺序是「`fd.sync()` → 记账 → 重写 `.meta`」：先落盘再记账，崩溃后才不会把没写完的片当成完成片。
     */
    private suspend fun parallelDownload(
        request: DownloadRequest,
        source: UpdateSource,
        plan: ChunkPlan,
        completed: List<Int>,
    ): Notice {
        val part = store.partFile(request.version)
        val done = completed.toMutableList()
        val written = LongArray(plan.chunkCount)
        // 续传时已完成的片按满长度记账，进度才不会从更小的数字开始
        completed.forEach { index -> written[index] = plan.bytesOf(index) }
        val lock = Any()
        val meter = SpeedMeter()
        val initial = ChunkProgress(plan, done.sorted(), written).downloadedBytes
        meter.record(System.currentTimeMillis(), initial)
        publish(request, source, initial, plan.totalBytes, meter)

        val outcome = coroutineScope {
            RandomAccessFile(part, "rw").use { file ->
                if (completed.isEmpty()) file.setLength(0L)
                val queue = kotlinx.coroutines.channels.Channel<Int>(
                    kotlinx.coroutines.channels.Channel.UNLIMITED
                )
                (0 until plan.chunkCount).filter { it !in completed }.forEach { queue.trySend(it) }
                queue.close()

                val workers = List(minOf(MAX_CONNECTIONS, plan.chunkCount)) {
                    async(Dispatchers.IO) {
                        while (true) {
                            coroutineContext.ensureActive()
                            if (cancelRequested.get()) return@async ChunkOutcome.Cancelled
                            val index = queue.tryReceive().getOrNull() ?: return@async ChunkOutcome.Completed
                            val result = downloadChunk(request, source, plan, index, file, written, plan.chunkCount, done, lock, meter)
                            // 某片发现服务端不认 Range 就没必要把剩下的片也拉下来，立刻收手
                            if (result !is ChunkOutcome.Completed) return@async result
                        }
                    }
                }
                // awaitAll 让所有 worker 收尾；只要有一个非 Completed 就取它
                val results = workers.awaitAll()
                results.firstOrNull { it !is ChunkOutcome.Completed } ?: ChunkOutcome.Completed
            }
        }

        return when (outcome) {
            ChunkOutcome.Completed -> verifyAndClearChunks(request, part, plan.totalBytes)
            ChunkOutcome.Unsupported -> Notice.Fallback
            ChunkOutcome.Cancelled -> Notice.Paused
            // 片内重试 3 次仍失败（设计 §2.2）：这是「这个源没跑通」，交给源链决定是换下一个源
            // 还是收场。已完成的片留在磁盘上，单源时 canResume = true 才有东西可续。
            is ChunkOutcome.Error -> Notice.Failed(outcome.reason, canResume = true)
        }
    }

    /** 下载一个分片：200 触发降级、416 触发完成/损坏、其余失败重试 3 次（线性退避） */
    private suspend fun downloadChunk(
        request: DownloadRequest,
        source: UpdateSource,
        plan: ChunkPlan,
        index: Int,
        file: RandomAccessFile,
        written: LongArray,
        chunkCount: Int,
        done: MutableList<Int>,
        lock: Any,
        meter: SpeedMeter,
    ): ChunkOutcome {
        val chunk = plan.chunks[index]
        var attempt = 0
        while (attempt < MAX_CHUNK_ATTEMPTS) {
            coroutineContext.ensureActive()
            if (cancelRequested.get()) return ChunkOutcome.Cancelled
            attempt++

            val etag = store.readMeta(request.version)?.etag
            val call = client.newCall(chunkRequest(source, chunk, etag))
            activeCalls.add(call)
            val response = try {
                call.execute()
            } catch (e: IOException) {
                activeCalls.remove(call)
                if (cancelRequested.get()) return ChunkOutcome.Cancelled
                if (attempt >= MAX_CHUNK_ATTEMPTS) {
                    return ChunkOutcome.Error("网络中断（${e.message ?: "IO 错误"}），可继续下载")
                }
                delay(RETRY_BASE_MILLIS * attempt)
                continue
            }

            val settled = try {
                response.use {
                    applyChunkResponse(request, source, plan, index, file, it, written, chunkCount, done, lock, meter)
                }
            } finally {
                activeCalls.remove(call)
            }

            when (settled) {
                Stop.None -> return ChunkOutcome.Completed
                Stop.Unsupported -> return ChunkOutcome.Unsupported
                Stop.Cancelled -> return ChunkOutcome.Cancelled
                // 416 说明服务端认为整份都在了，直接交校验去判断
                is Stop.AlreadyComplete -> return ChunkOutcome.Completed
                is Stop.Corrupt -> return ChunkOutcome.Error(settled.reason)
                is Stop.Fail -> return ChunkOutcome.Error(settled.reason)
                is Stop.Retry -> if (attempt >= MAX_CHUNK_ATTEMPTS) {
                    return ChunkOutcome.Error("下载失败（HTTP ${settled.code}），可继续下载")
                }
            }
            delay(RETRY_BASE_MILLIS * attempt)
        }
        return ChunkOutcome.Error("分片下载失败，可继续下载")
    }

    /** 分片请求；`Accept-Encoding: identity` 不能少，否则 Content-Length 是压缩后大小，长度校验会全错 */
    private fun chunkRequest(source: UpdateSource, chunk: Chunk, etag: String?): Request {
        val builder = Request.Builder()
            .url(source.url)
            .header("Accept-Encoding", "identity")
            .header("User-Agent", USER_AGENT)
            .header("Range", "bytes=${chunk.start}-${chunk.endInclusive}")
        // 交给服务端判断「资源没变才给 206」，能挡住资产被替换导致的新旧分片拼接损坏
        etag?.let { builder.header("If-Range", it) }
        return builder.build()
    }

    /** 单次分片响应的处理结论 */
    private sealed interface Stop {
        /** 这一片写完了 */
        data object None : Stop

        /** 服务端忽略 Range（200） */
        data object Unsupported : Stop

        /** 用户取消 / 超时 */
        data object Cancelled : Stop

        /** 416：服务端认为整份文件都在了，交校验判断 */
        data class AlreadyComplete(val total: Long) : Stop

        /** 分片集合坏了：起点对不上或没写完的 416 */
        data class Corrupt(val reason: String) : Stop

        /** 不可重试的失败 */
        data class Fail(val reason: String) : Stop

        /** 可重试的状态码（5xx / 429 / 响应无 body） */
        data class Retry(val code: Int) : Stop
    }

    private fun applyChunkResponse(
        request: DownloadRequest,
        source: UpdateSource,
        plan: ChunkPlan,
        index: Int,
        file: RandomAccessFile,
        response: Response,
        written: LongArray,
        chunkCount: Int,
        done: MutableList<Int>,
        lock: Any,
        meter: SpeedMeter,
    ): Stop {
        val contentRange = ResumableDownload.parseContentRange(response.header("Content-Range"))
        val total = contentRange?.total ?: plan.totalBytes
        val support = ResumableDownload.decideRangeSupport(response.code, chunkCount, done.size)
        return when (support) {
            RangeSupport.Unsupported -> Stop.Unsupported
            RangeSupport.AlreadyComplete -> Stop.AlreadyComplete(total)
            RangeSupport.Corrupt -> Stop.Corrupt("分片集合损坏，请重新下载")
            // 500 与 IO 不降级：那是网络问题，重试即可；4xx 是没救了，直接换下一个源
            is RangeSupport.Fail -> if (support.canResume) {
                Stop.Retry(response.code)
            } else {
                Stop.Fail(support.reason)
            }
            RangeSupport.Supported -> {
                // 206 的起点必须就是这一片的起点，否则拼出来的 APK 是坏的
                if (contentRange != null && contentRange.start != plan.chunks[index].start) {
                    return Stop.Corrupt("分片起点不匹配，请重新下载")
                }
                val body = response.body ?: return Stop.Retry(response.code)
                body.byteStream().use { input ->
                    writeChunk(request, source, plan, index, file, input, response, written, done, lock, meter, total)
                }
            }
        }
    }

    /** 把这一片写进 `.part`；每 64 KiB 刷新一次进度。片没读完（连接被掐）当作这一片失败重试 */
    private fun writeChunk(
        request: DownloadRequest,
        source: UpdateSource,
        plan: ChunkPlan,
        index: Int,
        file: RandomAccessFile,
        input: java.io.InputStream,
        response: Response,
        written: LongArray,
        done: MutableList<Int>,
        lock: Any,
        meter: SpeedMeter,
        total: Long,
    ): Stop {
        val chunk = plan.chunks[index]
        val buffer = ByteArray(BUFFER_BYTES)
        var offset = chunk.start
        var sinceUpdate = 0L
        while (offset <= chunk.endInclusive) {
            if (cancelRequested.get()) return Stop.Cancelled
            val want = minOf(BUFFER_BYTES.toLong(), chunk.endInclusive - offset + 1L).toInt()
            val read = input.read(buffer, 0, want)
            if (read < 0) break
            if (read == 0) continue
            // seek + write 必须原子：8 个 worker 共用同一个 RandomAccessFile，文件指针是共享状态。
            // 不锁的话「A 先 seek、被抢占、B 又 seek、A 再 write」会把 A 的数据写到 B 的偏移上，
            // 拼出来的 APK 静默损坏（长度对得上，只有整包 SHA-256 能发现）。锁与进度记账共用同一把。
            synchronized(lock) {
                file.seek(offset)
                file.write(buffer, 0, read)
            }
            offset += read
            sinceUpdate += read
            if (sinceUpdate >= PROGRESS_STEP_BYTES) {
                sinceUpdate = 0L
                synchronized(lock) {
                    written[index] = offset - chunk.start
                    val now = ChunkProgress(plan, done.sorted(), written).downloadedBytes
                    meter.record(System.currentTimeMillis(), now)
                    publish(request, source, now, total, meter)
                }
            }
        }
        // 片没读完（连接被掐）：交给上层重试，这一片下次会被整片覆盖
        if (offset <= chunk.endInclusive) return Stop.Retry(0)

        file.fd.sync()
        synchronized(lock) {
            written[index] = plan.bytesOf(index)
            if (index !in done) done.add(index)
            // 校验器：先从响应头捕获（第一片写完时把真正的 ETag/Last-Modified 记下来，
            // 后续片的 If-Range 才拿得到有效值），再退回元数据里已有的值
            val etag = response.header("ETag") ?: store.readMeta(request.version)?.etag
            val lastModified = response.header("Last-Modified") ?: store.readMeta(request.version)?.lastModified
            store.writeMeta(
                PartMeta(
                    version = request.version,
                    url = source.url,
                    etag = etag,
                    lastModified = lastModified,
                    totalBytes = total,
                    digest = request.digest,
                    chunkSize = plan.chunkSize,
                    completedChunks = done.sorted(),
                )
            )
            val now = ChunkProgress(plan, done.sorted(), written).downloadedBytes
            meter.record(System.currentTimeMillis(), now)
            publish(request, source, now, total, meter)
        }
        return Stop.None
    }

    /** 全部片齐了才校验；长度不符保留分片（下次只补缺的片），摘要不符必须整份重下 */
    private fun verifyAndClearChunks(request: DownloadRequest, part: File, total: Long): Notice {
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

    // ---------- 单连接顺序路径（v1，保留为 Range 不可用时的降级路径） ----------

    /**
     * 服务端不支持 Range，或只有一两个分片时：回到 v1 已验证过的单连接顺序下载。
     * [fromScratch] 为 true 时必须清掉元数据，否则顺序路径会读着指向镜像的 `.meta` 判续传，
     * 把带 Range 的请求打在镜像上；为 false（v1 元数据 + 同源前缀）时保留元数据，让顺序路径正常追加。
     */
    private suspend fun downloadSequential(
        request: DownloadRequest,
        source: UpdateSource,
        fromScratch: Boolean,
    ): Notice {
        if (fromScratch) store.deleteMeta(request.version)
        val part = store.partFile(request.version)
        val builder = Request.Builder()
            .url(source.url)
            .header("Accept-Encoding", "identity")
            .header("User-Agent", USER_AGENT)
        // 顺序续传时带上 Range/If-Range，语义与 v1 完全一致
        if (!fromScratch) {
            val meta = store.readMeta(request.version)
            val partBytes = store.partBytes(request.version)
            if (partBytes > 0L && meta != null && meta.url == source.url) {
                builder.header("Range", "bytes=$partBytes-")
                meta.etag?.let { builder.header("If-Range", it) }
            }
        }
        val call = client.newCall(builder.build())
        activeCalls.add(call)
        val response = try {
            call.execute()
        } catch (e: IOException) {
            activeCalls.remove(call)
            return if (cancelRequested.get()) {
                Notice.Paused
            } else {
                Notice.Done(DownloadState.Failed(request.version, "网络中断（${e.message ?: "IO 错误"}）", canResume = true))
            }
        }
        return try {
            response.use { handleSequential(request, source, it, part, fromScratch) }
        } finally {
            activeCalls.remove(call)
        }
    }

    /** v1 的下载体：从偏移处顺序写到底，中途被杀靠 `.meta` 的 totalBytes 兜进度分母 */
    private fun handleSequential(
        request: DownloadRequest,
        source: UpdateSource,
        response: Response,
        part: File,
        fromScratch: Boolean,
    ): Notice {
        if (response.code !in 200..299) {
            return Notice.Done(
                DownloadState.Failed(
                    request.version,
                    "下载失败（HTTP ${response.code}），请重试",
                    canResume = response.code in 500..599,
                )
            )
        }
        val contentRange = ResumableDownload.parseContentRange(response.header("Content-Range"))
        val partBytes = if (fromScratch) 0L else store.partBytes(request.version)
        val appendFrom = when {
            fromScratch -> 0L
            // 服务端不认 Range、或 If-Range 判定资源已变：这次响应是整份，从头覆盖
            response.code == 200 -> 0L
            contentRange != null && contentRange.start == partBytes -> partBytes
            else -> 0L
        }
        val body = response.body
            ?: return Notice.Done(DownloadState.Failed(request.version, "响应无内容，请重试", canResume = true))
        val total = ResumableDownload.resolveTotalBytes(body.contentLength(), contentRange, request.sizeBytes)

        // 先落元数据再写数据：中途被杀也能靠 .meta 记住进度分母
        store.writeMeta(
            PartMeta(
                version = request.version,
                url = source.url,
                etag = response.header("ETag"),
                lastModified = response.header("Last-Modified"),
                totalBytes = total ?: 0L,
                digest = request.digest,
            )
        )

        val meter = SpeedMeter()
        var written = appendFrom
        meter.record(System.currentTimeMillis(), written)
        publish(request, source, written, total, meter)

        RandomAccessFile(part, "rw").use { file ->
            if (appendFrom == 0L) file.setLength(0L)
            file.seek(appendFrom)
            val buffer = ByteArray(BUFFER_BYTES)
            body.byteStream().use { input ->
                while (true) {
                    if (cancelRequested.get()) return Notice.Paused
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    file.write(buffer, 0, read)
                    written += read
                    meter.record(System.currentTimeMillis(), written)
                    publish(request, source, written, total, meter)
                }
            }
            file.fd.sync()
        }

        if (total != null && written != total) {
            return Notice.Done(
                DownloadState.Failed(
                    request.version,
                    "下载未完成（已收到 ${ResumableDownload.formatBytes(written)}），可继续下载",
                    canResume = true,
                )
            )
        }
        return verifyAndClearChunks(request, part, total ?: part.length())
    }

    // ---------- 进度发布 ----------

    private fun publish(
        request: DownloadRequest,
        source: UpdateSource,
        downloaded: Long,
        total: Long?,
        meter: SpeedMeter,
    ) {
        _state.value = DownloadState.Running(
            version = request.version,
            downloadedBytes = downloaded,
            totalBytes = total,
            bytesPerSecond = meter.bytesPerSecond() ?: 0L,
            etaSeconds = meter.etaSeconds(downloaded, total),
            // 直连时不显示来源，默认路径不打扰
            sourceLabel = source.label.takeIf { source.id != DownloadSourceSet.DIRECT_ID },
        )
    }

    private companion object {
        const val BUFFER_BYTES = 64 * 1024

        /** 实测 8 并发把单连接吞吐提到 5.89 倍；单个常量，日后好调 */
        const val MAX_CONNECTIONS = 8
        const val MAX_CHUNK_ATTEMPTS = 3
        const val RETRY_BASE_MILLIS = 500L
        const val PROGRESS_STEP_BYTES = 64L * 1024L
        val USER_AGENT = "ZhiJianSchedule/" + BuildConfig.VERSION_NAME
    }
}
```

> 两点说明，避免执行时走偏：
> 1. `Channel` 用的是**全限定名** `kotlinx.coroutines.channels.Channel`，因为本项目其它文件里有同名标识符（通知渠道）在用 `Channel` 这个名字，不做 import 更保险。
> 2. `writeChunk` 处理「连接被掐」时返回的是 `Stop.Retry(0)` —— 那个 `0` 只是个哨兵值，因为「读到一半断了」拿不到 HTTP 状态码。`downloadChunk` 只在**用尽了重试次数**时才把这个 0 拿去拼错误文案（`下载失败（HTTP 0），可继续下载`）。如果你介意这行文案里有 `HTTP 0`，把 `downloadChunk` 里那句改成 `ChunkOutcome.Error("下载中断，可继续下载")` 即可，行为不变。

- [ ] **Step 4: 编译确认**

```bat
cmd /c ".tmp\run-tests.bat :app:compileDebugKotlin --console=plain"
```

预期：`BUILD SUCCESSFUL`。若报 `Unresolved reference: ChunkProgress`，说明 Task 2 的实现没落地，回 Task 2 Step 3 补 `ChunkProgress`；若报 `Argument type mismatch`，说明 `applyChunkResponse` / `writeChunk` 的参数顺序与声明不一致，按 Step 3 的签名逐个对齐。

- [ ] **Step 5: 跑全量单测确认没回归**

```bat
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --console=plain"
```

预期：`BUILD SUCCESSFUL`，13 个测试类 / 149 个用例，0 失败。

- [ ] **Step 6: 提交**

```bat
git -C "D:\课程表" add app/src/main/java/com/zhijian/schedule/update/ApkDownloader.kt
git -C "D:\课程表" commit -m "下载器改为 8 路并发分片 + 源链换源，v1 单连接路径保留为 Range 降级路径"
```


---

### Task 7: 通知与前台服务带上来来源标签

**Files:**
- Modify: `app/src/main/java/com/zhijian/schedule/update/UpdateNotification.kt`
- Modify: `app/src/main/java/com/zhijian/schedule/update/ApkDownloadService.kt`

- [ ] **Step 1: 通知文案加来源标签**

把 `UpdateNotification.kt` 的 `buildProgress`（37-65 行）替换为：

```kotlin
    /** 进度通知；percent 为 null 时显示不确定进度（响应没有长度信息）；sourceLabel 非空时注明来源 */
    fun buildProgress(
        context: Context,
        version: String,
        downloadedBytes: Long,
        totalBytes: Long?,
        percent: Int?,
        etaSeconds: Long?,
        sourceLabel: String? = null,
    ): Notification {
        val source = sourceLabel?.let { " · 经 $it" }.orEmpty()
        val text = if (totalBytes != null) {
            val tail = ResumableDownload.etaText(etaSeconds)?.let { " · $it" }.orEmpty()
            "${ResumableDownload.formatBytes(downloadedBytes)} / " +
                "${ResumableDownload.formatBytes(totalBytes)}$tail$source"
        } else {
            "已下载 ${ResumableDownload.formatBytes(downloadedBytes)}$source"
        }
        return NotificationCompat.Builder(context, CH_UPDATE)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("正在下载更新 v$version")
            .setContentText(text)
            .setProgress(100, percent ?: 0, percent == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent(context))
            .addAction(0, context.getString(R.string.update_action_cancel), cancelDownloadIntent(context))
            .build()
    }
```

- [ ] **Step 2: 服务把标签传下去**

把 `ApkDownloadService.kt` 的 `notificationFor`（125-133 行）替换为：

```kotlin
    private fun notificationFor(state: DownloadState.Running, percent: Int?): Notification =
        UpdateNotification.buildProgress(
            context = this,
            version = state.version,
            downloadedBytes = state.downloadedBytes,
            totalBytes = state.totalBytes,
            percent = percent,
            etaSeconds = state.etaSeconds,
            sourceLabel = state.sourceLabel,
        )
```

- [ ] **Step 3: 编译确认**

```bat
cmd /c ".tmp\run-tests.bat :app:compileDebugKotlin --console=plain"
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 4: 提交**

```bat
git -C "D:\课程表" add app/src/main/java/com/zhijian/schedule/update/UpdateNotification.kt app/src/main/java/com/zhijian/schedule/update/ApkDownloadService.kt
git -C "D:\课程表" commit -m "下载通知带上来来源标签，直连时不显示"
```

---

### Task 8: 「镜像加速」设置项

**Files:**
- Modify: `app/src/main/java/com/zhijian/schedule/core/data/prefs/SettingsStore.kt`

- [ ] **Step 1: 加字段**

把 `AppSettings` 的两个抓取时间字段之后补一行（`data class AppSettings` 的收尾 `)` 之前）：

```kotlin
    /** 「镜像加速（实验）」开关；默认关闭 —— 实测镜像比直连还慢，只在直连彻底跑不通时当退路 */
    val mirrorsEnabled: Boolean = false,
```

- [ ] **Step 2: 加键与读写**

在 `private object Keys` 的 `val HOLIDAY_FETCHED = ...` 之后补：

```kotlin
        val MIRRORS = booleanPreferencesKey("update_mirrors_enabled")
```

在 `settings` 的 `AppSettings(...)` 构造里、`holidayFetchedAt = ...` 之后补：

```kotlin
            mirrorsEnabled = p[Keys.MIRRORS] ?: false,
```

在 `setHolidayFetchedAt` 之后补：

```kotlin
    suspend fun setMirrorsEnabled(enabled: Boolean) = dataStore.edit { it[Keys.MIRRORS] = enabled }
```

- [ ] **Step 3: 编译确认**

```bat
cmd /c ".tmp\run-tests.bat :app:compileDebugKotlin --console=plain"
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 4: 提交**

```bat
git -C "D:\课程表" add app/src/main/java/com/zhijian/schedule/core/data/prefs/SettingsStore.kt
git -C "D:\课程表" commit -m "新增「镜像加速」设置项（默认关闭）"
```

---

### Task 9: 设置页接线（续传点 + 镜像开关）

**Files:**
- Modify: `app/src/main/java/com/zhijian/schedule/feature/settings/SettingsViewModel.kt`

- [ ] **Step 1: 用统一的续传点刷新磁盘状态**

把 `checkUpdate()` 里驱动 `restoreFromDisk` 的那段（251-256 行）替换为：

```kotlin
            // 磁盘上要是已经有下好的安装包或断点，直接把状态恢复出来，别又下一遍
            if (info?.apkUrl != null) {
                val version = info.version
                val size = info.apkSize
                withContext(Dispatchers.IO) { downloader.restoreFromDisk(version, size) }
            }
```

- [ ] **Step 2: 下载请求带上镜像开关**

把 `downloadUpdate()`（263-279 行）替换为：

```kotlin
    fun downloadUpdate() {
        val info = uiState.updateInfo
        val url = info?.apkUrl
        if (info == null || url.isNullOrBlank()) {
            uiState = uiState.copy(message = "请先检查更新")
            return
        }
        ApkDownloadService.start(
            context,
            DownloadRequest(
                version = info.version,
                url = url,
                sizeBytes = info.apkSize,
                digest = info.apkDigest,
                // 没有官方摘要时一律禁用镜像候选，代码层不给自己留后门
                mirrorsEnabled = uiState.settings.mirrorsEnabled && ResumableDownload.normalizeDigest(info.apkDigest) != null,
            ),
        )
    }
```

- [ ] **Step 3: 加开关回调**

在 `cancelDownload()` 之前插入：

```kotlin
    fun setMirrorsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setMirrorsEnabled(enabled) }
    }
```

并在 import 区补：

```kotlin
import com.zhijian.schedule.core.update.ResumableDownload
```

- [ ] **Step 4: 编译确认**

```bat
cmd /c ".tmp\run-tests.bat :app:compileDebugKotlin --console=plain"
```

预期：`BUILD SUCCESSFUL`。

- [ ] **Step 5: 提交**

```bat
git -C "D:\课程表" add app/src/main/java/com/zhijian/schedule/feature/settings/SettingsViewModel.kt
git -C "D:\课程表" commit -m "设置页接线镜像开关与无摘要时的强制禁用"
```

---

### Task 10: 设置页 UI（开关 + 来源标签）

**Files:**
- Modify: `app/src/main/java/com/zhijian/schedule/feature/settings/SettingsScreen.kt`

- [ ] **Step 1: 补 import**

在 `SettingsScreen.kt` 的 import 区追加（保持现有分组顺序）：

```kotlin
import com.zhijian.schedule.core.update.DownloadSourceSet
```

- [ ] **Step 2: 卡片里加「镜像加速」开关**

在 `UpdateSection` 的 `Card { ... }` 里、`if (ui.updateCacheBytes > 0L && download !is DownloadState.Running) { ... }` 这段**之前**插入：

```kotlin
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("镜像加速（实验）", color = colors.text, fontSize = 13.sp)
                Text(
                    text = mirrorHint(ui),
                    color = colors.textMuted,
                    fontSize = 12.sp,
                )
            }
            Switch(
                checked = ui.settings.mirrorsEnabled,
                onCheckedChange = { viewModel.setMirrorsEnabled(it) },
            )
        }
```

- [ ] **Step 3: 加提示文案函数并让状态行显示来源**

在 `runningLine` **之前**插入：

```kotlin
/** 镜像提示：先说合法性（有没有官方摘要），再说用途（实测镜像更慢，只当退路） */
private fun mirrorHint(ui: SettingsUiState): String {
    val reason = DownloadSourceSet.mirrorDisabledReason(
        digest = ui.updateInfo?.apkDigest,
        mirrorsEnabled = ui.settings.mirrorsEnabled,
    )
    return when (reason) {
        null -> "直连跑不通时自动换镜像重试；实测镜像更慢，能直连就不会切"
        "镜像加速已关闭" -> "关闭中：只用 GitHub 直连"
        // reason 已经是完整句（含「已禁用镜像」），直接用它，别再拼「本次只用直连」造成重复
        else -> reason
    }
}
```

把 `runningLine`（493-498 行）替换为：

```kotlin
private fun runningLine(state: DownloadState.Running): String {
    val head = state.totalBytes
        ?.let { "已下载 ${ResumableDownload.formatBytes(state.downloadedBytes)} / ${ResumableDownload.formatBytes(it)}" }
        ?: "已下载 ${ResumableDownload.formatBytes(state.downloadedBytes)}"
    // 直连时 sourceLabel 为 null，默认路径不显示来源
    val source = state.sourceLabel?.let { " · 经 $it" }.orEmpty()
    return head + source + (ResumableDownload.etaText(state.etaSeconds)?.let { " · $it" }.orEmpty())
}
```

- [ ] **Step 4: 编译并跑全量单测**

```bat
cmd /c ".tmp\run-tests.bat :app:compileDebugKotlin --console=plain"
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --console=plain"
```

预期：两条都 `BUILD SUCCESSFUL`，13 个测试类 / 149 个用例 0 失败。

- [ ] **Step 5: 提交**

```bat
git -C "D:\课程表" add app/src/main/java/com/zhijian/schedule/feature/settings/SettingsScreen.kt
git -C "D:\课程表" commit -m "设置页新增镜像加速开关与下载来源标签"
```

---

### Task 11: 收尾验证

**Files:** 无新增（只跑验证，发现问题就地修）

- [ ] **Step 1: 打包 + 全量单测**

```bat
cmd /c ".tmp\run-tests.bat :app:assembleDebug --console=plain"
cmd /c ".tmp\run-tests.bat :app:testDebugUnitTest --console=plain"
```

预期：两条都 `BUILD SUCCESSFUL`，13 个测试类 / 149 个用例 0 失败。把实际数字记下来，写进最终报告。

- [ ] **Step 2: 确认 `.meta` 的 v1 兼容是「解析」而不是「崩掉」**

```bat
git -C "D:\课程表" log --oneline -12
git -C "D:\课程表" status --short
```

预期：log 显示本计划产出的 **10 个提交**（Task 1-10 各一次，Task 11 只验证不提交）；`git status` 只剩 `?? docs/`（`docs/` 按用户要求留在本地不提交）。

- [ ] **Step 3: 确认 v1 顺序路径没有被删**

```bat
cmd /c "findstr /n /c:\"downloadSequential\" /c:\"MAX_CONNECTIONS\" app\src\main\java\com\zhijian\schedule\update\ApkDownloader.kt"
```

预期：出现 `downloadSequential` 的定义与 **3** 处调用（Range 降级、分片计划为空、分片数 <= 1），以及 `MAX_CONNECTIONS = 8` 的常量定义。

- [ ] **Step 4: 真机验证清单（人工，设计文档 §6）**

在 v1 那 8 项基础上加这 6 项，逐项走一遍并在报告里给出结论：

1. 正常网络下 44.7 MB 下载耗时与 v1 对比（期望 3~6 倍提升）；
2. 8 并发期间 `adb shell dumpsys activity services com.zhijian.schedule` 确认 FGS 类型仍是 `dataSync`、通知进度平滑；
3. 下载中切飞行模式 → 恢复 → 点「重试」只补未完成的分片（对照卡片字节数不是从 0 开始）；
4. 「取消 → 继续下载」仍能从已完成分片续上；
5. 开启镜像开关后用卡片标签确认走的是镜像；关掉后回到直连（或不显示来源）；
6. 构造「无 digest」场景（本地把 `UpdateInfo.apkDigest` 改成 `null`）确认镜像候选被禁用、设置页出现「该版本未提供官方摘要，已禁用镜像」。

```bat
adb shell dumpsys activity services com.zhijian.schedule
```

> 手机上有 `vgate0` 隧道，App 流量走加速通道而 `shell` 用户流量不走，所以 `adb shell` 里没法用 curl 对照实验 —— 并发收益只能靠第 1 项直接量 App 的耗时。若手机不可用，在报告里标注为未验证。

---

## 自检记录（写完计划后回看）

**1. 设计覆盖**

| 设计章节 | 落在哪个任务 |
|---|---|
| §2.1 `.meta` 向后兼容扩展（`chunkSize` / `completedChunks`） | Task 2（字段 + `parseMeta`）、Task 5（`readMeta` 走纯逻辑） |
| §2.1 兼容规则：v1 元数据由 `parseMeta` 归一化、`.part` 的连续前缀折算成整片不丢弃 | Task 2（`resolveResumePoint` 的折算分支 + 单测 `resumePointUpgradesV1PartFileByReplanningChunks`） |
| §2.2 分片大小 4 MiB / 12 片 / 单 `RandomAccessFile` + `seek` / 不开分片文件 | Task 1（`planChunks`、`DEFAULT_CHUNK_BYTES`）、Task 6（`parallelDownload`、`writeChunk`） |
| §2.2 共享待办队列 + `min(MAX_CONNECTIONS, chunkCount)` 个 worker | Task 6（`Channel<Int>` + `List(minOf(MAX_CONNECTIONS, plan.chunkCount)) { async { ... } }`） |
| §2.2 完成度以 `completedChunks` 为唯一权威 | Task 2（`ChunkProgress.completed`）、Task 6（`done` + `writeMeta`） |
| §2.2 `fd.sync()` → 记账 → 重写 `.meta`（先落盘再记账） | Task 6（`writeChunk` 的 `synchronized` 块） |
| §2.2 片内失败重试 3 次（指数退避）、仍失败则 `Failed(canResume = true)` 且已完成分片保留 | Task 6（`MAX_CHUNK_ATTEMPTS` / `RETRY_BASE_MILLIS` / `delay(RETRY_BASE_MILLIS * attempt)`） |
| §2.3 进度 = 已完成片 × chunkSize + Σ 在飞片已写字节；UI 零改动 | Task 2（`ChunkProgress.downloadedBytes`）、Task 6（`publish`） |
| §2.3 换源/崩溃后重下某片时进度回退 | Task 2（`progressGoesBackwardsWhenAChunkIsRedone`）、Task 6（`written[index]` 被覆盖） |
| §2.4 任一 Range 请求返回 200 → 放弃并行、清空、走 v1 单连接；500/IO 不降级 | Task 3（`decideRangeSupport` 单测）、Task 6（`Notice.Fallback` → `downloadSequential`） |
| §2.5 `MAX_CONNECTIONS = 8` 单个常量 | Task 6（`private companion object`） |
| §2.6 磁盘预检按 `missing = total - 已完成片字节` | Task 6（`downloadSource` 里的 `missing` 三分支） |
| §3.1 不改 `UpdateChecker`，从 `browser_download_url` 模板化生成候选 | Task 4（`DownloadSourceSet`，全程不碰 `UpdateChecker.kt`） |
| §3.2 无 digest 时候选只有直连（代码级强制） | Task 4（`availableSources` + 3 个单测）、Task 9（ViewModel 再兜一道） |
| §3.3 只有选中源 == `.meta.url` 才续传，否则清空 | Task 2（`resumePointStartsFreshWhenSourceChanged`）、Task 5（`deleteChunks`）、Task 6（`downloadSource` 的换源判重与 `store.deleteAll`） |
| §3.4 一次尝试只用一个源；失败且还有下一个则自动换源一次；慢 ≠ 失败；全失败 → `Failed(canResume = false, "所有下载源都失败")` | Task 6（`runSourceChain`、`lastReason`） |
| §3.5 设置页开关「镜像加速（实验）」默认关闭 | Task 8（`AppSettings.mirrorsEnabled`）、Task 10（`Switch`） |
| §3.5 卡片与通知显示来源标签，直连不显示 | Task 6（`Running.sourceLabel`）、Task 7（通知）、Task 10（设置页） |
| §3.5 「该版本未提供官方摘要，已禁用镜像」提示 | Task 4（`mirrorDisabledReason`）、Task 10（`mirrorHint`） |
| §4 改哪些文件（8 个文件 + 单测） | Task 1-10 的 `**Files:**` 逐条对齐 |
| §5 明确不做（速度自适应 / 分片哈希 / 多源竞速 / 第三方库 / 不改 v1 状态机与通知渠道） | 全计划无相关改动；Task 11 Step 3 显式确认 v1 路径仍在 |
| §6 单测 6 大项 | 分片规划 Task 1；进度聚合、`.meta` 兼容、换源判据 Task 2；降级判据 Task 3；源链候选 Task 4 |
| §6 真机清单 6 项 | Task 11 Step 4 |
| §7 风险（并发是单常量、镜像只当退路、digest 兜底、先 sync 再记账） | Task 1/3/4/6 各自的常量与判定 |

**2. 占位符扫描**

已通读全文：没有 `TBD` / `TODO` / 「稍后实现」/「加上适当的错误处理」/「添加适当的校验」/「类似 Task N」/「照上面的写法改一下」。每个改动步骤给出的都是可直接粘贴的完整代码；每条命令都给了 `BUILD SUCCESSFUL` / `BUILD FAILED` 与**具体用例数**。
全文只有三处「说明性引用」而不是占位符：Task 6 Step 3 结尾的两条执行提示（`Channel` 的全限定名、`Stop.Retry(0)` 哨兵值）、以及开头的「与设计文档的 4 处差异」。它们都在解释**为什么这么写**，没有一句是「这里待补」。

**3. 类型一致性**

- `Chunk(index, start, endInclusive)` / `ChunkPlan(totalBytes, chunkSize, chunks)` / `ChunkPlan.chunkCount` / `ChunkPlan.bytesOf(index)`：Task 1 定义；Task 2 的 `ResumePoint.Chunked(plan, completed)`、`ChunkProgress(plan, completed, written)`、Task 6 的 `parallelDownload(plan)`、`writeChunk` 全部用同一组名字与参数顺序。
- `ChunkProgress(plan, completed, written): .downloadedBytes / .neededChunks() / .remainingBytes() / .resetChunk(index)`：Task 2 定义，Task 6 的三处构造点（`parallelDownload` 初始进度与 `writeChunk` 的两处 `now`）都用 `ChunkProgress(plan, done.sorted(), written).downloadedBytes` 这一种读法；`.neededChunks()` / `.resetChunk()` / `.remainingBytes()` 只由单测覆盖（Task 6 用自己的 `done` + `written` 直接调度，不再经过这个快照类）。
- `ResumableDownload.planChunks(totalBytes: Long, chunkSize: Long = DEFAULT_CHUNK_BYTES)`：Task 1 定义，Task 2 的 `resolveResumePoint` 与 Task 6 的 `downloadSource` 都按 `planChunks(total, chunkSize)` 具名调用。
- `ResumableDownload.resolveResumePoint(version, url, totalBytes, partBytes, rawMeta): ResumePoint`：**签名里没有 `chunkSize`** —— 分片大小只从 `rawMeta?.chunkSize` 取，缺失时回落 `DEFAULT_CHUNK_BYTES`。单测若要断言分片边界，必须用真实的 4 MiB 常量（配缩小的总长），不能指望传一个签名里不存在的小分片。
- `ResumableDownload.chunkCountOf(writtenBytes, chunkSize): Int?`：Task 2 定义并在 `resolveResumePoint` 内使用，签名在自检里核对一致（返回可空 Int，调用处都带 `?: 0`）。
- `ResumePoint.Fresh(plan)` / `Chunked(plan, completed)`：Task 2 定义，Task 6 的 `downloadSource` 两分支穷尽匹配（`when (resume)` 无 `else`，每个分支都返回 `Notice`）；`Fresh` 带 plan 是为了「换源必须重下」时不必再算一遍分片计划。
- `RangeSupport.Supported` / `Unsupported` / `AlreadyComplete` / `Corrupt` / `Fail(reason, canResume)`：Task 3 定义，Task 6 的 `applyChunkResponse` 与 `Stop` 一一映射。
- `UpdateSource(id, label, url)` / `DownloadSourceSet.availableSources(directUrl, digest, mirrors)` / `withPrefix(prefix, url)` / `mirrorDisabledReason(digest, mirrorsEnabled)` / `DIRECT_ID` / `MIRRORS`：Task 4 定义；Task 6 用 `availableSources`、`DIRECT_ID`、`MIRRORS`，Task 10 用 `mirrorDisabledReason` —— 名字与参数名逐一对齐。
- `DownloadRequest(version, url, sizeBytes, digest, mirrorsEnabled)`：Task 6 Step 1 定义（最后一项有默认值，所以 Task 9 之前的所有旧调用点仍能编译）；Task 9 用 `mirrorsEnabled =` 具名传入。
- `DownloadState.Running(..., sourceLabel: String? = null)`：Task 6 Step 1 定义（有默认值，`UpdateNotification`/`ApkDownloadService` 的旧调用点不受影响）；Task 7 的通知与 Task 10 的 `runningLine` 消费同名属性。
- `PartMeta(version, url, etag, lastModified, totalBytes, digest, chunkSize, completedChunks)`：Task 2 加最后两项，Task 6 的三处构造（`downloadSource` 里锚来源、`writeChunk` 里逐片记账、`handleSequential` 里 v1 写法）都用**具名参数**，不依赖字段顺序。
- `UpdateStore.readMeta(version): PartMeta?` / `deleteChunks(version)` / `deleteAll(version)` / `partBytes(version)` / `freeSpaceBytes()`：Task 5 Step 2 新增 `deleteChunks`，其余沿用 v1 签名；Task 6 只调用这些。
- `SettingsViewModel.setMirrorsEnabled(enabled: Boolean)`：Task 9 定义，Task 10 的 `Switch(onCheckedChange = { viewModel.setMirrorsEnabled(it) })` 调用。
- 累加用例数核对（逐个任务数出来的，不是估的）：
  - `ResumableDownloadTest`：33（基线）→ 38（Task 1 加 5）→ 47（Task 2 加 9）→ 52（Task 3 加 5）；
  - `DownloadSourceTest`：Task 4 新加 7；
  - 其余 11 个既有测试类合计 90（`DownloadProgressTest` 13、`HolidayParserTest` 4、`IcsParserTest` 5、`OcrTimetableParserTest` 10、`WeekMaskTest` 6、`TableMappingTest` 4、`GradeParserTest` 8、`ZhengFangParseTest` 7、`CapsulePlanTest` 23、`GlassThemeTest` 6、`MotionKeyframesTest` 4）；
  - 全量 = 52 + 7 + 90 = **149 个用例 / 13 个测试类**。另一条算法同样得到 149：123 + 5 + 9 + 5 + 7。
  - `.meta` 的 3 个既有元数据用例（`partMetaRoundTripsThroughJson` 等）在 Task 2 加了两个字段之后仍然通过：它们用整对象 `assertEquals(meta, 解码结果)` 比较，而新字段有默认值，往返仍然相等。

**4. 自检里发现并修掉的问题**

- **Task 6 的六处硬缺陷（派发前复核发现，已修）**：Task 6 是唯一的大改，派发前逐行复核又抓到六个问题，其中三个是「按原文执行必然编译不过」、三个是「能编译但行为错」：  1. **`writeChunk` 用了不在参数表里的 `response`（编译不过）**：函数体里 `response.header("ETag")`（捕获校验器）与 `response.header("Last-Modified")` 两处引用 `response`，但参数表里没有它、调用点也没传。这个漏洞最阴：它只在**片真的写完之后**那一句才被用到，编译器一定报 `Unresolved reference: response`。已把 `response: Response` 加进参数表（放在 `input` 之后）、调用点同步传参。
  2. **`downloadSequential(request, source)` 少了一个必填参数（编译不过）**：声明是 `(request, source, fromScratch: Boolean)`，没有默认值，而 `Notice.Fallback` 那一支只传了两个实参。按设计 §2.4「200 时清空分片与元数据、改用单连接重下」，这里必须是 `fromScratch = true`。已补。
  3. **`downloadSequential(...)` 的返回值被当成 `DownloadState` 用（类型不匹配）**：它返回的是 `Notice`，但 `runSourceChain` 的返回类型是 `DownloadState`，`return downloadSequential(...)` 直接编译不过。已改为 `.settle(request.version)`。
  4. **`Notice.Failed` 只被匹配、从未被构造 → 镜像兜底链是死代码（最严重的一处）**：全文 `Notice.Failed(` 零处构造。`parallelDownload` 的 `ChunkOutcome.Error` 分支原本直接返回 `Notice.Done(DownloadState.Failed(...))` —— 那是**终态**，于是 `runSourceChain` 立刻 `return notice.state`，**永远不会换到下一个源**。也就是说设计 §3.4 承诺的「直连失败自动换镜像重试一次」根本不会发生，镜像开关打开也没用。已把 `parallelDownload` 的失败分支改成 `Notice.Failed(outcome.reason, canResume = true)`，让源链真正拿到「这个源没跑通」的信号。
  5. **`seek` + `write` 不是原子的 → 并发写坏文件（静默数据损坏）**：`writeChunk` 里 `file.seek(offset); file.write(...)` 原本在 `synchronized` 块**之外**，而 8 个 worker 共用同一个 `RandomAccessFile`，文件指针是共享可变状态。交错时序「A 先 seek → 被抢占 → B 又 seek → A 才 write」会把 A 的数据写到 B 的偏移上。危险之处在于它**不报错**：文件长度照样对得上，只有整包 SHA-256 能发现，用户看到的是「下载完成但安装包校验失败」。已把 `seek`+`write` 一起包进与进度记账共用的那把 `synchronized(lock)`（顺序保持「先落盘、再记账」）。
  6. **Task 11 Step 3 的核对标准少算一处调用**：`downloadSequential` 实际有 **3** 处调用（Range 降级、分片计划为空、分片数 <= 1），计划写的是 2 处。已改。
  另外顺手修掉 `runSourceChain` 里一处语义 bug：原文在最后一个源失败时返回的是 `lastReason` —— 那是**上一个源**的失败原因，与设计 §3.4 要求的文案「所有下载源都失败」不符；多源时还会返回一个属于别的源的 reason 去描述这一次的失败。定稿改成：还有候选就 `continue` 试下一个；已是最后一个源时，单源（镜像关闭的默认路径）保留该源自己的 `reason`/`canResume`（分片还在、`.meta.url` 就是它，点「重试」能接着下），多源才按 §3.4 收场为「所有下载源都失败」且 `canResume = false`（换源时已清过分片，确实无物可续）。
- **Task 6 第七处：worker 协程的返回类型被推成 `Any`，`when` 不再穷尽（首次实跑才暴露，已修）**：`parallelDownload` 里 `async(Dispatchers.IO) { while (true) { ... } }` 的协程体没有正常出口（三个 `return@async` 都在循环体内、末尾语句是 `while`），Kotlin 把协程体的末表达式推成 `Unit`、`Deferred<*>` 推成 `Deferred<Any>`，于是 `coroutineScope` 返回的 `outcome` 类型是 `Any` —— 对 `Any` 做 `when` 自然报「must be exhaustive」，且编译器**不告诉你缺哪个分支**（因为它不知道你要分派的是哪个 sealed interface）。修法两步：`async<ChunkOutcome>(...)` 显式钉住类型参数，同时把「队列被抢空」从 `return@async` 改成 `break` + 循环后的显式 `ChunkOutcome.Completed`（让协程体末表达式有确定类型）。教训：**worker 循环要么整个协程体只靠 `return@async` 出口（且循环本身不可达结束），要么用 `break` 让末表达式收尾**；混用会让类型推断悄悄落到 `Unit`/`Any`。  1. **可空接收者上直接调 `.contains()`**：`assertTrue(DownloadSourceSet.mirrorDisabledReason("md5:x", true).contains("摘要"))` —— 该函数返回 `String?`，Kotlin 不允许在可空值上直接调方法（`Only safe (?.) or non-null asserted (!!.) calls are allowed`）。已改成对完整串做 `assertEquals`，比模糊的 `contains` 更严格。
  2. **文案期望值漏了后缀**：测试写「该版本未提供官方摘要」，实现写「该版本未提供官方摘要，已禁用镜像」，而**设计 §3.2 第 107 行**的原文是后者 —— 实现是对的，测试抄短了。已按设计改成完整串。
  3. **模板用例的期望字面量是另一个地址**：该用例传的实参是 `ghUrl`，期望值却写成 `https://github.com/o/r/releases/download/v1/a.apk` 这个占位地址。已统一成 `"https://gh-proxy.com/$ghUrl"`（与同文件 `candidatesStartWithDirectAndAppendMirrorsWhenDigestIsValid` 里第 708 行的写法一致，可相互印证）。
  连带修正 Task 10 的 `mirrorHint`：`reason` 现在已是完整句（含「已禁用镜像」），原 `else -> "$reason，本次只用直连"` 会拼出「…已禁用镜像，本次只用直连」的重复，已改为 `else -> reason`。
- **基线用例数又记错（已修，第二次）**：我在派发 Task 4 时把「Task 1/2/3 之后的基线」写成「12 个测试类 / 137 个用例」，实际是 **142**（149 − 7）。`ResumableDownloadTest` 的 52 没错，错在其余类的合计：12 个既有类合计为 **90**（v1 基线 123 − 33），加上 `ResumableDownloadTest` 的 52 即 142。子代理如实把这个出入报了上来，没有顺着我的话附和。教训与上一条同源：**任何用例数都要现场从 XML 数，不能凭记忆写进派发指令**。：`resumePointUpgradesV1PartFileByReplanningChunks` 与 `resumePointStartsFreshWithoutAnyPartFile` 原本用 `planChunks(16L, chunkSize = 4L)` 造期望值、却传 `rawMeta = null`（或 `chunkSize = 0` 的 v1 meta）。但 `resolveResumePoint` 的签名里**没有 `chunkSize` 参数**，分片大小的唯一来源是 `rawMeta?.chunkSize`，缺失时回落 `DEFAULT_CHUNK_BYTES`（4 MiB）—— 所以函数只可能给出 4 MiB 分片，任何最小实现都做不出 4 字节分片。Task 2 首次执行时如实停在这里、未提交（`resumePointReusesPlanWhenSourceAndTotalMatch` 等另两个用例因为 meta 里带了 `chunkSize = 4L` 才侥幸通过，掩盖了这个矛盾）。定稿改用**真实的 4 MiB 分片 + 缩小的总长（16 MiB）**：`partBytes = 10 MiB` 仍折算成 `[0, 1]` 整片，用例想钉死的边界（半截片重下）一点没丢，但这次走的是生产路径上的真实常量。教训：**给函数造期望值时，参数必须来自它的真实签名**；用一个签名里根本不存在的入参去构造期望值，写出来的用例只是在测一个不存在的实现。
- **`ChunkProgress` 从未被声明（已补）**：Task 2 的 4 个单测（`progressSumsFinishedChunksAndInFlightBytes` 等）与 Task 6 的 3 处调用点（`parallelDownload` 的初始进度、`writeChunk` 的两处 `now`）都在用 `ChunkProgress(plan, completed, written)` 及其 `.downloadedBytes` / `.neededChunks()` / `.remainingBytes()` / `.resetChunk()`，但初稿只在「文件结构」表和「类型一致性」自检里提过这个名字，**Task 2 Step 3 没给出定义** —— 计划按原文执行必然编译不过（`Unresolved reference: ChunkProgress`）。已把完整的 `ChunkProgress` 类补进 Task 2 Step 3（紧跟 `ResumePoint` 之后）。注意构造参数 `written` 与内部字段同名会在 Kotlin 里歧义，内部字段改名 `bytes`。
- **用例数又算错一次（已修）**：初稿 Task 2 写「38 + 新 8 = 46」，但逐个机械数 `@Test` 得到 Task 2 实际是 **9** 个（4 个进度聚合 + 4 个续传点 + 1 个 `chunkCountOf`），于是 47 / 52 / **149** 才是真值。连同「自检」里的推导（`51 + 7 + 90` → `52 + 7 + 90`、`123 + 5 + 8 + 5 + 7` → `123 + 5 + 9 + 5 + 7`）一并改正。这条是继「130 → 148」之后的第二次同类型错误，教训是**用例数必须机械数 `@Test`，不能靠累加记忆**。
- **`writeChunk` 需要 `file` 与 `source`（已修）**：初稿里 `writeChunk` 引用了两个不存在的辅助函数 `currentSource` / `currentFile`，`applyChunkResponse` 的调用点还多传了一个参数。定稿把 `source: UpdateSource` 与 `file: RandomAccessFile` 提为显式参数，三处签名（`parallelDownload` → `downloadChunk` → `applyChunkResponse` → `writeChunk`）的参数表逐一对齐，全部在一段完整代码里，没有过渡步骤。
- **`published` 变量无作用（已修）**：初稿的 `writeChunk` 里 `published` 只用于 `return if (published) Stop.None else Stop.None` —— 两个分支同值。定稿删掉了该变量，直接 `return Stop.None`。
- **换源后的回退分支用错了源的标签（已修）**：初稿的 `runSourceChain` 在「第一个源失败、换第二个源再失败」时，把**第一个源**当成了 Fallback 的目标。定稿改成循环推进 `index`，`downloadSequential(request, source)` 用的始终是当前正在试的那个源。
- **`Notice.toState` 辅助函数多余（已修）**：初稿为了收敛换源分支写了个 `toState(...)`，但它把 `Failed` 一律映射成 `canResume = false`，丢了 `canResume` 语义。定稿删掉该函数，换成显式的 for 循环推进，语义直接可读。
- **`apkDownloader` 名称笔误（已修）**：草稿的一处叙述里写成 `apkdownloader.cleanupOutdated`；本计划不涉及该调用点，已统一为既有代码里的 `apkDownloader`（`ZhiJianApp.kt`），不在本次改动范围内。
- **`.part` 未被丢弃 ≠ `.part` 字节全都被认成已完成（已澄清并落到单测）**：`resumePointUpgradesV1PartFileByReplanningChunks` 断言 10 字节 / 4 字节片 → 认成 `[0, 1]` 两整片（第 3 片半截重下），既证明「不丢弃」也把边界钉死。
- **「换源就清空」不能靠返回 `Fresh` 来表达（已修）**：初稿让 `resolveResumePoint` 在来源变化时返回 `data object Fresh`，但调用方随后还要 `planChunks(...)` 再算一遍 —— 而 `knownTotal` 来自 `meta.totalBytes`，一旦先 `deleteAll` 就再也算不出来了。定稿把 `Fresh` 改成 `data class Fresh(val plan: ChunkPlan?)`，由 `resolveResumePoint` 在读盘之前把计划算好带出来，调用方直接 `resume.plan`。
- **`ResumePoint.Sequential` 曾是死代码（已删）**：`readMeta` 经 `parseMeta` 归一化后 `chunkSize` 永远是正值，`resolveResumePoint` 里 `meta.chunkSize <= 0L` 那条分支不可达，`ResumePoint.Sequential` 与它的调用点、断言它的单测都是死的。已把该分支（含注释）、`resumePointFallsBackToSequentialWhenMetaHasNoChunkSize` 单测、`missing` 与 `when (resume)` 里的对应分支一并删除；v1 遗留的 `.part` 由「连续前缀折算成整片」的既有分支覆盖（最多重下不到一片），`readMeta` 的 KDoc 也同步改成实话（`chunkSize` 补默认值，不是 0）。
- **降级时不能把已下字节清光（已修）**：初稿的 `downloadSequential` 无条件先 `deleteChunks` 再清元数据，导致「Range 不可用」时把已经下到的字节全扔了，而服务端不认 Range 意味着不可能带 Range 续传 —— 白扔是本可以避免的。定稿给 `downloadSequential` 加 `fromScratch` 开关：只有「分片计划为空 / 只有一片」这类必须重下的情况才清元数据，且 `handleSequential` 自己会在 `appendFrom == 0L` 时 `setLength(0)` 覆盖写，不需要调用方提前截断。
- **不能把旧元数据的 ETag 照抄进新一轮（已修）**：初稿的 `downloadSource` 在写 `.meta` 时带上 `meta?.etag`，可那对校验器属于**上一次会话**。照抄的后果是 `If-Range` 拿着过期校验器去问服务端：资源真的变了它也可能被判成没变，从而把新旧分片的字节拼在一起（事后只能靠整包 SHA-256 发现，代价是白下 46 MB）。定稿改成：重下（`Fresh`）时 `etag`/`lastModified` 留空，由第一片写完时从响应头捕获真正的校验器（`writeChunk` 里 `response.header("ETag") ?: 旧值`）；只有 `Chunked` 续传这一支才保留旧值 —— 那时 `.meta.url` 与当前源一致，已经证明是同一份文件。
- **`.meta.completedChunks` 为空但 `.part` 非空时不能直接清（已修）**：那样会白扔已经下好的量。`resolveResumePoint` 自带「按 `.part` 重新规划整片」的分支（`chunkCountOf` 把连续前缀折算成整片数），所以 `completedChunks` 为空只会走「认前 N 整片」，不会走到清空那一支。
- **`ActiveCall` → `ActiveCalls`（已修）**：v1 用单个 `activeCall`，8 路并发下会漏取消 —— 只剩最后一条连接被取消，其余 7 条会继续写文件。Task 6 改为 `ConcurrentHashMap.newKeySet<Call>()` + `cancelActiveCalls()`，`cancel()` 与 `timeout()` 都改用它。
- **换源时必须清空分片（已修）**：初稿的换源分支直接换源继续跑，会跨源拼接。Task 6 的 `runSourceChain` 在换源前显式 `store.deleteAll(request.version)`。
- **降级到单连接前必须先清元数据（已修）**：v1 的顺序路径会用 `.meta.url` 判续传，如果元数据还指着镜像地址，顺序路径会带着 Range 请求打在镜像上。Task 6 的 `downloadSequential` 第一行就是 `store.deleteMeta(request.version)`。
- **取消要落回 `Paused`（已修）**：v1 靠 `CancellationException` 之外的路径处理取消；并发版里服务被回收时协程被真正取消，若不接住就会把状态永远留在 `Running`。Task 6 在 `download()` 里 `catch (e: CancellationException)` 落地 `paused(...)` 后再 `throw`。
- **`ResumableDownload.aggregateBytes` → `ChunkProgress.downloadedBytes`（已修）**：Task 6 初稿调了一个独立函数 `ResumableDownload.aggregateBytes(plan, done, written)`，但 Task 2 确立的接口是 `ChunkProgress` 的属性。Task 6 的 `writeChunk` / `parallelDownload` 已全部改用 `ChunkProgress(plan, done, written).downloadedBytes`。
- **`mirrorsEnabled` 需要默认值（已修）**：`DownloadRequest` 加第四个参数时，Task 9 之前 `ApkDownloadService.toDownloadRequest()` 里的旧构造点会编译不过。Task 6 Step 1 给 `mirrorsEnabled` 与 `sourceLabel` 都加了默认值，保证中间任务的提交点都能编译通过。
- **`sourceLabel` 也走 Intent 吗（已确认无需）**：来源标签是下载器内部按当前源算出来的，不需要跨进程传；`ApkDownloadService` 只从 `DownloadState.Running` 读，所以 Task 6 不必动 `EXTRA_*` 常量。
