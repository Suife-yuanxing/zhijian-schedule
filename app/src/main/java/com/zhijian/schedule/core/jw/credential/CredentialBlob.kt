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

    /**
     * 这把密钥是不是 Android Keystore 里的（不可导出、限制 IV）。
     *
     * 判据是 [SecretKey.getFormat] 为 null —— **不是**看算法名。
     * AndroidKeystore 返回的密钥 getAlgorithm() 就是 "AES"，
     * 按算法名判会把真机上每一把密钥都判成「普通密钥」，
     * 于是继续自己传 IV、继续抛 Caller-provided IV not permitted。
     * 不可导出的密钥 getFormat() 返回 null、getEncoded() 也返回 null，
     * 普通 SecretKeySpec 则返回 "RAW"。这一条与 Keystore 的语义直接对应。
     */
    internal fun isKeystoreBacked(key: SecretKey): Boolean = key.format == null

    /**
     * 加密：返回可直接 Base64 化落盘的字节串。
     *
     * ## 为什么 IV 由 Cipher 自己生成，而不是我们传进去
     * Android Keystore 里的密钥默认 setRandomizedEncryptionRequired(true)，也就是
     * **禁止调用方指定 IV** —— 自己传会抛
     * InvalidAlgorithmParameterException: Caller-provided IV not permitted。
     * 这不是可选项：那一条正是 Keystore 用来保证「同一明文两次加密必须不同」的机制，
     * 关掉它（setRandomizedEncryptionRequired(false)）等于把这层保证拆掉。
     *
     * 真机实测（PKG110 / Android 16 / 2026-11-01）踩的就是这个：
     * 保存时抛异常 -> runCatching 吞掉 -> 界面只说「本机密钥不可用」，
     * 功能从上线起就没成功存过一次。
     *
     * 因此这里 init 时不传 GCMParameterSpec，加密完用 Cipher.getIV 把 IV 取回来
     * 写进信封；解密时 IV 必须来自信封，那时传 GCMParameterSpec 是允许的。
     * random 保留给纯 JVM 单测（普通 SecretKeySpec 不限制 IV），真实 Keystore 下不使用。
     */
    fun seal(
        key: SecretKey,
        plaintext: ByteArray,
        random: SecureRandom = SecureRandom(),
        /**
         * 强制走「Cipher 自己生成 IV」那条分支。
         *
         * 生产调用不需要传：默认按 [isKeystoreBacked] 判。这个参数只为**单测**存在——
         * 纯 JVM 造不出真正的 AndroidKeystore 密钥（JVM 的加密提供方会直接拒绝不是
         * 真 AES 密钥的对象），所以那条分支在单测里只能靠显式开关走到。
         * 一个只在测试里用的分支，比一条永远测不到的生产分支安全得多。
         */
        forceCipherIv: Boolean = isKeystoreBacked(key),
    ): ByteArray {
        val cipher = Cipher.getInstance(GCM)
        val iv =
            if (forceCipherIv) {
                cipher.init(Cipher.ENCRYPT_MODE, key)
                cipher.iv
            } else {
                val own = ByteArray(IV_BYTES).also(random::nextBytes)
                cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, own))
                own
            }
        require(iv.size == IV_BYTES) { "GCM IV 必须是 " + IV_BYTES + " 字节，实际 " + iv.size }
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
