/**
 * device —— 真机联调（adb）。
 *
 * action:
 *   list        枚举设备（序列号 / 型号 / Android 版本 / 状态）
 *   install     安装 APK（默认 debug 产物；-r 覆盖安装，保留数据）
 *   launch      启动主 Activity
 *   stop        强制停止应用
 *   logcat      抓日志（可按 tag / 关键字 / 行数过滤，抓完自动断开）
 *   screenshot  截图并保存到 .tmp/shots/
 *   shell       执行一条 adb shell 命令（白名单前缀校验，防误伤）
 *   uninstall   卸载（debug 包名）
 *   reverse     adb reverse 端口映射（add / list / remove；mock-jw 真机联调用）
 *   tap         单点点击（坐标结构化，不放开 shell 白名单）
 *   swipe       滑动（起止坐标 + 时长）
 *   text        输入 ASCII 文本（input 命令本身不支持中文，遇到会明确失败）
 *   key         按键（命名别名或 KEYCODE_*）
 *   settings    读 / 写调试类系统设置（写操作受键白名单限制）
 *   perf        gfxinfo 帧率与卡顿：reset → 等 → 读 → 解析成数字
 *
 * 设备选择：serial 为空时取第一台 device 状态的设备；多台设备时必须显式指定。
 */

import { existsSync, mkdirSync } from 'node:fs';
import path from 'node:path';
import { S } from '../schema.mjs';
import { ENVELOPE, ok, fail, str, int } from './common.mjs';
import { run } from '../exec.mjs';
import { repoRoot, repoPath, adbPath, debugApplicationId, artifactInfo } from '../project.mjs';

/** adb shell 允许的前缀（只读或低风险的排查命令）。 */
const SHELL_PREFIXES = [
    'pm ', 'dumpsys ', 'getprop', 'settings get', 'wm ', 'am start', 'am force-stop',
    'cmd ', 'logcat', 'ls ', 'cat /proc/', 'input keyevent', 'screencap',
];

const APK_DEBUG = ['app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'];

/**
 * settings 可写的键白名单。
 *
 * 只放行动效缩放与深色模式这两类「验证 UI 行为必须改」的开关：
 *  · animator_duration_scale / transition_animation_scale / window_animation_scale
 *    用来验证「reduce motion 时动效是否真的停住」（docs/research/2026-10-30-uiverse-motion-picks.md 实测手法）；
 *  · debug_layout 用来开布局边界看是否漏画。
 * 其他键（尤其 secure 命名空间）一律拒绝 —— 那是设备安全边界，不该由开发工具去动。
 */
const SETTINGS_WRITABLE = new Set([
    'global:animator_duration_scale',
    'global:transition_animation_scale',
    'global:window_animation_scale',
    'global:debug_layout',
]);

/** 按键别名 → KEYCODE。只收真机验证脚本里真用得到的几个。 */
const KEY_ALIASES = {
    back: 'KEYCODE_BACK',
    home: 'KEYCODE_HOME',
    enter: 'KEYCODE_ENTER',
    menu: 'KEYCODE_MENU',
    power: 'KEYCODE_POWER',
    wakeup: 'KEYCODE_WAKEUP',
    sleep: 'KEYCODE_SLEEP',
    appswitch: 'KEYCODE_APP_SWITCH',
    volumeup: 'KEYCODE_VOLUME_UP',
    volumedown: 'KEYCODE_VOLUME_DOWN',
};

async function adb(args, timeoutMs) {
    const exe = await adbPath();
    if (!exe) return { ok: false, output: '找不到 adb（检查 local.properties 的 sdk.dir 或 ADB 环境变量）' };
    return run({ command: exe, args, cwd: repoRoot(), timeoutMs: timeoutMs || 60000 });
}

