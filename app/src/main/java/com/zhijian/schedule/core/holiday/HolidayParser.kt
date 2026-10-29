package com.zhijian.schedule.core.holiday

import com.zhijian.schedule.core.data.db.HolidayEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * 节假日数据解析（纯函数，便于单测）。
 * 主源 holiday-cn（GitHub 静态 JSON），兜底 timor.tech API。
 */
object HolidayParser {
    /** holiday-cn：{days:[{name,date,isOffDay}]} */
    fun parseHolidayCn(json: String): List<HolidayEntity> =
        runCatching {
            val root = Json.parseToJsonElement(json) as? JsonObject ?: return@runCatching emptyList()
            val days = root["days"] as? JsonArray ?: return@runCatching emptyList()
            days.mapNotNull { el ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val date = obj["date"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val off = obj["isOffDay"]?.jsonPrimitive?.booleanOrNull ?: return@mapNotNull null
                HolidayEntity(date = date, name = obj["name"]?.jsonPrimitive?.content.orEmpty(), isOffDay = off)
            }
        }.getOrDefault(emptyList())

    /** timor.tech：{code:0, holiday:{"01-01":{holiday:true,name:"元旦",date:"2026-01-01"}}} */
    fun parseTimor(
        json: String,
        year: Int,
    ): List<HolidayEntity> =
        runCatching {
            val root = Json.parseToJsonElement(json) as? JsonObject ?: return@runCatching emptyList()
            if ((root["code"]?.jsonPrimitive?.intOrNull ?: -1) != 0) return@runCatching emptyList()
            val map = root["holiday"] as? JsonObject ?: return@runCatching emptyList()
            map.entries.mapNotNull { (key, el) ->
                val obj = el as? JsonObject ?: return@mapNotNull null
                val off = obj["holiday"]?.jsonPrimitive?.booleanOrNull ?: return@mapNotNull null
                val date =
                    obj["date"]?.jsonPrimitive?.content
                        ?: if (Regex("""\d{2}-\d{2}""").matches(key)) "$year-$key" else return@mapNotNull null
                HolidayEntity(date = date, name = obj["name"]?.jsonPrimitive?.content.orEmpty(), isOffDay = off)
            }
        }.getOrDefault(emptyList())
}
