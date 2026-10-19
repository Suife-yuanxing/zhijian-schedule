# 中国课程表类产品「多教务系统导入」业界做法调研

> 调研范围：WakeUp / 小爱课程表 / 拾光课程表 / 下节啥课 / ClassIsland / 超级课程表 + 可复用格式与开源实现。
> 目的：为「纸间课表」从「单一正方/强智 jsxsd 适配器」扩展到多教务系统导入提供可落地的对比结论。
> 约束：只调研，不改 app 源码。所有结论标注 **【事实】**（有链接佐证）/ **【推测】**（基于代码或机制的推断）/ **【未证实】**（二手陈述或口径冲突）。
> 调研日期：2026-10-19。抓取方式：搜索引擎文本抽取 + GitHub raw/API（本机直连 github.com 被重置，`raw.githubusercontent.com` 经抽取服务可读）。

---

## 0. 先给结论（TL;DR）

1. **业界没有"一个解析器吃下所有教务"的方案**。所有活下来的产品都是「**学校列表 + 通用教务类型兜底 + 文件/手动兜底**」三层结构，差别只在谁来写解析、在哪执行。
2. **解析必须跑在"已登录会话的 WebView 上下文"里**，这是成本最低的公共解：拾光、下节啥课、小爱 Provider 全部如此；本项目现有的「WebView 登录取 Cookie → 原生 OkHttp 重放」是同一思路的原生版，可继续用，但**每校一个原生适配器的边际成本远高于注入脚本**。
3. **事实标准已经出现，但只有"中间态"，没有"传输态"**：`{name, teacher, position, day, sections[], weeks[]}` + `timeSlots[]` + `semesterConfig`。正方新版 `kbList` JSON 事实上是上游标准，拾光导出的 JSON 已被第三方（Yunzai 插件）当交换格式吃下。
4. **"口令"是分水岭**：自重编码口令（base64+gzip JSON）可离线解、可长期兼容；**短码口令 + 服务端解析**（WakeUp 现行做法）一旦厂商加鉴权/反作弊就全废，且会连带打死第三方生态。
5. **不要把教务适配绑在"非核心"或"服务端"上**：小爱教务导入 2025 年下线（改为拍照导入）、ClassIsland 明确拒绝把教务导入并入主线（2025-11 closed as not planned）——两者都说明这是**长期维护负债**，必须做成可插拔、可热更新、可随时降级的能力。

---

## 1. WakeUp 课程表（i.wakeup.fun）

### 1.1 覆盖规模：【事实】但官方口径自相矛盾

| 来源 | 宣称 | 链接 |
|---|---|---|
| 官网首页 | 「1800+ 高校」 | https://www.wakeup.fun/ |
| 官方文档简介 | 「1200+ 所高校教务导入」 | https://www.wakeup.fun/doc/ |
| 官方文档·教务导入 | 「十多种通用教务，1200+ 所高校」 | https://www.wakeup.fun/doc/import_from_eas.html |
| 应用商店文案（腾讯/微软/App Store） | 「2000+」「3000+」 | https://sj.qq.com/appdetail/com.suda.yzune.wakeupschedule |

结论：**对外宣传数字不可作为覆盖率基准**；官方文档的数字（1200+）最保守也最可信。已适配学校名单页注明「列表更新日期：2022-02-22 … 学校可能会更换教务系统，此页面仅供参考」——**官方自己承认名单会过期**。

### 1.2 导入流程与兜底链路：【事实】

官方文档给出的完整漏斗（https://www.wakeup.fun/doc/import_from_eas.html）：

1. **学校列表检索**（区分「本科/专科」与「研究生教务」两类目，名单页可见双分类）。
2. 搜不到 / 导入失败 → **申请适配**（腾讯问卷 https://survey.daxuesoutijiang.com/survey/s/Cz8ze3 ）。
3. 同时可**自行选择"通用教务类型"**兜底导入；文档专章教用户「如何正确选择教务类型」：按登录页/页脚厂商标识分辨 新 URP / URP / 正方（新老两种）/ 强智 / 旧强智。
4. 进入导入页后，**「部分学校支持输入账号密码直接导入，大部分学校是通过网页进行导入」**；**网址可自行修改**（顶栏即地址栏）。
5. 旧强智在 App 内会「无法创建对象」→ 引导走 **HTML 文件导入**（https://www.wakeup.fun/doc/import_from_file.html ）。
6. 另有 CSV / Excel 文件导入（https://www.wakeup.fun/doc/import_from_csv.html ）与 **分享口令**导入（读取剪贴板，见隐私政策 https://www.wakeup.fun/policy_new.html ）。

**这是全行业最完整的"五级兜底"：学校专用 → 通用教务类型 → 可改 URL 的 WebView → HTML/Excel/CSV 文件 → 手动/口令。** 本项目目前只有「专用适配器（默认正方）+ 文件/口令导入」，缺中间两层。

### 1.3 口令格式：【事实】现行是"短码 + 服务端"，不是自重编码

- 第三方逆向项目 WakeUp2ICS / WakeUpDecoder 的 README 明确：口令解析需要 **APK 路径（提取签名信息）+ 16 位 ANDROID_ID**，请求 `https://api.wakeup.fun`，否则「会命中反作弊」（https://github.com/airline233/WakeUp2ICS 、https://github.com/airline233/WakeUpDecoder ）。
- 示例口令形如 `2bce137ad56d4ad19539c79834647dac`（32 位 hex 短码），**不是**可离线解压的负载。
- 第三方 Yunzai 课表插件 README 陈述：WakeUp 于「**4 月初**迁移课表请求接口并加鉴权」「**4 月 29 日**进一步加强鉴权，返回『命中反作弊』」，导致其口令导入彻底失效（https://github.com/Temmie0125/Yunzai-Schedule-Plugin ）。**【未证实】**：此为单方陈述，官方无公告；但与该 README 中「请尽快停用 WakeUp」的措辞一致，可信度中等。

