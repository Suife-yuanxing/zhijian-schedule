# 应用内更新：全渠道下发方案实测与更优路径（2026-11-01）

> 目标项目：纸间课表（Android / Kotlin）｜当前包 v1.0.16 / 69,105,445 B（65.90 MiB）
> 前置文档：[ADR-0003 自分发](../adr/0003-github-releases-self-distribution.md)、[ADR-0004 并发分片](../adr/0004-concurrent-chunked-download.md)、[下载加速设计 v2](../specs/2026-10-15-update-download-acceleration-design.md)
> 本文件回答两件 ADR 没有回答的事：**还有没有更快的下发方式**、**为什么现在还是慢**。
> 证据分级：**【实测】**本机可复现命令与数字；**【源码】**仓库代码路径可查；**【公开】**官方文档/上游仓库；**【估算】**基于实测的推算，需真机复验。

---

## 0. 一页结论

1. **瓶颈从来不是「下载器不够聪明」，而是「要传的字节太多 + 跨境链路太窄」**。实测本机对 GitHub 的吞吐上限约 **600 KB/s**，加并发到 16 条也不再增长；69 MB 的包在这个上限下注定要 2 分钟以上。**【实测】**
2. **包里 51% 是模拟器架构**。`lib/x86` + `lib/x86_64` 合计 33.71 MiB，真机和绝大多数平板永远是 ARM，这 33.71 MiB 是纯浪费。砍掉它，包从 65.55 MiB 降到 **31.84 MiB（-51%）**，一行 Gradle 配置的收益超过任何下载器优化。**【实测】**
3. **公共 GitHub 镜像已经整体失效**。本轮扫 14 个候选，只有 `gh-proxy.com` 可用（单连接 23 KB/s），其余 13 个全部 000/404/403/429。**镜像现在是「更慢的路」，不是「退路」**——ADR-0004 的「镜像不能当主力」结论依然成立，且比当时更成立。**【实测】**
4. **gh-proxy 的缓存 TTL 只有 60 秒**（`Cache-Control: public, max-age=60, s-maxage=60`），所以「新版本发布后集中下载」这个最需要缓存的场景，它几乎全部回源。这条解释了它为什么慢得像没缓存。**【实测】**
5. **增量更新（zip 条目级）实测可到 5.7%**：v1.0.15 → v1.0.16 只有 `classes.dex`、`resources.arsc`、`assets/jw/schools.tsv` 三个条目实质变化，压缩后合计 3.72 MiB。**不需要 bsdiff、不需要 JNI、不需要重新签名**——只要服务端下发「变化条目的原始压缩字节 + 重组清单」，客户端按新包的 zip 结构原样拼装，产出的文件与官方 APK 逐字节相同，签名自然有效。**【实测 + 估算】**
6. **当前固定 8 连接的取值在这条线路上已经过时**。实测 1/4/8/16 条并发分别是 503 / 629 / 586 / 582 KB/s：**甜点是 4**，8 条开始回退。ADR-0004 里「8 并发 = 5.89×」是当年代理线路的结论，今天不复现。**【实测】**
7. **落地顺序（按收益/成本比）**：① 砍 x86/x86_64 → ② MLKit 改 unbundled → ③ 自适应并发 + 分片大小联动 → ④ 条目级增量更新 → ⑤ 自建对象存储（只在①②③④都做完还不够时才做）。前三步零基础设施成本，一二两步就能把 69 MB 变成 31.84 MiB、再变成约 21 MiB。

---

## 1. 尺子：先测本机天花板，再谈方案

不谈线路谈方案的调研都是耍流氓。以下全部在**本机（Windows / Clash Verge mixed 7897）**于 2026-11-01 实测，命令可复现。

### 1.1 各源单连接吞吐

