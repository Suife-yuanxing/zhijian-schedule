# 教务脚本引擎（JS 适配脚本）设计

- 日期：2026-10-25
- 前置研究：[2026-10-19-multi-jw-import.md](../../research/2026-10-19-multi-jw-import.md)（下称「研究文档」，本文 § 编号沿用其分层：本设计即其 §4.1 L3 / §6 Phase 3 的落地）
- 范围：课表脚本的契约、运行时、分发、管理 UI；考试/成绩脚本、Ed25519 签名、QuickJS 引擎为范围外

## 0. 结论先行

1. 执行模型选**页面上下文注入**（拾光模式）：用户在登录 WebView 里进到课表页，点「脚本导入」，App 把 JS 注入当前页面执行，脚本读 DOM / 同源 `fetch()`（cookie 由页面自带），经 bridge 回传标准 JSON。不引入 QuickJS（研究文档 §5.4 警告的「桥面大」即指那种形态）。
2. 契约是**自定义最小契约**（研究文档 §5.4 结论）：脚本只暴露一个 `fetchSchedule(ctx)`，返回一个带 `apiVersion` 的信封；宿主严格校验后送进现有 `CourseDraft → ImportPreview → ImportGuard` 管线。字段名对齐小爱（`name/day/sections/weeks/...`），为将来可能的兼容层留映射。
3. 分发三件套一步到位：**内置脚本**（强智 jsxsd、正方新版各一份，由现有 Kotlin 解析逻辑忠实移植，兼作契约范本）+ **本地导入 .js** + **远程仓库**（index.json + SHA-256 校验）。远程 index 地址在脚本页可改（默认留空），便于指向公开仓库或本地 mock 调试。
4. 安全模型（研究文档 §4.7 的 MVP 版）：脚本只在用户显式点「导入」时于页面内执行一次；安装远程脚本需 HTTPS + SHA-256 对上 index 才落盘；manifest 的 `hosts` 非空时校验当前页面域名；bridge 只提供「回传结果」一个能力，无弹窗/存储/Prompt 桥；签名（Ed25519）后置。
5. 一期只做课表。信封预留 `fetchExams` 扩展位，schema 向后兼容靠 `apiVersion`。

## 1. 脚本契约 v1（apiVersion = 1）

### 1.1 脚本文件

一个 `.js` 文件 = manifest + 实现。文件头是块注释包 JSON（解析失败或字段缺失即拒绝）：

```js
/**
 * PAPER_SCRIPT_MANIFEST
 * {
 *   "id": "qiangzhi-jsxsd",
 *   "name": "强智教务（通用）",
 *   "version": 1,
 *   "apiVersion": 1,
 *   "hosts": [],                      // 空 = 通用脚本；非空 = 仅这些 host 可运行
 *   "entryHint": "登录后打开「我的课表」页面，再点导入"
 * }
 */
globalThis.__paperScheduleScript = {
  fetchSchedule: async (ctx) => ({
    courses: [ /* 见 1.3 */ ],
    totalWeeks: 20,          // 可选，纠正总周数
    semesterStart: "2026-08-31", // 可选，yyyy-MM-dd
    warnings: [ /* 可选，用户可读字符串 */ ]
  })
}
```

- `id` 全局唯一，`[a-z0-9-]`；同 id 高版本覆盖低版本（本地/远程均如此）。
- `apiVersion` 大于 App 支持值 → 拒绝并提示「脚本需要更新版本的 App」。
- `hosts` 非空且当前页面 host 不在列表 → 注入前拒绝（防错页误跑）。

### 1.2 ctx（宿主传给 fetchSchedule）

```js
ctx = { url: "<当前页面URL>", totalWeeks: <课表设置总周数> }
```

刻意最小：无弹窗桥、无存储桥、无定时桥。脚本要更多页面就自己同源 `fetch()`。

### 1.3 课程对象与校验

字段对齐小爱命名；宿主逐条校验，非法条目丢弃并计 warning，不整体失败：

| 字段 | 类型 | 规则 |
| --- | --- | --- |
| `name` | string | 必填，trim 后 1..30 字 |
| `day` | int | 1..7（周一~周日） |
| `startSection` / `endSection` | int | 1..15；end ≥ start |
| `weeks` | int[] 或 string | 数组直接用；字符串支持 `1-16周`、`1-16(单)`、`1-8,10-16周` 等现有周次语法（复用现有解析器） |
| `room` / `teacher` | string | 可选，≤30 字 |

信封级校验：`courses` 缺失或为空 → 失败「解析到 0 门课，请确认已打开课表页」（研究文档 §4.6，绝不自动应用空结果）；`courses.length > 500` → 拒绝（防失控）；`totalWeeks` 提供时须 1..30。

### 1.4 错误分类

脚本抛异常 / bridge `onError` / 超时（20s）/ 输出校验失败 → 归一为用户可读消息，走 JwImportScreen 现有 NoticeBanner；不暴露 JS 堆栈原文，但 debug 构建在日志里带详情。

## 2. 运行时

