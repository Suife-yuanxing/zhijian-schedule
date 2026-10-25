/**
 * PAPER_SCRIPT_MANIFEST
 * {
 *   "id": "qiangzhi-jsxsd",
 *   "name": "强智教务（通用）",
 *   "version": 1,
 *   "apiVersion": 1,
 *   "hosts": [],
 *   "entryHint": "登录教务后打开「学生课表」页面（xskb_list.do），再点导入"
 * }
 *
 * 纸间课表脚本范本：与内置强智解析（QiangZhiAdapter）同一套规则的页面上下文实现。
 * 给新学校写脚本可复制本文件改 manifest 与选择器。
 */
globalThis.__paperScheduleScript = {
  fetchSchedule: async function (ctx) {
    var warnings = [];
    var table = document.getElementById('kbtable') || document.querySelector('table.kbtable');
    if (!table) throw new Error('未找到课表表格（#kbtable），请先打开「学生课表」页面');
    var rows = [];
    Array.prototype.forEach.call(table.querySelectorAll('tr'), function (tr) {
      if (tr.querySelector('th,td')) rows.push(tr);
    });
    if (!rows.length) throw new Error('课表表格为空');

    function parseDay(text) {
      var t = (text || '').trim();
      if (!t || t.length > 8) return 0;
      if (t.indexOf('星期') >= 0 || t.indexOf('周') >= 0) {
        var zh = ['一', '二', '三', '四', '五', '六', '日', '天'];
        for (var i = 0; i < zh.length; i++) {
          if (t.indexOf(zh[i]) >= 0) return Math.min(i + 1, 7);
        }
      }
      return 0;
    }
    function cnNum(s) {
      var map = { '一': 1, '二': 2, '三': 3, '四': 4, '五': 5, '六': 6, '七': 7, '八': 8, '九': 9, '十': 10 };
      if (map[s] !== undefined) return map[s];
      var n = parseInt(s, 10);
      return isNaN(n) ? 0 : n;
    }
    function parseSectionLabel(text) {
      var t = (text || '').trim();
      if (!t) return null;
      if (/\d{1,2}\s*[:：]\s*\d{2}/.test(t)) return null;
      var big = /第([一二三四五六七八九十\d]+)大节/.exec(t);
      if (big) {
        var n = cnNum(big[1]);
        return n ? [n * 2 - 1, n * 2] : null;
      }
      var nums = (t.match(/\d+/g) || []).map(Number);
      if (nums.length === 1) return [nums[0], nums[0]];
      if (nums.length >= 2) return [Math.min(nums[0], nums[1]), Math.max(nums[0], nums[1])];
      return null;
    }
    function textOf(fragment) {
      var doc = new DOMParser().parseFromString(fragment, 'text/html');
      return (doc.body.textContent || '').replace(/\u00A0/g, ' ').trim();
    }
    function bracketSections(line) {
      var m = /\[([\d\-\s]+)节]/.exec(line || '');
      if (!m) return null;
      var nums = (m[1].match(/\d+/g) || []).map(Number);
      if (!nums.length) return null;
      return [Math.min.apply(null, nums), Math.max.apply(null, nums)];
    }
    function fallbackRange(ordinal, bigRowLayout) {
      if (bigRowLayout) {
        var period = Math.min(Math.max(ordinal + 1, 1), 6);
        return [period * 2 - 1, period * 2];
      }
      var section = Math.min(Math.max(ordinal + 1, 1), 12);
      return [section, section];
    }
    function mapLines(lines, day, rowRange, ordinal, bigRowLayout) {
      if (!lines.length) return null;
      var name = lines[0].trim().replace(/[*（(]+$/, '');
      if (!name || name.length > 30) return null;
      var weekLine = null;
      for (var i = 0; i < lines.length; i++) {
        var it = lines[i];
        if (it.indexOf('周') >= 0 && it.indexOf('星期') < 0 && (/\d/.test(it) || it.indexOf('全') >= 0)) {
          weekLine = it;
          break;
        }
      }
      if (!weekLine) {
        for (var j = 0; j < lines.length; j++) {
          if (/^[\d,\-()（）单双]+$/.test(lines[j]) && /\d/.test(lines[j])) { weekLine = lines[j]; break; }
        }
      }
      var rest = lines.slice(1).filter(function (x) { return x !== weekLine; });
      var teacher = rest[0] && rest[0].length <= 20 ? rest[0] : '';
      var room = rest[1] && rest[1].length <= 30 ? rest[1] : '';
      var weekExpr = weekLine ? weekLine.split('[')[0] : '';
      var range = bracketSections(weekLine) || rowRange || fallbackRange(ordinal, bigRowLayout);
      return {
        name: name, teacher: teacher, room: room, day: day,
        startSection: range[0], endSection: range[1], weeks: weekExpr
      };
    }

    var headerDays = null, headerIndex = -1, dayValues = [];
    for (var r = 0; r < Math.min(3, rows.length); r++) {
      var headCells = rows[r].querySelectorAll('th,td');
      var mapping = {};
      Array.prototype.forEach.call(headCells, function (c, idx) {
        var d = parseDay(c.textContent);
        if (d) mapping[idx] = d;
      });
      var keys = Object.keys(mapping);
      if (keys.length >= 3) {
        headerDays = mapping;
        headerIndex = r;
        keys.sort(function (a, b) { return Number(a) - Number(b); })
          .forEach(function (k) { dayValues.push(mapping[k]); });
        break;
      }
    }
    var dataRows = rows.filter(function (_, i) { return i !== headerIndex; });
    var bigRowLayout = dataRows.length <= 7;
    var courses = [];
    dataRows.forEach(function (tr, ordinal) {
      var cells = Array.prototype.slice.call(tr.querySelectorAll('th,td'));
      if (!cells.length) return;
      if (headerDays && cells.length < Object.keys(headerDays).length) return;
      var rowRange = parseSectionLabel(cells[0].textContent);
      var courseCells = headerDays
        ? cells.slice(cells.length - Object.keys(headerDays).length)
        : cells;
      courseCells.forEach(function (td, colIdx) {
        var day = headerDays ? dayValues[colIdx] : (colIdx >= 1 && colIdx <= 7 ? colIdx : 0);
        if (!day) return;
        var blocks = td.querySelectorAll('div.kbcontent');
        var sources = blocks.length
          ? Array.prototype.map.call(blocks, function (b) { return b.innerHTML; })
          : [td.innerHTML];
        sources.forEach(function (raw) {
          raw.split(/<hr[^>]*\/?>|-{6,}|<\/div>/i).forEach(function (fragment) {
            var lines = fragment.split(/<br\s*\/?>/i).map(textOf).filter(Boolean);
            var c = mapLines(lines, day, rowRange, ordinal, bigRowLayout);
            if (c) courses.push(c);
          });
        });
      });
    });
    return { courses: courses, warnings: warnings };
  }
};
