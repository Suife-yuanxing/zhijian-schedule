# 教务脚本契约（给脚本作者的一页说明）

- 日期：2026-10-25
- 设计全文：[2026-10-25-jw-script-engine-design.md](../specs/2026-10-25-jw-script-engine-design.md)
- 范本：App 仓库 `app/src/main/assets/jw/scripts/`（强智 DOM 版 / 正方 fetch 版）

## 一个脚本 = 一个 .js 文件

文件头部必须是含 `PAPER_SCRIPT_MANIFEST` 的块注释 + JSON：

```js
/**
 * PAPER_SCRIPT_MANIFEST
 * {
 *   "id": "my-school",            // 小写字母数字连字符，全局唯一
 *   "name": "XX学校教务",          // ≤30 字
 *   "version": 1,                  // 更新脚本时递增
 *   "apiVersion": 1,               // App 支持的契约版本
 *   "hosts": ["jw.xx.edu.cn"],     // 非空 = 仅这些域名下可运行；空 = 通用
 *   "entryHint": "登录后打开学生课表页再点导入"
 * }
 */
globalThis.__paperScheduleScript = {
  fetchSchedule: async function (ctx) {
    // ctx = { url: 当前页面地址, totalWeeks: 课表总周数 }
    return {
      courses: [ /* 见下表 */ ],
      totalWeeks: 20,             // 可选，纠正总周数
      warnings: ["给用户看的提示"] // 可选
    };
  }
};
```

## 课程对象（字段名对齐小爱课程表）

| 字段 | 类型 | 规则 |
| --- | --- | --- |
| `name` | string | 必填 1..30 字，超长会被截断 |
| `day` | 1..7 | 周一=1；非法条目被丢弃并计入 warnings |
| `startSection`/`endSection` | 1..15 | 落库时收敛到 1..12 |
| `weeks` | int[] 或 string | `"1-16周"`、`"1-8,10-16周"`、`"1-16(单)"`、`[1,2,3]` 均可 |
| `room` / `teacher` | string | 可选，≤30/20 字 |

信封级规则：`courses` 为空 → 导入失败（不会自动应用空结果）；>500 条 → 拒绝。

## 两种写法

- **DOM 解析**（适合 HTML 表格教务）：脚本在用户当前打开的课表页里执行，
  直接 `document.querySelector`；多课同格按 `<hr>`/`-----` 分隔拆分。
  参考范本 `qiangzhi-jsxsd.js`（`#kbtable` 矩阵、`div.kbcontent`、`[01-02节]` 权威节次）。
- **同源接口**（适合 JSON 型教务）：`fetch('/path', { credentials: 'same-origin' })`
  自动带登录 Cookie，只允许同源。参考范本 `zhengfang-new.js`（读 `xnm/xqm` 后 POST `kbList`）。

## 沙箱与限制（务必知悉）

- 脚本只在用户显式点「导入」时执行一次，20 秒超时；不能后台运行。
- 只有「回传结果」一个桥；没有弹窗、存储、Prompt 桥；不要 `window.open`。
- `hosts` 非空时 App 会校验当前页面域名，防错页误跑。
- 解析失败时把中间结果塞进 `warnings` 带回来调试（App 会以提示条展示）。
