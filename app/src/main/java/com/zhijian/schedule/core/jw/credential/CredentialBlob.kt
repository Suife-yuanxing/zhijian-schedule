package com.zhijian.schedule.core.jw.credential

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 凭据密文的字节信封：`"ZJC1" | 版本(1B) | IV(12B) | GCM 密文含 tag`。
 *
 * 为什么要自己拼一层信封，而不是直接落 `cipher.doFinal()` 的结果：
 * GCM 的 IV 必须与密文一起落盘（它不能从密钥推导出来，Keystore 里的密钥也不许导出）；
 * 版本号写进头部，将来换算法 / 换密钥时能识别旧文件，而不是把解不开的乱码当明文塞回输入框。
 *
 * 本文件是纯 JVM 实现（javax.crypto + java.util.Base64），Android Keystore 只负责提供
 * [SecretKey]，因此加解密格式可以进本机单测——真机上才需要的 Keystore 逻辑不在这一层。
 */
internal object CredentialBlob {
    private const val GCM = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private const val IV_BYTES = 12
    private const val VERSION_INDEX = 4
    private const val PAYLOAD_INDEX = 5
    private const val VERSION = 1
    private val MAGIC = byteArrayOf('Z'.code.toByte(), 'J'.code.toByte(), 'C'.code.toByte(), '1'.code.toByte())

    /** 加密：返回可直接 Base64 化落盘的字节串 */
    fun seal(
        key: SecretKey,
        plaintext: ByteArray,
        random: SecureRandom = SecureRandom(),
    ): ByteArray {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(GCM)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val body = cipher.doFinal(plaintext)
        val out = ByteArray(PAYLOAD_INDEX + IV_BYTES + body.size)
        MAGIC.copyInto(out, 0)
        out[VERSION_INDEX] = VERSION.toByte()
        iv.copyInto(out, PAYLOAD_INDEX)
        body.copyInto(out, PAYLOAD_INDEX + IV_BYTES)
        return out
    }

    /**
     * 解密：格式不对、版本不认识、被改过一个字节（GCM 会验 tag）都返回 null。
     *
     * 返回 null 而不是抛异常：凭据解密失败是**正常**场景（换机恢复、系统重置 Keystore 密钥），
     * 上层只要当作「没有保存过」继续走手动登录即可。
     */
    fun open(
        key: SecretKey,
        blob: ByteArray,
    ): ByteArray? {
        if (blob.size <= PAYLOAD_INDEX + IV_BYTES) return null
        for (i in MAGIC.indices) {
            if (blob[i] != MAGIC[i]) return null
        }
        if (blob[VERSION_INDEX] != VERSION.toByte()) return null
        return runCatching {
            val iv = blob.copyOfRange(PAYLOAD_INDEX, PAYLOAD_INDEX + IV_BYTES)
            val body = blob.copyOfRange(PAYLOAD_INDEX + IV_BYTES, blob.size)
            val cipher = Cipher.getInstance(GCM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(body)
        }.getOrNull()
    }
}