> ⚠️ **对本项目的直接结论**：`WakeUpParser.kt` 现在做的是「直读 JSON / CSV / 提取 ≥32 字符 Base64 段并 zlib·deflate·gzip 解压」的**离线容错**。这条路对**旧版自重编码口令**有效，对**现行短码口令无效**（短码只能查服务端）。建议：保留离线解码，但 UI 文案不要把「WakeUp 口令」当承诺；改为优先引导「导出文件（`.wakeup_schedule` / ICS / 备份）→ 文件导入」。

### 1.4 旧版开源代码能看出的架构：【事实】原生 Retrofit 重放 + 服务端下发配置 + 众包 HTML

证据仓库：https://github.com/tKM9WsmQUaUgNttn3DGUsHkxG8/WakeupSchedule_Kotlin （2018 Kotlin 重构版）、原始版 https://github.com/YZune/WakeUpSchedule 。**注意：这是 2018 年形态，与今日闭源产品不等价。**

- `schedule_import/ImportService.kt`：接口方法直接写死学校入口——
  `@GET("/CheckCode.aspx")`、`@POST("/default2.aspx")`（正方式字段 `txtUserName/Textbox2/txtSecretCode/RadioButtonList1`）、`@POST("/")`（URP 式 `TextBox1/TextBox2/TextBox3`）、`@GET("/xskbcx.aspx")`、`@POST("/xskbcx.aspx")`（带 `__EVENTTARGET/__VIEWSTATE/xnd/xqd`），并且 **`@Headers("Host: xk.suda.edu.cn")` 硬编码苏大域名**。→ **每校一个 Retrofit 接口，编译期写死**。
- `schedule_import/ImportRepository.kt`：`Retrofit.Builder().baseUrl(url)`（url = 学校教务地址）、`Jsoup` 抓 `__VIEWSTATE`、Cookie 用 `Array<String>(1)` 手工传递、`VIEWSTATE_LOGIN_CODE` 直接是一个**硬编码的 ASP.NET VIEWSTATE 常量**。
- `utils/MyRetrofitService.kt`：指向自家服务端 `/school/count`、`/school/getupdate`、`/school/count_html`、`/school/apply_html`。
  → **`apply_html(school, type, html, qq)`：用户把导入失败的页面 HTML 上传，由官方人工写适配**。这是"众包采集 + 官方适配"闭环，也是 1200+ 学校的真实产能来源（**【推测】**，但接口语义非常明确）。
- `utils/ExportUtils.kt`：导出文件扩展名 `.wakeup_schedule`，内容是字符串（`file.writeText(data)`）——即「分享/导出文件」通道。
- 目录里并存 `LoginWebActivity / LoginWebFragment / WebViewLoginFragment / SchoolInfoFragment / FileImportFragment`：**原生登录（部分校）+ WebView 登录（多数校）+ 文件导入**三套并存。

**可复用点**：`apply_html` 式的「用户回传页面 → 后台补适配器」是低成本冷启动手段；`getupdate` 说明**适配器/学校配置是热更新下发**的。

### 1.5 自动同步：【未证实】

官方文档与 FAQ 未见「自动同步课表」的说明（https://www.wakeup.fun/doc/faqs.html ）；商店文案只讲「一键抓取 / 手动 / 分享码」三路径。**不要在报告/产品文案里假定 WakeUp 有后台自动同步**。

---

## 2. 小米小爱课程表（MIUI「超级小爱」内置）

### 2.1 适配脚本生态：【事实】

- 官方开发文档：https://open-schedule-prod.ai.xiaomi.com/docs/#/help/ （「小爱课程表开发者工具使用教程」，最新更新 2024-12-13；配套「课程表开发者资源库」）。文档站是 Vue SPA，正文需 JS 渲染，本次**只能通过搜索摘要与第三方教程转述**其内容。
- 开发者工具形态：**Chromium 浏览器扩展 `AISchedule`**（Manifest V2，v0.3.8）+ 本地 `localTools` 工程（`pnpm i` / `pnpm main` 起本地服务器把编辑器代码热同步进扩展）+ 扩展内「新建项目 / 编写代码 / 本地测试 / 上传」。
- 社区镜像仓库：https://github.com/kqakqakqa/xiaoai-schedule-parser （27 所学校，**全部是新正方 V9.0**，目录结构 `学校名/新正方V9.0_request/provider.js`）；官方适配者 QQ 群「教务导入适配联盟 496486285」（同仓库 README）。

### 2.2 脚本 API / 规范：【事实】三文件三函数

必须提供三个文件、三个**固定函数名**：

| 文件 | 入口函数 | 执行位置 | 职责 |
|---|---|---|---|
| `provider.js` | `scheduleHtmlProvider(iframeContent, frameContent, dom)` | **手机 WebView 内**（已登录的教务页面上下文） | 用 `fetch(..., credentials:'include')` 或读 DOM 取课表原文，`return` 字符串；`return "do not continue"` 中止 |
| `parser.js` | `scheduleHtmlParser(input)` | **小米服务器** | 把字符串解析成规范 JSON 数组 |
| `timer.js` | `scheduleTimer({providerRes, parserRes})` | 手机 WebView 内 | 返回作息/学期配置 |

**Parser 返回值规范**【事实，转述自 https://zhul.in/2024/11/18/mi-ai-class-schedule-adapter-for-zjut/ 】：

```js
[{ name, position, teacher, weeks: [1,2,3], day: 3, sections: [1,2,3] }]
// 字段上限：name/position/teacher 各 50 字节（1 汉字=2 字节）
// weeks 1..30（超界被裁），day 1..7，sections 1..30
// "星期+地点+时间全同"才算一节课，否则必须拆成多条
```

