package com.zhijian.schedule.core.capsule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 小米超级岛参数构建的协议校验。
 *
 * 这些断言对着官方《小米超级岛开发指南》的字段表写：结构错一个 key 系统就会
 * 忽略整串参数，而真机上极难定位，所以在 JVM 层先把「结构 / 字节上限 / 转义」钉死。
 */
class HyperOsIslandParamTest {
    private fun content(
        chip: String = "剩 32 分",
        title: String = "正在上课 · 大学英语",
        subtitle: String? = "第7-8节 · 教305 · 14:00-15:40 · 张老师",
        detail: String? = "15:40 下课 · 还剩 32 分钟",
        countdownToMillis: Long? = System.currentTimeMillis() + 32 * 60_000L,
    ) = LiveContent(
        iconRes = 1,
        chip = chip,
        title = title,
        subtitle = subtitle,
        detail = detail,
        progress = 42,
        progressColor = 0xFF3A7BD5.toInt(),
        countdownToMillis = countdownToMillis,
        key = title,
        sessionKey = "ongoing:大学英语:14:00",
        live = true,
    )

    @Test
    fun paramRootAndIslandRequiredFields() {
        val json = HyperOsIslandRenderer.paramForTest(content())
        assertTrue("根节点必须是 param_v2", json.startsWith("{\"param_v2\":{"))
        assertTrue("business 必填", json.contains("\"business\":\"course\""))
        assertTrue("updatable 必填", json.contains("\"updatable\":true"))
        assertTrue("param_island 必填", json.contains("\"param_island\":{"))
        assertTrue("bigIslandArea 必填", json.contains("\"bigIslandArea\":{"))
        assertTrue("smallIslandArea 必填", json.contains("\"smallIslandArea\":{"))
        assertTrue("islandProperty 必填", json.contains("\"islandProperty\":1"))
        assertTrue("baseInfo 必填", json.contains("\"baseInfo\":{"))
    }

    @Test
    fun islandTimeoutClampedWithinBounds() {
        val soon =
            HyperOsIslandRenderer.islandTimeoutSecForTest(
                content(countdownToMillis = System.currentTimeMillis() + 5_000L),
            )
        assertEquals(
            "剩余过短时抬到下限 30s",
            30L,
            soon,
        )

        val long =
            HyperOsIslandRenderer.islandTimeoutSecForTest(
                content(countdownToMillis = System.currentTimeMillis() + 10L * 60 * 60 * 1000),
            )
        assertEquals(
            "剩余过长时压到上限 4h",
            4L * 60 * 60,
            long,
        )

        val none = HyperOsIslandRenderer.islandTimeoutSecForTest(content(countdownToMillis = null))
        assertEquals(
            "无倒计时时用上限",
            4L * 60 * 60,
            none,
        )
    }

    @Test
    fun paramStaysWithinProtocolByteLimit() {
        val json =
            HyperOsIslandRenderer.paramForTest(
                content(
                    title = "超长课程名称".repeat(20),
                    subtitle = "第1-2节 · 教室编号很长的教学楼 · 08:00-09:40 · 某位老师".repeat(10),
                    detail = "下课时间与剩余说明".repeat(20),
                ),
            )
        val bytes = json.toByteArray(Charsets.UTF_8).size
        assertTrue("参数 " + bytes + " 字节超过协议上限 3072", bytes <= 3072)
    }

    @Test
    fun quotesAreEscapedSoStructureHolds() {
        val json =
            HyperOsIslandRenderer.paramForTest(
                content(title = "讲\"设计\"的课", subtitle = "教室 \"A305\""),
            )
        assertTrue("转义后的标题应出现", json.contains("讲\\\"设计\\\"的课"))
        assertEquals(
            "大括号未配对",
            json.count { it == '{' },
            json.count { it == '}' },
        )
        assertEquals(
            "方括号未配对",
            json.count { it == '[' },
            json.count { it == ']' },
        )
    }

    @Test
    fun chipIsClippedWhenTooLong() {
        val json = HyperOsIslandRenderer.paramForTest(content(chip = "这是一个非常长的状态短语"))
        // clip(max=6) = take(5) + "…"，即前 5 个字符加省略号
        assertTrue("chip 应被裁剪", json.contains("\"ticker\":\"这是一个非…\""))
    }

    @Test
    fun progressColorIsWrittenAsSixDigitHex() {
        val json = HyperOsIslandRenderer.paramForTest(content())
        assertTrue("应输出 #RRGGBB", json.contains("#3A7BD5"))
        assertFalse("不得出现带 alpha 的 8 位色", json.contains("#FF3A7BD5"))
    }
}
