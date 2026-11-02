package com.zhijian.schedule.core.update

import java.io.File
import java.security.MessageDigest

/** 文件摘要工具：拼装、基线校验、patch.bin 校验三处共用，避免各自抄一份十六进制格式化 */
object UpdateDigest {
    /** 算文件的 SHA-256，返回小写十六进制（不带 `sha256:` 前缀，与 [ResumableDownload.normalizeDigest] 输出对齐） */
    fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().toHex()
    }

    private fun ByteArray.toHex(): String {
        val out = StringBuilder(size * 2)
        for (byte in this) {
            val v = byte.toInt() and BYTE_MASK
            out.append(HEX[v ushr NIBBLE_BITS]).append(HEX[v and NIBBLE_MASK])
        }
        return out.toString()
    }

    private const val BUFFER = 64 * 1024
    private const val HEX = "0123456789abcdef"

    /** 取低 8 位：把有符号 Byte 抬成 0..255 的无符号值 */
    private const val BYTE_MASK = 0xFF

    /** 一个十六进制字符占 4 位（半个字节） */
    private const val NIBBLE_BITS = 4

    /** 取低 4 位：一个字节拆成高低两个十六进制字符 */
    private const val NIBBLE_MASK = 0x0F
}
