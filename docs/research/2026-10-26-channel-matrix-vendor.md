# 高校教务「课程数据出口」渠道矩阵（2026-10-26）

> 目标项目：纸间课表（Android / Kotlin）　｜　调研人：渠道调研员（子会话）
> 范围：只做公开资料、公开仓库与学校公开通知的调研；**未对任何学校真实账号发起登录或抓取**，未改动 App 源码，未提交 git。
> 与既有文档的分工：`jw-vendor-landscape.md` 回答「谁家的系统、什么协议、先做谁」；本文回答「数据从哪个出口出来、出口长什么样、能不能自动化」。
> 证据分级：**【已证实】** 公开代码/官方手册/学校通知可直接支撑端点或功能｜**【部分证实】** 单一来源、单校样本或实现存在但无字段级文档｜**【未证实】** 仅营销文案、推断或二手转述。

---

## 0. 一页结论

1. **真正可自动化的出口只有两类**：登录后的 JSON/XHR 接口，和服务端渲染的 HTML 表格页。导出文件、移动端、门户、开放平台都只能算「补充/兜底」，不是主通道。
2. **JSON 出口的第一梯队是正方新版与强智**。正方 `POST /jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151`（body `xnm/xqm/kzlx`）直出 `kbList[]`；强智 `app.do?method=getKbcxAzc` 直出周课表数组。两者合计覆盖目录中 854 条候选，且都能在「WebView 登录 + Cookie 重放」下完成。
3. **导出通道的价值被高估**。正方「教学进程查看」导出的是**每门课一个 .zip**（还有全选导致课程名错乱的已知 bug），考试信息是 .xlsx；课表打印页才有「输出 EXCEL / 输出 PDF」。青果、强智的导出按钮主要在**教师/管理端**，学生端普遍没有。导出适合做「文件导入兼容层」，不适合做同步主链路。
4. **移动端不是独立协议，而是同一后端的另一张皮**。强智的「智校园」App 走的就是 `app.do`；金智 ehall 微应用走 `/jwapp/sys/<module>/...`；超星学习通的课表与教务排课**可能不同源**。把它当新渠道会重复造轮子。
5. **统一身份认证与 webvpn 是最大的工程摩擦点**，不是数据问题：CAS 二次认证（微信验证码）、TGC 换票、webvpn 的 URL 重写会让「构造请求」直接失效，只能在 WebView 内完成。
6. **校方开放平台对第三方 App 基本不可得**。北大有 REST/JSON 的「数据共享 API」（含课程信息、个人课表），南师大等只对**校内二级单位**开放且禁止转第三方。这条渠道的瓶颈是审批，不是技术。
7. **研究生系统必须单列**。目录里 301 条「研究生·待适配」、仅 2 条已实现；「博雅研究生管理信息系统」实际部署在超星域名（`*.mh.chaoxing.com`）下，且不少学校把研究生课表当**通知附件（Word/PDF）**发，根本没有接口。
8. **二维码与 ICS 都不是数据源**。校方课表页「扫码同步」目前只有第三方软文，未见学校官方证据；ICS 是输出格式，真正有价值的是「App 支持 ICS/Excel 导入导出」这件事本身。

---

## 1. 覆盖率口径（先统一尺子，再谈覆盖）

### 1.1 三个可引用的规模数字

