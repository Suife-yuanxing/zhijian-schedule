/**
 * docs_check —— 文档一致性检查。
 *
 * 仓库约定（CONTRIBUTING.md §6 + docs/README.md）：
 *  · docs/ 分 research / plans / specs / testing / adr 五类；
 *  · 新增或移动文档后必须同步 docs/README.md 索引；
 *  · 相对链接不得失效；
 *  · plans/X.md 与 specs/X-design.md 成对。
 *
 * 检查项：
 *  1. docs/README.md 里出现过的每个相对链接目标是否存在；
 *  2. docs/ 下每个 .md 是否在 README 索引里出现过（adr 例外，按 ADR 索引查）；
 *  3. plans 与 specs 的成对关系；
 *  4. 各文档内部指向仓库内文件的相对链接是否失效。
 */

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { S } from '../schema.mjs';
import { ENVELOPE, ok, fail, bool, int } from './common.mjs';
import { repoRoot, repoPath } from '../project.mjs';

/** 收集目录下所有 .md（递归）。 */
function listMarkdown(dir, depth) {
    if (depth > 4 || !existsSync(dir)) return [];
    const out = [];
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
        const full = path.join(dir, entry.name);
        if (entry.isDirectory()) {
            out.push(...listMarkdown(full, depth + 1));
        } else if (entry.name.endsWith('.md')) {
            out.push(full);
        }
    }
    return out;
}

/**
 * 剥掉围栏代码块与行内代码。
 * 文档里常出现 `[x](\\d{1,2})` 这类正则示例，若不剥离会被当成失效链接误报。
 */
