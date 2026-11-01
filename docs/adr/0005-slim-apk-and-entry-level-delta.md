# ADR-0005：更新下发做「瘦包 + 条目级增量」，不引入差分包引擎

- 状态：Accepted
- 日期：2026-11-01
- 相关文档：[渠道实测报告](../research/2026-11-01-update-distribution-channels.md)、[ADR-0003 自分发](0003-github-releases-self-distribution.md)、[ADR-0004 并发分片](0004-concurrent-chunked-download.md)
- 相关代码：`app/build.gradle.kts`（`abiFilters`）、`core/update/TransferTuning.kt`、`core/update/SourceProbe.kt`、`core/update/ApkPatch.kt`

## Context

ADR-0003 把分发定在 GitHub Releases，ADR-0004 用 8 路并发分片把吞吐提到 5.89 倍。但 2026-11-01 的全量复测显示，**这两条路径的收益已经到顶**：

| 观测 | 数值 | 含义 |
|---|---|---|
| 本机对 GitHub 的聚合吞吐上限 | 约 600 KB/s | 并发加到 16 条也不再增长 |
| 并发 1/4/8/16 的聚合吞吐 | 503/629/586/582 KB/s | **甜点是 4**，8 条已回退 |
| 14 个公共镜像候选 | 1 个可用（23 KB/s），13 个失效 | 镜像已是「更慢的路」而非退路 |
| v1.0.16 安装包 | 65.90 MiB | 其中 33.71 MiB 是模拟器架构 |
| 相邻两版逐条目差异 | 94.3% 字节未变 | 一次常规更新只需传 3.72 MiB |

结论：在链路已经打满的前提下，剩下的全部收益都在**减少要传的字节**，而不在调度算法。

## Decision

**四件事，按落地顺序：**

1. **只打 ARM 架构**（`ndk { abiFilters }` 固定 `arm64-v8a` + `armeabi-v7a`，`-PincludeX86=true` 可临时开）。
   MLKit 的两个 so 按 4 条 ABI 各打一份，其中 `lib/x86` + `lib/x86_64` 合计 33.71 MiB 只服务模拟器。
2. **分片大小随包体联动**（`chunkSizeFor`）：让总分片数不少于并发数的 3 倍，夹在 1–8 MiB。
   否则砍包之后 4 MiB 每片只剩 8 片，8 个 worker 一次发完，并发名存实亡。
3. **并发自适应且只增不降**（`adaptConnections`）：起手 4 条，速率增长 >= 12% 才 +1，上限 6。
   不收缩是刻意的：收缩要掐断在飞连接，那些分片的下半截白下，而多开两条的成本远低于重下。
4. **条目级增量更新**（`ApkPatch`）：只把变化的条目的**原始压缩字节**搬到旧包上，最后仍需整包 `sha256` 校验；
   **不做 bsdiff / hdiffpatch**（实测收益 11.2% 劣于条目级的 5.7%，还要背 JNI 与每 ABI 一份 so）。

同时在 `SourceProbe` 里把「连通即可用」换成**吞吐判据**（256 KiB 样本，低于 20 KB/s 判不可用）：
`ghproxy.net` 的 1 KB 探测能过（206 / 0.23 s），真拉 3 MB 只有 2 KB/s，这种源比直接失败更糟。

## 为什么增量更新必须「搬运压缩字节」而不是「重新打包」

APK 的 v2/v3 签名覆盖整个归档字节流（central directory 与 EOCD 都在签名范围内）。
解压→替换→重压会让签名立即失效、装不上。所以：

1. 服务端下发「新包里那几条 deflate 流的原始字节」+ 条目表（name/method/crc32/sizes/offset）。
2. 客户端 `RandomAccessFile` 复制旧包全量字节，按新包中央目录顺序原位覆盖或追加条目数据，
   再原样抄写新包的 central directory 与 EOCD。
3. 产出与官方 APK **逐字节相同**，签名自然有效。
4. 最后仍用 Release 的 `sha256` 校验整包；对不上就回退全量下载——**这道闸是整个方案的安全边界**。

## Consequences

正面：

- 安装包 **65.90 MiB → 32.10 MiB（-51.3%）**（实测 `assembleRelease` 产物，`lib/` 只剩 arm64-v8a 与 armeabi-v7a）。
- 一次常规更新的传输量从 69 MB 降到约 3.9 MB（按实测条目差异推算，**17.7 倍**）。
- 并发在好线路上比固定 8 快 7%~25%，且线路变差时不会更糟（只增不降，不会掐断在飞连接）。
- 移除 `ghproxy.net` 后不再有「探测通过但下载卡死」的支路。

代价与风险：

