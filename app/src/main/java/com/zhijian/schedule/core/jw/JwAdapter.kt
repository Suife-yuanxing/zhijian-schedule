package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.model.CourseDraft
import okhttp3.OkHttpClient

/**
 * 教务适配器（方案 4.6：脚本化适配生态的原生实现），
 * 与 WebView 共享 Cookie 完成 HTTP 重放（方案 4.10）。
 *
 * [match] 用打分代替布尔判断：分数越高越具体，注册表取最高分。
 * 不允许「任何 URL 都返回 true」的兜底适配器——那会把所有学校都按某一家解析。
 */
interface JwAdapter {
    val id: String
    val name: String

    /** 协议识别打分：0 = 不支持；分数越高越具体 */
    fun match(url: String): Int

    /** 兼容旧调用；新代码请直接用 [match] */
    fun supports(url: String): Boolean = match(url) > 0

    /**
     * 会话过期时可静默重登的 SSO 入口；返回 null 表示该适配器没有可重放的
     * GET 恢复链（例如正方新版只能由用户在 WebView 重新登录）。
     */
    fun ssoEntry(baseUrl: String): String? = null

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

    private val adapters: List<JwAdapter> = listOf(
        QiangZhiAdapter,
        ZhengFangNewAdapter,
    )

    /** 未识别时返回 null，由调用方给出明确提示；绝不静默回退到某一家厂商 */
    fun forUrl(url: String): JwAdapter? =
        adapters
            .map { it to it.match(url) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first

    fun all(): List<JwAdapter> = adapters
}
