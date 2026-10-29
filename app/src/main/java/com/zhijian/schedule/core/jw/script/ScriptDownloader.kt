package com.zhijian.schedule.core.jw.script

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/** 远程脚本仓库拉取（spec §3.1）；带 sha256 时校验通过才把脚本文本交给调用方 */
@Singleton
class ScriptDownloader
    @Inject
    constructor(
        private val client: OkHttpClient,
    ) {
        sealed interface Result {
            data class Ok(
                val text: String,
            ) : Result

            data class Failed(
                val message: String,
            ) : Result
        }

        suspend fun fetchText(
            url: String,
            sha256: String? = null,
            maxBytes: Int = 2_000_000,
        ): Result =
            withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder().url(url).build()
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return@use Result.Failed("下载失败（HTTP ${response.code}）")
                        val bytes = response.body?.bytes() ?: return@use Result.Failed("下载内容为空")
                        if (bytes.size > maxBytes) return@use Result.Failed("下载内容超出大小限制")
                        if (sha256 != null) {
                            val actual = ScriptIndexFns.sha256Hex(bytes)
                            if (!actual.equals(sha256, ignoreCase = true)) {
                                return@use Result.Failed("SHA-256 校验失败，脚本可能被篡改，已放弃安装")
                            }
                        }
                        Result.Ok(bytes.toString(Charsets.UTF_8))
                    }
                } catch (e: Exception) {
                    Result.Failed("下载失败：${e.message ?: "网络异常"}")
                }
            }
    }
