# 中国高校教务系统厂商调研：WebView 登录 + 课表抓取可行性

> 调研时间：2026-10-19　｜　目标项目：纸间课表（Android / Kotlin）
> 范围：只做公开资料与开源实现调研，未对任何学校真实账号发起登录或抓取，未改动 App 源码。
> 证据分级：**✅ 已证实**（公开页面/可复现实现直接支持）｜**⚠️ 部分证实**（多校样本或实现存在版本差异）｜**❓ 未证实**（仅推断或单一来源）

---

## 0. 一页结论（先看这里）

1. **现有 `ZhengFangAdapter` 名不副实**：它实现的是**强智 jsxsd**（`xskb/xskb_list.do`、`#kbtable`、`div.kbcontent`、`LoginToXk`、`sso.jsp`），而不是正方。正方新版是 `/jwglxt/` + RSA + `csrf_token` + `kbList` JSON，登录与课表接口**完全不同**。第一步应是「改名 + 协议识别」，不是急着加厂商。
2. **覆盖面最大的是正方新版（zf_new）与强智（qz）**。一份 4140 条学校目录的公开统计中，有协议候选的约 1470 条：zf_new 562、qz 292（另有 12 条变体）、青果 164、超星 135、金智 54、树维系 85、正方旧版 33、URP 41、乘方 41（详见 §2）。
3. **按「WebView 登录 + Cookie 重放」架构，最容易落地的是返回 JSON 的厂商**：正方新版（`kbList`）、强智 `app.do`（token + JSON）、金智 `/jwapp/`、新 URP（`dateList/selectCourseList`）。HTML 表格厂商（强智 jsxsd、青果、正方旧版）需要结构化表格解析，成本中等。
4. **超星、树维 eams5、强智 node/IEAS 变体属于高难度**：SPA/JS 渲染、加密参数或非标准登录，不建议第一批做。
5. **建议批次**：P0 强智（现有）+ 正方新版；P1 青果 + 正方旧版 + URP（新/旧）+ 树维；P2 超星 + 金智 + 乘方；长尾只做「识别 + 明确提示」，不做半成品解析。
6. **学校清单不要自己从零建**：公开开源目录（Sleepy 4140 条、拾光 260 条、WakeUp 1200+）可作为种子，但必须保留来源与「未验证」状态，并定期用学校教务处公告校正。合规上注意教育 App 备案、PIPL 最小必要、学校用户协议与访问频率。

---

## 1. 调研口径与前提修正

### 1.1 当前实现到底覆盖了什么

`core/jw/ZhengFangAdapter.kt` 的关键事实：

- 抓取候选：`xskb/xskb_list.do`、`xskb/xskbList.do`、`kbcx/xskbcx_cxXsKb.html`。其中 `xskbList.do` 无公开证据（见 §3.2.1），`xskbcx_cxXsKb.html` 实际是正方新版路径，属于混入。
- DOM：`#kbtable`、`div.kbcontent`、`div.kbcontent1`（隐藏重复）、周次行 `[01-02-03-04节]`。
- 会话：`LoginToXk` / `sso.jsp` / `authserver`，与强智 jsxsd 的登录链路一致。
- `JwAdapterRegistry` 中 `supports()` 恒为 `true`，未匹配时也回落到它——**所有学校都会被当成强智解析**，这是当前最大的误判风险。

### 1.2 术语纠正：jsxsd 是强智，不是正方

| 说法 | 实际归属 | 证据 |
|---|---|---|
| `/jsxsd/`、`xskb_list.do`、`#kbtable` | **强智科技**（湖南强智） | 强智学校站点、公开爬虫文章、Sleepy 协议表 |
| `/jwglxt/`、`login_slogin.html`、`xskbcx_cxXsKb.html` | **正方软件**（新版） | 正方 SDK 配置、正方 API 封装 |
| `default2.aspx`、`xskbcx.aspx`、`__VIEWSTATE` | **正方软件**（旧版 ASP.NET） | 旧版正方爬虫文章 |
| `/jwweb/`、`KINGOSOFT` | **青果软件** | 多校 `jwweb` 入口、青果官网 |

因此后文一律用「正方新版 / 正方旧版 / 强智 jsxsd」区分，避免把两家的路径混为一谈。

### 1.3 证据来源与局限

- 主要依赖公开开源项目实现的**协议指纹表**与**解析器**，其次才是博客/学校页面。
- 教务系统「一校一部署」，同一厂商不同学校路径、验证码、SSO 都可能不同；本报告的路径均为**常见形态**，不是每校保证。
- 没有覆盖：需要双向证书、硬件 UKey、强制校内网/VPN 的部署；这类只能靠 WebView 内网访问或用户手动导入。

---

## 2. 全景与覆盖规模

### 2.1 厂商分层

