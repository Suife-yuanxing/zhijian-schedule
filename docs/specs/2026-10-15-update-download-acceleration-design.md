# 应用内更新下载 · 加速设计（v2）

日期：2026-10-15
状态：**已确认**（2026-10-15 用户拍板：并发数取 **8**；镜像开关**默认关闭**。两项与本文档推荐一致，设计无需修改）
> 2026-10-17 修订：真机反馈「直连下载不可用」。镜像开关改为**默认开启**；下载前对候选源做 1KB Range 探测并按延迟排序（`DownloadSourceSet.orderByProbe`）；失败后弹窗提供「浏览器下载」镜像兜底。
关系：本文件是 [v1 设计](2026-10-15-in-app-update-download-design.md) 的**增量**。v1 已全部实现并提交（12 个提交），本文件只描述新增的两件事：**并发分片下载**与**镜像源链**。v1 未提及的部分（状态机、卡片、通知、安装链路、校验）一律不变。

## 1. 为什么改：实测依据

v1 在真机上跑通后，用户反馈「下载太慢」。以下是实测数据（2026-10-15，本地网络 + 手机真机）：

| 路径 | 单连接 | 4 并发 | 8 并发 |
|---|---|---|---|
| PC 经 Clash 代理（github.com → release-assets） | 82 KB/s | 312 KB/s（**3.81x**） | 482 KB/s（**5.89x**） |
| 手机直连（移动数据，经系统 vgate 加速通道） | 51 KB/s | 未能测（见下） | 未能测 |

| 其他路径 | 实测 |
|---|---|
| PC 直连 release 资产 | 0.8 KB/s（基本不可用） |
| 手机 App 下载 44.7 MB | 约 13 分钟 |

**结论一：瓶颈是「每条连接被限速」，不是总带宽。** 8 条并发把吞吐提到 5.89 倍，说明链路本身有余量，单连接被节流。这是本设计的主要依据。

**结论二：公共镜像现在基本不可用。** 实测 9 个主流 GitHub 加速站（PC 直连、同一条线路）：

| 镜像 | 结果 |
|---|---|
| gh-proxy.com | 206，前 1KB 与正版一致，但只有 **4.6 KB/s** |
| ghfast.top / mirror.ghproxy.com / gh.llkk.cc / ghproxy.vip | 连接超时 |
| cf.ghproxy.cc | **证书已过期** |
| ghproxy.net | 证书吊销检查失败 |
| gh.ddlc.top | HTTP 429 限流 |
| github.akams.cn | 404（那是选节点页，不是直链） |

唯一活着的镜像比手机直连还慢 11 倍。所以镜像**不能当主力**，只能当「直连彻底跑不通」时的最后一条退路，且默认关闭。

**结论三：手机侧没法用 shell 验证并发收益。** 手机上有 `vgate0` 隧道，App 流量走加速通道、`shell` 用户流量不走，所以 `adb shell` 里的 curl 连 `github.com:443` 都连不上（App 却能下）。并发收益的手机侧验证只能靠 App 自己（见 §7 真机清单）。

**结论四：镜像改包拦不住我们。** 官方 `sha256` 摘要取自 `api.github.com`（手机与 PC 实测都稳定可达），下载完成后的 `verifyFile` 一定会对不上。所以「用镜像」在安全上成立 —— **但前提是必须有 digest**。没有 digest 时只校验长度，那种情况下不允许走镜像。

## 2. 并发分片下载

### 2.1 数据模型（`.meta` 向后兼容扩展）

```kotlin
@Serializable
data class PartMeta(
    val version: String,
    val url: String,
    val etag: String? = null,
    val lastModified: String? = null,
    val totalBytes: Long = 0L,
    val digest: String? = null,
    // ---- v2 新增 ----
    /** 分片大小；0 = v1 写的旧元数据，按「单连接顺序续传」处理 */
    val chunkSize: Long = 0L,
    /** 已完成的分片序号；只有这里的序号才算下载完成 */
    val completedChunks: List<Int> = emptyList(),
)
```

**兼容规则**：v1 元数据（没有 `chunkSize`/`completedChunks`）由 `parseMeta` 统一归一化，缺失的 `chunkSize` 补成默认分片大小。v1 留下的 `.part` 是连续前缀，会被折算成前 N 个整片（最多重下不到一片），剩余分片照常并行 —— v1 的 `.part` 不丢弃、不需要迁移，但**不再保留单独的单连接顺序续传分支**（它已是死代码：`readMeta` 归一化后 `chunkSize` 永不 ≤ 0）。

