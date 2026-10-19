# 教务导入多系统支持：业界调研与落地方案

- 日期：2026-10-19
- 范围：Android App「纸间课表」的「教务系统导入」能力，不含 LMS（学习通/雨课堂等）课表同步
- 结论性质：调研 + 架构建议 + 排期；本文不改代码

---

## 0. 结论先行

1. **业界主流不是「每所学校写一份代码」，而是「少数通用教务厂商适配器 + 学校目录 + 通用兜底 + 长尾脚本」。**
   WakeUp 的官方文档写的是「十多种通用教务，1200+ 所高校」；下节啥课是 7 个厂商 parser + 上千所学校索引；拾光/小爱才走「每校脚本」路线，但背后有仓库索引、审核、测试插件和社区维护。
2. **第一批只做 4 家厂商，性价比最高：正方新版（jwglxt）、强智（jsxsd，已有）、金智 URP、青果（KINGOSOFT）。**
   正方官方称 1600+ 高校，强智官方称 900+ 合作院校，金智招股材料披露教务相关高校 628 所；青果没有公开准确数字，但在高职/独立学院存量很大。四家覆盖了国内教务的主流盘面。
3. **当前代码的第一障碍不是「缺适配器」，而是「把东莞职院写死在了通用路径里」。**
   `ZhengFangAdapter.normalizeBase()` 在 URL 不含 `/jsxsd/` 时直接回落到 `https://jw.dgpt.edu.cn/jsxsd/`；`DEFAULT_URL`、SSO 入口、`dgpt.edu.cn` 域名判断、成绩接口、WebVPN 提示全部写死东莞职院。换一所学校，轻则抓错学校，重则一直失败。必须先做「学校档案 + 适配器注册表 + 去硬编码」，再谈加厂商。
4. **明文 HTTP 是隐形红线。**
   大量学校教务仍是 `http://`（下节啥课学校索引里随处可见），而本 App `targetSdk 36`、Manifest 未声明 `usesCleartextTraffic`/`networkSecurityConfig`，Android 9+ 默认禁止明文；WebView 和 OkHttp 都会失败。支持更多学校前，必须做一次产品/安全决策：允许明文（并提示风险），还是只支持 HTTPS/WebVPN。
5. **第一阶段不建议做「小爱/拾光兼容脚本运行时」。**
   脚本仓库能覆盖长尾，但需要桥接 API、审核、签名、测试、后台执行和供应链安全，投入远大于 4 个原生厂商适配器；等厂商适配器把 70% 学校覆盖后，再用脚本补长尾更稳。反证：小爱课程表 2025 年起关闭教务导入（社区出现 vConsole 恢复教程，MIUI 论坛也有「为什么下线」讨论），ClassIsland 也明确拒绝把教务导入并入主线（Issue #1279，2025-11 closed as not planned）——教务导入是长期维护负债，必须可插拔、可降级、可随时关停，不能做成不可替代的核心路径。

---

## 1. 现状盘点（代码事实）

| 事实 | 位置 | 影响 |
| --- | --- | --- |
| 只有一个适配器，且 `supports()` 恒为 true | `core/jw/JwAdapter.kt:21-37` | 所有 URL 都进同一个解析器；无法路由到不同厂商 |
| `normalizeBase()` 未命中 `/jsxsd/` 时返回东莞职院地址 | `core/jw/ZhengFangAdapter.kt:98-111` | 输入其他学校 URL 会被改写成 `jw.dgpt.edu.cn`，**抓错学校** |
| 适配器实际覆盖「强智 jsxsd + 旧版正方表格」 | `core/jw/ZhengFangAdapter.kt:13-20` | 类名 `ZhengFangAdapter` 名不副实，容易误判已支持正方新版 |
| 默认 URL / SSO 入口 / 域名判断 / 外壳修复 / WebVPN 文案写死东莞职院 | `feature/jw/JwImportViewModel.kt:160`、`feature/jw/JwImportScreen.kt:105,111-137,495` | 其他学校无法复用；维护时容易漏改 |
| 成绩抓取写死强智 `kscj/cjcx_list` | `core/jw/GradeFetcher.kt:29-33` | 换厂商后成绩必然失败，且失败会拖累课表同步（当前已 `bestEffort`，但语义仍耦合） |
| 教务配置是全局单账号 | `core/data/prefs/SettingsStore.kt:28-29,54-55` | 多课表/多学校场景无法区分；一期可接受，二期可能要按课表绑定 |
| 已有统一导入管线 | `feature/importer/*`、`core/model/Models.kt` | `CourseDraft` + `ImportPreview` 可直接复用，新适配器只需产出 `CourseDraft` |
| 已有 Excel/CSV/JSON/ICS/WakeUp/OCR/二维码 | `feature/importer/parsers/*`、`ocr/*`、`qr/*` | 长尾学校的手动兜底已经很强，是「支持更多学校」的现成安全网 |
| 无学校目录、无系统指纹、无 fixture 测试 | 全局 | 用户只能手填 URL；开发者无法快速回归各厂商页面 |
| `targetSdk 36`，无明文 HTTP 配置 | `app/build.gradle.kts:26`、`AndroidManifest.xml:44-52` | 大量 `http://` 教务无法打开/抓取 |