| 来源 | 数字 | 口径 | 备注 |
|---|---:|---|---|
| Sleepy 学校目录 | **4140 条** | 目录条目，含别名/校区/研究生，不是独立高校数 | 数据基准 2026-10-14；[schools-list.md](https://github.com/lingion/sleepy/blob/main/docs/schools-list.md) |
| 拾光适配仓库 | **260 条** | 适配器条目：1 个通用工具 + 4 个厂商通用 + 255 校 | 本喵按 `root_index.yaml` 的 `- id:` 重算，与既有文档一致；[shiguang_warehouse](https://github.com/ShiGuangSchedule/shiguang_warehouse) |
| WakeUp | **1800+ / 1200+** | 官方口径随版本变化：首页写 1800+，文档页写 1200+ | 2022 年文档快照无系统类型标注；[首页](https://www.wakeup.fun/)、[文档](https://www.wakeup.fun/doc/) |

> **不要用产品宣传数字当覆盖率**：强智官网「900+ 合作院校」、某课表 App「1500 余所」都是厂商/营销口径，既无系统类型也无验证状态，只能当「市场存在感」参考。

### 1.2 本喵对 4140 条的重算（按协议候选列统计）

- 状态分布：已实现配置 **348**、待验证·可尝试 **1128**、待适配 **2361**、研究生·待适配 **301**、研究生·已实现 **2**（合计 4140）。
  > 与官方口径对齐：官方写作「已实现 350 / 待验证 1128 / 待适配 2662」；本喵只是把研究生单列，所以 350 = 348 + 2、2662 = 2361 + 301，两者不矛盾。
- 有协议候选 **1478 条**（扣除重复表头行），其中厂商通用协议约 **1419** 条、校本专有协议约 **40 条**（`yl`/`cumtb`/`umooc`/`scau`/`hnust`/`cqu`/`neu` 等）。

| 协议族 | 条目 | 主要数据出口形态 |
|---|---:|---|
| `zf_new` 正方新版 | 562 | JSON（`kbList`）+ 过渡版 HTML 表格 |
| `qz` 强智 jsxsd（含 `qz_with_node` 7、`qz_br` 3、`qz_old` 2） | 292 | HTML 表格 + `app.do` JSON |
| `kingo_new` 青果 | 164 | HTML 表格（iframe 框架页） |
| `chaoxing` 超星 | 135 | JSON（XHR）+ SPA |
| `wisedu` 金智 | 54 | JSON（`/jwapp/sys/*` 微应用） |
| `classic_eams` 树维经典 | 48 | HTML + `dataQuery.action` JSON |
| `shuwei_json` 树维 JSON 变体 | 34 | JSON（`wengine`/`shuwei` 标记） |
| `zf` 正方旧版 | 33 | HTML 表格（ASP.NET） |
| `urp_new` 新 URP | 31 | JSON（`dateList/selectCourseList`） |
| `urp` 老 URP | 10 | HTML 表格 |
| `cf` 乘方（含 `cf_new` 12） | 41 | Struts2 action，JSON/HTML 混合 |
| `eams5` 树维 eams5 | 3 | SPA + JSON |

> 口径提醒：同一学校可能同时存在「JSON 主通道 + HTML 兜底 + 导出兜底」，因此**各渠道条目数不可相加**；表中数字只用于判断相对优先级。

---

## 2. 渠道总表

| # | 渠道 | 技术形态 | 覆盖厂商 | 覆盖规模（目录条目，推断口径） | 取数难度 | 是否需账号 | 主要坑 |
|---|---|---|---|---|---|---|---|
| 1 | 登录后 JSON/XHR 直取 | REST/JSON、Struts2 action、`app.do` | 正方新、强智、金智、URP 新、树维 eams5、超星、乘方 | 约 900–1000（可 JSON 的协议族合计，含重叠） | 低–中 | 是 | 学期参数编码、`gnmkdm` 权限、token 过期、路径一校一变 |
| 2 | HTML 表格页解析 | 服务端渲染 HTML | 强智 jsxsd、正方旧、青果、URP 老、树维 classic | 约 550 | 中 | 是 | 合并单元格、iframe、GBK、隐藏重复节点、打印样式页 |
| 3 | 教务自带导出 | xlsx / zip / pdf / 打印页 | 正方、青果、乘方、URP、强智（教师端）、超星、树维 | 上限约 900，**学生端可用率远低** | 人工低 / 自动化高 | 是 | 权限不对称、格式一校一变、多门课多 zip、导出不含调课 |
| 4 | 移动端/微应用/企微/钉钉 | App、H5、微应用 | 强智「智校园」、金智 ehall、超星学习通、乘方移动、学校企业微信 | 规模未证实 | 中–高 | 是 | 与教务不同步、token/加密、入口分散、无统一协议 |
| 5 | SSO / 门户 / webvpn | CAS、TGC、OAuth、URL 重写 | 几乎全部本科院校 | 全量（前置条件） | 中–高 | 是 | 二次认证、跨域 Cookie、webvpn 重写后无法构造 |
| 6 | 校方开放平台/数据中台 | 申请制 REST API | 北大、南师大等少数 | <1% | 极高（非技术） | 是 | 仅校内单位、审批、禁止转第三方 |
| 7 | 研究生系统 | 独立部署 + 附件/Word | 博雅（超星系）、国科大 SEP、各校 yjs/grs | 303（301 待适配 + 2 已实现） | 高 | 是 | 与本科不同库、课表常为附件、选课系统与教学网不一致 |
| 8 | 二维码 / ICS / 订阅 | ICS、二维码、分享链接 | 学习通、第三方工具 | 长尾 | 低–中 | 视源 | 校方二维码未证实、ICS 不是数据源、口令进 URL |

---

## 3. 分渠道详解

### 3.1 渠道一：登录后 JSON/XHR 直取

#### 3.1.1 正方新版（`zf_new`）【已证实】

- **主端点**：`POST {base}/jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151`
  body：`xnm=2025&xqm=3&kzlx=ck&xsdm=`（`xsdm` 常留空）
  返回 JSON：`kbList[]`（`kcmc` 课程、`xm` 教师、`xqj` 星期、`jc` 节次、`zcd` 周次、`cdmc` 地点、`xqmc` 校区、`kch_id`、`jxbmc`）+ `xsxx` + `sjkList`。
  [zhengfang-api](https://github.com/znjhahaha/zhengfang-api) 给出了同样的路径与字段；[HDU-KillCourse](https://github.com/cr4n5/HDU-KillCourse/blob/master/client/service.go)、[funnel](https://github.com/zjutjh/funnel/blob/master/app/apis/zf/zfApi.go)、[xtu_ems](https://github.com/sky31studio/GongGong/blob/main/ems-sdk/xtu_ems/zf_ems/config.py)、[SIT-kite](https://github.com/SIT-kite/kite-app/blob/master/lib/module/timetable/service/school.dart) 是四个独立学校的同路径实现。
- **同族变体**：`/kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151`（[zfn_api](https://github.com/openschoolcn/zfn_api) 使用）；`gnmkdm=N253508`（[zhengfang_new.js](https://github.com/baoozak/timetable/blob/main/utils/parsers/zhengfang_new.js)）。
- **配套第二请求（关键，容易漏）**：`POST /kbcx/xskbcx_cxRjc.html` 取**节次时间表**，否则「第 3-4 节」无法变成钟点。[zf-to-ics](https://github.com/zaochih/zf-to-ics) 明确把 `xskbcx_cxRjc` 与 `xskbcx_cxXsgrkb` 成对使用。
- **班级课表**：`/kbdy/bjkbdy_cxBjKb.html`（教师/管理端，zfn_api 的 `CLASS_SCHEDULE`）。
- **坑**：
  1. `xqm` 的 3/12 语义与「双作息」（五一/国庆切换）——zf-to-ics 专门为此做了回退逻辑；
  2. `gnmkdm` 与菜单权限绑定，权限不足时**返回登录页 HTML 而不是 401**，会被误判为「未登录」；
  3. 有的部署 GET 也能取，有的强制 POST + `X-Requested-With`；
  4. `zcd`（周次串）、`jc`（节次串）必须二次解析，字段格式各校有差异。

#### 3.1.2 强智 `app.do`【已证实】

- **登录取 token**：`GET {host}/app.do?method=authUser&xh={学号}&pwd={密码}` → `{token, userrealname, userdwmc, usertype}`
- **课表**：`GET {host}/app.do?method=getKbcxAzc&xh={学号}&xnxqid={学年学期}&zc={周次}`，Header 带 `token`
  返回数组：`kcmc` 课程、`jsxm` 教师、`jsmc` 教室、`kkzc` 周次、`kcsj` 时间编码（`星期|起始节|结束节`，如 `50304` = 星期五第 3-4 节）、`jssj/kssj` 起止时间。
- **辅助**：`getCurrentTime&currDate=` 取当前周次与学年；`getXqcx` 校区；`getJxlcx` 教学楼。
- **来源**：[code84 抓包记录](https://code84.com/677670.html)、[HUT_API 强智 API 清单](https://github.com/cxyzzz/HUT_API/blob/master/README.md)、[GDUF-QZAPI](https://github.com/EwingYangs/GDUF-QZAPI)（明确写「根据智校园 APP 抓包得出，兼容其它学校」）。
- **重要纠正**：所谓「强智移动端 `qz_app`」在 Sleepy 里是**协议归类**，不是一个独立的 `/qz_app/` 路径；它的实现就是 `app.do`（智校园 App 的后端）。
- **坑**：token 有过期时间；部分学校已关闭 `authUser`（改为只允许 App 内登录）；密码走 GET 明文，日志与代理层会留痕；只能**按周**取，整学期要循环 `zc`。

#### 3.1.3 金智 `/jwapp/`【已证实形态，路径一校一变】

金智是「微应用 + 模块化 API」，同一个 `/jwapp/sys/` 前缀下按模块命名，**没有统一课表路径**：

| 学校 | 端点 | 形态 |
|---|---|---|
| 南京大学 | `POST https://ehallapp.nju.edu.cn/jwapp/sys/wdkb/modules/xskcb/cxxszhxqkb.do` | 表单编码，结构化上课安排 |
| 北京信息科技大学 | `POST /jwapp/sys/kbapp/api/wdkbcx/getMyScheduleDetail.do`；学期 `GET /jwapp/sys/homeapp/api/home/kb/xnxq.do` | JSON，支持按周/按学期 |
| 深圳职业技术大学 | `POST https://jwxt.szpu.edu.cn/jwapp/sys/kcbcxmdl/KbcxController/queryxskb.do` | body `requestParamStr` 内嵌 JSON（`XNXQDM`、`XH`） |
| 北京理工大学 | `https://jxzxehallapp.bit.edu.cn/jwapp/sys/wdkbby/*default/index.do#/xskcb` | 页面（SPA），需 DOM/渲染后取 |
| 武汉船舶职业技术学院 | `http://jw.wspc.edu.cn/jwapp/sys/emaphome/portal/index.do` | 页面，加载慢，实践中「先点打印再取」 |

来源：[NJU 课表字段](https://github.com/121mc/NJU_Calendar_Importer_Flutter/blob/main/docs/%E8%AF%BE%E8%A1%A8%E5%AD%97%E6%AE%B5.md)、[iBistu API](https://github.com/ProjektMing/iBistu/blob/main/docs/API.md)、[szpu 扩展](https://github.com/BoyangZhang619/szpu-jwxt-extension)、[BIT-enhanced](https://github.com/YDX-2147483647/BIT-enhanced)、[金智适配记录](https://qing90bing.github.io/QingNoteblog/2025/07/31/a3975d2b.html)。
**坑**：路径、模块名、参数包装（`requestParamStr` / 表单 / JSON body）三层都不统一；必须先过 CAS 拿 TGC 再 `casLogin.do` 换教务 session；SPA 页要等 JS 渲染。

#### 3.1.4 新 URP `dateList / selectCourseList`【已证实】

- 返回结构：`dateList[0].selectCourseList[]`，每门课下 `timeAndPlaceList[]`：`courseName`、`classDay`（星期）、`classSessions`（起始节）、`continuingSession`（连上几节）、`classWeek`（位图）、`weekDescription`（人读周次）、`teachingBuildingName`、`classroomName`、`campusName`、`attendClassTeacher`。
- 来源：[URP 课表转 ics 实践](https://nocolor.cc/archives/127)（逐字段打印）；本地 `.tmp/jwresearch/src_parser_NewUrpParser.kt` 为同一结构的 Kotlin 实现。
- **坑**：`classWeek` 位图起点/长度不统一；外层可能再包一层；`timeAndPlaceList` 可能为 `null`（未排地点），要跳过而不是崩。

#### 3.1.5 树维 eams5【已证实】

三段式，缺一不可：

1. `GET /eams5-student/for-std/course-table/get-data?bizTypeId=2&semesterId={id}&dataId={studentId}` → `lessonIds[]`
2. `POST /eams5-student/ws/schedule-table/datum` body `{lessonIds, studentId, weekIndex}` → `data`（课表文本）+ `periods`（节次时间）
3. `GET /for-std/course-table/print-data/{semesterId}/{studentId}` → 结构化课表（打印数据）

来源：[HFUTICS](https://github.com/ssyu0808/HFUTICS/blob/master/jxglstu.py)、[HFUT-Schedule](https://github.com/Chiu-xaH/HFUT-Schedule/blob/main/docs/HfutApi.md)、[aoxiang-assistant](https://github.com/Lorcas-Zephyr/aoxiang-assistant/blob/main/docs/JWXT_API.md)、[xauat 前端配置](https://github.com/xieyinch/xauat-swjw-app/blob/main/src/config/site.ts)。
**坑**：至少三种形态（`/eams5-student/` 与 `/student/` 前缀、GET `get-data` vs POST `datum`、HFUT 与 AHU 不同），`studentId` 要从 HTML `<script>` 里正则抠，`bizTypeId` 区分本科/研究生。

#### 3.1.6 超星 `queryKbForGrdb / sdpkkblist`【已证实】

- 个人课表：`GET /pkgl/xskb/queryKbForGrdb?sf_request_type=ajax` —— **无参**，服务端按会话取当前学期；返回 JSON 行（`kcmc`/`tmc`/`croommc`/`djc`/`zc`/`xjc`）。
- 节次时间：`GET /admin/api/getZclistByXnxq?xnxq={学年学期}&sf_request_type=ajax`（部分部署无 `/admin` 前缀）。
- 学期列表：`getMenuList`。
- 班级/教师端：`sdpkkblist`（HBUT 文档写作 `sdpkkbList?xnxq=&xhid=`）——**这是另一个入口，不是个人课表**，Sleepy 明确「仅做入口识别，本版不导入」。
- 来源：[Sleepy 1.0.49 发布说明](https://github.com/lingion/sleepy/blob/main/docs/release-notes-v1.0.49.md)（含吉林工商学院真实采集：22 行输入合并为 11 门课）、[HBUT API 文档](https://www.webkkk.net/meixu0/hbut-api-collection)。
- **坑**：字段里带 HTML 锚点要剥离；`xjc` 缺失时只能用行号兜底；必须先在课表页建立会话，直接 `fetch` 会拿到登录页。

#### 3.1.7 乘方 `xsgrkbcx!*.action`【部分证实】

Struts2 风格 action 族，多校同族：

| action | 用途 | 返回 |
|---|---|---|
| `xsgrkbcx!getXsgrbkList.action` | 我的课表页面（学期下拉） | HTML |
| `xsgrkbcx!getKbRq.action?xnxqdm=&zc=&xsdm=` | 周课表（`zc` 空 = 整学期） | **JSON** |
| `xsgrkbcx!xsAllKbList.action?xnxqdm=` | 全部课程 | HTML（部分校） |
| `xsgrkbcx!getDataList.action` | 全部课程 | JSON |
| `xsgrkbcx!getSkxxDataList.action?kcrwdm=&teadm=` | 排课详情（精确地点/周次） | JSON |

来源：[拾光 ZQU 脚本](https://github.com/ShiGuangSchedule/shiguang_warehouse/blob/main/resources/ZQU/zqu_01.js)（注释明确「直接取自后台 JSON 接口，不做 HTML 解析」）、[GDUT-Schedule-ng](https://github.com/Richard-Zheng/GDUT-Schedule-ng/blob/master/api.ts)、[CDUTCM 脚本](https://github.com/ShiGuangSchedule/shiguang_warehouse/blob/main/resources/CDUTCM/cdutcm_01.js)。
**坑**：厂商归属未独立核实（Sleepy 归 `cf`，但多校同族）；同一 action 在不同学校可能返回 HTML 或 JSON；`xnxqdm` 格式为 `2025-2026-1` 或 `20252026` 两类。

#### 3.1.8 青果：没有稳定 JSON

青果 `jwweb` 是典型服务端渲染，公开实现里未发现稳定 JSON 课表接口。走渠道二。

---

### 3.2 渠道二：HTML 表格页面解析

| 厂商 | 入口/页面 | 结构特征 | 证据 |
|---|---|---|---|
| 强智 jsxsd | `GET /jsxsd/xskb/xskb_list.do?Ves632DSdyV=NEW_XSD_PYGL` | `#kbtable`、`div.kbcontent`（另有 `kbcontent1` 隐藏重复）、周次行 `[01-02节]` | [强智课表抓取](https://www.jet-lab.site/blogs/qiangzhi) |
| 正方旧版 | `xskbcx.aspx`、`default2.aspx` | `#table1`、行首 `.festival`、列 `td[id]`、`.title`、`__VIEWSTATE`、GBK | 本地 `.tmp/jwresearch/src_parser_ZhengfangParser.kt` |
| 青果 | `/jwweb/` → 教学安排 → 教学安排表 → 格式一/格式二 → 检索 | 表头「星期一…星期日」，单元格 `1-16[01-02]`，多课换行；合并单元格导致列偏移 | [江汉大学指引](https://mt.sohu.com/20170909/n510682712.shtml)、[拾光青果脚本](https://github.com/ShiGuangSchedule/shiguang_warehouse/blob/main/resources/qingguo_jiaowu/qingguo_01.js)（用「倒数 7 列」定位星期） |
| URP 老版 | `login.jsp`、`xkaction.do` | 标准「星期 × 节次」表格 | 本地 `.tmp/jwresearch/src_parser_NewUrpParser.kt` 对照 |
| 树维 classic | `courseTableForStd!courseTable.action` | HTML 表格 + `dataQuery.action` 取学期 JSON | [NWUPL 接口笔记](https://github.com/dream2333/NWUPL-Pure-EMS/blob/master/%E6%8E%A5%E5%8F%A3.md) |

**通用解析器必须具备**：表头识别（星期/周X/一）、行首节次（`1-2节`/`第3大节`/时间区间）、单元格切分（`<hr>`/`<br>`/嵌套 `div`）、隐藏重复节点过滤、周次五态（`1-16`/`1-16(单)`/`1,3,5`/`1-8,10-16`/位图）、`rowspan/colspan` 展开、iframe 递归。
**共同坑**：GBK 编码、iframe 框架页、打印样式页（`print` 视图字段更全但样式内联）、合并单元格导致「第几列 = 星期几」不成立。

---

### 3.3 渠道三：教务系统自带导出

> 结论先行：**导出是「人工兜底通道」，不是同步通道**。它的真实价值是让 App 提供「上传文件导入」，覆盖那些接口拿不到的学校。

#### 3.3.1 正方【已证实】

- **教学进程查看 → 导出**：勾选课程行 → 右上角「导出」→ 浏览器**逐门课下载 .zip**。已知 bug：一次性全选所有课程会导致课程名错乱，官方实践是「按课程分别导出」。[wmukebiao 教程](https://github.com/pkx07/wmukebiao)（温州医科大学，正方 `jwxt.wmu.edu.cn`）。
- **考试信息查询 → 导出**：全选考试日程 → 导出 **.xlsx**（含考试时间、地点、类型）。同上来源。
- **课表打印页（字段最全的导出）**：`排课管理 → 课表打印 → 班级课表查询 / 学生课表打印`，页面右上角 **【输出 EXCEL】【输出 PDF】**，可切换「表格/列表」视图。[正方官方《教学管理信息服务平台班主任用户操作手册》](https://jwc.szcu.edu.cn/_upload/article/files/9c/5e/2b06b43d41639eaf3948624306a6/62b1c721-73fc-4f26-b253-ed3411f2baf1.pdf)
- **教师课表打印**：`排课管理 → 课表打印 → 教师课表打印`，勾选姓名即可导出打印。[无锡南洋学院说明](http://hr.wsoc.edu.cn/info/1035/1508.htm)
- **坑**：上述导出入口大多在**教师/班主任/管理端**；学生端是否可见取决于学校授权。成绩导出同理（`cjcx_cxDgXscj.html` 有 `doType=query`，但是否给导出按钮由学校决定）【部分证实】。

#### 3.3.2 青果【部分证实】

- 路径：`教学安排 → 编排课表 → 学期课表 → 刷新 → 班级课表 → 选校区/院系 → 双击班级 → 导出/打印`。[青果导出打印课表方法](https://wenku.baidu.com/view/fe73eb2bcf1755270722192e453610661ed95aeb.html)
- 这是**教师/教务员端**的操作路径；学生端一般只有「教学安排表」页面，无导出按钮。

#### 3.3.3 强智【部分证实】

- 教师端手册明确：「系统中所有的打印都是指将表格导出」，`教学服务 → 个人课表信息 → 打印` 即导出。[强智教师操作手册](http://mp.weixin.qq.com/s?__biz=MzA4OTQxNTEyMQ==&mid=2651942268&idx=5&sn=169dbb0b965e242a860a3b1443a651fe&chksm=8afd82d711dd975765e7ffcf064b9d767f2ec00aed6cd7382e552cd26619b8d010076e5f4a59#rd)
- 学生端没有原生「导出 Excel」：社区做法是用油猴脚本逐页「提取当前页 → 合并导出」，且作者注明「周末课程有概率错位、体育课被过滤」。[DGUT 班级课表导出](https://greasyfork.org/fil/scripts/573297-dgut%E7%8F%AD%E7%BA%A7%E8%AF%BE%E8%A1%A8%E5%AF%BC%E5%87%BA)

#### 3.3.4 乘方【已证实】

- 课表页上方有「导出」按钮，导出 **Excel**；官方说明同时警告：**导出的是原始课程信息，不包含后续调课**，以系统内上课时间为准。[牡丹江医学院操作指南](http://mp.weixin.qq.com/s?__biz=MzIyODM4ODM5MQ==&mid=2247491010&idx=3&sn=c7f8569bcd64159563b7ec662830ff6f&chksm=e853e68adf246f9c2374834e5b0ed84fb3a797818825bcd9c71ba07908cd98670b42d168c6f3#rd)

#### 3.3.5 URP【部分证实】

- 学生端：`个人管理 → 选课管理 → 本学期课表 → 课表或列表区域下载文档`。[安徽财经大学教务处通知](https://jwc.aufe.edu.cn/2020/0223/c2754a147002/page.psp)
- 说明老 URP 的课表页本身带「下载文档」区域，格式由学校模板决定。

#### 3.3.6 超星 / 树维【部分证实】

- 超星：课表页可「导出课表」生成 .ics（见 3.8），另有打印视图。
- 树维：`print-data` 端点本身就是「打印数据」的 JSON，前端渲染成打印页；直接解析 JSON 比抓打印页更稳。

---

### 3.4 渠道四：移动端 / 小程序 / 企业微信 / 钉钉

| 入口 | 实际后端 | 证据 | 判断 |
|---|---|---|---|
| 强智「智校园」App | `app.do`（同 3.1.2） | [GDUF-QZAPI](https://github.com/EwingYangs/GDUF-QZAPI) 抓包说明 | **不是新渠道**，复用同一接口 |
| 金智 ehall 微应用 | `/jwapp/sys/<module>/...`；门户 `ehall.<school>` | [金智智慧校园手册](https://www.cdct.edu.cn/uploadfile/22/file/20210422/1619054778107523.pdf)、[武汉船舶适配记录](https://qing90bing.github.io/QingNoteblog/2025/07/31/a3975d2b.html) | 与 Web 端同源，入口变、接口不变 |
| 超星学习通 | `i.chaoxing.com` 扫码登录；课表在「课程表」 | [HBUT 学习通 API](https://www.webkkk.net/meixu0/hbut-api-collection) | **课表可能与教务排课不同源**，需校验课程数/教师 |
| 乘方移动应用平台 | 厂商称「同步教务全量业务」，可查课表/成绩/考试 | [乘方移动应用平台](https://entss.cn/product/details/wh78pe.html) | 仅产品页，**无接口文档**【未证实】 |
| 企业微信「掌上教务」 | 学校自建 H5 或厂商微应用 | [重庆财经职业学院通知](https://jwc.cqcfe.com/article_157113.html) | 有官方警告：**办事大厅与教务系统课表可能不同步** |
| 钉钉 | 智慧校园门户聚合「课程表查询」 | [钉钉高校方案页](https://www.dingtalk.com/qidian/page-FwFLofCv.html) | 只有产品能力描述，**无公开接口**【未证实】 |
| 微信小程序 | 多为学校自建查询页/第三方 | — | 无统一协议，不做通用适配 |

**关键判断**：移动端渠道的工程价值不在「多一条取数路径」，而在「多一个**会话来源**」——企业微信/钉钉里往往已经完成了 SSO，WebView 可以直接复用登录态。

---

### 3.5 渠道五：统一身份认证、门户与 webvpn

- **CAS**：`authserver.<school>/authserver/login?service=...`；吉林大学 `cas.jlu.edu.cn/tpass/login`，密码用页面同款 3DES `strEnc(账号+密码+lt,"1","2","3")`，还带「二次认证 → 微信验证码」。[SuperJLU 文档](https://github.com/Runa-Asahi/SuperJLU/blob/main/docs/cli.md)
- **TGC 换票**：北京信息科技大学 `POST /username-password/login`（SM2 加密）→ TGC → `GET /casLogin.do` → 教务 session。[iBistu API](https://github.com/ProjektMing/iBistu/blob/main/docs/API.md)
- **门户/一网通办**：`one.jift.edu.cn/home`（智慧校园门户）→ 常用导航「教学综合信息服务平台」；[江西服装学院说明](https://wxzx.jift.edu.cn/info/1289/1092.htm)。`e.hbut.edu.cn/stu/index.html#/ServiceShow?...` 教管一体化入口，与其它三个入口认证方式完全相同。[HBUT 文档](https://www.webkkk.net/meixu0/hbut-api-collection)
- **webvpn**：`webvpn.buu.edu.cn`（企业微信扫码）、`vpn.jift.edu.cn`（CAS）、`webvpn.gpnu.edu.cn`、牡丹江医学院「校外 → webvpn → 乘方教务」、北航 `vpn.buaa.edu.cn` / `e1/e2.vpn.buaa.edu.cn`。[北联大指南](http://mp.weixin.qq.com/s?__biz=MzU1ODc0NTQyOA==&mid=2247498178&idx=1&sn=0ad7cca3481a95e6469902c8703ff503&chksm=fd4f3ded39b0f066b17ae46fb121193514ef74c8628b263cfbcae83157ac3a60661fa2655d31#rd)、[广东技术师范大学通知](https://gpnu.edu.cn/info/1400/66343.htm)
- **坑**：
  1. 二次认证（短信/微信验证码）无法自动化，只能让用户在 WebView 里过；
  2. SSO 会话普遍短于教务 session，容易出现「门户还登着、教务已掉」；
  3. webvpn 会把目标 URL 重写成加密串（如 `/https/xxxx/`），**离线构造请求必失败**，只能在 WebView 内跟随跳转；
  4. WebView 跨域 Cookie 不共享，`HttpOnly` + `SameSite` 会让「导出 Cookie 给原生层」失效。

---

### 3.6 渠道六：校方开放平台 / 数据中台 / API 对接

| 案例 | 能力 | 门槛 | 证据 |
|---|---|---|---|
| 北京大学「数据共享 API」 | REST/JSON，明确包含**课程信息、空闲教室、个人课表、个人成绩**、通知公告、微信消息 | 校内信息门户「信息服务 → 数据共享API」提交申请，审批后由计算中心对接 | [北大 IT 服务说明](https://m.pku.edu.cn/portal_4.jsp) |
| 南京师范大学「数据申请审核流程」 | 按审批范围开通 API 接口与授权信息 | 仅二级单位申请；**校内网络使用**；不得扩大范围、不得向第三方提供凭证；到期重申请 | [南师大信息化处](http://net.njnu.edu.cn/info/1341/4181.htm) |
| 树维「校园开放平台 / 数据中台」 | 产品层面提供统一数据/服务接口 | 校级采购与治理项目 | [树维官网](http://www.supwisdom.com/) |
| 强智「开放平台，数百微应用」 | 产品层面开放平台 | 校级合作 | [强智官网](http://qzdatasoft.com/) |

**结论**：这条渠道**对第三方 App 基本不可得**——它不是技术问题，是主体资格问题（只对校内单位、且禁止转授权）。可作为「与学校官方合作」时的路径，不能作为产品默认依赖。

---

### 3.7 渠道七：研究生系统（单独一条链）

- **博雅研究生管理信息系统**：大连交通大学部署在 `https://dljtyjs.mh.chaoxing.com/` —— 即**超星系域名**，机构账号登录；课表不是接口，而是「通知公告 → 研究生课表汇总」的**附件（Word/PDF）**，学生按学院/专业下载。[大连交大课表查看说明](https://gs.djtu.edu.cn/_uploadimg/file/20240906/20240906170873207320.pdf)
  > 注意：「博雅」在高校里至少有两个所指——北航「博雅选课系统」（`bykc.buaa.edu.cn`，素质教育选课）与「博雅研究生管理信息系统」（超星系）。不要混。
- **国科大 SEP**：`sep.ucas.ac.cn` 教育业务接入平台，第三方脚本模拟登录后导出 `.ics`。[UCAS_Course_Schedule_Convertor](https://github.com/ldiex/UCAS_Course_Schedule_Convertor)
- **各校 yjs/grs**：
  - 广东技术师范大学：`研究生教育综合管理系统` → 培养 → 课程排课管理 → 课表信息查询 → **Word 打印**；校外走 `webvpn.gpnu.edu.cn`。[通知](https://gpnu.edu.cn/info/1400/66343.htm)
  - 浙江工商大学：`我的课表` / `排课课表`，教务员可「导出后发给老师学生」。[通知](http://yjs.zjsu.edu.cn/2019/0906/c3859a150914/page.htm)
  - 北京大学：研究生选课以 `elective.pku.edu.cn` 为准，教学网 `course.pku.edu.cn` 可能不一致。[研究生院 Q&A](https://grs.pku.edu.cn/tzgg/50118yjsy326380.htm)
- **规模**：目录中 301 条「研究生·待适配」、2 条「研究生·已实现」。**必须按域名（`yjs`/`grs`/`graduate`/`sep`）单独识别**，不要假设本科适配器可用。

---

### 3.8 渠道八：二维码 / 分享 / ICS / 订阅

- **学习通官方 ICS**：学习通「课程表 → 设置 → 订阅课程表」生成专属 ICS 链接，可被系统日历订阅。[操作说明（第三方站）](https://www.shenglijr.com/197787.html)【部分证实】
  > 但学习通的「课程表」是**教学平台课程**，不等于教务排课（缺周次/教室/单双周），只能当兜底。
- **第三方 ICS 生成器**（说明「ICS 是输出格式，不是数据源」）：
  - [getMyCourses](https://github.com/whoisnian/getMyCourses)（树维，NEU）
  - [zf-to-ics](https://github.com/zaochih/zf-to-ics)（正方，含 `xskbcx_cxRjc` 节次表与双作息）
  - [UCAS convertor](https://github.com/ldiex/UCAS_Course_Schedule_Convertor)（SEP）
  - [cqu-kb](http://github.es/CQU-AI/cqu-kb)（重庆大学，**订阅 URL 里带学号与密码**，隐私风险，作者自己也在 README 里提示了）
- **校方课表页二维码**：只在第三方软文里出现「课表页底部扫码同步至课程表 App」的说法（如 [超级课程表导入教程](https://www.word2txt.com/rjjc/536581.html)），**未见任何学校官方页面或公告佐证** → 【未证实】。
- **学校发的二维码**：多为「入口二维码」（扫码进智慧校园/企业微信/掌上教务），不是数据二维码。例：重庆财经「微信（企业微信）扫描左上角智慧校园进入 → 教务系统」。[通知](https://jwc.cqcfe.com/article_157113.html)
- **判断**：二维码渠道不值得投入；ICS 值得作为**导入/导出格式**支持（成本低、跨厂商通用、用户可感知）。

---

## 4. 组合策略（给 App 的取数优先级）

| 批次 | 渠道组合 | 覆盖（目录条目，推断） | 理由 |
|---|---|---:|---|
| **P0** | 正方新版 JSON（`xskbcx_cxXsgrkb` + `xskbcx_cxRjc`） + 强智（`app.do` JSON + jsxsd HTML 兜底） | 854 | 覆盖最高、都有可直接照抄的公开实现、都能在 WebView 会话下完成 |
| **P1** | 青果 HTML + 正方旧版 HTML + URP 新/旧 + 树维 classic/eams5 | +323 | 以 HTML/表格为主，可沉淀通用解析器；eams5 已有三段式参考 |
| **P2** | 金智 `/jwapp/` + 超星 + 乘方 | +230 | 路径一校一变或 SPA，适合「按校配置」而非「通用适配」 |
| **兜底** | 导出文件导入（xlsx / zip / ics） + WebView DOM 兜底 + 手动录入 | 长尾 | 覆盖「接口拿不到」的学校，也覆盖研究生附件课表 |
| **不做** | 校方开放平台、二维码、门户缓存数据 | <1% | 主体资格 / 未证实 / 数据不同步 |

**三条硬约束**：
1. **节次时间表是必需的第二请求**：正方 `xskbcx_cxRjc`、超星 `getZclistByXnxq`、eams5 `datum.periods`。没有它，「第 3-4 节」无法落成时间。
2. **优先取教务系统本体，不要取门户/企业微信缓存**：重庆财经官方已明确「办事大厅与教务系统课表可能不同步」。
3. **同一学校多出口并存时，以「能拿到周次+节次+地点」的出口为准**：学习通课表、班级课表、附件课表都可能缺字段。

---

## 5. 证据清单

| # | 结论 | 等级 | 链接 |
|---:|---|---|---|
| E01 | 正方新版课表端点 `POST /jwglxt/kbcx/xskbcx_cxXsgrkb.html?gnmkdm=N2151`，body `xnm/xqm/kzlx`，返回 `kbList` | 已证实 | [zhengfang-api](https://github.com/znjhahaha/zhengfang-api) |
| E02 | 同端点四个独立学校实现（HDU/ZJUT/XTU/SIT） | 已证实 | [HDU](https://github.com/cr4n5/HDU-KillCourse/blob/master/client/service.go)、[ZJUT](https://github.com/zjutjh/funnel/blob/master/app/apis/zf/zfApi.go)、[XTU](https://github.com/sky31studio/GongGong/blob/main/ems-sdk/xtu_ems/zf_ems/config.py)、[SIT](https://github.com/SIT-kite/kite-app/blob/master/lib/module/timetable/service/school.dart) |
| E03 | 正方变体 `gnmkdm=N253508` 与 `/kbcx/xskbcx_cxXsKb.html` 存在 | 部分证实 | [zhengfang_new.js](https://github.com/baoozak/timetable/blob/main/utils/parsers/zhengfang_new.js)、[zfn_api](https://github.com/openschoolcn/zfn_api) |
| E04 | 正方 `xskbcx_cxRjc.html` 提供节次时间，且存在「双作息」切换 | 已证实 | [zf-to-ics](https://github.com/zaochih/zf-to-ics) |
| E05 | 正方「教学进程查看」导出为多门课多个 .zip；考试信息导出 .xlsx；全选有课程名错乱 bug | 已证实 | [wmukebiao](https://github.com/pkx07/wmukebiao) |
| E06 | 正方课表打印页有【输出 EXCEL】【输出 PDF】，路径 `排课管理-课表打印` | 已证实 | [正方官方班主任手册 PDF](https://jwc.szcu.edu.cn/_upload/article/files/9c/5e/2b06b43d41639eaf3948624306a6/62b1c721-73fc-4f26-b253-ed3411f2baf1.pdf) |
| E07 | 正方教师课表打印可勾选导出 | 已证实 | [无锡南洋学院](http://hr.wsoc.edu.cn/info/1035/1508.htm) |
| E08 | 强智 `app.do?method=authUser/getKbcxAzc` + token header + `kcsj` 编码 | 已证实 | [code84](https://code84.com/677670.html)、[HUT_API](https://github.com/cxyzzz/HUT_API/blob/master/README.md) |
| E09 | 强智移动端即「智校园」App，抓包同源 `app.do` | 已证实 | [GDUF-QZAPI](https://github.com/EwingYangs/GDUF-QZAPI) |
| E10 | 强智 jsxsd `#kbtable`/`div.kbcontent`/`[01-02节]` | 已证实 | [强智课表抓取](https://www.jet-lab.site/blogs/qiangzhi) |
| E11 | 强智「打印即导出」，但学生端无原生 Excel 导出（需油猴脚本） | 部分证实 | [教师手册](http://mp.weixin.qq.com/s?__biz=MzA4OTQxNTEyMQ==&mid=2651942268&idx=5&sn=169dbb0b965e242a860a3b1443a651fe&chksm=8afd82d711dd975765e7ffcf064b9d767f2ec00aed6cd7382e552cd26619b8d010076e5f4a59#rd)、[DGUT 脚本](https://greasyfork.org/fil/scripts/573297-dgut%E7%8F%AD%E7%BA%A7%E8%AF%BE%E8%A1%A8%E5%AF%BC%E5%87%BA) |
| E12 | 强智官网 900+ 院校、含研究生综合管理平台与开放平台 | 已证实（厂商自述） | [qzdatasoft.com](http://qzdatasoft.com/) |
| E13 | 金智 `/jwapp/sys/wdkb/modules/xskcb/cxxszhxqkb.do`（南大） | 已证实 | [NJU 课表字段](https://github.com/121mc/NJU_Calendar_Importer_Flutter/blob/main/docs/%E8%AF%BE%E8%A1%A8%E5%AD%97%E6%AE%B5.md) |
| E14 | 金智 `/jwapp/sys/kbapp/api/wdkbcx/getMyScheduleDetail.do` + `homeapp/api/home/kb/xnxq.do`（北信科） | 已证实 | [iBistu API](https://github.com/ProjektMing/iBistu/blob/main/docs/API.md) |
| E15 | 金智 `/jwapp/sys/kcbcxmdl/KbcxController/queryxskb.do`，body `requestParamStr`（深职大） | 已证实 | [szpu 扩展](https://github.com/BoyangZhang619/szpu-jwxt-extension) |
| E16 | 金智 ehall 微应用页面形态（北理工 `wdkbby`、武汉船舶 `emaphome`） | 部分证实 | [BIT-enhanced](https://github.com/YDX-2147483647/BIT-enhanced)、[适配记录](https://qing90bing.github.io/QingNoteblog/2025/07/31/a3975d2b.html) |
| E17 | 金智统一登录可模拟，支持「查课表/成绩」类集成 | 部分证实 | [wisedu-unified-login-api](https://github.com/sanseyooyea/wisedu-unified-login-api) |
| E18 | 新 URP 返回 `dateList[0].selectCourseList[]`，`timeAndPlaceList` 含 `classWeek/weekDescription` | 已证实 | [URP 转 ics](https://nocolor.cc/archives/127) |
| E19 | URP 学生端课表页有「课表或列表区域下载文档」 | 部分证实 | [安徽财经大学通知](https://jwc.aufe.edu.cn/2020/0223/c2754a147002/page.psp) |
| E20 | 树维 eams5 三段式：`get-data` → `ws/schedule-table/datum` → `print-data` | 已证实 | [HFUTICS](https://github.com/ssyu0808/HFUTICS/blob/master/jxglstu.py)、[HFUT API](https://github.com/Chiu-xaH/HFUT-Schedule/blob/main/docs/HfutApi.md) |
| E21 | 树维经典 EAMS `courseTableForStd.action` + `dataQuery.action`（semesterCalendar） | 已证实 | [NWUPL 接口笔记](https://github.com/dream2333/NWUPL-Pure-EMS/blob/master/%E6%8E%A5%E5%8F%A3.md) |
| E22 | 超星 `queryKbForGrdb` 返回 JSON；`sdpkkblist` 是班级/教师端，仅识别不导入 | 已证实 | [Sleepy 1.0.49](https://github.com/lingion/sleepy/blob/main/docs/release-notes-v1.0.49.md) |
| E23 | 超星 `sdpkkbList?xnxq=&xhid=`、`getZclistByXnxq?xnxq=`、学习通扫码登录 | 已证实 | [HBUT API 文档](https://www.webkkk.net/meixu0/hbut-api-collection) |
| E24 | 乘方 `xsgrkbcx!getKbRq.action` 直出 JSON，`zc` 空即整学期 | 已证实 | [拾光 ZQU 脚本](https://github.com/ShiGuangSchedule/shiguang_warehouse/blob/main/resources/ZQU/zqu_01.js) |
| E25 | 乘方课表页可导出 Excel，且**不含调课信息** | 已证实 | [牡丹江医学院指南](http://mp.weixin.qq.com/s?__biz=MzIyODM4ODM5MQ==&mid=2247491010&idx=3&sn=c7f8569bcd64159563b7ec662830ff6f&chksm=e853e68adf246f9c2374834e5b0ed84fb3a797818825bcd9c71ba07908cd98670b42d168c6f3#rd) |
| E26 | 青果课表页路径（教学安排表 格式一/二）+ 青果掌上课表 App | 已证实 | [江汉大学指引](https://mt.sohu.com/20170909/n510682712.shtml) |
| E27 | 青果导出/打印在教师/教务员端 | 部分证实 | [青果导出打印方法](https://wenku.baidu.com/view/fe73eb2bcf1755270722192e453610661ed95aeb.html) |
| E28 | 青果表格列数不稳定，需「倒数 7 列」定位星期 | 已证实 | [拾光青果脚本](https://github.com/ShiGuangSchedule/shiguang_warehouse/blob/main/resources/qingguo_jiaowu/qingguo_01.js) |
| E29 | 企业微信「掌上教务」可查课表，但官方警告与教务系统可能不同步 | 已证实 | [重庆财经职业学院](https://jwc.cqcfe.com/article_157113.html) |
| E30 | 门户 `one.jift.edu.cn` + `vpn.jift.edu.cn` CAS，教务仅校内/ VPN 访问 | 已证实 | [江西服装学院](https://wxzx.jift.edu.cn/info/1289/1092.htm) |
| E31 | 校外 webvpn（企业微信扫码）→ 教务系统 | 已证实 | [北京联合大学](http://mp.weixin.qq.com/s?__biz=MzU1ODc0NTQyOA==&mid=2247498178&idx=1&sn=0ad7cca3481a95e6469902c8703ff503&chksm=fd4f3ded39b0f066b17ae46fb121193514ef74c8628b263cfbcae83157ac3a60661fa2655d31#rd) |
| E32 | 吉大 CAS 3DES + 二次认证（微信验证码），本科教务为金智 jwapp | 已证实 | [SuperJLU](https://github.com/Runa-Asahi/SuperJLU/blob/main/docs/cli.md) |
| E33 | 北大开放「数据共享 API」：课程信息、个人课表、个人成绩，REST/JSON，需门户申请 | 已证实 | [北大 IT 服务](https://m.pku.edu.cn/portal_4.jsp) |
| E34 | 南师大 API 共享限校内单位、校内网络、禁止转第三方 | 已证实 | [南师大信息化处](http://net.njnu.edu.cn/info/1341/4181.htm) |
| E35 | 「博雅研究生管理信息系统」部署在 `*.mh.chaoxing.com`，课表以通知附件发布 | 已证实 | [大连交大说明](https://gs.djtu.edu.cn/_uploadimg/file/20240906/20240906170873207320.pdf) |
| E36 | 国科大 SEP 可模拟登录并导出 ics | 部分证实 | [UCAS convertor](https://github.com/ldiex/UCAS_Course_Schedule_Convertor) |
| E37 | 研究生系统课表可 Word 打印；校外走 webvpn | 已证实 | [广东技术师范大学](https://gpnu.edu.cn/info/1400/66343.htm) |
| E38 | 研究生系统有「我的课表/排课课表」，教务员可导出下发 | 已证实 | [浙江工商大学](http://yjs.zjsu.edu.cn/2019/0906/c3859a150914/page.htm) |
| E39 | 学习通「订阅课程表」生成 ICS 链接 | 部分证实 | [操作说明](https://www.shenglijr.com/197787.html) |
| E40 | 树维 ICS 工具与正方 ICS 工具均需自行抓取，非校方提供 | 已证实 | [getMyCourses](https://github.com/whoisnian/getMyCourses)、[zf-to-ics](https://github.com/zaochih/zf-to-ics) |
| E41 | 第三方 ICS 订阅 URL 可含学号+密码，存在隐私风险 | 已证实 | [cqu-kb](http://github.es/CQU-AI/cqu-kb) |
| E42 | 4140 条目录、348/1128/2361/301/2 状态分布 | 已证实 | [Sleepy schools-list](https://github.com/lingion/sleepy/blob/main/docs/schools-list.md) |
| E43 | 拾光 260 条目、4 个厂商通用适配器 | 已证实 | [shiguang_warehouse](https://github.com/ShiGuangSchedule/shiguang_warehouse) |
| E44 | WakeUp 官方口径 1800+（首页）/ 1200+（文档页） | 已证实 | [首页](https://www.wakeup.fun/)、[文档](https://www.wakeup.fun/doc/) |
| E45 | 乘方有独立移动应用平台（同步教务业务），但无公开接口 | 未证实（仅产品页） | [乘方移动应用平台](https://entss.cn/product/details/wh78pe.html) |

---

## 6. 未证实清单（不要写进对外材料）

- ❓ **校方课表页二维码**：仅第三方软文提及「扫码同步至课程表 App」，无学校官方证据。学校发的二维码多为「入口二维码」。
- ❓ **强智是否存在统一 REST 接口**：目前证据只支持 `app.do` 与 jsxsd HTML 两条通道。
- ❓ **金智 `/jwapp/` 是否存在跨校统一课表路径**：现有 5 个学校样本，模块名与参数包装全不同。
- ❓ **超星是否存在跨校统一课表接口**：`queryKbForGrdb` 与 `sdpkkblist` 确认存在，但路径前缀、参数、会话依赖各校不同。
- ❓ **树维 eams5 是否所有部署都有 `print-data`**：HFUT/AHU/NEU 三校成立，样本仍少。
- ❓ **正方研究生系统是否与本科共用 `jwglxt`**：无证据；目录里研究生条目多为待适配。
- ❓ **「导出按钮在学生端普遍可用」**：反证更多——青果、强智的导出主要在教师/管理端；正方学生端是否可见由学校授权决定。
- ⚠️ **任何「覆盖 XX 所高校」的宣传数字**：强智 900+、怪兽课表 1500+、WakeUp 1800+ 口径互不相同，不能当覆盖率。

---

## 7. 对现有工程的落地建议（只建议，不改代码）

1. **把「渠道」做成一等公民**：`detectChannel(url, html)` 输出 `{protocol, channel, confidence}`，`channel ∈ {json, html, export, mobile, manual}`。同一协议可以有多个 channel，按置信度排序尝试，而不是「一个协议一个适配器」。
2. **每个协议都补「节次时间表」请求**：正方 `xskbcx_cxRjc`、超星 `getZclistByXnxq`、eams5 `datum.periods`。缺失时 UI 要明确提示「只能按节次显示，无法换算时间」。
3. **做一个通用「文件导入器」**：支持正方多 zip、正方/强智/乘方 xlsx、ICS。这是长尾学校与研究生附件课表的唯一可行通道，成本远低于再适配 10 个厂商。
4. **渠道可信度写进数据模型**：`source_kind ∈ {jw_api, jw_html, jw_export, portal_cache, third_party_ics}`，`portal_cache` 与 `third_party_ics` 默认标「可能不同步」。
5. **会话失败分类要细化**：`NeedsCaptcha` / `NeedsSecondFactor` / `NeedsWebVpn` / `SessionExpired` / `PermissionDenied` / `StructureChanged`。现在「登录失败」一个状态无法指导用户。
6. **不要做**：门户缓存取数、二维码识别、开放平台对接、`authUser` 明文口令直连。前者不可靠，后者是安全问题。

---

### 附：调研方法备注

- 端点结论尽量做到「两个以上独立来源」或「一个可运行实现 + 字段级说明」；只有单一博客的标【部分证实】。
- 覆盖数字全部来自开源目录并注明口径；本喵对 4140 条按「协议候选」列重算了一遍，与既有文档一致（1478 条有候选，扣表头后）。
- 未对任何学校发起登录或抓取；所有「登录方式」「导出按钮」描述均来自公开页面、公开仓库或学校公开通知。
- 后续若要落地某条渠道，建议先采集 2–3 所不同学校的脱敏响应/页面，再写适配器，避免「一校一版」。
