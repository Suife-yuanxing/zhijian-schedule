# 编辑课程入口的圆形扩散改造（探索后拍板 2+1）设计规格

日期：2026-11-07 ｜ 状态：已拍板，随真机验收 ｜ 归属：feature/schedule + feature/today + navigation

## 背景与拍板

探索报告（代码链路 + 10x 慢放逐帧 + 业界对照）确认了「编辑」入口的现状：
详情面板内容收（130ms）→ **面板窗口连遮罩一帧瞬消、课表裸露一帧**（断点帧）→ 默认横滑进编辑页，
返回也是默认横滑。与三个添加入口「从被点控件长出来」的扩散语言不一致——根因是
`AppNavGraph` 里编辑分支写死了 `seedOrigin = null`（注释：「没有『被点的那个方块』」）。

用户拍板「2 + 1 一起做」：

| # | 改动 | 手段 |
| --- | --- | --- |
| 2 | 编辑跳转接入圆形扩散 | **课块 seed**：点开详情面板那一刻定格课块轮廓（root 坐标 + 课块圆角），编辑导航时喂给 `RevealState.requestNavigation` |
| 1 | 消灭断点帧 | reveal 盖屏与面板窗口移除落在**同一帧快照**：`sheets.course = null` 与 `requestNavigation` 在同一次处理器里写状态，下一帧主窗口直接画出「冻结旧帧 + 扩散起点」，面板窗口在组合阶段已移除 |

## 决策表

- **seed 采集点**：课块/课卡的可点击节点 `onGloballyPositioned` 记 `boundsInRoot()`（复用
  `WeekGridBands` 空课块的同一模式），圆角取 `preset.radius.blockDp`（课表课块）/ 12dp（今日页课卡）。
  点击时随回调上抛，存进 `ScheduleSheets.courseSeed` / TodayScreen 的 `editSeed`。
- **退化路径**：seed 为 null 或 bounds 为 0（理论上仅首帧）→ `seedOrigin = null` 直接导航走默认横滑，
  与添加入口的退化一致。
- **已知取舍一（亮度跳变）**：面板遮罩属于独立窗口，reveal 照片拍不到它；窗口移除那一帧遮罩亮
  度瞬时 lift。关注点此时已被「从课格长出的圆形」接管，且现状（裸帧）比它差得多——接受。
- **已知取舍二（改课时间后的返回）**：编辑把课改到别天/别节后，反向收回仍指向**进入时**的课格
  （可能是空格）。返回的语义是「回到来的地方」，不追踪课的新位置。
- **busy 退化**：`requestNavigation` 返回 false（扩散进行中）→ 直接导航，与添加入口一致。
- **范围**：课表 tab 与今日 tab 一并纳入——同一个「编辑」动作必须是一种语言。今日 tab 的
  路由（TODAY / TODAY_EDITOR）补上与 SCHEDULE 侧对称的 reveal 转场闸门
  （`reveal.navigating` 时 enter/exit/popExit 让位）。
- **返回路径**：零新增——编辑导航设置 `seedOrigin` 后，SCHEDULE_EDITOR 既有的 `leave`
  （`requestNavigationBack` → 反向收回进课块）自动生效。今日编辑页的 `onDone = popBackStack`
  升级为同款 leave。
- **不加 SeedBadge**：课块是冻结旧帧里的真实内容，不需要重画副本（与空课块路径一致）。

## 实施清单

- `WeekCourseOverlay.kt`：`onCourseClick` 增加 `SeedShape` 参数；`CourseCell` 捕获自身
  `boundsInRoot` 并构造种子（`preset.radius.blockDp` 圆角）
- `WeekGrid.kt`：签名透传
- `ScheduleScreen.kt`：`ScheduleSheets.courseSeed`；点开面板时记录；`onEdit` 摘面板 + 带 seed 上抛
- `TodayCourseRow.kt` / `TodayScreen.kt`：同款 seed 采集与上抛（12dp 圆角）
- `AppNavGraph.kt`：抽 `navigateEditor(route, seed)`（usable 判定 + requestNavigation + 退化）；
  两个 tab 的 `onEditCourse` 改走它；TODAY / TODAY_EDITOR 补 reveal 转场闸门与反向 leave
- 本文件 + docs/README.md 索引行

## 二轮：面板整体退场融合（用户反馈，2026-11-07 14:43）

一轮验收后用户指出：内容收完的那一帧，**面板壳本身**（白底圆角容器 + 遮罩）仍是瞬消，
被 reveal 的冻结照片顶掉——壳的退场没有参与编舞。拍板：整个卡片的退场融合进整体逻辑。

- 点「编辑」→ `onEdit` 先行（导航 + 圆形扩散从课块长出，与一轮相同）；
  面板随即走 **M3 原生 hide**：整壳滑下 + 遮罩同步淡出，与圆形生长**并行**在同一条时间线；
  `hide()` 播完才回调 `onDismiss` 让宿主摘组合。
- 旧「内容收 130ms 再换屏」（SheetExitGate）对编辑**停用**，仅删除路径保留——
  hide 是整壳的退场，再叠内容收就是两段动作。
- 双击防护：`editLeaving` 只认第一次提交；hide 途中删除/编辑不再排队。
- 无动画（系统关动画）时 `hide()` 立即完成 → 等价直摘，无需特判。

## 验收

- 编译 + 全量单测 + ktlint/detekt（触碰文件；门禁在 HEAD 已红，只查自己）
- 真机 10x 慢放逐帧：
  - 正向：内容收 → **圆形从被点课格长出**（第 0 帧边界 = 课格轮廓）→ 无课表裸露帧
  - 反向：编辑页按圆收回进课格，露出课表
  - 今日页同流程各验一遍