| 层级 | 厂商/产品 | 典型客户 | 课表获取形态 |
|---|---|---|---|
| 第一梯队 | 正方软件（jwglxt / 旧 ASP.NET） | 本科为主，覆盖最广 | JSON（新）/ HTML 表格（旧） |
| 第一梯队 | 强智科技（jsxsd / 教学一体化） | 本科 + 高职，广东样本多 | HTML 表格 + `app.do` JSON |
| 第一梯队 | 青果软件 KINGOSOFT（jwweb / V17） | 本科、高职、民办 | HTML 表格 |
| 第一梯队 | 金智教育（wisedu，`/jwapp/`） | 本科，常与统一身份认证绑定 | JSON |
| 第二梯队 | 清元优软 URP（老/新） | 本科 | HTML（老）/ JSON（新） |
| 第二梯队 | 树维科技 EAMS（classic / eams5 / shuwei_json） | 本科、部分高职 | HTML + JSON + SPA |
| 第二梯队 | 超星（`*.jw.chaoxing.com`） | 高职、民办、部分本科 | SPA + JSON |
| 第三梯队 | 乘方（cf，`/xsgrkbcx`） | 区域院校 | HTML/JSON 混合 |
| 长尾/非教务 | 南软 GMIS（研究生）、新中新、康赛、三盟、万维、汇文、THEOL | — | 见 §3.9 |

### 2.2 覆盖规模证据（重要，但要看口径）