**Timer 返回规范**：`{ totalWeek, startSemester(13 位时间戳字符串), startWithSunday, showWeekend, forenoon, afternoon, night, sections:[{section,startTime,endTime}] }`，全部可选，`sections` 总长必须与前三个节数之和相等。

**Provider 的真实工程形态**【事实，读 `kqakqakqa` 的模板代码】：
- 注入一层自绘 `logFrame`（遮罩 + iframe + 逐条 log + 复制按钮 + 仓库链接 + 跳转输入框），**把导入过程做成可视化诊断台**；
- 有 `document.body.dataset["xiaoai_schedule_parser_dev_mode"]` 连点 10 次进入的**「强制通过模式」**（隐藏入口，用于审核时绕过真实教务）；
- 内置通用后处理：`mergeConflictsAndDuplicates`（拆成单周单节再合并冲突）、`mergeWeeks`、`mergeTeachersOrPositions`（>150 条时把多教室/多教师压成 `教室A(1,2周) 教室B(3周)`），**这正是小爱 50 字节/字段与条数限制逼出来的工程妥协**；
- 该校 provider 直接返回 `{courses, timetable}`，并在注释里写「parser.js 和 timer.js 不做处理, 仅转发」——**生态已经把 Parser 退化成透传**。

### 2.3 分发方式与生命周期：【事实 + 未证实】

- 分发：**云端账号绑定**。开发者在扩展里上传 → 手机端登录同一小米账号即可在「测试项目」里试 → 点「上传审核」→ 通过后对所有用户公开（转述自 zhul.in）。**没有公开的脚本仓库/索引**，脚本存在小米侧。
- 覆盖规模：**【未证实】** 2022-03 知乎文章称「已适配 1832 所学校」（https://zhuanlan.zhihu.com/p/481202014 ）。
- **已下线**：2025-04 起「教务系统导入」入口关闭（提示"产品技术方案调整，建议使用拍照导入"），2025-09 仍有用户靠 vConsole 改 hash 到 `/import` 打开残留页面（https://blog.deepchirp.com/2025/09/07/Restore-the-Academic-Import-Feature-in-XiaoAi-Schedule/ ；该文更新说明"获取学校列表的 API 已返回 405，此方法可能失效"）。

### 2.4 可复用点 / 风险

- ✅ **三函数切分（取数 / 解析 / 作息）是被验证过的职责边界**：解析可以脱离登录态单独测试、单独审核。
- ✅ **Provider 内置可视化诊断面板 + 强制通过模式**，极大降低适配调试与审核成本。
- ✅ **字段/条数上限 + 后处理合并**是移动端课表格子的现实约束，本项目若做脚本层应设同类上限。
- ⚠️ **服务端解析 = 平台单点**：小米一关功能，1200+ 校适配与用户数据一起消失；且 Parser 上云意味着**用户课表原文离开设备**（隐私面）。
- ⚠️ 无开源脚本仓库 → 生态不可 fork、不可离线，社区只能靠个人镜像（如 kqakqakqa）续命。

---

## 3. 拾光课程表（shiguangschedule / shiguang_warehouse）

### 3.1 结构与许可：【事实】

- 主仓：https://github.com/XingHeYuZhuan/shiguangschedule （Kotlin → 2.x 起 Compose Multiplatform，Android/Desktop 主支维护，iOS 实验分支）。
- 适配仓：https://github.com/XingHeYuZhuan/shiguang_warehouse ，README 社区公约明示「**本项目基于 MIT 协议开源**」；主仓 license badge 为 MIT。**MIT 对闭源商用友好**，但注意：① 适配脚本是社区逐校贡献，仓库根 LICENSE 覆盖全仓，仍建议逐个脚本确认署名；② 引用需保留贡献记录（README 明确要求），这与 MIT 无冲突但属社区规范。
- 工具链：Wiki「如何适配教务 v2」+ 浏览器测试插件 https://github.com/XingHeYuZhuan/shiguang_Tester + 类型声明 `scripts/types/shiguang-bridge.d.ts` + YAML JSON Schema（`scripts/schemas/*.json`）。

### 3.2 脚本接口约定：【事实】不是"函数名约定"，而是"桥接对象 + 顶层自执行"

**没有** `getSchedule()` 之类的入口函数名要求。脚本被注入到 WebView（`import_url` 指定的**登录页**）后**顶层直接执行**，自己编排流程，通过全局对象与原生通信：

```js
window.shiguangBridgePromise            // 异步，返回 Promise
  .showAlert(title, content, confirmText)              -> boolean
  .showPrompt(title, tip, defaultText, validatorFnName)-> string | null
  .showSingleSelection(title, itemsJsonString, defaultIndex) -> number | null
  .saveImportedCourses(coursesJsonString)              -> true（失败 throw）
  .savePresetTimeSlots(timeSlotsJsonString)            -> true（必须先于 saveComboSchedule）
  .saveCourseConfig(configJsonString)                  -> true
  .saveComboSchedule(comboScheduleJsonString)          -> true
window.shiguangBridge                   // 同步
  .showToast(message)
  .notifyTaskCompletion()               // 生命周期结束信号，仅在成功时调用
// 兼容别名：AndroidBridgePromise / AndroidBridge（已 deprecated）
```

数据契约（v2）：