### 2.2 分片与调度

- 分片大小 4 MiB，`chunkCount = ceil(total / chunkSize)`；46 MB → 12 片。
- 单个 `RandomAccessFile(part, "rw")`，每个 worker 对自己那片 `seek(chunkIndex * chunkSize)` 后顺序写 —— **不产生分片文件、不需要事后拼接**，磁盘峰值与 v1 相同。
- 调度：共享一个待办分片队列 + `min(MAX_CONNECTIONS, chunkCount)` 个 worker 抢队列，直到队列空。
- **完成度以 `.meta.completedChunks` 为唯一权威**（随机写让 `file.length()` 不再代表连续前缀）。
- 每片写完的顺序：`fd.sync()` → 把序号加入 `completedChunks` → 重写 `.meta`（互斥保护）。先落盘再记账，崩溃后才不会把「没写完的片」当成完成片。
- 片内失败重试 3 次（指数退避）；仍失败则整次下载 `Failed(canResume = true)`，**已完成的分片保留在 `.meta` 里**，下次点「重试」只补没完成的片。

### 2.3 进度

`downloadedBytes = 已完成片数 × chunkSize + Σ(在飞片的已写字节)`，内存里维护 `LongArray(chunkCount)` 记录每片已写字节。卡片的百分比、通知栏的进度与 ETA 全部沿用 v1 的 `DownloadState.Running` 字段，UI 零改动。

已知取舍：换源或崩溃后重新下载某片时，进度数字会**往回跳一点**（那片要重下），这是诚实的表现，不做平滑假装。

### 2.4 降级：服务端不支持 Range 时回到单连接

任一 Range 请求返回 `200`（服务端忽略 Range）时立刻放弃并行：清空分片与元数据，改用 v1 已实现并验证过的单连接顺序路径重下一次。**500 或 IO 异常不降级**（那是网络问题，照 v1 重试即可）。

### 2.5 并发数

`MAX_CONNECTIONS = 8`（实测 5.89 倍收益的取值）。单个常量，便于日后调整。46 MB 文件用 8 条短连接是一次性行为，不做长时间占用。

### 2.6 磁盘空间

预检改为「还差多少」而不是整份大小：`missing = total - 已完成片字节`。续传时不再要求一整份的空闲空间。

## 3. 镜像源链

### 3.1 候选来源怎么来

不改 `UpdateChecker`：直接从 `browser_download_url` 模板化生成候选，无需额外 API 调用。

```kotlin
data class UpdateSource(val id: String, val label: String, val url: String)

// 直连永远排第一；镜像按常量表顺序追加
val SOURCES = listOf(
    UpdateSource("gh-proxy", "镜像 gh-proxy.com", "https://gh-proxy.com/"),
)
```

### 3.2 硬性安全约束（代码级，不只是文档）

**没有可用 digest 时，候选列表里只保留直连。** 判据是 `ResumableDownload.normalizeDigest(apkDigest) != null`。这条在构造候选时强制生效，并在卡片上说明「该版本未提供官方摘要，已禁用镜像」—— 避免「用第三方源 + 只校验长度」这个组合出现。

### 3.3 换源与续传的关系

`.meta.url` 记下这次分片集合来自哪个源。**只有选中的源与 `.meta.url` 一致时才续传**；否则清空重下。理由：两个源可能给出不同内容的同名文件，混着拼必然坏，而事后虽然有 digest 兜住，但代价是白下 46 MB。

### 3.4 失败与切换

- 一次下载尝试内**只用一个源**；该源失败（连接失败 / 4xx / 5xx）且还有下一个候选时，**自动换下一个源重试一次**（对用户表现为「点一次下载」）。
- 慢 ≠ 失败：直连虽然慢但能下，就不会自动切镜像。这是刻意的 —— 实测镜像更慢，自动切过去反而变糟。
- 全部候选都失败 → `Failed(canResume = false, reason = "所有下载源都失败")`，卡片同时给「打开发布页」。

### 3.5 开关与透明度

