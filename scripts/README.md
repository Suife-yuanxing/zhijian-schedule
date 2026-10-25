# 课程脚本仓库（种子）

这个目录是「纸间课表」教务脚本公开仓库的**种子内容**。App 的「脚本管理 → 检查更新」
会拉取一个 `index.json` 并按 SHA-256 校验后安装脚本。

## 发布步骤

1. 在 GitHub 建一个**公开**仓库（建议名 `zhijian-scripts`），把本目录整体拷过去，
   保持 `scripts/index.json + scripts/*.js` 的相对结构（或按 index 里的 url 调整）。
2. 改动任何 `.js` 后必须重算哈希并同步进 `index.json`：
   ```
   certutil -hashfile scripts\qiangzhi-jsxsd.js SHA256
   ```
3. 脚本版本号（manifest 的 `version`）递增，App 才会视为可更新。
4. App 侧：脚本管理页填入 `https://raw.githubusercontent.com/<user>/<repo>/main/scripts/index.json`
   → 「检查更新」。国内直连 raw 不稳时可换任意 HTTPS 静态托管（jsDelivr 等）。

## 脚本编写

契约见 `docs/research/2026-10-25-script-engine-notes.md`；内置五份脚本就是范本：

| 脚本 | 演示的套路 |
| --- | --- |
| `qiangzhi-jsxsd.js` | DOM 表格解析（div.kbcontent、`[01-02节]` 括号节次） |
| `zhengfang-new.js` | 同源 `fetch` 调 JSON 接口（学期候选逐个试） |
| `zhengfang-old.js` | HTML 大表格：rowspan/colspan 展开成网格、一格多课切块 |
| `wisedu-ehall.js` | 金智 jwapp 网关接口 + SKZC 周次二进制串转数组 |
| `generic-table.js` | 无厂商特征的通用 HTML 课表页兜底 |
