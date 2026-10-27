# 中国课表产品「导入渠道」全集与渠道矩阵（2026-10-26）

> 角色：课表产品渠道调研员｜范围：**用户可见的导入入口**（不评价 UI 美观、不做竞品功能清单）
> 前置：已读 [industry-import-practices.md](industry-import-practices.md)（2026-10-19），本文件只写**它没覆盖、已过时、或需要按"渠道"重新切分**的部分。
> 标记：**【事实】**有官方文档/源码/可复现请求佐证；**【推测】**基于机制或代码的推断；**【未证实】**二手口径、互相抄袭的 SEO 文、或单方陈述。
> 抓取方式：官方文档 + GitHub 仓库/raw + 应用商店页面 + 第三方实现源码。本机直连 GitHub raw 被重置，改用抓取服务与 GitHub API；星链官网用 curl 取 HTML 后抽文本。
> 本次新增（相对 10-19 文档）：星链课表全渠道、Sleepy 全渠道与协议族、拾光 `GLOBAL_TOOLS`（跨 App 分享码导入）、WakeUp v6.1.70 口令协议现状、超级课程表扫码语义纠偏、小程序/浏览器插件/ICS 转换器。

---

## 0. 一页结论

1. **渠道已经收敛成"6 条主线 + 2 条横向"**：账号直连、通用协议+自定义 URL、WebView 登录注入、文件导入、粘贴文本、AI 截图 OCR；横向是**分享码/二维码**与**日历订阅/导出**；手动录入永远是底座。没有产品能只靠一条渠道活下来，差别只在"主线之间怎么排序"。
2. **真正的分水岭是"解析跑在哪"**，不是"支持多少学校"。WakeUp 现行口令 = 短码 + `api.wakeup.fun` 服务端 + antispam 反作弊；星链分享码 = **第三方实现可无鉴权 GET 直接取明文 JSON**；Sleepy/拾光 = 设备端解析。解析位置决定了维护成本、隐私面、以及第三方生态能不能活。
3. **覆盖数字全部不可横比**。WakeUp 官网 1800+、文档 1200+，而其 **6.1.06 APK 内置 3,570 条学校记录（22 条是通用入口）**；星链官方 2000+；超级课程表 3618（应用商店更新日志口径）；小爱商店文案 1928；Sleepy 只有 **350 个有实测证据的导入配置**，但来源行有 5,779 条；拾光本次统计 **264 个学校目录 / 269 个 adapter**。"收录"与"能导入"是两件事，行业里普遍混用。
4. **文件导入不是万能兜底，而是"带前置条件的兜底"**。WakeUp 的 HTML 导入要求"学校已在列表 **或** 属于正方/强智"，并且要**手动选教务类型**、强智还要逐个模式试；CSV 模板对"星期/节次/周数"格式极严（周次用中文顿号、单双周写 `x-x单`）。Sleepy 把这条做到最稳：7 种文本/文件格式 + 强制预览 + 5 种冲突处理。
5. **AI OCR 已成标配，但产品定位分三档**：星链把 AI 当主入口（截图/网页/文件/粘贴文字都交给 AI，3 秒出表）；超级课程表/小爱把 AI 当**下线后的替代**（拍照识别）；Sleepy 只给"识图提示词"、**不内置上传**——隐私最优，但完成度最低。
6. **分享码正在变成"跨 App 输入格式"**。拾光 `GLOBAL_TOOLS` 现在直接提供 **WakeUp 口令导入** 与 **星链分享码导入** 两个适配器；Yunzai/Koishi 机器人插件也同时支持两者。这意味着：**做分享码不是为了社交，而是为了接住别的 App 的用户**。
7. **成本排序（低→高）**：手动 < 粘贴文本 < 文件（CSV/HTML/JSON/ICS）< 日历导出 < 通用协议+URL < WebView 脚本 < 学校专用适配 < AI OCR（含调用成本）< 分享码逆向（WakeUp 级）。
8. **对纸间课表的直接结论**：先把"粘贴文本 + 多格式文件 + 通用协议选择 + 可改 URL 的 WebView + 强制预览/冲突处理"补齐（全部设备端、零服务端）；**星链分享码可以做**（第三方已验证的公开 GET，半天工作量）；**WakeUp 现行口令不做**（版本钉死 + 反作弊 + 条款风险），只保留旧版离线解码与 `.wakeup_schedule` 文件导入。

---

## 1. 渠道对照总表（产品 × 渠道）

图例：● 原生支持｜◐ 间接/部分/需条件｜○ 无｜? 未证实｜— 不适用。

### 1.1 账号与在线渠道

| 产品 | 账号直连 | 通用协议 + 自定义 URL | WebView 登录注入 | 分享码 / 二维码 | 覆盖口径（证据） |
|---|---|---|---|---|---|
| **WakeUp** | ● 部分校可直接输账号密码 | ● 通用教务类型（新 URP/URP/正方新老/强智/旧强智）+ 顶栏可改 URL | ● 多数校走网页导入 | ● 分享口令（短码→服务端） | 官网 1800+／文档 1200+／APK 3,570 条 |
| **超级课程表** | ● 教务系统导课 | ◐ 可"手动输入教务网址"，是否通用解析未知 | ● 内置网页登录 | ◐ 扫码=同学/班委已录课表的二维码；无文字口令 | 3618 所（商店日志） |
| **星链课表** | ● 选学校→登录→课表/考试/成绩 | ○ 未见"通用类型"选择，靠 AI 兜底【推测】 | ● 教务导课内嵌网页 | ● 分享码；另可粘贴星链 JSON 走 AI 解析 | 2000+ 高校、10 万+ 用户 |
| **Sleepy** | ● 学校目录 → WebView 登录 → 自动抓取 | ● 搜索框直接输教务 URL，按 URL 猜协议 | ● 唯一在线入口 | ○ 不产码；但能导入 WakeUp 分享文本 | 350 个实测配置；5,779 条来源行 |
| **拾光** | ● 每校一个 JS 适配器 | ● 4 个通用教务（正方/URP/青果/超星），`import_url=""` | ● 脚本注入已登录页 | ◐ 自身无码；`GLOBAL_TOOLS` 可导入 WakeUp/星链码 | 264 校目录 / 269 adapter（本次统计） |
| **小爱（已下线）** | ● 学校列表（2025-04 下线） | ○ | ● provider 在 WebView 取数 | ◐ 分享链接"一人编辑全班导入"【未证实】 | 商店文案 1928 所【未证实】 |
| **下节啥课** | ● 学校列表 + WebView | ○ 学校目录固定 | ● provider 注入 | ○ | "上千所"（README） |
| **ClassIsland** | ○ | ○ | ○ | ○ | —（班级大屏，不做教务） |
| **小程序** | ◐ 校园官方小程序绑学号；第三方多为手动 | ○ | ◐ 狐友有教务登录 | ● 狐友分享口令（有时效） | 学校自建，无统一口径 |
| **插件/转换器** | ○ 复用浏览器已登录会话 | ○ | —（本身在浏览器里） | ○ | 单校/单协议 |