| 路径 | HTTP | 首字节 | 单连接速度 | 判定 |
|---|---|---:|---:|---|
| `github.com` → release-assets（**不经代理**） | — | 超时 | **0 B/s** | 完全不通 |
| `github.com` → release-assets（**经 Clash**） | 206 | 0.03 s | **503 KB/s** | 可用，这是本机对 GitHub 的天花板 |
| `gh-proxy.com` | 206 | 0.03 s | **23 KB/s** | 可用但比代理慢 22 倍 |
| `ghproxy.net` | 206 | 0.23 s | **2 KB/s** | 名义可用，实际不可用 |
| `ghfast.top` / `ghp.ci` / `ghproxy.cc` / `gh.llkk.cc` / `gh.api.99988866` / `ghproxy.net.cn` / `github.moeyy.xyz` / `raw.kkgithub.com` / `hub.gitmirror.com` | 000 | — | 0 | 连接超时 |
| `moeyy.cn/gh-proxy` | 404 | — | 0 | 路径规则已变 |
| `gh.ddlc.top` | 429 | — | — | 限流 |
| `ghproxy.homeboyc.cn` | 403 | — | — | 拒绝 |

> 对照：`api.github.com`（**更新检查**走的路径）不经代理直连 **200 / 0.8 s**，稳定可用。所以「检查更新」和「下载安装包」是两个完全不同的网络问题：前者好的，后者废的。

### 1.2 并发放大曲线（关键反直觉数据）

同一文件、同一时刻、8 秒窗口，按 `Range` 切 2 MB 段并发拉：

| 并发数 | 经 Clash 直连 GitHub | `gh-proxy.com` 镜像 |
|---:|---:|---:|
| 1 | 503 KB/s | 23 KB/s |
| 4 | **629 KB/s**（+25%） | 37 KB/s |
| 8 | 586 KB/s（**回退**） | 95 KB/s |
| 16 | 582 KB/s（**回退**） | — |

**读法**：
- 直连线路的**总带宽已经被打满**（约 600 KB/s），并发只在前 4 条有效，之后是连接争抢与限流惩罚。
- 镜像侧并发确实能放大（23 → 95 KB/s，4.1×），但**绝对值太低，放大也没用**。
- **结论**：ADR-0004 的「8 路并发 = 5.89×」是 2026-10-15 那条代理线路的结果；在今天这条线路上，**8 是次优解，4 才是甜点**。写死常量必然过时。

### 1.3 其它边缘网络的参考值

| 站点 | 边缘 | 速度 | 用途 |
|---|---|---:|---|
| `unpkg.com` | Cloudflare | 123 KB/s | 判断「CF 边缘到本机」的真实能力 |
| `registry.npmmirror.com` | 国内 | 24–37 KB/s | 判断「国内线路」的真实能力 |
| `gh-proxy.com`（命中 CF 缓存 `CF-Cache-Status: HIT`） | Cloudflare HKG | 23 KB/s | 同一条 CF 边缘，比 unpkg 慢 5 倍 |

**这条最重要**：同样是 Cloudflare 边缘节点，`unpkg` 有 123 KB/s，`gh-proxy` 只有 23 KB/s。说明慢的不是 CF、不是本机，**是 gh-proxy 自己的策略（含 60 秒缓存 TTL）**。这直接决定了下一节的取舍：**自建 R2 是在同一个 CF 网络上，能不能跑到 123 KB/s 级，是一个可以用一个下午验证的假设**，而不是拍脑袋。

---

## 2. 渠道矩阵：15 条路线的实测结论

