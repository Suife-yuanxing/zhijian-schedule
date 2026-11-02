/**
 * version_bump —— 版本号与发版清单。
 *
 * 仓库发版流程（CONTRIBUTING.md §7）：
 *  1. 改 app/build.gradle.kts 的 versionCode / versionName（一一对应：SemVer 补丁位 = versionCode）
 *  2. 提交「版本号提升至 vX.Y.Z（versionCode N）准备发布构建」
 *  3. assembleRelease
 *  4. GitHub Release，tag 必须是 vX.Y.Z，必须上传 .apk
 *  5. CHANGELOG 的 Unreleased 落到新版本号下
 *
 * 本工具做两件事：
 *  · action=status 检查上述清单的当前完成度（版本号 / CHANGELOG / 产物 / 签名 / tag）
 *  · action=apply  只改 app/build.gradle.kts 的两行，并给出 CHANGELOG 待办
 *    —— 不自动提交、不自动打 tag、不自动建 Release。
 */

import { existsSync, readFileSync, writeFileSync } from 'node:fs';
import { S } from '../schema.mjs';
import { ENVELOPE, ok, fail, str, bool, int } from './common.mjs';
import { run } from '../exec.mjs';
import { repoRoot, repoPath, appVersion, artifactInfo, debugApplicationId } from '../project.mjs';

const BUILD_FILE = ['app', 'build.gradle.kts'];
const RELEASE_APK = ['app', 'build', 'outputs', 'apk', 'release', 'app-release.apk'];

function changelogState(versionName) {
    const file = repoPath('CHANGELOG.md');
    if (!existsSync(file)) return { exists: false };
    const text = readFileSync(file, 'utf8');
    const hasUnreleased = /^## \[Unreleased\]/m.test(text);
    const hasVersion = versionName ? text.includes('## [' + versionName + ']') : false;
    const firstVersion = (text.match(/^## \[([^\]]+)\] - /m) || [])[1] || null;
    return { exists: true, hasUnreleased, hasVersion, firstVersion };
}

async function gitTags() {
    const result = await run({ command: 'git', args: ['tag', '--list', 'v*', '--sort=-v:refname'], cwd: repoRoot(), timeoutMs: 30000 });
    if (!result.ok) return [];
    return result.stdout.split(/\r?\n/).map((t) => t.trim()).filter(Boolean).slice(0, 10);
}

export const versionTool = {
    name: 'version_bump',
    title: '版本号与发版清单',
    description:
        '发版前检查与改号。action=status 报告 versionCode/versionName、CHANGELOG 是否已写实、release 产物与签名、' +
        '最近的 v* tag；action=apply 只改 app/build.gradle.kts 两行（不提交、不打 tag），并列出 CHANGELOG 待办。',
    inputSchema: S.obj(
        '版本参数',
        {
            action: S.pick('动作', ['status', 'apply'], 'status'),
            versionCode: S.int('新的 versionCode（apply 必填）'),
            versionName: S.str('新的 versionName，如 1.0.18（apply 必填）'),
            dryRun: S.bool('apply 时只预览 diff 不写盘（默认 true）', { default: true }),
        },
        []
    ),
    outputSchema: ENVELOPE,
    annotations: { readOnlyHint: false, openWorldHint: false, destructiveHint: false },
    async run(args) {
        const action = str(args, 'action', 'status');
        const current = appVersion();

        if (action === 'status') {
            const changelog = changelogState(current.versionName);
            const apk = artifactInfo(repoPath(...RELEASE_APK));
            const tags = await gitTags();
            const checks = [];
            checks.push({
                item: 'versionCode 与 versionName 已就位',
                pass: Boolean(current.versionCode && current.versionName),
                detail: current.versionCode + ' / ' + current.versionName,
            });
            checks.push({
                item: 'CHANGELOG 有 Unreleased 段',
                pass: changelog.hasUnreleased === true,
            });
            checks.push({
                item: 'CHANGELOG 已落到当前版本号',
                pass: changelog.hasVersion === true,
                detail: '## [' + current.versionName + ']',
            });
            checks.push({
                item: 'release APK 已构建',
                pass: apk.exists,
                detail: apk.exists ? apk.mib + ' MiB · ' + apk.modifiedAt : '尚未构建',
            });
            checks.push({
                item: '正式签名密钥就位',
                pass: existsSync(repoPath('keystore.properties')) && existsSync(repoPath('keystore', 'zhijian-release.jks')),
                detail: existsSync(repoPath('keystore.properties')) ? 'keystore.properties 存在' : '缺 keystore.properties，release 会回落 debug 签名',
            });
            const expectedTag = 'v' + current.versionName;
            checks.push({
                item: 'tag ' + expectedTag + ' 尚未创建（发版时手动打）',
                pass: !tags.includes(expectedTag),
                detail: tags.slice(0, 5).join(' ') || '无 v* tag',
            });
            const failed = checks.filter((c) => !c.pass);
            const data = { current, changelog, apk, tags, checks, debugApplicationId: debugApplicationId() };
            if (failed.length === 0) {
                return ok('发版清单全部满足：' + expectedTag + '（' + current.versionCode + '）', data);
            }
            return fail(
                '发版清单还有 ' + failed.length + ' 项未满足：' + failed.map((f) => f.item).join('；'),
                data
            );
        }

        if (action === 'apply') {
            const nextCode = args && Number.isFinite(args.versionCode) ? args.versionCode : null;
            const nextName = str(args, 'versionName', null);
            if (!nextCode || !nextName) return fail('apply 需要同时给出 versionCode 与 versionName');
            if (!/^\d+\.\d+\.\d+$/.test(nextName)) {
                return fail('versionName 必须是 X.Y.Z 形式：' + nextName);
            }
            if (current.versionCode !== null && nextCode <= current.versionCode) {
                return fail('versionCode 必须递增：当前 ' + current.versionCode + '，给的是 ' + nextCode);
            }
            const file = repoPath(...BUILD_FILE);
            if (!existsSync(file)) return fail('找不到 ' + BUILD_FILE.join('/'));
            const text = readFileSync(file, 'utf8');
            const updated = text
                .replace(/versionCode\s*=\s*\d+/, 'versionCode = ' + nextCode)
                .replace(/versionName\s*=\s*"[^"]+"/, 'versionName = "' + nextName + '"');
            const dryRun = args && args.dryRun === undefined ? true : args.dryRun === true;
            const changedLines = [];
            for (const line of updated.split(/\r?\n/)) {
                if (/versionCode|versionName/.test(line)) changedLines.push(line.trim());
            }
            if (!dryRun) writeFileSync(file, updated, 'utf8');
            const todo = [
                '提交：chore(release): 版本号提升至 v' + nextName + '（versionCode ' + nextCode + '）准备发布构建',
                './gradlew assembleRelease（或 build mode=release）',
                'GitHub Release：tag 必须是 v' + nextName + '，必须上传 .apk 资产',
                'CHANGELOG.md：把 Unreleased 段落落到 ## [' + nextName + '] - YYYY-MM-DD',
            ];
            return ok(
                (dryRun ? '预览' : '已写入') + '：' + current.versionName + ' (' + current.versionCode + ') → ' +
                nextName + ' (' + nextCode + ')',
                { dryRun, from: current, to: { versionCode: nextCode, versionName: nextName }, changedLines, todo }
            );
        }

        return fail('未知 action：' + action);
    },
};
