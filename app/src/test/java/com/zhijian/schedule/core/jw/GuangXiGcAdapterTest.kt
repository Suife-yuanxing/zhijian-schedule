package com.zhijian.schedule.core.jw

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class GuangXiGcAdapterTest {
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    private companion object {
        /** 校外实测被过滤的那个校内端口 */
        const val OFF_CAMPUS_PORT = 8088
    }

    private val portal = "https://zhxy.gxgcedu.com/login"

    @Test
    fun `portal host claims the landing and login pages`() {
        assertEquals(JwMatchScore.EXACT, GuangXiGcAdapter.match(portal))
        assertEquals(JwMatchScore.EXACT, GuangXiGcAdapter.match("https://zhxy.gxgcedu.com/"))
        assertEquals(JwMatchScore.EXACT, GuangXiGcAdapter.match("https://zhxy.gxgcedu.com/academic/"))
    }

    /** 已经进了课表页就交给 URP，本校适配器不该把带学期参数的地址改回门户根 */
    @Test
    fun `timetable endpoint belongs to urp`() {
        val url = "https://zhxy.gxgcedu.com/academic/manager/coursearrange/showTimetable.do?timetableType=STD"
        assertEquals(JwMatchScore.NONE, GuangXiGcAdapter.match(url))
        assertEquals(UrpAdapter.id, JwAdapterRegistry.forUrl(url)?.id)
    }

    @Test
    fun `other hosts are untouched`() {
        assertEquals(JwMatchScore.NONE, GuangXiGcAdapter.match("https://jw.example.edu.cn/jsxsd/"))
        assertEquals(JwMatchScore.NONE, GuangXiGcAdapter.match(""))
    }

    /** 校外直连时门户把 /academic/ 302 到同一主机的 :8088，这是唯一能证明「被拦」的证据 */
    @Test
    fun `off campus redirect is recognised by non standard port`() {
        assertEquals(
            8088,
            GuangXiGcAdapter.offCampusPort("http://zhxy.gxgcedu.com:8088/login", "zhxy.gxgcedu.com"),
        )
    }

    @Test
    fun `normal redirects are not off campus`() {
        val host = "zhxy.gxgcedu.com"
        assertNull(GuangXiGcAdapter.offCampusPort("https://zhxy.gxgcedu.com/login", host))
        assertNull(GuangXiGcAdapter.offCampusPort("https://zhxy.gxgcedu.com:443/login", host))
        assertNull(GuangXiGcAdapter.offCampusPort("http://zhxy.gxgcedu.com:80/login", host))
        assertNull(GuangXiGcAdapter.offCampusPort(null, host))
        assertNull(GuangXiGcAdapter.offCampusPort("/login", host))
    }

    /** 别的学校跳自己的 :8088 与本校无关，不能拿来报「校外被拦」 */
    @Test
    fun `foreign host with port is ignored`() {
        assertNull(
            GuangXiGcAdapter.offCampusPort("http://jw.other.edu.cn:8088/login", "zhxy.gxgcedu.com"),
        )
    }

    @Test
    fun `steps name the portal and the intranet requirement`() {
        assertTrue(GuangXiGcAdapter.steps.timetablePage.contains("门户"))
        assertTrue(GuangXiGcAdapter.steps.note.contains("校园网"))
        assertTrue(GuangXiGcAdapter.steps.note.contains("WebVPN"))
    }

    /** 门户统一认证有图形验证码与扫码，静默重放登录只会把账号逻辑打乱 */
    @Test
    fun `no silent sso replay`() {
        assertNull(GuangXiGcAdapter.ssoEntry(portal))
    }

    @Test
    fun `registry routes the portal here and the timetable to urp`() {
        assertEquals(GuangXiGcAdapter.id, JwAdapterRegistry.forUrl(portal)?.id)
        assertEquals(GuangXiGcAdapter.id, JwAdapterRegistry.forUrl("https://zhxy.gxgcedu.com/")?.id)
    }

    /**
     * 探测要「只看不跟」：跟随后 Location 会被沿链消耗掉，校外被拦的证据就丢了。
     * 这里让 /academic/ 回 302 → :8088，断言拿到端口而不是把探测结果吞掉。
     */
    @Test
    fun `probe reads the 302 location without following it`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", "http://" + server.hostName + ":" + OFF_CAMPUS_PORT + "/login"),
                )
                val blocked = GuangXiGcAdapter.offCampusPortProbe(client, server.url("/login").toString())
                assertEquals(8088, blocked)
                assertEquals(1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }

    /**
     * 校内也可能撞上「302 到 :8088」那个后端；端口连得上就不该拦，否则校内用户被劝去连校园网。
     * MockWebServer 自己占着这个端口，等于「端口活着」。
     */
    @Test
    fun `reachable port means we are on campus`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", "http://" + server.hostName + ":" + server.port + "/login"),
                )
                assertNull(GuangXiGcAdapter.blockedPort(client, server.url("/login").toString()))
            } finally {
                server.shutdown()
            }
        }

    /**
     * 端口关着 = 确实在校外被挡，这时才该提示连校园网。
     *
     * 探测本身仍要能通：校外时门户是活的，只是它指去的校内端口不通。
     * 所以这里用两台 server——一台当门户（正常应答 302），另一台的端口先关掉当靶子。
     */
    @Test
    fun `unreachable port means we are off campus`() =
        runBlocking {
            val portal = MockWebServer()
            portal.start()
            val deadPort = closedPort()
            try {
                portal.enqueue(
                    MockResponse()
                        .setResponseCode(302)
                        .addHeader("Location", "http://" + portal.hostName + ":" + deadPort + "/login"),
                )
                assertEquals(deadPort, GuangXiGcAdapter.blockedPort(client, portal.url("/login").toString()))
            } finally {
                portal.shutdown()
            }
        }

    /** 探测请求本身就失败时无从判断，宁可放行让 URP 报它自己的错 */
    @Test
    fun `dead portal never claims off campus`() =
        runBlocking {
            val portal = MockWebServer()
            portal.start()
            val url = portal.url("/login").toString()
            portal.shutdown()
            assertNull(GuangXiGcAdapter.blockedPort(client, url))
        }

    /** 起一台 server 拿到真实空闲端口后立刻关掉，得到一个确定没人听的端口 */
    private fun closedPort(): Int {
        val probe = MockWebServer()
        probe.start()
        val port = probe.port
        probe.shutdown()
        return port
    }

    /** 没有跳转就没有「校外」的证据，无论端口通不通 */
    @Test
    fun `no evidence never claims off campus`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setBody("<html>ok</html>"))
                assertNull(GuangXiGcAdapter.blockedPort(client, server.url("/login").toString()))
            } finally {
                server.shutdown()
            }
        }

    /** 校内：/academic/ 直接回教务页面，没有跳转，不该报「请连校园网」 */
    @Test
    fun `no redirect means on campus`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setBody("<html>ok</html>"))
                assertNull(GuangXiGcAdapter.offCampusPortProbe(client, server.url("/login").toString()))
            } finally {
                server.shutdown()
            }
        }

    /** 提示里必须写出真实端口：学校换端口时文案跟着变，不会说谎 */
    @Test
    fun `hint names the actual port`() {
        val hint = GuangXiGcAdapter.offCampusHint(9090)
        assertTrue(hint.contains("9090"))
        assertTrue(hint.contains("WebVPN"))
    }
}
