package com.zhijian.schedule.core.update

import com.zhijian.schedule.core.update.DownloadSourceSet
import com.zhijian.schedule.core.update.UpdateSource
import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadSourceTest {
    private val ghUrl =
        "https://github.com/Suife-yuanxing/zhijian-schedule/releases/download/v1.0.3/PaperTimetable-v1.0.3.apk"
    private val mirrors = listOf(UpdateSource("gh-proxy", "镜像 gh-proxy.com", "https://gh-proxy.com/"))

    // ---------- 候选构造 ----------

    @Test
    fun candidatesStartWithDirectAndAppendMirrorsWhenDigestIsValid() {
        val sources = DownloadSourceSet.availableSources(ghUrl, "sha256:" + "a".repeat(64), mirrors)
        assertEquals(
            listOf("direct", "gh-proxy"),
            sources.map { it.id },
        )
        assertEquals(ghUrl, sources.first().url)
        assertEquals("https://gh-proxy.com/$ghUrl", sources.last().url)
    }

    @Test
    fun candidatesDropMirrorsWithoutDigest() {
        val sources = DownloadSourceSet.availableSources(ghUrl, null, mirrors)
        assertEquals(listOf("direct"), sources.map { it.id })
        assertEquals(ghUrl, sources.single().url)
    }

    @Test
    fun candidatesDropMirrorsWhenDigestIsMalformed() {
        assertEquals(listOf("direct"), DownloadSourceSet.availableSources(ghUrl, "", mirrors).map { it.id })
        assertEquals(listOf("direct"), DownloadSourceSet.availableSources(ghUrl, "md5:abc", mirrors).map { it.id })
        assertEquals(
            listOf("direct"),
            DownloadSourceSet.availableSources(ghUrl, "sha256:abc", mirrors).map { it.id },
        )
    }

    @Test
    fun candidatesSurviveAnEmptyMirrorTable() {
        assertEquals(listOf("direct"), DownloadSourceSet.availableSources(ghUrl, null, emptyList()).map { it.id })
    }

    @Test
    fun nonGithubUrlsAreNotDuplicatedByMirrorPrefixes() {
        val url = "https://cdn.example.com/a.apk"
        val sources =
            DownloadSourceSet.availableSources(
                directUrl = url,
                digest = "sha256:" + "a".repeat(64),
                mirrors = DownloadSourceSet.MIRRORS,
            )
        assertEquals(listOf("direct"), sources.map { it.id })
        assertEquals(url, sources.single().url)
    }

    // ---------- 模板 ----------

    @Test
    fun templateKeepsNonGithubUrlsUntouched() {
        val url = "https://cdn.example.com/a.apk"
        assertEquals(url, DownloadSourceSet.withPrefix("https://gh-proxy.com", url))
    }

    @Test
    fun templateHandlesPrefixTrailingSlashAndExtraPath() {
        // 期望值必须跟着上面的 ghUrl 走：这里测的是「前缀尾斜杠归一化」，不是某个固定的示例地址
        assertEquals(
            "https://gh-proxy.com/$ghUrl",
            DownloadSourceSet.withPrefix("https://gh-proxy.com/", ghUrl),
        )
        assertEquals(
            "https://gh-proxy.com/$ghUrl",
            DownloadSourceSet.withPrefix("https://gh-proxy.com", ghUrl),
        )
    }

    @Test
    fun mirrorDisabledCopyExplainsWhy() {
        assertEquals("镜像加速已关闭", DownloadSourceSet.mirrorDisabledReason(digest = "x".repeat(64), mirrorsEnabled = false))
        // 文案以设计 §3.2 为准（完整句「…，已禁用镜像」）
        assertEquals(
            "该版本未提供官方摘要，已禁用镜像",
            DownloadSourceSet.mirrorDisabledReason(digest = null, mirrorsEnabled = true),
        )
        assertEquals(
            "该版本未提供官方摘要，已禁用镜像",
            DownloadSourceSet.mirrorDisabledReason("md5:x", mirrorsEnabled = true),
        )
        assertEquals(null, DownloadSourceSet.mirrorDisabledReason("sha256:" + "a".repeat(64), mirrorsEnabled = true))
    }

    // ---------- 下载前探测排序 ----------

    @Test
    fun probeOrderPutsFastestWorkingSourceFirst() {
        val sources =
            listOf(
                UpdateSource("direct", "直连", "https://github.com/a.apk"),
                UpdateSource("gh-proxy", "镜像", "https://gh-proxy.com/https://github.com/a.apk"),
            )
        val ordered =
            DownloadSourceSet.orderByProbe(
                sources,
                mapOf("direct" to 900L, "gh-proxy" to 120L),
            )
        assertEquals(listOf("gh-proxy", "direct"), ordered.map { it.id })
    }

    @Test
    fun probeOrderKeepsFailedSourcesAsLastResort() {
        val sources =
            listOf(
                UpdateSource("direct", "直连", "https://github.com/a.apk"),
                UpdateSource("gh-proxy", "镜像", "https://gh-proxy.com/https://github.com/a.apk"),
            )
        val ordered =
            DownloadSourceSet.orderByProbe(
                sources,
                mapOf("direct" to null, "gh-proxy" to 150L),
            )
        assertEquals(listOf("gh-proxy", "direct"), ordered.map { it.id })
    }

    @Test
    fun probeOrderFallsBackToOriginalWhenAllFail() {
        val sources =
            listOf(
                UpdateSource("direct", "直连", "https://github.com/a.apk"),
                UpdateSource("gh-proxy", "镜像", "https://gh-proxy.com/https://github.com/a.apk"),
            )
        val ordered =
            DownloadSourceSet.orderByProbe(
                sources,
                mapOf("direct" to null, "gh-proxy" to null),
            )
        assertEquals(sources, ordered)
    }

    @Test
    fun resumePrefersPreviousSourceEvenIfProbePutsAnotherFirst() {
        val direct = UpdateSource("direct", "直连", "https://github.com/a.apk")
        val mirror = UpdateSource("gh-proxy", "镜像", "https://gh-proxy.com/https://github.com/a.apk")
        val ordered = DownloadSourceSet.preferResumeSource(listOf(direct, mirror), mirror.url)
        assertEquals(listOf("gh-proxy", "direct"), ordered.map { it.id })
    }

    @Test
    fun resumeKeepsProbeOrderWhenPreviousSourceIsGone() {
        val direct = UpdateSource("direct", "直连", "https://github.com/a.apk")
        val mirror = UpdateSource("gh-proxy", "镜像", "https://gh-proxy.com/https://github.com/a.apk")
        val sources = listOf(mirror, direct)
        assertEquals(
            sources,
            DownloadSourceSet.preferResumeSource(sources, "https://old-mirror.example/a.apk"),
        )
    }
}
