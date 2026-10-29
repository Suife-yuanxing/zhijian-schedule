package com.zhijian.schedule.feature.importer.qr

import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** 相机预览 + 二维码分析（ML Kit 离线识别，识别到即回调一次） */
@Composable
internal fun CameraPreview(onDecoded: (ByteArray) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView =
        remember {
            PreviewView(context).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                // SurfaceView 独立于窗口图层，退出时无法随 Compose 一起淡出/位移，会留下残影；
                // TextureView 参与窗口合成，转场和窗口动画都能正常跟随。
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val decodedOnce = remember { AtomicBoolean(false) }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var analyzer: QrAnalyzer? = null
        var disposed = false
        providerFuture.addListener({
            // 相机初始化是异步的：若页面在回调前已退出，不能再绑相机，否则相机会
            // 一直挂到 Activity 结束，且预览视图已销毁无法再释放。
            if (disposed) return@addListener
            runCatching {
                val cameraProvider = providerFuture.get()
                provider = cameraProvider
                val preview =
                    Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }
                val analysis =
                    ImageAnalysis
                        .Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setResolutionSelector(
                            ResolutionSelector
                                .Builder()
                                .setResolutionStrategy(
                                    ResolutionStrategy(
                                        Size(1280, 960),
                                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                    ),
                                ).build(),
                        ).build()
                val qrAnalyzer =
                    QrAnalyzer { bytes ->
                        if (decodedOnce.compareAndSet(false, true)) onDecoded(bytes)
                    }
                analyzer = qrAnalyzer
                analysis.setAnalyzer(executor, qrAnalyzer)
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            disposed = true
            provider?.unbindAll()
            analyzer?.close()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

private class QrAnalyzer(
    private val onQr: (ByteArray) -> Unit,
) : ImageAnalysis.Analyzer {
    private val scanner =
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
        )
    private val processing = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (media == null || !processing.compareAndSet(false, true)) {
            image.close()
            return
        }
        scanner
            .process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
            .addOnSuccessListener { codes ->
                codes.firstOrNull { it.rawBytes?.isNotEmpty() == true }?.rawBytes?.let(onQr)
            }.addOnCompleteListener {
                processing.set(false)
                image.close()
            }
    }

    fun close() {
        scanner.close()
    }
}