**一句话**：现在的「教务导入」是「东莞职院专用导入」，不是「正方/强智导入」，更不是「多教务导入」。

---

## 2. 业界做法

### 2.1 WakeUp 课程表：学校目录 + 通用教务类型 + 可改 URL + HTML 兜底

官方文档《从教务系统导入课表》写明：

- 「App 已经适配了十多种通用教务，1200+ 所高校的教务系统」；
- 学校选择页可搜索学校，「注意区分研究生教务」；
- 大部分学校通过网页导入，**网址可自行修改**；内置地址不对时用户可改；
- 如果搜不到学校，或学校换了教务系统，可「自行选择通用教务类型」导入；
- 旧强智在 App 内会「无法创建对象」，官方直接引导走 **HTML 文件导入**；
- 官方反复强调「导入后一定要自行检查课程信息是否正确」。

这套链路是行业里最完整的「五级兜底」：**学校专用 → 通用教务类型 → 可改 URL 的 WebView → HTML/Excel/CSV 文件 → 手动/口令**。本项目目前只有「专用适配器（默认正方）+ 文件/口令导入」，缺中间两层。

**口令 caveat**：WakeUp 现行分享口令是 32 位 hex 短码，需要 APK 签名 + ANDROID_ID 请求 `api.wakeup.fun`，不是可离线解压的负载；本项目的 `WakeUpParser.kt` 走的是旧版自重编码（Base64 + zlib/deflate/gzip）容错，对现行短码无效。建议保留离线解码，但 UI 文案降级为「兼容旧版口令/粘贴文本」，主推「导出文件 → 文件导入」。

来源：
- <https://www.wakeup.fun/doc/import_from_eas.html>
- 已适配学校（2022-02-22 快照，含本科/专科与研究生分类）：<https://www.wakeup.fun/doc/adapt_android_hmos.html>
- 官网（宣称 1800+，应用商店文案有 2000+/3000+ 的口径差异）：<https://www.wakeup.fun/>

**可借鉴**：学校目录是入口；URL 必须可编辑；通用类型选择是「学校目录没收录/换系统」的逃生门；HTML 兜底必须有。
**不要照搬**：营销数字口径不一致，别把「收录学校名」当成「已适配」。

### 2.2 小米小爱课程表：Provider / Parser / Timer 三段式脚本

小爱开发者文档把适配代码拆成三段：

- `scheduleHtmlProvider()`：在**教务页面上下文**执行，抓 HTML 或调接口，返回字符串；
- `scheduleHtmlParser(html)`：在**服务器环境**执行，把 HTML 解析成标准课程数组（官方理由是「保护你的代码不被抓包」）；
- `scheduleTimer()`：再回页面上下文，返回总周数、开学时间、节次时间等配置；
- 课程 schema 有严格限制：`name/position/teacher/weeks/day/sections`，长度、取值范围都有限制；
- 有 E2E 真机测试、版本提审/撤审、Vconsole 调试；
- 针对旧版青果的**图片课表**，官方提供 `KingoImgReader` 做端上图片识别。

来源：<https://open-schedule-prod.ai.xiaomi.com/>

**可借鉴**：脚本接口要稳定、返回结构要严格校验；Provider/Parser 分离；Timer 把「总周数/节次时间」也带回来；旧青果图片课表要用 OCR 而不是 DOM。
**不要照搬**：Parser 上传服务器与我们的「本地优先、账号不经第三方」定位冲突；脚本审核体系也不是个人项目一期能承担的。
**生命周期警告**：小爱课程表 2025 年起关闭「教务系统导入」入口（社区出现 vConsole 恢复教程，MIUI 论坛也有「为什么下线」讨论），说明**服务端解析 + 平台单点**的适配体系一旦产品调整就会整体归零。这也印证了「本地优先、适配器可替换」的方向。

### 2.3 拾光课程表：适配仓库 + 索引 + 每校脚本 + 自定义仓库

`shiguang_warehouse` 的组织方式：

- 根索引 `index/root_index.yaml` 登记学校/工具（`id/name/initial/resource_folder`）；
- 每校目录下 `adapters.yaml` 声明 `adapter_id/adapter_name/category/asset_js_path/import_url/maintainer/description`；
- 脚本是普通 JS，通过 `window.shiguangBridgePromise.saveImportedCourses(JSON.stringify(courses))` 回传；
- 索引编译成 protobuf（`school_index.pb`），App 只拉索引 + JS；
- 支持自定义仓库/私有仓库，开发者模式（「更多」页点图标 5 次）做真机测试；
- 仓库里除学校目录外，还有 4 个 `GENERAL_TOOL` 通用教务适配器：`zhengfang_jiaowu`（正方 HTML 通用）、`urp_jiaowu`、`qingguo_jiaowu`、`chaoxing_jiaowu`，`import_url` 留空，由用户自己输网址、登录、切到课表页再执行；
- 仓库 MIT，社区 PR + 分支保护 + 测试插件。

