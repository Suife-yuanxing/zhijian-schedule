# 考试安排与成绩统计（学业中心一期）设计

- 日期：2026-10-24
- 状态：已与产品（用户）逐节确认，待转实施计划
- 上一轮背景：六个迭代方向盘点（方向②「学业中心」入选），复用面核查基于 v1.0.11 代码
- 结论性质：设计文档（本文不改代码）

---

## 0. 决策记录（已拍板）

| 决策点 | 结论 | 备选与理由 |
| --- | --- | --- |
| 考试数据来源 | **手动添加 + 强智教务抓取** | 复用成绩抓取的同源会话模式，东莞职院（强智）可真机验证；正方等系统提示"暂不支持，请手动添加"；文件/二维码导入考试不做 |
| 提醒与展示渠道 | **考前闹钟通知 + 今日页倒计时卡 + 胶囊/流体云联动** | 桌面小组件明确后置到下期 |
| 成绩增强边界 | **基础统计**（最新学期加权 GPA + 已修学分 + 按学期趋势） | 全量增强（筛选/分布/进度条）拆到下期 |
| 信息架构 | **方案 A：「我的」页入口，不动底部导航** | 方案 B（底部「学业」Tab）改动大且低频占坑；方案 C（嵌入周视图）破坏节次网格模型。A 后续可无痛升级为 B |
| 考试与胶囊的关系 | **伪课程方案**：考试当天映射为伪课程参与现有状态机 | 不新增 CapsulePlan 状态类型，全部实时行为（进度条/自愈闹钟）复用 |

## 1. 数据层与迁移（Room v2 → v3）

- `ExamEntity` 新增列 `manuallyEdited: Boolean = false`：手动创建/编辑过的考试在教务同步时**永不覆盖**，语义与课程侧 `CourseEntity.manuallyEdited` 一致。
- 迁移：`ALTER TABLE exam ADD COLUMN manuallyEdited INTEGER NOT NULL DEFAULT 0`（`Migrations.kt` 补 `MIGRATION_2_3`，DB version 2→3）。
- `ExamDao` 补 `getByTable(tableId): List<ExamEntity>`（现有只有 `observeByTable`，合并需要一次性快照）。
- `ScheduleRepository` 新增：
  - `saveExam(exam, id?)`：手动保存时 `manuallyEdited = true`；
  - `deleteExam(id)`；
  - `applyExamMerge(fetched: List<ExamDraft>)`：见 §2 合并策略；
  - `activeExams` Flow 已存在（`ScheduleRepository.kt:63`），无需改。

## 2. 教务抓取（强智，照成绩抓取模式）

- 新增 `core/jw/ExamFetcher.kt` + `ExamParser.kt`，结构对照 `GradeFetcher`/`GradeParser`：
  - 与 WebView 共享 Cookie 的 OkHttp HTTP 重放；
  - 会话过期抛 `JwException.NeedsLogin`，先走 `JwSessionRecovery.trySilentRecover`（仅强智 SSO 链）静默恢复一次，失败再抛给 UI；
  - Parser 只吃 HTML 字符串 → `List<ExamDraft>`，与网络解耦、可 fixture 单测。
- 端点：强智「考试安排」页（`ksap` 一类路径）。**具体 URL 与页面结构以真机抓包为准**；实施顺序：先在 `tools/mock-jw` 加一个考试安排页跑通 WebView → 抓取 → 合并 E2E，再真机验证东莞职院。
- **合并策略**（键 = `课程名 + 开考时间(epoch millis)`）：
  - 教务新增 → 插入（`manuallyEdited = false`）；
  - 教务侧考场/座位/备注变化 → 更新**非手动**项；手动项跳过并计入摘要（如「1 场手动考试保留原样」）；
  - 教务侧消失 → 仅删除非手动项；
  - 完成后弹摘要通知（如「已更新 3 场考试安排」），不做预览页（考试数据量小，摘要足够）。
