package com.zhijian.schedule.core.jw

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 原生适配器的「周次反推」接入点。
 *
 * 青果与 URP 的课表接口都不含日期，唯一线索是页面上的「当前第 N 周」。
 * 这里钉住两件事：有线索时能反推出来，没有线索时**绝不猜**（返回 null）。
 */
class AdapterWeekInferenceTest {
    private val pageWithWeek =
        """
        <html><body>
          <select id="zc"><option value="10" selected>第10周</option></select>
          <table><tr><td>高等数学<br/>1-16周</td></tr></table>
        </body></html>
        """.trimIndent()

    @Test
    fun qingguo_infers_start_date_from_current_week() {
        val meta = QingGuoAdapter.metaOf(pageWithWeek, 20)
        assertEquals(JwCurrentWeekInference.infer(10, 20), meta?.startDate)
    }

    @Test
    fun urp_infers_start_date_from_current_week() {
        val meta = UrpAdapter.metaOf(pageWithWeek, 20)
        assertEquals(JwCurrentWeekInference.infer(10, 20), meta?.startDate)
    }

    /** 页面没有周次线索时返回 null，让预览去点名「开学日期未同步」而不是编一个 */
    @Test
    fun no_week_signal_yields_no_meta() {
        val plain = "<html><body><table><tr><td>1-16周</td></tr></table></body></html>"
        assertNull(QingGuoAdapter.metaOf(plain, 20))
        assertNull(UrpAdapter.metaOf(plain, 20))
    }

    /** 周次超出总周数说明页面与学期不同源，同样不采用 */
    @Test
    fun week_beyond_total_weeks_is_rejected() {
        val bad =
            """
            <select id="zc"><option value="25" selected>第25周</option></select>
            """.trimIndent()
        assertNull(QingGuoAdapter.metaOf(bad, 20))
        assertNull(UrpAdapter.metaOf(bad, 20))
    }
}
