/**
 * jw_scripts —— 教务脚本仓库（scripts/）的维护。
 *
 * 背景（scripts/README.md + docs/research/2026-10-25-script-engine-notes.md）：
 *  App 的「脚本管理 → 检查更新」拉 scripts/index.json，按 sha256 校验后安装脚本；
 *  改了任何 .js 都必须重算哈希、递增 version，否则客户端不会更新。
 *
 * action:
 *   list     列 index.json 里的脚本（id / version / hosts / 文件是否存在）
 *   verify   重算每个脚本的真实 sha256 与 index.json 比对，报出失配
 *   sync     把真实 sha256 写回 index.json（可选 bump 版本号）
 *   lint     脚本 manifest 头部与 fetchSchedule 契约的静态检查
 */

import { createHash } from 'node:crypto';
import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { S } from '../schema.mjs';
import { ENVELOPE, ok, fail, str, bool, strList } from './common.mjs';
import { repoRoot, repoPath } from '../project.mjs';

const INDEX_REL = 'scripts/index.json';

function readIndex() {
    const file = repoPath('scripts', 'index.json');
    if (!existsSync(file)) return { error: '找不到 ' + INDEX_REL };
    try {
        return { index: JSON.parse(readFileSync(file, 'utf8')), file };
    } catch (error) {
        return { error: 'index.json 解析失败：' + String(error && error.message ? error.message : error) };
    }
}

/** 从 index 的 url 反推本地文件名（.../scripts/xxx.js -> scripts/xxx.js）。 */
function localScriptPath(url) {
    const name = String(url || '').split('/').pop();
    if (!name || !name.endsWith('.js')) return null;
    return repoPath('scripts', name);
}

function sha256File(file) {
    try {
        return createHash('sha256').update(readFileSync(file)).digest('hex');
    } catch {
        return null;
    }
}

/**
 * 静态检查脚本契约。
 *
 * 真实格式（scripts/*.js 实测）：manifest 是文件头注释里的一行标记 + 紧随其后的 JSON：
 *   /**
 *    * PAPER_SCRIPT_MANIFEST
 *    * { "id": "...", "version": 1, ... }
 *    *
 * 入口是 globalThis.__paperScheduleScript = { fetchSchedule: async function (ctx) { ... } }
 */
