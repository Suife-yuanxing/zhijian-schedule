package com.zhijian.schedule.core.model

import com.zhijian.schedule.core.jw.SectionTimeDraft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 预览里「作息会不会跟着改」的提示：用户得在点确认前知道这件事 */
class ImportPreviewSectionHintTest {
    private fun preview(times: List<SectionTimeDraft>) =
        ImportPreview(
            source = ImportSource.JW,
            changes = listOf(ImportChange(ImportChangeKind.ADDED, "高数", "周一 · 第1-2节")),
            sectionTimes = times,
        )

    @Test
    fun `no incoming times means nothing to announce`() {
        val p = preview(emptyList())
        assertFalse(p.hasSectionUpdate)
        assertEquals(null, p.sectionHint())
    }

    @Test
    fun `incoming times announce the new first and last section`() {
        val p =
            preview(
                listOf(
                    SectionTimeDraft(1, "08:00", "08:45"),
                    SectionTimeDraft(2, "08:50", "09:35"),
                    SectionTimeDraft(3, "10:00", "10:45"),
                ),
            )
        assertTrue(p.hasSectionUpdate)
        assertEquals("作息时间将同步更新（第 1 节 08:00 起，共 3 节）", p.sectionHint())
    }
}
