#!/usr/bin/env node
/**
 * 自检：用真实的 stdio 帧把服务器跑一遍。
 *
 * 覆盖四件事：
 *  1. 每个工具的 inputSchema / outputSchema 都落在 DSH 工具运行时接受的 JSON Schema 子集里
 *     （type/oneOf/properties/required/additionalProperties/items/enum/const + description/title/default/examples）；
 *  2. initialize 握手按客户端请求的版本协商，不支持的版本回落到最新；
 *  3. tools/list 返回全部工具且名字唯一；
 *  4. tools/call 对只读工具给出可解析的信封（ok/summary/data）。
 *
 * 用法：node tools/mcp-dev/selftest.mjs [--skip-calls]
 */

import { spawn } from 'node:child_process';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = path.dirname(fileURLToPath(import.meta.url));
const SERVER = path.join(HERE, 'server.mjs');

const CONSTRAINT = new Set(['type', 'oneOf', 'properties', 'required', 'additionalProperties', 'items', 'enum', 'const']);
const ANNOTATION = new Set(['description', 'title', 'default', 'examples']);
const TYPES = new Set(['object', 'array', 'string', 'number', 'integer', 'boolean', 'null']);

/** 复刻 DSH 的子集校验，用来在接入前就发现不合规的 schema。 */
function checkSchema(node, where, problems) {
    if (typeof node !== 'object' || node === null || Array.isArray(node)) {
        problems.push(where + ' 不是 schema 对象');
        return;
    }
    for (const key of Object.keys(node)) {
        if (!CONSTRAINT.has(key) && !ANNOTATION.has(key)) {
            problems.push(where + '.' + key + ' 不是受支持的关键字');
        }
    }
    if (Object.hasOwn(node, 'properties')) {
        if (node.type !== 'object') problems.push(where + '.properties 只能配 type=object');
        for (const [name, child] of Object.entries(node.properties)) {
            checkSchema(child, where + '.properties.' + name, problems);
        }
    }
    if (Object.hasOwn(node, 'required')) {
        if (!Array.isArray(node.required)) problems.push(where + '.required 必须是字符串数组');
        else if (node.properties) {
            for (const name of node.required) {
                if (!Object.hasOwn(node.properties, name)) problems.push(where + '.required 里的 ' + name + ' 不在 properties');
            }
        }
    }
    if (Object.hasOwn(node, 'additionalProperties') && typeof node.additionalProperties !== 'boolean') {
        problems.push(where + '.additionalProperties 必须是布尔');
    }
    if (Object.hasOwn(node, 'items')) {
        if (node.type !== 'array') problems.push(where + '.items 只能配 type=array');
        checkSchema(node.items, where + '.items', problems);
    }
    if (Object.hasOwn(node, 'enum')) {
        if (!Array.isArray(node.enum) || node.enum.length === 0) problems.push(where + '.enum 必须是非空数组');
    }
    if (Object.hasOwn(node, 'const') && !Object.hasOwn(node, 'enum')) {
        // const 单用合法，这里只确认类型是标量
        const t = typeof node.const;
        if (!['string', 'number', 'boolean'].includes(t) && node.const !== null) {
            problems.push(where + '.const 必须是标量');
        }
    }
    if (Object.hasOwn(node, 'oneOf')) {
        if (!Array.isArray(node.oneOf) || node.oneOf.length < 2) problems.push(where + '.oneOf 至少两个分支');
        else node.oneOf.forEach((child, i) => checkSchema(child, where + '.oneOf[' + i + ']', problems));
        if (Object.hasOwn(node, 'type')) problems.push(where + ' 不能同时声明 type 与 oneOf');
    }
    if (Object.hasOwn(node, 'type') && !TYPES.has(node.type)) {
        problems.push(where + '.type 非法：' + String(node.type));
    }
}

/** 起一个服务器进程，返回 request 函数。 */
function startServer() {
    const child = spawn(process.execPath, [SERVER], {
        stdio: ['pipe', 'pipe', 'pipe'],
        env: process.env,
    });
    let buffer = '';
    let nextId = 1;
    const pending = new Map();
    const stderr = [];

    child.stdout.setEncoding('utf8');
    child.stdout.on('data', (chunk) => {
        buffer += chunk;
        let index = buffer.indexOf('\n');
        while (index >= 0) {
            const line = buffer.slice(0, index).trim();
            buffer = buffer.slice(index + 1);
            index = buffer.indexOf('\n');
            if (!line) continue;
            let message;
            try {
                message = JSON.parse(line);
            } catch {
                continue;
            }
            if (message.id !== undefined && pending.has(message.id)) {
                const { resolve } = pending.get(message.id);
                pending.delete(message.id);
                resolve(message);
            }
        }
    });
    child.stderr.setEncoding('utf8');
    child.stderr.on('data', (chunk) => stderr.push(String(chunk)));

    function request(method, params) {
        const id = nextId++;
        const payload = JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n';
        return new Promise((resolve, reject) => {
            const timer = setTimeout(() => {
                pending.delete(id);
                reject(new Error('超时：' + method));
            }, 60000);
            pending.set(id, {
                resolve: (message) => {
                    clearTimeout(timer);
                    resolve(message);
                },
            });
            child.stdin.write(payload);
        });
    }

    function notify(method, params) {
        child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method, params }) + '\n');
    }

    function close() {
        child.stdin.end();
        return new Promise((resolve) => child.on('close', resolve));
    }

    return { request, notify, close, stderr };
}

