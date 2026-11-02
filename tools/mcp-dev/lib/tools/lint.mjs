/**
 * lint —— 静态检查与格式。
 *
 * action:
 *   check      ktlintCheck + detekt（CI 用的那条）
 *   ktlint     ktlintCheck
 *   detekt     detekt
 *   format     ktlintFormat（就地改文件，结果里列出被改动的文件）
 *   baseline   重新生成 ktlint / detekt baseline（会改 baseline 文件，慎用）
 *
 * 违规明细一律读工具自己写出的报告文件，不解析控制台：
 *  · ktlint 报告：app/build/reports/ktlint/<task>/<task>.txt（控制台带 ANSI 颜色码，且任务名随源集变化）
 *  · detekt 报告：app/build/reports/detekt/detekt.xml / detekt.txt
 *
 * 仓库约定：存量违例冻结在 baseline 里（config/ktlint/baseline.xml、config/detekt/baseline.xml），
 * 所以「报告里有违规」不等于「本次改动引入违规」—— 结果里把命中 baseline 的数量单列，
 * 剩下的才算新违规。
 */

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { S } from '../schema.mjs';
import { ENVELOPE, ok, fail, str, bool, secs } from './common.mjs';
import { run } from '../exec.mjs';
import { repoRoot, repoPath, gradlewPath } from '../project.mjs';

const ACTIONS = {
    check: [':app:ktlintCheck', ':app:detekt'],
    ktlint: [':app:ktlintCheck'],
    detekt: [':app:detekt'],
    format: [':app:ktlintFormat'],
    baseline: [':app:ktlintGenerateBaseline', ':app:detektBaseline'],
};

const ANSI = /\u001b\[[0-9;]*m/g;

/**
 * 读 ktlint 报告（剥掉 ANSI 颜色码）。
 *
 * 两个必须做的过滤，否则数字会虚高：
 *  1. 报告目录按任务名分（ktlintMainSourceSetCheck / ktlintDebugSourceSetCheck / …Format），
 *     只读本次真正跑过的 Check 任务；
 *  2. 只认本次执行之后写出的报告 —— 上一轮遗留的报告会把历史违规算进本次结果。
 */
function readKtlintReports(sinceMs, tasks) {
    const root = repoPath('app', 'build', 'reports', 'ktlint');
    const violations = [];
    let fresh = false;
    let newest = 0;
    if (!existsSync(root)) return { violations, fresh, files: 0 };
    // 报告目录名是「源集级」的（ktlintMainSourceSetCheck / ktlintTestSourceSetCheck），
    // 而 Gradle 任务是聚合的 :app:ktlintCheck —— 两者不同名，因此按任务族匹配：
    // 跑 ktlintCheck 就收全部 *Check 报告，跑 detekt 不收 ktlint 报告。
    const wantsKtlint = tasks.some((t) => /ktlint/i.test(t));
    if (!wantsKtlint) return { violations, fresh: false, files: 0 };
    const wantsFormat = tasks.some((t) => /ktlintFormat/i.test(t));
    let files = 0;
    for (const task of readdirSync(root, { withFileTypes: true })) {
        if (!task.isDirectory()) continue;
        if (wantsFormat ? !/Format$/.test(task.name) : !/Check$/.test(task.name)) continue;
        const dir = path.join(root, task.name);
        for (const entry of readdirSync(dir)) {
            if (!entry.endsWith('.txt')) continue;
            const full = path.join(dir, entry);
            let text;
            let mtimeMs = 0;
            try {
                text = readFileSync(full, 'utf8');
                mtimeMs = statSync(full).mtimeMs;
            } catch {
                continue;
            }
            newest = Math.max(newest, mtimeMs);
            if (sinceMs > 0 && mtimeMs < sinceMs - 5000) continue; // 上一轮的残留报告
            files += 1;
            for (const raw of text.split(/\r?\n/)) {
                const line = raw.replace(ANSI, '').trim();
                if (!line) continue;
                if (!/\.kt:\d+:\d+:/.test(line)) continue;
                violations.push(line);
            }
        }
    }
    fresh = sinceMs > 0 && newest >= sinceMs - 5000;
    return { violations, fresh, files, newest };
}

/** 读 detekt 的 XML 报告（结构化，含 rule 与 file）。 */
function readDetektReport() {
    const candidates = ['detekt.xml', 'detekt-main.xml'];
    const root = repoPath('app', 'build', 'reports', 'detekt');
    if (!existsSync(root)) return { violations: [], fresh: false, newest: 0 };
    let newest = 0;
    const violations = [];
    for (const name of readdirSync(root)) {
        if (!name.endsWith('.xml')) continue;
        const full = path.join(root, name);
        let text;
        try {
            text = readFileSync(full, 'utf8');
            newest = Math.max(newest, statSync(full).mtimeMs);
        } catch {
            continue;
        }
        const regex = /<file name="([^"]+)">([\s\S]*?)<\/file>/g;
        let fileMatch;
        while ((fileMatch = regex.exec(text)) !== null) {
            const file = fileMatch[1];
            const body = fileMatch[2];
            // 注意：detekt 写出的自闭合标签在 '/>' 前带空格（source="…" />），
            // 少一个 \s* 就会整份报告解析成 0 条。
            const errorRegex = /<error\s+line="(\d+)"\s+column="(\d+)"\s+severity="([^"]+)"\s+message="([^"]*)"\s+source="([^"]*)"\s*\/>/g;
            let errorMatch;
            while ((errorMatch = errorRegex.exec(body)) !== null) {
                violations.push({
                    file,
                    line: Number(errorMatch[1]),
                    column: Number(errorMatch[2]),
                    severity: errorMatch[3],
                    message: errorMatch[4],
                    rule: errorMatch[5].replace(/^detekt\./, ''),
                });
            }
        }
    }
    return { violations, fresh: newest > 0, newest };
}

