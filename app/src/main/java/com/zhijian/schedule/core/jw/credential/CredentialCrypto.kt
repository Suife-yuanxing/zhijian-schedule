package com.zhijian.schedule.core.jw.credential

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 凭据的加解密：密钥放在 Android Keystore（不可导出、随应用卸载销毁），
 * 明文的加解密在 [CredentialBlob] 里完成。
 *
 * 为什么不用 EncryptedSharedPreferences：它同样落在 filesDir 下、同样受 allowBackup 影响，
 * 却要额外引入 security-crypto 依赖；这里只需要「一个 AES-GCM 密钥 + 一段字节」，
 * 自己做反而少一层版本兼容面，也方便把格式单独测起来。
 *
 * 任何一步失败都返回 null，绝不抛给调用方——凭据是附加能力，不能让它拖垮导入流程。
 */
@Singleton
class CredentialCrypto
    @Inject
    constructor() {
        fun encrypt(plain: String): String? {
            val key = secretKey() ?: return null
            return runCatching {
                val blob = CredentialBlob.seal(key, plain.toByteArray(Charsets.UTF_8))
                Base64.getEncoder().encodeToString(blob)
            }.getOrNull()
        }

        fun decrypt(encoded: String): String? {
            val key = secretKey() ?: return null
            return runCatching {
                val blob = Base64.getDecoder().decode(encoded.trim())
                CredentialBlob.open(key, blob)?.toString(Charsets.UTF_8)
            }.getOrNull()
        }

        private fun secretKey(): SecretKey? =
            runCatching {
                val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
                val existing = store.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                existing?.secretKey ?: generateKey()
            }.getOrNull()

        private fun generateKey(): SecretKey {
            val spec =
                KeyGenParameterSpec
                    .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    // 不做生物识别/锁屏校验：自动填充发生在 WebView 加载回调里，
                    // 要求解锁会把这个能力变成「每次都要摸一下指纹」，反而是更差的选择。
                    .setUserAuthenticationRequired(false)
                    .build()
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(spec)
            return generator.generateKey()
        }

        private companion object {
            const val ANDROID_KEYSTORE = "AndroidKeyStore"
            const val KEY_ALIAS = "zj_jw_credential_key"
            const val KEY_SIZE_BITS = 256
        }
    }