| # | 方案 | 技术形态 | 本机实测 | 大陆可用性 | 成本 | 落地工作量 | 判定 |
|---|---|---|---|---|---|---|---|
| 1 | **GitHub Releases 直连** | HTTPS + Range | 无代理 0 / 代理 503 KB/s | 分地区，且极度依赖代理 | ¥0 | 已完成 | 保留为「官方源」，不作主路径 |
| 2 | **公共加速镜像** | 反代前缀 | gh-proxy 23 KB/s，13/14 全挂 | 已完成 | ¥0 | 已完成 | **降级为最后退路**（见 §3） |
| 3 | **自建 Cloudflare R2 + 自定义域** | S3 兼容对象存储 | CF 边缘参考 123 KB/s | 需海外域名 | 存储 $0.015/GB，出口 $0 | 半天 | **值得做**，唯一能自己控住的源 |
| 4 | **自建 Workers 反代** | Worker 流式转发 | — | 同 3 | 免费额度内 | 2 小时 | 与 3 二选一，Workers 更省事 |
| 5 | **国内对象存储 + CDN**（七牛/多吉/阿里/腾讯） | OSS + CDN | 未测（需备案域名） | **最好** | 约 ¥0.11–0.29/GB 流量 | 1 天（含备案） | 用户量上来后才划算 |
| 6 | **应用商店上架** | 商店分发 | — | 最好 | 个人主体门槛高 | 数周审核 | 与日更节奏冲突，ADR-0003 已否决 |
| 7 | **第三方内测分发平台** | 托管分发 | — | 好 | 免费额度有限 | 1 小时 | 适合灰度，不作主通道 |
| 8 | **Gitee Releases 镜像** | 国内代码托管 | — | 好 | ¥0 | 发布多推一次 | 附件限 100 MB（GVP 200 MB），69 MB 卡得刚好 |
| 9 | **增量更新：zip 条目级** | 自定义清单 | 实测 5.7% 体积 | 与所选源无关 | ¥0 | 2–3 天 | **性价比最高**（见 §4） |
| 10 | **增量更新：bsdiff / hdiffpatch** | native 差量 | 估算 11%（劣于 9） | 同上 | ¥0 | 5 天 + JNI | 不做 |
| 11 | **AAB / split APK 按 ABI 分发** | 构建期拆分 | arm64 单独 22.24 MiB | 与所选源无关 | ¥0 | 半天 | **必做**（见 §3.1） |
| 12 | **多源并行竞速** | 分片级 race | 镜像太慢，竞速只浪费流量 | — | ¥0 | 1 天 | 不做 |
| 13 | **P2P / BT / IPFS** | DHT / IPFS 网关 | — | 差 | ¥0 | 很高 | 不做 |
| 14 | **Play 的 in-app updates** | Play Core | — | **国内无 Google Play** | ¥0 | — | 不适用 |
| 15 | **静默安装 / Device Owner** | PackageInstaller | — | — | — | — | 不可能（普通应用无权限） |

---

## 3. 真正的杠杆：把包变小

### 3.1 APK 体积解剖（v1.0.16，实测）

```
条目 1481 个｜解压后 71.2 MiB｜压缩后 65.5 MiB

group                     raw_MB   cmp_MB    n
lib/x86                    16.92    16.92    6
lib/x86_64                 16.79    16.79    6
lib/arm64-v8a              15.32    15.32    6
lib/armeabi-v7a             9.60     9.60    6
dex                         7.77     3.44    1
assets/mlkit-*              3.21     2.68   28
res/                        0.94     0.44 1271
resources.arsc              0.23     0.23    1
```

**lib 里只有两个 so 有分量**：

| 文件 | x86 | x86_64 | arm64-v8a | armeabi-v7a | 合计 |
|---|---:|---:|---:|---:|---:|
| `libmlkit_google_ocr_pipeline.so` | 11.09 | 11.03 | 10.55 | 6.47 | **39.14 MiB** |
| `libbarhopper_v3.so` | 5.84 | 5.64 | 4.72 | 3.09 | **19.29 MiB** |
| 其余 4 个 so | 0.0 | 0.1 | 0.1 | 0.0 | 0.2 MiB |

MLKit 一家占了整个 lib 的 **99.7%**。这不是「应用大了」，这是**把 Google 的 OCR 引擎按 4 个 CPU 架构各打包了一份**。

**砍法一：去掉 x86 / x86_64**（模拟器架构，真机零影响）

```kotlin
// app/build.gradle.kts defaultConfig
ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
```

| 产物 | 体积 | 占全量 |
|---|---:|---:|
| 全量（现状） | 65.55 MiB | 100% |
| 只留 arm64 + armv7 | **31.84 MiB** | **49%** |
| 只留 arm64（split APK） | **22.24 MiB** | **34%** |

> 代价：模拟器用户装不上。项目 `minSdk = 26`、真机为主，这个代价可以接受；真要留，用 split 出多包，arm64 用户拿 22.24 MiB。