### 1.2 文件、文本与外部格式

| 产品 | Excel/CSV | HTML | JSON/备份 | ICS | 粘贴文本 | AI OCR | 日历订阅/导出 |
|---|---|---|---|---|---|---|---|
| **WakeUp** | ● Excel 模板另存 CSV | ● 需选教务类型 | ● `.wakeup_schedule` 备份（iOS 暂无） | ◐ 仅导出 | ○（只粘贴口令） | ○ | ◐ 导出 ICS |
| **超级课程表** | ? 仅 SEO 教程，无官方模板 | ○ | ○ | ? | ○ | ● 拍照识别 | ? 同步系统日历 |
| **星链课表** | ? 文件交给 AI，格式未明示 | ? 教务网页交给 AI | ● 粘贴星链 JSON → AI 解析 | ◐ 导出 iCal | ● AI 识别粘贴文字 | ● 截图/拍照/粘贴 | ◐ 同步系统日历 |
| **Sleepy** | ● CSV（含表头） | ● `<table>` 表格 | ● Sleepy/WakeUp JSON | ● 标准 iCalendar | ● 7 种格式自动识别 | ◐ 只给外部提示词 | ◐ 导出 ICS / 写系统日历 |
| **拾光** | ? 未见 CSV 入口 | ○ | ● 导入/导出 JSON | ◐ 既有调研称支持（待复核） | ○ | ○ | ○ |
| **小爱** | ○ | ○ | ○ | ○ | ○ | ● 拍照导入（下线后唯一） | ○ |
| **下节啥课** | ○ | ○ | ○ | ○ | ○ | ○ | ○ |
| **ClassIsland** | ● 学校电子版课表表格拖入 | ○ | ○（档案是内部格式） | ○（主线无，社区 fork 才加） | ○ | ○ | ○ |
| **小程序** | ? | ● 无广课程表支持 HTML | ● 无广课程表支持 JSON 备份/AI JSON | ○ | ○ | ◐ 狐友智能识图（每日 20 次） | ○ |
| **插件/转换器** | ● wmukebiao 吃 `.xlsx` 考试表 | ○ | ○ | ● 产出 ICS | ○ | ○ | ● zf-to-ics 发 Gist 订阅 URL |

### 1.3 读表要点

- **"账号直连"几乎人手一个，但只有 WakeUp 把"通用类型"做成显式菜单**；星链、超级课程表的"通用"是隐式的（匹配不到就进网页/让用户输网址）。
- **HTML 导入只有 WakeUp 与 Sleepy 做成独立入口**；前者要求"先声明教务类型"，后者要求"表头可识别"，都不是真正"任意 HTML"。
- **JSON 是备份格式，不是交换格式**：WakeUp 用 `.wakeup_schedule`，Sleepy 用 `sleepy-v1`/WakeUp JSON，拾光用 `{courses,timeSlots,config}`。真正被第三方广泛吃下的是**拾光 JSON** 与 **星链 JSON**。
- **只有星链把 AI 放在主路径**，其余产品的 AI 都是"兜底"或"迁移工具"。

---

## 2. 分产品详解

### 2.1 WakeUp 课程表（渠道最全，也是"分享码反例"）

