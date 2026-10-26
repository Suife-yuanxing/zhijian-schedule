package com.zhijian.schedule.feature.importer.parsers

import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** 文本解码：UTF-8 优先，失败回退 GBK（国内 Excel 导出常见编码）。 */
internal object TextDecoding {

    fun decode(bytes: ByteArray): String = try {
        Charset.forName("UTF-8").newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: Exception) {
        runCatching { Charset.forName("GBK").decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { String(bytes, Charsets.UTF_8) }
    }
}