function lintScript(text, indexEntry) {
    const problems = [];

    const marker = text.indexOf('PAPER_SCRIPT_MANIFEST');
    if (marker < 0) {
        problems.push('缺 PAPER_SCRIPT_MANIFEST 头部');
    } else {
        const after = text.slice(marker);
        const braceStart = after.indexOf('{');
        const braceEnd = after.indexOf('}', braceStart);
        if (braceStart < 0 || braceEnd < 0) {
            problems.push('manifest 后没有 JSON 对象');
        } else {
            const jsonText = after.slice(braceStart, braceEnd + 1)
                .split(/\r?\n/)
                .map((line) => line.replace(/^\s*\*\s?/, ''))
                .join('\n');
            try {
                const parsed = JSON.parse(jsonText);
                for (const key of ['id', 'name', 'version', 'apiVersion']) {
                    if (parsed[key] === undefined) problems.push('manifest 缺 ' + key);
                }
                if (indexEntry) {
                    if (parsed.id !== indexEntry.id) problems.push('manifest.id 与 index.json 不一致（' + parsed.id + ' vs ' + indexEntry.id + '）');
                    if (parsed.version !== indexEntry.version) {
                        problems.push('manifest.version ' + parsed.version + ' 与 index.json 的 ' + indexEntry.version + ' 不一致');
                    }
                }
            } catch (error) {
                problems.push('manifest JSON 解析失败：' + String(error && error.message ? error.message : error));
            }
        }
    }

    if (!/__paperScheduleScript\s*=/.test(text)) problems.push('缺 globalThis.__paperScheduleScript 挂载');
    if (!/fetchSchedule\s*[:=]/.test(text)) problems.push('缺 fetchSchedule(ctx)');
    if (/\beval\s*\(/.test(text)) problems.push('使用了 eval（脚本在页面上下文执行，禁止）');
    if (/document\.write\s*\(/.test(text)) problems.push('使用了 document.write');
    if (!/warnings\s*[:=]/.test(text) && !/courses\s*[:=]/.test(text)) problems.push('未见返回信封字段（courses / warnings）');
    return problems;
}

export const jwScriptsTool = {
    name: 'jw_scripts',
    title: '教务脚本仓库维护',
    description:
        '维护 scripts/index.json 与 scripts/*.js。list 看清单，verify 重算 sha256 并报失配，' +
        'sync 把真实哈希写回（bump=true 同时递增 version），lint 静态检查 manifest 与 fetchSchedule 契约。',
    inputSchema: S.obj(
        '脚本仓库参数',
        {
            action: S.pick('动作', ['list', 'verify', 'sync', 'lint'], 'list'),
            ids: S.arr('只处理指定脚本 id（空表示全部）', S.str('脚本 id，如 qiangzhi-jsxsd')),
            bump: S.bool('sync 时递增 version（默认 false）', { default: false }),
            dryRun: S.bool('sync 时只报告不写盘（默认 true）', { default: true }),
        },
        []
    ),
    outputSchema: ENVELOPE,
    annotations: { readOnlyHint: false, openWorldHint: false, destructiveHint: false },
    async run(args) {
        const action = str(args, 'action', 'list');
        const loaded = readIndex();
        if (loaded.error) return fail(loaded.error);
        const index = loaded.index;
        const onlyIds = strList(args, 'ids');
        const entries = (index.scripts || []).filter((s) => onlyIds.length === 0 || onlyIds.includes(s.id));
        if (entries.length === 0) return fail('index.json 里没有匹配的脚本', { ids: onlyIds });

        if (action === 'list') {
            const rows = entries.map((s) => {
                const local = localScriptPath(s.url);
                return {
                    id: s.id,
                    name: s.name,
                    version: s.version,
                    hosts: s.hosts || [],
                    local: local ? path.relative(repoRoot(), local).replace(/\\/g, '/') : null,
                    localExists: local ? existsSync(local) : false,
                };
            });
            return ok(
                '共 ' + rows.length + ' 个脚本，本地缺 ' + rows.filter((r) => !r.localExists).length + ' 个',
                { indexVersion: index.indexVersion, scripts: rows }
            );
        }

        if (action === 'verify' || action === 'sync') {
            const dryRun = action === 'verify' ? true : (args && args.dryRun === undefined ? true : args.dryRun === true);
            const bump = args && args.bump === true;
            const report = [];
            let changed = 0;
            for (const script of entries) {
                const local = localScriptPath(script.url);
                if (!local || !existsSync(local)) {
                    report.push({ id: script.id, status: 'missing-local', expected: script.sha256 });
                    continue;
                }
                const actual = sha256File(local);
                const match = actual === script.sha256;
                if (!match) changed += 1;
                report.push({
                    id: script.id,
                    status: match ? 'match' : 'mismatch',
                    expected: script.sha256,
                    actual,
                    version: script.version,
                    nextVersion: match || !bump ? script.version : script.version + 1,
                });
                if (!match && !dryRun) {
                    script.sha256 = actual;
                    if (bump) script.version = script.version + 1;
                }
            }
            if (!dryRun && changed > 0) {
                writeFileSync(loaded.file, JSON.stringify(index, null, 2) + '\n', 'utf8');
            }
            const verb = dryRun ? '校验' : '已写回';
            const tail = changed === 0
                ? '全部哈希一致'
                : changed + ' 个脚本哈希失配' + (dryRun ? '（dryRun，未写盘；确认后传 dryRun=false 落盘）' : '，' + verb + ' index.json');
            return ok(verb + '：' + tail, { dryRun, bump, changed, scripts: report });
        }

        if (action === 'lint') {
            const results = [];
            for (const script of entries) {
                const local = localScriptPath(script.url);
                if (!local || !existsSync(local)) {
                    results.push({ id: script.id, problems: ['本地文件不存在'] });
                    continue;
                }
                const text = readFileSync(local, 'utf8');
                results.push({
                    id: script.id,
                    bytes: Buffer.byteLength(text, 'utf8'),
                    lines: text.split(/\r?\n/).length,
                    problems: lintScript(text, script),
                });
            }
            const bad = results.filter((r) => r.problems.length > 0);
            return ok(
                bad.length === 0
                    ? '脚本契约检查通过（' + results.length + ' 个）'
                    : bad.length + ' 个脚本有问题：' + bad.map((b) => b.id + '(' + b.problems.join('/') + ')').join('，'),
                { scripts: results }
            );
        }

        return fail('未知 action：' + action);
    },
};