来源：
- <https://github.com/ShiGuangSchedule/shiguang_warehouse>
- <https://github.com/ShiGuangSchedule/shiguangschedule>

**可借鉴**：索引与脚本分离、脚本可热更新、开发者模式/测试入口、每校可维护者；**4 个通用教务适配器正是「通用类型兜底」的工程实现**，与我们的 WebView 登录形态同构，可直接照搬这个分层。
**不要照搬**：桥接 API 面很大（弹窗、Prompt、时间段、组合作息、配置），兼容成本高；脚本注入登录态 origin 后能读 cookie、发任意请求，必须有签名/白名单/权限提示；个人项目没有社区审核人力。

### 2.4 下节啥课：7 个厂商 parser + 学校索引，MIT

`baoozak/timetable` 的 `utils/parsers/` 里是：

- `zhengfang_new.js`：正方新版，`POST /kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N253508`，表单 `xnm/xqm`，解析 `kbList` 的 `kcmc/xqj/jcs/cdmc/zcd/xm`；
- `qiangzhi.js` / `qiangzhi_old.js`：强智，`#kbtable` + DOM matrix；
- `urp.js` / `urp_new.js`：URP，`#cwTable`/`.displayTag`；
- `kingosoft.js` / `kingosoft_new.js`：青果/金智（作者命名有混用）；
- `wisedu.js`、`south_soft.js`、`chaoxing.js`；
- `schools.json`：上千所学校，字段 `id/name/type/url`，类型正是上述 7 类；
- MIT 许可。

来源：
- <https://github.com/baoozak/timetable>
- parser 目录：<https://github.com/baoozak/timetable/tree/main/utils/parsers>
- 学校索引：<https://github.com/baoozak/timetable/blob/main/utils/schools.json>

**一个重要发现**：小爱模板、拾光、下节啥课、本项目四方独立实现，最后都在解正方新版 `kbList` 的同一组字段（`kcmc/xqj/jcs/cdmc/zcd/xm`，接口 `/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N253508`）。这说明「正方新版 JSON」事实上已经是上游通用语，优先做它能一次覆盖大量学校。

**可借鉴**：厂商级 parser 的划分、学校索引的字段、DOM matrix helper 处理 rowspan/colspan。
**注意**：这份代码质量参差——`urp_new.js` 的 JSON 分支直接 `return []`，`chaoxing.js` 明确写「需针对不同高校结构微调」；只有正方新版和强智相对完整。**只能当结构与字段参考，不能当生产级实现直接抄。**

### 2.5 超级课程表 / ClassIsland

- 超级课程表：闭源，App Store 更新日志称「累计支持 3618 所大学自动导入课程表」，是全行业最大公开数字；但它没有公开适配规范、脚本生态或学校列表文档，纯自建、纯闭源，无法参考实现，只能当「规模上限」参照。
- ClassIsland：桌面端课表，导入以 Excel/CSES 文件为主，**明确不做教务导入**（Issue #1279，2025-11 closed as not planned，理由是「应由插件实现」）。这再次说明教务导入是维护负债；其课表域模型（档案/时间表/科目、多周轮换、临时调课）值得读思路，但代码是 GPLv3/LGPLv3，**不可抄实现**。


### 2.6 业界共识与反模式

**共识**

1. 学校目录是导入体验的第一入口，搜索比手填 URL 重要。
2. 通用厂商适配器覆盖大多数学校；学校特有差异用配置/脚本补，不为每校写原生代码。
3. WebView 登录 + 页面内抓取是合规主流；账号密码不经过开发者服务器。
4. 导入后必须预览/校验；页面结构一变就可能少课/错节。
5. 必须有手动兜底（改 URL、换通用类型、HTML/Excel/ICS）。
6. 适配器要能独立于 App 版本更新，否则每次改版都要发版。

**反模式**

1. 把某所学校地址写成默认值/回退值。
2. 把「学校名单数量」当「已适配数量」宣传。
3. 只做自动同步、不给手动刷新和失败提示。
4. 用服务端解析/存储账号（与本地优先定位冲突，且是高风险资产）。
5. 为长尾学校硬编码原生分支，最后无人维护。
6. 只支持 HTTPS 却宣称支持全国高校（大量学校仍是 HTTP）。

---

## 3. 教务厂商版图与优先级

> 说明：下表是「做 App 内 WebView 登录 + 课表抓取」的可行性评估，不是厂商功能评测。URL/接口会随学校和版本变化，需以实际抓包为准。

