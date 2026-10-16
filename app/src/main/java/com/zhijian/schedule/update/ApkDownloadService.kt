package com.zhijian.schedule.update

import android.app.Notification
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 更新下载的 dataSync 前台服务：把下载协程的存活期绑在服务上，并在通知栏显示进度。
 * 状态本身在 [ApkDownloader]（Singleton），服务只负责「保活 + 渲染通知」。
 * `START_NOT_STICKY`：不做静默重启，分片已经落盘，用户重试即可。
 */
@AndroidEntryPoint
class ApkDownloadService : Service() {

    @Inject
    lateinit var downloader: ApkDownloader

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val throttle = ProgressThrottle()

    private var job: Job? = null
    private var watcher: Job? = null
    private var finished = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        UpdateNotification.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == UpdateNotification.ACTION_CANCEL) {
            downloader.cancel()
            return START_NOT_STICKY
        }
        val request = intent.toDownloadRequest()
        if (request == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (job?.isActive == true) {
            // 同一时刻只允许一个下载：重复启动只补上前台通知，满足前台服务的契约
            runCatching { goForeground(statusNotification(request)) }
            return START_NOT_STICKY
        }
        if (!runCatching { goForeground(statusNotification(request)) }.isSuccess) {
            // Android 12+ 后台启动前台服务受限（例如点按后立刻切走）：直接收尾，状态不变由用户重试
            stopSelf()
            return START_NOT_STICKY
        }

        watcher = scope.launch { downloader.state.collect { renderProgress(it) } }
        job = scope.launch {
            downloader.download(request)
            finish()
        }
        return START_NOT_STICKY
    }

    /** Android 15+ 的 dataSync 前台服务有累计时长上限，到点必须收尾，否则系统回调会抛异常 */
    @RequiresApi(35)
    override fun onTimeout(startId: Int) {
        super.onTimeout(startId)
        downloader.timeout()
        finish()
    }

    override fun onDestroy() {
        // 系统回收（含用户划掉任务）：把 Running 落回 Paused，分片保留
        if (!finished) downloader.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun renderProgress(state: DownloadState) {
        if (state !is DownloadState.Running) return
        val percent = percentOf(state)
        if (!throttle.shouldRefresh(System.currentTimeMillis(), percent)) return
        UpdateNotification.postProgress(this, notificationFor(state, percent))
    }

    private fun finish() {
        if (finished) return
        finished = true
        (downloader.state.value as? DownloadState.Ready)?.let { UpdateNotification.postDone(this, it.version) }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun goForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, UpdateNotification.NOTIFICATION_ID_PROGRESS, notification, type)
    }

    private fun statusNotification(request: DownloadRequest): Notification =
        UpdateNotification.buildProgress(
            context = this,
            version = request.version,
            downloadedBytes = downloader.downloadedBytes(request.version),
            totalBytes = request.sizeBytes,
            percent = null,
            etaSeconds = null,
        )

    private fun notificationFor(state: DownloadState.Running, percent: Int?): Notification =
        UpdateNotification.buildProgress(
            context = this,
            version = state.version,
            downloadedBytes = state.downloadedBytes,
            totalBytes = state.totalBytes,
            percent = percent,
            etaSeconds = state.etaSeconds,
            sourceLabel = state.sourceLabel,
        )

    private fun percentOf(state: DownloadState.Running): Int? =
        state.totalBytes?.takeIf { it > 0 }
            ?.let { ((state.downloadedBytes * 100) / it).toInt().coerceIn(0, 100) }

    companion object {
        private const val EXTRA_VERSION = "update_version"
        private const val EXTRA_URL = "update_url"
        private const val EXTRA_SIZE = "update_size"
        private const val EXTRA_DIGEST = "update_digest"
        private const val EXTRA_MIRRORS = "update_mirrors"

        /** 启动下载。必须在可见界面上调用（Android 12+ 不允许后台启动前台服务） */
        fun start(context: Context, request: DownloadRequest) {
            val intent = Intent(context, ApkDownloadService::class.java)
                .putExtra(EXTRA_VERSION, request.version)
                .putExtra(EXTRA_URL, request.url)
                .putExtra(EXTRA_SIZE, request.sizeBytes ?: -1L)
                .putExtra(EXTRA_DIGEST, request.digest)
                // 镜像开关必须随 Intent 过河：服务会用 extras 重建请求，默认值 false 会把开关吞掉
                .putExtra(EXTRA_MIRRORS, request.mirrorsEnabled)
            ContextCompat.startForegroundService(context, intent)
        }

        /** 通知栏「取消下载」：服务没在跑时 startService 会被系统拒绝，runCatching 兜住即可 */
        fun cancel(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, ApkDownloadService::class.java).setAction(UpdateNotification.ACTION_CANCEL)
                )
            }
        }

        private fun Intent?.toDownloadRequest(): DownloadRequest? {
            val source = this ?: return null
            val version = source.getStringExtra(EXTRA_VERSION)?.takeIf { it.isNotBlank() } ?: return null
            val url = source.getStringExtra(EXTRA_URL)?.takeIf { it.isNotBlank() } ?: return null
            return DownloadRequest(
                version = version,
                url = url,
                sizeBytes = source.getLongExtra(EXTRA_SIZE, -1L).takeIf { it > 0 },
                digest = source.getStringExtra(EXTRA_DIGEST),
                mirrorsEnabled = source.getBooleanExtra(EXTRA_MIRRORS, false),
            )
        }
    }
}
