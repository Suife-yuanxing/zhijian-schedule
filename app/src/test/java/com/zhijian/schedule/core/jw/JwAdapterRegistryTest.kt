package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.jw.JwAdapterRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JwAdapterRegistryTest {

    @Test
    fun `qiangzhi wins on jsxsd path`() {
        assertEquals(
            "qiangzhi",
            JwAdapterRegistry.forUrl("https://jw.dgpt.edu.cn/jsxsd/")?.id,
        )
    }

    @Test
    fun `zhengfang new wins on jwglxt path`() {
        assertEquals(
            "zhengfang_new",
            JwAdapterRegistry.forUrl("https://jw.example.edu.cn/jwglxt/xtgl/login_slogin.html")?.id,
        )
    }

    @Test
    fun `host containing jwglxt is not zhengfang`() {
        // 山东科技大学等强智学校的域名里也含 jwglxt，只能看路径，不能看主机名
        assertEquals(
            "qiangzhi",
            JwAdapterRegistry.forUrl("https://jwglxt.sdust.edu.cn/jsxsd/")?.id,
        )
    }

    @Test
    fun `unknown host is not silently treated as a vendor`() {
        assertNull(JwAdapterRegistry.forUrl("https://jw.example.edu.cn/"))
    }

    @Test
    fun `authserver login page is not a timetable`() {
        assertNull(JwAdapterRegistry.forUrl("https://authserver.dgpt.edu.cn/authserver/login?service=x"))
    }
}
