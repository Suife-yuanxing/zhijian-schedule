package com.zhijian.schedule

import com.zhijian.schedule.core.jw.JwSessionRecovery
import com.zhijian.schedule.core.jw.isSessionDeadPage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** 会话静默恢复：重放 jsxsd → 统一认证的 GET 链路（真实站点的页面形态作为样例） */
class JwSessionRecoveryTest {

    private lateinit var server: MockWebServer
    private val client = OkHttpClient.Builder()
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun url(path: String) = server.url(path).toString()

    private fun dispatcher(block: (RecordedRequest) -> MockResponse) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = block(request)
    }

    /** 站点真实形态：sso.jsp 用 JS 跳统一认证；CAS 免密 302 回来带 ticket；ticket 页重建会话后进入主页 */
    @Test
    fun followsJsRedirectThroughCasChainToSuccess() = runBlocking {
        server.dispatcher = dispatcher { request ->
            val path = request.path.orEmpty()
            when {
                path.startsWith("/authserver/login") -> MockResponse().setResponseCode(302)
                    .setHeader("Location", "/jsxsd/sso.jsp?ticket=ST-123")

                path.startsWith("/jsxsd/sso.jsp?ticket") ->
                    MockResponse().setResponseCode(200)
                        .setBody("<html><title>教学一体化服务平台</title><body>主页内容</body></html>")

                path.startsWith("/jsxsd/sso.jsp") ->
                    MockResponse().setResponseCode(200).setBody(
                        "<script languge='javascript'>window.location.href='${
                            url("/authserver/login?service=http%3A%2F%2Fjw%2Fjsxsd%2Fsso.jsp")
                        }'</script>",
                    )

                else -> MockResponse().setResponseCode(404)
            }
        }

        assertTrue(JwSessionRecovery.recoverFrom(client, url("/jsxsd/sso.jsp")))
        assertEquals(3, server.requestCount)
        // 每一跳都带浏览器 UA（跨域到统一认证服务器后不能退化成 okhttp 默认 UA）
        repeat(3) {
            val ua = server.takeRequest().getHeader("User-Agent").orEmpty()
            assertTrue(ua.contains("Mozilla"))
        }
    }

    /** CAS 会话也没了：停在统一认证登录表单 → 恢复失败（此时才值得提示用户重新登录） */
    @Test
    fun returnsFalseWhenCasSessionIsGone() = runBlocking {
        server.dispatcher = dispatcher {
            MockResponse().setResponseCode(200)
                .setBody("<html><title>统一身份认证平台</title><input type=\"password\" id=\"password\"></html>")
        }

        assertFalse(JwSessionRecovery.recoverFrom(client, url("/jsxsd/sso.jsp")))
    }

    /** 非本校/无 SSO 入口：入口 404 → 恢复失败而不是异常 */
    @Test
    fun returnsFalseOnHttpError() = runBlocking {
        server.dispatcher = dispatcher { MockResponse().setResponseCode(404) }
        assertFalse(JwSessionRecovery.recoverFrom(client, url("/jsxsd/sso.jsp")))
    }

    /** 环状跳转在步数上限内收敛为失败 */
    @Test
    fun stopsAfterTooManyRedirects() = runBlocking {
        server.dispatcher = dispatcher { request ->
            when (request.path.orEmpty()) {
                "/a" -> MockResponse().setResponseCode(302).setHeader("Location", "/b")
                else -> MockResponse().setResponseCode(302).setHeader("Location", "/a")
            }
        }
        assertFalse(JwSessionRecovery.recoverFrom(client, url("/a")))
        assertTrue(server.requestCount <= 8)
    }

    /** meta refresh 形态的跳转同样跟随 */
    @Test
    fun followsMetaRefresh() = runBlocking {
        server.dispatcher = dispatcher { request ->
            when (request.path.orEmpty()) {
                "/start" -> MockResponse().setResponseCode(200)
                    .setBody("<html><head><meta http-equiv=\"refresh\" content=\"0;url=/main\"></head></html>")
                else -> MockResponse().setResponseCode(200).setBody("<html><body>主页</body></html>")
            }
        }
        assertTrue(JwSessionRecovery.recoverFrom(client, url("/start")))
    }

    /** 大页面里的散落 location 赋值（菜单/按钮脚本）不算跳转页，按终点页处理 */
    @Test
    fun doesNotChaseIncidentalLocationAssignmentInLargePage() = runBlocking {
        server.dispatcher = dispatcher {
            MockResponse().setResponseCode(200).setBody(
                "<html><body>${"x".repeat(2000)}<script>location.href='/somewhere'</script></body></html>",
            )
        }
        assertTrue(JwSessionRecovery.recoverFrom(client, url("/page")))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun sessionDeadPageDetection() {
        assertTrue(isSessionDeadPage("<html><input type=\"password\"></html>"))
        assertTrue(isSessionDeadPage("<form action=\"/jsxsd/LoginToXkLdap\">"))
        assertTrue(isSessionDeadPage("{\"flag1\":3,\"msgContent\":\"您的账号在其它地方登录\"}"))
        assertFalse(isSessionDeadPage("<html><title>教学一体化服务平台</title>课表数据</html>"))
    }
}
