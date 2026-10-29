package com.zhijian.schedule.core.update

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

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

    /** 每次「换一轮下载」加一；旧任务收尾时用它判断自己还是不是当前任务 */
    private var generation = 0
    private var activeRequest: DownloadRequest? = null

    /** 最近一次 start 的 id：stopSelf 带着它才不会被「刚到的下一次 start」抢先收掉 */
    private var lastStartId = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        UpdateNotification.ensureChannel(this)
        // 通知栏进度整个服务生命周期只收一份，多次 start 不会重复订阅
        watcher = scope.launch { downloader.state.collect { renderProgress(it) } }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        lastStartId = startId
        when (val outcome = classifyStart(intent)) {
            is StartOutcome.Ignore -> {
                // 取消指令 / 无效意图 / 前台化失败：三种情况都只是「这次 start 不该开新下载」
                if (outcome.stopSelf) stopSelf(startId)
            }
            is StartOutcome.Launch -> launchRound(outcome.request)
        }
        return START_NOT_STICKY
    }

    /** 这次 start 该不该开新下载；不开的时候顺带说明要不要收工 */
    private sealed interface StartOutcome {
        data class Ignore(
            val stopSelf: Boolean,
        ) : StartOutcome

        data class Launch(
            val request: DownloadRequest,
        ) : StartOutcome
    }

    /**
     * 判定本次 start 的意图。三处「不该开新下载」的收尾条件刻意留在一起：
     * 取消指令（服务可能只是被它唤醒）、意图里没有合法请求、前台化失败。
     */
    private fun classifyStart(intent: Intent?): StartOutcome {
        if (intent?.action == UpdateNotification.ACTION_CANCEL) {
            downloader.cancel()
            // 没有下载在跑时服务只是被这次取消唤醒的，立刻收工
            return StartOutcome.Ignore(stopSelf = job?.isActive != true)
        }
        val request = intent.toDownloadRequest() ?: return StartOutcome.Ignore(stopSelf = true)
        val foregrounded = runCatching { goForeground(statusNotification(request)) }.isSuccess
        // Android 12+ 后台启动前台服务受限（例如点按后立刻切走）：直接收尾，状态不变由用户重试
        return if (foregrounded) StartOutcome.Launch(request) else StartOutcome.Ignore(stopSelf = true)
    }

    /** 开一轮新的下载任务；同一请求的重复点按会被并入当前轮 */
    private fun launchRound(request: DownloadRequest) {
        val running = job
        // 同一请求的重复点按：前台通知已经在上面补过了，绝不重头再下一遍
        if (running?.isActive == true && activeRequest == request && !downloader.isCancelling()) return
        // 「取消后马上点继续」时上一轮可能还在收尾：先让它彻底停下再开新的，
        // 两轮同时写同一个 .part 会把进度账目和文件内容一起搞乱
        if (running?.isActive == true) {
            downloader.cancel()
            running.cancel()
        }
        val round = ++generation
        activeRequest = request
        // 上一轮可能刚 stopSelf 完还没被销毁，这次 start 让服务继续活着：收工标记必须重置
        finished = false
        job =
            scope.launch {
                running?.join()
                try {
                    downloader.download(request)
                } finally {
                    // 只有最新一轮负责收工，旧轮次的 finally 不能把新下载一起停掉
                    if (round == generation) finish()
                }
            }
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
        stopSelf(lastStartId)
    }

    private fun goForeground(notification: Notification) {
        val type =
            if (Build.VERSION.SDK_INT >= 34) {
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

    private fun notificationFor(
        state: DownloadState.Running,
        percent: Int?,
    ): Notification =
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
        state.totalBytes
            ?.takeIf { it > 0 }
            ?.let { ((state.downloadedBytes * 100) / it).toInt().coerceIn(0, 100) }

    companion object {
        private const val EXTRA_VERSION = "update_version"
        private const val EXTRA_URL = "update_url"
        private const val EXTRA_SIZE = "update_size"
        private const val EXTRA_DIGEST = "update_digest"
        private const val EXTRA_MIRRORS = "update_mirrors"

        /** 启动下载。必须在可见界面上调用（Android 12+ 不允许后台启动前台服务） */
        fun start(
            context: Context,
            request: DownloadRequest,
        ) {
            val intent =
                Intent(context, ApkDownloadService::class.java)
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
                    Intent(context, ApkDownloadService::class.java).setAction(UpdateNotification.ACTION_CANCEL),
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
