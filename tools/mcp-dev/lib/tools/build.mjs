/**
 * build —— 构建与产物。
 *
 * mode:
 *   compile  :app:compileDebugKotlin            （改完 Kotlin 的第一道闸，最快）
 *   debug   :app:assembleDebug                  （出可安装 debug APK）
 *   release :app:assembleRelease                （无 keystore.properties 时回落 debug 签名）
 *   clean    clean + 目标模式
 *   custom  任意 gradle 任务（任务名白名单校验，防手滑）
 *
 * 设计要点：
 *  · 一律用仓库里的 gradlew（Windows 是 gradlew.bat），不依赖全局 gradle；
 *  · 任务名先做白名单校验：只允许本仓库真实存在的任务族，避免拼错任务白等 10 分钟；
 *  · release 构建前先看 keystore.properties，没有就在结果里明说「本次是 debug 签名」。
 */

import { existsSync, rmSync } from 'node:fs';
import path from 'node:path';
import { S } from '../schema.mjs';
import { ENVELOPE, ok, fail, str, bool, mib, secs } from './common.mjs';
import { run } from '../exec.mjs';
import { repoRoot, repoPath, gradlewPath, artifactInfo, appVersion } from '../project.mjs';

/**
 * 可自动修复的构建期故障。
 *
 * KSP 的增量缓存（app/build/kspCaches）在中断构建、并行写盘、或 .kt 被外部工具重写后
 * 会报 "Storage corrupted ... lookups.tab_i"，此时删掉缓存目录重跑即可 —— 这是
 * 项目里真实会遇到的故障（本机实测），值得让工具自己处理，而不是把一屏堆栈丢给人。
 */
const REPAIRABLE = [
    {
        pattern: /Storage corrupted|symbolLookups.*lookups\.tab/i,
        label: 'KSP 增量缓存损坏',
        clean: ['app', 'build', 'kspCaches'],
    },
    {
        pattern: /Could not delete|Unable to delete directory|Directory not empty/i,
        label: '构建输出目录被占用',
        clean: ['app', 'build', 'intermediates'],
    },
];

/** 删一个仓库内目录（带越界保护）。 */
function cleanDir(segments) {
    const target = repoPath(...segments);
    const root = path.resolve(repoRoot());
    if (!path.resolve(target).startsWith(root)) return { cleaned: false, path: target };
    if (!existsSync(target)) return { cleaned: false, path: target, reason: '目录不存在' };
    try {
        rmSync(target, { recursive: true, force: true, maxRetries: 3, retryDelay: 200 });
        return { cleaned: true, path: target };
    } catch (error) {
        return { cleaned: false, path: target, reason: String(error && error.message ? error.message : error) };
    }
}

/** 允许的任务名模式：:app:<任务> 或根任务。只放行本仓库实际用到的族。 */
const ALLOWED_TASK = /^(?::app:)?(assemble|compile|bundle|lint|test|ktlint|detekt|clean|dependencies|tasks|projects|help)[A-Za-z]*$/;

const MODE_TASKS = {
    compile: [':app:compileDebugKotlin'],
    debug: [':app:assembleDebug'],
    release: [':app:assembleRelease'],
    bundle: [':app:bundleRelease'],
};

const APK_PATHS = {
    debug: ['app', 'build', 'outputs', 'apk', 'debug', 'app-debug.apk'],
    release: ['app', 'build', 'outputs', 'apk', 'release', 'app-release.apk'],
};

