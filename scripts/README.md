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

契约见 `docs/research/2026-10-25-script-engine-notes.md`；两份内置脚本
（`qiangzhi-jsxsd.js` / `zhengfang-new.js`）就是范本：强智版演示 DOM 表格解析，
正方版演示同源 `fetch` 调 JSON 接口。
