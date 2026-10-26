package com.zhijian.schedule.feature.importer.ocr

/** 一行 OCR 文本及其在图片中的包围盒（像素坐标）。 */
data class OcrLine(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val height: Float get() = (bottom - top).coerceAtLeast(1f)
}
