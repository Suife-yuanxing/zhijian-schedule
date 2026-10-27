package com.zhijian.schedule.core.jw

import android.content.Context
import java.io.BufferedReader
import java.io.InputStreamReader

/** 学校目录在 APK assets 里的位置 */
private const val ASSET = "jw/schools.tsv"

/**
 * 读一次 assets 里的学校目录；重复调用无害。
 *
 * 与 [SchoolCatalog] 的检索 / 解析逻辑分开成两个文件：那边保持纯 JVM，
 * 单测直接喂字符串，这边才允许出现 `android.content.Context`。
 * 读取或解析失败时保留内置条目，不抛异常、不阻塞调用方。
 */
fun SchoolCatalog.ensureLoaded(context: Context) {
    if (SchoolCatalog.isLoaded) return
    val text =
        runCatching {
            context.assets.open(ASSET).use { input ->
                BufferedReader(InputStreamReader(input, Charsets.UTF_8)).readText()
            }
        }.getOrElse { "" }
    SchoolCatalog.loadFrom(text)
}