- **模拟器用户装不上正式包**；需要时用 `-PincludeX86=true` 单独出调试包。
- 条目级增量需要服务端在发版时额外生成清单；客户端要维护「本地基线 APK」的位置与生命周期。
- 增量拼装一旦出错，只能靠整包 `sha256` 发现并回退全量——所以兜底路径**不能删**。
- 自适应并发只增不降：线路中途变差时不会主动收敛（由源监视器的停滞/过慢判决接管换源）。

## 备选方案

1. **bsdiff / hdiffpatch 差分包**：否。实测 dex 的滚动匹配只有 6.4%（R8 每版重排符号），
   端到端 11.2% 劣于条目级的 5.7%，且要引入 JNI、每 ABI 一份 native so、差分包与本地版本的配对管理。
2. **多源并行竞速**：否。镜像最快 95 KB/s，竞速只会让慢源拖累快源。
3. **保持固定 8 并发**：否。实测 8 条（586 KB/s）已经低于 4 条（629 KB/s）。
4. **MLKit 改 unbundled**：暂缓。AAR 只差 11.3 MB，但 APK 里对应的 so 是 58.4 MiB；
   国内大量机型没有 GMS，这条路会让扫码/OCR 在纯国产 ROM 上直接不可用，需要先决定降级路径。
5. **自建 Cloudflare R2**：保留为下一步。`UPDATE_CF_MIRROR_PREFIX` 当前是空串（未启用），
   而同一 CF 边缘上 `unpkg` 实测有 123 KB/s，值得用一个下午验证。
## 实施记录（2026-11-01 当晚）

### 已完成

| 阶段 | 产出 | 验证 |
|---|---|---|
| P0 只打 ARM | `app/build.gradle.kts` 的 `ndk { abiFilters }` | `assembleRelease` 实测 **32.10 MiB**（原 65.90 MiB），APK 内只剩 `arm64-v8a` + `armeabi-v7a` |
| P1 镜像清理 | `DownloadSourceSet.MIRRORS` 移除 `ghproxy.net` | 该源实测 2 KB/s，却能让 1 KB 探测通过 |
| P1 吞吐判据 | `SourceProbe.decideProbeThroughput` | 256 KiB 样本、20 KB/s 下限；7 个单测 |
| P2 分片联动 | `TransferTuning.chunkSizeFor` | 让分片数不少于并发数 ×3，夹在 1–8 MiB；5 个单测 |
| P2 自适应并发 | `TransferTuning.adaptConnections` | 4 起手、上限 6、只增不降；7 个单测 |
| P3 决策层 | `DeltaPolicy.decide` | 基线摘要不符 / 增量过大 / 缺新包摘要 一律回退全量 |
| P3 拼装层 | `ApkAssembler.assemble` | 按区间表搬运，不重压不重排 |
| P3 清单解析 | `PatchManifest.parse` | 坏 JSON / 未知 kind / 非正长度 一律返回 null |
| 发版工具 | `scripts/make-apk-patch.py` | 用真实两个 APK 生成区间表 |

### 端到端验证（真实 APK，不是估算）

用 `dist/zhijian-themes-1.0.15-release.apk` 作基线、`app/build/outputs/apk/release/app-release.apk` 作新包：

```
清单: newSize=33664379  patchSize=4021300  segments=1481
patch sha256 校验: True
base  sha256 校验: True
重建逐字节相同: True
重建 sha256 == 清单: True
```

**patch = 3.84 MiB，占全量 11.9%。** 相比传整包 32.10 MiB，省掉 88%。

### 一个必须记住的坑：客户端不能自己决定条目位置

第一版拼装器是「把变化条目追加到旧包末尾、再重写中央目录」。这个实现**必然失败**：
APK 的 v2/v3 签名覆盖整个归档字节流，每个条目在文件里的偏移写死在中央目录里，
客户端一旦自己选位置，产出的字节就与官方包不同，签名立即失效。

正确分工：**服务端**（发版时手上有新旧两个完整包）算「新包每一段字节从哪来」，
输出区间表；**客户端**只按表搬运，一个字节都不多想。

### 第二个坑：APK Signing Block 不在任何条目里

最后一个 zip 条目结束到中央目录开始之间还有一段（本包实测 16384 字节），那是 APK Signing Block。
它不属于任何条目，必须**整段随增量下发**，否则区间表会缺一块、重建出来的包直接坏掉。
`scripts/make-apk-patch.py` 里的 `emit(last_end, ...)` 就是补这块的。

### 尚未完成

- **服务端发布链路**：`patch.json` 还没有随 Release 一起上传；`UpdateChecker` 也没去读它。
- **客户端接线**：`ApkDownloader` 尚未按 `DeltaPolicy` 走增量路径，也没有「保留上一版基线包」的生命周期管理。
- **真机验证**：32.10 MiB 这个包还没装到真机上跑过扫码 / OCR。