**砍法二：MLKit 改 unbundled 依赖**（模型与引擎交给 Google Play Services，首次用时下载）

| 依赖 | AAR 体积 |
|---|---:|
| `com.google.mlkit:barcode-scanning:17.3.0`（bundled，现状） | **9.90 MB** |
| `com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.0`（unbundled） | **0.50 MB** |
| `com.google.mlkit:text-recognition-chinese:16.0.1`（bundled，现状） | **2.04 MB** |
| `com.google.android.gms:play-services-mlkit-text-recognition-chinese:16.0.1`（unbundled） | **0.08 MB** |

**AAR 只差 11.3 MB，但 APK 里对应的 so 是 58.4 MiB**——unbundled 版本把 `libmlkit_google_ocr_pipeline.so` 与 `libbarhopper_v3.so` 整体移交给 GMS。**【估算】**真机上大约能再拿下十几到二十几 MiB，具体值必须构建一次实测，不能照抄。

> 代价：**国内大量机型没有 GMS**。这条路会让「扫码 / OCR 识图」在纯国产 ROM 上直接不可用。**要么放弃 unbundled，要么为国产 ROM 保留一条降级路径**（例如 ZXing 纯 Java 解二维码 + 去掉 OCR）。这是产品决策，不是技术决策。

### 3.2 三条砍法的叠加效果

| 阶段 | 体积 | 相对现状 | 额外成本 |
|---|---:|---:|---|
| 现状 | 65.55 MiB | — | — |
| ① 去 x86/x86_64 | 31.84 MiB | **-51%** | 一行 Gradle |
| ② + MLKit unbundled（若可接受无 GMS 降级） | **约 12–20 MiB** | **-70%~-82%** | 依赖替换 + 降级路径 |
| ③ + arm64 only split | 22.24 MiB，叠加②后约 6–12 MiB | — | splits 配置 |

**这比任何下载器优化都值钱。** 同样 600 KB/s 的线路：65 MiB 要 110 秒，31.84 MiB 要 54 秒，12 MiB 要 20 秒。

---

## 4. 增量更新：条目级 wins，bsdiff loses

### 4.1 实测：v1.0.15 → v1.0.16 到底变了什么

逐条目比对两个 APK 的 CRC 与大小：

```
新包压缩总量          65.55 MiB
完全相同的条目        61.82 MiB   <- 94.3%
发生变化的条目         3.72 MiB   <- 5.7%
新增条目               0.00 MiB
删除条目               0.00 MiB
```

具体变化：

| 条目 | 新 | 旧 | 说明 |
|---|---:|---:|---|
| `classes.dex` | 3.435 MiB | 3.325 MiB | 代码，几乎每版必变 |
| `resources.arsc` | 0.229 MiB | 0.228 MiB | 资源表，几乎每版必变 |
| `assets/jw/schools.tsv` | 0.044 MiB | 0.026 MiB | 学校目录 |
| `assets/dexopt/baseline.prof` | 0.006 MiB | — | 基线 profile |
| `AndroidManifest.xml` | 0.005 MiB | — | — |
| `META-INF/services/pd.x` 等 | <0.001 MiB | — | R8 改名副作用 |

**所以「下一版更新需要传 3.72 MiB」是可实测、可复现的事实，不是估算。**

### 4.2 为什么不做 bsdiff / hdiffpatch

对三个变化文件做滚动块匹配（32 B 块 / 8 B 步长）：

| 文件 | 旧 | 新 | 匹配上 | 估算差量 |
|---|---:|---:|---:|---:|
| `classes.dex` | 7.54 MiB | 7.77 MiB | 0.50 MiB | 7.42 MiB（**95.5%**） |
| `resources.arsc` | 0.23 MiB | 0.23 MiB | 0.06 MiB | 0.17 MiB（74.5%） |
| `assets/jw/schools.tsv` | 0.11 MiB | 0.20 MiB | 0.03 MiB | 0.17 MiB（86.8%） |
| **合计** | | | | **约 7.76 MiB = 全量 11.2%** |

