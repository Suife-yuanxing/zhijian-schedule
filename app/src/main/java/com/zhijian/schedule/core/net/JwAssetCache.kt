package com.zhijian.schedule.core.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 教务门户静态资源缓存：给教务 WebView 的 `shouldInterceptRequest` 用。
 *
 * 动机（2026-10 对 zhxy.gxgcedu.com 实测）：门户一页带 ~35 个 `/ui/` 静态资源，
 * 服务端**一个缓存头都不发**（无 Cache-Control / ETag / Last-Modified，只有
 * Content-Length），WebView 每次进页面都全量重下——这是「教务系统打开慢」里
 * App 侧唯一能自己解决的一块。好在门户给静态资源都编了版本参数
 * （`?cache_id=1001`），内容更新时 URL 会变，所以按**完整 URL（含 query）**
 * 落盘缓存是安全的：内容变了，key 也跟着变。
 *
 * 策略：命中且未过期直接回；未命中/过期不拦截（放 WebView 自己拉，不在资源
 * 线程上等网络），同时后台拉一份存盘供下次用。任何异常都退回「不拦截」，
 * 绝不影响页面可用性。登录相关路径一律不缓存——登录 JS 里是加密与风控逻辑，
 * 宁可慢一点，也不能拿到旧的。
 */
