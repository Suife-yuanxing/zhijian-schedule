package com.zhijian.schedule.core.jw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 考试安排抓取：强智 jsxsd，与课表/成绩共用 WebView 会话 Cookie（HTTP 重放）。
 * 各校部署路径不一，候选端点逐个尝试；页面结构差异由 ExamParser 容错。
 * 真实学校端点确认见 spec §2 开放项——先在 tools/mock-jw 跑通 E2E。
 */
object ExamFetcher {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    suspend fun fetch(
        client: OkHttpClient,
        baseUrl: String,
    ): List<ExamRecord> =
        withContext(Dispatchers.IO) {
            try {
                fetchRecords(client, baseUrl)
            } catch (e: JwException.NeedsLogin) {
                if (!JwSessionRecovery.trySilentRecover(client, QiangZhiAdapter, baseUrl)) throw e
                fetchRecords(client, baseUrl)
            }
        }

    private suspend fun fetchRecords(
        client: OkHttpClient,
        baseUrl: String,
    ): List<ExamRecord> =
        withContext(Dispatchers.IO) {
            val base = QiangZhiAdapter.normalizeBase(baseUrl)
            val referer = "${base}framework/xsMain.jsp"
            val candidates =
                listOf(
                    "${base}ksgl/xsksap_list",
                    "${base}ksgl/xskksap_list",
                    "${base}ksgl/xskksap.do",
                )
            var lastError: String? = null
            for (url in candidates) {
                val html =
                    try {
                        get(client, url, referer)
                    } catch (e: JwException) {
                        throw e
                    } catch (e: Exception) {
                        lastError = e.message
                        continue
                    }
                if (isSessionDeadPage(html)) throw JwException.NeedsLogin()
                val records = ExamParser.parse(html)
                if (records.isNotEmpty()) return@withContext records
                lastError = "未在页面中找到考试安排"
            }
            throw JwException.ParseFailed("抓取失败：${lastError ?: "页面结构不匹配"}")
        }

    private fun get(
        client: OkHttpClient,
        url: String,
        referer: String,
    ): String {
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", UA)
                .header("Referer", referer)
                .get()
                .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw httpFailure(response.code)
            return response.body?.string().orEmpty()
        }
    }
}
