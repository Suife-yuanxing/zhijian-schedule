/**
 * env —— 开发环境体检。
 *
 * 一次把「能不能构建、能不能上真机、版本号是多少、SDK 在哪」问清楚，
 * 避免后续每个工具各查一半。
 */

import { existsSync } from 'node:fs';
import path from 'node:path';
import { S } from '../schema.mjs';
import { ENVELOPE, ok, fail } from './common.mjs';
import { run } from '../exec.mjs';
import { snapshot, adbPath, repoPath } from '../project.mjs';

async function adbDevices() {
    const adb = await adbPath();
    if (!adb) return { available: false, devices: [] };
    const result = await run({ command: adb, args: ['devices', '-l'], timeoutMs: 20000 });
    if (!result.ok) return { available: true, devices: [], error: result.output };
    const devices = [];
    for (const line of result.stdout.split(/\r?\n/)) {
        const text = line.trim();
        if (!text || text.startsWith('List of devices')) continue;
        const [serial, state, ...rest] = text.split(/\s+/);
        devices.push({
            serial,
            state,
            model: (rest.join(' ').match(/model:(\S+)/) || [])[1] || null,
            product: (rest.join(' ').match(/product:(\S+)/) || [])[1] || null,
            android: (rest.join(' ').match(/device:(\S+)/) || [])[1] || null,
        });
    }
    return { available: true, devices };
}

async function javaVersion(javaHome) {
    if (!javaHome) return null;
    const exe = path.join(javaHome, 'bin', process.platform === 'win32' ? 'java.exe' : 'java');
    if (!existsSync(exe)) return null;
    const result = await run({ command: exe, args: ['-version'], timeoutMs: 20000 });
    const text = (result.stderr || result.stdout || '').split(/\r?\n/)[0] || '';
    return text.trim() || null;
}

export const envTool = {
    name: 'env',
    title: '开发环境体检',
    description:
        '报告本仓库的构建环境：JDK、Android SDK、Gradle wrapper、adb 设备、Python、包名与版本号，' +
        '以及中文路径编码修复是否生效。开工前先跑一次，避免在缺 SDK / 缺设备的条件下瞎试。',
    inputSchema: S.obj('环境体检参数（无）', {
        withDevices: S.bool('是否顺带枚举 adb 设备（默认 true）', { default: true }),
    }),
    outputSchema: ENVELOPE,
    annotations: { readOnlyHint: true, openWorldHint: false },
    async run(args) {
        const withDevices = args && args.withDevices === false ? false : true;
        const snap = await snapshot();
        if (!snap.looksLikeRepo) {
            return fail('当前目录不像纸间课表仓库：找不到 settings.gradle.kts 或 app/build.gradle.kts', snap);
        }
        const jdkVersion = await javaVersion(snap.javaHome);
        const devices = withDevices ? await adbDevices() : { available: Boolean(snap.adb), devices: [] };
        const data = Object.assign({}, snap, {
            javaVersion: jdkVersion,
            adbDevices: devices.devices,
            adbAvailable: devices.available,
            keystoreProperties: existsSync(repoPath('keystore.properties')),
        });
        const problems = [];
        if (!snap.gradlew) problems.push('缺 gradlew');
        if (!snap.androidSdkExists) problems.push('local.properties 的 sdk.dir 不可用');
        if (!snap.javaHome) problems.push('找不到 JDK');
        if (!snap.adb) problems.push('找不到 adb');
        if (!snap.fileEncodingCompat) problems.push('gradle.properties 缺 -Dfile.encoding=COMPAT（中文路径下单元测试会挂）');
        const head =
            '仓库 ' + snap.repoRoot + ' · ' + snap.versionName + ' (' + snap.versionCode + ')' +
            ' · 模块 ' + snap.modules.join(',') +
            ' · 设备 ' + (devices.devices.length ? devices.devices.map((d) => d.serial + '(' + d.state + ')').join(',') : '无');
        const tail = problems.length ? ' · 待修：' + problems.join('；') : ' · 环境齐全';
        return ok(head + tail, Object.assign(data, { problems }));
    },
};
