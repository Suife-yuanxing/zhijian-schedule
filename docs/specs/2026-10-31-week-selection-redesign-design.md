# 周次选择「一体周历」重做设计

- 日期：2026-10-31
- 状态：已评审通过（方案 A）
- 调研依据：Material 3 选型规则（segmented button / chip / picker）、Apple HIG 就地日期选择、周选择器行业模式（整周行高亮、周数轨、月历跳周）、WakeUp 系生态惯例（每周/单周/双周快捷档）

## 背景与问题

App 内三处「周次」界面各说各话：

1. **设置 · 课表信息弹窗**：第 1 周周一靠手打 `yyyy-MM-dd`，无校验反馈（仅保存时报错）；总周数档位选完不知道「学期到几号」。
2. **课表 · 选择周次面板**（WeekPickerSheet）：格子带日期范围但缺月份锚点，跨月翻页时「第 N 周」缺乏时间感。
3. **编辑课程 · 周次**：纯数字 chip 与另两处配色语言不一致；多选只有逐点 + 快捷档，连选一段周次效率低。

## 目标

- 三处统一为同一套「周格」视觉语法（继承现有 WeekCell 四态：选中实心+弹跳 / 本周描边+浅底 / 过去淡化 / 普通收边）。
- 设置处补上「日期感」：日历选开学日 + 学期时间轴实时预览。
- 编辑课程补上拖动连选与实时周次表达式。

## 方案（用户已选定 A「一体周历」）

### 统一语言（组件层）

- `core/ui/week/WeekCellKit.kt`（新）：共享 `WeekCell` 从 `feature/schedule/WeekCell.kt` 迁入并参数化：
  - 单选导航态（带日期范围副标题）与多选标记态（纯数字紧凑格）共用同一组件、同一状态机。
  - 保留单 transition 四通道（背景/描边/标题/日期色）同步动画与 `pressScale`/`selectedPop` 手感。
- `core/ui/week/SemesterTimeline.kt`（新）：学期时间轴条（设置弹窗用）。

### 三处界面改造

**① 设置 · 课表信息弹窗（TimetableEditDialog）**

- 「第 1 周周一」手打输入框 → 日历按钮字段：显示「2026年8月31日 · 周一」，点开 M3 `DatePickerDialog`（中文本地化），选任意日期自动对齐到所在周周一落库（存储格式仍为 `yyyy-MM-dd`）。
- 「总周数」预设 chip + 自定义输入框 → 分段预设（16/18/20/22）+ 步进器（−/＋，范围 1..30，长按连发）；取消自定义输入框。
- 新增学期时间轴：N 段小格实时预览；已过周淡色、本周实心、右侧标注结束日期；开学日在未来显示「未开学」、已过末周显示「已结课」。
- 课表名称、学期字段不动。

**② 课表 · 选择周次面板（WeekPickerSheet）**

- 相邻两周的周一跨月时插入「N 月」分界细线小标，随行一起 StaggerIn。
- 格子改用迁移后的共享 WeekCell（视觉零变化）。
- 回到本周、点选即收、选中弹跳保留。

**③ 编辑课程 · 周次（CourseEditorScreen）**

- 纯数字 chip → 共享 WeekCell 紧凑多选格。
- 拖动连选：按住划过批量增/删，模式由按下那一刻决定（空格=连加，选中格=连删）；单击逐格切换始终可用。
- 快捷档「全选/单周/双周/清空」保留，升级分段器样式；计数旁新增实时表达式。

### 架构与数据流

- 数据库零改动：`startDate` 仍存 `yyyy-MM-dd`，`totalWeeks` 仍为 Int；`TimetableEditDialog.onSave` 签名不变。
- 新纯逻辑（不碰 Android API，配 JVM 单测）：
  - `SemesterTimelinePlan`：输入 startDate + totalWeeks + today → 每周状态列表、结束日期、进行中/未开学/已结课三态（复用 `TimeUtils.weekOfSemester`）。
  - `WeekMask.describe(mask)`：全奇→「x-y 单周」、全偶→「x-y 双周」、其余走现有 `toExpression`；不改动 `toExpression` 既有行为。
  - `WeekPickerRows`：总周数 + startDate → 分行结构（4 列 + 月分界线插入点），供面板渲染。

### 边界与错误处理

- 日历选择只产出合法日期；`saveTimetable` 现有 `TimeUtils.parseDate` 校验与 1..30 收口继续兜底。
- 总周数改小时：时间轴按 N 截断；课程周次掩码由现有 `WeekMask.normalize` 收口。
- 今天不在学期内：时间轴不给「本周」标记，仅改说明文字。
- 拖动连选是加速器，不破坏按钮语义（无障碍单击逐格）。

### 测试

- JVM 单测：`SemesterTimelinePlanTest`（跨年、未开学、已结课、周中）、`WeekMaskDescribeTest`（单/双/混/空）、`WeekPickerRowsTest`（跨月分界、4 列补位）。
- 全量回归：`tools/run_unit_tests.py`。
- 真机验证：设置弹窗改周数 → 时间轴/课程周次联动；选周面板跨月翻页。

## 明确不做

- 学期时间轴不做点击跳转（避免设置弹窗里藏导航）。
- 不改课表名称/学期字段的交互。
- 不采用 B（日历优先）/ C（轻量步进）方案元素。
