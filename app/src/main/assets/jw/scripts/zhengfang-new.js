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

    // 学年 + 学期码 → 学期名。与 Kotlin 侧 JwSemesterLabel 同口径：正方用 3/12，
    // 也兼容强智那套 1/2。认不出返回空串（不回传字段，本地值原样保留）。
    function termLabel(year, term) {
      // 变量名避开外层那个 m（location 匹配结果），同名虽不冲突但读起来容易错认
      var yearMatch = /^(\d{4})\s*-?\s*(\d{4})?/.exec(String(year || '').trim());
      if (!yearMatch) return '';
      var start = parseInt(yearMatch[1], 10);
      var end = yearMatch[2] ? parseInt(yearMatch[2], 10) : start + 1;
      if (end !== start + 1) end = start + 1;
      var t = parseInt(String(term || '').trim(), 10);
      var name = (t === 1 || t === 3) ? '第一学期' : (t === 2 || t === 12) ? '第二学期' : '';
      if (!name) return '';
      return start + '-' + end + '学年' + name;
    }

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
      if (courses.length) {
        // 学期名随课表一起交回去：kbList 只给周次不给日期，但页面上的学年学期下拉
        // 是现成的，能同步一个字段就少让用户手填一个。开学日期拿不到就不编。
        var env = { courses: courses };
        var label = termLabel(parts[0], parts[1]);
        if (label) env.semester = label;
        return env;
      }
    }
    throw new Error('正方新版未返回课表：' + lastErr);
  }
};
