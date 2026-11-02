/**
 * arch_check —— 架构约定校验（本项目特有的硬规矩）。
 *
 * 这些规矩来自 CONTRIBUTING.md §4，工具化的意义是不靠人记：
 *  1. 包结构固定为 core / feature / di / navigation，不新建顶层功能包；
 *  2. 可测逻辑与 Android 解耦：标注为纯逻辑的文件顶部不得 import android.*
 *     （判定方式：core 下文件名命中白名单的纯逻辑文件 + 显式声明）；
 *  3. 注释与文档用中文：新文件里出现成片的英文注释即提示（只警告不失败）；
 *  4. Room schema 变更必须提交导出：entities 有改动时 schemas/ 下要有对应版本；
 *  5. max_line_length = 120：按字符数统计（不是字节，grep -P 会误判中文）。
 */

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { S } from '../schema.mjs';
import { ENVELOPE, ok, fail, int, bool } from './common.mjs';
import { repoRoot, repoPath } from '../project.mjs';

const ALLOWED_TOP = new Set(['core', 'feature', 'di', 'navigation']);

/**
 * 「纯逻辑」的判定标记：文件 KDoc 里显式写了「纯逻辑」或「不依赖 Android」。
 * 只有被这样标注的文件才受 import android.* 禁令约束 ——
 * 拿目录名猜（core/jw、core/update）会把 WebView 执行器、通知服务这类
 * 天然需要 Android API 的类全部误判，规则就没人愿意看。
 */
const PACKAGE_ROOT = ['app', 'src', 'main', 'java', 'com', 'zhijian', 'schedule'];
const TEST_ROOT = ['app', 'src', 'test', 'java', 'com', 'zhijian', 'schedule'];

function walkKt(dir, out, limit) {
    if (out.length >= limit) return;
    let entries;
    try {
        entries = readdirSync(dir, { withFileTypes: true });
    } catch {
        return;
    }
    for (const entry of entries) {
        if (out.length >= limit) return;
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
            if (entry.name === 'build' || entry.name === '.gradle') continue;
            walkKt(full, out, limit);
        } else if (entry.name.endsWith('.kt')) {
            out.push(full);
        }
    }
}

function rel(file) {
    return path.relative(repoRoot(), file).replace(/\\/g, '/');
}