```jsonc
// 课程（每条形如一门课在一个时段）
{ "name":"高等数学", "teacher":"张教授", "position":"教101", "day":1,
  "startSection":1, "endSection":2, "weeks":[1,2,3,4],
  "isCustomTime":false, "customStartTime":"08:00", "customEndTime":"08:45", "credit":2.5 }
// 时间段
[{ "number":1, "startTime":"08:00", "endTime":"08:45" }]   // number 必须从 1 连续递增、不重叠
// 课表配置
{ "semesterStartDate":"2024-09-01", "semesterTotalWeeks":18,
  "defaultClassDuration":45, "defaultBreakDuration":10, "firstDayOfWeek":1 }
// 组合作息（夏令时/冬令时）
{ "name":"2025 学年作息方案", "publicSchedules":[{ "name":"夏季作息","startDate":"2025-05-01",
  "endDate":"2025-09-30","timeSlots":[{ "number":2,"startTime":"09:00","endTime":"09:45" }] }] }
// 差量合并：以 savePresetTimeSlots 的骨架为准，模板只提交差异节次
```

真实适配器示例（浙工大，https://raw.githubusercontent.com/XingHeYuZhuan/shiguang_warehouse/main/resources/ZJUT/zjut_01.js ）：文件就是**裸 JS 脚本**——常量（`BASE`/`GNMKDM`/`TIME_SLOTS`）+ `async function req()`（`fetch` + `credentials:'include'`）+ `parseWeeks('4-6周(双)')` + `parseCourses(kbList)` + 顶层 `run()`；**不 import、不 export、不需要任何注册**，`run()` 内部依次调 `saveCourseConfig` → `savePresetTimeSlots` → `saveImportedCourses` → `notifyTaskCompletion`。

### 3.3 脚本如何组织 / 更新：【事实】Git 仓库 + 编译索引 + 客户端拉取

```
shiguang_warehouse/
├─ index/root_index.yaml          # id / name / initial / resource_folder（25,001 B，未逐条计数）
├─ proto/school_index.proto       # protocol_version / version_id / repeated School{ adapters[] }
├─ resources/<SCHOOL_ID>/
│   ├─ adapters.yaml              # 7 个必填字段
│   └─ *.js                       # 适配脚本
├─ scripts/build_data.py          # 编译 school_index.pb
└─ .github/workflows/build-index.yml
```

`adapters.yaml` 字段（全必填）：`adapter_id` / `adapter_name` / `category`（`BACHELOR_AND_ASSOCIATE`｜`POSTGRADUATE`｜`GENERAL_TOOL`）/ `asset_js_path` / `import_url`（**必须是登录页**）/ `maintainer` / `description`。

更新链路：`index-pb-release` **孤儿分支**存 `school_index.pb`（索引协议 **v2**）→ App 只拉「索引 + `resources/**/*.js`」→ 客户端按 `protocol_version` 做兼容。分支保护：PR 必须提到 `pending` 分支；`main` 受保护。开发者在 App「更多」页**连点图标 5 次**开开发者功能 → 「我的-更多-更新仓库」填**自定义/私有仓库**做 Beta 验证。`resources/GLOBAL_TOOLS/test.js` 是"免编译索引"的占位测试位。

**`root_index.yaml` + `adapters.yaml` + `.pb` 索引 + `.js` 资源，"配置与代码分离 + 索引版本化"是本调研里最干净的可热更新设计。**

### 3.4 通用教务（关键发现）：【事实】

`resources/` 下除学校目录外，还有 4 个**通用教务适配器**（`category: GENERAL_TOOL`，`import_url: ""`）：

- `zhengfang_jiaowu/`「正方教务html通用获取」：`description: "输入教务网址，登录，切换到个人课表页面点击执行导入"`
- `urp_jiaowu/`、`qingguo_jiaowu/`、`chaoxing_jiaowu/`

**这就是"通用类型兜底"的工程实现**：不绑定学校域名，由用户输入 URL、自己登录、切到课表页，脚本再执行。与本项目「WebView 登录 + 抓取」的形态完全同构，**可直接照搬这个分层**。

### 3.5 可复用点 / 风险

- ✅ 桥接 API 的设计（异步 Promise + 原生 UI 回调 + 生命周期信号）比"三固定函数"更灵活，能表达验证码、学期选择、公告确认等交互。
- ✅ YAML 配置 + Protobuf 索引 + 孤儿分支发布 + 自定义仓库测试，**一套组合解决"热更新 / 版本兼容 / 贡献者自测"**。
- ✅ TS 类型声明（`shiguang-bridge.d.ts`）让脚本作者在 IDE 里有补全，降低贡献门槛。
- ⚠️ 任意 JS 在**已登录教务页面的 origin** 上执行 → 脚本可读 cookie、可发任意请求。**必须有脚本签名/白名单/权限提示**，否则一个恶意 PR 即可批量窃取教务凭据（**【推测】**风险，未见官方签名机制）。
- ⚠️ 索引与脚本分离意味着**旧客户端可能拉到不兼容的新脚本**，协议版本号是唯一护栏。

---

## 4. 下节啥课（baoozak/timetable）

### 4.1 组织方式：【事实】"provider 字符串 + parser 函数"配对，7 大教务 10 个文件

`utils/parsers/index.js`（https://raw.githubusercontent.com/baoozak/timetable/main/utils/parsers/index.js ）集中注册 10 个适配器：

| 文件 | 覆盖 |
|---|---|
| `zhengfang_new.js` | 正方新版 jwglxt |
| `urp.js` / `urp_new.js` | URP 新老 |
| `kingosoft.js` / `kingosoft_new.js` | 青果新老 |
| `qiangzhi.js` / `qiangzhi_old.js` | 强智新老 |
| `wisedu.js` | 金智 |
| `south_soft.js` | 南软 |
| `chaoxing.js` | 超星 |

每个模块固定导出 `{ id, name, provider, parser }`：