**bsdiff 路线劣于条目级路线**（11.2% vs 5.7%），因为它对 dex 内部重排无能为力——R8 每版重排符号顺序，字节层面几乎全变。还要额外付出：JNI 层、每条 ABI 一份 native so、差分包与本地的版本配对管理。

**判定：条目级方案已经吃掉主要收益，bsdiff 是负收益的复杂度。**

### 4.3 条目级增量怎么落地（关键：不能重新打包）

**最容易踩的坑**：把旧 APK 解压、替换变化条目、再压回去 → **APK 签名（v2/v3）立即失效，装不上**。

正确做法（`archive-patcher` 的思路，但可以简化）：

1. **服务端**发布时，对「上一版 → 本版」生成 `patch.json` + 变化条目的**原始压缩字节**（直接截取新 APK 里那段 deflate 流，不解压不重压）。
2. 清单里记：每个条目的 `name / method / crc32 / compressedSize / uncompressedSize / localHeaderOffset`，以及变化条目的数据块偏移与长度。
3. **客户端**用 `RandomAccessFile` 复制旧 APK 全量字节 → 按新包中央目录顺序把条目数据**原位覆盖**或**追加**，并**原样抄写**新包的 central directory 与 EOCD。
4. 产出文件与官方 APK **逐字节相同**（压缩字节没被触碰），签名自然有效。
5. **最后一道闸**：现有 `verifyFile` 用 Release 里的 `sha256` 校验整包，对不上就回退全量下载——**这条兜底必须保留，它是整个方案的安全边界**。

> 为什么可行而不是空想：`archive-patcher`（Google 开源，AOSP `platform/external/archive-patcher`）用的正是同一套原理——File-by-File patch 产出的是逐字节相同的归档。区别在于它用 bsdiff 编码 delta，这里用「原始压缩字节直传」，省掉 native 层。

| 方案 | 传输量 | 实现复杂度 | 签名风险 |
|---|---:|---|---|
| 全量 | 69.11 MB | 0 | 无 |
| **条目级增量** | **3.90 MB** | 中（清单 + 拼装） | 低（sha256 兜底） |
| 条目级 + bsdiff on dex | 约 3.2 MB | 高（JNI） | 中 |

从 69 MB 到 3.9 MB 是 **17.7×**。这才是「更快的下载」真正该拿的分。

---

## 5. 下载器本身的三个洞（现在就能改）

代码位置：`core/update/`

### 5.1 `MAX_CONNECTIONS = 8` 是过时常量

`ChunkedTransfer.kt:17`：固定 8。实测甜点是 4（629 KB/s），8 反而 586。建议改成**自适应**：起 4 条，每 3 秒看聚合速率，仍在增长（>15%）就 +2，下降（<-10%）就回退到上一档并锁定。收益 +7%~25%，约 60 行，纯逻辑可单测。

### 5.2 分片大小与包体脱钩

`ResumableDownload.kt` 的 `DEFAULT_CHUNK_BYTES = 4 MiB`。对 69 MiB 的包是 17 片，8 worker 抢完一轮只剩 1 片，**尾巴又长又慢**。而一旦先做了 §3.1 砍包（31.84 MiB），4 MiB/片只剩 8 片，**8 worker 一次发完，并发彻底失效**。

建议：`chunkSize = clamp(total / (workers * 3), 1 MiB, 8 MiB)`。69 MiB + 4 并发 → 5.7 MiB/片（12 片）；31.84 MiB + 4 并发 → 2.6 MiB/片（12 片）。**两个改动必须一起做**，否则先砍包会让并发优化白做。

### 5.3 镜像候选表已经全是死链

`DownloadSourceSet.kt:41-50` 现在挂的是 `gh-proxy.com` 与 `ghproxy.net`。实测后者只有 2 KB/s，**保留它等于保留一条「看起来成功、实际卡死」的支路**——比直接失败更糟，因为 `SourceProbe` 的 1 KB 探测能通过（连通 ≠ 能下载），然后掉进去卡 25 秒等 watchdog。

