package com.zhijian.schedule.core.update

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 基线摘要记忆的测试。
 *
 * 三条断言对应三件真事：
 * 1. 命中缓存 —— 同一个文件不重复读盘（这是「点下载」响应速度的来源）；
 * 2. 文件被原地覆盖后必须失效 —— 基线包是 copyTo(overwrite) 换的，路径不变内容变，
 *    拿旧摘要去比对新清单必然判「不是同一个包」，增量会永远走不通；
 * 3. clear 之后必须重新算 —— 清缓存、换基线时由调用方显式清掉。
 */
class BaselineDigestCacheTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val sha = "sha256:" + "a".repeat(64)

    @Test
    fun missBeforeAnyPut() {
        val file = temp.newFile("baseline.apk").apply { writeBytes(ByteArray(16)) }
        assertNull(BaselineDigestCache().get(file))
    }

    @Test
    fun hitAfterPut() {
        val file = temp.newFile("baseline.apk").apply { writeBytes(ByteArray(16)) }
        val cache = BaselineDigestCache()
        cache.put(file, sha)
        assertEquals(sha, cache.get(file))
    }

    @Test
    fun contentChangeInvalidates() {
        val file = temp.newFile("baseline.apk").apply { writeBytes(ByteArray(16)) }
        val cache = BaselineDigestCache()
        cache.put(file, sha)
        // 模拟 saveBaseline 的 copyTo(overwrite = true)：路径不变、长度与时间变了
        Thread.sleep(20)
        file.writeBytes(ByteArray(32))
        assertNull("文件变了就必须重算，否则增量判据会拿着旧摘要永远失配", cache.get(file))
    }

    @Test
    fun clearDropsEntry() {
        val file = temp.newFile("baseline.apk").apply { writeBytes(ByteArray(16)) }
        val cache = BaselineDigestCache()
        cache.put(file, sha)
        assertNotNull(cache.get(file))
        cache.clear()
        assertNull(cache.get(file))
    }

    @Test
    fun differentFileDoesNotReuseEntry() {
        val first = temp.newFile("a.apk").apply { writeBytes(ByteArray(16)) }
        val second = temp.newFile("b.apk").apply { writeBytes(ByteArray(16)) }
        val cache = BaselineDigestCache()
        cache.put(first, sha)
        assertNull("换了文件不能命中上一个文件的摘要", cache.get(second))
    }

    // ---------- 磁盘旁注：跨进程复用 ----------

    @Test
    fun noteRoundTripsAndSurvivesRebuild() {
        // 冷启动后内存记忆是空的，靠旁注命中：这正是「第一次点下载也不慢」的来源
        val dir = temp.newFolder("baseline")
        val apk = File(dir, "1.0.18.apk").apply { writeBytes(ByteArray(64)) }
        val json = Json { ignoreUnknownKeys = true }
        BaselineDigestNoteStore({ dir }, json).write("1.0.18", apk, sha)
        // 新建一个实例 = 模拟进程重启后内存记忆清空
        assertEquals(sha, BaselineDigestNoteStore({ dir }, json).read("1.0.18", apk))
    }

    @Test
    fun noteInvalidatesWhenBaselineIsReplaced() {
        // 基线包是原地覆盖（同版本重装 / 换包）：路径与版本号都不变，只有长度和 mtime 会变
        val dir = temp.newFolder("baseline")
        val apk = File(dir, "1.0.18.apk").apply { writeBytes(ByteArray(64)) }
        val json = Json { ignoreUnknownKeys = true }
        val notes = BaselineDigestNoteStore({ dir }, json)
        notes.write("1.0.18", apk, sha)
        Thread.sleep(20)
        apk.writeBytes(ByteArray(128))
        assertNull("换了包就必须重算，否则增量判据拿着旧摘要永远失配", notes.read("1.0.18", apk))
    }

    @Test
    fun missingNoteIsJustAMiss() {
        val dir = temp.newFolder("baseline")
        val apk = File(dir, "1.0.18.apk").apply { writeBytes(ByteArray(8)) }
        assertNull(BaselineDigestNoteStore({ dir }, Json { ignoreUnknownKeys = true }).read("1.0.18", apk))
    }

    @Test
    fun corruptedNoteDoesNotThrow() {
        // 旁注坏了只当没有：绝不能让一个坏 JSON 卡住更新
        val dir = temp.newFolder("baseline")
        val apk = File(dir, "1.0.18.apk").apply { writeBytes(ByteArray(8)) }
        File(dir, "1.0.18.digest.json").writeText("{ not json")
        assertNull(BaselineDigestNoteStore({ dir }, Json { ignoreUnknownKeys = true }).read("1.0.18", apk))
    }

    @Test
    fun digestIsStableAcrossRepeatedReads() {
        // 缓存只是省掉重复计算，值必须与直接算一致
        val file = temp.newFile("baseline.apk")
        file.writeBytes(ByteArray(4_096) { (it % 251).toByte() })
        val direct = UpdateDigest.sha256Of(file)
        val cache = BaselineDigestCache()
        cache.put(file, direct)
        assertEquals(direct, cache.get(file))
    }
}