- 可用性判定：仅当 `JwAdapterRegistry` 当前教务为强智（`QiangZhiAdapter`）时展示「从教务导入」入口；其余系统 UI 提示「暂不支持，请手动添加」。

## 3. 提醒链路（独立小链路，不碰已验证的课程链路）

- 新增 `alarm/ExamAlarmScheduler.kt`：
  - `requestCode = EXAM_RC_OFFSET + examId.toInt()`，`EXAM_RC_OFFSET = 1_000_000`，与课程 id 的 PendingIntent 空间隔离；
  - 照抄 `AlarmScheduler` 模式：`setExactAndAllowWhileIdle`，无精确闹钟权限时降级 `setAndAllowWhileIdle`。
- 每场考试固定两档提醒：**开考前 24 小时** 与 **开考前 1 小时**（本期不做设置项）。
- 新增 `ExamReminderReceiver` + 新通知渠道「考试提醒」（对齐技术方案 §4.7 的渠道拆分规划）。
- 新增 `ExamReminderCoordinator`（照 `ReminderCoordinator` 模式）：observe `activeExams` → 全量重排；考试删除/过期后自动取消。不并入现有 coordinator。
- 开考时刻已过的考试不排提醒；设备重启恢复复用现有 `BootReceiver` 挂载点。

## 4. 胶囊联动（伪课程方案）

- `buildCapsulePlan(...)` 增加参数 `exams: List<ExamEntity> = emptyList()`（默认值保证现有 `CapsulePlanTest` 与调用方不破坏）。
- **仅考试当天**的考试参与映射，规则：
  - `name = "${考试名} · 考试"`；`room = 考场 + " · " + 座位号 + "号座"`（座位为空则只显示考场）；
  - `start = 开考时间`，`end = 开考 + 120 分钟`（**默认时长，本期已知限制**：`ExamEntity` 无时长字段，不在本期加）；
  - 伪课程插入当日课程列表**头部**：与真实课程同 slot 时考试优先（考试当天通常停课）。
- 白拿的行为：AboutToStart 倒数、Ongoing 进度条与切换闹钟/心跳自愈、`reminderMilestones(120)` 的「剩 40 分 / 剩 5 分」节点——后者恰好构成**交卷倒计时**能力。
- `DayDone` / `Holiday` 的 `nextText` 从「未来 7 天最近一节课」扩展为「课程 ∪ 考试 最近一件」，可显示「明天 14:00 · 高等数学考试」。

## 5. 考试列表页与编辑（`feature/exam` 新包）

- `ExamScreen` + `ExamViewModel`，二级页（复用 `SecondaryTopBar`），从「我的」进入。
- 列表按开考时间升序，分「即将到来 / 已结束」两组；已结束灰显、折叠为计数行。每行：考试名、`11-02 周一 14:00`、`考场 · 座位号`、右侧倒计时徽标（`3 天` / `明天` / `3 小时`）。
- FAB「＋」→ 编辑表单：名称（必填）、日期、时间、考场、座位号、备注；手动保存即 `manuallyEdited = true`。
- 「从教务导入」按钮：仅强智会话可用时展示；`NeedsLogin` 失败时引导跳教务导入页重新登录。
- 行点击进编辑；行内菜单删除（同时取消对应闹钟）。
- 空态：引导手动添加或教务抓取。

## 6. 我的页入口与导航

- `ProfileScreen` 在「成绩」行旁新增「考试安排」行，副文案预览最近一场（如「高等数学 · 还有 3 天」，无考试则「点击添加」）。
- `AppRoot` 新增 exam 二级路由，返回回「我的」。

## 7. 今日页倒计时卡

- 最近一场 **14 天内**的考试 → 在「正在上课 / 下一节」区域之后插卡：
  - 主行：`距「高等数学」还有 3 天`（当天显示小时粒度）；
  - 副行：`11-02 周一 14:00 · 教305 · 12号座`；
  - 点击进入考试页。
- 超过 14 天不显示；考试当天由胶囊接管后此卡仍显示（信息一致，不冲突）。