| 厂商/系统 | 覆盖证据 | 常见 URL/指纹 | 课表获取方式 | 公开参考 | 难度 | 备注 |
| --- | --- | --- | --- | --- | --- | --- |
| **正方新版 jwglxt** | 官方 1600+ 高校；招股材料披露高校覆盖率 >28%（2021） | `/jwglxt/`、`xtgl/login_slogin.html`、页面底部「正方软件」 | `POST /kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N253508`，表单 `xnm/xqm`，JSON `kbList`；HTML 兜底 `#kbtable`/`#kblist_table` | 下节啥课 `zhengfang_new.js`；拾光 `zhengfang_01.js` | 低-中 | 验证码/SSO 因校而异；JSON 字段稳定，优先做 |
| **强智 jsxsd** | 官方 900+ 合作院校 | `/jsxsd/`、`xskb/xskb_list.do`、`xskbList.do`、`#kbtable`、`div.kbcontent` | HTML 表格；周次行 `[01-02-03-04节]` | 当前 `ZhengFangAdapter.kt`；下节啥课 `qiangzhi.js` | 低（已有） | 现有代码已覆盖，需改名/去硬编码 |
| **金智 URP** | 金智招股材料：教务管理系统相关高校 628 所（占 22.76%）；整体服务 1200+ 高校 | `/urp/`、`cwTable`、`.displayTag`、`infoTitle` | HTML 表格，rowspan/colspan 矩阵 | 下节啥课 `urp.js`；WakeUp 文档单列「新 URP / URP」 | 中 | URP 与金智新教务命名易混，需按页面指纹细分 |
| **青果 KINGOSOFT** | 官方无公开准确数字；高职/独立学院存量大 | `/jwweb/`、`kingosoft`、登录页「KINGOSOFT」 | 新版 HTML 表格；旧版课表可能是**图片** | 下节啥课 `kingosoft*.js`；小爱 `KingoImgReader` | 中-高 | 旧版图片课表走现有 OCR 管线，不硬解 DOM |
| **南软** | 区域型厂商，公开资料少 | 需按学校抓包 | HTML/接口不一 | 下节啥课 `south_soft.js`（较粗） | 中 | 二期长尾 |
| **超星（教务）** | 与学习通生态相关，学校差异大 | `*.jw.chaoxing.com` 等 | HTML/JSON 混合 | 下节啥课 `chaoxing.js`（半成品） | 中-高 | 不建议一期做 |
| **研究生系统** | 各校独立采购，常与本科系统不同 | 校名 + `yjs`/`graduate` 等 | HTML/JSON 不一 | WakeUp 单列「研究生教务」 | 中 | 学校目录必须区分本/研，否则用户选错 |

### 3.1 优先级建议

- **P0（第一批）**：正方新版 jwglxt。它是当前最大缺口，且 JSON 接口最稳定、最容易做 fixture 回归。
- **P1（第二批）**：金智 URP、青果新版。二者覆盖本科/高职的大头，HTML 表格可复用同一套 DOM matrix 解析器。
- **P2（第三批）**：青果旧版（图片 → OCR，复用现有 `feature/importer/ocr`）、南软、超星、研究生系统。
- **P3（长尾）**：脚本运行时/社区仓库 + 手动 HTML/Excel/ICS 兜底。

**不建议**一上来就追「1800 所」：学校目录可以收录很多名字，但「已适配」必须按厂商 parser 的实际测试结果标注，否则用户预期会崩。

---

## 4. 推荐架构

### 4.1 分层

```
L0 学校目录 SchoolCatalog
   └─ assets/jw/schools.json（名称/别名/系统/登录地址/来源/验证状态）
L1 系统识别 JwDetector
   └─ URL 路径/域名 + 页面标题/页脚/JS 资源名 → 系统类型 + 置信度
L2 厂商适配器 JwAdapter（原生 Kotlin）
   ├─ QiangzhiJsxsdAdapter（现有，改名）
   ├─ ZhengfangJwglxtAdapter（新增）
   ├─ UrpAdapter / KingosoftAdapter / ...
   └─ GenericTableAdapter（兜底，低置信度，必须用户确认）
L3 长尾脚本运行时（P3，可选）
   └─ WebView 内 JS 适配脚本 + 签名清单 + 严格 schema
L4 手动兜底（已有）
   └─ HTML/Excel/CSV/JSON/ICS/WakeUp 口令/OCR/二维码
L5 预览与落库（已有）
   └─ CourseDraft → ImportPreview（新增/变更/取消/冲突）→ 用户确认
```

### 4.2 数据模型

```kotlin
enum class JwSystem {
    ZHENGFANG_JWGLXT,
    QIANGZHI_JSXSD,
    URP,
    KINGOSOFT,
    NANRUAN,
    CHAOXING,
    UNKNOWN,
}

data class SchoolProfile(
    val id: String,                 // 稳定 id，如 "dgpt"
    val name: String,               // 东莞职业技术学院
    val aliases: List<String> = emptyList(),
    val system: JwSystem,
    val loginUrl: String,           // 建议入口，不保证长期有效
    val ssoPath: String? = null,    // 如 "sso.jsp"，仅对支持的适配器生效
    val source: String = "",        // 数据来源（MIT 仓库/人工验证/用户反馈）
    val verified: Boolean = false,  // 是否人工跑通过
    val note: String = "",
)

data class JwCapabilities(
    val login: LoginKind,           // LOCAL / CAS / SSO / CAPTCHA / UNKNOWN
    val httpReplay: Boolean,        // 能否纯 OkHttp 抓取（后台同步前提）
    val backgroundSync: Boolean,    // 是否适合每日自动同步
    val grades: Boolean,            // 是否有成绩抓取实现
    val needsVisibleWebView: Boolean,
)
```