- 设置页新增开关「镜像加速（实验）」：**默认关闭**。关闭时候选列表只有直连。
- 下载中卡片与通知显示当前来源标签（`DownloadState.Running.sourceLabel`），用户随时知道包是从哪来的。直连时不显示（默认路径不打扰）。

## 4. 改哪些文件

| 文件 | 改动 |
|---|---|
| `update/ResumableDownload.kt` | 纯逻辑新增：分片规划、进度聚合、`.meta` v2 兼容、源链候选构造（全部可单测） |
| `update/UpdateStore.kt` | `.meta` v2 的读写与 `completedChunks` 记账 |
| `update/ApkDownloader.kt` | 并行调度器、降级到单连接、源链切换；v1 的顺序路径保留为降级路径 |
| `update/UpdateNotification.kt` | 进度通知文案带来源标签（可选） |
| `core/data/prefs` + 设置页 | 镜像开关 |
| `feature/settings/SettingsScreen.kt` | 来源标签 + 镜像开关 + 「无摘要已禁用镜像」提示 |
| `UpdateChecker.kt` | **不动** |
| 单测 | 分片规划边界（0/1/整除/余数）、进度聚合、`.meta` v1↔v2 兼容、候选构造与无 digest 时禁镜像、换源必须清空、降级判定 |

## 5. 明确不做

- 不做速度自适应（不根据实时速率动态增减连接数）；
- 不做分片级哈希（片内损坏只能靠整包 SHA-256 在最后发现，代价是重下）；
- 不做多源并行竞速（同时从直连和镜像拉同一片，取先到者）—— 实测镜像太慢，竞速只会浪费流量；
- 不引入第三方下载库（保持 OkHttp 单依赖）；
- 不改 v1 的状态机、卡片态、通知渠道与安装链路。

## 6. 测试策略

纯逻辑单测（沿用 `ResumableDownloadTest` / `DownloadProgressTest` 的写法，JUnit4、零 Android 依赖）：

| 单测项 | 覆盖 |
|---|---|
| 分片规划 | `total=0` / `total < chunkSize`（1 片）/ 整除 / 有余数（末片更短）|
| 进度聚合 | 已完成片 + 在飞片求和；片重下时进度回退；`total` 为 null 时不显示百分比 |
| `.meta` 兼容 | v1 无 `chunkSize` 的 JSON → 解析成 `chunkSize=0` 且不误判为「0 片完成」；v2 往返 |
| 源链候选 | 有 digest → 含镜像；无 digest / digest 格式非法 → 只有直连；模板拼 URL 正确 |
| 换源判据 | 选中源 == `.meta.url` 才允许续传，否则必须清空 |
| 降级判据 | Range 请求收到 200 → 放弃并行；500/IO → 不降级 |

真机验证清单（在 v1 那 8 项基础上加）：

1. 正常网络下 44.7 MB 下载耗时与 v1 对比（期望 3~6 倍提升）；
2. 8 并发期间 `adb shell dumpsys activity services` 确认 FGS 类型仍为 `dataSync`、通知进度平滑；
3. 下载中切飞行模式 → 恢复 → 点「重试」只补未完成的分片（对照卡片字节数不是从 0 开始）；
4. 「取消 → 继续下载」仍能从已完成分片续上；
5. 开启镜像开关后用 `dumpsys` 或卡片标签确认走的是镜像；关掉后回到直连；
6. 构造「无 digest」场景（本地改 `UpdateInfo.apkDigest = null`）确认镜像候选被禁用。

## 7. 风险

| 项 | 说明 | 应对 |
|---|---|---|
| 多连接可能触发服务端限速/封禁 | 8 条短连接对 46 MB 文件属温和用量，但不保证 GitHub/Azure 的看法 | 并发数是单个常量；异常时自动降级/失败由用户重试 |
| 手机侧并发收益未实测 | `vgate0` 隧道导致 shell 无法对照实验 | §6 真机清单第 1 项直接量 App 的耗时 |
| 公共镜像持续不可用 | 实测已经如此 | 默认关闭 + 只当退路；用户可自行改常量表 |
| 镜像被投毒 | 第三方源不可信 | 强制 digest 校验；无 digest 直接禁用镜像（代码级） |
| 随机写 + 崩溃导致「完成片」不完整 | `file.length()` 不再代表连续前缀 | 先 `fd.sync()` 再记账；末尾整包 SHA-256 兜底 |
