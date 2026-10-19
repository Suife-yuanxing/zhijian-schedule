# 本地模拟正方教务（无需真实学校账号）

> 用途：验证 App 的 WebView → 共享 Cookie → 正方新版适配器 → 预览 → 落库整条**客户端**链路。
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