`JwAdapter` 建议演进为：

```kotlin
interface JwAdapter {
    val system: JwSystem
    val capabilities: JwCapabilities

    /** 0 = 不匹配；分数越高越可信。profile 可为空（用户直接粘贴 URL） */
    fun match(url: String, profile: SchoolProfile?): Int

    /** 抓取并解析；失败抛 JwException 子类，便于 UI 分类提示 */
    suspend fun fetch(ctx: JwContext): JwFetchResult
}

data class JwContext(
    val client: OkHttpClient,
    val baseUrl: String,
    val totalWeeks: Int,
    val profile: SchoolProfile?,
    val webViewBridge: JwWebViewBridge?, // 仅需要页面内执行时使用
)

data class JwFetchResult(
    val courses: List<CourseDraft>,
    val totalWeeks: Int? = null,        // 适配器可纠正总周数
    val semesterStart: String? = null,  // 可选
    val warnings: List<String> = emptyList(),
)
```

**关键**：`JwAdapter` 不再返回裸 `List<CourseDraft>`，而是带 `warnings/totalWeeks` 的结果，UI 才能提示「可能少课」「周数不一致」。

### 4.3 学校目录

**字段**（`assets/jw/schools.json`）：

```json
[
  {
    "id": "dgpt",
    "name": "东莞职业技术学院",
    "aliases": ["东莞职院", "dgpt"],
    "system": "qiangzhi_jsxsd",
    "loginUrl": "https://jw.dgpt.edu.cn/jsxsd/",
    "ssoPath": "sso.jsp",
    "source": "manual",
    "verified": true
  }
]
```

**数据来源与许可**：

- 下节啥课 `schools.json`：MIT，可复制，需保留版权/许可声明；但类型/URL 有噪声（例如把部分学校标成 `urp`/`kingosoft` 并不准确），只能作为种子。
- 拾光 `shiguang_warehouse` 的 `root_index.yaml`：MIT，可补学校名；`adapters.yaml` 的 `import_url` 可补登录地址。
- WakeUp 已适配名单：无明确开源许可，**只用它交叉核对学校名，不复制其数据/代码**。
- 人工验证：每批适配器上线前，用真实账号跑通并标 `verified: true`。

**产品策略**：

- 目录可以大，但 UI 必须区分「已验证」「仅收录，待验证」。
- 搜索按中文名/别名/域名；拼音搜索可后置（不引入大依赖）。
- 选中学校后自动填 URL，但输入框始终可改。
- 搜不到 → 「手动输入教务地址」+「选择通用类型」两个出口。
- 研究生入口单独标注，避免选错。

### 4.4 抓取策略

按优先级：

1. **HTTP 重放**：与 WebView 共享 `WebViewCookieJar`，OkHttp 直接 GET/POST。快、省电、可在 WorkManager 后台跑。适用于正方 JSON、强智 HTML、URP HTML。
2. **可见 WebView 内注入**：页面必须 JS 渲染、或需要 iframe 内容时，在用户眼前的 WebView 里 `evaluateJavascript`，通过 `@JavascriptInterface` 回传。手动导入可用；后台同步不保证。
3. **离屏 WebView**：仅作为兜底，必须主线程、可能被 ROM 冻结；不建议作为自动同步主路径。
4. **手动兜底**：HTML 文件/Excel/ICS/OCR。

**能力声明决定调度**：

- `backgroundSync=true`：才允许 `SyncScheduler` 每日自动抓。
- `backgroundSync=false`：自动同步按钮置灰/提示「该校需打开 App 手动刷新」，避免每天推失败通知。
- 验证码/CAS 无静默票据：不尝试后台重登，只提示重新登录。

### 4.5 去硬编码清单（P0 必做）