function stripCode(text) {
    return text
        .replace(/^```[\s\S]*?^```/gm, '')
        .replace(/```[\s\S]*?```/g, '')
        .replace(/`[^`\n]*`/g, '');
}

/** 抽出 markdown 里的相对链接目标（忽略 http / mailto / 纯锚点）。 */
function extractLinks(rawText) {
    const text = stripCode(rawText);
    const links = [];
    const regex = /\]\(([^)\s]+)(?:\s+"[^"]*")?\)/g;
    let match;
    while ((match = regex.exec(text)) !== null) {
        const target = match[1];
        if (/^(https?:|mailto:|#|tel:)/i.test(target)) continue;
        links.push(target.split('#')[0]);
    }
    return links;
}

function rel(file) {
    return path.relative(repoRoot(), file).replace(/\\/g, '/');
}

export const docsTool = {
    name: 'docs_check',
    title: '文档一致性检查',
    description:
        '检查 docs/ 的索引与链接：README 索引里的相对链接是否有效、docs 下每篇文档是否被索引、' +
        'plans 与 specs 是否成对、文档内指向仓库文件的相对链接是否失效。新增或移动文档后跑一次。',
    inputSchema: S.obj(
        '文档检查参数',
        {
            strict: S.bool('严格模式：未被索引的文档也算失败（默认 true）', { default: true }),
            maxIssues: S.int('最多返回问题条数（默认 80）', { default: 80 }),
        },
        []
    ),
    outputSchema: ENVELOPE,
    annotations: { readOnlyHint: true, openWorldHint: false },
    async run(args) {
        const strict = !args || args.strict !== false;
        const maxIssues = args && Number.isFinite(args.maxIssues) ? args.maxIssues : 80;
        const docsRoot = repoPath('docs');
        if (!existsSync(docsRoot)) return fail('找不到 docs/ 目录');

        const issues = [];
        const readme = repoPath('docs', 'README.md');
        if (!existsSync(readme)) return fail('找不到 docs/README.md');
        const readmeText = readFileSync(readme, 'utf8');

        // 1) README 索引里的链接是否有效
        const indexed = new Set();
        for (const link of extractLinks(readmeText)) {
            const target = path.resolve(path.dirname(readme), link);
            if (existsSync(target)) {
                indexed.add(path.resolve(target));
            } else {
                issues.push({ kind: 'broken-link', where: 'docs/README.md', target: link });
            }
        }

        // 2) docs 下每篇 md 是否被索引
        const allDocs = listMarkdown(docsRoot, 0).filter((f) => path.resolve(f) !== path.resolve(readme));
        const adrIndex = repoPath('docs', 'adr', 'README.md');
        const adrText = existsSync(adrIndex) ? readFileSync(adrIndex, 'utf8') : '';
        const adrIndexed = new Set();
        for (const link of extractLinks(adrText)) {
            adrIndexed.add(path.resolve(path.dirname(adrIndex), link));
        }
        // 「被索引」的三种合法形态：
        //   1. 该文件本身在主索引里被链接；
        //   2. 它是 adr 且被 docs/adr/README.md 索引；
        //   3. 它位于一个已被主索引链接的目录下（如液态玻璃研究包，主索引只链到其 README，
        //      包内七篇由包内 README 自己组织 —— 要求主索引逐篇列出会让索引退化成文件清单）。
        const indexedDirs = [];
        for (const link of extractLinks(readmeText)) {
            const target = path.resolve(path.dirname(readme), link);
            if (existsSync(target)) {
                try {
                    if (statSync(target).isDirectory()) indexedDirs.push(target);
                } catch {
                    /* 忽略不可读路径 */
                }
            }
        }
        const unindexed = [];
        for (const doc of allDocs) {
            const resolved = path.resolve(doc);
            if (indexed.has(resolved)) continue;
            if (resolved.startsWith(path.resolve(repoPath('docs', 'adr'))) && adrIndexed.has(resolved)) continue;
            if (path.basename(doc) === 'README.md') continue;
            if (indexedDirs.some((dir) => resolved.startsWith(dir + path.sep))) continue;
            unindexed.push(rel(doc));
        }
        if (strict) {
            for (const file of unindexed) issues.push({ kind: 'unindexed', where: file });
        }

        // 3) plans 与 specs 成对
        const plansDir = repoPath('docs', 'plans');
        const specsDir = repoPath('docs', 'specs');
        const planFiles = existsSync(plansDir) ? readdirSync(plansDir).filter((f) => f.endsWith('.md')) : [];
        const specFiles = existsSync(specsDir) ? readdirSync(specsDir).filter((f) => f.endsWith('.md')) : [];
        const unpaired = [];
        for (const plan of planFiles) {
            const base = plan.replace(/\.md$/, '');
            const expected = base + '-design.md';
            if (!specFiles.includes(expected)) unpaired.push({ plan: 'docs/plans/' + plan, expectedSpec: 'docs/specs/' + expected });
        }

        // 4) 文档内部相对链接
        let brokenInner = 0;
        for (const doc of allDocs) {
            let text;
            try {
                text = readFileSync(doc, 'utf8');
            } catch {
                continue;
            }
            for (const link of extractLinks(text)) {
                if (link.startsWith('/')) continue;
                const target = path.resolve(path.dirname(doc), link);
                if (!existsSync(target)) {
                    brokenInner += 1;
                    if (issues.length < maxIssues) {
                        issues.push({ kind: 'broken-inner-link', where: rel(doc), target: link });
                    }
                }
            }
        }

        const data = {
            docsCount: allDocs.length,
            indexedCount: indexed.size,
            unindexed,
            unpaired,
            brokenInnerLinks: brokenInner,
            issues: issues.slice(0, maxIssues),
            issueCount: issues.length,
        };

        if (issues.length === 0) {
            return ok(
                '文档检查通过：' + allDocs.length + ' 篇文档，索引完整，无失效链接' +
                (unpaired.length ? '（' + unpaired.length + ' 个 plan 无对应 design，仅提示）' : ''),
                data
            );
        }
        return fail(
            '文档检查发现 ' + issues.length + ' 个问题：' +
            issues.slice(0, 5).map((i) => i.kind + ' ' + i.where).join('，') +
            (issues.length > 5 ? ' …' : ''),
            data
        );
    },
};