建议：
1. 从候选表移除 `ghproxy.net`。
2. `SourceProbe` 增加**吞吐探测**而不只是**连通探测**：拉 256 KB 计时，低于 20 KB/s 判为不可用。1 KB 的探测骗得过延迟，骗不过吞吐。
3. 自建前缀 `UPDATE_CF_MIRROR_PREFIX`（当前构建产出是空串，即未启用）填上之后，R2 排第一才真正有意义。

---

## 6. 落地顺序（建议按此排期）

| 阶段 | 动作 | 预期效果 | 工作量 | 需要真机验证 |
|---|---|---|---|---|
| **P0** | 去掉 x86/x86_64（+ 可选 split） | 69.11 → **约 33 MB**（-51%） | 2 小时 | 装包、扫码、OCR 各跑一次 |
| **P1** | 移除 ghproxy.net；SourceProbe 加吞吐判据 | 不再掉进「假成功」支路 | 半天 | 弱网重试一次 |
| **P2** | 自适应并发 + 分片大小联动 | +7%~25%，且不随包体缩水失效 | 1–2 天 | 4 种线路各测一次 |
| **P3** | 条目级增量更新 + sha256 兜底 | 单次更新 **69 MB → 约 3.9 MB**（17.7×） | 2–3 天 | 连续跨 2 个版本升级 |
| **P4** | 自建 R2（填 UPDATE_CF_MIRROR_PREFIX） | 摆脱 gh-proxy；CF 边缘实测有 123 KB/s 的能力 | 半天 + 域名 | 全国 3 地各测一次 |
| **P5** | 评估 MLKit unbundled | 再省 10–20 MiB | 1–2 天 | **国产 ROM 无 GMS 场景必测** |
| — | 国内 OSS+CDN / 应用商店 | 最好的体验 | 数天~数周 | 用户量到规模再谈 |

**P0 是这一整页文档里唯一「改一行、立省一半」的动作。**

---

## 7. 明确不做

1. **不做多源并行竞速**：镜像最快 95 KB/s，竞速只会让 95 KB/s 那条拖慢 629 KB/s 那条。
2. **不做 bsdiff / hdiffpatch**：实测收益（11.2%）劣于条目级（5.7%），且必须背 JNI 与 native so。
3. **不做 P2P / BT / IPFS**：国内 DHT 不可用，客户端复杂度远超一个 APK。
4. **不做「客户端重新打包 APK」**：签名必然失效，这不是优化是搞坏。
5. **不为「看起来快」做进度平滑**：现有「换源后进度往回跳」是诚实行为，保留。
6. **不删除「打开发布页 / 浏览器下载」兜底**：它是所有自动路径都失效时唯一还活着的东西。

---

## 8. 复现命令

```bash
# 1. 各源单连接吞吐
URL="https://github.com/Suife-yuanxing/zhijian-schedule/releases/download/v1.0.16/PaperTimetable-v1.0.16.apk"
curl -sL -o /dev/null --max-time 10 -r 0-2999999 -w '%{http_code} %{speed_download}\n' "https://gh-proxy.com/$URL"
curl -sL -x http://127.0.0.1:7897 -o /dev/null --max-time 10 -r 0-2999999 -w '%{http_code} %{speed_download}\n' "$URL"

# 2. 并发放大曲线（n = 1/4/8/16）
for i in $(seq 0 7); do
  off=$(( i * 4000000 )); end=$(( off + 4000000 - 1 ))
  curl -sL -x http://127.0.0.1:7897 -o /dev/null --max-time 7 -r $off-$end "$URL" &
done; wait

# 3. 镜像缓存 TTL
curl -sI -L --max-time 15 -r 0-1023 "https://gh-proxy.com/$URL" | grep -iE 'cache-control|cf-cache-status|age'

# 4. APK 条目级差异：用 Python zipfile 按 CRC 逐条比对两个 APK
```

---

## 9. 一句话给下一次改动的执行者

> **先别碰下载器。先把 33.71 MiB 的模拟器 so 从包里拿掉，再把 94.3% 没变的字节从传输里拿掉。这两件事做完，同一个 600 KB/s 的破线路能从「2 分钟」变成「7 秒」，任何并发调参都追不上这个数量级。**