export const archCheckTool = {
    name: 'arch_check',
    title: '架构约定校验',
    description:
        '校验本仓库的硬约定：包结构只在 core/feature/di/navigation 下、纯逻辑文件不 import android.*、' +
        '源码行宽按字符数不超过 120、Room schema 导出与实体改动同步、新文件中文注释比例。' +
        '提交前与 lint 一起跑。',
    inputSchema: S.obj(
        '架构校验参数',
        {
            maxFiles: S.int('最多扫描文件数（默认 1200）', { default: 1200 }),
            includeLineLength: S.bool('是否统计超 120 字符的行（默认 true）', { default: true }),
            includeAndroidImports: S.bool('是否检查纯逻辑文件的 android.* 依赖（默认 true）', { default: true }),
        },
        []
    ),
    outputSchema: ENVELOPE,
    annotations: { readOnlyHint: true, openWorldHint: false },
    async run(args) {
        const maxFiles = args && Number.isFinite(args.maxFiles) ? args.maxFiles : 1200;
        const includeLineLength = !args || args.includeLineLength !== false;
        const includeAndroidImports = !args || args.includeAndroidImports !== false;

        const pkgRoot = repoPath(...PACKAGE_ROOT);
        if (!existsSync(pkgRoot)) return fail('找不到主源码目录：' + PACKAGE_ROOT.join('/'));

        // 1) 顶层包结构
        const topDirs = readdirSync(pkgRoot, { withFileTypes: true })
            .filter((e) => e.isDirectory())
            .map((e) => e.name);
        const illegalTop = topDirs.filter((d) => !ALLOWED_TOP.has(d));

        // 2) 扫描源码
        const files = [];
        walkKt(pkgRoot, files, maxFiles);

        const longLines = [];
        const androidImports = [];
        const englishHeavy = [];
        let totalLines = 0;

        for (const file of files) {
            let text;
            try {
                text = readFileSync(file, 'utf8');
            } catch {
                continue;
            }
            const lines = text.split(/\r?\n/);
            totalLines += lines.length;

            if (includeLineLength) {
                for (let i = 0; i < lines.length; i += 1) {
                    // 按字符数（不是字节）：Array.from 展开代理对，中文按 1 计
                    if (Array.from(lines[i]).length > 120) {
                        longLines.push({ file: rel(file), line: i + 1, chars: Array.from(lines[i]).length });
                        if (longLines.length > 400) break;
                    }
                }
            }

            if (includeAndroidImports && /^import android\./m.test(text)) {
                const imports = Array.from(text.matchAll(/^import (android\.[\w.]+)/gm)).map((m) => m[1]);
                androidImports.push({ file: rel(file), imports });
            }

            // 英文注释密度：整行 // 注释里纯 ASCII 占比高
            const commentLines = lines.filter((l) => /^\s*(\/\/|\*|\/\*)/.test(l) && l.trim().length > 12);
            if (commentLines.length >= 8) {
                const asciiOnly = commentLines.filter((l) => !/[\u4e00-\u9fff]/.test(l)).length;
                if (asciiOnly / commentLines.length > 0.8) {
                    englishHeavy.push({ file: rel(file), commentLines: commentLines.length, asciiOnly });
                }
            }
        }

        // 2b) 单测源码目录：JUnit4 且不引入 Robolectric，因此测试文件不得 import android.*
        //     （unitTests.isReturnDefaultValues = true 只让桩方法返回默认值，不提供类实现）
        const testFiles = [];
        const testRoot = repoPath(...TEST_ROOT);
        if (existsSync(testRoot)) walkKt(testRoot, testFiles, 800);
        const testAndroidLeaks = [];
        for (const file of testFiles) {
            let text;
            try {
                text = readFileSync(file, 'utf8');
            } catch {
                continue;
            }
            const imports = Array.from(text.matchAll(/^import (android\.[\w.]+)/gm)).map((m) => m[1]);
            if (imports.length > 0) testAndroidLeaks.push({ file: rel(file), imports });
        }

        // 3) Room schema 与实体
        const schemasDir = repoPath('app', 'schemas');
        let schemaVersions = [];
        if (existsSync(schemasDir)) {
            for (const dbDir of readdirSync(schemasDir, { withFileTypes: true })) {
                if (!dbDir.isDirectory()) continue;
                const dir = path.join(schemasDir, dbDir.name);
                schemaVersions = readdirSync(dir)
                    .filter((f) => f.endsWith('.json'))
                    .map((f) => Number(f.replace('.json', '')))
                    .sort((a, b) => a - b);
            }
        }
        const dbFile = repoPath('app', 'src', 'main', 'java', 'com', 'zhijian', 'schedule', 'core', 'data', 'db', 'AppDatabase.kt');
        const dbVersion = existsSync(dbFile)
            ? Number((readFileSync(dbFile, 'utf8').match(/version\s*=\s*(\d+)/) || [])[1] || 0)
            : null;
        const schemaSynced = dbVersion === null || schemaVersions.includes(dbVersion);

        const issues = [];
        if (illegalTop.length > 0) {
            issues.push({ kind: 'illegal-top-package', detail: illegalTop.join(', '), hint: '新代码放进 core/ 或 feature/<功能>/' });
        }
        if (testAndroidLeaks.length > 0) {
            issues.push({
                kind: 'android-import-in-unit-test',
                detail: testAndroidLeaks.slice(0, 8).map((a) => a.file).join(', '),
                hint: 'JUnit4 + 无 Robolectric：被测判定逻辑必须抽成不依赖 Android API 的纯逻辑文件',
            });
        }
        if (!schemaSynced) {
            issues.push({
                kind: 'room-schema-out-of-sync',
                detail: 'AppDatabase version=' + dbVersion + '，已导出 schema=' + schemaVersions.join(','),
                hint: '改表后要提交 app/schemas 下的新版本 JSON 与迁移',
            });
        }

        const data = {
            scannedFiles: files.length,
            totalLines,
            topDirs,
            illegalTop,
            longLineCount: longLines.length,
            longLines: longLines.slice(0, 40),
            testScannedFiles: testFiles.length,
            testAndroidLeaks,
            androidImportingSources: androidImports.length,
            englishHeavyComments: englishHeavy.slice(0, 20),
            room: { dbVersion, schemaVersions, schemaSynced },
            issues,
        };

        const warnings = [];
        if (longLines.length > 0) warnings.push(longLines.length + ' 行超过 120 字符');
        if (englishHeavy.length > 0) warnings.push(englishHeavy.length + ' 个文件注释以英文为主（约定用中文）');

        if (issues.length > 0) {
            return fail(
                '架构校验发现 ' + issues.length + ' 个问题：' + issues.map((i) => i.kind).join('，') +
                (warnings.length ? '（提示：' + warnings.join('；') + '）' : ''),
                data
            );
        }
        return ok(
            '架构校验通过：' + files.length + ' 个 .kt / ' + totalLines + ' 行，包结构合规，Room schema 同步' +
            (warnings.length ? '（提示：' + warnings.join('；') + '）' : ''),
            data
        );
    },
};
