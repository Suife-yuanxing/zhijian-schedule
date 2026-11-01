package com.zhijian.schedule.core.jw.credential

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import javax.crypto.KeyGenerator
import javax.crypto.spec.SecretKeySpec

/**
 * 凭据密文信封的格式约束：这些用例就是「换机恢复 / 文件被改坏」时的兜底行为。
 *
 * 纯 JVM 测：用 [SecretKeySpec] 造一把临时 AES 密钥，不碰 Android Keystore
 * （真机 Keystore 只在 [CredentialCrypto] 里出现，那一层不做单测）。
 */
class CredentialBlobTest {
    private fun key(): SecretKeySpec {
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256, SecureRandom())
        // 取原始字节再造一份：让 KeySpec 的算法名与 GCM 匹配，且不依赖 Keystore
        return SecretKeySpec(generator.generateKey().encoded, "AES")
    }

    @Test
    fun sealAndOpenRoundTrip() {
        val k = key()
        val plain = "学号20230001|密码p@ss\"中\""
        val blob = CredentialBlob.seal(k, plain.toByteArray(Charsets.UTF_8))
        assertArrayEquals(plain.toByteArray(Charsets.UTF_8), CredentialBlob.open(k, blob))
    }

    @Test
    fun wrongKeyCannotOpen() {
        val blob = CredentialBlob.seal(key(), "secret".toByteArray())
        assertNull(CredentialBlob.open(key(), blob))
    }

    @Test
    fun tamperedByteIsRejected() {
        val k = key()
        val blob = CredentialBlob.seal(k, "secret".toByteArray())
        // 改最后一个字节（GCM tag 的一部分）：必须解不开，而不是解出垃圾
        blob[blob.lastIndex] = (blob[blob.lastIndex].toInt() xor 0xFF).toByte()
        assertNull(CredentialBlob.open(k, blob))
    }

    @Test
    fun unknownVersionIsRejected() {
        val k = key()
        val blob = CredentialBlob.seal(k, "secret".toByteArray())
        blob[4] = 9
        assertNull(CredentialBlob.open(k, blob))
    }

    @Test
    fun truncatedBlobIsRejected() {
        val k = key()
        val blob = CredentialBlob.seal(k, "secret".toByteArray())
        assertNull(CredentialBlob.open(k, blob.copyOfRange(0, 8)))
    }

    @Test
    fun twoSealsProduceDifferentCiphertext() {
        val k = key()
        val a = CredentialBlob.seal(k, "same".toByteArray())
        val b = CredentialBlob.seal(k, "same".toByteArray())
        // 随机 IV：同一明文两次加密必须不同，否则能看出「密码没改过」
        assertNotNull(a)
        assertTrue(!a.contentEquals(b))
    }

    /**
     * 「IV 由 Cipher 自己生成」那条分支必须真的能用。
     *
     * 钉的是真机事故（PKG110 / Android 16 / 2026-11-01）：Android Keystore 的密钥默认
     * 禁止调用方指定 IV，传 GCMParameterSpec 会抛
     * InvalidAlgorithmParameterException: Caller-provided IV not permitted。
     * 之前 seal 无条件自己造 IV，于是保存永远失败，界面只说「本机密钥不可用」——
     * 这个功能从上线起没成功存过一次。
     *
     * 用 forceCipherIv 显式走到那条分支：纯 JVM 造不出真正的 Keystore 密钥，
     * 但「Cipher 自己出 IV、我们把 getIV() 写进信封、再按信封解回来」这条路可以完整验证。
     */
    @Test
    fun cipherGeneratedIvBranchRoundTrips() {
        val k = key()
        val plain = "学号20230001|密码p@ss"
        val blob = CredentialBlob.seal(k, plain.toByteArray(Charsets.UTF_8), forceCipherIv = true)
        // 信封里必须真的带上了 Cipher 生成的 12 字节 IV（否则 open 解不开）
        assertEquals(12, blob.size - 5 - plain.toByteArray().size - 16)
        assertArrayEquals(plain.toByteArray(Charsets.UTF_8), CredentialBlob.open(k, blob))
    }

    @Test
    fun cipherGeneratedIvIsRandomPerCall() {
        val k = key()
        val a = CredentialBlob.seal(k, "same".toByteArray(), forceCipherIv = true)
        val b = CredentialBlob.seal(k, "same".toByteArray(), forceCipherIv = true)
        // IV 交给 Cipher 生成之后，同一明文两次加密仍必须不同
        assertTrue("两次加密的 IV 相同，说明 IV 没真的随机", !a.contentEquals(b))
    }

    @Test
    fun keystoreBackedKeyIsDetected() {
        // 判据是「不可导出」，不是算法名：AndroidKeystore 的 getAlgorithm() 就是 "AES"，
        // 按名字判会把真机上每一把密钥都判成普通密钥，然后继续自己传 IV、继续失败。
        val exportable = SecretKeySpec(ByteArray(32), "AES")
        assertFalse("可导出的密钥不该判成 Keystore 密钥", CredentialBlob.isKeystoreBacked(exportable))
    }
}
