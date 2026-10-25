package com.zhijian.schedule.core.jw.script

import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.net.URI
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * 页面上下文脚本执行（spec §2）：把脚本注入当前教务 WebView 的主 frame，
 * 脚本读 DOM / 同源 fetch，经 bridge 回传 JSON 信封。20s 超时；执行完清理全局残留。
 */
object JwScriptRunner {

    enum class FailureKind { HOST_MISMATCH, NO_ENTRY, TIMEOUT, SCRIPT_ERROR }

    sealed interface RunResult {
        data class Ok(val envelopeJson: String) : RunResult
        data class Failed(val message: String, val kind: FailureKind) : RunResult
    }

    private const val BRIDGE_NAME = "PaperBridge"
    private const val DEFAULT_TIMEOUT_MS = 20_000L

    suspend fun run(
        webView: WebView,
        sourceText: String,
        manifest: JwScriptManifest,
        pageUrl: String,
        totalWeeks: Int,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    ): RunResult = withContext(Dispatchers.Main) {
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
        webView.addJavascriptInterface(Bridge(deferred), BRIDGE_NAME)
        try {
            webView.evaluateJavascript(buildLoader(sourceText, totalWeeks), null)
            val outcome = try {
                withTimeoutOrNull(timeoutMs) { deferred.await() }?.let { Outcome.Done(it) }
                    ?: Outcome.Timeout
            } catch (e: CancellationException) {
                Outcome.ScriptError(e.message ?: "脚本执行出错")
            }
            when (outcome) {
                is Outcome.Done -> RunResult.Ok(outcome.json)
                is Outcome.Timeout -> RunResult.Failed(
                    "脚本执行超时（${timeoutMs / 1000} 秒），请确认停留在课表页面后重试",
                    FailureKind.TIMEOUT,
                )
                is Outcome.ScriptError -> RunResult.Failed(outcome.message, FailureKind.SCRIPT_ERROR)
            }
        } finally {
            runCatching { webView.removeJavascriptInterface(BRIDGE_NAME) }
            // 清理全局引用，避免下一个脚本读到上一个的残留
            runCatching {
                webView.evaluateJavascript("try { delete globalThis.__paperScheduleScript } catch (e) {}", null)
            }
        }
    }

    private sealed interface Outcome {
        data class Done(val json: String) : Outcome
        data object Timeout : Outcome
        data class ScriptError(val message: String) : Outcome
    }

    /** bridge 回调跑在 WebView 的 JavaBridge 线程，用原子标志保证只完成一次 */
    private class Bridge(private val deferred: CompletableDeferred<String>) {
        private val done = AtomicReference(false)

        private fun completeOnce(block: () -> Unit) {
            if (done.compareAndSet(false, true)) block()
        }

        @JavascriptInterface
        fun onResult(json: String?) {
            completeOnce { deferred.complete(json ?: "null") }
        }

        @JavascriptInterface
        fun onError(kind: String?, message: String?) {
            completeOnce {
                val reason = when (kind) {
                    "no_entry" -> "脚本未定义 fetchSchedule 入口"
                    else -> "脚本执行出错：${message ?: "未知错误"}"
                }
                deferred.cancel(CancellationException(reason))
            }
        }
    }

    private fun buildLoader(sourceText: String, totalWeeks: Int): String {
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
                  $BRIDGE_NAME.onError('no_entry', 'fetchSchedule 未定义');
                  return;
                }
                Promise.resolve()
                  .then(function () { return script.fetchSchedule({ url: location.href, totalWeeks: $weeks }); })
                  .then(function (r) { $BRIDGE_NAME.onResult(JSON.stringify(r === undefined ? null : r)); })
                  .catch(function (e) { $BRIDGE_NAME.onError('script_error', String((e && e.message) || e)); });
              } catch (e) {
                try { $BRIDGE_NAME.onError('script_error', String((e && e.message) || e)); } catch (e2) {}
              }
            })()
        """.trimIndent()
    }
}
