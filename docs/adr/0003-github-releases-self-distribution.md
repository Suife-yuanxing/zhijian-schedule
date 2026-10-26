# ADR-0003：应用内更新走 GitHub Releases 自分发，不进应用商店

- 状态：Accepted
- 日期：2026-10-13
- 相关文档：[应用内更新下载设计](../specs/2026-10-15-in-app-update-download-design.md)、[下载加速设计](../specs/2026-10-15-update-download-acceleration-design.md)、[README](../../README.md)
- 相关代码：`core/update/UpdateChecker.kt`（`GITHUB_REPO` 常量）、`core/update/ApkInstaller.kt`、`feature/update/UpdateDialog.kt`、`app/src/main/AndroidManifest.xml`（FileProvider）

## Context

Android 应用在大陆的常规分发路径是各家应用商店（华为/小米/OPPO/vivo/应用宝等）。要把 App 上架，通常需要企业主体、软件著作权、备案等资质，审核周期以周计，且每个商店都要单独维护一套物料与更新流程。

本项目的现实约束是：**个人开发者主体**、无推广预算、迭代节奏以天计（v1.0.0 → v1.0.12 只用了 13 天）。README 的路线图也写明厂商合作「需企业主体」。

同时代码已经托管在 GitHub，早期实现是"点检查更新 → 跳到浏览器发布页 → 用户自己在 GitHub 页面里找 APK、下载、再手动打开安装"，下载过程完全脱离应用，失败只能从头再来且看不到进度。

## Decision

**不进应用商店，用 GitHub Releases 做自分发，并把下载环节收回应用内。**

- 更新检查直接读 GitHub 的 Release API：`https://api.github.com/repos/Suife-yuanxing/zhijian-schedule/releases/latest`（`core/update/UpdateChecker.kt` 的 `GITHUB_REPO` 常量）。
- **版本判定规则**：取 `tag_name` 去掉 `v` 前缀，与本地 `BuildConfig.VERSION_NAME` 做**逐位数值比较**（`isNewer`），所以发布 tag 必须写成 `vX.Y.Z`。
- **资产选取规则**：在 `assets` 里取**第一个文件名以 `.apk` 结尾**的资产，拿它的下载地址与大小；`digest` 字段（`sha256:...`）用作下载完成后的完整性校验，旧 Release 没有该字段时跳过校验。
- 下载、校验、安装都留在应用内：`core/update/ApkDownloader.kt` 管状态，`core/update/UpdateStore.kt` 管文件与断点元数据，前台服务渲染通知，`ApkInstaller.kt` 通过 FileProvider 交给系统安装器。
- **保留「打开发布页」作为降级退路**：任何一环失败时，用户仍能自己去 GitHub 下载。

## Consequences

正面：

- **零上架成本、零审核周期**：发版动作就是打 tag + 传 APK，当天可发。
- 不需要企业主体与备案，个人项目能长期跑下去。
- 版本与发布说明由开发者完全掌控，Release notes 直接展示给用户；更新检查不依赖任何第三方 SDK。
- 与 [ADR-0001](0001-local-first-data-architecture.md) 的无服务端取向一致：整个分发链路只需要一个 GitHub 仓库。

必须接受的代价：

- **国内网络对 GitHub 的连通性不稳定**，直连 release-assets 的下载速度可能只有两位数 KB/s——这是产品体验上最实在的代价，直接催生了 [ADR-0004](0004-concurrent-chunked-download.md)。
- **安装必须用户手动确认**：普通应用拿不到静默安装权限，只能引导用户授权「安装未知应用」（已接入 FileProvider 与引导流程）。
- 没有应用商店的流量入口与自动更新通道，也没有商店的增量更新能力，每次都要下整个 APK。
- 放弃国内商店上架，也就放弃了通过厂商生态（如 OPPO 流体云之外的厂商合作）获取用户——路线图里的厂商接入仍写着"需企业主体"。
- 更新说明得自己维护，所以 [CHANGELOG.md](../../CHANGELOG.md) 同时承担了 Release notes 的数据源。

## 备选方案

1. **上架国内应用商店**：否。需要企业主体、软著与备案，个人开发者不可行；且审核周期与项目的日更节奏冲突。
2. **自建更新服务（自有域名 + 对象存储）**：否。要域名、备案、证书与运维，收益仅是一点点速度，与"无服务端"的架构取向直接冲突；真需要加速时用镜像源链解决（见 ADR-0004）。
3. **应用内静默更新（PackageInstaller 静默安装）**：否。需要系统级权限或设备所有者（Device Owner）身份，普通应用拿不到。
4. **只在 GitHub 页面手动下载**：否（这是被替换掉的旧实现）。下载脱离应用、没有进度、失败要重来，体验代价太高。