class JwAssetCache(
    private val client: OkHttpClient,
    private val dir: File,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {
    private val executor: ExecutorService =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "jw-asset-cache").apply { isDaemon = true }
        }

    /** 命中且未过期时返回资源；否则返回 null，并视情况后台拉一份供下次用 */
    fun intercept(url: String): CachedAsset? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        if (!shouldCache(parsed)) return null
        val hit = readCache(parsed)
        if (hit != null) return hit
        prefetchAsync(url)
        return null
    }

    /** 是否值得缓存：认识的静态扩展名，且不是登录相关路径 */
    fun shouldCache(url: HttpUrl): Boolean {
        val path = url.encodedPath
        if (path.contains("login", ignoreCase = true)) return false
        return extOf(url) in CACHED_EXTENSIONS
    }

    /** 后台拉一份存盘（WebView 未命中时调用）；失败静默 */
    fun prefetchAsync(url: String) {
        executor.execute { runCatching { fetchAndStore(url) } }
    }

    /** 测试用：等后台刷新队列清空 */
    internal fun joinBackgroundWork() {
        executor.submit {}.get()
    }

    /** 同步抓取并落盘；返回抓到的资源。失败返回 null，不留半个文件 */
    internal fun fetchAndStore(url: String): CachedAsset? {
        val fetched = fetchRemote(url) ?: return null
        return writeToDisk(fetched)
    }

    /** 一次成功的静态资源抓取：缓存 key、扩展名（MIME 兜底用）与资源本体 */
    private data class Fetched(
        val key: String,
        val ext: String,
        val asset: CachedAsset,
    )

    private fun fetchRemote(url: String): Fetched? {
        val parsed = url.toHttpUrlOrNull()?.takeIf { shouldCache(it) } ?: return null
        val request =
            Request
                .Builder()
                .url(parsed)
                .header("User-Agent", WEB_UA)
                .get()
                .build()
        val fetched =
            runCatching {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body
                    val bytes = if (responseBody != null) responseBody.bytes() else ByteArray(0)
                    if (response.isSuccessful && bytes.isNotEmpty()) {
                        Fetched(keyOf(parsed), extOf(parsed), assetOf(response, bytes, extOf(parsed)))
                    } else {
                        null
                    }
                }
            }.getOrNull()
        return fetched
    }

    /** 抓到的响应 → 资源；Content-Type 缺失时按扩展名兜底 */
    private fun assetOf(
        response: Response,
        bytes: ByteArray,
        ext: String,
    ): CachedAsset {
        val header = response.header("Content-Type")
        val contentType =
            header
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf(String::isNotBlank)
                ?: MIME_BY_EXT[ext]
                ?: "application/octet-stream"
        val charset =
            header
                ?.substringAfter("charset=", "")
                ?.substringBefore(';')
                ?.trim()
                ?.takeIf(String::isNotBlank)
        return CachedAsset(contentType, charset, bytes, nowMs())
    }

    /** 落盘：临时文件写全后改名，读到半个文件的概率交给长度校验兜底 */
    private fun writeToDisk(fetched: Fetched): CachedAsset? {
        dir.mkdirs()
        val bodyTmp = File(dir, "${fetched.key}.body.tmp")
        val metaTmp = File(dir, "${fetched.key}.meta.tmp")
        val bytes = fetched.asset.bytes
        bodyTmp.writeBytes(bytes)
        metaTmp.writeText(
            listOf(
                fetched.asset.contentType,
                fetched.asset.charset.orEmpty(),
                fetched.asset.fetchedAt.toString(),
                bytes.size.toString(),
            ).joinToString("\n"),
        )
        val bodyOk = bodyTmp.renameTo(File(dir, fetched.key))
        val metaOk = metaTmp.renameTo(File(dir, "${fetched.key}.meta"))
        if (!bodyOk || !metaOk) {
            bodyTmp.delete()
            metaTmp.delete()
            return null
        }
        trim()
        return fetched.asset
    }

    /**
     * 磁盘读取。meta 四行：contentType / charset / fetchedAt / bodyLength；
     * 长度对不上（并发写入只换了一半）视为未命中，让 WebView 自己拉。
     */
    private fun readCache(url: HttpUrl): CachedAsset? {
        val cached = runCatching { loadFromDisk(keyOf(url)) }.getOrNull()
        return cached
    }

    private fun loadFromDisk(key: String): CachedAsset? {
        val meta = File(dir, "$key.meta")
        if (!meta.isFile) return null
        val lines = meta.readLines()
        val fetchedAt = lines.getOrNull(META_FETCHED_AT)?.toLongOrNull()
        val length = lines.getOrNull(META_LENGTH)?.toLongOrNull()
        val body = File(dir, key)
        val bytes =
            if (fetchedAt == null || length == null || !body.isFile) {
                null
            } else {
                body.readBytes().takeIf { it.size.toLong() == length }
            }
        // 过期的缓存顺手清掉，别等 trim
        if (fetchedAt != null && nowMs() - fetchedAt > TTL_MS) {
            meta.delete()
            body.delete()
        }
        return if (bytes != null && fetchedAt != null && nowMs() - fetchedAt <= TTL_MS) {
            CachedAsset(
                lines[META_CONTENT_TYPE],
                lines[META_CHARSET].takeIf(String::isNotBlank),
                bytes,
                fetchedAt,
            )
        } else {
            null
        }
    }

    /** 目录超上限时按「最旧优先」淘汰，兜住失控站点的磁盘占用 */
    private fun trim() {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".tmp") } ?: return
        var toFree = files.sumOf { it.length() } - TARGET_BYTES
        if (toFree <= 0) return
        files.sortedBy { it.lastModified() }.forEach { file ->
            if (toFree <= 0) return@forEach
            toFree -= file.length()
            file.delete()
        }
    }

    private fun keyOf(url: HttpUrl): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(url.toString().toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun extOf(url: HttpUrl): String = url.encodedPath.substringAfterLast('.', "").lowercase()

    /** 命中的资源：WebResourceResponse 三要素 + 命中时间 */
    class CachedAsset(
        val contentType: String,
        val charset: String?,
        val bytes: ByteArray,
        val fetchedAt: Long,
    )

    private companion object {
        /** 资源版本随 URL 变（cache_id），7 天足够覆盖一个学期的使用节奏 */
        const val TTL_MS = 7L * 24 * 60 * 60 * 1000

        const val TARGET_BYTES = 8L * 1024 * 1024

        /** meta 文件行序：contentType / charset / fetchedAt / bodyLength */
        const val META_CONTENT_TYPE = 0
        const val META_CHARSET = 1
        const val META_FETCHED_AT = 2
        const val META_LENGTH = 3

        /** 与教务 WebView 一致的 UA：有的站点会按 UA 发不同内容 */
        const val WEB_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

        val CACHED_EXTENSIONS =
            setOf(
                "js", "mjs", "css",
                "png", "jpg", "jpeg", "gif", "webp", "svg", "ico",
                "woff", "woff2", "ttf", "otf", "eot",
            )

        val MIME_BY_EXT =
            mapOf(
                "js" to "application/javascript",
                "mjs" to "text/javascript",
                "css" to "text/css",
                "png" to "image/png",
                "jpg" to "image/jpeg",
                "jpeg" to "image/jpeg",
                "gif" to "image/gif",
                "webp" to "image/webp",
                "svg" to "image/svg+xml",
                "ico" to "image/x-icon",
                "woff" to "font/woff",
                "woff2" to "font/woff2",
                "ttf" to "font/ttf",
                "otf" to "font/otf",
                "eot" to "application/vnd.ms-fontobject",
            )
    }
}