| 位置 | 现在 | 改成 |
| --- | --- | --- |
| `ZhengFangAdapter.normalizeBase()` | 无 `/jsxsd/` → 东莞职院 | 按 URL 中的 `/jsxsd/`、`/jwglxt/`、`/jwweb/`、`/urp/` 截取 base；找不到就用 origin+path，**绝不回落到某校地址** |
| `JwImportViewModel.DEFAULT_URL` | 东莞职院 | 空字符串/引导页；或读取上次学校档案 |
| `JwImportScreen.SSO_ENTRY_URL` | 东莞职院 `sso.jsp` | `SchoolProfile.ssoPath` + 适配器能力；无档案时不注入 |
| `JwImportScreen.SSO_RECOVER_JS` | 写死 `dgpt.edu.cn` | 由适配器/学校档案提供，或删除，改为通用「检测到登录表单」提示 |
| `JwImportScreen.JW_SHELL_FIX_JS` | 东莞职院 iframe bug | 移到 `SchoolProfile.extraJs`/适配器 hook，默认不注入 |
| `JwImportScreen` HTTP 错误文案 | 写死 `webvpn.dgpt.edu.cn` | 通用文案 + 学校档案可选 WebVPN 地址 |
| `GradeFetcher` | 写死强智成绩接口 | 改为 `QiangzhiGradeFetcher`，由适配器 `capabilities.grades` 决定是否调用 |
| `JwAdapterRegistry.forUrl()` | 未命中回退正方 | 未命中返回 `null`/`GenericTableAdapter`，由 UI 让用户确认系统类型 |
| `JwImportScreen` 方法卡文案 | 「直连学校教务（正方等）」 | 改为「支持正方/强智/URP/青果等，可手动输入地址」 |

### 4.6 UI/UX

建议流程：

```
导入课表
  └─ 教务系统导入
       ├─ 搜索学校（名称/别名/域名）
       │    └─ 选中 → 显示「系统：正方新版（已验证/待验证）」→ 打开登录
       ├─ 手动输入教务地址
       └─ 不知道系统？→ 打开后自动识别，识别失败可手动选类型
登录完成 → 「抓取课表」→ 预览（新增/变更/取消/冲突）→ 确认
```

错误分类（直接决定用户能否自救）：

- 未登录/会话过期：提示重新登录，保留 URL。
- 验证码/SSO：提示在网页内完成，不要自动重试。
- 页面结构变化：提示「学校教务可能改版」，给「换通用类型」「用 Excel/HTML 导入」「反馈」。
- 网络/校园网/WebVPN：区分 HTTP 错误码与 DNS/超时。
- 解析到 0 门课：不要自动应用；提示检查是否在课表页、是否选对学期。

### 4.7 安全与隐私

- 账号密码只在 WebView 与学校服务器之间传递；不写日志、不上传、不落盘明文。
- Cookie 由 WebView 管理；OkHttp 只读共享 CookieJar。
- 远程脚本（若做）：签名（Ed25519）+ SHA-256 + 版本兼容声明；只在 WebView 沙箱执行；域名白名单；禁止动态 DEX/APK 加载。
- 日志脱敏：URL 查询参数、Cookie、学号/姓名不进日志。
- 明文 HTTP：见 §5 决策点。

### 4.8 测试与运维

- **fixture 单测**：每个厂商至少 2 份 HTML/JSON（正常 + 变体），放 `app/src/test/resources/jw/<system>/`；用 JUnit 直接测 parser。
- **MockWebServer**：测端点拼接、表单、登录页识别、HTTP 错误。
- **适配器实验室（debug）**：粘贴 HTML/URL 直接跑 parser，显示 `CourseDraft` 列表；仅 debug build。
- **失败样本**：用户可选导出「脱敏后的页面 HTML」到本地文件，用于反馈；默认不上传。
- **健康度**：本地记录 `adapter_id/school_id/parse_ok/course_count/error_kind`，设置页可查看；不做静默上报。
- **回归**：每次页面改版，补一份 fixture；CI 跑全部 parser 测试。

---

## 5. 决策点（需要产品/用户拍板）

### 5.1 明文 HTTP 是否放行

- 现状：`targetSdk 36`，默认禁止 cleartext；`AndroidManifest` 未配置。
- 影响：大量 `http://` 教务无法打开/抓取；这比「缺适配器」更致命。
- 选项：
  - **A. 放行 cleartext**（`networkSecurityConfig` base-config `cleartextTrafficPermitted=true`）：覆盖最大，但允许任意明文流量，存在 MITM 风险；需在隐私政策/首次使用时说明。
  - **B. 只允许 HTTPS**：安全，但排除大量学校；需要 WebVPN/校园网 HTTPS 入口。
  - **C. 混合**：默认 HTTPS；检测到 `http://` 时弹窗告知风险，用户确认后继续（技术上仍需全局放行，只是产品层加提示）。
- 建议：**C**（全局放行 + 对 `http://` 明确提示 + 隐私政策披露），否则「支持更多学校」无法兑现。

### 5.2 自动同步边界

- 正方/强智等有验证码或 CAS 的学校，后台无法静默重登；不要为了「自动」而存明文密码。
- 建议：`backgroundSync` 由适配器声明；不支持时只做「打开 App 时提示刷新」，不推每日失败通知。

### 5.3 学校目录的「支持」口径

- 建议三级：`已验证`（真实账号跑通）/ `已收录`（有学校名和地址，未验证）/ `通用`（用户自选厂商）。
- 对外文案不要写「支持 N 所」，写「已适配 X 个教务厂商，覆盖 Y 所已验证学校」。

