package com.zhijian.schedule.feature.importer.ocr

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** ML Kit 中文识别（内置离线模型，识别行文本与坐标交给 [OcrTimetableParser]）。 */
@Singleton
class ImageOcr @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    suspend fun recognize(uri: Uri): List<OcrLine> = withContext(Dispatchers.IO) {
        val image = InputImage.fromFilePath(context, uri)
        recognizer.process(image).await().textBlocks
            .flatMap { it.lines }
            .mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                OcrLine(
                    text = line.text,
                    left = box.left.toFloat(),
                    top = box.top.toFloat(),
                    right = box.right.toFloat(),
                    bottom = box.bottom.toFloat(),
                )
            }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
        addOnSuccessListener { cont.resume(it) }
        addOnFailureListener { cont.resumeWithException(it) }
        addOnCanceledListener { cont.cancel() }
    }
}