## 8. 成绩统计（`GradesScreen` 顶部，纯本地计算）

- 新增 `core/jw/GradeStats.kt` 纯函数：
  - **最新学期加权 GPA** = Σ(credit × gpa) ÷ Σ(credit)，仅统计 `gpa != null` 的行；`retakeTerm` 非空（补考/重修记录）的行排除；
  - **已修学分** = 同口径 Σ credit（即只计有绩点且非补考重修的行）；
  - 按学期分组输出（学期名、GPA、学分），供趋势图使用。
- UI：顶部汇总卡（最新学期 GPA + 已修学分）+ 按学期 GPA 横向条形图（Compose Canvas 自绘，不引第三方库）。
- 等级制成绩（「优/良」等 gpa 为空）不计入 GPA 与学分口径，UI 注明「按绩点课程统计」。

## 9. 测试与验收

**单元测试（新增）**

- `ExamParserTest`：fixture 三类——正常表格、空表、未登录页；
- `ExamMergeTest`：新增 / 变更 / 删除保护手动项 / 手动项变更跳过并计数；
- `CapsulePlanTest` 增补：考试当天伪课程态、与课程同 slot 冲突排序、`nextText` 含考试；
- `ExamAlarmSchedulerTest`：T-24h / T-1h 两档时刻计算、已过期不排、requestCode 隔离；
- `GradeStatsTest`：加权计算、等级制排除、补考行排除、空数据。

**E2E（mock-jw）**

- `tools/mock-jw` 增加考试安排页，跑通 WebView 登录 → 抓取 → 合并 → 摘要。

**真机验收清单**

1. 手动创建一场 25 小时后的考试 → 1 小时档闹钟准点送达（24h 档用 mock 时间或日志核验时刻计算）；
2. 考试当天：胶囊显示「高等数学 · 考试」态，考试中「剩 40 分」节点出现；
3. 今日页卡片在 14 天边界正确出现/消失；
4. 教务抓取后列表与 mock 页面/教务页面人工核对一致；手动添加的考试在再次抓取后原样保留；
5. 成绩统计卡数字与教务成绩页人工核对一致；
6. 课程提醒链路回归：考试功能加入后原有课前提醒不受影响（requestCode 隔离验证）。

## 10. 工作量（合计约 7-8 人日）

| 块 | 人日 |
| --- | --- |
| 数据层 + Room 迁移 | 0.5 |
| 抓取 + Parser + mock-jw 考试页 | 1.5 |
| 提醒链路（Scheduler/Receiver/Coordinator/渠道） | 1 |
| 胶囊伪课程 | 0.5 |
| 考试页 UI（列表/编辑/入口/路由） | 1.5 |
| 今日页倒计时卡 | 0.5 |
| 成绩统计 | 1 |
| 测试补齐 + 真机验证 | 1 |

## 11. 风险与开放问题

| 项 | 说明 | 缓解 |
| --- | --- | --- |
| 强智考试安排页结构因校而异 | 端点与字段以真机抓包为准 | Parser/网络分离 + fixture 单测；解析失败给出「手动添加」引导 |
| 考试时长固定 120 分钟 | 无时长字段，胶囊结束时刻与实际可能有偏差 | 已知限制写入版本说明；如需精确，下期迁移加 `durationMinutes` |
| 补考/重修行口径 | `retakeTerm` 判定可能与个别学校教务导出不符 | 排除策略保守处理并在 UI 注明口径 |
| Room v3 迁移 | 升级路径必须无损 | 迁移单测（Room MigrationTestHelper 或手工建 v2 库验证） |

## 12. 本期明确不做（Out of Scope）

- 考试桌面小组件（下期候选，复用 `NextCourseWidget` 骨架）；
- 正方/URP/青果的考试抓取；
- 考试文件/二维码导入、考试预览页；
- 提醒档位/提前量设置项；
- 成绩全量增强（按学期筛选、课程性质分布、学分进度条）；
- 考试时长字段与编辑。
