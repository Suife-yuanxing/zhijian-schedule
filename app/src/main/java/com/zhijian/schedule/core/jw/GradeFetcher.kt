package com.zhijian.schedule.core.jw

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * 成绩抓取：强智 jsxsd `kscj/cjcx_list?kksj=`（kksj 留空 = 全部学期，一次拿到全部成绩）。
 * 与课表抓取共用 WebView 会话 Cookie（HTTP 重放）。
 */
object GradeFetcher {
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Mobile Safari/537.36"

    suspend fun fetch(
        client: OkHttpClient,
        baseUrl: String,
    ): List<GradeRecord> =
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
    ): List<GradeRecord> =
        withContext(Dispatchers.IO) {
            val base = QiangZhiAdapter.normalizeBase(baseUrl)
            val request =
                Request
                    .Builder()
                    .url("${base}kscj/cjcx_list?kksj=")
                    .header("User-Agent", UA)
                    .header("Referer", "${base}kscj/cjcx_frm")
                    .get()
                    .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw JwException.NetworkFailed("HTTP ${response.code}")
                val html = response.body?.string().orEmpty()
                if (isSessionDeadPage(html)) throw JwException.NeedsLogin()
                GradeParser.parse(html)
            }
        }
}
