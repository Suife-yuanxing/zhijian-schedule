package com.zhijian.schedule.core.update

import com.zhijian.schedule.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 应用内检查更新：GitHub Releases（方案 1.4 自分发 / 8.2-7）。
 */
@Singleton
class UpdateChecker @Inject constructor(
    private val client: OkHttpClient,
) {

    data class UpdateInfo(
        val version: String,
        val pageUrl: String,
        val apkUrl: String?,
        /** assets[].size：下载前展示大小，同时作为进度分母的兜底 */
        val apkSize: Long?,
        /** assets[].digest（"sha256:..."）：下载完成后的完整性校验；旧 Release 可能没有 */
        val apkDigest: String?,
        val notes: String,
    )

    /** 最近一次成功检查的缓存（启动自动检查后，窗口打开时直接展示、不再重复请求） */
    data class CachedCheck(val info: UpdateInfo?)

    @Volatile
    private var lastSuccessAt = 0L

    @Volatile
    private var lastSuccessInfo: UpdateInfo? = null

    fun cachedCheck(): CachedCheck? {
        val at = lastSuccessAt
        if (at == 0L || System.currentTimeMillis() - at > CACHE_TTL_MS) return null
        return CachedCheck(lastSuccessInfo)
    }

    suspend fun check(): Result<UpdateInfo?> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/$GITHUB_REPO/releases/latest")
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IllegalStateException("检查更新失败（HTTP ${response.code}）")
                }
                val body = response.body?.string().orEmpty()
                val root = Json.parseToJsonElement(body) as? JsonObject
                    ?: throw IllegalStateException("响应格式异常")
                val tag = root["tag_name"]?.jsonPrimitive?.content.orEmpty()
                val version = tag.removePrefix("v")
                val pageUrl = root["html_url"]?.jsonPrimitive?.content.orEmpty()
                val notes = root["body"]?.jsonPrimitive?.content.orEmpty()
                val apkAsset = (root["assets"] as? JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    ?.firstOrNull { asset ->
                        asset["name"]?.jsonPrimitive?.content?.endsWith(".apk", ignoreCase = true) == true
                    }
                val apkUrl = apkAsset?.get("browser_download_url")?.jsonPrimitive?.content
                val apkSize = apkAsset?.get("size")?.jsonPrimitive?.longOrNull
                val apkDigest = apkAsset?.get("digest")?.jsonPrimitive?.contentOrNull
                if (isNewer(version, BuildConfig.VERSION_NAME)) {
                    UpdateInfo(
                        version = version,
                        pageUrl = pageUrl,
                        apkUrl = apkUrl,
                        apkSize = apkSize,
                        apkDigest = apkDigest,
                        notes = notes,
                    )
                } else null
            }
        }.onSuccess { info ->
            lastSuccessInfo = info
            lastSuccessAt = System.currentTimeMillis()
        }
    }

    private fun isNewer(remote: String, current: String): Boolean {
        if (remote.isBlank()) return false
        val r = remote.split(".").mapNotNull { it.toIntOrNull() }
        val c = current.split(".").mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(r.size, c.size)) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv != cv) return rv > cv
        }
        return false
    }

    companion object {
        /** 发布仓库（GitHub Releases 分发，方案 1.4） */
        const val GITHUB_REPO = "Suife-yuanxing/zhijian-schedule"

        /** 成功检查结果的缓存有效期 */
        private const val CACHE_TTL_MS = 15 * 60_000L
    }
}
