/**
 * 子进程执行层：本服务器所有外部命令（gradle / adb / python / git / node）都从这里走。
 *
 * 三个硬约束（都是本机踩过的坑）：
 *  1. 仓库路径含中文（D:\课程表），Windows 下必须把 cwd 原样交给子进程，
 *     不要经过 shell 二次拼接，否则编码会变形。
 *  2. .bat / .cmd 只能经 cmd.exe 启动；参数含空格时要自己加引号。
 *  3. 任何命令都必须能超时收尸，Windows 下用 taskkill /T /F 杀整棵树，
 *     否则 Gradle 守护进程与 adb 会把端口和锁占住。
 */

import { spawn } from 'node:child_process';

const IS_WIN = process.platform === 'win32';

/** 输出缓冲：保留开头与结尾各一段，中间丢弃并标注。 */
class OutputBuffer {
    constructor(headLines = 200, tailLines = 1200) {
        this.headLines = headLines;
        this.tailLines = tailLines;
        this.head = [];
        this.tail = [];
        this.bytes = 0;
        this.lines = 0;
        this.dropped = 0;
    }

    push(chunk) {
        this.bytes += Buffer.byteLength(chunk, 'utf8');
        const parts = String(chunk).split(/\r?\n/);
        for (const line of parts) {
            this.lines += 1;
            if (this.head.length < this.headLines) {
                this.head.push(line);
                continue;
            }
            this.tail.push(line);
            if (this.tail.length > this.tailLines) {
                this.tail.shift();
                this.dropped += 1;
            }
        }
    }

    toString() {
        if (this.dropped === 0) return this.head.concat(this.tail).join('\n');
        return (
            this.head.join('\n') +
            '\n... [' + this.dropped + ' 行已省略] ...\n' +
            this.tail.join('\n')
        );
    }

    get truncated() {
        return this.dropped > 0;
    }
}

/** Windows 命令行引号规则（只处理含空格/引号的情况，够用且不引入 shell 语义）。 */
function quoteWin(arg) {
    const text = String(arg);
    if (text.length === 0) return '""';
    if (!/[\s"&|<>^]/.test(text)) return text;
    return '"' + text.replace(/"/g, '\\"') + '"';
}

/**
 * 把「逻辑命令」翻译成 spawn 需要的 (file, args)。
 * - .bat/.cmd：Windows 走 cmd.exe /d /s /c
 * - 其他：直接 spawn，不做 shell 解释
 */
function toSpawnSpec(command, args) {
    const lower = command.toLowerCase();
    if (IS_WIN && (lower.endsWith('.bat') || lower.endsWith('.cmd'))) {
        const line = [command, ...args].map(quoteWin).join(' ');
        return { file: process.env.ComSpec || 'cmd.exe', args: ['/d', '/s', '/c', line] };
    }
    return { file: command, args: args.slice() };
}

/** 杀掉整棵进程树。 */
function killTree(child) {
    if (child.exitCode !== null || child.signalCode !== null) return;
    if (IS_WIN && child.pid !== undefined) {
        try {
            spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
            return;
        } catch {
            /* 落到下面的通用分支 */
        }
    }
    try {
        child.kill('SIGKILL');
    } catch {
        /* 进程可能已经退出 */
    }
}

/**
 * 执行一个命令并收集结果。
 *
 * @param {object} options
 * @param {string} options.command 可执行文件名或绝对路径
 * @param {string[]} [options.args]
 * @param {string} [options.cwd]
 * @param {Record<string,string>} [options.env]
 * @param {number} [options.timeoutMs] 默认 10 分钟
 * @param {string} [options.stdin]
 * @returns {Promise<object>}
 */
export function run(options) {
    const {
        command,
        args = [],
        cwd,
        env,
        timeoutMs = 600000,
        stdin,
    } = options;

    return new Promise((resolve) => {
        const startedAt = Date.now();
        const out = new OutputBuffer();
        const err = new OutputBuffer();
        const spec = toSpawnSpec(command, args);

        let child;
        try {
            child = spawn(spec.file, spec.args, {
                cwd,
                env: Object.assign({}, process.env, env || {}),
                stdio: ['pipe', 'pipe', 'pipe'],
                windowsHide: true,
            });
        } catch (error) {
            resolve({
                ok: false,
                command,
                args,
                cwd: cwd || null,
                exitCode: null,
                timedOut: false,
                spawnError: String(error && error.message ? error.message : error),
                durationMs: Date.now() - startedAt,
                stdout: '',
                stderr: '',
                truncated: false,
            });
            return;
        }

        let timedOut = false;
        const timer = setTimeout(() => {
            timedOut = true;
            killTree(child);
        }, Math.max(1000, timeoutMs));

        child.stdout.on('data', (chunk) => out.push(chunk));
        child.stderr.on('data', (chunk) => err.push(chunk));
        child.on('error', (error) => {
            err.push('\n[spawn error] ' + String(error && error.message ? error.message : error) + '\n');
        });
        if (stdin !== undefined && child.stdin) {
            child.stdin.end(stdin);
        } else if (child.stdin) {
            child.stdin.end();
        }

        child.on('close', (code, signal) => {
            clearTimeout(timer);
            const stdout = out.toString();
            const stderr = err.toString();
            resolve({
                ok: code === 0 && !timedOut,
                command,
                args,
                cwd: cwd || null,
                exitCode: code,
                signal: signal || null,
                timedOut,
                durationMs: Date.now() - startedAt,
                stdout,
                stderr,
                output: [stdout, stderr].filter(Boolean).join('\n'),
                truncated: out.truncated || err.truncated,
            });
        });
    });
}

/** 命令是否存在（Windows 用 where，其他用 which）。 */
export async function which(binary, cwd) {
    const result = await run({
        command: IS_WIN ? 'where' : 'which',
        args: [binary],
        cwd,
        timeoutMs: 15000,
    });
    if (!result.ok) return null;
    const first = result.stdout.split(/\r?\n/).map((l) => l.trim()).filter(Boolean)[0];
    return first || null;
}

/** 常用二进制路径解析：优先 PATH，其次若干已知目录。 */
export async function resolveBinary(names, extraDirs, cwd) {
    for (const name of names) {
        const found = await which(name, cwd);
        if (found) return found;
    }
    if (extraDirs && extraDirs.length > 0) {
        const { existsSync } = await import('node:fs');
        const path = await import('node:path');
        for (const dir of extraDirs) {
            for (const name of names) {
                const candidate = path.join(dir, name);
                if (existsSync(candidate)) return candidate;
            }
        }
    }
    return null;
}

/** 统一的失败结果包装：工具层用它返回 isError。 */
export function failure(message, extra) {
    return Object.assign({ ok: false, error: message }, extra || {});
}

/** 把命令结果裁成工具返回用的小对象（不带整段日志，日志另给字段）。 */
export function summarize(result, keep = 8000) {
    const text = result.output || '';
    return {
        ok: result.ok,
        exitCode: result.exitCode,
        timedOut: result.timedOut === true,
        durationMs: result.durationMs,
        command: [result.command].concat(result.args || []).join(' '),
        truncated: result.truncated === true,
        output: text.length > keep ? text.slice(-keep) : text,
        outputBytes: Buffer.byteLength(text, 'utf8'),
    };
}
