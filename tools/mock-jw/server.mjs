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
<body><h1>模拟强智教务首页</h1><p>会话已建立，可回 App 抓取课表 / 考试安排。</p>
<p><a href="/jsxsd/xskb/xskb_list.do">学生课表（脚本导入用）</a></p></body></html>`;

/** 强智课表页：与 QiangZhiAdapter/内置强智脚本的目标结构同构（#kbtable + div.kbcontent） */
const qzCoursePage = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>学生课表</title></head>
<body>
<table id="kbtable">
<tr><th>第一大节 08:20-09:55</th><th>星期一</th><th>星期二</th><th>星期三</th><th>星期四</th><th>星期五</th></tr>
<tr>
  <th>第一大节 08:20-09:55</th>
  <td><div class="kbcontent">模拟高等数学<br/>王老师<br/>A101<br/>1-16周(第1-16周)<br/>[01-02节]</div></td>
  <td><div class="kbcontent"></div></td>
  <td><div class="kbcontent">模拟大学英语<br/>李老师<br/>B202<br/>1-16周(单)<br/>[01-02节]</div></td>
  <td><div class="kbcontent"></div></td>
  <td><div class="kbcontent"></div></td>
</tr>
<tr>
  <th>第二大节 10:10-11:45</th>
  <td><div class="kbcontent"></div></td>
  <td><div class="kbcontent">模拟数据结构<br/>赵老师<br/>C303<br/>1-8,10-16周(第1-16周)<br/>[03-04节]</div></td>
  <td><div class="kbcontent"></div></td>
  <td><div class="kbcontent">模拟体育<br/>钱老师<br/>田径场<br/>1-16周(双)<br/>[03-04节]</div></td>
  <td><div class="kbcontent"></div></td>
</tr>
</table>
</body></html>`;

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

// ---------- 旧版正方（xskbcx.aspx 大表格） ----------

const zfOldLoginPage = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>模拟旧正方登录</title></head>
<body>
  <h1>模拟旧版正方教务（本地假数据）</h1>
  <p><a href="/zfold/xskbcx.aspx">课表查询（xskbcx.aspx）</a></p>
</body></html>`;

/** 12 小节行布局 + 带时间标签（脚本需走行列序推算）+ rowspan 连堂 + 单双周 */
function zfOldCoursePage() {
  const label = (n) => `第${n}节(${String(8 + Math.floor((n - 1) / 2)).padStart(2, '0')}:${(n - 1) % 2 ? '05' : '00'})`;
  const empty = '<td>&nbsp;</td>';
  const rows = [];
  for (let i = 0; i < 12; i++) {
    let cells = `<td class="tdlabel">${label(i + 1)}</td>`;
    if (i === 0) cells += `<td rowspan="2">模拟高等数学<br>1-16周<br>王老师<br>A101</td>${empty}${empty}${empty}${empty}`;
    else if (i === 2) cells += `${empty}${empty}${empty}${empty}<td rowspan="2">模拟C语言<br>1-8周<br>孙老师<br>D404</td>`;
    else if (i === 4) cells += `${empty}${empty}<td rowspan="2">模拟大学英语<br>1-16周(单)<br>李老师<br>B202</td>${empty}${empty}`;
    else if (i === 10) cells += `${empty}<td rowspan="2">模拟体育<br>1-16周(双)<br>钱老师<br>田径场</td>${empty}${empty}${empty}`;
    else cells += `${empty}${empty}${empty}${empty}${empty}`;
    rows.push(`<tr>${cells}</tr>`);
  }
  return `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>学生课表</title></head>
<body>
<table id="xskb_table">
<tr><td>节次</td><td>星期一</td><td>星期二</td><td>星期三</td><td>星期四</td><td>星期五</td></tr>
${rows.join('\n')}
</table>
</body></html>`;
}

// ---------- 金智 wisedu jwapp（ehall 网关） ----------

const wiseduHomePage = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>模拟网上服务大厅</title></head>
<body>
  <h1>模拟金智网上服务大厅（本地假数据）</h1>
  <p>会话已建立，可回 App 用「金智网上服务大厅」脚本导入。</p>
</body></html>`;

const WISEDU_ROWS = [
  { KCM: '模拟高等数学', SKJS: '王老师', JASMC: 'A101', SKXQ: '1', KSJC: 1, JSJC: 2, SKZC: '1111111111111111' },
  { KCM: '模拟大学英语', SKJS: '李老师', JASMC: 'B202', SKXQ: '3', KSJC: 3, JSJC: 4, SKZC: '1010101010101010' },
  { KCM: '模拟数据结构', SKJS: '赵老师', JASMC: 'C303', SKXQ: '2', KSJC: 5, JSJC: 6, SKZC: '1111111100000000' },
  { KCM: '模拟体育', SKJS: '钱老师', JASMC: '田径场', SKXQ: '4', KSJC: 7, JSJC: 8, SKZC: '0101010101010101' },
];