官方导入弹窗只有 5 个入口 + 1 个申请适配：从学校教务导入、从 Excel 导入、从 HTML 文件导入、从分享/导出文件导入（iOS 版暂无）、从分享口令导入（[官方文档](https://www.wakeup.fun/doc/import_schedule.html)）。**【事实】**

| 渠道 | 用户操作路径 | 技术实现 / 覆盖 | 失败兜底 | 维护成本 · 隐私 |
|---|---|---|---|---|
| 学校教务 | 搜学校 → 登录 → 找"个人课表" → 展开学期/周数 → 右下下载 → 新建课表 | 学校列表 + 专用适配；部分校支持直接输账号密码，多数走网页 | 搜不到→申请适配（腾讯问卷）；导入失败→改用通用类型 | 每校 1–5 人日 + 年检【估算】；密码若落盘是最大隐私点 |
| 通用教务类型 | 不选学校，直接选"新 URP/URP/正方（新/老）/强智/旧强智" | 按登录页/页脚厂商标识选型；官方专章教用户辨认 | 旧强智在 App 内"无法创建对象"→引导 HTML 导入 | 6 类协议一次性 2–4 人日/类；无学校元数据 |
| 可改 URL 的 WebView | 导入页顶栏就是地址栏，可粘贴任意教务 URL | WebView 登录，页面内抓取；这是"长尾学校"的真正兜底 | 页面结构不认→HTML 文件导入 | 低；但脚本/页面可读 Cookie |
| Excel/CSV | 下载模板 → WPS 填 → **另存为 CSV**（不能改后缀）→ 选取文件 | 模板列：课程名称/星期/开始节数/结束节数/老师/地点/周数；周次用 `、` 分隔，单双周 `x-x单`/`x-x双`；地点老师必须填"无"（[CSV 文档](https://www.wakeup.fun/doc/import_from_csv.html)） | 报错→检查是否合并单元格/编码；安卓可经微信"用其他应用打开" | 解析 2–3 人日；零隐私风险，但用户教育成本极高 |
| HTML 文件 | 教务课表页右键"查看源代码"→全选复制→记事本存 UTF-8→选文件→**再选学校类型** | 不是通用解析：前提是"学校已在列表 **或** 教务属于正方/强智"，且强智要逐个模式试（[HTML 文档](https://www.wakeup.fun/doc/import_from_html.html)） | 报错→换编码/换模式 | 中；本质是"把页面当离线样本喂给已有解析器" |
| 分享/导出文件 | 选 App 导出的备份文件 | `.wakeup_schedule`（旧版源码 `file.writeText(data)`） | iOS 无此入口 | 低；文件可转发=隐私外泄面 |
| 分享口令 | 复制同学口令 → 导入 → 粘贴 | **短码 + 服务端**：`api.wakeup.fun`，新版 v6.1.70 走 `/pluto/app/antispam` 取 signB，再 `/share_schedule/getv2`；私有 DES + RC4 + MD5，设备指纹由 AndroidID 派生（[拾光实现](https://github.com/ShiGuangSchedule/shiguang_warehouse/blob/main/resources/GLOBAL_TOOLS/wake_up.js)） | 口令失效/命中反作弊→无兜底，只能改文件 | **最高**：版本钉死，App 一升级就全废；且逆向涉条款风险 |
| 申请适配 | 腾讯问卷提交学校/教务地址 | 官方人工适配；旧版另有 `apply_html(school,type,html,qq)` 众包上传页面 | — | 众包是 1200+ 的真实产能来源【推测】 |

**关键事实与纠偏**

- **覆盖口径**：官方首页 1800+、文档 1200+、商店 2000+/3000+；但 Sleepy 从 **WakeUp 6.1.06 APK 内置资源**数出 **3,570 条学校记录 + 22 条通用入口**（时间戳 2025-11-13），并明确"不是 2026 最新在线库"（[Sleepy 目录说明](https://github.com/lingion/sleepy/blob/main/docs/catalog/README.md)）。**【事实】** → 对外的"1200/1800"更可能是"已验证可导入"，3,570 是"列表里有"。
- **口令不是"离线可解"**：WakeUp2ICS 要求 `APK_PATH`（提取签名）+ 使用过 WakeUp 的设备的 `ANDROID_ID`，否则"会命中反作弊"（[README](https://github.com/airline233/WakeUp2ICS)）。**【事实】** 这与 10-19 文档一致，但需补充：**新版口令并非完全不可解**——拾光已把 v6.1.70 的签名/antispam/加密完整重实现并在生产使用。**【事实，读到源码】** 代价是"版本钉死"，属于持续对抗。
- **第三方生态已被打过一轮**：Yunzai 插件称 4 月初迁移接口加鉴权、4 月 29 日加强并返回"命中反作弊"，作者建议停用并迁移到拾光/星链；同时给出"降级到 6.0.23 可导出"的经验（[README](https://github.com/Temmie0125/Yunzai-Schedule-Plugin)）。**【单方陈述→未证实，但机制与 WakeUp2ICS 的 APK/AndroidID 要求互相印证】**
- **隐私/合规**：隐私政策明确涉及剪贴板读取（分享口令导入）（[policy](https://www.wakeup.fun/policy_new.html)）；逆向服务端接口在厂商明确"反作弊"的前提下，属于高不稳定 + 条款风险路径，不适合作为产品能力承诺。

### 2.2 超级课程表（规模最大，机制最不透明）

| 渠道 | 用户操作路径 | 技术实现 / 覆盖 | 失败兜底 | 维护成本 · 隐私 |
|---|---|---|---|---|
| 教务系统导课 | 课表页 → 右上角 `≡`/`+` → "教务系统导课"/"导入课程" → 进入 → 登录 → 自动抓取 | 自建闭源抓取；学校库 3618 所（商店更新日志，[百科](https://baike.baidu.com/item/超级课程表-表表/16853910)） | 匹配失败→"手动输入教务网址"→网页登录 | 高；无公开规范，无法评估 |
| 扫码导课 | 课表页 → `+` → 扫码导课 → 扫同学/班委分享的二维码 | **二维码承载的是"一张已录入课表"的复制凭证**，不是教务系统授权（应用说明："班长或辅导员录入课表后，可将二维码分享到群聊…即可复制并生成你的课程表"）[mrpyx](https://www.mrpyx.cn/soft/25862.html) | 课程不一致→手动改 | 低；但"整张课表=行踪"被二维码转发 |
| CSV/Excel | 声称"导入课表文件"→选 CSV/XLS→勾选课程→确认 | **仅见 SEO/聚合站教程，未见官方模板或官方帮助页**；多篇文本高度雷同，疑为 AI 生成 | 未知 | **【未证实】不要当事实写进产品对标** |
| 拍照识别 | 拍照/相册 → 识别 → 编辑后导入 | OCR/多模态；识别后需人工校对 | 识别失败→手动 | 中；图片含课表+可能含姓名学号 |
| 手动添加 | 右下 `+` → 填课程/教师/地点/周次/节次 | 基础能力 | — | 低 |

**注意**

- 关于"扫描教务系统课表页二维码授权"的说法（多篇 SEO 文），与"同学分享二维码"这一应用内说明**直接冲突**；后者有具体使用场景（班长/辅导员先录），可信度更高。**【冲突→按"同学课表二维码"记，另一说标未证实】**
- 合规史：2020-11 曾被 App 违法违规收集使用个人信息治理工作组通报（[百科](https://baike.baidu.com/item/超级课程表-表表/16853910)）。**【事实，百科口径】** 做竞品隐私对标时应引用，而不是学。
- 2026-06 与 OPPO ColorOS 合作，课程信息进入流体云/小布建议（同上）——说明"课表"正在变成系统级日程源，**导出/订阅渠道的价值在上升**。

### 2.3 星链课表（AI 主路径 + 最"松"的分享码）

官网把导入写成 4 条：教务导课、AI 截图识别、网页与文件、分享码；并列出已适配 11 类教务：正方、强智、青果、URP、金智、树维、超星、乘方、南软、优慕课、优霓（[官网](https://www.starlinkkb.cn/)）。**【事实】**

| 渠道 | 用户操作路径 | 技术实现 / 覆盖 | 失败兜底 | 维护成本 · 隐私 |
|---|---|---|---|---|
| 教务导课 | 选学校 → 登录 → 课表/考试/成绩一起带回 | 自建抓取；2000+ 高校；11 类教务 | 没适配→AI 截图/网页/文件；可提交适配申请 | 高（闭源）；账号密码在第三方 |
| AI 截图识别 | 截图/拍照/粘贴一段文字 → 3 秒出表 | 多模态识别课程、教室、节次、周次 | 识别错→预览编辑；再不行手动 | 中 + 推理成本；**图片上传=课表外泄** |
| 网页与文件 | 教务网页打开即可识别；课表文件交给 AI | 网页/文件 → AI 结构化 | 格式不认→截图 | 中；网页可能含姓名学号 |
| 分享码 | 同学：右上角三点 → 倒数第二行"分享课表" → 在线分享 → 复制分享码；你：输入分享码 | **第三方实现直接 `GET https://api.starlinkkb.cn/share/curriculum/{code}`（官方未公开该接口），无鉴权，返回 `{data:{courses[],startDate,totalWeeks,sectionMinutes}}`**；码形如 `[A-Za-z0-9-]{5,20}`（[拾光 starlink.js](https://github.com/ShiGuangSchedule/shiguang_warehouse/blob/main/resources/GLOBAL_TOOLS/starlink.js)） | 码失效→提示"分享码已失效或网络异常"；无二次兜底 | **极低（半天）**；但"整张课表可被任何拿到码的人读取" |
| 粘贴 JSON | `+` → 一键导课 → 粘贴 JSON → AI 解析 | 星链 JSON 不含时间信息；时间表要单独导出（`上课时间`→编辑→分享时间 JSON） | AI 解析失败→重贴 | 低；第三方插件已实现 |
| 导出 | 导出 iCal / 图片；同步系统日历 | 单向导出 | — | 低 |

**关键判断**

- **星链分享码是目前"成本/收益"最好的跨 App 渠道**：一个公开 GET + 明文 JSON，不需要 APK、不需要设备指纹、不需要签名。代价是**没有反作弊**，任何人可枚举/转发。**【推测】** 码长度 5–20 位且字符集小，存在被穷举的可能；官方未说明有效期/频控。
- **星链 JSON 是"半结构化"**：`sectionMinutes` 是 `{节次: [开始分钟, 结束分钟]}`，与主流的 `timeSlots[{number,startTime,endTime}]` 不同，接入时要转换。
- **隐私面偏大**：隐私政策写明通过友盟+ SDK 收集 Android ID/OAID/GAID、**传感器列表**（用于设备指纹/防模拟器）、位置（天气主题）（[隐私政策](https://privacy.starlinkkb.cn/)）；小米商店权限列表还含"获取手机信息（IMEI/IMSI）""REQUEST_INSTALL_PACKAGES""前台服务"（[商店页](https://r.app.xiaomi.com/details?id=com.xlhzcm.starcurriculum)）。**【事实】**
- **AI 是主入口也是主风险**：截图/网页/文件都可能带姓名、学号、班级；产品文案未说明是否上云、留存多久。**【推测】** 按行业惯例是云端多模态。

### 2.4 Sleepy 轻课表（开源，设备端解析的"教科书"）

入口在「课表管理 → 导入课表」，三条：教务直连、粘贴课表文本、从文件导入（`.json`/`.ics`/`.csv`/`.html`）；**所有路径先生成预览，确认后才写入**（[README](https://github.com/lingion/sleepy)）。**【事实】**

| 渠道 | 用户操作路径 | 技术实现 / 覆盖 | 失败兜底 | 维护成本 · 隐私 |
|---|---|---|---|---|
| 教务直连 | 选已收录学校，或搜索框输入教务 URL → WebView 登录 → 自动抓取 → 预览 | 协议族 20+：`wisedu`、`qz`/`qz_old`/`qz_crazy`/`qz_br`/`qz_with_node`、`zf`/`zf_1`/`zf_new`、`urp`/`urp_new`、`cf`、`classic_eams`、`eams5`、`whut`、`cqu`、`seu`、`zju`、`ustc`、`scu`、`neu`、`hnust`、`hniu`、`pku`、`bnuz`、`chaoxing`、`qz_app`/`qz_ieas`、`boya_pp`、`xju_post`、`yethan`、`ucas` 等（README 协议示例表） | 学校不在目录→输 URL 猜协议；仍失败→提适配申请（要求教务 URL + 失败现象，禁止提交账号密码） | 中；**GPL-3.0**，代码可读不可抄进闭源 |
| 自定义 URL | 搜索框直接输 `https://jw.xxx.edu.cn` | 按 URL 识别协议 → 打开该地址登录 → 对应解析器 | 识别不出→按通用协议尝试/提申请 | 低；URL 识别是"最便宜的通用兜底" |
| 粘贴文本 | 粘贴任意格式 → 自动识别 → 预览 | 7 种：WakeUp 分享文本（`【来自WakeUp课程表】`开头）、WakeUp JSON、ICS、CSV（含表头）、HTML `<table>`、纯文本（一行一课、Tab 分隔）、`sleepy-v1`（带 `chk` 完整性字段） | 识别失败→提示格式示例 | 2–4 人日；零登录 |
| 文件导入 | 选 `.json`/`.ics`/`.csv`/`.html` | 同上解析器；文件从系统选择器进入 | 同上 | 低 |
| 预览/冲突 | 导入前展示课程与冲突 → 5 选 1：替换当前/导入为新表/仅追加不冲突/合并为新表/连冲突一起追加 | 端上 diff + 冲突检测 | 中途退出保留"导入草稿" | 3–5 人日；**这是最值得抄的交互** |
| AI（非内置） | 复制"识图提示词"→ 自己找识图工具 → 把文本贴回 | **不上传截图、不内置 AI** | — | 0 成本；隐私最优、完成度最低 |
| 导出 | WakeUp 兼容 JSON / 分享文本（URL 编码 JSON）/ ICS / `sleepy-v1`；可写系统日历；可交小爱 | 单向导出；小爱入口会校验 1–30 周、节次连续 | 不合法→提示并停止 | 低 |

**关键事实**

- 学校目录**三种状态**：已有导入配置 / 待验证可尝试 / 待适配；"收录 ≠ 支持导入"，新条目未做真实登录不标"已支持"。**【事实】** 这套状态机比"1200+/2000+"诚实得多。
- 目录来源：教育部 2026-06-18 名单（大陆 3,196 所）+ 港澳台官方 + **WakeUp 6.1.06 APK（3,570 条）** + 用户提供的 WakeUp 差集（2,253 条），合计 **5,779 条来源行、44 条通用入口**；旧 **350 个导入配置**保留实测证据（[目录说明](https://github.com/lingion/sleepy/blob/main/docs/catalog/README.md)）。**【事实】**
- 隐私：本地存储、无 Firebase/Crashlytics/Sentry/Bugsnag；定位仅点击后前台请求、坐标不保存不上传；权限按需（日历/通知/精确闹钟）。**【事实】**
- 工具链：`tools/sleepy-collector`（Go 采集器）、`docs/adapt-kit`（采集教程）、`PROTOCOL_MAPPING.md`（协议裁定）。**【事实】**

### 2.5 拾光课程表（脚本仓库 + 跨 App 导入）

| 渠道 | 用户操作路径 | 技术实现 / 覆盖 | 失败兜底 | 维护成本 · 隐私 |
|---|---|---|---|---|
| 学校索引 | 选学校 → 进 `import_url`（登录页）→ 脚本自执行 → 桥回传 → 保存 | `root_index.yaml` + `adapters.yaml`（7 必填字段）+ `*.js`；PB 索引 v2；客户端只拉索引和 `resources/**/*.js` | 脚本抛错→桥 `showAlert`；无匹配→通用教务 | 社区贡献；索引/协议版本是护栏 |
| 4 个通用教务 | 选"正方/URP/青果/超星 通用"→ 输 URL → 登录 → 切到课表页 → 执行导入 | `category: GENERAL_TOOL`、`import_url: ""`；本次确认 4 个目录仍在：`zhengfang_jiaowu`、`urp_jiaowu`、`qingguo_jiaowu`、`chaoxing_jiaowu`（[zhengfang adapters.yaml](https://github.com/ShiGuangSchedule/shiguang_warehouse/blob/main/resources/zhengfang_jiaowu/adapters.yaml)） | 页面不认→提示"切到个人课表页再执行" | 低；用户自担登录 |
| 跨 App 分享码 | 选 `GLOBAL_TOOLS` 里的 "WakeUp课程表分享口令导入(支持新版)" 或 "星链课表分享码导入" → 执行 → 粘贴 | `GLOBAL_TOOLS/adapters.yaml` 现有 4 条：组件测试、适配代码测试、WakeUp、StarLink；WakeUp 走 v6.1.70 协议，星链走公开 GET | 码失效→Alert；无兜底 | WakeUp 高（版本钉死）、星链极低 |
| 文件 | 导入/导出 JSON（`{courses,timeSlots,config}`）；ICS 既有调研称支持【待复核】 | 与第三方（Yunzai/Koishi）互通 | — | 低 |
| 手动 | 常规 | — | — | 低 |

**关键事实**

- 本次统计：`resources/` 下 **269 个 `adapters.yaml`**、**287 个 `.js`**、**264 个学校目录 + 5 个工具目录**（4 通用教务 + `GLOBAL_TOOLS`）。**【事实，GitHub tree 统计 2026-10-26】**
- `GLOBAL_TOOLS` 的出现是**渠道策略变化**：从"只做本校导入"转为"接住竞品用户"——WakeUp 口令与星链分享码都成了拾光的输入源。**【事实】**
- 风险未变：任意 JS 在已登录教务页 origin 执行，可读 Cookie、可发任意请求；仓库未见脚本签名/哈希白名单机制。**【推测风险，10-19 文档已提】**

### 2.6 小爱课程表（已下线，但三文件契约仍是最好参考）

| 渠道 | 用户路径 | 技术实现 | 现状/兜底 | 成本 · 隐私 |
|---|---|---|---|---|
| 教务导入 | 学校列表 → 登录 → provider 取页面/接口 → 云端 parser → timer | 三文件三函数：`scheduleHtmlProvider(iframeContent, frameContent, dom)`（WebView）/`scheduleHtmlParser(input)`（**小米服务器**）/`scheduleTimer({providerRes,parserRes})`（WebView） | **2025-04 起入口关闭**，提示"产品技术方案调整，建议拍照导入"；2025-09 仍可 vConsole 改 hash 到 `/import`，但学校列表 API 返回 405（[考据](https://blog.deepchirp.com/2025/09/07/Restore-the-Academic-Import-Feature-in-XiaoAi-Schedule)） | 云端 parser=平台单点；课表原文出设备 |
| 拍照导入 | 拍照 → 识别 → 编辑 | OCR/多模态 | 下线后唯一"自动"入口；识别率被用户吐槽 | 中 |
| 分享链接 | 同学分享链接 → 打开 → 导入 | 链接携带整张课表；"一人编辑，全班分享"（[商店文案](https://m.9upk.com/ruanjian/22355.html)） | **【未证实】** 仅此一处描述 | 低；链接=课表公开 |
| 手动 | 逐门添加 | — | — | 低 |

- 覆盖：商店文案"已覆盖 1928 所高校"，2022 知乎称 1832 所；官方文档站未给数字。**【未证实】**
- 下线原因：官方只给"产品技术方案调整"；第三方推测与"抓取第三方教务数据"的合规/接口压力有关（[考据文](https://blog.deepchirp.com/2025/09/07/Restore-the-Academic-Import-Feature-in-XiaoAi-Schedule) 提出该疑问但未定论）。**【推测/未证实】**
- 契约价值：字段上限（name/position/teacher ≤50 字节、weeks 1..30、day 1..7、sections 1..30）+ "星期/地点/时间全同才算一节课" + provider 可视化诊断台 + `do not continue` 软失败——这些仍是最完整的移动端约束样本。**【事实，10-19 文档已详述】**
- 残留：`i.xiaomixiaoai.com/h5/precache/ai-schedule/#/import` 仍可打开；`h5_pad_config.json` 里仍有 `ai-schedule` 配置。**【事实，考据文】**

### 2.7 ClassIsland 与下节啥课（两条"极简渠道"样本）

**ClassIsland（班级大屏，不做教务）**

| 渠道 | 路径 | 实现 | 兜底 | 成本 · 隐私 |
|---|---|---|---|---|
| 文件拖入 | 把学校发布的电子版课表**表格文件**拖进"档案编辑"窗口 | Excel/表格解析；导入课表 + 时间表 | 手动建 | 低；许可 **GPLv3 + LGPLv3**，只可读思路 |
| CSES | 从 [CSES](https://github.com/SmartTeachCN/CSES) 导入 | 教育课表交换标准，偏中小学/班级 | — | 低 |
| 其他课表软件 | 从 Class Widgets 等导入 | 私有/半公开格式 | — | 中 |
| ICS | **主线不支持**；社区 fork `Leaf-lsgtky/miuix-ClassIsland` 自行加 | — | — | — |

- 官方文档只写"拖入/手动"，未见 ICS/JSON（[档案编辑](https://docs.classisland.tech/app/profile/)）。教务导入 Issue #1279 于 2025-11-17 **closed as not planned**："不属于主线，应由插件实现"。**【事实】**

**下节啥课（最小渠道集）**

- 只有"学校列表 + WebView 登录 + provider 抓取"，7 大教务（正方/URP/青果/强智/金智/南软/超星）新老变种；**无文件、无粘贴、无分享码、无 OCR**；MIT（[README](https://github.com/baoozak/timetable)）。**【事实】**
- 价值：证明"只做一条渠道 + 做好学校目录"也能成立（64★，uni-app）；反模式是 `document.title` 当传输通道（10-19 文档已判）。

### 2.8 小程序、浏览器插件与 ICS 转换器（长尾生态）

| 形态 | 代表 | 渠道 | 技术要点 | 风险/成本 |
|---|---|---|---|---|
| 微信小程序（第三方） | 无广课程表 | 文件导入 HTML/JSON + 手动 + 导出备份 | 小程序选文件 → 解析 → 预览 → 确认；JSON 也接受"AI 生成"的课表（[教程](https://github.com/pocketchen18/ikun_web/blob/main/src/content/posts/tutorial.md)） | 低；微信内传播快；无教务登录 |
| 微信小程序（校园社区） | 狐友 | 教务系统导入 + 分享口令 + 智能识图 + 手动 | 口令有时效；识图每日 20 次；"不会存储密码"文案（[教程](http://mp.weixin.qq.com/s?__biz=Mzk1NzIwNTQwNw==&mid=2247484803&idx=1&sn=f48244a0ea39b96b463cac6f5f185d00)） | 中；口令转发=课表公开 |
| 校园官方小程序 | 各校"微教务" | 绑定学号 → 课表/成绩/空教室 | 学校自建，走统一身份认证 | 低；仅本校 |
| Chrome 扩展 | zf-to-ics | 正方 V9 课表 → ICS；可发 GitHub Gist 做**日历订阅 URL** | 运行时探测 `apiBase`，适配不同学校根路径；v1.1.0（2026-10-13）；声明不收集数据（[仓库](https://github.com/zaochih/zf-to-ics)、[商店](https://chromewebstore.google.com/detail/x/chnhhdhbciemnjaohomeohlklfdlmhbl)） | 低；订阅 URL 泄露=课表公开 |
| Web 转换器 | wmukebiao | 正方"教学进度表"导出的 **`.zip`（多文件合并）+ `.xlsx` 考试表** → 上传 → ICS | Render 后端；按课程分批导出规避正方导出 bug（[仓库](https://github.com/pkx07/wmukebiao)、[站点](https://kebiao.ipkx.tech/)） | 中；上传教务导出文件到第三方 |
| 油猴脚本 | ZFClassTable2ICS / Curriculum-to-iCalendar | 在已登录课表页点"保存 ICS" | 需改 `@include` 与 `TIME` 作息表；MIT（[ZFClassTable2ICS](https://github.com/Feldan/ZFClassTable2ICS)、[Curriculum-to-iCalendar](https://github.com/chaiqingao/Curriculum-to-iCalendar)） | 低；脚本随教务改版失效 |
| 口令→ICS | WakeUp2ICS | 口令 → ICS / JSON API | 需 APK + AndroidID；FastAPI；Apache-2.0（[仓库](https://github.com/airline233/WakeUp2ICS)） | 高（反作弊）；适合自建，不适合产品化 |

**共性**：这一层全部是"**用户自己动手**"的渠道，价值是验证需求真实存在（教务导出格式千奇百怪、ICS 是最大公约数），但不能作为产品主路径——因为要求用户装扩展/改脚本/传文件到第三方。

---

## 3. 通用渠道类型归纳

| # | 渠道 | 本质 | 适用场景 | 主要失败模式 | 维护成本【估算】 | 隐私/合规 |
|---|---|---|---|---|---|---|
| 1 | 账号直连 | 客户端/服务端重放登录 | 学校在列表、无验证码/弱 SSO | 验证码、加密口令、SSO 跳转、WAF 封 IP | 每校 1–5 人日 + 年检 | 最高：学号+密码；必须不落盘、最小必要 |
| 2 | 通用协议 + 自定义 URL | URL/页面指纹 → 选解析器 | 长尾学校、名单过期 | 指纹误判、页面改版 | 6 类 × 2–4 人日 | 中：仍要登录 |
| 3 | WebView 登录注入 | 在已登录会话里取数 | 前端渲染重、接口加密 | 页面结构变、iframe、脚本无宿主库 | 每校 0.5–2 人日 | 中高：脚本可读 Cookie，需白名单/签名 |
| 4 | 文件导入 | 离线样本 → 解析器 | 教务不可达/封 IP/用户不愿给密码 | 编码、合并单元格、模板不合规、多课一格 | CSV/HTML/ICS 3–8 人日；xlsx +2–3 | 最低：文件不出设备 |
| 5 | 粘贴文本 | 剪贴板 → 格式嗅探 | 跨 App 迁移、同学互传 | 格式混杂、全角/半角、周次写法 | 2–4 人日 | 低；但剪贴板可能含敏感信息 |
| 6 | AI 截图 OCR | 图片/网页/文件 → 多模态 → 结构化 | 学校未适配、临时课表、迁移 | 幻觉、周次/单双周错、图片模糊 | 3–10 人日 + 推理成本 | **高**：图片含姓名学号；需明示上传与留存 |
| 7 | 分享码/二维码 | 短码或二维码 → 服务端/本地还原 | 同学互传、跨 App 迁移 | 过期、反作弊、码被枚举/转发 | 星链 0.5–1 人日；WakeUp 5–15 人日+对抗 | 高：整张课表=行踪；公开 API 可被爬 |
| 8 | 日历订阅/导出 | 课表 → ICS/CalDAV | 给系统日历看、跨端 | 教学周无法表达、调课/停课丢失、时区 | 2–4 人日 | 中：Gist/订阅 URL 公开即泄露 |
| 9 | 手动录入 | 人填 | 永远的最后兜底 | 重复、冲突、周次错 | 1–2 人日 | 无 |
| 10 | 浏览器插件 | 复用浏览器登录态 → 导出 | 电脑端、一次性转换 | 教务改版、@include 不匹配 | 每校 0.5–1 人日 | 低；但脚本权限大 |

### 3.1 渠道工程属性（决定能不能做"自动同步"）

| 渠道 | 需登录 | 需前台 WebView | 可后台/定时 | 依赖第三方服务端 | 结果确定性 |
|---|---|---|---|---|---|
| 账号直连（原生重放） | ✅ | ❌ | ✅ | ❌ | 高（字段写死，改版即失效） |
| 通用协议 + 自定义 URL | ✅ | ❌/✅ | ✅（原生）/❌（脚本） | ❌ | 中 |
| WebView 登录注入 | ✅ | ✅ | ❌ | ❌ | 中高（脚本可热修） |
| 文件导入 | ❌ | ❌ | ❌（需用户选文件） | ❌ | 高 |
| 粘贴文本 | ❌ | ❌ | ❌ | ❌ | 高（格式嗅探） |
| AI OCR | ❌ | ❌ | ❌ | ✅（多模态 API） | 低（需预览校对） |
| 分享码（星链） | ❌ | ❌ | ✅（拿到码即可） | ✅（`api.starlinkkb.cn`） | 高 |
| 分享码（WakeUp） | ❌ | ❌ | ✅ | ✅（`api.wakeup.fun` + 反作弊） | 中（易被拦） |
| 日历订阅/导出 | ❌ | ❌ | ✅（订阅端拉取） | 可能（Gist/CalDAV） | 中（丢教学周语义） |
| 手动录入 | ❌ | ❌ | ❌ | ❌ | 高 |

**选择规则（一句话版）**：要"后台自动更新自己的课表"→ 只能选 1/2（分享码虽可后台拉取，但拿不到自己的码）；要"长尾覆盖"→ 3 + 4/5 兜底；要"零登录、低隐私风险"→ 4/5；要"接住竞品用户"→ 7；要"给系统日历看"→ 8。**AI 与分享码都不适合做后台同步的主路径**。

**四种可复用的渠道组合范式**

- **A 五级漏斗（WakeUp）**：学校专用 → 通用类型 → 可改 URL WebView → 文件（HTML/CSV/备份）→ 手动/口令。优点：任何一步失败都有下一步；缺点：每一步都要用户理解"教务类型"。
- **B 端上脚本仓库（拾光）**：索引 + YAML + JS + 桥 + 版本号；通用教务与跨 App 分享码都做成 `GENERAL_TOOL`。优点：热更新、社区产能；缺点：脚本安全与协议兼容。
- **C 本地优先（Sleepy）**：协议识别 + 多格式 + 强制预览 + 五选一冲突处理 + 目录状态机。优点：隐私最好、失败可解释；缺点：没有"一键成功"的爽感。
- **D AI 兜底（星链）**：直连 + AI 截图/网页/文件 + 分享码。优点：长尾覆盖最快；缺点：非确定性、成本、隐私。

---

## 4. 对「纸间课表」的取舍建议

前提：现有 `JwAdapter`/`JwAdapterRegistry` + WebView Cookie 共享 + `JwSessionRecovery` + Excel/CSV/JSON/ICS/WakeUp 口令/OCR/二维码，已经覆盖"直连 + 文件 + 手动"。短板是**渠道编排**与**失败可解释性**，不是缺一个新解析器。

### 4.1 P0（先做，全部设备端、零服务端）

1. **统一 `ImportSource` 抽象 + 强制预览**：所有渠道产出 `CourseDraft[] + timeSlots[] + semesterConfig + sourceMeta`，进同一个 `ImportPreview`；预览必须给 5 选 1（替换/新表/仅追加不冲突/合并为新表/连冲突追加），并保留"导入草稿"。直接照 Sleepy 的交互，成本最低、用户感知最强。
2. **粘贴文本做成第一等入口**：一次粘贴，按顺序嗅探 `【来自WakeUp课程表】` 分享文本 → JSON（拾光/星链/Sleepy）→ CSV/TSV → 纯文本（一行一课、Tab 分隔）→ ICS。理由：跨 App 迁移与同学互传的真实需求都在这里，且不碰账号。
3. **补齐文件格式矩阵**：`.csv`（模板 + 宽松表头）、`.html`（`<table>` + `div.kbcontent` 两种）、`.xlsx`（WakeUp 模板列）、`.ics`、`.json`（拾光/Sleepy/WakeUp）、`.zip`（正方教学进度表；可后置）。每个解析器都要有 fixture 与"识别失败→告诉用户哪一列不对"的错误文案。
4. **通用协议选择 + 可改 URL**：`supports()` 全不中时不要静默回落正方；给 6 类协议菜单（新 URP/URP/正方新/正方老/强智/旧强智）+ 页脚厂商识别指引 + 顶栏地址栏可编辑（WakeUp 已验证）。这是零新依赖提升成功率的一步。
5. **错误分类**：把现有 3 类错误扩成 `NeedsLogin / Captcha / WrongPage / ParseFailed / Network / Unsupported`，每类给一个可执行动作（重登/换协议/改 URL/导文件/手动）。

### 4.2 P1（低成本高杠杆）

6. **星链分享码导入（做）**：`GET https://api.starlinkkb.cn/share/curriculum/{code}`，解析 `data.courses[]`/`startDate`/`totalWeeks`/`sectionMinutes`；预览后入库。工作量约 0.5–1 人日。文案写"导入同学分享的星链课表"，不要承诺"星链全部学校"。
7. **WakeUp 备份文件 + 旧版离线口令（保留）**：`.wakeup_schedule` 与 base64/zlib 旧口令继续支持；**不做现行短码**（v6.1.70 协议钉死、antispam、条款风险），UI 不把"WakeUp 口令"当承诺。
8. **ICS 只做导出，不做导入主格式**：导出给系统日历；若做订阅，明确"公开链接=课表公开"，并提供"停止订阅/删除"。
9. **学校目录状态机**：每条学校标 `已验证 / 待验证 / 待适配` + `来源` + `最后验证日期`；"收录 ≠ 支持"。可直接采用 Sleepy 的口径，避免再出现 1200/1800/3570 的口径混乱。
10. **适配申请 + HTML 回传**：做一个"导入失败→上传当前页面 HTML（可选）"的入口，走本地脱敏（去 Cookie/学号）后由维护者补适配；这是 WakeUp `apply_html` 的低成本版。

### 4.3 P2（评估后再做）

11. **AI OCR**：如果做，必须"用户点一次、明确告知上传内容、可撤回"，并默认**不**把截图上传到自有服务；可先做"复制识图提示词"（Sleepy 模式），零成本验证需求。
12. **脚本层**：若确要覆盖长尾，走"WebView 注入 + 桥 + 远端仓库"，但至少加：脚本哈希白名单、`apiVersion` 兼容、来源明示、执行前确认、失败回退文件导入。优先兼容小爱三函数形态（生态存量最大）。
13. **浏览器插件/桌面端**：不是移动端主路径；若做，只做"导出 ICS/JSON"给高级用户，不做登录代理。

### 4.4 明确不做

- **不做 WakeUp 现行口令逆向**：版本钉死 + 反作弊 + 厂商明确阻断第三方，收益（一批迁移用户）远小于维护与条款风险。
- **不做服务端解析**：小爱已证明"平台一关，适配与数据一起归零"；且课表原文出设备违反最小必要。
- **不做"无预览的自动覆盖"**：一次解析抖动就能清空课表；自动同步必须"默认不删、超阈值转人工"。
- **不把分享码当唯一/主入口**：它解决"同学互传"，不解决"第一次建表"。
- **不把 AI 识别结果直接入库**：必须预览 + 置信度/警告 + 可编辑。

### 4.5 数据与隐私底线（渠道无关）

- 学号/密码：只在 WebView 会话内使用，不落盘、不进日志、不上传；提供"退出即清 Cookie"。
- 导入原文（HTML/JSON/ICS/截图）：默认只存本地，保留 7 天用于排错，可一键清除。
- 分享码：解析结果只存本地；UI 提示"分享码=整张课表，转发前确认"。
- AI：单独开关 + 每次确认 + 明示"会上传图片/文本"；不做默认开启。
- 合规：教育类 App 备案、PIPL 最小必要、学校用户协议与访问频率；学校列表注明来源与"未验证"状态。

### 4.6 验收口径（建议写进排期）

| 渠道 | 验收标准 |
|---|---|
| 粘贴文本 | 5 种输入各 3 个样本，识别率 ≥95%，失败有明确原因 |
| 文件 | CSV/HTML/JSON/ICS 各 5 个真实样本；错误列定位到表头 |
| 通用协议 | 6 类各 1 校真实登录；错页面能提示"切到课表页" |
| 星链分享码 | 3 个真实码；失效码提示；时间表正确落库 |
| 预览 | 5 种冲突处理各 1 个用例；草稿可恢复 |
| 隐私 | 抓包确认无账号/课表外传（AI 关闭时） |

---

## 5. 证据、口径与未证实清单

### 5.1 本次新增的一手证据

- **WakeUp**：导入弹窗 5 入口（[import_schedule](https://www.wakeup.fun/doc/import_schedule.html)）；CSV 模板与规则（[import_from_csv](https://www.wakeup.fun/doc/import_from_csv.html)）；HTML 导入前提与强智多模式（[import_from_html](https://www.wakeup.fun/doc/import_from_html.html)）；教务导入漏斗（[import_from_eas](https://www.wakeup.fun/doc/import_from_eas.html)）；口令协议 v6.1.70（[拾光 wake_up.js](https://github.com/ShiGuangSchedule/shiguang_warehouse/blob/main/resources/GLOBAL_TOOLS/wake_up.js)）；APK/AndroidID 与反作弊（[WakeUp2ICS](https://github.com/airline233/WakeUp2ICS)）；6.1.06 APK 3,570 条（[Sleepy 目录说明](https://github.com/lingion/sleepy/blob/main/docs/catalog/README.md)）。
- **星链**：4 条导入路径与 11 类教务（[官网](https://www.starlinkkb.cn/)）；分享码 API（[拾光 starlink.js](https://github.com/ShiGuangSchedule/shiguang_warehouse/blob/main/resources/GLOBAL_TOOLS/starlink.js)）；JSON 导入与时间表单独导出（[Koishi 插件说明](https://forum.koishi.xyz/t/topic/12565)）；隐私政策（[privacy.starlinkkb.cn](https://privacy.starlinkkb.cn/)）；权限列表（[小米商店](https://r.app.xiaomi.com/details?id=com.xlhzcm.starcurriculum)）。
- **Sleepy**：三条导入路径、7 种文本格式、5 种冲突处理、协议族清单、目录状态机（[README](https://github.com/lingion/sleepy)、[catalog README](https://github.com/lingion/sleepy/blob/main/docs/catalog/README.md)、[schools-list](https://github.com/lingion/sleepy/blob/main/docs/schools-list.md)、[PROTOCOL_MAPPING](https://github.com/lingion/sleepy/blob/main/docs/catalog/PROTOCOL_MAPPING.md)）。
- **拾光**：`GLOBAL_TOOLS` 4 条 adapter（[adapters.yaml](https://github.com/ShiGuangSchedule/shiguang_warehouse/blob/main/resources/GLOBAL_TOOLS/adapters.yaml)）；4 个通用教务目录仍在（[zhengfang](https://github.com/ShiGuangSchedule/shiguang_warehouse/blob/main/resources/zhengfang_jiaowu/adapters.yaml)）；264 校/269 adapter 为本次 tree 统计。
- **超级课程表**：3618 所与 2026-06 OPPO 合作（[百科](https://baike.baidu.com/item/超级课程表-表表/16853910)）；扫码=班委/同学课表二维码（[应用说明](https://www.mrpyx.cn/soft/25862.html)）；2020 通报（同上）。
- **小爱**：下线与 vConsole 残留（[考据](https://blog.deepchirp.com/2025/09/07/Restore-the-Academic-Import-Feature-in-XiaoAi-Schedule)）；1928 所与分享链接（[商店文案](https://m.9upk.com/ruanjian/22355.html)）。
- **ClassIsland / 下节啥课**：拖入表格/CSES（[文档](https://docs.classisland.tech/app/profile/)）；Issue #1279 not planned；7 教务 + WebView（[README](https://github.com/baoozak/timetable)）。
- **生态**：zf-to-ics（[仓库](https://github.com/zaochih/zf-to-ics)）；wmukebiao（[仓库](https://github.com/pkx07/wmukebiao)）；ZFClassTable2ICS（[仓库](https://github.com/Feldan/ZFClassTable2ICS)）；无广课程表（[教程](https://github.com/pocketchen18/ikun_web/blob/main/src/content/posts/tutorial.md)）；狐友（[教程](http://mp.weixin.qq.com/s?__biz=Mzk1NzIwNTQwNw==&mid=2247484803&idx=1&sn=f48244a0ea39b96b463cac6f5f185d00)）。

### 5.2 口径冲突 / 未证实

1. **WakeUp 覆盖**：1800+（官网）/1200+（文档）/3570（6.1.06 APK 资源）三者不互斥但常被混用；"可导入"与"列表里有"没有官方定义。
2. **WakeUp 口令**：拾光实现声称支持新版 v6.1.70；Yunzai 作者称 ≥6.1.x 无法导出、6.0.23 可用；Koishi 称 v1 无鉴权、v2 有鉴权。三者时间点不同，**"哪个版本还能用"未证实**。
3. **星链分享码有效期/频控/是否可枚举**：官方未说明；"5–20 位字符集"来自第三方正则，**未证实**。
4. **星链文件导入格式**：官方只说"课表文件交给 AI"；第三方称 Excel 另存 CSV UTF-8。**未证实**。
5. **超级课程表 CSV/Excel 模板**：仅 SEO/聚合站教程，文本高度雷同，疑 AI 生成；无官方模板下载。**未证实**。
6. **超级课程表扫码**：一说"扫教务页二维码授权"（多篇 SEO），一说"扫同学/班委课表二维码复制"（应用说明）。后者更可信，前者**未证实**。
7. **小爱分享链接**：仅一处商店文案描述"一人编辑全班分享"，**未证实**；1928 所也是商店文案。
8. **拾光 ICS 导入**：10-19 文档称支持，本次未在 README 逐条复核；按"既有调研"引用，标**待复核**。
9. **下节啥课**是否有隐藏的文件/分享入口：README 只列 WebView，**未证实无**。
10. **AI 是否上云/留存**：星链、超级课程表、小爱均未在公开文档说明；按行业惯例推测为云端，**未证实**。

### 5.3 与 10-19 文档的差异（避免重复读）

- 新增：星链、Sleepy、拾光 `GLOBAL_TOOLS`、超级课程表扫码语义、小程序/插件/转换器。
- 修正：拾光除 4 个通用教务外，`GLOBAL_TOOLS` 已变成"跨 App 分享码导入 + 测试位"；WakeUp 新版口令并非"只能查服务端、第三方无解"，拾光已重实现但版本钉死；WakeUp APK 学校记录 3,570 条，可作为"名单真实规模"的上界。
- 保留：小爱三文件/云端 parser、下节啥课 `document.title` 反模式、ClassIsland 许可风险、ICS 只适合导出——这些结论本次复核仍成立。
