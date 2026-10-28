# 教务导入：真机实测记录（2026-10-27）

真机：OnePlus PKG110 / Android 16 / 一加 OxygenOS，安装 `app-release.apk`（release 签名，覆盖升级保留原有数据）。
网络：移动数据（非校园网、非 WebVPN），因此「站点不可达」一律归因为校内外网隔离，不视为适配器缺陷。

## 测试方式

1. 「我的 → 导入课表 → 教务系统导入」进入导入页；
2. 点地址栏右侧「选校」→ 搜索 → 选中；
3. 读顶部「识别为：…」文案，并观察下方 WebView 是否打开真实教务页。

脚本化操作（`adb shell input tap/text`）与截图见本目录 `shots/jw-real-device/`。

## 结果

| 适配器 | 学校（目录检索词） | 填入地址 | 识别结果 | WebView |
|---|---|---|---|---|
| 强智 jsxsd | 东莞职业技术学院（原有配置） | `https://jw.dgpt.edu.cn/jsxsd/` | 强智 jsxsd ✅ | 强智登录页正常渲染 ✅ |
| 树维 EAMS classic | 电子科技大学成都学院（`dianzi`） | `http://www.cduestc.cn/eams/loginExt.action` | 树维 EAMS ✅ | 树维「教学管理信息系统」登录页 ✅ |
| URP 综合教务 | 河北大学（`hebeidaxue`） | `http://zhjw.hbu.edu.cn/` | URP 综合教务 ✅ | 河北大学 CAS 统一认证页 ✅ |
| 树维 eams5 | 合肥工业大学（`hfut`） | `https://jxglstu.hfut.edu.cn/eams5-student/for-std/course-table` | 树维 eams5 ✅ | `ERR_CONNECTION_TIMED_OUT`（校内外网隔离） |
| 青果 KINGOSOFT | 合肥共达职业技术学院（`hefeigongda`） | `http://220.178.78.157:8082/jwweb/` | 青果 KINGOSOFT ✅ | 青果「教务网络管理系统」首页 ✅ |
| 正方新版 jwglxt | 安徽财贸职业学院（`anhuicaimao`） | `https://jw.afc.edu.cn/jwglxt/xtgl/login_slogin.html` | 正方新版（jwglxt）✅ | 「教学综合信息服务平台」登录页 ✅ |

6 个适配器的**识别链路全部通过**。其中：

- 河北大学只填了根路径 `/`，没有任何 `/academic/` 指纹，靠学校目录的 `adapterId`（85 分）识别成功——这条验证了「目录兜底」的价值，也验证了强智/正方不再抢走 URP 学校的那次修正。
- 电子科技大学成都学院、合肥共达职业技术学院在目录里分别是「已验证」「未实测」，但两者页面实际都可达——说明「未实测」是保守标注，不代表打不开。
- 合肥工业大学是唯一站点不可达的样本，属于校内外网隔离，与识别无关。

### 识别链路证据截图

12 张截图全部落在本目录 `shots/jw-real-device/`，按链路顺序编号；下表说明每张证明的是哪一环，避免后人只看文件名猜。

| 文件 | 证明的点 |
|---|---|
| `01-school-picker.png` | 「选校」弹层可用：按校名 / 拼音检索，条目区分「已验证」与「未实测」 |
| `02-urp-hebei.png` | 河北大学只填根路径即可被识别为 URP 综合教务，WebView 落到学校 CAS 统一认证页 |
| `03-qingguo-hefei.png` | 青果 KINGOSOFT 识别成功，教务首页正常渲染 |
| `04-eams5-hfut.png` | 树维 eams5 识别成功；站点 `ERR_CONNECTION_TIMED_OUT` 属校内外网隔离 |
| `05-zhengfang-afc.png` | 正方新版 jwglxt 识别成功，登录页正常渲染 |
| `06-hint-urp.png` | URP 抓取引导条：第二段显示「个人管理 → 本学期课表」（改造前的通用文案对照） |
| `07-hint-urp-failed.png` | URP 未登录抓取失败提示 + 定制补充说明（下方「抓取失败的定制指引」引用） |
| `08-hint-qiangzhi-failed.png` | 强智未登录抓取失败提示 + 定制补充说明 |
| `09-qiangzhi-fetch-preview.png` | 强智真实登录会话下「抓取 → 解析 → 预览」全链路结果弹层 |
| `10-script-picker-open.png` | 「用脚本导入」弹层初始打开状态（滚动修复前基线） |
| `11-script-picker-middrag-still.png` | 弹层内容区拖拽中途静止帧：内容跟随、弹层本体不位移 |
| `12-script-picker-after-fling-fixed.png` | 修复后连续快速上甩：弹层稳在 y≈145 锚点，不再过顶回弹 |

## 抓取失败的定制指引（同日追加验证）

`JwAdapter.steps` 让每家适配器自带「课表入口 + 注意事项」，引导条的第二段与失败提示都按它渲染：

