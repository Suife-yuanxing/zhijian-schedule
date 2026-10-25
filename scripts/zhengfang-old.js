/**
 * PAPER_SCRIPT_MANIFEST
 * {
 *   "id": "zhengfang-old",
 *   "name": "正方旧版教务（通用）",
 *   "version": 1,
 *   "apiVersion": 1,
 *   "hosts": [],
 *   "entryHint": "登录旧版正方教务后打开「课表查询(xskbcx.aspx)」页面，再点导入"
 * }
 *
 * 旧正方课表页是 HTML 大表格（常见 id=xskb_table）：节次标签列 + 星期列，
 * 课程格常带 rowspan（连堂/大节），一格多门课按 <br> 连排。脚本先展开
 * rowspan/colspan 成规整网格，再按「周次行」切块提取每门课。
 */
globalThis.__paperScheduleScript = {
  fetchSchedule: async function (ctx) {
    var warnings = [];

    var table = document.getElementById('xskb_table');
    if (!table) {
      var best = null, bestCells = 0;
      Array.prototype.forEach.call(document.querySelectorAll('table'), function (t) {
        var n = t.querySelectorAll('td,th').length;
        if (n > bestCells) { bestCells = n; best = t; }
      });
      table = best;
    }
    if (!table) throw new Error('页面上没有表格，请先打开旧正方的「课表查询(xskbcx.aspx)」页面');

    // rowspan/colspan 展开成规整网格：连堂跨的行、占双列的格子都铺平
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
      // 必须带「星期/周」字样，避免把「第一大节」里的「一」当星期一
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
    // 行标签 → 节次区间；含时间(hh:mm)的一律不认（那是时间不是节次）
    function parseSectionLabel(text) {
      var t = (text || '').trim();
      if (!t) return null;
      if (/\d{1,2}\s*[:：]\s*\d{2}/.test(t)) return null;
      var big = t.match(/第([一二三四五六七八九十\d]+)\s*大节/);
      if (big) {
        var n = cnNum(big[1]);
        return n ? [n * 2 - 1, n * 2] : null;
      }
      var pair = t.match(/(\d{1,2})\s*[-–,，、]\s*(\d{1,2})/);
      if (pair) {
        var a = Number(pair[1]), b = Number(pair[2]);
        return [Math.min(a, b), Math.max(a, b)];
      }
      var one = t.match(/第?\s*(\d{1,2})\s*节?/);
      if (one) return [Number(one[1]), Number(one[1])];
      var cn = t.match(/第\s*([一二三四五六七八九十]+)\s*节/);
      if (cn) {
        var m = cnNum(cn[1]);
        return m ? [m, m] : null;
      }
      return null;
    }
    function textOf(html) {
      var doc = new DOMParser().parseFromString(html, 'text/html');
      return (doc.body.textContent || '').replace(/\u00A0/g, ' ').trim();
    }
    // 周次行：1-16周 / 1,3,5周(单) / (1-16周) / 纯数字列表
    function isWeekLine(s) {
      if (!s || !/\d/.test(s)) return false;
      if (s.length > 24) return false;
      if (/周/.test(s) && /单|双|\d/.test(s)) return true;
      return /^[\d,，\-–\s()（）单双周第]+$/.test(s);
    }

    // 表头行：同一行里 ≥3 个「星期X」格
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
    if (headerRow < 0) throw new Error('表格里没找到「星期」表头，请确认已打开课表查询页面');

    var courses = [];
    function emitBlock(lines, day, range) {
      lines = lines.filter(function (x) { return x; });
      if (!lines.length) return;
      var name = String(lines[0]).replace(/[{｛].*?[}｝]/g, '').trim();
      var pm = name.match(/[（(]([^（）()]*周[^（）()]*)[）)]/);
      name = name.replace(/[（(][^（）()]*周[^（）()]*[）)]/g, '').trim();
      if (!name || name.length > 30) return;
      var week = '', rest = [];
      for (var i = 1; i < lines.length; i++) {
        if (!week && isWeekLine(lines[i])) { week = lines[i]; continue; }
        if (lines[i]) rest.push(lines[i]);
      }
      if (!week && pm) week = pm[1];
      week = week.replace(/第/g, '').replace(/[{｝}]/g, '');
      var teacher = '', room = '';
      rest.forEach(function (x) {
        if (!teacher && x.length <= 12 && !/\d/.test(x)) teacher = x;
        else if (!room && x.length <= 30 && /\d|楼|馆|场|室|教/.test(x)) room = x;
      });
      if (!room) {
        rest.forEach(function (x) {
          if (!room && x.length <= 30 && x !== teacher) room = x;
        });
      }
      courses.push({
        name: name, teacher: teacher, room: room, day: day,
        startSection: range[0], endSection: range[1], weeks: week
      });
    }

    // 数据行按小节布局推每行节次：≤7 行按大节（每行 2 节），否则每行 1 节
    var dataRowCount = grid.length - (headerRow + 1);
    var perRow = dataRowCount <= 7 ? 2 : 1;
    // rowspan 展开后同一格出现在多行，按格子引用去重（连堂只发射一次）
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

    var seqOrdinal = 0;
    for (var rr = headerRow + 1; rr < grid.length; rr++) {
      var row = grid[rr];
      var labelText = ((row[0] && row[0].textContent) || '').trim();
      // 午休/备注分隔行不占节次序号
      if (/午休|中午|备注|晚上/.test(labelText) && labelText.length <= 6) continue;
      var seqTop = seqOrdinal;
      seqOrdinal++;
      var labelRange = parseSectionLabel(labelText);
      for (var cc = 1; cc < row.length; cc++) {
        var cell = row[cc];
        if (!cell) continue;
        if (alreadyEmitted(cell)) continue;
        var day = dayByCol[cc];
        if (!day) continue;
        // 跨行数：连堂课一格占多行
        var spanN = 1;
        for (var k = rr + 1; k < grid.length && grid[k][cc] === cell; k++) spanN++;
        var min, max;
        if (labelRange) {
          min = labelRange[0]; max = labelRange[1];
          for (var k2 = 1; k2 < spanN; k2++) {
            var lc = grid[rr + k2] && grid[rr + k2][0];
            var rg2 = lc ? parseSectionLabel(lc.textContent) : null;
            if (rg2) { min = Math.min(min, rg2[0]); max = Math.max(max, rg2[1]); }
          }
        } else {
          // 标签带时间不可解析时按行列序推：第 seqTop 行从 seqTop*perRow+1 节起
          min = seqTop * perRow + 1;
          max = Math.min(seqTop * perRow + perRow * spanN, 15);
        }
        var range = [Math.max(min, 1), Math.min(Math.max(max, min), 15)];
        var lines = cell.innerHTML
          .split(/<br\s*\/?>|<\/div>|<\/p>|<hr[^>]*\/?>/i)
          .map(textOf)
          .filter(function (x) { return x && x.indexOf('备注') !== 0; });
        // 一格多门课：新块从「已有周次行 + 本行像课名 + 后 1-2 行内出现周次行」处切开。
        // 带数字的行（教室 D404）只在紧跟周次行时才算新块起点，避免把教室当课名。
        var block = [];
        for (var li = 0; li < lines.length; li++) {
          var ln = lines[li];
          var hasWeek = block.some(function (b) { return isWeekLine(b); });
          var looksName = !isWeekLine(ln) && ln.length <= 30;
          var isWeek1 = isWeekLine(lines[li + 1]);
          var isWeek2 = isWeekLine(lines[li + 2]);
          var digitless = !/\d/.test(ln);
          var newBlock = hasWeek && looksName && (digitless ? (isWeek1 || isWeek2) : isWeek1);
          if (newBlock) {
            emitBlock(block, day, range);
            block = [ln];
          } else {
            block.push(ln);
          }
        }
        emitBlock(block, day, range);
      }
    }
    if (!courses.length) throw new Error('表格解析结果为空：页面结构可能与旧正方不同');
    return { courses: courses, warnings: warnings };
  }
};