- `core/jw/script/JwScriptManifest.kt`：manifest 解析与校验（纯 JVM，可测）。
- `core/jw/script/JwScriptOutput.kt`：信封/课程校验、weeks 归一化 → `List<CourseDraft>`（纯 JVM，可测）。
- `core/jw/script/ScriptStore.kt`：已安装脚本登记（`filesDir/jw-scripts/scripts.json`，kotlinx.serialization）：id/name/version/source(BUILTIN|LOCAL|REMOTE)/sha256/entryHint/hosts/enabled/installedAt + 脚本文件落盘 `filesDir/jw-scripts/<id>.js`。纯函数 + 文件 IO，可测。
- `core/jw/script/JwScriptRunner.kt`（Android）：主线程 `webView.evaluateJavascript` 注入 loader（源码以 JSON 字符串传入、IIFE 包裹、`"use strict"`），脚本设置 `globalThis.__paperScheduleScript` 后 loader 调 `fetchSchedule(ctx)`，经 `@JavascriptInterface PaperBridge.onResult/onError` 回传；`suspendCancellableCoroutine` + 20s 超时。仅主 frame（学校 iframe 嵌套场景，脚本可自行 `frames[i]` 访问同源子框，写入契约文档）。
- 内置脚本：`app/src/main/assets/jw/scripts/qiangzhi-jsxsd.js`、`zhengfang-new.js`，忠实移植 `QiangZhiAdapter` / `ZhengFangNewAdapter` 的解析逻辑（页面上下文版：强智直接读 `#kbtable` DOM 矩阵；正方从页面读 `xnm/xqm` 后同源 POST `kbList`）。assets 属于 APK 内信任源，不做哈希校验。

## 3. 分发

### 3.1 远程仓库（index.json）

```json
{
  "indexVersion": 1,
  "scripts": [
    { "id": "qiangzhi-jsxsd", "name": "强智教务（通用）", "version": 2,
      "url": "https://.../qiangzhi-jsxsd.js", "sha256": "<hex>",
      "hosts": [], "minAppVersion": "1.1.0", "entryHint": "..." }
  ]
}
```

- `core/jw/script/ScriptIndex.kt`：解析 + 校验（url 必须 https、sha256 64 位 hex、minAppVersion 语义化比较）；`ScriptDownloader`：OkHttp 拉取 → SHA-256 对比（`MessageDigest`，纯函数可测）→ 通过才落盘覆盖同 id 低版本。
- index 地址存 DataStore（`SettingsStore` 新增 `jwScriptIndexUrl`，默认空）。为空时脚本页显示「未配置脚本仓库」+ 编辑入口；指向 mock server 可做 E2E。
- 建议把 `scripts/index.json` + 脚本放在独立公开仓库（如 `Suife-yuanxing/zhijian-scripts`）；App 仓库的 `scripts/` 目录出一份可直接拷贝的成品与 README。**远程信任 = HTTPS + SHA-256 + 安装时用户确认 + hosts 守卫；Ed25519 签名后置（范围外）。**

### 3.2 本地导入

脚本管理页「导入 .js」→ 文件选择器 → manifest 校验通过即入列（source=LOCAL，无哈希要求，信任 = 用户主动行为）。

## 4. UI / UX

- **JwImportScreen**：WebView 就绪后新增「脚本导入」动作 → 脚本选择对话框（当前 host 命中的脚本排前并标注「适用于当前站点」，`hosts` 为空的通用脚本次之，未命中且 hosts 非空的置灰）→ 运行（转圈 + 20s 超时）→ 成功进现有 ImportPreview；失败 NoticeBanner 分类提示。manifest.entryHint 首次运行前展示。
- **脚本管理页** `ScriptManageScreen`（`feature/jw/`）：入口在 JwImportScreen 顶栏（脚本图标）。列表项：名称/版本/来源徽章（内置/本地/远程）/启用开关；操作：更新检查（拉 index 比对 version）、删除（内置不可删，仅停用）、导入 .js、index 地址编辑（更多区）。
- **导航**：`Routes.PROFILE_JW_SCRIPTS`，沿用 ProfileEntryPage 容器过渡；`AppRoot.kt` 含用户未提交 WIP，只做必要追加、不单独提交。

## 5. 与现有管线的对接

脚本运行由 Screen 发起（WebView 归 Screen 所有），结果 `List<CourseDraft>` 交回 `JwImportViewModel` 既有预览/应用路径；`ImportGuard` 护栏（首同步不删、增删比例熔断）对脚本来源一视同仁。脚本路径不出现在任何自动同步（`backgroundSync=false` 语义，研究文档 §5.2）。

## 6. 测试

- 纯 JVM：manifest 解析/拒绝路径、输出校验与 weeks 归一化（复用现有周次解析用例语法）、index 解析 + sha256 + 版本比较、ScriptStore 增删改（临时目录）。
- WebView 注入不可 JVM 单测 → mock-jw E2E：`tools/mock-jw` 已有强智/正方课表页，内置脚本在其上全链路（登录 → 脚本导入 → 预览 → 落库），真机过一遍；docs/testing/mock-jw.md 补「脚本导入」一节。
- 回归：现有 35 suites / 322 tests 全绿 + `:app:assembleRelease` 通过。

## 7. 范围外（明确不做）

考试/成绩脚本（`fetchExams` 扩展位仅预留）、Ed25519 签名与审核体系、QuickJS 引擎与后台脚本同步、脚本兼容小爱/拾光桥、脚本市场远端托管服务、debug 实验室。
