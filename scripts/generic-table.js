/**
 * PAPER_SCRIPT_MANIFEST
 * {
 *   "id": "generic-table",
 *   "name": "通用课表页（HTML 表格）",
 *   "version": 1,
 *   "apiVersion": 1,
 *   "hosts": [],
 *   "entryHint": "打开学校课表页面（网页上是「星期一～星期日」的大表格）后点导入"
 * }
 *
 * 不依赖特定教务厂商：页面上找到带「星期」表头的最大表格，展开
 * rowspan/colspan，按强智/正方通行的单元格文本套路提取课程
 * （首行课名 → 周次行 → 教师 → 教室；div.kbcontent / <hr> 分隔多门课）。
 * 适合 URP、青果、树维以及学校自建的任意 HTML 课表页。
 */
globalThis.__paperScheduleScript = {
  fetchSchedule: async function (ctx) {
    var warnings = [];

    // 选「含星期表头且格子最多」的表格，页面有多张表（成绩、通知…）时不易选错
    var table = null, tableScore = 0;
    Array.prototype.forEach.call(document.querySelectorAll('table'), function (t) {
      var dayCells = 0, cells = t.querySelectorAll('td,th').length;
      Array.prototype.forEach.call(t.querySelectorAll('tr'), function (tr, ri) {
        if (ri > 3) return;
        Array.prototype.forEach.call(tr.cells, function (cell) {
          var x = (cell.textContent || '').replace(/\s+/g, '');
          if (x.length <= 8 && (x.indexOf('星期') >= 0 || /^周[一二三四五六日天]$/.test(x))) dayCells++;
        });
      });
      var score = dayCells >= 3 ? dayCells * 1000 + cells : 0;
      if (score > tableScore) { tableScore = score; table = t; }
    });
    if (!table) throw new Error('页面上没找到带「星期」表头的课表表格');

    // rowspan/colspan 展开成规整网格
    var grid = [];
    Array.prototype.forEach.call(table.rows, function (tr, r) {
      grid[r] = grid[r] || [];
      var col = 0;
      Array.prototype.forEach.call(tr.cells, function (cell) {
        while (grid[r][col] !== undefined) col++;
        var rs = cell.rowSpan || 1, cs = cell.colSpan || 1;
        for (var dr = 0; dr < rs; dr++) {
          // 后续行的数组此刻可能还没建（tr 顺序处理），要先补出来
          if (!grid[r + dr]) grid[r + dr] = [];
          for (var dc = 0; dc < cs; dc++) grid[r + dr][col + dc] = cell;
        }
        col += cs;
      });
    });
    grid = grid.filter(function (row) { return row && row.length; });
    if (!grid.length) throw new Error('课表表格为空');

    function parseDay(text) {
      var t = (text || '').replace(/\s+/g, '');
      if (!t || t.length > 8) return 0;
      if (t.indexOf('星期') < 0 && t.indexOf('周') < 0) return 0;
      var zh = ['一', '二', '三', '四', '五', '六', '日', '天'];
      for (var i = 0; i < zh.length; i++) {
        if (t.indexOf(zh[i]) >= 0) return Math.min(i + 1, 7);
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
      var big = t.match(/第([一二三四五六七八九十\d]+)\s*[大小结]?\s*节/);
      if (big) {
        var n = cnNum(big[1]);
        if (n && /大节/.test(t)) return [n * 2 - 1, n * 2];
        if (n) return [n, n];
      }
      var nums = (t.match(/\d+/g) || []).map(Number);
      if (nums.length === 1) return [nums[0], nums[0]];
      if (nums.length >= 2) return [Math.min(nums[0], nums[1]), Math.max(nums[0], nums[1])];
      return null;
    }
    function textOf(html) {
      var doc = new DOMParser().parseFromString(html, 'text/html');
      return (doc.body.textContent || '').replace(/\u00A0/g, ' ').trim();
    }
    function bracketSections(line) {
      var m = (line || '').match(/\[([\d\-\s]+)节]/);
      if (!m) return null;
      var nums = (m[1].match(/\d+/g) || []).map(Number);
      if (!nums.length) return null;
      return [Math.min.apply(null, nums), Math.max.apply(null, nums)];
    }
    function isWeekLine(s) {
      if (!s || !/\d/.test(s)) return false;
      if (s.length > 24) return false;
      if (/周/.test(s) && /单|双|\d/.test(s)) return true;
      return /^[\d,，\-–\s()（）单双周第]+$/.test(s);
    }
    function mapLines(lines, day, rowRange, ordinal, perRow) {
      if (!lines.length) return null;
      var name = lines[0].trim().replace(/[*（(]+$/, '');
      if (!name || name.length > 30) return null;
      var weekLine = null;
      for (var i = 0; i < lines.length; i++) {
        var it = lines[i];
        if (it.indexOf('周') >= 0 && it.indexOf('星期') < 0 && (/\d/.test(it) || it.indexOf('全') >= 0)) {
          weekLine = it; break;
        }
      }
      if (!weekLine) {
        for (var j = 0; j < lines.length; j++) {
          if (/^[\d,\-()（）单双]+$/.test(lines[j]) && /\d/.test(lines[j])) { weekLine = lines[j]; break; }
        }
      }
      var rest = lines.slice(1).filter(function (x) { return x !== weekLine; });
      var teacher = '', room = '';
      rest.forEach(function (x) {
        if (!teacher && x.length <= 12 && !/\d/.test(x)) teacher = x;
        else if (!room && x.length <= 30) room = x;
      });
      var base = weekLine ? null : (rowRange || [ordinal * perRow + 1, ordinal * perRow + perRow]);
      var range = bracketSections(weekLine) || rowRange || base;
      return {
        name: name, teacher: teacher, room: room, day: day,
        startSection: range[0], endSection: range[1],
        weeks: weekLine ? weekLine.split('[')[0] : ''
      };
    }

    // 表头行
    var headerRow = -1, dayByCol = {};
    for (var r = 0; r < Math.min(4, grid.length); r++) {
      var mapping = {};
      for (var c = 0; c < grid[r].length; c++) {
        if (mapping[c] !== undefined) continue;
        var d = parseDay(grid[r][c].textContent);
        if (d) mapping[c] = d;
      }
      if (Object.keys(mapping).length >= 3) { dayByCol = mapping; headerRow = r; break; }
    }
    if (headerRow < 0) throw new Error('表格里没找到「星期」表头');

    var dataRowCount = grid.length - (headerRow + 1);
    var perRow = dataRowCount <= 7 ? 2 : 1;
    var emitted = typeof WeakSet === 'function' ? new WeakSet() : null;
    var emittedFallback = [];
    function alreadyEmitted(cell) {
      if (emitted) {
        if (emitted.has(cell)) return true;
        emitted.add(cell);
        return false;
      }
      if (emittedFallback.indexOf(cell) >= 0) return true;
      emittedFallback.push(cell);
      return false;
    }

    var courses = [];
    var seqOrdinal = 0;
    for (var rr = headerRow + 1; rr < grid.length; rr++) {
      var row = grid[rr];
      var labelText = ((row[0] && row[0].textContent) || '').trim();
      if (/午休|中午|备注|晚上/.test(labelText) && labelText.length <= 6) continue;
      var ordinal = seqOrdinal;
      seqOrdinal++;
      var rowRange = parseSectionLabel(labelText);
      for (var cc = 1; cc < row.length; cc++) {
        var cell = row[cc];
        if (!cell) continue;
        if (alreadyEmitted(cell)) continue;
        var day = dayByCol[cc];
        if (!day) continue;
        var spanN = 1;
        for (var k = rr + 1; k < grid.length && grid[k][cc] === cell; k++) spanN++;
        var min, max;
        if (rowRange) {
          min = rowRange[0]; max = rowRange[1];
          for (var k2 = 1; k2 < spanN; k2++) {
            var lc = grid[rr + k2] && grid[rr + k2][0];
            var rg2 = lc ? parseSectionLabel(lc.textContent) : null;
            if (rg2) { min = Math.min(min, rg2[0]); max = Math.max(max, rg2[1]); }
          }
        } else {
          min = ordinal * perRow + 1;
          max = Math.min(ordinal * perRow + perRow * spanN, 15);
        }
        var range = [Math.max(min, 1), Math.min(Math.max(max, min), 15)];
        var blocks = cell.querySelectorAll('div.kbcontent');
        var sources = blocks.length
          ? Array.prototype.map.call(blocks, function (b) { return b.innerHTML; })
          : [cell.innerHTML];
        sources.forEach(function (raw) {
          raw.split(/<hr[^>]*\/?>|-{6,}|<\/div>/i).forEach(function (fragment) {
            var lines = fragment.split(/<br\s*\/?>/i).map(textOf).filter(Boolean);
            var course = mapLines(lines, day, range, ordinal, perRow);
            if (course) courses.push(course);
          });
        });
      }
    }
    if (!courses.length) throw new Error('表格解析结果为空：未识别出任何课程');
    return { courses: courses, warnings: warnings };
  }
};