- `provider` 是**一段字符串形式的 IIFE**，被注入 WebView 在**目标教务页面上下文**里执行。以 `zhengfang_new.js` 为例：读 `#xnm/#xqm` 选中学年学期（读不到就按当前月份猜 `3`/`12`）→ 校验 `location.href` 含 `jwglxt`（否则 `document.title='KEBIAO_ERR:...'` 直接返回）→ 推导 `/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N253508` → `XMLHttpRequest` + `withCredentials=true` POST `xnm=&xqm=` → 把 `resp.kbList` 压成**紧凑串**：`kcmc~xqj~jcs~cdmc~zcd~xm`，多条用 `|` 连接，并对 `~|` 做替换消毒 → **`document.title = 'KEBIAO_OK:' + parts.join('|')`**。
- `parser(compactStr)` 在 uni-app 侧执行，拆 `|`/`~`，调 `parseWeeks()`，输出 `{ id, name, day, startNode, endNode, room, teacher, weeks, weeksText }`。
- 学校索引：`utils/schools.json`（README：「内置支持全国上千所高校的教务系统网址 … 支持快捷搜索与一键匹配」），页面 `pages/import/select-school.vue` + `import.vue`（WebView 登录后抓取）。
- 许可：**MIT**（README 明示，65 stars，uni-app/Vue3）。

### 4.2 可复用点 / 反模式

- ✅ **「紧凑串 + 分隔符消毒」**把结构化课表压成一行字符串回传，比 JSON 过桥便宜，且天然规避引号转义问题。
- ✅ **`provider` 与 `parser` 分离**：前者只需懂页面/接口，后者可离线单测（喂一段 compactStr 即可）。
- ✅ 注册表 + `id` 常量，新增教务 = 加一个文件 + 一行 import。
- ⚠️ **`document.title` 当传输通道**：标题长度受 WebView/宿主限制，课程多时可能被截断或触发重排；且任何页面脚本改 title 都会污染通道（**【推测】**，代码里靠 `KEBIAO_OK:`/`KEBIAO_ERR:` 前缀区分）。**这是明确反模式，不要学**。
- ⚠️ 适配器随 App 发版，**无热更新、无版本号**；学校换系统只能等新版本。

---

## 5. ClassIsland 与超级课程表

### 5.1 ClassIsland：【事实】不做教务导入，只做文件交换

- 导入：**直接拖文件进「档案编辑」窗口**；README 明示「从 **Excel 表格**、**CSES**（https://github.com/SmartTeachCN/CSES ）或其他软件导入课表」；导出到 Excel。文档页 https://docs.classisland.tech/app/profile/ 只讲「拖入导入」+ 档案/时间表/科目模型，**未见 ICS / JSON 导入**。
- 模型值得借鉴：**档案（课表 + 时间表 + 科目）**三元组 + 时间点四类型（上课/课间/分割线/行动）+ 多周轮换 + 单日/跨天临时换课 + 预定调课。这是"课表域模型"最完整的一份。
- 教务导入的地位：Issue #1279「加入正方教务系统导入功能」（2025-08-31，理由写着「**小爱课程表停止教务系统导入了**」）→ 2025-11-17 由维护者 **closed as not planned**，理由「不属于 ClassIsland 主线开发目标，应该由插件实现」（https://github.com/ClassIsland/ClassIsland/issues/1279 ）。
- 许可：**应用本体 GPLv3；`ClassIsland.Core`/`PluginSdk`/`Shared*` 为 LGPLv3**（README）。→ **对本项目风险最高的一份代码：任何 GPLv3 代码进 Android app 都可能触发整体开源义务，LGPLv3 部分需动态链接+可替换。只能读思路，不能抄实现。**
- 社区变体：`Leaf-lsgtky/miuix-ClassIsland` 自行加了 **`.ics` 导入**——说明"ICS 是社区补的，不是主线能力"。

### 5.2 超级课程表：【事实】规模最大，机制最封闭

- 规模：App Store 更新日志反复写「本次新增/修复 N 所大学从系统自动导入课表。**累计支持 3618 所大学自动导入课程表**，2443 所高校一键从官方导入各大考试时间」（https://apps.apple.com/us/app/id573868981 ；同文案见 jb51/pc6 等镜像）。这是全行业**最大**的公开数字。
- 导入路径：首页课表 → 菜单「教务系统导课」→ 进教务门户 → 登录 → 抓取（第三方教程 https://soft.zol.com.cn/1253/12534080.html ）；另有扫码/口令/手动等入口。
- **无公开适配规范、无脚本生态、无学校列表文档**；百度百科词条只写「可登录高校教务系统自动导入课表」（https://baike.baidu.com/item/超级课程表-表表/16853910 ）。→ **纯自建、纯闭源，不可复用，只能作为"规模上限"参照。**

---

## 6. 其他值得参考的实现与格式

### 6.1 事实标准：正方 `kbList` 是上游"通用语"

小爱模板、拾光 `zjut_01.js`、下节啥课 `zhengfang_new.js`、本项目的 `ZhengFangAdapter` **四处独立实现都在解同一组字段**：

```
kcmc(课程名) xqj(星期) jcs(节次 "1-4") cdmc(教室) zcd(周次 "4-6周(双),7-11周")
xm(教师) xqh_id(校区)  ← 新版 jwglxt 的 /kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N253508
```

→ **把"正方新版 JSON"作为第一等公民，能一次性覆盖大量学校**；这也是本项目现有默认适配器的正确方向。

### 6.2 已出现的"交换格式"