来源：开源课表 App「Sleepy」公开的学校目录与协议裁决文档（数据基准 2026-10-14）：
[`docs/schools-list.md`](https://github.com/lingion/sleepy/blob/main/docs/schools-list.md)、[`docs/catalog/PROTOCOL_MAPPING.md`](https://github.com/lingion/sleepy/blob/main/docs/catalog/PROTOCOL_MAPPING.md)。

- 目录共 **4140 条**（含研究生、别名、重复校区），其中已实现配置 350 条、待验证 1128 条、待适配 2662 条。
- 有协议候选的约 **1470 条**，分布如下（同一行可能多候选，统计的是候选出现次数）：

| 协议族 | 条目数 | 说明 |
|---|---:|---|
| `zf_new` | 562 | 正方新版（jwglxt） |
| `qz` | 292 | 强智 jsxsd；另有 `qz_with_node` 7、`qz_br` 3、`qz_old` 2 |
| `kingo_new` | 164 | 青果（KINGOSOFT） |
| `chaoxing` | 135 | 超星综合教务 |
| `wisedu` | 54 | 金智教务（`/jwapp/`） |
| `classic_eams` | 48 | 树维经典 EAMS |
| `shuwei_json` | 34 | 树维 JSON 变体 |
| `zf` | 33 | 正方旧版 ASP.NET |
| `urp_new` | 31 | 新 URP |
| `urp` | 10 | 老 URP（`xkaction.do`） |
| `cf` / `cf_new` | 29 / 12 | 乘方 |
| `eams5` | 3 | 树维 eams5（SPA） |

> 口径提醒：这是「目录条目」不是「独立高校数」，且 2662 条待适配没有协议证据。它适合判断**相对优先级**，不适合对外宣称覆盖率。

其他规模参照：
- WakeUp 官方文档称适配「十多种通用教务、1200+ 所高校」（2022 年列表，已含东莞职业技术学院）：[从教务系统导入](https://www.wakeup.fun/doc/import_from_eas.html)、[已适配学校](https://www.wakeup.fun/doc/adapt_android_hmos.html)。
- 拾光课程表适配仓库 `root_index.yaml` 收录 **260 个条目**，其中 4 个是通用厂商适配器（正方/超星/青果/URP）：[shiguang_warehouse](https://github.com/ShiGuangSchedule/shiguang_warehouse)。

---

## 3. 分厂商详解

### 3.1 正方软件（ZhengFang / zfsoft）

#### 3.1.1 新版 jwglxt（`zf_new`）

- **域名/路径指纹** ✅：`/jwglxt/`、`/xtgl/`、`login_slogin.html`、`/kbcx/`、`xskbcx_cx`；页面标题常见「教学管理信息服务平台」、前端资源 `zftal-ui`。
- **登录方式** ⚠️：
  - 本地账号：登录页 `/jwglxt/xtgl/login_slogin.html`；先取 CSRF（页面隐藏域 `csrftoken`）与 RSA 公钥 `/jwglxt/xtgl/login_getPublicKey.html`（`modulus` + `exponent`，exponent 常见 `AQAB`），密码 RSA 加密后以 `yhm` / `mm` / `csrf_token` 表单提交，服务端 302 + 新 `JSESSIONID`。[新版正方登录实现](https://blog.revincx.icu/posts/new-jw-analysis/index.html)
  - 验证码：不是所有学校都开；有的部署登录即带图形码，有的（据单一博客）错 3 次后才出码，浙江工业大学等使用**滑块拼图**。[浙工大正方入口](http://www.gdjw.zjut.edu.cn/)
  - 统一身份认证：大量学校把入口收口到 CAS/信息门户（如 `vpn`/`authserver`），WebView 里表现为跨域跳转。[示例通知](https://wxzx.jift.edu.cn/info/1289/1092.htm)
- **课表获取** ✅：`POST /jwglxt/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151`，表单 `xnm`（学年）、`xqm`（学期，第一学期常为 `3`、第二学期 `12`），返回 **JSON**：`kbList[]`（`kcmc` 课程名、`xm` 教师、`xqj` 星期、`jc` 节次、`zcd` 周次、`cdmc` 地点、`xqmc` 校区、`kch_id`、`jxbmc` 教学班），另有 `xsxx`（学号/姓名）与 `sjkList`（实践课）。[zfn_api 实现](https://github.com/openschoolcn/zfn_api/blob/main/zfn_api.py)
  - 也存在 HTML 表格形态（`#table1`、`.festival`、`td[id]`、`.title`、`<p title="教师|上课地点|节/周">`），多见于新旧过渡版本。[CourseAdapter NewZFParser](https://github.com/liuzhenghang/CourseAdapter)
- **开源可参考**：
  - [openschoolcn/zfn_api](https://github.com/openschoolcn/zfn_api)（Python，MPL-2.0，登录+课表+成绩，兼容多校）
  - [FarmerChillax/new-school-sdk](https://github.com/FarmerChillax/new-school-sdk)（Python，MIT，自动识别/处理滑块与图形验证码）
  - [Cheukfung/ZFCrawler](https://github.com/Cheukfung/ZFCrawler)（Java，MIT，主打「一份代码通用多校正方」）
  - [liuzhenghang/CourseAdapter](https://github.com/liuzhenghang/CourseAdapter)（Kotlin，MIT，WakeUp 的解析库，含 NewZFParser）
- **难度**：**低-中**。登录交给 WebView，App 侧只需带 Cookie POST 取 JSON；坑在学期参数 `xnm/xqm` 的来源、部分学校禁 POST 或改字段、验证码/滑块（用户手动过）。
- **证据状态**：路径与 JSON 字段 ✅；「所有学校都无验证码」❌（不成立）。

#### 3.1.2 旧版正方（`zf`，ASP.NET）

- **指纹** ✅：`default2.aspx`、`xskbcx.aspx`、`xs_main`、`__VIEWSTATE`、验证码 `CheckCode.aspx`。
- **登录**：ASP.NET WebForm，必须携带 `__VIEWSTATE` / `__EVENTVALIDATION`，通常有图形验证码；页面编码 GBK 居多。
- **课表**：HTML 表格，`#table1` 行首 `.festival` 节次、列 `td[id]` 为星期、课程名 `.title`。[旧版正方爬虫](https://www.cnblogs.com/blog567/p/12374645.html)
- **开源**：[Cheukfung/ZFCrawler](https://github.com/Cheukfung/ZFCrawler)（MIT）、[CourseAdapter ZhengfangParser](https://github.com/liuzhenghang/CourseAdapter)（MIT）。
- **难度**：**中**。WebView 登录后重放要处理 ViewState/编码/Referer；表格可能有合并单元格。
- **判断**：33 条候选，占比不高，但和正方新版同属一家的「旧资产」，可复用同一套表格解析器。

#### 3.1.3 研究生系统

- 正方研究生系统与本科**通常不是同一套**：独立域名、独立库、课表字段更少（无行政班、课程周次不规则）。
- WakeUp 文档明确提醒「注意区分研究生教务」；Sleepy 目录把「XX大学 - 研究生」单列，且大多数是「研究生·待适配」。[WakeUp 导入说明](https://www.wakeup.fun/doc/import_from_eas.html)
- **结论**：不要假设本科适配器能直接用于研究生；先按域名/标题识别，再决定是否单独适配。❓「正方研究生统一接口」未证实。

---

### 3.2 强智科技（QiangZhi / qz，湖南强智）

#### 3.2.1 jsxsd（当前已覆盖）

- **指纹** ✅：`/jsxsd/`、`/jxd/`、`logon.do`、`verifycode.servlet`、页脚「湖南强智科技发展有限公司」/`qzdatasoft.com`；页面标题常见「教学一体化服务平台」。
- **登录** ⚠️：本地账号 + 图形验证码（`verifycode.servlet`），登录动作常见 `LoginToXk`；也有学校走 `sso.jsp → authserver` 的 CAS 链路（当前 `JwSessionRecovery` 正是为此写的）。
- **课表** ✅：
  - HTML：`GET /jsxsd/xskb/xskb_list.do?Ves632DSdyV=NEW_XSD_PYGL`，返回整页 HTML，`#kbtable`、`div.kbcontent`、周次行 `[01-02节]`。[强智课表爬取](https://www.jet-lab.site/blogs/qiangzhi)
  - JSON（移动端）：`app.do?method=authUser&xh=&pwd=` 取 `token`，再 `app.do?method=getKbcxAzc&xh=&xnxqid=&zc=` 并带 `token` 头，返回 `kcmc/kcsj/kkzc/jsxm/jsmc/sjbz`（`kcsj` 形如 `10102` = 星期 1 第 1-2 节）。同上来源。
  - `xskbList.do`（大写 L）：**❓未证实**。公开资料与代码检索均未找到该路径，当前代码里的候选应视为兼容猜测，不要写进对外文档。
- **开源**：[inannan423/classCrawl](https://github.com/inannan423/classCrawl)（Python，MIT，强智课表）、Sleepy `JwQzParser` 等（GPL-3.0，仅可读协议）。
- **难度**：**低**（已有实现）。坑：页面编码、`kbcontent1` 隐藏重复、周次表达（单双周/跳周）、iframe、验证码与 SSO。

#### 3.2.2 新版强智 / 教学一体化

- **重要事实** ✅：Sleepy 的协议表把「强智科技 / 教学一体化服务平台 / qzdatasoft.com」统一判为 `qz`，**不是新协议**；差异体现在部署变体：
  - `qz_with_node`：带 Node/JS 挑战或前端渲染，纯 HTTP 重放拿不到数据；
  - `qz_ieas`：IEAS 变体；
  - `qz_app`：移动端接口；
  - `qz_br`、`qz_crazy`：学校定制/异常变体。
- **判断**：新版强智优先尝试「HTML 表格 + `app.do` JSON 双通道」；若页面由 JS 渲染，再考虑 WebView 内取 DOM。❓「新版强智统一 REST 接口」未证实。
- **样本**：东莞理工学院 `https://jwx.dgut.edu.cn/`（登录页明示「强智教务系统」）、山东科技大学 `https://jwglxt.sdust.edu.cn/`（页脚强智）。注意：域名里出现 `jwglxt` 只是主机名，**不能据此判为正方**。

#### 3.2.3 研究生

- 强智有独立的「研究生综合管理平台」（官网产品线）：[qzdatasoft.com](http://www.qzdatasoft.com/)。
- 与本科 jsxsd 是否同路径 ❓未证实；目录中研究生条目多为待适配。建议单独识别域名（`yjs`/`grs`/`graduate`）后再决定。

---

### 3.3 青果软件（KINGOSOFT / kingo_new）

- **指纹** ✅：`/jwweb/`（经典入口）、`KINGOSOFT` 品牌、页脚「青果软件集团有限公司」；V17 新版样本见 `.../qzhgjw/cas/login.action`；也有学校用 `xk.` 子域做统一认证入口。
  - 样本：[新乡工程学院](http://jw.xxgc.edu.cn/jwweb/)、[河南大学](https://xk.henu.edu.cn/)、青果 V17 CAS 入口样本 `jwgl.hgu.cn/qzhgjw/cas/login.action`（校名待核）。
- **登录** ⚠️：本地账号 + 图形验证码（部分学校「正常情况免验证码」）；新版接 CAS/统一认证；经典 `jwweb` 入口常见 ASP.NET 表单（`__VIEWSTATE`），V17 新版以 CAS 为主。
- **课表** ✅：HTML 表格，表头含「星期一…星期日」，单元格文本形如 `1-16[01-02]`，多门课以换行分隔；表格常因合并单元格导致列偏移。
  - 拾光通用青果脚本用「**倒数 7 列**」定位星期，说明列数不稳定是已知坑：[qingguo_01.js](https://github.com/ShiGuangSchedule/shiguang_warehouse/blob/main/resources/qingguo_jiaowu/qingguo_01.js)
- **开源**：上述 JS（MIT）、Sleepy `JwKingoZxParser`（GPL-3.0，协议参考）。
- **难度**：**中**。坑：iframe/框架页、必须先进「个人课表」页面、合并单元格、周次/节次格式不统一、登录页编码。
- **证据状态**：路径 ✅；「所有青果都能用同一 URL 抓」❌。

---

### 3.4 URP（清元优软 / 金智关系辨析）

- **厂商辨析** ⚠️：公开资料里「URP 综合教务管理系统」多指向**北京清元优软**（大连海事大学采购公告明确写「URP 综合教务管理系统，由北京清元优软科技有限公司开发」）；金智教育另有「教务管理与服务系统」。两者都常与学校统一身份认证集成，**不能简单等同**。
  - [大连海事采购公告](https://www.dlmu.edu.cn/info/1004/10942.htm)、[金智教务产品页](https://www.wisedu.com/?list_26/88.html)
- **老 URP（`urp`）** ✅：常见独立域名 `urp.*` 或路径 `/urp/`；登录页 `login.jsp`（复旦样本 `http://www.urp.fudan.edu.cn/login.jsp`）；另有 `xkaction.do`、`actionType=6` 指纹；课表为 HTML 表格。
- **新 URP（`urp_new`）** ✅：课表返回 **JSON**：`dateList[0].selectCourseList[]`，字段 `courseName`、`classDay`、`classSessions`、`continuingSession`、`classWeek`（如 `"1111000011..."` 的周次位图）、`attendClassTeacher`、`campusName`、`teachingBuildingName`、`classroomName`。[CourseAdapter NewUrpParser](https://github.com/liuzhenghang/CourseAdapter)
- **登录** ⚠️：本地或 CAS；部分学校有验证码；双因子少见但存在（研究生/教师端更多）。
- **开源**：CourseAdapter（MIT）、Sleepy `JwUrpParser` / `JwNewUrpParser`（GPL-3.0）。
- **难度**：**中-高**。坑：新老两代字段差异大、`classWeek` 位图起点/长度不统一、各校入口路径差异、JSON 外层可能再包一层。
- **证据状态**：新 URP JSON 结构 ✅（有实现）；「所有 URP 统一 `/urp/`」❌。

---

### 3.5 金智教育（wisedu，`/jwapp/`）

- **指纹** ✅：URL 含 `/jwapp/`；页面/页脚「金智」；常与学校信息门户、统一身份认证一起出现。
- **登录** ⚠️：以统一身份认证为主（CAS/OAuth/门户跳转），部分学校保留本地账号；WebView 内完成登录最稳妥。
- **课表** ✅：JSON API 直连（Sleepy 标注「金智教务（JSON API 直连，如哈尔滨工程大学）」），课表接口在 `/jwapp/` 下按校定制，字段需按校探测。
- **开源**：Sleepy `JwWiseduParser`（GPL-3.0，仅参考协议）；拾光目录中无独立金智通用脚本（其 `urp_jiaowu` 与金智不是一回事）。
- **难度**：**中**。坑：接口路径一校一变、登录强依赖 SSO、JSON 字段命名不统一。
- **证据状态**：`/jwapp/` 指纹与 JSON 形态 ✅；统一接口路径 ❓。

---

### 3.6 树维科技（EAMS / eams5 / shuwei_json）

- **指纹** ✅：
  - 经典 EAMS（`classic_eams`）：`eams.../login.action`、`loginExt.action`、`homeExt.action`、`courseTableForStd`；
  - eams5（`eams5`）：`jxglstu`、`/eams5-student/`、`/for-std/`；
  - JSON 变体（`shuwei_json`）：`wengine`、`shuwei`。
- **登录** ⚠️：经典 EAMS 为表单登录，密码常见 `SHA1(动态前缀 + 明文)`，前缀每次从登录页取；eams5 为前后端分离，登录后走 token/会话。参考：[树维登录解析](https://blog.csdn.net/JackDual/article/details/115034701)、[NEU 新版树维抓取](https://github.com/whoisnian/getMyCourses)。
- **课表**：经典 EAMS 为 HTML；eams5 为 SPA + JSON（需 JS 执行或抓 XHR）；`courseTableForStd` 是经典入口特征。
- **开源**：[whoisnian/getMyCourses](https://github.com/whoisnian/getMyCourses)（Go，MIT，NEU 树维）、Sleepy `JwClassicEamsParser` / `JwEams5Parser`（GPL-3.0）。
- **难度**：**中-高**。坑：SHA1 动态前缀、SPA、接口分页/鉴权、课程时间字段与节次映射。
- **证据状态**：三类指纹 ✅；「树维只有一种登录」❌。

---

### 3.7 超星（Chaoxing / 学习通教务）

- **指纹** ✅：`*.jw.chaoxing.com`（如 `https://dlust.jw.chaoxing.com/`、`https://gtzj.jw.chaoxing.com/admin/login`）、`mh.chaoxing`、`/xsd/`、`queryKbForGrdb`、`sdpkkblist`。
- **登录** ⚠️：学校子域 + 本地账号/统一认证；常见图形验证码与「非涉密平台」提示；部分学校与学习通账号打通。
- **课表**：SPA + JSON，接口按校/版本变化；公开实现少。Sleepy 仅接入 1 所（吉林工商学院）作为 `chaoxing` 样本，说明**通用化成本高**。
- **开源**：Sleepy `JwChaoxingParser`（GPL-3.0，仅参考）。
- **难度**：**高**。坑：SPA、加密参数、验证码、接口版本差异、页面结构频繁改版。
- **证据状态**：域名与指纹 ✅；「统一课表 API」❓。
- **优先级判断**：135 条候选但实现成本最高，放 P2，先做识别与提示。

---

### 3.8 乘方（cf / cf_new）

- **指纹** ✅：`/xsgrkbcx`、`/new/xskb`；页面/页脚「乘方教务/科技」。
- **登录/课表** ❓：公开资料少，Sleepy 有 `JwChengFangParser` / `JwCfNewParser`，但无独立文档；从指纹看是「个人课表查询」+「新版课表」两类页面，HTML/JSON 混合。
- **难度**：**中-高**（信息不足，试错成本高）。
- **建议**：P2 末位，先收集 2-3 所学校样本再动手。

---

### 3.9 长尾与非教务系统（按实际市场取舍）

> 以下厂商不满足「提供教务课表」这一前提，故不套用六项模板，只说明定位与排除理由；南软是值得保留的研究生教务长尾之一。

| 名称 | 实际定位 | 是否值得做课表适配 | 证据 |
|---|---|---|---|
| 南软（NanRuan） | 南软科技 **研究生管理信息系统（GMIS）** | 研究生长尾，单独适配 | 北工大研究生手册 PDF 明示「南软科技 培养管理」：[PDF](https://graduate.bjut.edu.cn/__local/5/6E/01/9B9D8D43E0D876DCB9B402B8A53_29E7B5D3_8B1D2.pdf?e=.pdf) |
| 新中新 | 校园一卡通/智慧校园（市占率高） | **否**，未见独立教务课表产品 | [官网](http://www.synjones.com/detail/615.html)、[福大介绍](https://usp.fzu.edu.cn/info/1032/1912.htm) |
| 康赛 | 智慧校园、数据治理、统一门户 | **否**（可能承载门户，不提供课表） | [百科](https://baike.baidu.com/item/%E6%88%90%E9%83%BD%E5%BA%B7%E8%B5%9B%E4%BF%A1%E6%81%AF%E6%8A%80%E6%9C%AF%E6%9C%89%E9%99%90%E5%85%AC%E5%8F%B8/19992314) |
| 三盟 | 教育大数据、智慧教学环境 | **否** | [官网介绍](https://www.kczg.org.cn/org/orgdetail?id=125764) |
| 万维（捷通） | 考试/测评/题库 | **否** | [官网](http://www.wwjt.com.cn/) |
| 汇文 | 图书馆管理系统 | **否**；❓未证实有教务产品 | — |
| 清华教育在线 THEOL | 网络教学平台（课程/资源），非教务课表 | **否** | [THEOL 介绍](http://47.92.87.222:2021/evaluate/infoSingleArticle.do?articleId=666418&columnId=666142) |
| 湖南强智 | 即强智科技（总部长沙） | 同 §3.2 | [官网](http://www.qzdatasoft.com/) |
| 金智 URP | 常被混称；金智教务与清元优软 URP 是两条产品线 | 分别处理 | 见 §3.4/§3.5 |

> 结论：用户清单里的「新中新/康赛/三盟/万维/汇文/THEOL」多数不是教务课表厂商，**不应占用第一批适配预算**；调研价值在于避免把「门户/一卡通/教学平台」误当教务。

---

## 4. 高职/民办院校常见组合与东莞职院

- **常见组合**（样本观察，非统计结论）：
  - 强智 jsxsd + 青果 + 正方新版，是高职/民办出现频率最高的三类；
  - 民办本科更常见正方新版/青果；
  - 广东样本中强智存在感较强：东莞职院、东莞理工、广东金融均为 `qz` 或强智页面；外省样本（如安徽工业大学）同样常见 `qz`。
- **东莞职业技术学院** ✅：
  - 入口 `https://jw.dgpt.edu.cn/`，协议候选 `qz`（Sleepy 目录，状态「待验证·可尝试」）；
  - 当前适配器默认 `https://jw.dgpt.edu.cn/jsxsd/`，与公开目录的 `qz` 判定一致；
  - 校内实际登录可能走统一认证（`authserver`），现有 `JwSessionRecovery` 已覆盖「CAS 票据仍在 → GET 链路免密重建会话」。
- **给首个目标的建议**：先把「强智 jsxsd + 东莞职院」打磨成金标准（含验证码、SSO 过期、课表页 iframe、周次异常），再复制到同省 `qz` 学校。

---

## 5. 厂商优先级矩阵

评分口径：覆盖 = §2.2 条目数（相对值）；难度 = 低/中/高；复用 = 是否有 MIT/MPL 的可直接参考实现（GPL 仅协议参考）。

| 厂商/协议 | 覆盖 | 难度 | 可复用解析器 | 建议批次 | 理由 |
|---|---:|---|---|---|---|
| 强智 `qz`（jsxsd） | 292 | 低 | classCrawl(MIT)、现有实现 | **P0（已做，硬化）** | 首个目标；HTML+JSON 双通道 |
| 正方 `zf_new` | 562 | 低-中 | zfn_api(MPL)、new-school-sdk(MIT)、CourseAdapter(MIT) | **P0** | 覆盖第一；JSON 最易解析 |
| 青果 `kingo_new` | 164 | 中 | 拾光 JS(MIT) | **P1** | 覆盖第三；纯 HTML 表格 |
| 正方旧版 `zf` | 33 | 中 | ZFCrawler(MIT)、CourseAdapter(MIT) | **P1** | 与新版共用表格解析器 |
| URP 新+老 | 41 | 中-高 | CourseAdapter NewUrp(MIT) | **P1 末** | JSON 结构清晰，但校际差异大 |
| 树维 classic+eams5+json | 85 | 中-高 | getMyCourses(MIT，仅 classic) | **P1 末/P2** | 三类形态，eams5 需 JS |
| 金智 `wisedu` | 54 | 中 | Sleepy(GPL，仅参考) | **P2** | SSO 重，接口一校一变 |
| 超星 `chaoxing` | 135 | 高 | Sleepy(GPL，仅参考) | **P2** | 覆盖高但 SPA/加密，ROI 低 |
| 乘方 `cf` | 41 | 中-高 | Sleepy(GPL) | **P2 末** | 资料少，样本不足 |
| 研究生系统（南软/强智研/校本） | 长尾 | 高 | 无通用 | **长尾** | 目录中大量「待适配」 |
| 校本/自建/IEAS 变体 | 长尾 | 高 | 无 | **长尾** | 只做识别 + 手动导入兜底 |

**建议节奏**：
1. **第一批（1-2 个迭代）**：强智硬化 + 正方新版。两者合计覆盖约 854/1470 条候选（≈58%），且都能在「WebView 登录 + Cookie 重放」下完成。
2. **第二批（2-3 个迭代）**：青果 + 正方旧版 + URP。覆盖再 +238 条（≈16%），以 HTML 表格为主，可沉淀通用表格解析器。
3. **第三批**：树维、金智、超星、乘方。先做**协议识别 + 明确「暂不支持」**，避免误用强智解析器产生空课表。
4. **长尾**：研究生、校本系统、IEAS/node 变体，只保留「手动导入/HTML 文件导入」通道。

---

## 6. 「通用 HTML 表格解析器 + 厂商专用适配器 + JS 脚本兜底」怎么分工

### 6.1 通用 HTML 表格解析器能覆盖谁

**能覆盖（值得做）**：
- 强智 jsxsd：`#kbtable` + `div.kbcontent` + 周次行；
- 正方旧版/过渡版：`#table1` + `.festival` + `td[id]`；
- 青果：`星期一…星期日` 表头 + `周次[节次]` 文本块；
- 部分老 URP：标准「星期 × 节次」表格。

**通用解析器必须具备的能力**：
1. 表头识别：`星期一/周一/一`、`星期日/周天`，允许表头不在第一行；
2. 行首节次：`1-2节`、`第3大节`、`08:00-09:40`（纯时间要回退到行序）；
3. 单元格切分：`<hr>`、`<br>`、嵌套 `div`、`<p>`；过滤隐藏重复节点（如 `kbcontent1`）；
4. 周次：`1-16周`、`1-16(单)`、`1,3,5`、`1-8,10-16`、`[01-02节]` 方括号优先；
5. `rowspan/colspan` 展开与「倒数 N 列」容错（青果已知坑）；
6. iframe 递归（青果/强智常见框架页）；
7. **置信度评分 + 预览确认**：解析出 0 门课或字段缺失率过高时，不静默成功。

**不能覆盖（必须专用）**：
- 正方新版 `kbList` JSON（字段语义、学期参数）；
- 强智 `app.do` token JSON；
- 新 URP `dateList/selectCourseList` 位图周次；
- 金智 `/jwapp/` JSON；
- 超星 SPA、树维 eams5、强智 node 变体（数据在 JS 执行后才有）。

### 6.2 厂商专用适配器该做什么

专用适配器不应该重复「表格解析」，而应负责：
- **协议识别**：URL 指纹 + 页面指纹（参考 Sleepy 的两张表），输出 `Protocol` 与置信度；
- **登录/会话**：识别登录页、CAS 跳转、验证码存在性；会话失效判定；
- **取数**：正确的 URL/POST 参数/Header/编码；JSON → 统一 `CourseDraft`；
- **失败分类**：`NeedsLogin` / `NoPermission` / `StructureChanged` / `EmptySemester`，便于 UI 给出可行动提示。

### 6.3 JS 脚本兜底：可以做，但要克制

**适用**：页面 DOM 稳定、但接口路径/参数无法离线复现（青果课表页、部分强智变体、树维 eams5 的表格视图）。

**风险**：
- WebView 内执行远程脚本 = 远程代码执行面；必须**内置/签名**脚本，禁止从服务器下发未签名脚本；
- 脚本拿得到 Cookie 与页面内容，权限边界要明确（只读 DOM、不发网络请求）；
- 页面改版即失效，维护成本高；调试困难；
- 与「通用解析器」职责重叠，容易变成两套逻辑。

**建议**：把 JS 兜底定位为**最后一公里**：
1. 仅当专用适配器与通用表格都失败时启用；
2. 脚本由 App 内置、版本随 APK 发布，带白名单域名校验；
3. 脚本只做「读取当前 DOM → 返回结构化 JSON」，不做登录、不发请求；
4. 失败时保留脱敏 HTML 快照，供用户反馈与后续规则修正。

---

## 7. 学校清单 / 学校-系统映射：数据来源与合规

### 7.1 数据来源建议（按可信度排序）

| 来源 | 规模/形态 | 优点 | 注意 |
|---|---|---|---|
| 教育部全国高等学校名单 | 全量学校 | 权威、稳定 | 只有学校，没有教务系统信息；[moe.gov.cn](http://www.moe.gov.cn/) |
| 学校教务处/信息化处公告 | 单校 | 最权威的系统变更信号 | 需人工/半自动采集，更新慢 |
| [Sleepy 学校目录](https://github.com/lingion/sleepy/blob/main/docs/schools-list.md) | 4140 条，含协议候选与入口 | 有 URL、状态、来源字段 | GPL-3.0 项目；数据可参考，代码不可直接抄 |
| [拾光适配仓库](https://github.com/ShiGuangSchedule/shiguang_warehouse) | 260 条 + 4 个通用厂商 | MIT，结构清晰（`root_index.yaml`） | 以脚本为主，需核对当前有效性 |
| [WakeUp 已适配列表](https://www.wakeup.fun/doc/adapt_android_hmos.html) | 1200+ 学校 | 覆盖广、有学校名 | 2022 年快照，未标系统类型 |
| 小爱课程表适配脚本（多仓库） | 单校 JS | 可看到真实页面结构 | 分散、质量参差、部分已归档 |
| 用户共建 | 众包 | 能发现新系统/新变体 | 必须脱敏、去重、标注证据与时间 |

### 7.2 建议的数据模型（最小字段）

```
school: id, 名称, 别名[], 省份, 层次(本科/高职/研究生)
entry: url, 协议候选, 置信度, 证据来源, 最后验证时间, 验证人, 状态(已实现/待验证/待适配)
```

- 状态必须显式区分「已实现配置」「待验证」「待适配」，**收录 ≠ 支持**；
- 同一学校允许多入口（校内/校外/VPN/研究生），不要强行合并；
- 记录「最后验证时间」，超过一学期未验证自动降级为「待验证」。

### 7.3 合规注意

1. **教育 App 备案**：教育移动应用提供者需备案，应用商店上架通常要求备案号；自研自用按隶属关系备案。[《教育移动互联网应用程序备案管理办法》](http://www.moe.gov.cn/srcsite/A16/s3342/201911/t20191122_409333.html)、[备案平台](https://app.eduyun.cn/)
2. **个人信息保护法**：最小必要、告知同意、本地处理优先；学号/姓名/课表属于个人信息，**不得上传第三方服务器**，不得用于画像/广告。[PIPL 全文](https://www.cac.gov.cn/2021-08/20/c_1631050028355286.htm)
3. **网络安全法/学校协议**：只访问用户本人账号；遵守学校用户协议与访问频率限制；控制请求频率（建议≥数秒级、仅每日同步）；**不提供抢课/代刷/绕过验证码**功能。
4. **凭据安全**：密码只在 WebView 中由用户输入，App 不落盘、不转发；Cookie 存私有存储；日志脱敏。
5. **开源许可**：
   - MIT（CourseAdapter、new-school-sdk、ZFCrawler、getMyCourses、拾光仓库）：可复用，保留版权与许可声明；
   - MPL-2.0（zfn_api）：文件级 copyleft，修改过的文件需开源；
   - GPL-3.0（Sleepy、部分课表 App）：**不要复制代码进闭源 App**，只可阅读协议与字段作为事实参考。

---

## 8. 对现有工程的落地建议（只建议，不改代码）

1. **先正名**：`ZhengFangAdapter` → `QiangZhiJsxsdAdapter`；`JwAdapterRegistry` 去掉「默认回落」，改为「识别失败 → 让用户选择厂商/手动导入」。
2. **加协议识别层**：`detectProtocol(url, html)`，指纹可直接采用 §3 与 Sleepy 的 URL/页面两张表；识别结果在 UI 可改，避免「一校一改代码」。
3. **正方新版适配器**（收益最高）：
   - 会话：沿用 WebView Cookie；
   - 取数：`POST {base}/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151`，`xnm/xqm` 从设置/页面获取；
   - 解析：`kbList[]` → `CourseDraft`；`zcd` 周次、`jc` 节次需各自解析；失败回退到 `#table1` 表格。
4. **通用表格解析器**：从现有 `parse()` 抽出「表头/行首/单元格/周次/iframe」五个可复用组件，供强智、青果、正方旧版共用；补 `rowspan/colspan` 与「倒数 N 列」。
5. **青果/树维 eams5/超星**：先只做识别与提示（「已识别为青果，请在内置浏览器打开课表页后点『从当前页面导入』」），不要用强智解析器硬解。
6. **失败可诊断**：保存最近一次抓取的**脱敏 HTML**（去 Cookie/学号/姓名），让用户一键反馈；区分「未登录 / 无权限 / 学期为空 / 结构变化」。
7. **回归样本**：为每个已支持协议准备 2-3 份脱敏 HTML/JSON 固件，做单元测试；页面改版时能快速定位。

---

## 9. 待证实清单（不要在对外文档中写成事实）

- ❓ 强智 `xskbList.do`（大写 L）是否存在——当前代码中的候选，无公开证据。
- ❓ 新版强智是否存在统一 REST API（目前证据只支持 `app.do` 与 HTML 两条通道）。
- ❓ 正方研究生系统是否与本科共用 `jwglxt` 接口。
- ❓ 金智 `/jwapp/` 下各校课表接口是否有统一路径与字段。
- ❓ 超星是否存在跨校统一的课表 JSON 接口。
- ❓ 乘方 `cf` 的登录与课表接口细节（仅有解析器，无文档）。
- ❓ 汇文是否有教务产品（公开资料只指向图书馆系统）。
- ⚠️ 「URP = 金智」这一常见说法不准确：URP 多指清元优软产品，金智是另一条产品线；两者常被学校门户统一包装，容易混淆。

---

## 10. 参考链接（正文已内联，此处只列核心）

- **协议/目录**：[Sleepy 协议映射](https://github.com/lingion/sleepy/blob/main/docs/catalog/PROTOCOL_MAPPING.md)｜[学校目录 4140 条](https://github.com/lingion/sleepy/blob/main/docs/schools-list.md)｜[拾光适配仓库](https://github.com/ShiGuangSchedule/shiguang_warehouse)
- **正方**：[登录分析（RSA/csrf）](https://blog.revincx.icu/posts/new-jw-analysis/index.html)｜[zfn_api（MPL-2.0）](https://github.com/openschoolcn/zfn_api/blob/main/zfn_api.py)｜[new-school-sdk（MIT）](https://github.com/FarmerChillax/new-school-sdk/blob/master/school_sdk/config.py)｜[ZFCrawler（MIT）](https://github.com/Cheukfung/ZFCrawler)
- **强智**：[课表 HTML + app.do JSON](https://www.jet-lab.site/blogs/qiangzhi)｜[classCrawl（MIT）](https://github.com/inannan423/classCrawl)｜[官网](http://www.qzdatasoft.com/)
- **青果**：[官网](http://www.kingosoft.com/)｜[河南大学入口](https://xk.henu.edu.cn/)｜[V17 CAS 样本](https://jwgl.hgu.cn/qzhgjw/cas/login.action)
- **URP/金智/树维**：[复旦 URP](http://www.urp.fudan.edu.cn/login.jsp)｜[清元优软采购公告](https://www.dlmu.edu.cn/info/1004/10942.htm)｜[金智教务](https://www.wisedu.com/?list_26/88.html)｜[树维样本](https://www.cduestc.cn/eams/loginExt.action)｜[getMyCourses（MIT）](https://github.com/whoisnian/getMyCourses)
- **超星/合规**：[超星样本](https://gtzj.jw.chaoxing.com/admin/login)｜[WakeUp 导入](https://www.wakeup.fun/doc/import_from_eas.html)｜[备案办法](http://www.moe.gov.cn/srcsite/A16/s3342/201911/t20191122_409333.html)｜[个人信息保护法](https://www.cac.gov.cn/2021-08/20/c_1631050028355286.htm)

---

### 附：调研方法备注

- 使用 GitHub 代码检索与公开仓库文档交叉验证路径，避免单一博客口径；
- 覆盖数字来自第三方开源目录，仅用于相对排序；
- 未对任何学校发起登录/抓取；所有「登录方式」描述均来自公开页面与开源实现；
- 若后续要落地某厂商，建议先采集 3 所不同学校的脱敏页面/响应，再写适配器，避免「一校一版」。