function parseDevices(text) {
    const devices = [];
    for (const line of text.split(/\r?\n/)) {
        const t = line.trim();
        if (!t || t.startsWith('List of devices') || t.startsWith('*')) continue;
        const parts = t.split(/\s+/);
        const serial = parts[0];
        const state = parts[1];
        if (!serial || !state) continue;
        const tail = parts.slice(2).join(' ');
        devices.push({
            serial,
            state,
            model: (tail.match(/model:(\S+)/) || [])[1] || null,
            android: (tail.match(/device:(\S+)/) || [])[1] || null,
        });
    }
    return devices;
}

async function pickSerial(requested) {
    const listed = await adb(['devices'], 20000);
    const devices = parseDevices(listed.stdout || '');
    const online = devices.filter((d) => d.state === 'device');
    if (requested) {
        const hit = devices.find((d) => d.serial === requested);
        if (!hit) return { error: '设备 ' + requested + ' 不在 adb 列表里', devices };
        if (hit.state !== 'device') return { error: '设备 ' + requested + ' 状态是 ' + hit.state + '（不是 device）', devices };
        return { serial: requested, devices };
    }
    if (online.length === 0) return { error: '没有处于 device 状态的设备', devices };
    if (online.length > 1) return { error: '有 ' + online.length + ' 台设备，请用 serial 指定', devices };
    return { serial: online[0].serial, devices };
}

/**
 * 解析 adb reverse --list 的输出。
 *
 * 第一列是**传输通道名**（UsbFfs / emulator-5554 / 局域网地址），不是设备序列号 ——
 * 名字叫 serial 会让人误以为能拿去填 serial 参数，所以这里叫 transport。
 */
function parseReverseList(text) {
    const rows = [];
    for (const line of String(text).split(/\r?\n/)) {
        // 形如：UsbFfs tcp:8080 tcp:8080
        const m = line.trim().match(/^(\S+)\s+tcp:(\d+)\s+tcp:(\d+)$/);
        if (!m) continue;
        rows.push({ transport: m[1], local: Number(m[2]), remote: Number(m[3]) });
    }
    return rows;
}

/**
 * 解析 dumpsys gfxinfo 的汇总段。
 *
 * 只认 "Graphics info for pid" 之后那一段的键值行 —— gfxinfo 还会打印每条命令的
 * 明细直方图，那部分行数极多且与结论无关，全部丢掉。
 */
function parseGfxinfo(text) {
    const result = {
        packageName: null,
        pid: null,
        totalFrames: null,
        jankyFrames: null,
        jankyPercent: null,
        p50: null,
        p90: null,
        p95: null,
        p99: null,
        missedVsync: null,
        highInputLatency: null,
        slowUiThread: null,
        slowBitmapUploads: null,
        slowIssueDrawCommands: null,
    };
    const head = String(text).match(/Graphics info for pid (\d+) \[([^\]]+)\]/);
    if (head) {
        result.pid = Number(head[1]);
        result.packageName = head[2];
    }
    const num = (pattern) => {
        const m = String(text).match(pattern);
        return m ? Number(m[1]) : null;
    };
    result.totalFrames = num(/Total frames rendered:\s*(\d+)/);
    result.jankyFrames = num(/Janky frames:\s*(\d+)/);
    result.jankyPercent = num(/Janky frames:\s*\d+\s*\(([\d.]+)%\)/);
    result.p50 = num(/50th percentile:\s*(\d+)ms/);
    result.p90 = num(/90th percentile:\s*(\d+)ms/);
    result.p95 = num(/95th percentile:\s*(\d+)ms/);
    result.p99 = num(/99th percentile:\s*(\d+)ms/);
    result.missedVsync = num(/Number Missed Vsync:\s*(\d+)/);
    result.highInputLatency = num(/Number High input latency:\s*(\d+)/);
    result.slowUiThread = num(/Number Slow UI thread:\s*(\d+)/);
    result.slowBitmapUploads = num(/Number Slow bitmap uploads:\s*(\d+)/);
    result.slowIssueDrawCommands = num(/Number Slow issue draw commands:\s*(\d+)/);
    return result;
}