async function main() {
    const skipCalls = process.argv.includes('--skip-calls');
    const problems = [];
    const server = startServer();

    // 1) initialize
    const init = await server.request('initialize', {
        protocolVersion: '2025-06-18',
        capabilities: {},
        clientInfo: { name: 'selftest', version: '0.0.1' },
    });
    if (!init.result) problems.push('initialize 没有返回 result：' + JSON.stringify(init).slice(0, 300));
    else {
        if (init.result.protocolVersion !== '2025-06-18') {
            problems.push('协议协商失败：期望 2025-06-18，得到 ' + init.result.protocolVersion);
        }
        if (!init.result.serverInfo || init.result.serverInfo.name !== 'zhijian-dev') {
            problems.push('serverInfo 不正确：' + JSON.stringify(init.result.serverInfo));
        }
        if (typeof init.result.instructions !== 'string' || init.result.instructions.length < 40) {
            problems.push('instructions 缺失或过短');
        }
    }
    server.notify('notifications/initialized', {});

    // 不支持的版本回落
    const fallback = await server.request('initialize', {
        protocolVersion: '1999-01-01',
        capabilities: {},
        clientInfo: { name: 'selftest', version: '0.0.1' },
    });
    if (fallback.result && fallback.result.protocolVersion !== '2025-11-25') {
        problems.push('不支持的版本应回落到 2025-11-25，得到 ' + fallback.result.protocolVersion);
    }

    // 2) tools/list + schema 子集校验
    const listed = await server.request('tools/list', {});
    const tools = (listed.result && listed.result.tools) || [];
    if (tools.length === 0) problems.push('tools/list 返回空');
    const seen = new Set();
    for (const tool of tools) {
        if (seen.has(tool.name)) problems.push('工具名重复：' + tool.name);
        seen.add(tool.name);
        if (!tool.description || tool.description.length < 20) problems.push(tool.name + ' 的 description 过短');
        checkSchema(tool.inputSchema, tool.name + '.inputSchema', problems);
        checkSchema(tool.outputSchema, tool.name + '.outputSchema', problems);
        if (tool.inputSchema.type !== 'object') problems.push(tool.name + '.inputSchema 根必须是 object');
    }

    // 3) 只读工具实调
    const calls = [];
    if (!skipCalls) {
        for (const name of ['env', 'map', 'docs_check', 'arch_check', 'jw_scripts', 'version_bump', 'git', 'mock_jw']) {
            if (!seen.has(name)) continue;
            const args =
                name === 'map' ? { scope: 'tree' } :
                name === 'docs_check' ? { strict: false } :
                name === 'git' ? { action: 'status' } :
                {};
            const response = await server.request('tools/call', { name, arguments: args });
            const result = response.result || {};
            const structured = result.structuredContent;
            const text = (result.content && result.content[0] && result.content[0].text) || '';
            calls.push({
                name,
                isError: result.isError === true,
                hasStructured: Boolean(structured && typeof structured.ok === 'boolean' && typeof structured.summary === 'string'),
                summary: structured ? structured.summary : text.slice(0, 120),
            });
            if (!structured || typeof structured.ok !== 'boolean') {
                problems.push(name + ' 未返回 { ok, summary, data } 信封');
            }
        }
        // 未知工具应报错而不是崩溃
        const unknown = await server.request('tools/call', { name: 'no_such_tool', arguments: {} });
        if (!unknown.result || unknown.result.isError !== true) {
            problems.push('未知工具调用未返回 isError');
        }
        // 未知方法应回 -32601
        const badMethod = await server.request('no/such/method', {});
        if (!badMethod.error || badMethod.error.code !== -32601) {
            problems.push('未知方法未返回 -32601');
        }
    }

    await server.close();

    const report = {
        tools: tools.map((t) => t.name),
        toolCount: tools.length,
        calls,
        problems,
        stderrTail: server.stderr.join('').split('\n').slice(-6).join('\n'),
    };
    console.log(JSON.stringify(report, null, 2));
    if (problems.length > 0) {
        process.exitCode = 1;
    }
}

main().catch((error) => {
    console.error('selftest 崩了：', error && error.stack ? error.stack : error);
    process.exitCode = 1;
});
