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
 *
 * 设备选择：serial 为空时取第一台 device 状态的设备；多台设备时必须显式指定。
 */

import { existsSync, mkdirSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { S } from '../schema.mjs';
import { ENVELOPE, ok, fail, str, int, bool, strList } from './common.mjs';
import { run } from '../exec.mjs';
import { repoRoot, repoPath, adbPath, debugApplicationId, artifactInfo } from '../project.mjs';

/** adb shell 允许的前缀（只读或低风险的排查命令）。 */
const SHELL_PREFIXES = [
    'pm ', 'dumpsys ', 'getprop', 'settings get', 'wm ', 'am start', 'am force-stop',
    'cmd ', 'logcat', 'ls ', 'cat /proc/', 'input keyevent', 'screencap',
];

const APK_DEBUG = ['app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'];

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

export const deviceTool = {
    name: 'device',
    title: '真机联调',
    description:
        'adb 真机操作：list 枚举设备、install 装 debug 包、launch 启动、logcat 抓日志（可过滤 tag/关键字）、' +
        'screenshot 截图、shell 跑只读排查命令、uninstall 卸载。多设备时必须给 serial。',
    inputSchema: S.obj(
        '真机参数',
        {
            action: S.pick('动作', ['list', 'install', 'launch', 'stop', 'logcat', 'screenshot', 'shell', 'uninstall'], 'list'),
            serial: S.str('设备序列号；单设备时可省略'),
            apk: S.str('install 用的 APK 路径，默认 app/build/outputs/apk/debug/app-debug.apk'),
            component: S.str('launch 的组件，默认 <debug 包名>/.MainActivity'),
            command: S.str('shell 动作的命令（需命中白名单前缀）'),
            filter: S.str('logcat 的关键字或 tag 过滤（正则片段）'),
            lines: S.int('logcat 抓取行数（默认 300）', { default: 300 }),
            timeoutSec: S.int('logcat / 命令的超时秒数（默认 60）', { default: 60 }),
            reinstall: S.bool('install 时先卸载旧包（默认 false）', { default: false }),
            grant: S.bool('install 时授予全部运行时权限（默认 true）', { default: true }),
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

        return fail('未知 action：' + action);
    },
};
