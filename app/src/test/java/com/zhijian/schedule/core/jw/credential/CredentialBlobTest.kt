package com.zhijian.schedule.core.jw.credential

import org.junit.Assert.assertArrayEquals
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
}