| 格式 | 来源 | 现状 |
|---|---|---|
| 拾光导出 JSON `{courses[], timeSlots[], config{}}` | 拾光主仓 | 已被第三方 Yunzai 插件直接支持导入/导出（https://github.com/Temmie0125/Yunzai-Schedule-Plugin ），**事实上的互操作格式** |
| 小爱 Parser JSON `[{name,position,teacher,weeks,day,sections}]` | 小米 | 生态最广但平台已下线，只剩历史价值 |
| `CSES` 课表 | https://github.com/SmartTeachCN/CSES | ClassIsland 原生支持，偏"中小学/班级大屏"场景 |
| `.wakeup_schedule` / WakeUp 备份文件 | WakeUp | 私有格式，第三方靠逆向（WakeUp2ICS 已支持备份文件解析） |
| 星链课表口令 | 星链课表 | 第三方插件已支持，**未证实**其编码方式 |

### 6.3 ICS / CalDAV 在课表场景的局限 【分析，基于 RFC 5545】

RFC 5545（https://datatracker.ietf.org/doc/html/rfc5545 ）能表达"每周重复"，但**没有"教学周"概念**，必须把 `weeks` 展开成日历日期：

- **单双周**：`RRULE:FREQ=WEEKLY;INTERVAL=2` 可表达，但要求周次**从首周起等间隔**；`[1,3,5,7,9]` 可行，`[2,4,6]` 需把 DTSTART 落到第 2 周。
- **不连续周**（`1-8,10-16`，极其常见）：只能拆多个 `VEVENT`，或用 `RDATE` 逐周枚举 / `EXDATE` 排除——文件体积与"一节课一条记录"的语义都会崩。
- **调课/停课/节假日**：ICS 无"临时换课"语义，只能新增/删除单次实例（`RECURRENCE-ID` 覆盖），且大多数日历 App 不保证写回。
- **时区与夏令时**：需 `VTIMEZONE` + 本地时间；跨时区/设备导入常错位（社区文章普遍反馈）。
- **CalDAV 双向同步**：读写支持参差，服务端不保证 `RRULE` 往返一致；课表 App 拿不到"第几周"上下文。
- **结论**：**ICS 只适合"导出给系统日历看"，不能作为导入/存储的主格式**。本项目现有 ICS 导入/导出应定位为"单向兼容层"。

### 6.4 其他可读实现

- `openschoolcn/zfn_api`（新正方教务 API，Python）+ `zfn-api-go`：把登录/验证码/课表/成绩封装成 SDK（https://github.com/openschoolcn/zfn_api ）——**可参考其"接口分层 + 验证码策略"，但 Python 侧无法直接移植**。
- `Pure课程表`（Gitee hztv/Schedule）：明确支持三类在线导入——本地模拟登录、WebView 指定网页解析、**基于导入 JS 的解析（兼容小爱课程表脚本）**（https://gitee.com/hztv/Schedule ）。→ **"兼容小爱脚本"是被验证过的第三方脚本复用路径**，若本项目做脚本层，可优先兼容小爱三函数形态（生态存量最大、规范最完整）。
- 一批「教务课表 → ICS」小工具（`Feldan/ZFClassTable2ICS`、`LengerHu/CQU_classtabletoics`、`yujqiao/hnu-courses-to-ics`、Chrome 扩展「正方教务课表转 ICS」）：都是"一次性转换"路线，**说明轻量需求真实存在，且用户愿意自己动手**。

---

## 7. 对比表

| 产品 | 覆盖规模（证据） | 适配机制 | 脚本 / 数据格式 | 更新方式 | 可借鉴点 | 许可风险 |
|---|---|---|---|---|---|---|
| **WakeUp** | 官网 1800+／文档 1200+／商店 3000+（**口径冲突**）；名单页 2022-02-22 | 学校专用 → **通用教务类型** → 可改 URL 的 WebView → HTML/Excel/CSV 文件 → 手动/口令；失败可「申请适配」 | 早期开源版=每校 Retrofit 接口（Host 硬编码）；现行=闭源、**口令为 32 位短码 + api.wakeup.fun 服务端 + 反作弊** | 服务端下发学校配置/`getupdate`；用户上传 HTML 众包适配 | 五级兜底、通用教务类型、可改 URL、`apply_html` 众包 | 闭源；逆向涉反作弊条款（灰色）；旧仓未见 license |
| **小爱课程表** | 2022 年称 1832 所（**未证实**）；**2025-04 起教务导入下线** | 学校列表（云端）+ 浏览器扩展开发 + 人工审核上架 | **三文件三函数**：`scheduleHtmlProvider`（WebView）/`scheduleHtmlParser`（**小米服务器**）/`scheduleTimer`；Parser JSON 有 50 字节/字段、1..30 周次上限 | 脚本存小米云端，随账号下发，**不可 fork、不可离线** | 职责三分、Provider 可视化诊断台、强制通过模式、字段上限+后处理合并 | 闭源；Parser 上云=课表原文出设备 |
| **拾光课程表** | 未公开数字；`root_index.yaml` 25 KB + 按校目录 + **4 个通用教务**（正方/URP/青果/超星） | 学校列表 + 通用工具（`GENERAL_TOOL`，用户自填 URL）+ 文件（JSON/ICS） | **桥接 API**（`window.shiguangBridgePromise` 6 方法 + 2 同步方法），脚本顶层自执行；数据=`{courses[],timeSlots[],config{}}` | Git 仓库 + YAML 配置 + **Protobuf 索引（协议 v2，孤儿分支发布）** + 客户端拉取；支持自定义/私有仓库自测 | **配置/代码分离 + 索引版本化 + 类型声明 + 浏览器测试插件**；通用教务兜底 | **MIT**（可商用，需保留署名与贡献记录）；脚本无签名机制→凭据风险 |
| **下节啥课** | 未公开数字；`schools.json` 称「上千所」 | 学校列表 + WebView 登录后注入 `provider` | 每教务一文件 `{id,name,provider(字符串 IIFE),parser(compactStr)}`；紧凑串 `kcmc~xqj~jcs~cdmc~zcd~xm`，`|` 分隔 | **随 App 发版**，无热更新 | provider/parser 分离、紧凑串+消毒、注册表 | **MIT**（65★，uni-app） |
| **ClassIsland** | 非课表导入向（班级大屏） | **不做教务导入**；拖入 Excel / CSES 文件 | 档案 = 课表+时间表+科目；Excel/CSES | 随版本；社区 fork 才加 ICS | **课表域模型**（时间点四类型、多周轮换、预定调课） | **GPLv3 + LGPLv3** → 只可读思路，不可抄实现 |
| **超级课程表** | **3618 所**自动导入（App Store 更新日志） | 自建闭源抓取；首页→教务系统导课→登录 | 无公开规范 | 不公开 | 规模上限参照、大厂级学校库 | 完全闭源，无复用可能 |

