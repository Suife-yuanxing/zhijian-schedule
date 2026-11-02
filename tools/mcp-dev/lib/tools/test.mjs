/**
 * test —— 单元测试。
 *
 * 两条通道：
 *   gradle : ./gradlew testDebugUnitTest（CI 跑的就是这条，支持 --tests 过滤）
 *   direct : tools/run_unit_tests.py（绕开 Gradle Worker 的中文路径 @argfile 编码坑，
 *            由 python 直接起 JVM 跑 JUnitCore）
 *
 * 结果判定一律读 Gradle 的 JUnit XML 报告（app/build/test-results/testDebugUnitTest/*.xml），
 * 不解析控制台文本 —— 「UP-TO-DATE」只说明任务被跳过，不代表测试通过，
 * 而 --rerun-tasks 之后控制台又不一定打印计数。XML 是唯一权威来源。
 *
 * 默认 auto：先 gradle，命中编码类失败特征时自动回落 direct，并把回落原因写进结果。
 */

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { S } from '../schema.mjs';
import { ENVELOPE, ok, fail, str, bool, strList, secs } from './common.mjs';
import { run } from '../exec.mjs';
import { repoRoot, repoPath, gradlewPath, pythonPath } from '../project.mjs';

const ENCODING_FAILURE = /ClassNotFoundException|initializationError|Could not write standard input|GradleWorkerMain/;
const REPORT_DIR = ['app', 'build', 'test-results', 'testDebugUnitTest'];

/** 读 Gradle 的 JUnit XML 报告，聚合 tests / failures / errors / skipped。 */
function readReports(sinceMs, classFilter) {
    const dir = repoPath(...REPORT_DIR);
    if (!existsSync(dir)) return { available: false, reports: [], total: 0, failed: 0, skipped: 0, fresh: false };
    const reports = [];
    let total = 0;
    let failed = 0;
    let skipped = 0;
    let fresh = false;
    let newest = 0;

    for (const entry of readdirSync(dir)) {
        if (!entry.endsWith('.xml')) continue;
        const full = path.join(dir, entry);
        let text;
        try {
            text = readFileSync(full, 'utf8');
            newest = Math.max(newest, statSync(full).mtimeMs);
        } catch {
            continue;
        }
        const suite = text.match(/<testsuite\b[^>]*>/);
        if (!suite) continue;
        const attr = (name) => {
            const m = suite[0].match(new RegExp(name + '="([^"]*)"'));
            return m ? m[1] : null;
        };
        const name = attr('name') || entry.replace(/^TEST-/, '').replace(/\.xml$/, '');
        if (classFilter.length > 0 && !classFilter.some((c) => name === c || name.startsWith(c))) continue;
        const tests = Number(attr('tests') || 0);
        const failures = Number(attr('failures') || 0);
        const errors = Number(attr('errors') || 0);
        const skips = Number(attr('skipped') || 0);
        total += tests;
        failed += failures + errors;
        skipped += skips;
        const caseNames = Array.from(text.matchAll(/<testcase\b[^>]*name="([^"]*)"/g)).map((m) => m[1]);
        const failureMessages = Array.from(text.matchAll(/<(?:failure|error)\b[^>]*message="([^"]*)"/g))
            .map((m) => m[1])
            .slice(0, 10);
        reports.push({ suite: name, tests, failures: failures + errors, skipped: skips, cases: caseNames.length, failureMessages });
    }
    // 判定「本次生成」：报告 mtime 不早于本次 gradle 启动时间（留 5s 容差，避免文件系统时间戳精度问题）
    fresh = sinceMs > 0 && newest >= sinceMs - 5000;
    return { available: true, reports, total, failed, skipped, fresh, newest };
}

/**
 * 展示用裁剪：保留头（构建结论 / JUnit 汇总常在开头）与尾（错误堆栈常在结尾）。
 * 解析必须用全文 —— JUnitCore 会把 "OK (N tests)" 打在最前面，只留尾巴会把它切掉。
 */
function trimForDisplay(text, headBytes = 4000, tailBytes = 8000) {
    if (text.length <= headBytes + tailBytes) return text;
    const omitted = text.length - headBytes - tailBytes;
    const marker = '\n… [' + omitted + ' 字符已省略] …\n';
    return text.slice(0, headBytes) + marker + text.slice(-tailBytes);
}