/** 读 baseline 里登记的 ID，用来区分「存量」与「新增」。 */
function baselineIds() {
    const ids = new Set();
    for (const rel of [['config', 'ktlint', 'baseline.xml'], ['config', 'detekt', 'baseline.xml']]) {
        const file = repoPath(...rel);
        if (!existsSync(file)) continue;
        let text;
        try {
            text = readFileSync(file, 'utf8');
        } catch {
            continue;
        }
        for (const m of text.matchAll(/<ID>([\s\S]*?)<\/ID>/g)) {
            ids.add(m[1].replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>').trim());
        }
    }
    return ids;
}

/** detekt 违规是否在 baseline 里：baseline ID 形如 Rule:File.kt$签名。 */
function inDetektBaseline(violation, ids) {
    const base = path.basename(violation.file);
    for (const id of ids) {
        if (!id.startsWith(violation.rule + ':' + base + '$')) continue;
        return true;
    }
    return false;
}

export const lintTool = {
    name: 'lint',
    title: '静态检查与格式',
    description:
        '跑 ktlint 与 detekt。action=check 是 CI 那条；format 就地格式化并列出改动文件；' +
        'baseline 重写 config/*/baseline.xml（只在整体收紧规则时用）。违规读报告文件而非控制台，' +
        '并把命中 baseline 的存量与本次新增分开统计。',
    inputSchema: S.obj(
        '静态检查参数',
        {
            action: S.pick('动作', ['check', 'ktlint', 'detekt', 'format', 'baseline'], 'check'),
            timeoutSec: S.int('超时秒数（默认 900，上限 3600）', { default: 900 }),
            offline: S.bool('是否加 --offline', { default: false }),
            maxViolations: S.int('最多返回违规条数（默认 40）', { default: 40 }),
        },
        []
    ),
    outputSchema: ENVELOPE,
    annotations: { readOnlyHint: false, openWorldHint: false, destructiveHint: false },
    async run(args) {
        const gradlew = gradlewPath();
        if (!gradlew) return fail('找不到 gradlew');
        const action = str(args, 'action', 'check');
        const tasks = ACTIONS[action];
        if (!tasks) return fail('未知 action：' + action);
        const timeoutMs = Math.max(30, Math.min(3600, args && Number.isFinite(args.timeoutSec) ? args.timeoutSec : 900)) * 1000;
        const maxViolations = args && Number.isFinite(args.maxViolations) ? args.maxViolations : 40;
        const gradleArgs = tasks.concat(['--console=plain']);
        if (args && args.offline === true) gradleArgs.push('--offline');

        const startedAt = Date.now();
        const result = await run({ command: gradlew, args: gradleArgs, cwd: repoRoot(), timeoutMs });

        const ktlint = readKtlintReports(startedAt, tasks);
        const detekt = readDetektReport();
        const ids = baselineIds();
        const newDetekt = detekt.violations.filter((v) => !inDetektBaseline(v, ids));

        const data = {
            action,
            tasks,
            exitCode: result.exitCode,
            timedOut: result.timedOut,
            durationMs: result.durationMs,
            ktlint: {
                total: ktlint.violations.length,
                reportFresh: ktlint.fresh,
                samples: ktlint.violations.slice(0, maxViolations),
            },
            detekt: {
                total: detekt.violations.length,
                newCount: newDetekt.length,
                samples: newDetekt.slice(0, maxViolations).map(
                    (v) => path.basename(v.file) + ':' + v.line + ':' + v.column + ' ' + v.message + ' [' + v.rule + ']'
                ),
            },
            output: (result.output || '').length > 6000 ? (result.output || '').slice(-6000) : result.output || '',
        };

        const detail =
            'ktlint ' + ktlint.violations.length + ' 条' +
            '，detekt ' + detekt.violations.length + ' 条（其中新增 ' + newDetekt.length + '）';

        if (!result.ok) {
            const reason = result.timedOut ? '超时被杀' : '退出码 ' + result.exitCode;
            return fail('静态检查失败（' + tasks.join(' ') + '）：' + reason + ' · ' + detail + ' · ' + secs(result.durationMs), data);
        }
        return ok('静态检查通过（' + tasks.join(' ') + '）· ' + detail + ' · ' + secs(result.durationMs), data);
    },
};