---

## 8. 业界共识（3–5 条）

1. **「学校列表 + 通用教务类型 + 文件兜底」是标准三层**，缺任何一层都会在"学校换系统 / 名单过期 / 校园网限制"时掉用户。WakeUp 把三层写进文档并教用户自己判断教务类型；拾光把通用层做成 `GENERAL_TOOL` 适配器；本项目目前只有第一层的默认项（正方）和第三层（文件/口令），**第二层是最大缺口**。
2. **解析跑在"已登录会话的页面上下文"里，是投入产出比最高的位置**；谁掌握登录态谁就免去验证码/加密/单点登录的重放工程。WakeUp 早期硬编码 `Host` + `__VIEWSTATE` 的做法（每校编译期接口）已被证明不可扩展。
3. **中间态先统一，传输态可以私有**。所有产品最终都收敛到 `{name, teacher, position, day, sections[], weeks[]}` + `timeSlots` + `semesterConfig`；差异只在怎么把这段从 WebView 搬回原生（桥接对象 / JSON / 紧凑串）。
4. **生态化 = 覆盖规模，但必须配"配置与代码分离 + 索引版本化 + 自测通道"**。拾光的 YAML+PB 索引+自定义仓库、小爱的扩展+审核，都是为了让"非核心开发者"能独立交付一个学校而不碰主仓代码。
5. **课表导入是长期维护负债，不是一次性功能**。小爱直接下线、ClassIsland 明确拒绝并入主线、WakeUp 官方承认名单会过期——**能力必须可插拔、可降级、可远程关停**。

## 9. 反模式（务必避开）

1. **服务端集中解析 + 短码口令**：厂商一加鉴权/反作弊（WakeUp 2026-04）或平台一下线（小爱 2025-04），适配与用户数据同时归零，第三方生态被"顺手打死"。本项目**不要**把解析放服务端，也**不要**承诺解析 WakeUp 现行口令。
2. **拿 `document.title` 当数据通道**（下节啥课）：长度受限、易被污染、无错误码空间（只能塞 `KEBIAO_ERR:` 前缀）。应改用桥接对象/`postMessage`/自定义 scheme。
3. **学校列表不及时更新却不给"通用类型"出口**：用户只会看到"导入失败"，官方文档还得专门写一节教用户自己辨认教务厂商——这是把适配成本转嫁给用户。
4. **任意 JS 在登录态 origin 上执行，且无签名/无审核/无权限提示**：等于把教务账号密码暴露给任何提 PR 的人（**【推测】**，拾光未见签名机制；小爱靠云端审核缓解）。
5. **把教务适配塞进"非核心功能"**：它一定会在某次产品调整里被砍（小爱），或被维护者明确划到主线之外（ClassIsland #1279）。要么单独立项维护，要么做成插件/脚本层。

---

## 10. 对「纸间课表」的落地建议（只给结论，不改代码）

**现状**：`JwAdapter`（`id/name/supports(url)/fetch(client, baseUrl, totalWeeks)`）+ `JwAdapterRegistry`（未匹配一律回落 `ZhengFangAdapter`）+ WebView Cookie 共享重放 + `JwSessionRecovery` 静默重登 + 已有的 Excel/CSV/JSON/ICS/WakeUp 口令/OCR/二维码导入。这套"原生重放 + 会话恢复"其实**比多数竞品更稳**（不依赖脚本、不怕厂商改前端框架），短板是**覆盖广度**与**适配边际成本**。

按性价比排序的建议：

1. **补"通用教务族"适配器（最高优先，纯 Kotlin，零新依赖）**：把 `ZhengFangAdapter` 拆成 `zhengfang_new / zhengfang_old / qiangzhi / urp / kingosoft / wisedu / southsoft / chaoxing`，每个只负责「发现课表接口 + 解析」，共用现有 Cookie 重放与 `supports()` 特征匹配（域名含 `jwglxt`/`jsxsd`/`jw.`、页面标题、登录页 form 字段名）。
2. **补第二层兜底 UX**：`supports()` 全不中时，不要静默回落正方，而是**让用户从通用类型里选**（附"看页脚厂商名"的识别指引，直接借 WakeUp 文档的表述），并允许用户改 URL。这是零成本提升成功率的一步。
3. **把中间态补齐到"可交换"级别**：现有 `CourseDraft` 之上补 `timeSlots[]` + `semesterStartDate/totalWeeks/firstDayOfWeek`，并新增一个**拾光兼容 JSON 导出/导入**（`{courses[], timeSlots[], config{}}`）。理由：该格式已被第三方实现采纳，是当下最省事的互操作面；MIT 且只是数据格式，无许可风险。
4. **中期再评估脚本层**：若确要覆盖长尾学校，走**"WebView 内注入 + 桥接 API + 远端仓库拉脚本"**（拾光模型），并至少加：脚本签名/哈希白名单、`protocol_version` 兼容、脚本来源明示、执行前用户确认、失败自动回退文件导入。**优先兼容小爱三函数形态**（生态存量最大，Pure 课程表已验证可行），而不是自创规范。
5. **明确不做**：服务端解析、WakeUp 现行短码口令（`api.wakeup.fun` + APK 签名 + AndroidID + 反作弊，逆向成本与法律风险都不划算）。现有 `WakeUpParser` 的离线 base64+zlib 容错保留即可，文案降级为"兼容旧版口令/粘贴文本"。
6. **格式定位**：ICS 只做导出（给系统日历），不做导入主格式；JSON 做备份/交换；教务抓取结果是权威源。

