package com.zhijian.schedule.core.net

import java.security.KeyStore
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 带"过期放行"能力的 TLS 配置，供 OkHttp 使用（见 [ExpiredCertTolerance]）。
 *
 * 系统 CA 与校验逻辑仍然沿用 [TrustManagerFactory] 的默认实现，
 * 这里只是把它的失败结果再交给 [ExpiredCertTrustManager] 判一次——
 * 没有替换任何根证书，也没有关掉主机名校验。
 */
object ToleratedTls {
    /** SSLContext 只能给出一对 (socketFactory, trustManager)，两者必须同源，否则握手与校验会错配 */
    class Config(
        val socketFactory: SSLSocketFactory,
        val trustManager: X509TrustManager,
    )

    fun create(onTolerated: (String) -> Unit = {}): Config {
        val delegate = defaultTrustManager()
        val tolerant = ExpiredCertTrustManager(delegate, onTolerated = onTolerated)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(tolerant), null)
        return Config(context.socketFactory, tolerant)
    }

    /**
     * 系统默认信任锚。
     *
     * `init(null)` 走的是平台自带 CA 库（Android 上由 Conscrypt 提供），
     * 因此系统更新根证书后这里自动跟随，不需要往 APK 里塞任何证书。
     */
    private fun defaultTrustManager(): X509TrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509TrustManager>().firstOrNull()
            ?: error("系统未提供 X509TrustManager")
    }
}
