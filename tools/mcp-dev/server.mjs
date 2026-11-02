#!/usr/bin/env node
/**
 * 纸间课表开发后端 MCP 服务器。
 *
 * 传输：stdio，MCP 帧格式 = 每行一条 JSON-RPC 2.0 消息（换行分隔，不用 Content-Length 头）。
 * 纪律：stdout 只写协议消息；任何诊断日志一律走 stderr，否则客户端解析会炸。
 *
 * 支持的方法：
 *   initialize / notifications/initialized / ping
 *   tools/list / tools/call
 *   resources/list / resources/templates/list（返回空表，占位以兼容只读客户端）
 *   prompts/list（空表）
 * 不支持的方法按 JSON-RPC 规范回 -32601。
 */

import { SERVER_INFO, INSTRUCTIONS, PROTOCOL_VERSIONS, LATEST_PROTOCOL_VERSION } from './lib/meta.mjs';
import { envTool } from './lib/tools/env.mjs';
import { mapTool } from './lib/tools/map.mjs';
import { buildTool } from './lib/tools/build.mjs';
import { testTool } from './lib/tools/test.mjs';
import { lintTool } from './lib/tools/lint.mjs';
import { deviceTool } from './lib/tools/device.mjs';
import { gitTool } from './lib/tools/git.mjs';
import { jwScriptsTool } from './lib/tools/jw-scripts.mjs';
import { docsTool } from './lib/tools/docs.mjs';
import { versionTool } from './lib/tools/version.mjs';
import { archCheckTool } from './lib/tools/arch.mjs';
import { mockJwTool } from './lib/tools/mock-jw.mjs';

const TOOLS = [
    envTool,
    mapTool,
    buildTool,
    testTool,
    lintTool,
    archCheckTool,
    deviceTool,
    gitTool,
    jwScriptsTool,
    docsTool,
    versionTool,
    mockJwTool,
];

const TOOL_MAP = new Map(TOOLS.map((tool) => [tool.name, tool]));

/** 诊断日志：只走 stderr。 */
function log(...parts) {
    process.stderr.write('[zhijian-dev] ' + parts.join(' ') + '\n');
}

/** 把工具定义转成 MCP tools/list 的条目。 */
function toMcpTool(tool) {
    return {
        name: tool.name,
        title: tool.title,
        description: tool.description,
        inputSchema: tool.inputSchema,
        outputSchema: tool.outputSchema,
        annotations: tool.annotations,
    };
}

/** 统一的成功响应。 */
function reply(id, result) {
    send({ jsonrpc: '2.0', id, result });
}

/** 统一的错误响应。 */
function replyError(id, code, message, data) {
    send({
        jsonrpc: '2.0',
        id: id === undefined ? null : id,
        error: Object.assign({ code, message }, data === undefined ? {} : { data }),
    });
}

let writeQueue = Promise.resolve();

/** 串行写 stdout，避免并发写把一行 JSON 切断。 */
function send(message) {
    const line = JSON.stringify(message) + '\n';
    writeQueue = writeQueue.then(
        () =>
            new Promise((resolve) => {
                process.stdout.write(line, () => resolve());
            })
    );
}

/** tools/call 的执行体。 */
async function callTool(params) {
    const name = params && params.name;
    const tool = TOOL_MAP.get(name);
    if (!tool) {
        return {
            isError: true,
            content: [{ type: 'text', text: '未知工具：' + String(name) + '（可用：' + TOOLS.map((t) => t.name).join(', ') + '）' }],
        };
    }
    const args = params && typeof params.arguments === 'object' && params.arguments !== null ? params.arguments : {};
    const startedAt = Date.now();
    try {
        const value = await tool.run(args);
        const elapsed = Date.now() - startedAt;
        log(name, 'ok=' + String(value && value.ok), elapsed + 'ms');
        const text = (value && value.summary ? value.summary : '') +
            '\n\n' + JSON.stringify(value && value.data !== undefined ? value.data : {}, null, 2);
        return {
            content: [{ type: 'text', text }],
            structuredContent: value,
            isError: value && value.ok === false ? true : undefined,
        };
    } catch (error) {
        const message = error && error.stack ? error.stack : String(error);
        log(name, 'threw', message.split('\n')[0]);
        return {
            isError: true,
            content: [{ type: 'text', text: '工具 ' + name + ' 执行异常：' + message }],
        };
    }
}

/** 处理一条入站消息。 */
async function handle(message) {
    const { id, method, params } = message || {};
    const isRequest = id !== undefined && id !== null;

    if (method === 'initialize') {
        const requested = params && typeof params.protocolVersion === 'string' ? params.protocolVersion : null;
        const negotiated = requested && PROTOCOL_VERSIONS.includes(requested) ? requested : LATEST_PROTOCOL_VERSION;
        log('initialize', 'requested=' + String(requested), 'negotiated=' + negotiated);
        reply(id, {
            protocolVersion: negotiated,
            capabilities: {
                tools: { listChanged: false },
                resources: { subscribe: false, listChanged: false },
                prompts: { listChanged: false },
            },
            serverInfo: SERVER_INFO,
            instructions: INSTRUCTIONS,
        });
        return;
    }

    if (method === 'notifications/initialized' || method === 'notifications/cancelled') {
        return; // 通知不需要响应
    }

    if (method === 'ping') {
        if (isRequest) reply(id, {});
        return;
    }

    if (method === 'tools/list') {
        reply(id, { tools: TOOLS.map(toMcpTool) });
        return;
    }

    if (method === 'tools/call') {
        const result = await callTool(params);
        const clean = { content: result.content };
        if (result.structuredContent !== undefined) clean.structuredContent = result.structuredContent;
        if (result.isError === true) clean.isError = true;
        reply(id, clean);
        return;
    }

    if (method === 'resources/list') {
        reply(id, { resources: [] });
        return;
    }

    if (method === 'resources/templates/list') {
        reply(id, { resourceTemplates: [] });
        return;
    }

    if (method === 'prompts/list') {
        reply(id, { prompts: [] });
        return;
    }

    if (isRequest) {
        replyError(id, -32601, '不支持的方法：' + String(method));
    }
}

/** 逐行解析 stdin。 */
function startReader() {
    let buffer = '';
    process.stdin.setEncoding('utf8');
    process.stdin.on('data', (chunk) => {
        buffer += chunk;
        let index = buffer.indexOf('\n');
        while (index >= 0) {
            const line = buffer.slice(0, index).trim();
            buffer = buffer.slice(index + 1);
            index = buffer.indexOf('\n');
            if (!line) continue;
            let parsed;
            try {
                parsed = JSON.parse(line);
            } catch (error) {
                replyError(null, -32700, 'JSON 解析失败：' + String(error && error.message ? error.message : error));
                continue;
            }
            if (Array.isArray(parsed)) {
                for (const item of parsed) {
                    handle(item).catch((error) => log('handle error', String(error)));
                }
            } else {
                handle(parsed).catch((error) => log('handle error', String(error)));
            }
        }
    });
    process.stdin.on('end', () => {
        log('stdin closed, exiting');
        process.exit(0);
    });
}

process.on('uncaughtException', (error) => {
    log('uncaughtException', error && error.stack ? error.stack : String(error));
});
process.on('unhandledRejection', (reason) => {
    log('unhandledRejection', String(reason));
});

log('started', 'tools=' + TOOLS.length, 'protocol=' + LATEST_PROTOCOL_VERSION);
startReader();
