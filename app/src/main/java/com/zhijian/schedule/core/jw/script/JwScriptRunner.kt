package com.zhijian.schedule.core.jw.script

import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.CancellationException

/**
 * 页面上下文脚本执行（spec §2）：把脚本注入当前教务 WebView 的主 frame，
 * 脚本读 DOM / 同源 fetch，经 bridge 回传 JSON 信封。20s 超时；执行完清理全局残留。
 *
 * bridge 在 WebView 创建时由 [PaperJavascriptBridge] 永久注入（已加载页面上
 * 事后 addJavascriptInterface 在部分 ROM 不立即生效），通过 [ScriptBridgeHub]
 * 路由到当次运行的 deferred。
 */
object JwScriptRunner {
    enum class FailureKind { HOST_MISMATCH, NO_ENTRY, TIMEOUT, SCRIPT_ERROR }

    sealed interface RunResult {
        data class Ok(
            val envelopeJson: String,
        ) : RunResult

        data class Failed(
            val message: String,
            val kind: FailureKind,
        ) : RunResult
    }

    private const val DEFAULT_TIMEOUT_MS = 20_000L

    suspend fun run(
        webView: WebView,
        sourceText: String,
        manifest: JwScriptManifest,
        pageUrl: String,
        totalWeeks: Int,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): RunResult =
        withContext(Dispatchers.Main) {
            if (manifest.hosts.isNotEmpty()) {
                val host = runCatching { URI(pageUrl).host?.lowercase() }.getOrNull().orEmpty()
                if (host !in manifest.hosts) {
                    return@withContext RunResult.Failed(
                        "该脚本仅适用于 ${manifest.hosts.joinToString("、")}，当前页面不属于它们",
                        FailureKind.HOST_MISMATCH,
                    )
                }
            }
            val deferred = CompletableDeferred<String>()
            ScriptBridgeHub.attach(deferred)
            try {
                webView.evaluateJavascript(buildLoader(sourceText, totalWeeks), null)
                val outcome =
                    try {
                        withTimeoutOrNull(timeoutMs) { deferred.await() }?.let { Outcome.Done(it) }
                            ?: Outcome.Timeout
                    } catch (e: CancellationException) {
                        Outcome.ScriptError(e.message ?: "脚本执行出错")
                    }
                when (outcome) {
                    is Outcome.Done -> RunResult.Ok(outcome.json)
                    is Outcome.Timeout ->
                        RunResult.Failed(
                            "脚本执行超时（${timeoutMs / 1000} 秒），请确认停留在课表页面后重试",
                            FailureKind.TIMEOUT,
                        )
                    is Outcome.ScriptError -> RunResult.Failed(outcome.message, FailureKind.SCRIPT_ERROR)
                }
            } finally {
                ScriptBridgeHub.detach(deferred)
                // 清理全局引用，避免下一个脚本读到上一个的残留
                runCatching {
                    webView.evaluateJavascript("try { delete globalThis.__paperScheduleScript } catch (e) {}", null)
                }
            }
        }

    private sealed interface Outcome {
        data class Done(
            val json: String,
        ) : Outcome

        data object Timeout : Outcome

        data class ScriptError(
            val message: String,
        ) : Outcome
    }

    /** bridge 回调跑在 WebView 的 JavaBridge 线程；attach 的 deferred 即当次运行 */
    internal object ScriptBridgeHub {
        @Volatile
        private var current: CompletableDeferred<String>? = null

        fun attach(deferred: CompletableDeferred<String>) {
            current = deferred
        }

        fun detach(deferred: CompletableDeferred<String>) {
            if (current === deferred) current = null
        }

        fun onResult(json: String?) {
            current?.complete(json ?: "null")
        }

        fun onError(
            kind: String?,
            message: String?,
        ) {
            val reason =
                when (kind) {
                    "no_entry" -> "脚本未定义 fetchSchedule 入口"
                    else -> "脚本执行出错：${message ?: "未知错误"}"
                }
            current?.cancel(CancellationException(reason))
        }
    }

    /** 暴露给 JS 的桥；公开类 + 公开方法，WebView 创建时注入一次 */
    class PaperJavascriptBridge {
        @JavascriptInterface
        fun onResult(json: String?) = ScriptBridgeHub.onResult(json)

        @JavascriptInterface
        fun onError(
            kind: String?,
            message: String?,
        ) = ScriptBridgeHub.onError(kind, message)
    }

    private fun buildLoader(
        sourceText: String,
        totalWeeks: Int,
    ): String {
        val source = JSONObject.quote(sourceText)
        val weeks = totalWeeks.coerceIn(1, 30)
        return """
            (function () {
              try {
                delete globalThis.__paperScheduleScript;
                var src = $source;
                (0, eval)(src);
                var script = globalThis.__paperScheduleScript;
                if (!script || typeof script.fetchSchedule !== 'function') {
                  PaperBridge.onError('no_entry', 'fetchSchedule 未定义');
                  return;
                }
                Promise.resolve()
                  .then(function () { return script.fetchSchedule({ url: location.href, totalWeeks: $weeks }); })
                  .then(function (r) { PaperBridge.onResult(JSON.stringify(r === undefined ? null : r)); })
                  .catch(function (e) { PaperBridge.onError('script_error', String((e && e.message) || e)); });
              } catch (e) {
                try { PaperBridge.onError('script_error', String((e && e.message) || e)); } catch (e2) {}
              }
            })()
            """.trimIndent()
    }
}
