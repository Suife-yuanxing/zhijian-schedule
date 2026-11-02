/**
 * git —— 状态 / 差异 / 提交。
 *
 * 仓库硬约定（CONTRIBUTING.md §5）：
 *  · 永远不要 git add -A / git add . —— 因此本工具的 stage 只接受显式文件列表；
 *  · 提交信息 <type>(<scope>): 中文描述；
 *  · 不自动 push（push 需要用户明确指令，本工具不提供）。
 */

import { S } from '../schema.mjs';
import { ENVELOPE, ok, fail, str, bool, strList, int } from './common.mjs';
import { run } from '../exec.mjs';
import { repoRoot } from '../project.mjs';

const TYPES = ['feat', 'fix', 'docs', 'test', 'chore', 'style', 'refactor', 'perf', 'build', 'ci'];
const COMMIT_PATTERN = /^(feat|fix|docs|test|chore|style|refactor|perf|build|ci)(\([a-z0-9_-]+\))?: \S/;

function git(args, timeoutMs) {
    return run({ command: 'git', args, cwd: repoRoot(), timeoutMs: timeoutMs || 60000 });
}

export const gitTool = {
    name: 'git',
    title: 'Git 状态与提交',
    description:
        '仓库 git 操作。action=status 看工作区（含未跟踪），diff 看改动，stage 只接受显式文件列表' +
        '（禁止 git add -A），commit 校验 <type>(<scope>): 中文描述 格式，log 看最近提交。' +
        '本工具不做 push。',
    inputSchema: S.obj(
        'Git 参数',
        {
            action: S.pick('动作', ['status', 'diff', 'stage', 'commit', 'log', 'show'], 'status'),
            files: S.arr('stage 的文件（相对仓库根，必须显式列出）', S.str('如 app/build.gradle.kts')),
            message: S.str('commit 的提交信息，需符合 <type>(<scope>): 中文描述'),
            type: S.pick('提交类型（生成 message 时用）', TYPES, 'feat'),
            scope: S.str('提交 scope，如 ui / jw / update / db'),
            description: S.str('提交描述（中文），与 type/scope 一起拼出 message'),
            staged: S.bool('diff 时只看已暂存（默认 false）', { default: false }),
            limit: S.int('log 条数（默认 10）', { default: 10 }),
            ref: S.str('show 的 ref，默认 HEAD'),
        },
        []
    ),
    outputSchema: ENVELOPE,
    annotations: { readOnlyHint: false, openWorldHint: false, destructiveHint: false },
    async run(args) {
        const action = str(args, 'action', 'status');

        if (action === 'status') {
            const status = await git(['status', '--porcelain=v1', '--branch']);
            if (!status.ok) return fail('git status 失败：' + status.output.trim());
            const lines = status.stdout.split(/\r?\n/).filter(Boolean);
            const branchLine = lines.find((l) => l.startsWith('##')) || '';
            const entries = lines.filter((l) => !l.startsWith('##')).map((l) => ({
                code: l.slice(0, 2).trim(),
                path: l.slice(3).trim(),
            }));
            const dirty = entries.length;
            return ok(
                (branchLine.replace('## ', '') || '未知分支') + ' · ' + dirty + ' 个改动' +
                (dirty ? '（' + entries.slice(0, 6).map((e) => e.code + ' ' + e.path).join('，') + (dirty > 6 ? ' …' : '') + '）' : ''),
                { branch: branchLine.replace('## ', ''), entries, count: dirty }
            );
        }

        if (action === 'diff') {
            const staged = args && args.staged === true;
            const diff = await git(staged ? ['diff', '--cached', '--stat'] : ['diff', '--stat']);
            const full = await git(staged ? ['diff', '--cached'] : ['diff']);
            const text = full.output || '';
            return ok(
                (staged ? '暂存区' : '工作区') + ' 差异：' + (diff.stdout.trim().split(/\r?\n/).pop() || '无改动'),
                {
                    staged,
                    stat: diff.stdout.trim(),
                    patch: text.length > 20000 ? text.slice(0, 20000) + '\n…（已截断）' : text,
                    truncated: text.length > 20000,
                }
            );
        }

        if (action === 'stage') {
            const files = strList(args, 'files');
            if (files.length === 0) {
                return fail('stage 必须显式给出 files 列表（本仓库禁止 git add -A / git add .）');
            }
            const result = await git(['add', '--'].concat(files));
            if (!result.ok) return fail('git add 失败：' + result.output.trim(), { files });
            const after = await git(['status', '--porcelain=v1']);
            return ok('已暂存 ' + files.length + ' 个文件', { files, status: after.stdout.trim() });
        }

        if (action === 'commit') {
            let message = str(args, 'message', '');
            if (!message) {
                const type = str(args, 'type', 'feat');
                const scope = str(args, 'scope', '');
                const description = str(args, 'description', '');
                if (!description) return fail('commit 需要 message，或同时给出 type + description');
                message = type + (scope ? '(' + scope + ')' : '') + ': ' + description;
            }
            if (!COMMIT_PATTERN.test(message)) {
                return fail(
                    '提交信息不符合 <type>(<scope>): 中文描述 —— ' + message,
                    { allowedTypes: TYPES, example: 'feat(update): 条目级增量更新链路' }
                );
            }
            const stagedList = await git(['diff', '--cached', '--name-only']);
            const stagedFiles = stagedList.stdout.split(/\r?\n/).filter(Boolean);
            if (stagedFiles.length === 0) return fail('暂存区为空，先 stage 明确列出的文件');
            const result = await git(['commit', '-m', message]);
            if (!result.ok) return fail('git commit 失败：' + result.output.trim(), { message });
            const head = await git(['log', '-1', '--oneline']);
            return ok('已提交：' + head.stdout.trim(), {
                message,
                stagedFiles,
                output: result.output.trim(),
            });
        }

        if (action === 'log') {
            const limit = args && Number.isFinite(args.limit) ? args.limit : 10;
            const result = await git(['log', '-n', String(limit), '--oneline', '--decorate']);
            if (!result.ok) return fail('git log 失败：' + result.output.trim());
            return ok('最近 ' + limit + ' 条提交', { output: result.stdout.trim() });
        }

        if (action === 'show') {
            const ref = str(args, 'ref', 'HEAD');
            const result = await git(['show', '--stat', '--patch', ref]);
            const text = result.output || '';
            return ok('显示 ' + ref, {
                ref,
                patch: text.length > 24000 ? text.slice(0, 24000) + '\n…（已截断）' : text,
                truncated: text.length > 24000,
            });
        }

        return fail('未知 action：' + action);
    },
};
