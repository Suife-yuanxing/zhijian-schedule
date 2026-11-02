/**
 * map —— 仓库结构导航。
 *
 * 目的：让模型在改代码前先拿到「真实存在的包路径与文件清单」，而不是靠记忆猜。
 * 输出刻意做成扁平列表 + 分组计数，避免一次吐几万行。
 */

import { existsSync, readdirSync, statSync } from 'node:fs';
import path from 'node:path';
import { S } from '../schema.mjs';
import { ENVELOPE, ok, fail, str, int, bool } from './common.mjs';
import { repoRoot, repoPath, modules, appVersion } from '../project.mjs';

const SKIP_DIRS = new Set(['build', '.gradle', '.git', '.kotlin', 'node_modules', '.idea']);

/** 递归列文件，受 maxFiles 限制。 */
function walk(root, options) {
    const { maxFiles, onlyExt } = options;
    const files = [];
    const stack = [root];
    while (stack.length > 0 && files.length < maxFiles) {
        const dir = stack.pop();
        let entries;
        try {
            entries = readdirSync(dir, { withFileTypes: true });
        } catch {
            continue;
        }
        for (const entry of entries) {
            if (files.length >= maxFiles) break;
            const full = path.join(dir, entry.name);
            if (entry.isDirectory()) {
                if (SKIP_DIRS.has(entry.name)) continue;
                stack.push(full);
                continue;
            }
            if (onlyExt && !onlyExt.some((ext) => entry.name.endsWith(ext))) continue;
            files.push(full);
        }
    }
    return files;
}

/** 按包目录聚合 Kotlin 文件。 */
function groupByPackage(files) {
    const groups = new Map();
    for (const file of files) {
        const rel = path.relative(repoRoot(), file).replace(/\\/g, '/');
        const dir = path.posix.dirname(rel);
        if (!groups.has(dir)) groups.set(dir, []);
        groups.get(dir).push(path.posix.basename(rel));
    }
    return groups;
}

export const mapTool = {
    name: 'map',
    title: '仓库结构导航',
    description:
        '查看仓库真实结构。scope=tree 列顶层目录与关键文件，scope=packages 列 Kotlin 包与文件数，' +
        'scope=files 列指定目录下的文件，scope=module 看 app 模块的目录约定（core / feature / di / navigation），' +
        'scope=docs 列文档分类。改代码前用它确认真实路径。',
    inputSchema: S.obj(
        '导航参数',
        {
            scope: S.pick('视角', ['tree', 'packages', 'files', 'module', 'docs'], 'tree'),
            path: S.str('scope=files 时的相对路径，默认 app/src/main/java'),
            ext: S.arr('scope=files 时的扩展名过滤，如 .kt', S.str('扩展名')),
            maxFiles: S.int('最多返回文件数（默认 400）', { default: 400 }),
        },
        []
    ),
    outputSchema: ENVELOPE,
    annotations: { readOnlyHint: true, openWorldHint: false },
    async run(args) {
        const scope = str(args, 'scope', 'tree');
        const maxFiles = args && Number.isFinite(args.maxFiles) ? args.maxFiles : 400;
        const mods = modules();
        const version = appVersion();

        if (scope === 'tree') {
            const top = [];
            for (const entry of readdirSync(repoRoot(), { withFileTypes: true })) {
                if (SKIP_DIRS.has(entry.name)) continue;
                if (entry.name.startsWith('.') && entry.name !== '.github') continue;
                top.push((entry.isDirectory() ? 'd ' : 'f ') + entry.name);
            }
            const keyFiles = [
                'settings.gradle.kts',
                'build.gradle.kts',
                'app/build.gradle.kts',
                'gradle/libs.versions.toml',
                'gradle.properties',
                '.editorconfig',
                'README.md',
                'CONTRIBUTING.md',
                'CHANGELOG.md',
            ].filter((f) => existsSync(repoPath(f)));
            return ok(
                '根工程 ' + mods.rootProject + ' · 模块 ' + mods.modules.join(',') +
                ' · 版本 ' + version.versionName + ' (' + version.versionCode + ')',
                { repoRoot: repoRoot(), entries: top, keyFiles }
            );
        }

        if (scope === 'packages') {
            const srcRoot = repoPath('app', 'src', 'main', 'java');
            const files = walk(srcRoot, { maxFiles: 4000, onlyExt: ['.kt'] });
            const groups = groupByPackage(files);
            const packages = Array.from(groups.entries())
                .map(([dir, names]) => ({
                    dir: dir.replace('app/src/main/java/', ''),
                    files: names.length,
                    sample: names.slice(0, 6),
                }))
                .sort((a, b) => b.files - a.files)
                .slice(0, 80);
            return ok(
                '主源码 ' + files.length + ' 个 .kt，分布于 ' + groups.size + ' 个包（列出前 80）',
                { totalKtFiles: files.length, packageCount: groups.size, packages }
            );
        }

        if (scope === 'module') {
            const base = repoPath('app', 'src', 'main', 'java', 'com', 'zhijian', 'schedule');
            const result = {};
            for (const sub of ['core', 'feature', 'di', 'navigation']) {
                const dir = path.join(base, sub);
                if (!existsSync(dir)) continue;
                result[sub] = readdirSync(dir, { withFileTypes: true })
                    .filter((e) => e.isDirectory())
                    .map((e) => e.name)
                    .sort();
            }
            return ok(
                'app 模块目录约定：core ' + (result.core || []).length + ' 个子域，feature ' + (result.feature || []).length + ' 个功能包',
                { base: path.relative(repoRoot(), base).replace(/\\/g, '/'), layout: result }
            );
        }

        if (scope === 'docs') {
            const docsRoot = repoPath('docs');
            const result = {};
            if (existsSync(docsRoot)) {
                for (const entry of readdirSync(docsRoot, { withFileTypes: true })) {
                    if (!entry.isDirectory()) continue;
                    const dir = path.join(docsRoot, entry.name);
                    result[entry.name] = readdirSync(dir).slice(0, 40);
                }
            }
            return ok('docs 分类：' + Object.keys(result).join(' / '), { docs: result });
        }

        // scope === 'files'
        const rel = str(args, 'path', 'app/src/main/java');
        const target = path.resolve(repoRoot(), rel);
        if (!target.startsWith(path.resolve(repoRoot()))) return fail('路径越界：' + rel);
        if (!existsSync(target)) return fail('路径不存在：' + rel);
        const extFilter = Array.isArray(args && args.ext) ? args.ext.filter((e) => typeof e === 'string') : [];
        const files = walk(target, { maxFiles, onlyExt: extFilter.length ? extFilter : null });
        const relFiles = files.map((f) => path.relative(repoRoot(), f).replace(/\\/g, '/'));
        return ok(
            rel + ' 下 ' + relFiles.length + ' 个文件' + (files.length >= maxFiles ? '（已达上限 ' + maxFiles + '）' : ''),
            { path: rel, count: relFiles.length, truncated: files.length >= maxFiles, files: relFiles }
        );
    },
};
