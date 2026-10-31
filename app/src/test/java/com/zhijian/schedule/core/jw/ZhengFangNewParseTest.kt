package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.jw.ZhengFangNewAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ZhengFangNewParseTest {
    private val kbListJson =
        """
        {
          "kbList": [
            {"kcmc":"高等数学","xm":"王五","cdmc":"理A401","xqj":"1","jc":"1-2节","zcd":"1-16周"},
            {"kcmc":"大学英语","xm":"李四","cdmc":"教101","xqj":"3","jc":"3-4节","zcd":"1-16周(单)"}
          ],
          "xsxx": {"XM":"张三"}
        }
        """.trimIndent()

    @Test
    fun `parse zhengfang new kbList json`() {
        val drafts = ZhengFangNewAdapter.parseJson(kbListJson, 20)
        assertEquals(2, drafts.size)

        val math = drafts.first { it.name == "高等数学" }
        assertEquals(1, math.dayOfWeek)
        assertEquals(1, math.startSection)
        assertEquals(2, math.endSection)
        assertEquals("王五", math.teacher)
        assertEquals("理A401", math.room)
        assertEquals(16, math.weekMask.count { it == '1' })

        val english = drafts.first { it.name == "大学英语" }
        assertEquals(3, english.dayOfWeek)
        assertEquals(3, english.startSection)
        assertEquals(4, english.endSection)
        // 单周：第 1 周上课、第 2 周不上，共 8 周
        assertEquals('1', english.weekMask[0])
        assertEquals('0', english.weekMask[1])
        assertEquals(8, english.weekMask.count { it == '1' })
    }

    @Test
    fun `merge duplicate rows by weeks`() {
        val json =
            """
            {"kbList":[
              {"kcmc":"线性代数","xm":"赵六","cdmc":"理A402","xqj":"2","jc":"5-6节","zcd":"1-8周"},
              {"kcmc":"线性代数","xm":"赵六","cdmc":"理A402","xqj":"2","jc":"5-6节","zcd":"9-16周"}
            ]}
            """.trimIndent()
        val drafts = ZhengFangNewAdapter.parseJson(json, 20)
        assertEquals(1, drafts.size)
        assertEquals(16, drafts.first().weekMask.count { it == '1' })
    }

    @Test
    fun `malformed html returns empty instead of crashing`() {
        assertTrue(ZhengFangNewAdapter.parseJson("<html>登录</html>", 20).isEmpty())
    }

    @Test
    fun `parse field aliases and fallback room`() {
        val json =
            """
            {"kbList":[{"kcmc":" 大学物理 ","xm":"周老师","xqmc":"主校区","xqj":"星期三","jcs":"5-6节"}]}
            """.trimIndent()
        val drafts = ZhengFangNewAdapter.parseJson(json, 20)
        assertEquals(1, drafts.size)

        val physics = drafts.first()
        assertEquals("大学物理", physics.name)
        assertEquals(3, physics.dayOfWeek)
        assertEquals(5, physics.startSection)
        assertEquals(6, physics.endSection)
        // cdmc 缺失时回落到校区名
        assertEquals("主校区", physics.room)
        // zcd 缺失时按整学期处理
        assertEquals(20, physics.weekMask.count { it == '1' })
    }

    @Test
    fun `empty kbList returns empty`() {
        assertTrue(ZhengFangNewAdapter.parseJson("""{"kbList":[]}""", 20).isEmpty())
    }

    @Test
    fun `term candidates follow academic calendar`() {
        assertEquals(
            listOf("2025-2026" to "3", "2025-2026" to "12"),
            ZhengFangNewAdapter.termCandidates(LocalDate.of(2025, 10, 19)),
        )
        assertEquals(
            listOf("2025-2026" to "12", "2025-2026" to "3"),
            ZhengFangNewAdapter.termCandidates(LocalDate.of(2026, 3, 1)),
        )
        assertEquals(
            listOf("2025-2026" to "3", "2025-2026" to "12"),
            ZhengFangNewAdapter.termCandidates(LocalDate.of(2026, 1, 10)),
        )
    }

    // ---------- 成绩（xs_kccj_query.do 的两种响应形状） ----------

    /** 平铺形状：字段直接在条目上（部分部署） */
    @Test
    fun `parse flat score items`() {
        val json =
            """
            {"items":[
              {"kcmc":"高等数学","kch":"0911001","cj":"89","xf":"4","jd":"3.7","xnmc":"2025-2026","xqmc":"1"},
              {"kcmc":"大学英语","kch":"0911002","cj":"B+","xf":"3","jd":"","xnmc":"2025-2026","xqmc":"1"}
            ]}
            """.trimIndent()
        val records = ZhengFangNewAdapter.parseZhengFangGrades(json)
        assertEquals(2, records.size)
        val math = records[0]
        assertEquals("高等数学", math.name)
        assertEquals("89", math.score)
        assertEquals("0911001", math.code)
        assertEquals(4.0, math.credit, 0.001)
        assertEquals(3.7, math.gpa!!, 0.001)
        assertEquals("2025-2026-1", math.term)
        // jd 空串 → null（不猜 0）；xf="3" 正常解析
        val english = records[1]
        assertEquals("B+", english.score)
        assertNull(english.gpa)
        assertEquals(3.0, english.credit, 0.001)
    }

    /** XSKC 嵌套形状：字段在条目的 XSKC 子对象里（主流部署） */
    @Test
    fun `parse nested XSKC score items`() {
        val json =
            """
            {"items":[
              {"xh":"20250101","xm":"张三","XSKC":{"KCMC":"大学物理","KCH":"0911003","CJ":"91","XF":"3","JD":"4.0","XNMC":"2025-2026","XQMC":"2"}}
            ]}
            """.trimIndent()
        val records = ZhengFangNewAdapter.parseZhengFangGrades(json)
        assertEquals(1, records.size)
        val physics = records.first()
        assertEquals("大学物理", physics.name)
        assertEquals("91", physics.score)
        assertEquals(3.0, physics.credit, 0.001)
        assertEquals(4.0, physics.gpa!!, 0.001)
        assertEquals("2025-2026-2", physics.term)
    }

    @Test
    fun `malformed score json returns empty`() {
        assertTrue(ZhengFangNewAdapter.parseZhengFangGrades("not json").isEmpty())
        assertTrue(ZhengFangNewAdapter.parseZhengFangGrades("""{"items":[]}""").isEmpty())
        assertTrue(ZhengFangNewAdapter.parseZhengFangGrades("""{"items":[{"kcmc":""}]}""").isEmpty())
    }
}