### 5.4 是否做脚本运行时

- 建议 P3 再做；先看 4 个厂商适配器的实际覆盖率与维护成本。
- 若做，优先自定义最小契约（`fetchSchedule` 返回标准 JSON），不要一上来兼容小爱/拾光两套桥。

---

## 6. 排期建议

> 人日为粗估，不含真实学校账号联调的不确定性。

### Phase 0：去硬编码 + 框架（2-4 人日）

- 新增 `JwSystem`、`SchoolProfile`、`SchoolCatalog`、`JwDetector`。
- `JwAdapter` 增加 `system/capabilities/match/fetch(ctx)`；`JwAdapterRegistry` 改为按匹配度选择。
- 重命名 `ZhengFangAdapter` → `QiangzhiJsxsdAdapter`；抽取 `LegacyTableParser`。
- 删除/迁移 dgpt 硬编码；保留一个 `dgpt` 学校档案，确保现有用户行为不变。
- `GradeFetcher` 改为强智专属，按能力调用。
- 测试：`JwDetectorTest`、`SchoolCatalogTest`、现有 `ZhengFangParseTest` 全绿。

**验收**：输入任意 URL 不再被改写到东莞职院；dgpt 用户导入结果与现在一致；所有单测通过。

### Phase 1：正方新版（3-5 人日）

- `ZhengfangJwglxtAdapter`：
  - 先 GET 课表页，提取 `xnm/xqm`（`#xnm`/`#xqm`，缺失则按当前日期推断学年学期）；
  - POST `xskbcx_cxXsKb.html?gnmkdm=N2151`（个人课表 `xskbcx_cxXsgrkb.html?gnmkdm=N253508` 作为兜底），解析 `kbList`；
  - HTML 兜底：`#table1` / `#kblist_table`（本轮未做，下一批）。
- 字段映射：`kcmc`→name、`xqj`→day、`jc`（兼容 `jcs`）→节次、`cdmc`→room、`zcd`→周次、`xm`→teacher。
- 周次解析：`1-16周`、`1-16周(单)`、`1-8,10-16周`、`1-16(周)` 等。
- 学校目录补一批正方学校（标记待验证）。
- 测试：JSON fixture + HTML fixture + 单双周/跳周用例。

**验收**：至少 2 所真实正方学校手动跑通（需用户/测试账号）；fixture 单测覆盖正常/空课表/登录页/字段缺失。

### Phase 2：URP + 青果新版（5-8 人日）

- `UrpAdapter`：`#cwTable`/`.displayTag`，DOM matrix 处理 rowspan/colspan。
- `KingosoftAdapter`：新版 HTML；旧版图片课表引导到 OCR（多图）流程，不做 DOM 硬解。
- 能力声明：URP 通常可 HTTP 重放；青果旧版 `backgroundSync=false`。
- 学校目录补 URP/青果学校。

**验收**：各至少 1 所真实学校跑通；解析结果与页面人工核对一致。

### Phase 3：长尾与增强（10+ 人日，可选）

- 南软/超星/研究生系统。
- 脚本运行时 MVP（自定义契约 + 签名清单 + debug 实验室）。
- 学校目录远程更新（签名 JSON，GitHub raw/Release 托管）。
- 按课表绑定教务账号（多学校/多课表）。

---

## 7. 风险清单

| 风险 | 概率 | 影响 | 缓解 |
| --- | --- | --- | --- |
| 学校页面改版导致解析失败 | 高 | 导入 0 课/少课 | fixture 回归 + 预览 + 失败样本 + 快速发版 |
| 验证码/SSO 阻断后台同步 | 高 | 自动同步失效 | 能力声明 + 手动刷新提示，不存密码 |
| 明文 HTTP 政策 | 高 | 大量学校不可用 | §5.1 决策，尽早做 |
| 学校目录 URL 过期/类型错误 | 高 | 用户选错入口 | 标记「待验证」+ URL 可改 + 自动识别 |
| 维护成本失控 | 中 | 长期不可持续 | 只做 4 家厂商；长尾用脚本/文件兜底 |
| 许可证/数据来源 | 中 | 合规风险 | 只用 MIT 源，保留 NOTICE；不抄闭源 |
| 自动同步改坏课表 | 中 | 用户数据损失 | 现有 `manuallyEdited` 保护 + 快照回滚 + 预览/通知 |
| 平台/功能被下线（小爱前车之鉴） | 中 | 整块能力归零 | 本地优先、适配器可替换、文件导入兜底；不要把解析放服务端 |
| WakeUp 现行口令不可离线解 | 高 | 用户以为能导入却失败 | 文案降级为「兼容旧版口令」；主推导出文件/ICS/JSON |

---

## 8. 建议的下一步

1. **先拍板 §5.1 明文 HTTP**：这是「支持更多学校」的前置条件。
2. **按 Phase 0 做去硬编码**：这是所有后续适配器的地基，且能立刻消除「抓错学校」的隐患。
3. **Phase 1 做正方新版**：用 fixture 单测 + 2 所真实学校联调，先把最大缺口补上。
4. **再决定是否做脚本运行时**：等 4 家厂商的覆盖率和维护成本有数据后再投入。

