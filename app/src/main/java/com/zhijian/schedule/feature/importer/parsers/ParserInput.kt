package com.zhijian.schedule.feature.importer.parsers

/**
 * 解析器输入：文本通道只有 [text]；文件通道额外带原始 [bytes] 与文件名。
 * [text] 已是按 UTF-8 → GBK 解码后的正文，文本类解析器直接用，容器类解析器用 [bytes]。
 */
class ParserInput(
    val text: String,
    val bytes: ByteArray? = null,
    val fileName: String? = null,
) {

    /** 去掉 BOM 与前导空白的正文，供文本类格式嗅探使用。 */
    val trimmedText: String
        get() = text.trimStart(*SNIFF_TRIM)

    /**
     * 头部 [limit] 字节按 UTF-8 粗读。
     * 只用于嗅探文件格式，不承担真实编码判断；没有字节时退化为 [trimmedText]。
     */
    fun sniffHead(limit: Int = SNIFF_BYTES): String {
        val source = bytes ?: return trimmedText
        val head = String(source.copyOfRange(0, minOf(limit, source.size)), Charsets.UTF_8)
        return head.trimStart(*SNIFF_TRIM)
    }

    /** zip 魔数：xlsx 是 zip 容器。 */
    val isZip: Boolean
        get() {
            val source = bytes ?: return false
            return source.size > 2 && source[0] == 0x50.toByte() && source[1] == 0x4B.toByte()
        }

    companion object {

        private const val SNIFF_BYTES = 64
        private val SNIFF_TRIM = charArrayOf('\uFEFF', ' ', '\n', '\r', '\t')

        /** 粘贴 / 口令通道的输入。 */
        fun ofText(text: String): ParserInput = ParserInput(text)

        /** 文件通道的输入：原始字节、解码后的正文与文件名。 */
        fun ofFile(bytes: ByteArray, text: String, fileName: String? = null): ParserInput =
            ParserInput(text = text, bytes = bytes, fileName = fileName)
    }
}
