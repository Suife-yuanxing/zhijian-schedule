/**
 * mock_jw —— 本地假教务服务（tools/mock-jw/server.mjs）的启停。
 *
 * 用途：没有真实学校账号时，验证「WebView → 共享 Cookie → 适配器 → 预览 → 落库」整条链路。
 * 服务是一个独立 node 进程，这里只做三件事：起、停、查。
 *
 * 端口默认 8080（与 tools/mock-jw/server.mjs 的 PORT 默认值一致）。
 * 进程句柄保存在本进程内存里；MCP 服务器重启后残留进程用 stop(force) 按端口清。
 */

import { spawn } from 'node:child_process';
import { existsSync } from 'node:fs';
import net from 'node:net';
import { S } from '../schema.mjs';
import { ENVELOPE, ok, fail, int, str } from './common.mjs';
import { run } from '../exec.mjs';
import { repoRoot, repoPath } from '../project.mjs';

/** 存活检测：能连上就认为在跑。 */
function probe(port) {
    return new Promise((resolve) => {
        const socket = net.connect({ host: '127.0.0.1', port, timeout: 1200 });
        socket.on('connect', () => {
            socket.destroy();
            resolve(true);
        });
        socket.on('error', () => resolve(false));
        socket.on('timeout', () => {
            socket.destroy();
            resolve(false);
        });
    });
}

/** 按端口找监听进程 PID（Windows: netstat + tasklist 组合）。 */
async function pidOnPort(port) {
    if (process.platform !== 'win32') {
        const result = await run({ command: 'lsof', args: ['-ti', 'tcp:' + port], cwd: repoRoot(), timeoutMs: 15000 });
        if (!result.ok) return [];
        return result.stdout.split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
    }
    const result = await run({ command: 'netstat', args: ['-ano', '-p', 'TCP'], cwd: repoRoot(), timeoutMs: 20000 });
    if (!result.ok) return [];
    const pids = new Set();
    for (const line of result.stdout.split(/\r?\n/)) {
        const m = line.trim().match(/^TCP\s+\S+:(\d+)\s+\S+\s+LISTENING\s+(\d+)$/);
        if (!m) continue;
        if (Number(m[1]) === port) pids.add(m[2]);
    }
    return Array.from(pids);
}

let running = null; // { child, port, startedAt }

export const mockJwTool = {
    name: 'mock_jw',
    title: '假教务服务启停',
    description:
        '启动 / 停止 / 查看 tools/mock-jw 假教务服务（模拟正方新版与强智 jsxsd）。' +
        'start 后台起进程并等待端口就绪，stop 结束进程（force 时按端口清残留），status 报告端口占用。',
    inputSchema: S.obj(
        '假教务参数',
        {
            action: S.pick('动作', ['start', 'stop', 'status'], 'status'),
            port: S.int('监听端口（默认 8080）', { default: 8080 }),
            force: S.bool('stop 时按端口强制结束残留进程（默认 false）', { default: false }),
        },
        []
    ),
    outputSchema: ENVELOPE,
    annotations: { readOnlyHint: false, openWorldHint: false, destructiveHint: false },
    async run(args) {
        const action = str(args, 'action', 'status');
        const port = args && Number.isFinite(args.port) ? args.port : 8080;
        const serverFile = repoPath('tools', 'mock-jw', 'server.mjs');
        if (!existsSync(serverFile)) return fail('找不到 ' + serverFile);

        if (action === 'status') {
            const alive = await probe(port);
            const pids = alive ? await pidOnPort(port) : [];
            const owned = running && running.port === port ? running.child.pid : null;
            return ok(
                (alive ? '端口 ' + port + ' 有服务在跑' : '端口 ' + port + ' 空闲') +
                (pids.length ? '（PID ' + pids.join(',') + '）' : '') +
                (owned ? ' · 本工具持有 PID ' + owned : ''),
                { port, alive, pids, owned, baseUrl: 'http://127.0.0.1:' + port }
            );
        }

        if (action === 'start') {
            if (await probe(port)) {
                const pids = await pidOnPort(port);
                return ok('端口 ' + port + ' 已有服务（PID ' + pids.join(',') + '），未重复启动', { port, pids, reused: true });
            }
            const child = spawn(process.execPath, [serverFile], {
                cwd: repoRoot(),
                env: Object.assign({}, process.env, { PORT: String(port) }),
                stdio: ['ignore', 'pipe', 'pipe'],
                windowsHide: true,
                detached: false,
            });
            const log = [];
            child.stdout.on('data', (c) => log.push(String(c)));
            child.stderr.on('data', (c) => log.push(String(c)));
            running = { child, port, startedAt: Date.now() };

            const deadline = Date.now() + 8000;
            let alive = false;
            while (Date.now() < deadline) {
                await new Promise((r) => setTimeout(r, 200));
                if (await probe(port)) {
                    alive = true;
                    break;
                }
                if (child.exitCode !== null) break;
            }
            if (!alive) {
                return fail('假教务服务启动失败（端口 ' + port + ' 未就绪）', {
                    port,
                    exitCode: child.exitCode,
                    log: log.join('').slice(-4000),
                });
            }
            return ok('假教务已启动：http://127.0.0.1:' + port + '（PID ' + child.pid + '）', {
                port,
                pid: child.pid,
                baseUrl: 'http://127.0.0.1:' + port,
                endpoints: [
                    '/jwglxt/xtgl/login_slogin.html',
                    '/jwglxt/kbcx/xskbcx_cxXskbcxIndex.html',
                    '/jwglxt/kbcx/xskbcx_cxXsKb.html',
                ],
            });
        }

        if (action === 'stop') {
            const stopped = [];
            if (running && running.port === port) {
                try {
                    running.child.kill('SIGTERM');
                    stopped.push(running.child.pid);
                } catch {
                    /* 已退出 */
                }
                running = null;
            }
            if (args && args.force === true) {
                const pids = await pidOnPort(port);
                for (const pid of pids) {
                    const kill = await run({
                        command: process.platform === 'win32' ? 'taskkill' : 'kill',
                        args: process.platform === 'win32' ? ['/pid', pid, '/T', '/F'] : ['-9', pid],
                        cwd: repoRoot(),
                        timeoutMs: 15000,
                    });
                    if (kill.ok) stopped.push(Number(pid));
                }
            }
            const stillAlive = await probe(port);
            if (stillAlive) {
                return fail('端口 ' + port + ' 仍有服务在跑（加 force=true 按端口强杀）', { port, stopped });
            }
            return ok(stopped.length ? '已停止 PID ' + stopped.join(',') : '端口 ' + port + ' 本就空闲', { port, stopped });
        }

        return fail('未知 action：' + action);
    },
};
