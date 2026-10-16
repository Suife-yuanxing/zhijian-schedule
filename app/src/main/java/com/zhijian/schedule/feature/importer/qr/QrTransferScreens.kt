package com.zhijian.schedule.feature.importer.qr

import android.Manifest
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.zhijian.schedule.R
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.pressScale
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 分享二维码的生成结果：位图 / 门数 / 失败原因（超容量等）/ 原始载荷（分享文件按需高分辨率重渲染） */
data class QrShareState(
    val bitmap: Bitmap? = null,
    val courseCount: Int = 0,
    val message: String? = null,
    val payload: ByteArray? = null,
)

/**
 * 分享码字节流 → 二维码位图；纠错 L 以换取最大容量。
 * marginModules=0 供界面展示（白色卡片提供静区）；分享文件用 4 模块白边保证独立可扫。
 */
fun renderQrBitmap(bytes: ByteArray, sizePx: Int, marginModules: Int = 0): Bitmap {
    val matrix = QRCodeWriter().encode(
        String(bytes, Charsets.ISO_8859_1),
        BarcodeFormat.QR_CODE,
        sizePx,
        sizePx,
        mapOf(
            EncodeHintType.CHARACTER_SET to "ISO-8859-1",
            EncodeHintType.MARGIN to marginModules,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        ),
    )
    val pixels = IntArray(sizePx * sizePx)
    var i = 0
    for (y in 0 until sizePx) {
        for (x in 0 until sizePx) {
            pixels[i++] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
    }
    return Bitmap.createBitmap(pixels, sizePx, sizePx, Bitmap.Config.ARGB_8888)
}

/** 扫码导入：全屏相机取景 + 中央取景框；识别到第一个二维码即回调解码结果；也可从相册选码 */
@Composable
fun QrScanContent(onDecoded: (ByteArray) -> Unit, onSwitchToShare: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val bytes = decodeQrFromImage(context, uri)
                if (bytes != null) {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onDecoded(bytes)
                } else {
                    Toast.makeText(context, "没在图片里找到课程二维码，换一张试试", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    val pickFromGallery = {
        imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (hasPermission) {
            CameraPreview(
                onDecoded = { bytes ->
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onDecoded(bytes)
                },
            )
            Box(
                Modifier
                    .size(252.dp)
                    .align(Alignment.Center)
                    .border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(18.dp)),
            )
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, bottom = 30.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("将同学的分享码放入框内，自动识别", color = Color.White, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                ScanActionIsland(
                    onPickGallery = pickFromGallery,
                    onSwitchToShare = onSwitchToShare,
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text("需要相机权限才能扫码", color = Color.White, fontSize = 15.sp)
                Spacer(Modifier.height(6.dp))
                Text(
                    "相机仅在本机解析二维码，不拍照、不上传",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("授予相机权限", color = Color.White)
                }
                TextButton(onClick = pickFromGallery) {
                    Text("或从相册选二维码图片", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                }
            }
        }
    }
}

/** 底部动作浮岛：两枚动作共处一条悬浮胶囊，两侧留白、细分隔线、按压有浅色反馈 */
@Composable
private fun ScanActionIsland(onPickGallery: () -> Unit, onSwitchToShare: () -> Unit) {
    val shape = RoundedCornerShape(percent = 50)
    Row(
        Modifier
            .fillMaxWidth()
            .height(54.dp)
            .shadow(
                elevation = 12.dp,
                shape = shape,
                ambientColor = Color.Black.copy(alpha = 0.25f),
                spotColor = Color.Black.copy(alpha = 0.45f),
            )
            .clip(shape)
            .background(Color.Black.copy(alpha = 0.44f))
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape),
    ) {
        ScanIslandAction(
            iconRes = R.drawable.ic_scan_gallery,
            label = "从相册选码",
            onClick = onPickGallery,
            modifier = Modifier.weight(1f),
        )
        Box(
            Modifier
                .align(Alignment.CenterVertically)
                .width(1.dp)
                .height(22.dp)
                .background(Color.White.copy(alpha = 0.14f)),
        )
        ScanIslandAction(
            iconRes = R.drawable.ic_scan_share,
            label = "生成我的分享码",
            onClick = onSwitchToShare,
            modifier = Modifier.weight(1f),
        )
    }
}

/** 浮岛内的单个动作：按下时整格浅色反馈，图标与文字轻微收缩（与全 App 按压语言一致） */
@Composable
private fun ScanIslandAction(
    @DrawableRes iconRes: Int,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier
            .fillMaxHeight()
            .background(if (pressed) Color.White.copy(alpha = 0.09f) else Color.Transparent)
            .clickable(interactionSource = source, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier.pressScale(source, pressedScale = 0.94f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.94f),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(7.dp))
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.94f),
                fontSize = 13.5.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

/** 分享二维码：白色卡片承载位图（二维码需要白色静区），附课程数与使用说明 */
@Composable
fun QrShareContent(state: QrShareState?, onSwitchToScan: () -> Unit) {
    val colors = LocalCourseColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var sharing by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        when {
            state == null -> {
                CircularProgressIndicator(color = colors.accent)
                Spacer(Modifier.height(12.dp))
                Text("正在生成分享码…", color = colors.textMuted, fontSize = 13.sp)
            }

            state.message != null -> Text(
                text = state.message,
                color = colors.text,
                fontSize = 14.sp,
            )

            state.bitmap != null -> {
                val payload = state.payload
                Box(
                    Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color.White)
                        .padding(14.dp),
                ) {
                    Image(
                        bitmap = state.bitmap.asImageBitmap(),
                        contentDescription = "课程分享二维码",
                        modifier = Modifier.size(276.dp),
                    )
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    text = "共 ${state.courseCount} 门课程",
                    color = colors.text,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "也可把图片发给同学，在扫码页从相册选码导入",
                    color = colors.textMuted,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(18.dp))
                if (payload != null) {
                    val shareSource = remember { MutableInteractionSource() }
                    Button(
                        onClick = {
                            if (sharing) return@Button
                            sharing = true
                            scope.launch {
                                val uri = withContext(Dispatchers.IO) { writeShareQrPng(context, payload) }
                                sharing = false
                                if (uri == null) {
                                    Toast.makeText(context, "生成分享图片失败，请重试", Toast.LENGTH_SHORT).show()
                                } else if (!shareQrPng(context, uri)) {
                                    Toast.makeText(context, "没有可用的分享应用", Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        enabled = !sharing,
                        interactionSource = shareSource,
                        modifier = Modifier.pressScale(shareSource, pressedScale = 0.97f),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (sharing) "正在准备…" else "分享二维码")
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onSwitchToScan) { Text("去扫别人的码") }
    }
}

/** 高分辨率 + 白边重渲染 → 缓存 PNG → FileProvider URI；失败返回 null */
private fun writeShareQrPng(context: Context, payload: ByteArray): Uri? = runCatching {
    val dir = File(context.cacheDir, "qr_share").apply { mkdirs() }
    val file = File(dir, "zhijian_timetable_qr.png")
    val bitmap = renderQrBitmap(payload, 1600, marginModules = 4)
    FileOutputStream(file).use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
    }
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}.getOrNull()

/** 调起系统分享面板（微信/QQ 等可直接发送）；返回是否成功调起 */
private fun shareQrPng(context: Context, uri: Uri): Boolean = runCatching {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newUri(context.contentResolver, "zhijian_timetable_qr", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "分享课程二维码"))
}.isSuccess

/** 相册里的二维码图片 → 本机离线解码（同一张分享图截图/转发后依然可识别） */
private suspend fun decodeQrFromImage(context: Context, uri: Uri): ByteArray? =
    withContext(Dispatchers.IO) {
        val scanner = BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
        try {
            val image = InputImage.fromFilePath(context, uri)
            val result = CompletableDeferred<ByteArray?>()
            scanner.process(image)
                .addOnSuccessListener { codes ->
                    result.complete(codes.firstOrNull { it.rawBytes?.isNotEmpty() == true }?.rawBytes)
                }
                .addOnFailureListener { result.complete(null) }
                .addOnCompleteListener {
                    // Task 被取消时 success/failure 都不回调，这里兜底避免 await 永久挂起
                    if (!result.isCompleted) result.complete(null)
                }
            result.await()
        } catch (_: Exception) {
            null
        } finally {
            scanner.close()
        }
    }

/** 相机预览 + 二维码分析（ML Kit 离线识别，识别到即回调一次） */
@Composable
private fun CameraPreview(onDecoded: (ByteArray) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val decodedOnce = remember { AtomicBoolean(false) }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var analyzer: QrAnalyzer? = null
        providerFuture.addListener({
            runCatching {
                val cameraProvider = providerFuture.get()
                provider = cameraProvider
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 960),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                )
                            )
                            .build()
                    )
                    .build()
                val qrAnalyzer = QrAnalyzer { bytes ->
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
            provider?.unbindAll()
            analyzer?.close()
            executor.shutdown()
        }
    }

    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}

private class QrAnalyzer(private val onQr: (ByteArray) -> Unit) : ImageAnalysis.Analyzer {

    private val scanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
    )
    private val processing = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        val media = image.image
        if (media == null || !processing.compareAndSet(false, true)) {
            image.close()
            return
        }
        scanner.process(InputImage.fromMediaImage(media, image.imageInfo.rotationDegrees))
            .addOnSuccessListener { codes ->
                codes.firstOrNull { it.rawBytes?.isNotEmpty() == true }?.rawBytes?.let(onQr)
            }
            .addOnCompleteListener {
                processing.set(false)
                image.close()
            }
    }

    fun close() {
        scanner.close()
    }
}
