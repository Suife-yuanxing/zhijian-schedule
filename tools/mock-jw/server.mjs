#!/usr/bin/env node
/**
 * 本地模拟「正方新版 jwglxt」教务，用于没有真实学校账号时验证 App 客户端链路。
 *
 * 只模拟正方新版的课表接口形态，不模拟真实登录 / RSA / 验证码：
 *   GET  /jwglxt/xtgl/login_slogin.html                  → 设置 JSESSIONID，返回假登录页
 *   GET  /jwglxt/kbcx/xskbcx_cxXskbcxIndex.html?...      → 返回带选中 xnm/xqm 的课表页
 *   POST /jwglxt/kbcx/xskbcx_cxXsKb.html?...             → 返回 kbList JSON
 *
 * 用法：
 *   node tools/mock-jw/server.mjs
 *   PORT=18080 node tools/mock-jw/server.mjs
 */
import { createServer } from 'node:http';

const port = Number(process.env.PORT || 8080);

const KB_LIST = [
  {
    kcmc: '模拟高等数学',
    xm: '王老师',
    cdmc: 'A101',
    xqj: '1',
    jc: '1-2节',
    zcd: '1-16周',
  },
  {
    kcmc: '模拟大学英语',
    xm: '李老师',
    cdmc: 'B202',
    xqj: '3',
    jc: '3-4节',
    zcd: '1-16周(单)',
  },
];

const loginPage = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>模拟正方登录</title></head>
<body>
  <h1>模拟正方教务（本地假数据）</h1>
  <p>本页只用于验证 App 的 WebView 会话与抓取链路，不连接任何真实学校。</p>
  <p><a href="/jwglxt/xtgl/index_initMenu.html">进入模拟教务首页</a></p>
</body></html>`;

const indexPage = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>模拟课表查询</title></head>
<body>
  <h1>学生课表查询</h1>
  <label>学年
    <select id="xnm">
      <option value="2024-2025">2024-2025</option>
      <option value="2025-2026" selected>2025-2026</option>
    </select>
  </label>
  <label>学期
    <select id="xqm">
      <option value="3" selected>第一学期</option>
      <option value="12">第二学期</option>
    </select>
  </label>
</body></html>`;

const homePage = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>模拟教务首页</title></head>
<body><h1>模拟教务首页</h1><p>可以回到 App 点「抓取课表」了。</p></body></html>`;

// ---------- 强智 jsxsd（考试安排等） ----------

const qzLoginPage = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>模拟强智登录</title></head>
<body>
  <h1>模拟强智 jsxsd（本地假数据）</h1>
  <p><a href="/jsxsd/framework/xsMain.jsp">进入模拟教务首页</a></p>
</body></html>`;

const qzHomePage = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>模拟强智首页</title></head>
<body><h1>模拟强智教务首页</h1><p>会话已建立，可回 App 抓取课表 / 考试安排。</p></body></html>`;

/** 考试安排表：与 ExamParserTest fixture 同构（id=dataList，列头模糊匹配） */
const examPage = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>考试安排</title></head>
<body>
<table id="dataList">
<tr><th>序号</th><th>课程名称</th><th>考试时间</th><th>考场</th><th>座位号</th><th>备注</th></tr>
<tr><td>1</td><td>模拟高等数学</td><td>2026-11-10 14:00~15:50</td><td>实B-301</td><td>07</td><td>闭卷</td></tr>
<tr><td>2</td><td>模拟大学英语</td><td>2026-11-14 08:20~10:00</td><td>教305</td><td>12</td><td></td></tr>
</table>
</body></html>`;

function send(res, status, contentType, body, headers = {}) {
  res.writeHead(status, { 'Content-Type': contentType, ...headers });
  res.end(body);
}

const server = createServer((req, res) => {
  const url = new URL(req.url || '/', `http://${req.headers.host || 'localhost'}`);
  const path = url.pathname;

  if (path === '/health') {
    send(res, 200, 'application/json; charset=utf-8', JSON.stringify({ ok: true }));
    return;
  }

  if (path === '/jwglxt/xtgl/login_slogin.html') {
    send(res, 200, 'text/html; charset=utf-8', loginPage, {
      // 模拟登录成功后的会话 Cookie；App 的 WebView 与 OkHttp 共享它
      'Set-Cookie': 'JSESSIONID=mock-zf-session; Path=/',
    });
    return;
  }

  if (path === '/jwglxt/xtgl/index_initMenu.html') {
    send(res, 200, 'text/html; charset=utf-8', homePage);
    return;
  }

  if (path === '/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html') {
    send(res, 200, 'text/html; charset=utf-8', indexPage);
    return;
  }

  if (path === '/jwglxt/kbcx/xskbcx_cxXsKb.html' && req.method === 'POST') {
    let body = '';
    req.on('data', (chunk) => { body += chunk; });
    req.on('end', () => {
      // 真实接口按 xnm/xqm 查学期；这里不校验，直接返回固定假数据
      send(res, 200, 'application/json; charset=utf-8', JSON.stringify({ kbList: KB_LIST, echo: body }));
    });
    return;
  }

  if (path === '/jsxsd/sso.jsp') {
    // 模拟强智统一认证回跳：设会话 Cookie 后回到首页（会话恢复链路按「末页不像登录页」判定成功）
    send(res, 200, 'text/html; charset=utf-8', qzLoginPage, {
      'Set-Cookie': 'JSESSIONID=mock-qz-session; Path=/',
    });
    return;
  }

  if (path === '/jsxsd/framework/xsMain.jsp') {
    send(res, 200, 'text/html; charset=utf-8', qzHomePage, {
      'Set-Cookie': 'JSESSIONID=mock-qz-session; Path=/',
    });
    return;
  }

  if (path === '/jsxsd/ksgl/xsksap_list') {
    send(res, 200, 'text/html; charset=utf-8', examPage);
    return;
  }

  send(res, 404, 'text/plain; charset=utf-8', 'not found');
});

server.listen(port, '0.0.0.0', () => {
  console.log(`mock 正方教务已启动：http://127.0.0.1:${port}/jwglxt/xtgl/login_slogin.html`);
  console.log('模拟器访问：http://10.0.2.2:' + port + '/jwglxt/xtgl/login_slogin.html');
  console.log(`mock 强智 jsxsd（考试安排）：http://127.0.0.1:${port}/jsxsd/framework/xsMain.jsp`);
});
