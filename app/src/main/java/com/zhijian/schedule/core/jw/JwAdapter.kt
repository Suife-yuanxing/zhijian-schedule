package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.model.CourseDraft
import okhttp3.OkHttpClient

/**
 * 抓取前要在网页里完成的动作。
 *
 * 每家教务的菜单路径都不一样（强智是「学生课表」、URP 是「个人管理 → 本学期课表」、
 * eams5 是单页应用里等渲染），一句统一的「请进入学生课表」帮不上忙，
 * 所以指引文案按适配器定制，由 UI 直接渲染。
 */
data class AdapterSteps(
    /** 登录后要进入的页面，会显示成「② {timetablePage}」 */
    val timetablePage: String,
    /** 该校特有的补充说明；空串表示不显示 */
    val note: String = "",
)

/**
 * 教务适配器（方案 4.6：脚本化适配生态的原生实现），
 * 与 WebView 共享 Cookie 完成 HTTP 重放（方案 4.10）。
 *
 * [match] 用打分代替布尔判断：分数越高越具体，注册表取最高分。
 * 不允许「任何 URL 都返回 true」的兜底适配器——那会把所有学校都按某一家解析。
 */
interface JwAdapter {
    val id: String
    val name: String

    /** 抓取失败的指引与引导条文案来源 */
    val steps: AdapterSteps

    /** 协议识别打分：0 = 不支持；分数越高越具体 */
    fun match(url: String): Int

    /** 兼容旧调用；新代码请直接用 [match] */
    fun supports(url: String): Boolean = match(url) > 0

    /**
     * 统一认证登录入口（如强智的 `sso.jsp` → authserver）。
     *
     * 两处消费：教务导入 WebView **优先用它作落点**（学校本地登录入口常被
     * WAF 拦截 / 甩到校内地址，统一认证链路更稳）；会话过期时 [JwSessionRecovery]
     * 也沿它做静默 GET 重登。返回 null 表示没有这样的入口
     * （例如 gxgc 门户本身就是统一登录，本地入口即正解）。
     */
    fun ssoEntry(baseUrl: String): String? = null

    /**
     * 抓取**全部学期**成绩，随课表抓取一起刷新并整体覆盖本地成绩。
     *
     * 返回空列表 = 本次没有抓到成绩（未适配 / 页面结构不认识 / 未登录），
     * 调用方静默跳过，不影响课表结果。[gradeFetchSupported] 供成绩页做能力提示。
     * 实现一律先过 [JwUrlGuard.safeHttpUrl]——baseUrl 来自用户输入。
     */
    suspend fun fetchGrades(
        client: OkHttpClient,
        baseUrl: String,
    ): List<GradeRecord> = emptyList()

    /** 成绩抓取是否已适配（成绩页按钮与提示用；[fetchGrades] 返回空也以它为准提示） */
    val gradeFetchSupported: Boolean
        get() = false

    /**
     * 抓取课表，连同页面里写着的作息时间一起返回；未登录时抛 [JwException.NeedsLogin]。
     *
     * 作息是教务自己的排课时间，比本地默认值权威，所以顺手带回；
     * 页面没有时间文字时 [JwFetchResult.sectionTimes] 为空，表示「没法更新」。
     *
     * @param baseUrl 用户在 WebView 中登录的教务入口地址
     */
    suspend fun fetchSchedule(
        client: OkHttpClient,
        baseUrl: String,
        totalWeeks: Int,
    ): JwFetchResult

    /** 只要课程列表的旧调用面；实现见各适配器的 [fetchSchedule]。 */
    suspend fun fetch(
        client: OkHttpClient,
        baseUrl: String,
        totalWeeks: Int,
    ): List<CourseDraft> = fetchSchedule(client, baseUrl, totalWeeks).courses
}

sealed class JwException(
    message: String,
) : Exception(message) {
    class NeedsLogin :
        JwException(
            "尚未登录或登录已过期，请先在网页中登录教务",
        )

    class ParseFailed(
        message: String,
    ) : JwException(message)

    class NetworkFailed(
        message: String,
    ) : JwException(message)
}

/**
 * HTTP 状态码 → 用户可读的失败原因。
 * 5xx 多来自学校 WAF / 源站临时抖动，重试往往能恢复；单独给出提示，避免暴露裸状态码。
 */
internal fun httpFailure(code: Int): JwException.NetworkFailed =
    if (code >= 500) {
        JwException.NetworkFailed("学校服务器暂时不可用（HTTP $code），请稍后重试或连接校园网 / WebVPN")
    } else {
        JwException.NetworkFailed("教务系统返回 HTTP $code")
    }

/**
 * 协议识别打分梯度：注册表取最高分，语义集中在这里，避免各适配器散落魔法数字。
 * 新增适配器请从这里取值，不要写字面量。
 */
object JwMatchScore {
    /** 路径命中该厂商的专有入口 */
    const val EXACT = 100

    /** 强特征路径（/academic/、/eams/ 等），能确定厂商但不能确定端点 */
    const val STRONG = 95

    /** 已知端点（登录页、课表页） */
    const val ENDPOINT = 90

    /** 学校目录标注的协议，未实测 */
    const val CATALOG = 85

    /** 目录标注但凭据更弱（仅域名匹配） */
    const val CATALOG_WEAK = 80

    const val HINT = 70

    const val WEAK = 60

    const val HOST = 40

    const val NONE = 0
}

object JwAdapterRegistry {
    private val adapters: List<JwAdapter> =
        listOf(
            QiangZhiAdapter,
            ZhengFangNewAdapter,
            ZhengFangOldAdapter,
            QingGuoAdapter,
            UrpAdapter,
            ShuWeiClassicAdapter,
            // 金智与树维同住 /eams/：裸 /eams/ 仍判树维，金智只靠自己的端点指纹赢
            JinZhiAdapter,
            Eams5Adapter,
            ChaoXingAdapter,
            ChengFangAdapter,
            LianYiAdapter,
            // 校级条目放最后：它靠主机名精确命中，不影响任何厂商的路径指纹
            GuangXiGcAdapter,
        )

    /** 未识别时返回 null，由调用方给出明确提示；绝不静默回退到某一家厂商 */
    fun forUrl(url: String): JwAdapter? =
        adapters
            .map { it to it.match(url) }
            .filter { it.second > 0 }
            .maxByOrNull { it.second }
            ?.first

    fun all(): List<JwAdapter> = adapters
}