---

## 附录 A：证据链接清单

**WakeUp**：官网 https://www.wakeup.fun/ ｜文档首页 https://www.wakeup.fun/doc/ ｜已适配学校（2022-02-22） https://www.wakeup.fun/doc/adapt_android_hmos.html ｜教务导入 https://www.wakeup.fun/doc/import_from_eas.html ｜文件导入 https://www.wakeup.fun/doc/import_from_file.html ｜CSV 导入 https://www.wakeup.fun/doc/import_from_csv.html ｜隐私政策（剪贴板/口令） https://www.wakeup.fun/policy_new.html ｜FAQ https://www.wakeup.fun/doc/faqs.html ｜旧版源码 https://github.com/tKM9WsmQUaUgNttn3DGUsHkxG8/WakeupSchedule_Kotlin （`schedule_import/ImportService.kt`、`ImportRepository.kt`、`utils/MyRetrofitService.kt`、`utils/ExportUtils.kt`）｜原始版 https://github.com/YZune/WakeUpSchedule ｜口令逆向 https://github.com/airline233/WakeUp2ICS 、https://github.com/airline233/WakeUpDecoder

**小爱课程表**：官方文档 https://open-schedule-prod.ai.xiaomi.com/docs/#/help/ ｜实操长文（三函数/规范/上传审核） https://zhul.in/2024/11/18/mi-ai-class-schedule-adapter-for-zjut/ ｜社区脚本仓 https://github.com/kqakqakqa/xiaoai-schedule-parser ｜北航研究生适配（三文件确认） https://github.com/zhangkaihua88/BUAA-Postgraduate-Aischedule ｜下线考据 https://blog.deepchirp.com/2025/09/07/Restore-the-Academic-Import-Feature-in-XiaoAi-Schedule/ ｜1832 所（未证实） https://zhuanlan.zhihu.com/p/481202014

**拾光课程表**：主仓 https://github.com/XingHeYuZhuan/shiguangschedule ｜适配仓 https://github.com/XingHeYuZhuan/shiguang_warehouse ｜Wiki 适配指南 v2 https://github.com/ShiGuangSchedule/shiguangschedule/wiki/%E5%A6%82%E4%BD%95%E9%80%82%E9%85%8D%E6%95%99%E5%8A%A1v2 ｜类型声明 `scripts/types/shiguang-bridge.d.ts` ｜索引协议 `proto/school_index.proto` ｜示例脚本 `resources/ZJUT/zjut_01.js` ｜通用教务 `resources/zhengfang_jiaowu/adapters.yaml` ｜测试插件 https://github.com/XingHeYuZhuan/shiguang_Tester

**下节啥课**：https://github.com/baoozak/timetable （README 含 7 大教务/`schools.json`/MIT）｜注册表 `utils/parsers/index.js` ｜正方 provider `utils/parsers/zhengfang_new.js`

**ClassIsland**：https://github.com/ClassIsland/ClassIsland （README：导入方式/许可证）｜档案编辑文档 https://docs.classisland.tech/app/profile/ ｜拒绝教务导入 Issue https://github.com/ClassIsland/ClassIsland/issues/1279 ｜CSES https://github.com/SmartTeachCN/CSES ｜社区 ICS fork `Leaf-lsgtky/miuix-ClassIsland`

**超级课程表**：https://apps.apple.com/us/app/id573868981 （3618 所，更新日志）｜官网 https://www.super.cn/ ｜导课教程 https://soft.zol.com.cn/1253/12534080.html ｜百科 https://baike.baidu.com/item/超级课程表-表表/16853910

**格式与其它**：RFC 5545 https://datatracker.ietf.org/doc/html/rfc5545 ｜拾光 JSON 互操作（Yunzai 插件，含 WakeUp 口令事件陈述） https://github.com/Temmie0125/Yunzai-Schedule-Plugin ｜正方 Python SDK https://github.com/openschoolcn/zfn_api ｜Pure 课程表（兼容小爱 JS） https://gitee.com/hztv/Schedule ｜课表转 ICS 工具示例 https://github.com/Feldan/ZFClassTable2ICS

## 附录 B：未证实 / 待复核清单

1. WakeUp 各渠道"1800+/2000+/3000+"数字互斥，**无官方覆盖率定义**（是否含研究生教务、是否含"通用类型"导入未知）。
2. WakeUp 是否具备**自动同步/定时刷新**：官方文档与 FAQ 均未见描述，**未证实**。
3. 小爱"1832 所"为 2022 年单篇知乎口径，**未证实**；官方文档站未给出覆盖数字。
4. 小爱 Parser"上传服务器运行是为防抓包"——来自博客作者的解读（zhul.in），**非官方表述**，本报告标注为转述。
5. WakeUp 2026-04 加鉴权/反作弊——来自 Yunzai 插件作者单方陈述，**无官方公告**。
6. 拾光适配仓库学校总数：`root_index.yaml` 25,001 字节，**本次未逐条计数**；`resources/` 下同时存在学校目录与 4 个通用工具目录。
7. 拾光脚本是否有签名/哈希校验：本次在仓库与 Wiki 中**未见**相关机制，标注为推测风险。
8. 星链课表口令的编码方式：仅知第三方插件支持，**未证实**其格式。
