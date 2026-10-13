package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.model.CourseDraft
import okhttp3.OkHttpClient

/**
 * 教务适配器（方案 4.6：脚本化适配生态的原生实现），
 * 与 WebView 共享 Cookie 完成 HTTP 重放（方案 4.10）。
 */
interface JwAdapter {
    val id: String
    val name: String

    /** 该适配器是否支持此教务地址 */
    fun supports(url: String): Boolean

    /**
     * 抓取并解析为标准课程列表；未登录时抛 [JwException.NeedsLogin]。
     * @param baseUrl 用户在 WebView 中登录的教务入口地址
     */
    suspend fun fetch(client: OkHttpClient, baseUrl: String, totalWeeks: Int): List<CourseDraft>
}

sealed class JwException(message: String) : Exception(message) {
    class NeedsLogin : JwException("尚未登录或登录已过期，请先在网页中登录教务")
    class ParseFailed(message: String) : JwException(message)
    class NetworkFailed(message: String) : JwException(message)
}

object JwAdapterRegistry {
    private val adapters: List<JwAdapter> = listOf(ZhengFangAdapter)

    /** 未匹配到专用适配器时使用正方适配器作为默认（覆盖最广） */
    fun forUrl(url: String): JwAdapter =
        adapters.firstOrNull { it.supports(url) } ?: ZhengFangAdapter

    fun all(): List<JwAdapter> = adapters
}
