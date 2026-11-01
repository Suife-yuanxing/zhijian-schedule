package com.zhijian.schedule.core.jw.credential

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 落盘记录的明文形态（整体加密后才写文件，这个类本身从不单独出现在磁盘上） */
@Serializable
internal data class CredentialRecord(
    val version: Int = 1,
    val origin: String = "",
    val username: String = "",
    val password: String = "",
    val savedAt: Long = 0L,
)

/** 解密后的完整凭据：只有自动填充那一刻才需要它 */
data class JwCredential(
    val origin: String,
    val username: String,
    val password: String,
    val savedAt: Long,
)

/**
 * 给「高级设置」看的凭据摘要：**刻意不含密码字段**。
 *
 * 设置页只需要展示「记的是哪个账号」，让它拿不到明文，
 * 就把「密码会不会被渲染到界面上」这个问题从根上去掉了。
 */
data class JwCredentialSummary(
    val origin: String,
    val username: String,
    val savedAt: Long,
) {
    /** 账号打码：规则与导入页的确认气泡共用 [JwCredentialMasking]，两处显示必须一致 */
    val maskedUsername: String
        get() = JwCredentialMasking.mask(username).ifEmpty { "未记录账号" }
}

/**
 * 教务账号密码的本机存储。
 *
 * ## 三条硬约束
 * 1. **不进备份**：文件放在 [Context.getNoBackupFilesDir]，Android 的全量备份与
 *    `adb backup` 都不会带上它。Keystore 里的密钥本来也不出设备，密文跟着云备份走
 *    只会在新机器上变成一堆解不开的字节，没有任何收益。
 * 2. **不参与导出**：`BackupManager.exportJson()` 只导 Room 里的课表数据，
 *    本类完全独立于它，导出备份时不会把密码带出去。
 * 3. **原子写**：先写 `.tmp` 再 rename，中途被杀不会留下半截密文。
 */
@Singleton
class JwCredentialStore
    @Inject
    constructor(
        private val crypto: CredentialCrypto,
        private val json: Json,
        @ApplicationContext context: Context,
    ) {
        private val file = File(context.noBackupFilesDir, FILE_NAME)
        private val mutex = Mutex()
        private val _summary = MutableStateFlow<JwCredentialSummary?>(null)

        /** 已保存凭据的摘要；null = 没存过（或密钥失效后读不出来） */
        val summary: StateFlow<JwCredentialSummary?> = _summary.asStateFlow()

        /** 从磁盘重读一次摘要。构造函数里不碰 IO，避免 Hilt 在主线程建单例时读盘 */
        suspend fun refresh() {
            withContext(Dispatchers.IO) {
                mutex.withLock { _summary.value = readLocked()?.toSummary() }
            }
        }

        /** 读取完整凭据（含明文密码）：调用方自己比对 origin，只在登录页回填时用 */
        suspend fun load(): JwCredential? =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    val record = readLocked() ?: return@withLock null
                    JwCredential(
                        origin = record.origin,
                        username = record.username,
                        password = record.password,
                        savedAt = record.savedAt,
                    )
                }
            }

        /** 写入（覆盖）一条凭据；加解密失败或磁盘不可写时返回 false */
        suspend fun save(
            origin: String,
            username: String,
            password: String,
        ): Boolean =
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    if (origin.isBlank() || password.isEmpty()) return@withLock false
                    val record =
                        CredentialRecord(
                            origin = origin,
                            username = username.trim(),
                            password = password,
                            savedAt = System.currentTimeMillis(),
                        )
                    val sealed = crypto.encrypt(json.encodeToString(record)) ?: return@withLock false
                    val ok = writeAtomically(sealed)
                    if (ok) _summary.value = record.toSummary()
                    ok
                }
            }

        suspend fun clear() {
            withContext(Dispatchers.IO) {
                mutex.withLock {
                    runCatching { file.delete() }
                    runCatching { tempFile().delete() }
                    _summary.value = null
                }
            }
        }

        private fun readLocked(): CredentialRecord? {
            if (!file.isFile) return null
            val sealed = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return null
            val plain = crypto.decrypt(sealed) ?: return null
            return runCatching { json.decodeFromString<CredentialRecord>(plain) }.getOrNull()
        }

        private fun writeAtomically(content: String): Boolean =
            runCatching {
                file.parentFile?.mkdirs()
                val temp = tempFile()
                temp.writeText(content, Charsets.UTF_8)
                if (!temp.renameTo(file)) {
                    file.delete()
                    temp.renameTo(file)
                }
            }.isSuccess

        private fun tempFile(): File = File(file.parentFile, FILE_NAME + ".tmp")

        private fun CredentialRecord.toSummary(): JwCredentialSummary =
            JwCredentialSummary(origin = origin, username = username, savedAt = savedAt)

        private companion object {
            const val FILE_NAME = "jw-credential.bin"
        }
    }