/** 坐标必须是非负整数，否则 input 会静默点到屏幕外。 */
function coord(value, key) {
    if (typeof value !== 'number' || !Number.isFinite(value)) {
        return { error: key + ' 需要数字（当前：' + String(value) + '）' };
    }
    const v = Math.trunc(value);
    if (v < 0) return { error: key + ' 不能为负（当前：' + v + '）' };
    if (v > 20000) return { error: key + ' 超出合理范围（当前：' + v + '）' };
    return { value: v };
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, Math.max(0, ms)));

export const deviceTool = {
    name: 'device',
    title: '真机联调',
    description:
        'adb 真机操作：list 枚举设备、install 装 debug 包、launch 启动、logcat 抓日志（可过滤 tag/关键字）、' +
        'screenshot 截图、shell 跑只读排查命令、uninstall 卸载；' +
        'reverse 端口映射（mock-jw 联调）、tap/swipe/text/key 输入、settings 读写调试开关、' +
        'perf 读 gfxinfo 帧率与卡顿（输出数字）。多设备时必须给 serial。',
    inputSchema: S.obj(
        '真机参数',
        {
            action: S.pick(
                '动作',
                [
                    'list', 'install', 'launch', 'stop', 'logcat', 'screenshot', 'shell', 'uninstall',
                    'reverse', 'tap', 'swipe', 'text', 'key', 'settings', 'perf',
                ],
                'list'
            ),
            serial: S.str('设备序列号；单设备时可省略'),
            apk: S.str('install 用的 APK 路径，默认 app/build/outputs/apk/debug/app-debug.apk'),
            component: S.str('launch 的组件，默认 <debug 包名>/.MainActivity'),
            command: S.str('shell 动作的命令（需命中白名单前缀）'),
            filter: S.str('logcat 的关键字或 tag 过滤（正则片段）'),
            lines: S.int('logcat 抓取行数（默认 300）', { default: 300 }),
            timeoutSec: S.int('logcat / 命令的超时秒数（默认 60）', { default: 60 }),
            reinstall: S.bool('install 时先卸载旧包（默认 false）', { default: false }),
            grant: S.bool('install 时授予全部运行时权限（默认 true）', { default: true }),
            // reverse
            reverseAction: S.pick('reverse 的子动作（默认 list）', ['list', 'add', 'remove', 'removeAll'], 'list'),
            localPort: S.int('reverse 的本地（电脑侧）端口，例如 mock-jw 的 8080'),
            remotePort: S.int('reverse 的设备侧端口；省略时与 localPort 相同'),
            // tap / swipe
            x: S.int('tap 的 x 坐标 / swipe 的起点 x'),
            y: S.int('tap 的 y 坐标 / swipe 的起点 y'),
            x2: S.int('swipe 的终点 x'),
            y2: S.int('swipe 的终点 y'),
            durationMs: S.int('swipe 时长毫秒（默认 300）', { default: 300 }),
            // text / key
            text: S.str('text 动作要输入的 ASCII 文本（input 命令不支持中文）'),
            key: S.str('key 动作：别名（back/home/enter/menu/volumeup…）或 KEYCODE_*'),
            // settings
            settingsAction: S.pick('settings 的子动作（默认 get）', ['get', 'put'], 'get'),
            namespace: S.pick('settings 命名空间（默认 global）', ['global', 'system', 'secure'], 'global'),
            settingsKey: S.str('settings 的键名，例如 animator_duration_scale'),
            settingsValue: S.str('settings put 的值，例如 0 / 1'),
            // perf
            pkg: S.str('perf 的包名；省略时用 debug 包名'),
            waitMs: S.int('perf 的采样时长毫秒（默认 5000）', { default: 5000 }),
        },
        []
    ),
    outputSchema: ENVELOPE,
    annotations: { readOnlyHint: false, openWorldHint: false, destructiveHint: false },
    async run(args) {
        const action = str(args, 'action', 'list');

        if (action === 'list') {
            const listed = await adb(['devices', '-l'], 20000);
            if (!listed.ok && !listed.stdout) return fail('adb devices 失败：' + (listed.output || '').trim());
            const devices = parseDevices(listed.stdout || '');
            return ok(
                devices.length === 0
                    ? '没有已连接设备（先开 USB 调试并授权）'
                    : devices.length + ' 台设备：' + devices.map((d) => d.serial + '(' + d.state + (d.model ? ',' + d.model : '') + ')').join('，'),
                { devices, debugApplicationId: debugApplicationId() }
            );
        }

        const picked = await pickSerial(str(args, 'serial', ''));
        if (picked.error) return fail(picked.error, { devices: picked.devices });
        const serial = picked.serial;

        if (action === 'install') {
            const apkRel = str(args, 'apk', null);
            const apkPath = apkRel ? path.resolve(repoRoot(), apkRel) : repoPath(...APK_DEBUG);
            const info = artifactInfo(apkPath);
            if (!info.exists) {
                return fail('APK 不存在：' + apkPath + '（先 build mode=debug）', { apk: info });
            }
            if (args && args.reinstall === true) {
                const pkg = debugApplicationId();
                await adb(['-s', serial, 'uninstall', pkg], 120000);
            }
            const installArgs = ['-s', serial, 'install', '-r'];
            if (!args || args.grant !== false) installArgs.push('-g');
            installArgs.push(apkPath);
            const result = await adb(installArgs, 300000);
            if (!result.ok || /Failure|Error/.test(result.stdout)) {
                return fail('安装失败：' + (result.output || '').trim().slice(-600), { apk: info, serial });
            }
            return ok('已安装到 ' + serial + ' · ' + info.mib + ' MiB', { apk: info, serial, output: result.stdout.trim() });
        }

        if (action === 'launch') {
            // 组件名不能靠包名拼：applicationIdSuffix 只改 applicationId，Activity 仍在
            // namespace（com.zhijian.schedule）下 —— 拼成 <appId>/.MainActivity 会报
            // "Activity class does not exist"。以设备上 resolve-activity 的结果为准。
            let component = str(args, 'component', null);
            let resolvedFrom = 'explicit';
            if (!component) {
                const pkg = debugApplicationId();
                const resolved = await adb(
                    ['-s', serial, 'shell', 'cmd', 'package', 'resolve-activity', '--brief', pkg],
                    30000
                );
                const line = (resolved.stdout || '')
                    .split(/\r?\n/)
                    .map((l) => l.trim())
                    .filter((l) => l.includes('/') && l.startsWith(pkg))
                    .pop();
                if (!line) {
                    return fail(
                        '解析不到 ' + pkg + ' 的启动组件（应用是否已安装？）',
                        { serial, output: (resolved.output || '').slice(-800) }
                    );
                }
                component = line;
                resolvedFrom = 'resolve-activity';
            }
            const result = await adb(['-s', serial, 'shell', 'am', 'start', '-n', component], 30000);
            const text = result.output || '';
            if (!result.ok || /Error type|does not exist|Error:/i.test(text)) {
                return fail('启动失败：' + text.trim().slice(-600), { component, serial, resolvedFrom });
            }
            return ok('已启动 ' + component + ' @ ' + serial, { component, serial, resolvedFrom, output: text.trim() });
        }

        if (action === 'stop') {
            const pkg = debugApplicationId();
            const result = await adb(['-s', serial, 'shell', 'am', 'force-stop', pkg], 30000);
            if (!result.ok) return fail('停止失败：' + (result.output || '').trim(), { pkg });
            return ok('已停止 ' + pkg, { pkg, serial });
        }

        if (action === 'uninstall') {
            const pkg = debugApplicationId();
            const result = await adb(['-s', serial, 'uninstall', pkg], 120000);
            return ok('已卸载 ' + pkg + '（' + (result.stdout || '').trim() + '）', { pkg, serial });
        }

        if (action === 'logcat') {
            const lines = args && Number.isFinite(args.lines) ? args.lines : 300;
            const timeoutSec = args && Number.isFinite(args.timeoutSec) ? args.timeoutSec : 60;
            const filter = str(args, 'filter', '');
            const logArgs = ['-s', serial, 'logcat', '-d', '-v', 'time', '-t', String(lines)];
            const result = await adb(logArgs, Math.max(10, timeoutSec) * 1000);
            let text = result.output || '';
            if (filter) {
                const needle = filter.toLowerCase();
                text = text
                    .split(/\r?\n/)
                    .filter((line) => line.toLowerCase().includes(needle))
                    .join('\n');
            }
            const outLines = text.split(/\r?\n/).filter(Boolean);
            return ok(
                'logcat ' + outLines.length + ' 行' + (filter ? '（过滤 ' + filter + '）' : '') + ' @ ' + serial,
                { serial, filter, lines: outLines.length, output: text.slice(-16000) }
            );
        }

        if (action === 'screenshot') {
            const dir = repoPath('.tmp', 'shots');
            mkdirSync(dir, { recursive: true });
            const stamp = new Date().toISOString().replace(/[:.]/g, '-');
            const remote = '/sdcard/zhijian-shot.png';
            const local = path.join(dir, 'shot-' + stamp + '.png');
            const shot = await adb(['-s', serial, 'shell', 'screencap', '-p', remote], 60000);
            if (!shot.ok) return fail('截图失败：' + (shot.output || '').trim());
            const pull = await adb(['-s', serial, 'pull', remote, local], 120000);
            if (!pull.ok || !existsSync(local)) return fail('拉取截图失败：' + (pull.output || '').trim());
            return ok('截图已保存：' + path.relative(repoRoot(), local).replace(/\\/g, '/'), {
                serial,
                file: local,
                relative: path.relative(repoRoot(), local).replace(/\\/g, '/'),
            });
        }

        if (action === 'shell') {
            const command = str(args, 'command', '');
            if (!command) return fail('shell 动作需要 command');
            const allowed = SHELL_PREFIXES.some((prefix) => command.trim().startsWith(prefix));
            if (!allowed) {
                return fail('命令不在白名单内：' + command, { allowedPrefixes: SHELL_PREFIXES });
            }
            const timeoutSec = args && Number.isFinite(args.timeoutSec) ? args.timeoutSec : 60;
            const result = await adb(['-s', serial, 'shell', command], Math.max(5, timeoutSec) * 1000);
            return ok(
                'shell 退出码 ' + result.exitCode + ' @ ' + serial,
                { serial, command, exitCode: result.exitCode, output: (result.output || '').slice(-12000) }
            );
        }

        if (action === 'reverse') {
            const sub = str(args, 'reverseAction', 'list');
            if (sub === 'list') {
                const result = await adb(['-s', serial, 'reverse', '--list'], 30000);
                const rows = parseReverseList(result.stdout || '');
                return ok(
                    rows.length === 0
                        ? '当前没有 reverse 映射 @ ' + serial
                        : rows.length + ' 条 reverse 映射 @ ' + serial + '：' +
                              rows.map((r) => 'tcp:' + r.local + '→tcp:' + r.remote).join('，'),
                    { serial, reverse: rows }
                );
            }
            if (sub === 'removeAll') {
                const result = await adb(['-s', serial, 'reverse', '--remove-all'], 30000);
                if (!result.ok) return fail('清除 reverse 失败：' + (result.output || '').trim(), { serial });
                return ok('已清除全部 reverse 映射 @ ' + serial, { serial });
            }
            const local = int(args, 'localPort', null);
            if (local === null) return fail('reverse ' + sub + ' 需要 localPort（电脑侧端口，例如 mock-jw 的 8080）');
            if (local < 1 || local > 65535) return fail('localPort 必须在 1-65535 之间（当前：' + local + '）');
            const remote = int(args, 'remotePort', local);
            if (remote < 1 || remote > 65535) return fail('remotePort 必须在 1-65535 之间（当前：' + remote + '）');
            const verb = sub === 'remove' ? '--remove' : 'tcp:' + remote;
            const result = await adb(['-s', serial, 'reverse', verb, 'tcp:' + local], 30000);
            if (!result.ok) return fail('reverse ' + sub + ' 失败：' + (result.output || '').trim(), { serial, local, remote });
            return ok(
                (sub === 'remove' ? '已移除' : '已建立') + ' reverse tcp:' + local + ' → 设备 tcp:' + remote + ' @ ' + serial,
                { serial, local, remote, action: sub, output: (result.output || '').trim() }
            );
        }

        if (action === 'tap') {
            const cx = coord(args ? args.x : undefined, 'x');
            if (cx.error) return fail(cx.error);
            const cy = coord(args ? args.y : undefined, 'y');
            if (cy.error) return fail(cy.error);
            const result = await adb(['-s', serial, 'shell', 'input', 'tap', String(cx.value), String(cy.value)], 30000);
            if (!result.ok) return fail('点击失败：' + (result.output || '').trim(), { serial, x: cx.value, y: cy.value });
            return ok('已点击 (' + cx.value + ', ' + cy.value + ') @ ' + serial, { serial, x: cx.value, y: cy.value });
        }

        if (action === 'swipe') {
            const x1 = coord(args ? args.x : undefined, 'x');
            if (x1.error) return fail(x1.error);
            const y1 = coord(args ? args.y : undefined, 'y');
            if (y1.error) return fail(y1.error);
            const x2 = coord(args ? args.x2 : undefined, 'x2');
            if (x2.error) return fail(x2.error);
            const y2 = coord(args ? args.y2 : undefined, 'y2');
            if (y2.error) return fail(y2.error);
            const duration = int(args, 'durationMs', 300, 1, 60000);
            const result = await adb(
                ['-s', serial, 'shell', 'input', 'swipe', String(x1.value), String(y1.value), String(x2.value), String(y2.value), String(duration)],
                30000
            );
            if (!result.ok) return fail('滑动失败：' + (result.output || '').trim(), { serial });
            return ok(
                '已滑动 (' + x1.value + ',' + y1.value + ') → (' + x2.value + ',' + y2.value + ') · ' + duration + 'ms @ ' + serial,
                { serial, from: { x: x1.value, y: y1.value }, to: { x: x2.value, y: y2.value }, durationMs: duration }
            );
        }

        if (action === 'text') {
            const value = str(args, 'text', '');
            if (!value) return fail('text 动作需要 text 参数');
            // input text 只吃 ASCII：非 ASCII 会被静默丢弃或变成空格，必须提前拒绝并说明替代方案。
            if (/[^\x20-\x7e]/.test(value)) {
                return fail('input text 只支持 ASCII 可见字符（当前含非 ASCII）：' + value, {
                    hint: '中文输入请改用 uiautomator 或让页面预置；本项目真机脚本只用 ASCII（路径 / 端口 / 搜索词拼音）',
                });
            }
            const escaped = value.replace(/ /g, '%s');
            const result = await adb(['-s', serial, 'shell', 'input', 'text', escaped], 30000);
            if (!result.ok) return fail('输入失败：' + (result.output || '').trim(), { serial, text: value });
            return ok('已输入 ' + value.length + ' 字符 @ ' + serial, { serial, text: value });
        }

        if (action === 'key') {
            const raw = str(args, 'key', '');
            if (!raw) return fail('key 动作需要 key 参数');
            const lower = raw.toLowerCase();
            const code = KEY_ALIASES[lower] || (raw.toUpperCase().startsWith('KEYCODE_') ? raw.toUpperCase() : null);
            if (!code) {
                return fail('未知按键：' + raw, { aliases: Object.keys(KEY_ALIASES), hint: '也可直接传 KEYCODE_* 形式' });
            }
            const result = await adb(['-s', serial, 'shell', 'input', 'keyevent', code], 30000);
            if (!result.ok) return fail('按键失败：' + (result.output || '').trim(), { serial, key: code });
            return ok('已按键 ' + code + ' @ ' + serial, { serial, key: code });
        }

        if (action === 'settings') {
            const sub = str(args, 'settingsAction', 'get');
            const ns = str(args, 'namespace', 'global');
            const key = str(args, 'settingsKey', '');
            if (!key) return fail('settings 动作需要 settingsKey');
            if (sub === 'get') {
                const result = await adb(['-s', serial, 'shell', 'settings', 'get', ns, key], 30000);
                const value = (result.stdout || '').trim();
                return ok(
                    ns + '/' + key + ' = ' + (value || '(空)') + ' @ ' + serial,
                    { serial, namespace: ns, key, value, nullValue: value === 'null' || value === '' }
                );
            }
            const value = str(args, 'settingsValue', null);
            if (value === null) return fail('settings put 需要 settingsValue');
            const id = ns + ':' + key;
            if (!SETTINGS_WRITABLE.has(id)) {
                return fail('settings 写入被拒绝：' + id + ' 不在可写白名单内', { writable: [...SETTINGS_WRITABLE] });
            }
            const result = await adb(['-s', serial, 'shell', 'settings', 'put', ns, key, value], 30000);
            if (!result.ok) return fail('settings put 失败：' + (result.output || '').trim(), { serial, id });
            // 回读确认：settings put 退出码 0 不代表值真的落了（部分厂商 ROM 会静默忽略）
            const back = await adb(['-s', serial, 'shell', 'settings', 'get', ns, key], 30000);
            const actual = (back.stdout || '').trim();
            return ok(
                ns + '/' + key + ' 已设为 ' + value + '（回读 ' + (actual || '(空)') + '）@ ' + serial,
                { serial, namespace: ns, key, value, readBack: actual, applied: actual === value }
            );
        }

        if (action === 'perf') {
            const pkg = str(args, 'pkg', null) || debugApplicationId();
            const waitMs = int(args, 'waitMs', 5000, 0, 600000);
            const reset = await adb(['-s', serial, 'shell', 'dumpsys', 'gfxinfo', pkg, 'reset'], 30000);
            if (!reset.ok) return fail('gfxinfo reset 失败：' + (reset.output || '').trim(), { serial, pkg });
            if (waitMs > 0) await sleep(waitMs);
            const read = await adb(['-s', serial, 'shell', 'dumpsys', 'gfxinfo', pkg], 60000);
            const stats = parseGfxinfo(read.stdout || '');
            if (stats.totalFrames === null) {
                return fail('没解析到帧数据（应用是否在运行？）', {
                    serial, pkg, waitMs, output: (read.stdout || '').slice(-800),
                });
            }
            // reset 之后一帧都没画：gfxinfo 仍会打印 p50/p90/p99，但那是上一轮采样遗留的
            // 分位数，与本次无关。先短路成"空闲"，不给假数字。
            if (stats.totalFrames === 0) {
                // 分位数是上一轮采样的遗留值，一并抹掉，避免调用方误读成"本次 p50=4950ms"。
                const idleStats = Object.assign({}, stats, {
                    jankyPercent: null, p50: null, p90: null, p95: null, p99: null,
                });
                return ok(
                    pkg + ' 在 ' + waitMs + 'ms 采样窗口内没有绘制任何帧（页面静止）· ' +
                        '要测帧率请在采样期间持续操作页面（tap/swipe）',
                    { serial, pkg, waitMs, reliable: false, idle: true, stats: idleStats }
                );
            }
            const janky = stats.jankyPercent === null ? '?' : stats.jankyPercent + '%';
            // 帧数太少时百分比没有统计意义：采样窗口内页面静止就是 3 帧、卡顿 100%，
            // 这种数字拿去对比改动前后会得出完全错误的结论，必须显式标注不可信。
            const reliable = stats.totalFrames >= 60;
            const caveat = reliable
                ? ''
                : '（仅 ' + stats.totalFrames + ' 帧，样本不足 60 帧，卡顿率不可用于前后对比；' +
                  '把 waitMs 调大并在采样期间持续操作页面）';
            return ok(
                pkg + ' · ' + stats.totalFrames + ' 帧 · 卡顿 ' + janky +
                    ' · p50 ' + stats.p50 + 'ms / p90 ' + stats.p90 + 'ms / p99 ' + stats.p99 + 'ms' + caveat,
                { serial, pkg, waitMs, reliable, stats }
            );
        }

        return fail('未知 action：' + action);
    },
};
