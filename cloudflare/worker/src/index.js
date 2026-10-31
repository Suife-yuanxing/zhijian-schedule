/**
 * 指尖课表 · 更新包镜像 Worker（路线 A）
 *
 * 用途：让 Cloudflare 成为 app 内 DownloadSourceSet 的自建镜像前缀。
 * app 侧只会请求「前缀 + 完整 GitHub 下载地址」，形如：
 *   https://gh.example.com/https://github.com/Suife-yuanxing/zhijian-schedule/releases/download/v1.0.16/PaperTimetable-v1.0.16.apk
 * Worker 把它换成 R2 上的对象，并 302 到 R2 自定义域名，字节完全不经 Worker 中转。
 *
 * 为什么是 302 而不是自己转发：
 *   免费版 Workers 有 CPU 时长与请求体限制，60+ MB 的 APK 让 Worker 代理是纯亏；
 *   302 只花一次极小的请求，真正的传输与 Range 都交给 R2 边缘节点。
 *
 * 绑定（见 wrangler.toml）：
 *   R2_PUBLIC_BASE  形如 https://dl.example.com  —— R2 桶的自定义域名（必须接自定义域名，
 *                   r2.dev 公共域名官方标注「不用于生产」，会限速并返回 429）
 *   BUCKET          R2 桶绑定，只在 302 兜不住时用作直传回源
 */

const R2_PUBLIC_BASE = "https://dl.example.com"; // 和 wrangler.toml 的 [vars] 一致

/** 从「前缀后面的剩余路径」里取出 APK 文件名 */
export function apkKeyFromPath(rest) {
  // 允许两种形态：
  //   1) /https://github.com/owner/repo/releases/download/v1.0.16/x.apk   （gh-proxy 同款）
  //   2) /gh/https://github.com/...                                       （多站点前缀）
  let p = rest;
  if (p.startsWith("/gh/")) p = p.slice(3);
  if (!p.startsWith("/https://github.com/")) return null;
  const path = p.slice(1); // 去掉前导斜杠，得到完整 URL
  let url;
  try {
    url = new URL(path);
  } catch {
    return null;
  }
  if (url.hostname !== "github.com") return null;
  const name = url.pathname.split("/").pop() || "";
  if (!/^[\w.\-+ ()]+\.apk$/i.test(name)) return null;
  return decodeURIComponent(name);
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method !== "GET" && request.method !== "HEAD") {
      return new Response("method not allowed", { status: 405 });
    }

    // 健康检查：不落 R2，便于 uptime 探针
    if (url.pathname === "/healthz") return new Response("ok");

    const key = apkKeyFromPath(url.pathname);
    if (key) {
      const base = (env.R2_PUBLIC_BASE || R2_PUBLIC_BASE).replace(/\/+$/, "");
      // 保留查询串（app 侧不带，但浏览器下载兜底可能带缓存击穿参数）
      return Response.redirect(`${base}/${encodeURIComponent(key)}${url.search}`, 302);
    }

    // 兜底：把剩余路径当对象 key 直接从桶里取，Range 原样透传（用于 302 被拦或直连调试）
    if (env.BUCKET) {
      const objectKey = decodeURIComponent(url.pathname.replace(/^\/+/, ""));
      const range = request.headers.get("Range");
      if (range) {
        const obj = await env.BUCKET.get(objectKey, { range: request.headers });
        if (!obj) return new Response("404", { status: 404 });
        const headers = new Headers();
        obj.writeHttpMetadata(headers);
        headers.set("Accept-Ranges", "bytes");
        headers.set("Content-Range", `bytes ${obj.range.offset}-${obj.range.offset + obj.range.length - 1}/${obj.size}`);
        headers.set("Content-Length", String(obj.range.length));
        return new Response(obj.body, { status: 206, headers });
      }
      const obj = await env.BUCKET.get(objectKey);
      if (!obj) return new Response("404", { status: 404 });
      const headers = new Headers();
      obj.writeHttpMetadata(headers);
      headers.set("Accept-Ranges", "bytes");
      headers.set("Content-Length", String(obj.size));
      return new Response(obj.body, { headers });
    }

    return new Response("404", { status: 404 });
  },
};
