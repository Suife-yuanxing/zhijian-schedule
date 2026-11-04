package com.zhijian.schedule.core.update

import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * 基线包的身份快照：路径 + 长度 + 最后修改时间。
 *
 * 三个字段都参与比较，因为「是不是同一个文件」在这套目录里没有别的凭据：
 * 基线包会被 UpdateStore.saveBaseline 用 copyTo(overwrite = true) 原地覆盖，
 * 路径不变而内容变了；只按路径记忆会把**旧包的摘要**当成新包的，
 * 于是 DeltaPolicy 一定判「基线与清单不是同一个包」，增量永远走不通。
 * 加上长度与 mtime 之后，换版本必然失配、命中缓存必然是真同一个文件。
 */
internal data class BaselineSnapshot(
    val path: String,
    val length: Long,
    val modifiedAt: Long,
) {
    companion object {
        fun of(file: File): BaselineSnapshot =
            BaselineSnapshot(
                path = file.absolutePath,
                length = file.length(),
                modifiedAt = file.lastModified(),
            )
    }
}

/**
 * 基线包 sha256 的记忆。
 *
 * 为什么需要它：点「下载更新」之后，ApkDownloader 必须先算出**本机已安装整包**的 sha256
 * 才能让 DeltaPolicy 判断增量能不能用；那是一份 30~50 MB 的文件，
 * 手机上同步读一遍要几百毫秒到一秒多，而这段时间用户已经点了按钮、界面却还没有任何反应。
 * 摘要只跟文件内容有关，同一个基线包在进程存活期间算一次就够了。
 *
 * 为什么用内存而不是落盘：落盘要在 updates/ 之外再造一处需要清理的产物
 * （用户点「清理下载缓存」不会清它，换版本时它又得跟着失效），
 * 收益只有「冷启动后第一次点下载少算一遍」；而那次冷启动本来就要 ensureBaseline
 * 复制基线包，用户对那一段的耐心远高于点击响应。
 */
internal class BaselineDigestCache {
    private data class Entry(
        val snapshot: BaselineSnapshot,
        val digest: String,
    )

    private val entry = AtomicReference<Entry?>(null)

    /** 命中返回摘要；文件变了或没算过返回 null（调用方去算并 [put]） */
    fun get(file: File): String? {
        val cached = entry.get() ?: return null
        return cached.digest.takeIf { cached.snapshot == BaselineSnapshot.of(file) }
    }

    fun put(
        file: File,
        digest: String,
    ) {
        entry.set(Entry(BaselineSnapshot.of(file), digest))
    }

    /** 基线与目录不再对应时清掉（清缓存、换基线） */
    fun clear() {
        entry.set(null)
    }
}

// ---------- 磁盘旁注：跨进程复用 ----------

/**
 * 基线包摘要的**磁盘旁注**。
 *
 * 为什么要在内存记忆之外再落一份：用户杀进程、系统清理之后第一次点下载，内存记忆是空的，
 * 又要算一遍 30~50 MB 的 sha256（实测 1.66 s）。那一次恰恰是最容易被拿来评价「点下载慢不慢」的一次。
 *
 * 为什么要带长度与 mtime：基线包是**原地覆盖**（UpdateStore.saveBaseline 用 copyTo(overwrite)），
 * 路径与版本号都不变而内容全变；只按版本号记会把旧包的摘要当成新包的，
 * 于是 DeltaPolicy 永远判「不是同一个包」——增量和响应速度一起坏掉，还很难查。
 *
 * 放在 baselineDigests 这套逻辑旁边而不是 UpdateStore 里：UpdateStore 只管目录与 JSON 布局，
 * 现在已经有 22 个函数，再往里塞会把那个类的职责冲淡。
 */
internal class BaselineDigestNoteStore(
    private val dir: () -> File,
    private val json: Json,
) {
    /** 基线包摘要的旁注位置：与基线同名，后缀不同 */
    private fun noteFile(version: String): File = File(dir(), version + SUFFIX)

    /**
     * 读旁注。长度与 mtime 都要对上才算有效：
     * 包被换过（同版本重装、包被替换）时 mtime 必然变，那正是内存里记不住的那一层失效。
     */
    fun read(
        version: String,
        apk: File,
    ): String? {
        val file = noteFile(version)
        if (!file.isFile) return null
        return runCatching {
            val note = json.decodeFromString<BaselineDigestNote>(file.readText())
            note.digest.takeIf { note.length == apk.length() && note.modifiedAt == apk.lastModified() }
        }.getOrNull()
    }

    /** 写旁注；写失败只当没缓存，不影响这次下载 */
    fun write(
        version: String,
        apk: File,
        digest: String,
    ) {
        runCatching {
            noteFile(version).writeText(
                json.encodeToString(
                    BaselineDigestNote(
                        digest = digest,
                        length = apk.length(),
                        modifiedAt = apk.lastModified(),
                    ),
                ),
            )
        }
    }

    /** 换基线、清缓存时删掉旁注 */
    fun delete(version: String) {
        runCatching { noteFile(version).delete() }
    }

    private companion object {
        /** 与基线包并列存放，后缀区分 */
        const val SUFFIX = ".digest.json"
    }
}
