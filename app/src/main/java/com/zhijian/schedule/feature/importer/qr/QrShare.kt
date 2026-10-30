package com.zhijian.schedule.feature.importer.qr

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.zhijian.schedule.core.theme.LocalCourseColors
import com.zhijian.schedule.core.ui.RippleLoader
import com.zhijian.schedule.core.ui.TapTextButton
import com.zhijian.schedule.core.ui.buttonFeedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

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
fun renderQrBitmap(
    bytes: ByteArray,
    sizePx: Int,
    marginModules: Int = 0,
): Bitmap {
    val matrix =
        QRCodeWriter().encode(
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

/** 分享二维码：白色卡片承载位图（二维码需要白色静区），附课程数与使用说明 */
@Composable
fun QrShareContent(
    state: QrShareState?,
    onSwitchToScan: () -> Unit,
) {
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
                // 生成分享码要把整份课表编码进二维码，课上得多时有明显停顿。
                // 涟漪 + 一行说明，比一个转圈更能说明「在算，而不是在等网络」。
                RippleLoader(
                    modifier = Modifier.size(88.dp),
                    color = colors.accent,
                    rings = 4,
                )
                Spacer(Modifier.height(12.dp))
                Text("正在生成分享码…", color = colors.textMuted, fontSize = 13.sp)
            }

            state.message != null ->
                Text(
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
                        modifier = Modifier.buttonFeedback(shareSource, pressedScale = 0.97f),
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(if (sharing) "正在准备…" else "分享二维码")
                    }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        TapTextButton(onClick = onSwitchToScan) { Text("去扫别人的码") }
    }
}

/** 高分辨率 + 白边重渲染 → 缓存 PNG → FileProvider URI；失败返回 null */
private fun writeShareQrPng(
    context: Context,
    payload: ByteArray,
): Uri? =
    runCatching {
        val dir = File(context.cacheDir, "qr_share").apply { mkdirs() }
        val file = File(dir, "zhijian_timetable_qr.png")
        val bitmap = renderQrBitmap(payload, 1600, marginModules = 4)
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }.getOrNull()

/** 调起系统分享面板（微信/QQ 等可直接发送）；返回是否成功调起 */
private fun shareQrPng(
    context: Context,
    uri: Uri,
): Boolean =
    runCatching {
        val intent =
            Intent(Intent.ACTION_SEND).apply {
                type = "image/png"
                putExtra(Intent.EXTRA_STREAM, uri)
                clipData = ClipData.newUri(context.contentResolver, "zhijian_timetable_qr", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        context.startActivity(Intent.createChooser(intent, "分享课程二维码"))
    }.isSuccess
