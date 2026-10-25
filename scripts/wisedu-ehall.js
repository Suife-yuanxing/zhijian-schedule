/**
 * PAPER_SCRIPT_MANIFEST
 * {
 *   "id": "wisedu-ehall",
 *   "name": "金智网上服务大厅（通用）",
 *   "version": 1,
 *   "apiVersion": 1,
 *   "hosts": [],
 *   "entryHint": "登录统一身份认证进入网上服务大厅/教务系统任意页面后，点导入"
 * }
 *
 * 金智(wisedu)新教务走 ehall 的 jwapp 网关：
 *   POST /jwapp/sys/wdkb/modules/xskcb/cxxszhxqkb.do   （部分学校是 wdkbby）
 *   表单参数 XNXQDM=2024-2025-1（学年-学期号）；不带参数时部分学校默认返回当前学期。
 * 返回 datas.<模块名>.rows：KCM 课程名 / SKJS 教师 / JASMC 教室 / SKXQ 星期(1-7) /
 * KSJC、JSJC 起止节次 / SKZC 周次二进制串（第 i 位=1 表示第 i 周有课），ZCMC 周次文案兜底。
 */
globalThis.__paperScheduleScript = {
  fetchSchedule: async function (ctx) {
    // 学期候选：先试不带参数（部分学校默认当前学期），再按月份推 2024-2025-1/-2
    var now = new Date();
    var month = now.getMonth() + 1;
    var fy = month >= 9 ? now.getFullYear() : now.getFullYear() - 1;
    var ay = fy + '-' + (fy + 1);
    var first = month >= 9 ? '1' : '2';
    var second = first === '1' ? '2' : '1';
    var terms = ['', ay + '-' + first, ay + '-' + second, (fy - 1) + '-' + fy + '-' + first];
    var bases = [
      location.origin + '/jwapp/sys/wdkb/modules/xskcb/cxxszhxqkb.do',
      location.origin + '/jwapp/sys/wdkbby/modules/xskcb/cxxszhxqkb.do'
    ];

    var lastErr = '未取到课表';
    for (var b = 0; b < bases.length; b++) {
      for (var t = 0; t < terms.length; t++) {
        var res;
        try {
          res = await fetch(bases[b], {
            method: 'POST',
            credentials: 'same-origin',
            headers: {
              'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
              'X-Requested-With': 'XMLHttpRequest'
            },
            body: terms[t] ? 'XNXQDM=' + encodeURIComponent(terms[t]) : ''
          });
        } catch (e) { lastErr = '网络异常'; continue; }
        if (!res.ok) { lastErr = 'HTTP ' + res.status; continue; }
        var text = await res.text();
        var data;
        try { data = JSON.parse(text); } catch (e) {
          // 网关把未登录请求重定向到统一身份认证时会返回 HTML
          lastErr = /login|cas|auth/i.test(text.slice(0, 2000))
            ? '登录已过期，请先完成统一身份认证登录'
            : '返回内容不是 JSON';
          continue;
        }
        var datas = data && data.datas;
        var rows = null;
        if (datas) {
          for (var key in datas) {
            if (datas[key] && datas[key].rows && datas[key].rows.length) { rows = datas[key].rows; break; }
          }
        }
        if (!rows) { lastErr = '接口没有返回课表数据'; continue; }
        var courses = [];
        rows.forEach(function (row) {
          var name = String(row.KCM || row.kcm || '').replace(/\s+/g, ' ').trim();
          if (!name) return;
          var day = parseInt(row.SKXQ !== undefined ? row.SKXQ : row.skxq, 10);
          if (isNaN(day) || day < 1 || day > 7) return;
          var start = parseInt(row.KSJC !== undefined ? row.KSJC : row.ksjc, 10);
          var end = parseInt(row.JSJC !== undefined ? row.JSJC : row.jsjc, 10);
          if (isNaN(start) || isNaN(end)) return;
          // 周次：优先 SKZC 二进制串（第 i 位=1 → 第 i+1 周），退化用 ZCMC 文案
          var zc = String(row.SKZC !== undefined ? row.SKZC : row.skzc || '');
          var weeks = null;
          if (/^[01\s]+$/.test(zc) && zc.indexOf('1') >= 0) {
            weeks = [];
            for (var i = 0; i < zc.length; i++) {
              if (zc.charAt(i) === '1') weeks.push(i + 1);
            }
          }
          if (!weeks) {
            var zcmc = String(row.ZCMC !== undefined ? row.ZCMC : row.zcmc || '').trim();
            if (!zcmc) return;
            weeks = zcmc;
          }
          courses.push({
            name: name,
            teacher: String(row.SKJS !== undefined ? row.SKJS : row.skjs || '').trim(),
            room: String(row.JASMC !== undefined ? row.JASMC : row.jasmc || '').trim(),
            day: day,
            startSection: start,
            endSection: end,
            weeks: weeks
          });
        });
        if (courses.length) return { courses: courses };
        lastErr = '课表数据为空（学期 ' + (terms[t] || '默认') + '）';
      }
    }
    throw new Error('金智教务未返回课表：' + lastErr);
  }
};
