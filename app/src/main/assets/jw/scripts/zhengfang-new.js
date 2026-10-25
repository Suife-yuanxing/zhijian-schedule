/**
 * PAPER_SCRIPT_MANIFEST
 * {
 *   "id": "zhengfang-new",
 *   "name": "正方新版教务（通用）",
 *   "version": 1,
 *   "apiVersion": 1,
 *   "hosts": [],
 *   "entryHint": "登录正方教务（地址含 /jwglxt/）后任意页面即可点导入"
 * }
 */
globalThis.__paperScheduleScript = {
  fetchSchedule: async function (ctx) {
    var m = location.pathname.match(/\/jwglxt\//);
    if (!m) throw new Error('请先打开正方教务（地址需包含 /jwglxt/）');
    var base = location.origin + location.pathname.slice(0, m.index + '/jwglxt/'.length);

    function selected(id) {
      var el = document.querySelector('select#' + id + ' option[selected]');
      return el ? (el.getAttribute('value') || '').trim() : '';
    }
    var candidates = [];
    var xnm = selected('xnm'), xqm = selected('xqm');
    if (xnm && xqm) candidates.push(xnm + '|' + xqm);
    var now = new Date();
    var month = now.getMonth() + 1;
    var fy = month >= 9 ? now.getFullYear() : now.getFullYear() - 1;
    var ay = fy + '-' + (fy + 1);
    if (month >= 2 && month <= 8) candidates.push(ay + '|12', ay + '|3');
    else candidates.push(ay + '|3', ay + '|12');
    candidates = candidates.filter(function (c, i) { return candidates.indexOf(c) === i; });

    var lastErr = '学期参数不匹配或页面结构已改';
    for (var i = 0; i < candidates.length; i++) {
      var parts = candidates[i].split('|');
      var res;
      try {
        res = await fetch(base + 'kbcx/xskbcx_cxXsKb.html?gnmkdm=N2151', {
          method: 'POST',
          credentials: 'same-origin',
          headers: {
            'Content-Type': 'application/x-www-form-urlencoded; charset=UTF-8',
            'X-Requested-With': 'XMLHttpRequest'
          },
          body: 'xnm=' + encodeURIComponent(parts[0]) + '&xqm=' + encodeURIComponent(parts[1]) + '&kzlx=ck'
        });
      } catch (e) { lastErr = '网络异常'; continue; }
      if (!res.ok) { lastErr = 'HTTP ' + res.status; continue; }
      var text = await res.text();
      if (text.indexOf('login_slogin') >= 0 || text.indexOf('请登录') >= 0) {
        throw new Error('登录已过期，请重新登录后再导入');
      }
      var data;
      try { data = JSON.parse(text); } catch (e) { lastErr = '返回内容不是 JSON'; continue; }
      var list = data && data.kbList;
      if (!list || !list.length) continue;
      var courses = [];
      list.forEach(function (k) {
        var name = String(k.kcmc || '').replace(/\s+/g, ' ').trim();
        if (!name) return;
        var day = parseInt(k.xqj, 10);
        if (isNaN(day) || day < 1 || day > 7) return;
        var nums = String(k.jc || k.jcs || '').match(/\d+/g) || [];
        if (!nums.length) return;
        var sections = nums.map(Number);
        courses.push({
          name: name,
          teacher: String(k.xm || '').trim(),
          room: String(k.cdmc || k.xqmc || '').trim(),
          day: day,
          startSection: Math.min.apply(null, sections),
          endSection: Math.max.apply(null, sections),
          weeks: String(k.zcd || k.zcmc || '')
        });
      });
      if (courses.length) return { courses: courses };
    }
    throw new Error('正方新版未返回课表：' + lastErr);
  }
};
