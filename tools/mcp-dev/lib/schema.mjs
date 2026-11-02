/**
 * JSON Schema 构造小工具。
 *
 * DSH 的工具运行时只接受 JSON Schema 的一个子集：
 *   type / oneOf / properties / required / additionalProperties / items / enum / const
 * 外加 description / title / default / examples 这些纯注解。
 * 因此这里只提供能落在子集内的构造器，禁止使用 format、pattern、minimum、anyOf、$ref。
 */

export const S = {
    str(description, extra) {
        return Object.assign({ type: 'string', description }, extra || {});
    },

    int(description, extra) {
        return Object.assign({ type: 'integer', description }, extra || {});
    },

    bool(description, extra) {
        return Object.assign({ type: 'boolean', description }, extra || {});
    },

    /** 字符串枚举；fallback 会写成 default 注解。 */
    pick(description, values, fallback) {
        const schema = { type: 'string', description, enum: values.slice() };
        if (fallback !== undefined) schema.default = fallback;
        return schema;
    },

    arr(description, items) {
        return { type: 'array', description, items };
    },

    obj(description, properties, required) {
        const schema = {
            type: 'object',
            description,
            properties,
            additionalProperties: false,
        };
        if (required && required.length > 0) schema.required = required.slice();
        return schema;
    },

    /** 无参工具的空对象 schema。 */
    noArgs(description) {
        return {
            type: 'object',
            description,
            properties: {},
            additionalProperties: false,
        };
    },
};
