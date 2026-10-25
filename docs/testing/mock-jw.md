# 本地模拟正方教务（无需真实学校账号）

> 用途：验证 App 的 WebView → 共享 Cookie → 正方新版适配器 → 预览 → 落库整条**客户端**链路。
> 另含一个最小的**强智 jsxsd** 区块，用于验证考试安排导入（考试抓取仅强智可用）。
> 不验证：真实登录、RSA 密码加密、验证码/滑块、各校字段差异、学校 WAF。这些只能靠真实学校 beta。

## 1. 启动模拟服务

```bash
node tools/mock-jw/server.mjs
# 或换端口
PORT=18080 node tools/mock-jw/server.mjs
```

看到 `mock 正方教务已启动` 即可。

## 2. 在模拟器 / 真机上打开

- **Android 模拟器**：App 里填
  `http://10.0.2.2:8080/jwglxt/xtgl/login_slogin.html`
- **USB 真机**：先 `adb reverse tcp:8080 tcp:8080`，App 里填
  `http://127.0.0.1:8080/jwglxt/xtgl/login_slogin.html`
- 手机和电脑同一局域网：填电脑局域网 IP，例如
  `http://192.168.1.20:8080/jwglxt/xtgl/login_slogin.html`

> 本项目已放行明文 HTTP（`network_security_config.xml`），模拟器可直接访问。

## 3. 预期结果

1. 地址栏显示「识别为：正方新版（jwglxt）」；
2. 点「抓取课表」后出现预览；
3. 预览应有 2 门课：
   - 模拟高等数学：周一 1-2 节，1-16 周；
   - 模拟大学英语：周三 3-4 节，1-16 周单周；
4. 应用后能在周视图看到。

## 4. 这个模拟环境能覆盖什么

- URL 识别与 `/jwglxt/` 基地址归一化；
- WebView 与 OkHttp 的 Cookie 共享；
- 学年/学期读取（`#xnm` / `#xqm`）；
- `POST xskbcx_cxXsKb.html` 与 `kbList` JSON 解析；
- 单双周、预览 Diff、落库。

## 5. 不能覆盖什么

- 真实登录与验证码；
- 不同学校的字段别名/学期编码；
- 学校 WAF、WebVPN、CAS 跳转；
- 真实课表结构差异。

这些必须找 1–2 位有账号的同学/用户做 beta：装 debug 包，只回传脱敏后的错误信息或空课表截图即可，不需要把账号给开发者。

## 6. 强智 jsxsd：考试安排导入 E2E

服务端在 8080 端口同时提供 jsxsd 区块（`/jsxsd/sso.jsp`、`/jsxsd/framework/xsMain.jsp`、`/jsxsd/ksgl/xsksap_list`）：

1. 教务导入页填 `http://10.0.2.2:8080/jsxsd/framework/xsMain.jsp`（真机用 `adb reverse` 后填 127.0.0.1），地址栏应识别为「强智 jsxsd」；
2. 打开「我的 → 考试安排」，右上角出现「从教务导入」按钮（仅强智会话展示）；
3. 点击导入 → 应出现两条考试（模拟高等数学 11-10 14:00 实B-301 07 号座、模拟大学英语 11-14 08:20 教305 12 号座）与摘要横幅「新增 2」；
4. 手动添加一场考试后再点导入：手动场保留原样，摘要应含「手动 1 场保留」；
5. 再次点导入（无变化）→ 摘要为「考试安排无变化」。

## 7. 脚本导入 E2E（JS 脚本引擎）

服务端提供 `/jsxsd/xskb/xskb_list.do`（`#kbtable` 课表页，4 门课含单双周与 `[01-02节]` 节次）：

1. 启动 mock（`node tools/mock-jw/server.mjs`），教务导入页填 `http://<PC-IP>:8080/jsxsd/framework/xsMain.jsp` 打开（真机与 PC 同一局域网；模拟器用 10.0.2.2）；
2. 在 WebView 里点进「学生课表（脚本导入用）」链接，确认页面出现 4 门课的表格；
3. 点顶栏右上角「课程脚本」图标 → 选择器应列出「强智教务（通用）」「正方新版教务（通用）」，强智脚本带「适用于当前站点」标记；
4. 点运行 → 预览 4 门课（高数 1-2 节周一 1-16 周、英语 1-2 节周三单周、数据结构 3-4 节周二 1-8,10-16、体育 3-4 节周四双周）→ 应用；
5. 脚本管理页（选择器底部「管理脚本…」）：内置两份带「内置」徽章；填仓库地址后「检查更新」可装远程脚本（index 校验要求 url 为 https，本地验证建议直接「导入 .js」）；
6. 「导入 .js」选一个 manifest 非法的文件 → 应提示原因且不落盘。

## 8. 脚本导入 E2E（旧正方 / 金智 / 通用表格）

三个通用脚本各自的 mock 页面（同一个 8080 服务）：

- 旧正方 `/zfold/default.aspx` → `/zfold/xskbcx.aspx`：12 小节行、标签带时间
  `第1节(08:00)`（脚本走行列序推算）、rowspan 连堂、单双周。选「正方旧版教务（通用）」
  运行 → 预览 4 门课：高数 周一 1-2、C语言 周五 3-4（1-8 周）、英语 周三 5-6（单）、
  体育 周二 11-12（双）。
- 金智 `/wisedu/`：选「金智网上服务大厅（通用）」运行 → 脚本会 POST
  `/jwapp/sys/wdkb*/modules/xskcb/cxxszhxqkb.do`，预览 4 门课，周次来自 SKZC
  二进制串（英语=单周数组、数据结构=1-8、体育=双周数组）。
- 通用 `/generic/table.html`：无厂商特征表格（`div.kbcontent` + `<hr>` 一格两门课 +
  rowspan 跨两大节）。选「通用课表页（HTML 表格）」运行 → 预览 6 门课：C语言/Java 同格
  切成两条，英语 周三 5-8（rowspan 并集），体育 周四 7-8（双）。

本地解析快速验证（不装机）：`.tmp/jsdom-test/run.mjs` 用 jsdom 对着 mock 页面跑三个脚本。