| 适配器 | 引导条第二段 | 失败时的补充说明 |
|---|---|---|
| 强智 jsxsd | 学生课表 | 左侧菜单里没有「学生课表」时，请让教务开放该菜单权限 |
| 正方新版 | 信息查询 → 学生课表 | 先在页面顶部把学年学期选成当前学期，再点抓取 |
| 青果 KINGOSOFT | 课表查询 → 学生课表 | 青果菜单在页面左侧，展开「课表查询」后选学生课表 |
| URP 综合教务 | 个人管理 → 本学期课表 | 老版 URP 要先选定学期；地址栏出现 `showTimetable.do` 才算进对页 |
| 树维 EAMS | 我的课表 | 课表页地址里带 `courseTableForStd` 才算进对页 |
| 树维 eams5 | 我的课表 | eams5 是单页应用，等课表格子真正画出来再点抓取 |

真机验证两组：

- 河北大学（URP）未登录点抓取 →「提示：请先在上方网页中登录，并进入「个人管理 → 本学期课表」页面后重试。」+「老版 URP 要先选定学期；地址栏出现 showTimetable.do 才算进对页」（`07-hint-urp-failed.png`）
- 东莞职业技术学院（强智）未登录点抓取 →「…并进入「学生课表」页面后重试。」+「左侧菜单里没有「学生课表」时，请让教务开放该菜单权限」（`08-hint-qiangzhi-failed.png`）

未识别教务时退回通用文案「学生课表」，与改造前一致。

## 抓取链路（强智 · 真实登录会话）

东莞职业技术学院（`https://jw.dgpt.edu.cn/jsxsd/`）的 WebView 登录态仍然有效，因此在真机上完整跑通了「抓取课表 → 解析 → 预览」：

- 结果弹层标题「抓取结果 · 无变化」，逐条列出 `包装材料与检测`、`包装职业技能实训A`、`包装插画设计`、`包装装潢设计`、`毛泽东思想和中国特色社会主义理论体系概论` 等课程；
- 底部「取消 / 应用变更」，本次只观察、未点应用，本地课表未改动；
- 「上次成功」时间被刷新，说明走的是成功分支（`FetchResult.Success`），不是兜底文案。

证据：`09-qiangzhi-fetch-preview.png`。

这条证明强智的 `xskb_list.do` → `div.kbcontent` 解析在真实数据上可用；其余五家仍需各自学校账号才能验证抓取，当前只验证到「识别 + 页面可达」。

## 脚本选择弹层滚动修复（同日追加验证）

用户报告：「用脚本导入」弹层向上滑动时页面反复上下弹跳。

**第一轮修复（内部滚动）不彻底**：列表区域套 `weight(1f, fill = false)` + `verticalScroll` 解决了内容超屏时「够不到底部按钮」的问题，但用户实测快速上甩仍会复现弹跳——慢速拖拽验证测不出 fling 路径，这条教训记入下文。

**根因（查实）**：Material3 `ModalBottomSheet` 的已知嵌套滚动缺陷（社区 issue 353304855 家族）：弹层已在 Expanded 锚点时，内部滚动容器吃不掉的**上甩剩余速度（fling velocity）**会交给弹层本体（AnchoredDraggable），spring 动画让弹层越过锚点再回弹，往复形成肉眼可见的上下弹跳。真机证据：上甩后弹层顶边被顶到屏幕最上沿（拖拽把手 y≈100，页面标题被盖住），稳定锚点应在 y≈145。

**第二轮修复（真正生效）**：`ScriptPickerDialog.kt` 增加 `ConsumeUpwardScrollConnection`（`NestedScrollConnection`），挂在弹层内容根上——`onPostScroll` / `onPostFling` 把「向上」（y < 0）的剩余拖动与甩动速度就地消费，弹层本体收不到任何向上增量；「向下」原样放行，下拉关闭手势保留。

真机复验（release 覆盖安装，`10-script-picker-open.png` / `12-script-picker-after-fling-fixed.png`）：

- 连续三次 70ms 快速上甩后立即截图：弹层稳在 y≈145 锚点，过顶消失（修复前同手法必现）；
- 下滑正常关闭弹层（下拉关闭手势未被误伤）；
- 慢速拖拽内容跟随正常，列表滚动不受影响；
- 门禁全绿：ktlint / detekt / testDebugUnitTest（444 例）/ assembleRelease。

## 未覆盖

- **抓取只验证了强智一家**：URP / 树维 classic / 青果 / eams5 / 正方新版需要各自的真实学号口令，本记录对它们只到「识别 + 页面可达」。
- 「应用变更」未点，落库与冲突合并逻辑不在本次实测范围（由单测与 mock 服务覆盖）。
- 研究生教务、乘方、金智、超星、正方旧版未接适配器，不在本次实测范围。
- 页面结构解析的回归仍由单元测试覆盖（`UrpParseTest` / `ShuWeiClassicParseTest` / `Eams5ParseTest` / `QingGuoParseTest`），真机只证明协议识别与地址可达。

## 复现

```bash
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am start -n com.zhijian.schedule/.MainActivity
```

release 与已装版本同签名，可覆盖安装并保留本地课表数据；debug 包因签名不同会 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。
