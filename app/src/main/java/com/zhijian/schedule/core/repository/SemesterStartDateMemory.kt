package com.zhijian.schedule.core.repository

import com.zhijian.schedule.core.jw.JwMeta
import com.zhijian.schedule.core.util.TimeUtils

/**
 * 「这个学期的第 1 周是几号」的按学期记忆。
 *
 * ## 为什么需要一张表
 * 十二家教务里只有强智能从校历页读到开学日期，其余（正方 / 青果 / URP / 树维 / 金智 /
 * eams5 / 超星 / 乘方 / 联奕）的课表接口只有「第 N 周上什么课」，服务端的官方接口即便
 * 带 termStartDate 也锁在网关鉴权后面，学生 WebView 会话拿不到。本地那个 startDate
 * 因此来自建表当天，于是「学期第 10 周」被算成第 1 周。
 *
 * 既然取不到，就让用户填**一次**，然后按学期记住：下一学期（学期名变了）才会重新问。
 * 这张表是判断「这个学期的开学日期到底有没有权威来源」的唯一依据——课表实体上的
 * startDate 永远有值（建表当天写入的），从它身上看不出「这是猜的还是填的」。
 *
 * ## 为什么键是学期名
 * 校历随学期变，而学期名是教务自己写在页面上的（`2026-2027学年第一学期`），
 * 也是用户手填时填的那个字段。用它做键，语义上就是「这个学期的开学日期」。
 *
 * ## 为什么存储格式是 `学期\t日期` 而不是 JSON
 * 学期名与 ISO 日期都不含制表符与换行，一进一出无损；而这张表要进 DataStore 的
 * 单个字符串条目，用 JSON 得多引一个序列化器、多处理一次转义，收益为零。
 *
 * 纯逻辑、不碰 Android API（项目约定，见 CONTRIBUTING 第 3 节）。
 */
object SemesterStartDateMemory {
    /** 最多记住几个学期：四年制本科约 8 个，留一点余量，超出按最久未用淘汰 */
    const val MAX_ENTRIES = 12

    private const val FIELD_SEP = '\t'
    private const val ROW_SEP = '\n'

    /** 所有空白（含全角空格）：学期名的键必须与空白无关 */
    private val BLANK_RUN = Regex("[\\s\\u3000]+")

    /**
     * 学期名的归一化形态（用作键）；空串表示这条记录没有可用的键。
     *
     * ## 为什么把空白**全删**而不是压成一个空格
     * 学期名是「2026-2027学年第一学期」这种没有词间空格的串，它出现在页面上时
     * 偶尔带全角空格、偶尔被折行插进一个换行。压成单个空格之后，「学年 第一学期」
     * 与「学年第一学期」仍然是两个键——同一所学校在两处以两种写法出现时，
     * 记忆就查不到，用户填过的日期白填。
     *
     * 删干净则任何空白写法都落到同一个键上。代价是英文校名里词间空格也不再区分，
     * 而两所只差一个空格的学期名实际上不存在。
     */
    fun normalize(semester: String): String = semester.replace(BLANK_RUN, "")

    /**
     * 这个学期记住的开学日期；没有记录、或记录不是合法日期时返回 null。
     *
     * 记录可能是旧版本写坏的（用户手改过偏好文件），所以读出来也要过一遍格式校验——
     * 一张存着 `"2026-13-45"` 的表会让整学期周次算成 null。
     */
    fun lookup(
        semester: String,
        entries: Map<String, String>,
    ): String? {
        val key = normalize(semester)
        if (key.isEmpty()) return null
        return entries[key]?.takeIf { isUsableDate(it) }
    }

    /**
     * 记住某个学期的开学日期，返回**新的表**（不修改入参）。
     *
     * 重复写同一个学期会把它移到队尾：淘汰按插入顺序进行，刚填的那个不该是
     * 第一个被挤掉的。键或值不可用时原样返回，调用方不需要先做校验。
     */
    fun remember(
        semester: String,
        startDate: String,
        entries: Map<String, String>,
    ): Map<String, String> {
        val key = normalize(semester)
        if (key.isEmpty() || !isUsableDate(startDate)) return entries
        val next = LinkedHashMap<String, String>(entries.size + 1)
        entries.forEach { (k, v) -> if (k != key) next[k] = v }
        next[key] = startDate.trim()
        while (next.size > MAX_ENTRIES) {
            val oldest = next.keys.firstOrNull() ?: break
            next.remove(oldest)
        }
        return next
    }

    /**
     * 抓取到的元数据缺开学日期时，用记忆里的值补上。
     *
     * 来源自己给了日期就以来源为准（那是权威），记忆只补空缺——反过来会让
     * 一所换过校历的学校永远同步不到新日期。
     *
     * @param fallbackSemester 来源没报学期名时用它当键（本地课表的学期）。有些厂家
     *   的接口只给周次、连学期名都不给，而用户此刻的课表显然属于某个学期——
     *   拿本地学期名去查，比直接放弃更接近用户看到的事实
     */
    fun resolve(
        meta: JwMeta,
        entries: Map<String, String>,
        fallbackSemester: String = "",
    ): JwMeta {
        if (meta.startDate.isNotBlank()) return meta
        val key = normalize(meta.semester).ifEmpty { normalize(fallbackSemester) }
        val known = lookup(key, entries) ?: return meta
        return meta.copy(startDate = known)
    }