/** 控制台里抓真正的失败行（只用于给人看的上下文）。 */
function failureLines(text) {
    return text
        .split(/\r?\n/)
        .filter((line) => /FAILED|AssertionError|expected:|What went wrong|> Task .* FAILED|Caused by:/.test(line))
        .slice(0, 25);
}

async function runGradleTests(classes, timeoutMs, extraArgs) {
    const gradlew = gradlewPath();
    if (!gradlew) return { skipped: true, reason: '找不到 gradlew' };
    const args = [':app:testDebugUnitTest', '--console=plain'];
    for (const cls of classes) args.push('--tests', cls);
    for (const extra of extraArgs) args.push(extra);
    const startedAt = Date.now();
    const result = await run({ command: gradlew, args, cwd: repoRoot(), timeoutMs });
    return { skipped: false, result, startedAt };
}

async function runDirectTests(classes, timeoutMs) {
    const python = await pythonPath();
    if (!python) return { skipped: true, reason: '找不到 python' };
    const args = ['tools/run_unit_tests.py'];
    if (classes.length === 0) args.push('--all');
    else args.push(...classes);
    const startedAt = Date.now();
    const result = await run({ command: python, args, cwd: repoRoot(), timeoutMs });
    return { skipped: false, result, startedAt };
}

/** direct 通道没有 XML，只能解析 JUnitCore 的控制台输出。 */
function parseJUnitCore(text) {
    const okLine = text.match(/^OK \((\d+) tests?\)/m);
    if (okLine) return { total: Number(okLine[1]), failed: 0, skipped: 0 };
    const runLine = text.match(/^Tests run: (\d+),\s*Failures: (\d+)/m);
    if (runLine) {
        const total = Number(runLine[1]);
        const failed = Number(runLine[2]);
        return { total, failed, skipped: 0 };
    }
    return null;
}

