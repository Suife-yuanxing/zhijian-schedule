package com.zhijian.schedule.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 预览弹层里「课表信息可能不对」的提示：抓完让用户知道该去核对一眼 */
class ImportPreviewInfoHintTest {
    private fun preview(hint: String?) =
        ImportPreview(
            source = ImportSource.JW,
            changes = listOf(ImportChange(ImportChangeKind.ADDED, "高数", "周一 · 第1-2节")),
            infoHint = hint,
        )

    @Test
    fun `no hint by default`() {
        val p =
            ImportPreview(
                source = ImportSource.MANUAL,
                changes = emptyList(),
            )
        assertNull(p.infoHint)
    }

    @Test
    fun `hint is carried through to the preview`() {
        val p = preview("课表信息未填写学期，建议在高级设置里补上")
        assertEquals("课表信息未填写学期，建议在高级设置里补上", p.infoHint)
    }

    @Test
    fun `info hint is independent of section hint`() {
        val p = preview("检测到课程排到第 24 周")
        assertNull(p.sectionHint())
        assertEquals("检测到课程排到第 24 周", p.infoHint)
    }
}
