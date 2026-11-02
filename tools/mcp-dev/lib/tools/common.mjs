/**
 * 工具层公共件：统一返回信封 + 输出 schema + 参数校验小工具。
 *
 * 所有工具都返回同一个信封 { ok, summary, data }：
 *  · summary 给人看（一行结论 + 关键数字）
 *  · data 给模型解析（结构化，字段名稳定）
 * 这样 MCP 客户端既能直接渲染文本，也能按字段取值。
 */

export const ENVELOPE = {
    type: 'object',
    properties: {
        ok: { type: 'boolean', description: '本次操作是否成功' },
        summary: { type: 'string', description: '一句话结论与关键数字' },
        data: { description: '结构化明细，字段名稳定' },
    },
    required: ['ok', 'summary'],
};

/** 成功信封。 */
export function ok(summary, data) {
    return { ok: true, summary, data: data === undefined ? {} : data };
}

/** 失败信封（不是抛异常：失败也是可读结果）。 */
export function fail(summary, data) {
    return { ok: false, summary, data: data === undefined ? {} : data };
}

/** 取字符串参数并去空白；缺省返回 fallback。 */
export function str(args, key, fallback) {
    const value = args ? args[key] : undefined;
    if (typeof value !== 'string') return fallback;
    const trimmed = value.trim();
    return trimmed === '' ? fallback : trimmed;
}

/** 取整数参数并夹在 [min, max]。 */
export function int(args, key, fallback, min, max) {
    const value = args ? args[key] : undefined;
    if (typeof value !== 'number' || !Number.isFinite(value)) return fallback;
    const rounded = Math.trunc(value);
    if (min !== undefined && rounded < min) return min;
    if (max !== undefined && rounded > max) return max;
    return rounded;
}

/** 取布尔参数。 */
export function bool(args, key, fallback) {
    const value = args ? args[key] : undefined;
    return typeof value === 'boolean' ? value : fallback;
}

/** 取字符串数组参数（去空、去重）。 */
export function strList(args, key) {
    const value = args ? args[key] : undefined;
    if (!Array.isArray(value)) return [];
    const seen = new Set();
    const out = [];
    for (const item of value) {
        if (typeof item !== 'string') continue;
        const trimmed = item.trim();
        if (!trimmed || seen.has(trimmed)) continue;
        seen.add(trimmed);
        out.push(trimmed);
    }
    return out;
}

/** 人读的字节数。 */
export function mib(bytes) {
    if (!Number.isFinite(bytes) || bytes <= 0) return '0 MiB';
    return (bytes / 1048576).toFixed(2) + ' MiB';
}

/** 人读的耗时。 */
export function secs(ms) {
    if (!Number.isFinite(ms)) return '0s';
    if (ms < 1000) return ms + 'ms';
    return (ms / 1000).toFixed(1) + 's';
}
