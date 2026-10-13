package com.zhijian.schedule.core.holiday

import com.zhijian.schedule.core.data.db.HolidayDao
import com.zhijian.schedule.core.data.db.HolidayEntity
import com.zhijian.schedule.core.data.prefs.SettingsStore
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

private const val REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000L // 一天内不重复拉取

/** 节假日/调休数据：按年拉取并缓存到 Room，失败静默降级（只是没有标注，不影响其他功能） */
@Singleton
class HolidayRepository @Inject constructor(
    private val client: OkHttpClient,
    private val holidayDao: HolidayDao,
    private val settingsStore: SettingsStore,
) {

    val holidays: Flow<List<HolidayEntity>> = holidayDao.observeAll()

    suspend fun ensureFresh() = withContext(Dispatchers.IO) {
        runCatching {
            val year = LocalDate.now().year
            val settings = settingsStore.settings.first()
            val fresh = System.currentTimeMillis() - settings.holidayFetchedAt < REFRESH_INTERVAL_MS
            if (fresh && holidayDao.countLike("$year-%") > 0) return@runCatching
            val items = fetchAll(year)
            if (items.isEmpty()) return@runCatching
            holidayDao.upsertAll(items)
            settingsStore.setHolidayFetchedAt(System.currentTimeMillis())
        }
    }

    /**
     * holiday-cn 静态 JSON 多级兜底（国内网络下 cdn.jsdelivr 时常被重置，
     * fastly/gcore 镜像与 gh-proxy 通常可用），全部失败再试 timor.tech。
     */
    private fun fetchAll(year: Int): List<HolidayEntity> {
        val path = "NateScarlet/holiday-cn@master/$year.json"
        val urls = listOf(
            "https://cdn.jsdelivr.net/gh/$path",
            "https://fastly.jsdelivr.net/gh/$path",
            "https://gcore.jsdelivr.net/gh/$path",
            "https://gh-proxy.com/https://raw.githubusercontent.com/NateScarlet/holiday-cn/master/$year.json",
        )
        urls.forEach { url ->
            val json = httpGet(url) ?: return@forEach
            val items = HolidayParser.parseHolidayCn(json)
            if (items.isNotEmpty()) return items
        }
        val timor = httpGet("https://timor.tech/api/holiday/year/$year") ?: return emptyList()
        return HolidayParser.parseTimor(timor, year)
    }

    private fun httpGet(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "ZhiJianTimetable/1.0 (Android)")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            if (response.isSuccessful) response.body?.string() else null
        }
    }.getOrNull()
}