export const testTool = {
    name: 'test',
    title: '单元测试',
    description:
        '跑 JUnit4 单元测试。channel=auto 先走 Gradle，命中中文路径 @argfile 编码失败时自动回落到 ' +
        'tools/run_unit_tests.py。结果以 Gradle 的 JUnit XML 报告为准（控制台 UP-TO-DATE 不代表通过）。' +
        'classes 为空表示全量；extraArgs 可传 --rerun-tasks 强制重跑。',
    inputSchema: S.obj(
        '测试参数',
        {
            classes: S.arr('限定测试类全名，如 com.zhijian.schedule.core.jw.ExamParserTest', S.str('全限定类名')),
            channel: S.pick('执行通道', ['auto', 'gradle', 'direct'], 'auto'),
            timeoutSec: S.int('超时秒数（默认 900，上限 3600）', { default: 900 }),
            extraArgs: S.arr('追加给 gradle 测试任务的参数（如 --rerun-tasks 强制重跑）', S.str('gradle 参数')),
            list: S.bool('只列出已编译的测试类，不执行', { default: false }),
        },
        []
    ),
    outputSchema: ENVELOPE,
    annotations: { readOnlyHint: false, openWorldHint: false, destructiveHint: false },
    async run(args) {
        const classes = strList(args, 'classes');
        const channel = str(args, 'channel', 'auto');
        const timeoutSec = args && Number.isFinite(args.timeoutSec) ? args.timeoutSec : 900;
        const timeoutMs = Math.max(30, Math.min(3600, timeoutSec)) * 1000;
        const extraArgs = Array.isArray(args && args.extraArgs)
            ? args.extraArgs.filter((a) => typeof a === 'string' && a.startsWith('-'))
            : [];

        if (args && args.list === true) {
            const python = await pythonPath();
            if (!python) return fail('找不到 python，无法列出已编译测试类');
            const listed = await run({
                command: python,
                args: ['tools/run_unit_tests.py', '--list'],
                cwd: repoRoot(),
                timeoutMs: 60000,
            });
            const names = (listed.stdout.match(/^\s{2}\S+$/gm) || []).map((l) => l.trim());
            return ok('已编译测试类 ' + names.length + ' 个', { exitCode: listed.exitCode, classes: names, output: listed.output });
        }

        const runs = [];

        if (channel === 'gradle' || channel === 'auto') {
            let attempt = await runGradleTests(classes, timeoutMs, extraArgs);
            // Gradle 判定 UP-TO-DATE 时会跳过执行，XML 还是上一轮的：此时「通过」是历史结论。
            // 没有显式 --rerun-tasks 就自动补跑一次，保证返回的是本次真实结果。
            if (!attempt.skipped && attempt.result.ok && !extraArgs.includes('--rerun-tasks')) {
                const stale = readReports(attempt.startedAt, classes);
                if (!stale.available || !stale.fresh) {
                    const retry = await runGradleTests(classes, timeoutMs, extraArgs.concat(['--rerun-tasks']));
                    if (!retry.skipped) attempt = retry;
                }
            }
            if (!attempt.skipped) {
                const xml = readReports(attempt.startedAt, classes);
                const record = {
                    channel: 'gradle',
                    exitCode: attempt.result.exitCode,
                    ok: attempt.result.ok,
                    timedOut: attempt.result.timedOut,
                    durationMs: attempt.result.durationMs,
                    counts: xml.available ? { total: xml.total, failed: xml.failed, passed: xml.total - xml.failed, skipped: xml.skipped } : null,
                    reportFresh: xml.fresh,
                    reports: xml.reports,
                    failureLines: failureLines(attempt.result.output || ''),
                    output: trimForDisplay(attempt.result.output || ''),
                };
                runs.push(record);
                const encodingBroken = !record.ok && ENCODING_FAILURE.test(record.output);
                if (channel === 'gradle' || record.ok || !encodingBroken) {
                    return finish(runs);
                }
                runs.push({ channel: 'gradle-fallback', reason: '命中中文路径编码失败特征，回落 direct 通道' });
            } else {
                runs.push({ channel: 'gradle', skipped: true, reason: attempt.reason });
            }
        }

        const direct = await runDirectTests(classes, timeoutMs);
        if (direct.skipped) {
            runs.push({ channel: 'direct', skipped: true, reason: direct.reason });
            return fail('两条测试通道都不可用', { runs });
        }
        const counts = parseJUnitCore(direct.result.output || '');
        runs.push({
            channel: 'direct',
            exitCode: direct.result.exitCode,
            ok: direct.result.ok,
            timedOut: direct.result.timedOut,
            durationMs: direct.result.durationMs,
            counts: counts ? Object.assign({ passed: counts.total - counts.failed }, counts) : null,
            failureLines: failureLines(direct.result.output || ''),
            output: trimForDisplay(direct.result.output || ''),
        });
        return finish(runs);

        function finish(records) {
            const executed = records.filter((r) => typeof r.ok === 'boolean');
            const last = executed[executed.length - 1];
            const data = { channel, runs: records };
            if (!last) return fail('没有任何通道真正执行测试', data);

            const counts = last.counts;
            const countText = counts
                ? '通过 ' + counts.passed + '/' + counts.total + (counts.failed ? '，失败 ' + counts.failed : '')
                : '无测试报告';

            if (!last.ok) {
                const reason = last.timedOut ? '超时被杀' : '退出码 ' + last.exitCode;
                return fail('测试失败（' + last.channel + '）· ' + countText + ' · ' + reason + ' · ' + secs(last.durationMs), data);
            }
            if (!counts || counts.total === 0) {
                return fail(
                    '测试任务执行成功但没找到测试结果（' + last.channel + '）：可能被 UP-TO-DATE 跳过或过滤没命中',
                    Object.assign(data, {
                        hint: '传 extraArgs:["--rerun-tasks"] 强制重跑，或核对 classes 名称',
                    })
                );
            }
            if (last.channel === 'gradle' && last.reportFresh === false) {
                return fail(
                    '测试报告不是本次生成的（mtime 早于本次执行）：结果是历史数据，' + countText,
                    Object.assign(data, { hint: '传 extraArgs:["--rerun-tasks"] 强制重跑' })
                );
            }
            return ok('测试通过（' + last.channel + '）· ' + countText + ' · ' + secs(last.durationMs), data);
        }
    },
};
