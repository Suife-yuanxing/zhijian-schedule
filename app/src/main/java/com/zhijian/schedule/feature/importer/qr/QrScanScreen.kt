package com.zhijian.schedule.feature.importer.qr

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.zhijian.schedule.R
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.pressScale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 扫码导入：全屏相机取景 + 中央取景框；识别到第一个二维码即回调解码结果；也可从相册选码 */
@Composable
fun QrScanContent(
    onDecoded: (ByteArray) -> Unit,
    onSwitchToShare: () -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted -> hasPermission = granted }
    val imagePicker =
        rememberLauncherForActivityResult(
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
                TapTextButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text("授予相机权限", color = Color.White)
                }
                TapTextButton(onClick = pickFromGallery) {
                    Text("或从相册选二维码图片", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                }
            }
        }
    }
}

/** 底部动作浮岛：两枚动作共处一条悬浮胶囊，两侧留白、细分隔线、按压有浅色反馈 */
@Composable
private fun ScanActionIsland(
    onPickGallery: () -> Unit,
    onSwitchToShare: () -> Unit,
) {
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
            ).clip(shape)
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

/** 相册里的二维码图片 → 本机离线解码（同一张分享图截图/转发后依然可识别） */
private suspend fun decodeQrFromImage(
    context: Context,
    uri: Uri,
): ByteArray? =
    withContext(Dispatchers.IO) {
        val scanner =
            BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
            )
        try {
            val image = InputImage.fromFilePath(context, uri)
            val result = CompletableDeferred<ByteArray?>()
            scanner
                .process(image)
                .addOnSuccessListener { codes ->
                    result.complete(codes.firstOrNull { it.rawBytes?.isNotEmpty() == true }?.rawBytes)
                }.addOnFailureListener { result.complete(null) }
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
