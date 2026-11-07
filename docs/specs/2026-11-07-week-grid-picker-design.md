# 周次选择重做：5 列全览格阵（方案 A）设计规格

日期：2026-11-07 ｜ 状态：已拍板，随真机验收 ｜ 归属：feature/courseeditor

## 背景与决策

真机截图（2026-11-07 13:41）确认设备上仍是滑块版：无标签圆点滑块 + 连续/单周/双周三大按钮
+ 「逐周挑选」链接 + 提示句 + 结果行。工作区里另有一版未提交的横条重做
（WeekStrip + WeekSelectionSection + WeekSelection 纯逻辑 + 单测）。

本轮以可交互 mockup 对比三方案后由用户拍板：

| 方案 | 形态 | 取舍 |
| --- | --- | --- |
| **A · 全览格阵（选定）** | 5 列 × 4 行，20 周一屏全见 | 全览 + 直接操纵价值 > 约 4 行高度 |
| B · 日期横条 | 横条 + 该周周一日期副行 | 省 2 行，但要横滑才能看全 |
| C · 摘要 + 底部面板 | 编辑页两行摘要，ModalBottomSheet 装格阵 | 页面最轻，高频操作多一跳 |

## 拍板条目

- 布局：头行（周次 + 结果表达式 + 清空）→ 5 列格阵 → 整学期 / 单周 / 双周 tonal chips。
- 手势：点击切换；横向拖过即连选；**轴向判定**保父级 verticalScroll——旧格阵
  「页面滚不动」的根因是无条件 consume，不是格阵本身（横条版已验证的修法平移过来）。
- 视觉通道沿用 WeekCellKit：实心 = 已选、本周 = 底边下划线、占用 = 右下角圆点；
  格阵态启用 PAST 淡化（横条版未启用，全览后「已过去的周」有阅读价值）。
- **二轮细化（真机验收反馈）**：① 未选中格加 1dp 发丝描边（与「星期」chips 同语言，
  过去周描边再淡一档）——否则格阵里 20 个未选格读作悬浮数字，可点范围看不见；
  ② 下划线与选中态解耦，改由 `isCurrent` 参数驱动——旧实现给每个选中格自动画
  onAccent 底线，格阵里十几个实心块就是十条重复白线（条纹噪音）；
  ③ 格间距 6dp → 8dp，实心块之间留呼吸。WeekPickerSheet 同步受益。
- 语义不变：整学期 = 1..N；单/双周在已有范围闭包内取奇偶（< 2 格置灰）；
  结果表达式仍走 WeekMask.describe；CUSTOM 形态保留「逐周组合」角标。
- 逻辑层不动：WeekSelection（mode / bounds / range / parity）与 CourseEditorViewModel 动作原样保留。
- 删除 WeekStrip.kt（唯一引用方是 WeekSelectionSection.kt）。
- 末行不满 5 格补透明占位，周格不拉宽。

## 实施清单

- 新增 `core/ui/week/WeekCellGrid.kt`：格阵 + 轴向判定纯函数 `resolveDragAxis`（供 JVM 单测）
- 重写 `feature/courseeditor/WeekSelectionSection.kt`：WeekStrip → WeekCellGrid，TextAction → QuickChip
- 新增 `app/src/test/java/com/zhijian/schedule/core/ui/week/WeekCellGridTest.kt`：轴向判定边界
- 更新 `CourseEditorScreen.kt` 调用点注释；删除 `core/ui/week/WeekStrip.kt`
- 本文件 + docs/README.md 索引行

## 验收

- 编译 + WeekSelectionTest / WeekCellGridTest 单测
- ktlint / detekt：门禁在 HEAD 已红（见 docs/plans/2026-11-04 条目），只核对本次触碰文件
- 真机：assembleDebug（`.debug` 包与正式版并排安装，不动正式版数据）→
  添加课程 → 点选 / 横向拖选 / 单双周 / 清空 → 格阵区域纵向拖动页面必须能滚