    /**
     * 已经**确认过**没有来源时的提示（抓取刚回来、预览里 startDate 仍是空的）。
     *
     * 单独一个入口是为了让调用点读得懂：那里已经查过一次记忆（
     * [com.zhijian.schedule.core.repository.TimetableImportService] 的 resolve），
     * 传空表不是"忘了查"，而是"查过了，没有"。写成 `prompt(x, emptyMap())`
     * 会让下一个人以为漏了一次查询。
     */
    fun missingPrompt(semester: String): String? = prompt(semester, emptyMap())

    /**
     * 需要用户补开学日期时的那句话；无需提示时返回 null。
     *
     * 只报「这个学期还没有权威的开学日期」，不判断具体是哪一家教务——判错的代价是
     * 用户被要求填一个其实已经取到的值，而这件事他没法反驳。
     *
     * ## 为什么不写「去设置页」
     * 这句话会出现在**两个屏幕**上：教务导入页（抓完发现没有日期）与设置页（每次进来
     * 提醒）。真机截图当场暴露了问题——在设置页上它说「去设置页填一次」，而用户已经
     * 站在那一页、那句提示正压着「课表信息」卡片。两句话单独读都通顺，所以这类错法
     * 在代码评审里看不出来，只有在它真正出现的那一屏上才刺眼。
     * 改成只点名**控件**（「课表信息」在设置页是一个卡片标题，在全 App 唯一），
     * 两个位置就都成立。
     */
    fun prompt(
        semester: String,
        entries: Map<String, String>,
    ): String? {
        val key = normalize(semester)
        if (key.isEmpty() || lookup(key, entries) != null) return null
        // 上屏用**原文**而不是归一化后的键：键把空白删干净了，而那是给查表用的，
        // 摆到屏幕上会读成「学年第一学期」连在一起的一串
        return "「" + semester.trim() + "」的开学日期还没有来源 · 在「课表信息」里填一次，之后按学期自动复用"
    }

    /**
     * 这次抓取**还差什么**：学期名没取到、或开学日期没有权威来源。
     *
     * ## 为什么要看「抓到了什么」而不只看「记住了什么」
     * 强智能从校历页直接读到开学日期，此时记忆表里还没有它（记忆是在确认导入时
     * 才写的）。只看记忆会把这种**已经拿到日期**的成功抓取报成「缺开学日期」——
     * 那是最糟的一类误报：用户被要求填一个屏幕上刚刚显示过的值。
     *
     * 所以来源给了日期就直接放行；只有来源没给、记忆里也没有时才算缺。
     *
     * ## 为什么学期名缺失也算「缺」
     * 记忆表以学期名为键。学期名为空时连键都没有——这一次填的日期无法归属到
     * 任何学期，下次抓取照样复用不到。所以它必须一起说。
     *
     * @param semester 这次抓取声明（或本地已有）的学期名
     * @param fetchedStartDate 这次抓取**自己**声明的开学日期；非空即视为权威
     * @param entries 记忆表
     */
    fun missingInfoPrompt(
        semester: String,
        fetchedStartDate: String,
        entries: Map<String, String>,
    ): String? {
        // 来源给了日期就是权威，无论学期名有没有，都不算缺
        if (fetchedStartDate.isNotBlank()) return null
        if (normalize(semester).isEmpty()) return SEMESTER_MISSING
        // 记忆里有：抓取路径的 resolve 已经把它补进 meta 了，不必再提醒
        if (lookup(semester, entries) != null) return null
        return prompt(semester, entries)
    }

    /**
     * 学期名没取到时的提示。
     *
     * 说清两件事：这一次没取到，以及为什么值得填——学期名是记忆表的键，
     * 空着就没法把开学日期归到任何学期上，下次抓取照样复用不到。
     * 只有这一条会提到「记住」，因为其余提示面对的用户已经有学期名了。
     *
     * 与 [prompt] 一样不写位置指代（见那里的注释）。
     */
    private const val SEMESTER_MISSING = "这次抓取没有取到学期名 · 在「课表信息」里填一次，开学日期才能按学期记住"

    /** 表 → 单行存储串 */
    fun encode(entries: Map<String, String>): String =
        entries.entries.joinToString(ROW_SEP.toString()) { (k, v) -> k + FIELD_SEP + v }

    /**
     * 单行存储串 → 表；坏行**跳过**而不是整表作废。
     *
     * 一个字符写坏就丢掉整个学期的记忆，代价是用户再填一次；而整表作废会让
     * 另外十一个学期的记录一起消失，那才是真正的数据丢失。
     */
    fun decode(text: String): Map<String, String> {
        if (text.isBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        text.split(ROW_SEP).forEach { row ->
            val at = row.indexOf(FIELD_SEP)
            if (at <= 0) return@forEach
            val key = normalize(row.substring(0, at))
            val value = row.substring(at + 1).trim()
            if (key.isNotEmpty() && isUsableDate(value)) out[key] = value
        }
        return out
    }

    private fun isUsableDate(raw: String): Boolean = TimeUtils.parseDate(raw.trim()) != null
}
