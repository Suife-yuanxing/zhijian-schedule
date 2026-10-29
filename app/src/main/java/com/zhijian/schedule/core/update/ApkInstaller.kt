package com.zhijian.schedule.core.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 把校验通过的 APK 交给系统安装器。
 * minSdk 26：`file://` URI 在 API 24+ 会抛 FileUriExposedException，必须走 FileProvider。
 */
@Singleton
class ApkInstaller
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) {
        /** 「安装未知应用」是否已经放行。没放行时安装 Intent 会被系统静默丢掉，所以要先判断 */
        fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

        /** 跳「安装未知应用」授权页（带包名，直接落在本应用那一项）；返回后由 ON_RESUME 刷新状态 */
        fun unknownSourcesIntent(): Intent =
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** 调起系统安装器；失败信息交给调用方展示 */
        fun install(file: File): Result<Unit> =
            runCatching {
                require(file.isFile) { "安装包不存在" }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}$AUTHORITY_SUFFIX", file)
                context.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, APK_MIME)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }

        companion object {
            const val APK_MIME = "application/vnd.android.package-archive"
            const val AUTHORITY_SUFFIX = ".fileprovider"
        }
    }