---

## 附录：主要来源

> 竞品逐项考据（WakeUp/小爱/拾光/下节啥课/ClassIsland/超级课程表，含事实/推测/未证实标注）见同目录 [industry-import-practices.md](industry-import-practices.md)。

- WakeUp 从教务系统导入：<https://www.wakeup.fun/doc/import_from_eas.html>
- WakeUp 已适配学校（2022 快照）：<https://www.wakeup.fun/doc/adapt_android_hmos.html>
- WakeUp 官网：<https://www.wakeup.fun/>
- 小爱课程表开发者文档：<https://open-schedule-prod.ai.xiaomi.com/>
- 拾光适配仓库：<https://github.com/ShiGuangSchedule/shiguang_warehouse>
- 拾光主仓库：<https://github.com/ShiGuangSchedule/shiguangschedule>
- 下节啥课：<https://github.com/baoozak/timetable>
- 下节啥课 parser：<https://github.com/baoozak/timetable/tree/main/utils/parsers>
- 下节啥课学校索引：<https://github.com/baoozak/timetable/blob/main/utils/schools.json>
- 正方软件官网（1600+ 高校）：<https://www.zfsoft.com/>
- 强智科技官网（900+ 合作院校）：<http://www.qzdatasoft.com/>
- 金智教育教务产品页：<https://www.wisedu.com/?list_26/88.html>
- 金智教育招股材料（628 所/22.76%）：<https://static.sse.com.cn/stock/disclosure/announcement/c/202101/000721_20210129_XQIT.pdf>
- 青果软件：<http://www.kingosoft.com/>
- Android 网络安全配置（明文流量）：<https://developer.android.com/privacy-and-security/security-config>
- WakeUp 旧版社区 fork（架构参考）：<https://github.com/tKM9WsmQUaUgNttn3DGUsHkxG8/WakeupSchedule_Kotlin>

---

## 9. 实施记录（2026-10-19，本轮已落地）

> 厂商版图与证据分级见 [jw-vendor-landscape.md](jw-vendor-landscape.md)；适配器分层、分发与护栏设计见 [adapter-architecture.md](adapter-architecture.md)。
>
> 与 §6 的排期差异：先做「地基 + 正方新版」，没有先做脚本运行时/远程分发；理由见 §5.4 与竞品教训（小爱下线、ClassIsland 拒收）。

已完成：

- `ZhengFangAdapter` → `QiangZhiAdapter`（id `qiangzhi`），修正 jsxsd 归属；删除「未匹配回落到东莞职院」。
- `JwAdapter` 改为 `match(url): Int` 打分；`JwAdapterRegistry.forUrl` 未识别返回 `null`，由调用方明确提示。
- 新增 `ZhengFangNewAdapter`（id `zhengfang_new`）：`/jwglxt/` 识别、`xnm/xqm` 页面读取/按日期推断、`kbList` JSON 解析、单双周与重复行合并、两个课表端点兜底。
- 新增 `SchoolCatalog`（目前仅 dgpt 一档）：裸域名只对已实证学校补路径，未知学校直接报错。
- `JwImportScreen`：动态 SSO 入口（不再写死 dgpt）、URL 识别提示、HTTP 明文风险提示；移除 `webvpn.dgpt.edu.cn` 文案。
- `JwImportViewModel`：默认地址改为空白页，保存识别出的适配器名。
- `ImportGuard`：首次同步不删、取消 > max(3, 25%)、新增 > max(5, 40%) 时暂停自动应用；`JwSyncManager` / `JwSyncWorker` 支持「不可重试」失败，避免无意义重试。
- `GradeFetcher` / 成绩页：仅强智 jsxsd 可用，其他系统明确提示「暂不支持成绩抓取」。
- Manifest + `network_security_config.xml`：放行明文 HTTP（配 UI 风险提示）；学校支持 HTTPS 时仍建议 HTTPS。
- 本地模拟正方环境：`tools/mock-jw/server.mjs` + [使用说明](../testing/mock-jw.md)，无需真实学校账号即可跑 WebView → 抓取 → 预览 → 落库链路。
- 测试新增 17 个用例：`JwAdapterRegistryTest`(5)、`ZhengFangNewParseTest`(6)、`ZhengFangNewFetchTest`(1)、`ImportGuardTest`(5)；`QiangZhiParseTest`(7) 全绿；全量 23 suite / 233 tests / 0 失败。

未做（下一批）：

- 正方新版 HTML 过渡页兜底、至少 2 所真实学校联调（需账号）。
- URP / 青果 / 正方旧版 / 树维 / 超星 / 金智。
- 脚本执行器、远程分发、学校目录远程更新。
- 自动同步跨天连续失败熔断（目前是 WorkManager 单次重试 + 不可重试标记）。
