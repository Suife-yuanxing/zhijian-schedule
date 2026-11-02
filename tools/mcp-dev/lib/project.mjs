/**
 * 仓库探测：根目录、Gradle wrapper、SDK、JDK、包名、版本号、模块清单。
 *
 * 所有工具都从这里取路径，禁止各自拼 D:\课程表 —— 换机器时只改一处。
 */

import { existsSync, readFileSync, statSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { resolveBinary } from './exec.mjs';

/** 本文件位于 <repo>/tools/mcp-dev/lib/project.mjs */
const HERE = path.dirname(fileURLToPath(import.meta.url));

/** 仓库根：tools/mcp-dev/lib -> tools/mcp-dev -> tools -> <root> */
export const REPO_ROOT = path.resolve(HERE, '..', '..', '..');

/** 允许用环境变量覆盖（隔离验证、CI 或 worktree 场景）。 */
export function repoRoot() {
    const override = process.env.ZHIJIAN_REPO_ROOT;
    return override && override.trim() ? path.resolve(override.trim()) : REPO_ROOT;
}

export function repoPath(...segments) {
    return path.join(repoRoot(), ...segments);
}

function readIfExists(file) {
    try {
        if (!existsSync(file)) return null;
        return readFileSync(file, 'utf8');
    } catch {
        return null;
    }
}

/** 从 properties 文本读一个键（按行扫描，不构造正则，避免转义坑）。 */
function gradleProperty(text, key) {
    if (!text) return null;
    for (const rawLine of text.split(/\r?\n/)) {
        const line = rawLine.trim();
        if (!line || line.startsWith('#')) continue;
        const eq = line.indexOf('=');
        if (eq < 0) continue;
        if (line.slice(0, eq).trim() !== key) continue;
        return line.slice(eq + 1).trim();
    }
    return null;
}

/** 解析 local.properties 的 sdk.dir（注意 Windows 反斜杠转义）。 */
export function androidSdkDir() {
    const text = readIfExists(repoPath('local.properties'));
    if (!text) return null;
    const match = text.match(/^sdk\.dir\s*=\s*(.+)$/m);
    if (!match) return null;
    const raw = match[1].trim().replace(/\\\\/g, '\\').replace(/\\:/g, ':');
    return raw;
}

/** 读 app/build.gradle.kts 里的版本号。 */
export function appVersion() {
    const text = readIfExists(repoPath('app', 'build.gradle.kts'));
    if (!text) return { versionCode: null, versionName: null };
    const code = text.match(/versionCode\s*=\s*(\d+)/);
    const name = text.match(/versionName\s*=\s*"([^"]+)"/);
    return {
        versionCode: code ? Number(code[1]) : null,
        versionName: name ? name[1] : null,
    };
}

/** 读 applicationId。 */
export function applicationId() {
    const text = readIfExists(repoPath('app', 'build.gradle.kts'));
    if (!text) return null;
    const match = text.match(/applicationId\s*=\s*"([^"]+)"/);
    return match ? match[1] : null;
}

/** debug 变体包名（applicationIdSuffix = ".debug"）。 */
export function debugApplicationId() {
    const base = applicationId();
    if (!base) return null;
    const text = readIfExists(repoPath('app', 'build.gradle.kts')) || '';
    const suffix = text.match(/applicationIdSuffix\s*=\s*"([^"]+)"/);
    return base + (suffix ? suffix[1] : '.debug');
}

/** 读 settings.gradle.kts 的模块清单（含根工程名）。 */
export function modules() {
    const text = readIfExists(repoPath('settings.gradle.kts'));
    if (!text) return { rootProject: null, modules: [] };
    const rootMatch = text.match(/rootProject\.name\s*=\s*"([^"]+)"/);
    const includes = Array.from(text.matchAll(/include\(\s*"([^"]+)"\s*\)/g)).map((m) => m[1]);
    return {
        rootProject: rootMatch ? rootMatch[1] : null,
        modules: includes,
    };
}

/** Gradle wrapper 路径（Windows 优先 gradlew.bat）。 */
export function gradlewPath() {
    const isWin = process.platform === 'win32';
    const candidate = repoPath(isWin ? 'gradlew.bat' : 'gradlew');
    return existsSync(candidate) ? candidate : null;
}

/** JDK：JAVA_HOME 优先，其次 PATH 上的 java。 */
export async function javaHome() {
    const envHome = process.env.JAVA_HOME;
    if (envHome && existsSync(path.join(envHome, 'bin', 'java.exe'))) return envHome;
    if (envHome && existsSync(path.join(envHome, 'bin', 'java'))) return envHome;
    const found = await resolveBinary(['java'], [], repoRoot());
    if (!found) return null;
    return path.dirname(path.dirname(found));
}

/** adb 可执行文件。 */
export async function adbPath() {
    const explicit = process.env.ADB;
    if (explicit && existsSync(explicit)) return explicit;
    const sdk = androidSdkDir();
    const extra = sdk ? [path.join(sdk, 'platform-tools')] : [];
    return resolveBinary(['adb'], extra, repoRoot());
}

/** python 可执行文件（tools/run_unit_tests.py 与 mock-jw 用）。 */
export async function pythonPath() {
    return resolveBinary(['python', 'python3', 'py'], [], repoRoot());
}

/** 目录是否像本仓库（防止在错误目录上跑构建）。 */
export function looksLikeRepo() {
    return (
        existsSync(repoPath('settings.gradle.kts')) &&
        existsSync(repoPath('app', 'build.gradle.kts'))
    );
}

/** 一次性快照，env 工具直接用。 */
export async function snapshot() {
    const sdk = androidSdkDir();
    const jdk = await javaHome();
    const gradle = gradlewPath();
    const adb = await adbPath();
    const py = await pythonPath();
    const version = appVersion();
    const mods = modules();
    return {
        repoRoot: repoRoot(),
        looksLikeRepo: looksLikeRepo(),
        platform: process.platform,
        nodeVersion: process.version,
        gradlew: gradle,
        gradleWrapperProperties: readIfExists(repoPath('gradle', 'wrapper', 'gradle-wrapper.properties')),
        androidSdkDir: sdk,
        androidSdkExists: sdk ? existsSync(sdk) : false,
        javaHome: jdk,
        adb: adb,
        python: py,
        applicationId: applicationId(),
        debugApplicationId: debugApplicationId(),
        versionCode: version.versionCode,
        versionName: version.versionName,
        rootProject: mods.rootProject,
        modules: mods.modules,
        hasLocalProperties: existsSync(repoPath('local.properties')),
        hasKeystoreProperties: existsSync(repoPath('keystore.properties')),
        hasReleaseKeystore: existsSync(repoPath('keystore', 'zhijian-release.jks')),
        gradleJvmArgs: gradleProperty(readIfExists(repoPath('gradle.properties')), 'org.gradle.jvmargs'),
        fileEncodingCompat: /-Dfile\.encoding=COMPAT/.test(
            readIfExists(repoPath('gradle.properties')) || ''
        ),
    };
}

/** 文件是否存在且非空（构建产物判定）。 */
export function artifactInfo(file) {
    try {
        const stat = statSync(file);
        return {
            path: file,
            exists: true,
            bytes: stat.size,
            mib: Math.round((stat.size / 1048576) * 100) / 100,
            modifiedAt: stat.mtime.toISOString(),
        };
    } catch {
        return { path: file, exists: false, bytes: 0, mib: 0, modifiedAt: null };
    }
}