// ---------- 通用 HTML 课表页（无厂商特征） ----------

const genericCoursePage = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><title>我的课表</title></head>
<body>
<table>
<tr><th>节次</th><th>星期一</th><th>星期二</th><th>星期三</th><th>星期四</th><th>星期五</th></tr>
<tr>
  <td>第一大节 08:00-09:40</td>
  <td>模拟高等数学<br>1-16周<br>王老师<br>A101</td>
  <td>&nbsp;</td>
  <td>&nbsp;</td>
  <td>&nbsp;</td>
  <td><div class="kbcontent">模拟C语言<br>1-8周<br>孙老师<br>D404</div><hr><div class="kbcontent">模拟Java<br>9-16周<br>周老师<br>E505</div></td>
</tr>
<tr>
  <td>第二大节 10:00-11:40</td>
  <td>&nbsp;</td>
  <td>&nbsp;</td>
  <td>模拟数据结构<br>1-8,10-16周<br>赵老师<br>C303</td>
  <td>&nbsp;</td>
  <td>&nbsp;</td>
</tr>
<tr>
  <td>第三大节 14:00-15:40</td>
  <td>&nbsp;</td>
  <td>&nbsp;</td>
  <td rowspan="2">模拟大学英语<br>1-16周(单)<br>李老师<br>B202</td>
  <td>&nbsp;</td>
  <td>&nbsp;</td>
</tr>
<tr>
  <td>第四大节 16:00-17:40</td>
  <td>&nbsp;</td>
  <td>&nbsp;</td>
  <td>&nbsp;</td>
  <td>模拟体育<br>1-16周(双)<br>钱老师<br>田径场</td>
  <td>&nbsp;</td>
</tr>
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

  if (path === '/jsxsd/xskb/xskb_list.do') {
    send(res, 200, 'text/html; charset=utf-8', qzCoursePage, {
      'Set-Cookie': 'JSESSIONID=mock-qz-session; Path=/',
    });
    return;
  }

  if (path === '/zfold/' || path === '/zfold/default.aspx') {
    send(res, 200, 'text/html; charset=utf-8', zfOldLoginPage, {
      'Set-Cookie': 'ASP.NET_SessionId=mock-zfold-session; Path=/',
    });
    return;
  }

  if (path === '/zfold/xskbcx.aspx') {
    send(res, 200, 'text/html; charset=utf-8', zfOldCoursePage(), {
      'Set-Cookie': 'ASP.NET_SessionId=mock-zfold-session; Path=/',
    });
    return;
  }

  if (path === '/wisedu/') {
    send(res, 200, 'text/html; charset=utf-8', wiseduHomePage, {
      'Set-Cookie': 'iPlanetDirectoryPro=mock-wisedu-session; Path=/',
    });
    return;
  }

  // 金智课表接口：wdkb / wdkbby 两个变体都接；SKZC 二进制周次串
  if (
    path === '/jwapp/sys/wdkb/modules/xskcb/cxxszhxqkb.do' ||
    path === '/jwapp/sys/wdkbby/modules/xskcb/cxxszhxqkb.do'
  ) {
    send(res, 200, 'application/json; charset=utf-8', JSON.stringify({
      code: '0',
      datas: { cxxszhxqkb: { rows: WISEDU_ROWS, extParams: { code: 1, msg: '查询成功' } } },
    }));
    return;
  }

  if (path === '/generic/table.html') {
    send(res, 200, 'text/html; charset=utf-8', genericCoursePage);
    return;
  }

  send(res, 404, 'text/plain; charset=utf-8', 'not found');
});

server.listen(port, '0.0.0.0', () => {
  console.log(`mock 正方教务已启动：http://127.0.0.1:${port}/jwglxt/xtgl/login_slogin.html`);
  console.log('模拟器访问：http://10.0.2.2:' + port + '/jwglxt/xtgl/login_slogin.html');
  console.log(`mock 强智 jsxsd（考试安排）：http://127.0.0.1:${port}/jsxsd/framework/xsMain.jsp`);
  console.log(`mock 强智 jsxsd（课表页·脚本导入）：http://127.0.0.1:${port}/jsxsd/xskb/xskb_list.do`);
  console.log(`mock 旧版正方（课表页）：http://127.0.0.1:${port}/zfold/default.aspx`);
  console.log(`mock 金智 jwapp（大厅首页）：http://127.0.0.1:${port}/wisedu/`);
  console.log(`mock 通用课表页：http://127.0.0.1:${port}/generic/table.html`);
});
