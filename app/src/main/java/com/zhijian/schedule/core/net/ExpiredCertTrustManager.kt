package com.zhijian.schedule.core.net

import java.security.cert.CertificateException
import java.security.cert.CertificateExpiredException
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * 给系统 TrustManager 包一层：**只**在"证书过期"这一种失败上，交给
 * [ExpiredCertTolerance] 决定要不要放行，其余错误原样抛出。
 *
 * ## 为什么不在 TrustManager 里判主机名
 * `checkServerTrusted(chain, authType)` 拿不到连接目标主机，所以这里读的是
 * **叶子证书自己声明的 SAN**：它必须是放行名单里的那台主机。
 * 万一有人拿别的主机的证书来顶，主机名校验（OkHttp 自己做的、没被关掉）
 * 会立刻失败——SAN 只是第二道闸门，不是唯一一道。
 *
 * 这里不 import 任何 Android 类型，因此可以在本机 JVM 上直接构造。
 */
class ExpiredCertTrustManager(
    private val delegate: X509TrustManager,
    private val now: () -> Long = System::currentTimeMillis,
    private val onTolerated: (String) -> Unit = {},
) : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.getAcceptedIssuers()

    override fun checkClientTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(
        chain: Array<out X509Certificate>?,
        authType: String?,
    ) {
        try {
            delegate.checkServerTrusted(chain, authType)
        } catch (e: CertificateException) {
            if (!tolerateExpired(chain, e)) throw e
        }
    }

    private fun tolerateExpired(
        chain: Array<out X509Certificate>?,
        failure: CertificateException,
    ): Boolean {
        if (!causedByExpiry(failure)) return false
        val leaf = chain?.firstOrNull() ?: return false
        val san = runCatching { leaf.subjectAlternativeNames }.getOrNull()
        val notAfter = runCatching { leaf.notAfter.time }.getOrNull()
        val nowMs = now()
        if (!ExpiredCertTolerance.shouldProceedOnChainFailure(san, notAfter, nowMs)) return false
        onTolerated(ExpiredCertTolerance.sslNotice(notAfter ?: 0L, nowMs))
        return true
    }

    /**
     * 过期常被包在 `SSLHandshakeException` / `CertificateException("Chain validation failed")` 里，
     * 所以要顺着 cause 链找。
     *
     * 桌面 JDK 与 Android Conscrypt 抛的是同一个 [CertificateExpiredException]，但
     * 中间层的包装形态不保证一致，所以再留一条消息判定作为兜底——
     * 其它任何失败都不会落到这里（主机名校验是 OkHttp 单独做的一步，异常类型也不同）。
     */
    private fun causedByExpiry(failure: Throwable): Boolean {
        var current: Throwable? = failure
        while (current != null) {
            if (current is CertificateExpiredException) return true
            if (current.message?.contains("expired", ignoreCase = true) == true) return true
            current = current.cause
        }
        return false
    }
}