export const buildTool = {
    name: 'build',
    title: 'Gradle 构建',
    description:
        '用仓库自带 gradlew 构建纸间课表。mode=compile 做编译检查（最快），debug 出 debug APK，' +
        'release 出正式包，clean 先清理，custom 跑指定任务（如 :app:ktlintCheck）。' +
        '返回退出码、耗时与 APK 体积；失败时给出裁剪后的错误输出。',
    inputSchema: S.obj(
        '构建参数',
        {
            mode: S.pick('构建模式', ['compile', 'debug', 'release', 'bundle', 'clean', 'custom'], 'compile'),
            tasks: S.arr('mode=custom 时的 Gradle 任务列表', S.str('任务名，如 :app:ktlintCheck')),
            extraArgs: S.arr('追加给 gradle 的参数', S.str('如 --offline / -PincludeX86=true / --stacktrace')),
            timeoutSec: S.int('超时秒数（默认 900，上限 3600）', { default: 900 }),
            offline: S.bool('是否加 --offline（默认 false）', { default: false }),
        },
        []
    ),
    outputSchema: ENVELOPE,
    annotations: { readOnlyHint: false, openWorldHint: false, destructiveHint: false },
    async run(args) {
        const gradlew = gradlewPath();
        if (!gradlew) return fail('找不到 gradlew：' + repoRoot() + ' 不是有效的 Gradle 仓库');

        const mode = str(args, 'mode', 'compile');
        const offline = bool(args, 'offline', false);
        const timeoutMs = Math.max(30, Math.min(3600, args && Number.isFinite(args.timeoutSec) ? args.timeoutSec : 900)) * 1000;
        const extraArgs = Array.isArray(args && args.extraArgs) ? args.extraArgs.filter((a) => typeof a === 'string') : [];

        let tasks;
        if (mode === 'custom') {
            const requested = Array.isArray(args && args.tasks) ? args.tasks.filter((t) => typeof t === 'string') : [];
            if (requested.length === 0) return fail('mode=custom 需要提供 tasks 列表');
            const bad = requested.filter((t) => !ALLOWED_TASK.test(t.trim()));
            if (bad.length > 0) {
                return fail('任务名不在白名单内：' + bad.join(', '), {
                    hint: '允许 assemble*/compile*/bundle*/lint*/test*/ktlint*/detekt*/clean/dependencies/tasks/projects/help',
                });
            }
            tasks = requested.map((t) => t.trim());
        } else if (mode === 'clean') {
            tasks = ['clean'].concat(MODE_TASKS.compile);
        } else {
            tasks = MODE_TASKS[mode];
            if (!tasks) return fail('未知 mode：' + mode);
        }

        const gradleArgs = tasks.concat(['--console=plain']);
        if (offline) gradleArgs.push('--offline');
        gradleArgs.push(...extraArgs);

        let result = await run({
            command: gradlew,
            args: gradleArgs,
            cwd: repoRoot(),
            timeoutMs,
        });

        // 命中可自动修复的故障：清缓存后重跑一次，并在结果里说明修了什么。
        const repairs = [];
        if (!result.ok) {
            for (const rule of REPAIRABLE) {
                if (!rule.pattern.test(result.output || '')) continue;
                const cleaned = cleanDir(rule.clean);
                repairs.push({ fault: rule.label, path: rule.clean.join('/'), cleaned: cleaned.cleaned, reason: cleaned.reason || null });
                if (cleaned.cleaned) {
                    result = await run({ command: gradlew, args: gradleArgs, cwd: repoRoot(), timeoutMs });
                }
                break;
            }
        }

        const artifacts = [];
        if (mode === 'debug' || mode === 'release' || mode === 'clean') {
            const keys = mode === 'clean' ? ['debug', 'release'] : [mode];
            for (const key of keys) {
                const info = artifactInfo(repoPath(...APK_PATHS[key]));
                if (info.exists) artifacts.push(Object.assign({ variant: key }, info));
            }
        }
        const version = appVersion();
        const signedWith = mode === 'release'
            ? (existsSync(repoPath('keystore.properties')) ? 'release 密钥' : 'debug 密钥（无 keystore.properties）')
            : null;

        const data = {
            mode,
            tasks,
            gradleArgs,
            exitCode: result.exitCode,
            timedOut: result.timedOut,
            durationMs: result.durationMs,
            artifacts,
            versionCode: version.versionCode,
            versionName: version.versionName,
            signedWith,
            repairs,
            output: result.output.length > 12000 ? result.output.slice(-12000) : result.output,
        };

        if (!result.ok) {
            const reason = result.timedOut ? '超时被杀' : '退出码 ' + result.exitCode;
            return fail('构建失败（' + tasks.join(' ') + '）：' + reason + '，耗时 ' + secs(result.durationMs), data);
        }

        const apkText = artifacts.length
            ? ' · 产物 ' + artifacts.map((a) => a.path.split(/[\\/]/).pop() + ' ' + mib(a.bytes)).join('，')
            : '';
        const repairText = repairs.length
            ? ' · 已自动修复：' + repairs.map((r) => r.fault + (r.cleaned ? '（已清 ' + r.path + '）' : '（清理失败）')).join('，')
            : '';
        return ok(
            '构建成功（' + tasks.join(' ') + '）· ' + secs(result.durationMs) + apkText + repairText,
            data
        );
    },
};
