# ADR-0002：教务导入采用「可下载 JS 脚本引擎 + WebView 页面上下文」

- 状态：Accepted
- 日期：2026-10-25
- 相关文档：[脚本引擎设计](../specs/2026-10-25-jw-script-engine-design.md)、[实施计划](../plans/2026-10-25-jw-script-engine.md)、[脚本契约一页说明](../research/2026-10-25-script-engine-notes.md)、[多教务导入调研](../research/2026-10-19-multi-jw-import.md)、[适配器架构调研](../research/adapter-architecture.md)、[脚本仓库种子说明](../../scripts/README.md)
- 相关代码：`core/jw/script/JwScriptRunner.kt`、`core/jw/script/JwScriptManifest.kt`、`core/jw/script/JwScriptOutput.kt`、`core/jw/script/ScriptCatalog.kt`、`core/jw/script/ScriptDownloader.kt`、`core/jw/script/ScriptIndex.kt`、`core/jw/script/ScriptStore.kt`、`app/src/main/assets/jw/scripts/`、`feature/importer/`

## Context

国内高校教务系统厂商极其分散。调研（[jw-vendor-landscape.md](../research/jw-vendor-landscape.md)）统计的一份 4140 条学校目录里，约 1470 条能判出协议：正方新版 562、强智 292、青果 164、超星 135、金智 54，其余还散着树维、正方旧版、URP、乘方等。同厂商不同版本、同版本不同学校的字段差异也普遍存在。

而当时的现状是：**一个原生 Kotlin 适配器**（命名为 `ZhengFangAdapter`，实际抓的却是强智 jsxsd 的 `xskb/xskb_list.do`）。由此浮出两个问题：

1. **改一所学校要发一次版。** 每接一所学校 = 写一份解析 + 提交 + 构建 + 发 Release + 等用户升级，边际成本极高，长尾学校永远排不上队。
2. **适配器不能热修。** 教务系统改版（正方新版就是典型）会让线上版本直接失效，用户只能等下一次发布。

业界已经收敛出解法：拾光、小爱、下节啥课都是让**解析跑在「已登录会话的 WebView 上下文」里**（[industry-import-practices.md](../research/industry-import-practices.md)）。同时架构调研给出了硬边界：**远程只能分发脚本与元数据，不能分发 DEX/APK**（[adapter-architecture.md](../research/adapter-architecture.md)）。

## Decision

**用可下载的 JS 脚本描述"怎么取数"，用原生代码负责"怎么理解数"。**

1. **执行模型：页面上下文注入**（不是 QuickJS 沙箱）。用户在已登录的教务 WebView 里走到课表页，点「脚本导入」，App 把脚本注入当前页面执行；脚本读 DOM 或做同源 `fetch()`（Cookie 由页面自带），经 `@JavascriptInterface` bridge 回传标准 JSON。宿主侧不再模拟浏览器环境。
2. **契约是自定义最小契约**：脚本头部是含 `PAPER_SCRIPT_MANIFEST` 的块注释 + JSON（校验、版本、hosts、目标页面特征），运行时只暴露一个 `globalThis.__paperScheduleScript.fetchSchedule(ctx)`，返回带 `apiVersion` 的信封。宿主**严格校验**后转成 `CourseDraft`，送进既有的 `ImportPreview` / `ImportGuard` 管线——**不另造模型**。
3. **分发三件套一步到位**：assets 内置脚本 + 本地导入 `.js` + 远程仓库（`index.json` + SHA-256 校验，地址可在设置里改）。
4. **安全边界（一期）**：脚本只在用户显式点「导入」时执行一次；安装远程脚本要求 HTTPS 且 SHA-256 与 index 对上才落盘；manifest 的 `hosts` 非空时校验当前页面域名；bridge **只提供「回传结果」一个能力**，不给弹窗、存储、Prompt 桥。Ed25519 签名后置。
5. **一期只做课表**：信封预留 `fetchExams` 扩展位，向后兼容靠 `apiVersion`。

## Consequences

正面：

- 接一所新学校的成本从"改代码 + 发版"降到"写一份 `.js` 并进 index"，长尾学校可以靠社区脚本覆盖。
- 教务改版可以**热修**：脚本版本号递增，用户点「检查更新」即可，无需等 App 发版。
- 内置的五份脚本（强智 jsxsd、正方新版、正方旧版、金智 ehall、通用表格）既是线上可用的兜底，也是契约的可执行范本（见 `app/src/main/assets/jw/scripts/`）。
- 职责切得干净：脚本只管"从页面里抠出数据"，字段归一、去重、周次换算、预览与冲突判定全部留在原生侧，可被 JUnit 直接测。

代价与风险：

- **脚本与页面强耦合**：脚本要在用户真的走到课表页时才能跑，无法后台静默执行——自动化程度低于纯 HTTP 重放路径。
- **能力边界靠契约死守**：注入的脚本理论上能碰到页面里的一切（包括已登录的教务会话），因此 bridge 只回传数据、远程脚本必须哈希校验；**一期的完整性校验不保证来源可信**（无签名），这是明确留下的技术债。
- 脚本作者要理解页面上下文，比写 JSON 配置门槛高；出问题时的排查链路也更长（页面 DOM + 脚本 + bridge + 宿主校验四段）。
- 与"纯原生适配器"并存，两套模型都要维护。

## 备选方案

1. **每校一个原生 Kotlin 适配器**：否。边际成本最高，且无法热修——这正是本 ADR 要解决的问题。
2. **把页面 HTML 上传到服务端解析**：否。直接违反 [ADR-0001](0001-local-first-data-architecture.md) 的"无服务端"，而且等于把用户的已登录会话内容送到第三方。
3. **QuickJS 引擎内跑脚本**：否（推迟）。要自己实现 DOM 与 `fetch` 的模拟层，桥面大、行为与真实页面不一致，调研文档 §5.4 已给出风险分析；页面注入能用零成本拿到真实 DOM 与真实 Cookie。
4. **只做内置脚本、不做远程分发**：否。放弃远程仓库就等于放弃了覆盖长尾学校的能力，与决策目标矛盾。
5. **配置化适配（YAML/JSON 描述选择器）**：否。教务改版时需要条件分支、循环、多次请求，配置表达力不够，最终会演化成一门残缺的脚本语言。
