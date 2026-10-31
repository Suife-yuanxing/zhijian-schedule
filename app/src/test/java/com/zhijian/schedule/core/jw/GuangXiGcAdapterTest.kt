package com.zhijian.schedule.core.jw

import com.zhijian.schedule.core.util.WeekMask
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * 广西工程职业学院（CIP 智慧校园）适配器。
 *
 * fixture 全部来自 2026-10 用真实账号（2026 级金属智能加工技术1班）实测抓到的报文：
 * 课表 JSON 的槽位键、排课规则、课表页的 CSRF 与学期写法都照抄真实响应，
 * 只有房间号之外的个人信息（教师、教室）保留了原文——它们就是要抓的东西。
 */
class GuangXiGcAdapterTest {
    private val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()

    private val portal = "https://zhxy.gxgcedu.com/login"

    /** 「钟点接口为空」的记忆是 object 级状态：每个用例前清掉，测试顺序才无关 */
    @Before
    fun resetEmptyRuleTimeMemo() {
        GuangXiGcAdapter.clearEmptyRuleTimeMemo()
    }

    @Test
    fun `portal host claims every page`() {
        assertEquals(JwMatchScore.EXACT, GuangXiGcAdapter.match(portal))
        assertEquals(JwMatchScore.EXACT, GuangXiGcAdapter.match("https://zhxy.gxgcedu.com/"))
        assertEquals(
            JwMatchScore.EXACT,
            GuangXiGcAdapter.match("https://zhxy.gxgcedu.com/timetable/timetableView/studentTimetable"),
        )
    }

    @Test
    fun `other hosts are untouched`() {
        assertEquals(JwMatchScore.NONE, GuangXiGcAdapter.match("https://jw.example.edu.cn/jsxsd/"))
        assertEquals(JwMatchScore.NONE, GuangXiGcAdapter.match(""))
        assertEquals(JwMatchScore.NONE, GuangXiGcAdapter.match("https://zhxy.gxgcedu.com.evil.io/login"))
    }

    @Test
    fun `no silent sso replay`() {
        assertNull(GuangXiGcAdapter.ssoEntry(portal))
    }

    @Test
    fun `steps name the portal entry`() {
        assertTrue(GuangXiGcAdapter.steps.timetablePage.contains("门户"))
        assertTrue(GuangXiGcAdapter.steps.note.contains("登录"))
    }

    @Test
    fun `registry routes the portal here`() {
        assertEquals(GuangXiGcAdapter.id, JwAdapterRegistry.forUrl(portal)?.id)
        assertEquals(GuangXiGcAdapter.id, JwAdapterRegistry.forUrl("https://zhxy.gxgcedu.com/sys/portal")?.id)
    }

    // ---------- 课表页解析 ----------

    @Test
    fun `page yields csrf token and current semester`() {
        val (csrf, semester) = GuangXiGcAdapter.parseTimetablePage(PAGE_HTML)!!
        assertEquals("97de3745-c400-4c52-9e4a-3ad630422c48", csrf)
        assertEquals("17804696533371735297414361881535", semester)
    }

    @Test
    fun `login page has nothing to parse`() {
        assertNull(GuangXiGcAdapter.parseTimetablePage(LOGIN_PAGE_HTML))
    }

    // ---------- 排课规则 → 槽位偏移 ----------

    @Test
    fun `band hours follow the real rule`() {
        val hours = GuangXiGcAdapter.bandHoursOf(RULE_JSON)
        assertEquals(0, hours.offsetOf("morning"))
        assertEquals(0, hours.offsetOf("am"))
        assertEquals(4, hours.offsetOf("pm"))
        assertEquals(8, hours.offsetOf("night"))
        assertEquals(12, hours.offsetOf("evening"))
    }

    /** 字段认不出来时整体退回实测值——偏移错一节，全表全错，宁缺毋滥 */
    @Test
    fun `unreadable rule falls back to the measured default`() {
        assertEquals(CipBandHours.DEFAULT, GuangXiGcAdapter.bandHoursOf("""{"code":0,"data":{}}"""))
        assertEquals(CipBandHours.DEFAULT, GuangXiGcAdapter.bandHoursOf("not json"))
        assertEquals(CipBandHours.DEFAULT, GuangXiGcAdapter.bandHoursOf(RULE_JSON_PARTIAL))
    }

    // ---------- 课表解析 ----------

    /** 槽位键 = 星期_时段内节段；偏移按排课规则累加（前端 timetable.ctrls.js 的口径） */
    @Test
    fun `slots map to day sections with band offset`() {
        val drafts = GuangXiGcAdapter.parseTimetable(TIMETABLE_JSON, CipBandHours.DEFAULT, totalWeeks = 20)
        val byName = drafts.associateBy { it.name }

        // am/1_34 → 周一上午 3-4 节
        val math = byName.getValue("高等数学")
        assertEquals(1, math.dayOfWeek)
        assertEquals(3, math.startSection)
        assertEquals(4, math.endSection)
        assertEquals("陆梅蓝", math.teacher)
        assertEquals("2-601", math.room)

        // am/2_12 → 周二上午 1-2 节
        val pe = byName.getValue("大学体育1")
        assertEquals(2, pe.dayOfWeek)
        assertEquals(1, pe.startSection)
        assertEquals(2, pe.endSection)

        // pm/1_12 → 周一下午 5-6 节（偏移 = 上午 4 节）
        val metal = byName.getValue("金属材料热处理及加工应用")
        assertEquals(1, metal.dayOfWeek)
        assertEquals(5, metal.startSection)
        assertEquals(6, metal.endSection)

        // night/1_12 → 全天第 9-10 节
        val evening = byName.getValue("晚自习")
        assertEquals(1, evening.dayOfWeek)
        assertEquals(9, evening.startSection)
        assertEquals(10, evening.endSection)
    }

    /** weekNos 是逗号分隔的全学期周次列表，直接建掩码 */
    @Test
    fun `week mask is built from the week list`() {
        val drafts = GuangXiGcAdapter.parseTimetable(TIMETABLE_JSON, CipBandHours.DEFAULT, totalWeeks = 20)
        val math = drafts.first { it.name == "高等数学" }
        assertEquals((6..18).toList(), WeekMask.weeks(math.weekMask))
    }

    /** 周次字段缺失时退回 startEndWeek，再缺失按整学期 */
    @Test
    fun `week fallback chain`() {
        val drafts = GuangXiGcAdapter.parseTimetable(TIMETABLE_JSON, CipBandHours.DEFAULT, totalWeeks = 20)
        // 国家安全教育只有 startEndWeek（weekNos 缺失）
        val security = drafts.first { it.name == "国家安全教育" }
        assertEquals(listOf(7, 8, 9, 10, 11, 12, 13, 14), WeekMask.weeks(security.weekMask))
        // 晚自习两个周次字段都没有 → 整学期
        val evening = drafts.first { it.name == "晚自习" }
        assertEquals(WeekMask.of(20), evening.weekMask)
    }

    @Test
    fun `garbled payload parses to nothing`() {
        assertEquals(0, GuangXiGcAdapter.parseTimetable("", CipBandHours.DEFAULT, 20).size)
        assertEquals(0, GuangXiGcAdapter.parseTimetable("""{"code":500,"msg":"x"}""", CipBandHours.DEFAULT, 20).size)
    }

    // ---------- 作息 ----------

    /** 教务没配钟点时用本校实测作息：12 节、每节 45 分钟（校方排课规定）、时间单调递进 */
    @Test
    fun `school hours ship as the fallback`() {
        val times = GuangXiGcAdapter.SECTION_TIMES
        assertEquals(12, times.size)
        assertEquals("08:00", times.first().startTime)
        assertEquals("22:30", times.last().endTime)
        var prevEnd: java.time.LocalTime? = null
        times.forEach { draft ->
            val start = java.time.LocalTime.parse(draft.startTime)
            val end = java.time.LocalTime.parse(draft.endTime)
            assertEquals(
                45,
                java.time.Duration
                    .between(start, end)
                    .toMinutes(),
            )
            assertTrue("$draft 应紧跟上一节", prevEnd?.let { !start.isBefore(it) } ?: true)
            prevEnd = end
        }
    }

    /** getTimetableRuleTime 的形状：data[校区ID][时段]["classIndexN"] = [起, …, 止] */
    @Test
    fun `rule times are read per campus and band`() {
        val times = GuangXiGcAdapter.parseRuleTimes(RULE_TIME_JSON)
        assertEquals(12, times.size)
        val byNo = times.associateBy { it.sectionNo }
        assertEquals("08:00", byNo.getValue(1).startTime)
        assertEquals("08:45", byNo.getValue(1).endTime)
        // pm 时段的 classIndex1 → 全天第 5 节（偏移 4）
        assertEquals("14:30", byNo.getValue(5).startTime)
        // night 时段的 classIndex3/4 → 全天第 11/12 节
        assertEquals("20:50", byNo.getValue(11).startTime)
        assertEquals("22:30", byNo.getValue(12).endTime)
    }

    @Test
    fun `empty rule times mean the school ships its own`() {
        assertEquals(0, GuangXiGcAdapter.parseRuleTimes(RULE_TIME_EMPTY).size)
    }

    // ---------- 学期元数据 ----------

    /** 周历（school/core/calendar/getWeekList 实测）：18 周，第 1 周周一 2026-08-31 */
    @Test
    fun `week list yields total weeks and first monday`() {
        val (total, firstMonday) = GuangXiGcAdapter.parseWeekList(WEEK_LIST_JSON)!!
        assertEquals(18, total)
        assertEquals("2026-08-31", firstMonday)
    }

    // ---------- 成绩 ----------

    /** 学期列表 → id 到名字的映射（成绩抓取的 term 名与全学期 ID 集） */
    @Test
    fun `semester map pairs ids with names`() {
        val map = CipGradeFetcher.parseSemesterMap(SEMESTER_LIST_JSON)
        assertEquals(
            mapOf(
                "17616125694251788336827988858181" to "2025-2026学年第二学期",
                "17804696533371735297414361881535" to "2026-2027学年第一学期",
            ),
            map,
        )
        assertEquals(0, CipGradeFetcher.parseSemesterMap("not json").size)
        assertEquals(0, CipGradeFetcher.parseSemesterMap(RULE_TIME_EMPTY).size)
    }

    /**
     * 成绩 JSON → 记录。scoreList 条目字段照抄页面 v2.js 模板（courseName /
     * sumScore / examStatus）；sumScore 数字与等级文本原文照收，examStatus 非 0
     * 映射成文案；CIP 无学分绩点 → 0 / null。
     */
    @Test
    fun `cip scores map to grade records`() {
        val names = mapOf("17804696533371735297414361881535" to "2026-2027学年第一学期")
        val records = CipGradeFetcher.parseCipScores(SCORE_JSON, names)
        assertEquals(3, records.size)

        val math = records[0]
        assertEquals("2026-2027学年第一学期", math.term)
        assertEquals("高等数学", math.name)
        assertEquals("89.5", math.score)
        assertEquals("", math.scoreFlag)
        assertEquals(0.0, math.credit, 0.001)
        assertNull(math.gpa)

        assertEquals("优秀", records[1].score)
        assertEquals("缺考", records[2].scoreFlag)
        assertEquals("", records[2].score)
    }

    @Test
    fun `broken cip scores parse to nothing`() {
        assertEquals(0, CipGradeFetcher.parseCipScores("not json", emptyMap()).size)
        assertEquals(0, CipGradeFetcher.parseCipScores("""{"code":500,"msg":"x"}""", emptyMap()).size)
        assertEquals(0, CipGradeFetcher.parseCipScores(RULE_TIME_EMPTY, emptyMap()).size)
    }

    /** 成绩全链路：成绩页(csrf) → 学期名 → 成绩 JSON，三请求一循环 */
    @Test
    fun `grade fetch replays the cip score flow`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setBody(SCORE_PAGE_HTML))
                server.enqueue(MockResponse().setBody(SEMESTER_LIST_JSON))
                server.enqueue(MockResponse().setBody(SCORE_JSON))

                val records = GuangXiGcAdapter.fetchGrades(client, server.url("/login").toString())

                assertEquals(3, records.size)
                assertEquals(3, server.requestCount)
                val pagePath = server.takeRequest().path.orEmpty()
                assertTrue(pagePath.contains("teachingStudentScoreViewV2"))
                val baseinfo = server.takeRequest()
                assertTrue(baseinfo.path.orEmpty().contains("getSchoolBaseInfo"))
                assertTrue(baseinfo.body.readUtf8().contains("semester"))
                val scores = server.takeRequest()
                assertTrue(scores.path.orEmpty().contains("getStudentScoreListV2"))
                assertTrue(scores.body.readUtf8().contains("17804696533371735297414361881535"))
                assertTrue(scores.getHeader("X-CSRF-TOKEN") == "score-csrf-token")
            } finally {
                server.shutdown()
            }
        }

    /** 成绩 POST 403（csrf 轮换）时重取一次成绩页再试 */
    @Test
    fun `grade fetch retries with fresh csrf on 403`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setBody(SCORE_PAGE_HTML))
                server.enqueue(MockResponse().setBody(SEMESTER_LIST_JSON))
                server.enqueue(MockResponse().setResponseCode(403))
                server.enqueue(MockResponse().setBody(SCORE_PAGE_HTML))
                server.enqueue(MockResponse().setBody(SCORE_JSON))

                val records = GuangXiGcAdapter.fetchGrades(client, server.url("/login").toString())

                assertEquals(3, records.size)
                assertEquals(5, server.requestCount)
                repeat(5) { server.takeRequest() }
            } finally {
                server.shutdown()
            }
        }

    /** 成绩页是登录页时抛 NeedsLogin（会话失效交回上层） */
    @Test
    fun `grade fetch on login page means needs login`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setBody(LOGIN_PAGE_HTML))
                try {
                    GuangXiGcAdapter.fetchGrades(client, server.url("/login").toString())
                    throw AssertionError("expected NeedsLogin")
                } catch (ignored: JwException.NeedsLogin) {
                    // 预期路径
                }
            } finally {
                server.shutdown()
            }
        }

    @Test
    fun `broken week list parses to nothing`() {
        assertNull(GuangXiGcAdapter.parseWeekList(RULE_TIME_EMPTY))
        assertNull(GuangXiGcAdapter.parseWeekList("not json"))
        assertNull(GuangXiGcAdapter.parseWeekList(WEEK_LIST_EMPTY))
    }

    /** 学期名（school/common/getSchoolBaseInfo 实测）：按 semesterId 精确匹配 */
    @Test
    fun `semester name matches by id`() {
        val name = GuangXiGcAdapter.parseSemesterName(SEMESTER_LIST_JSON, "17804696533371735297414361881535")
        assertEquals("2026-2027学年第一学期", name)
        assertEquals("", GuangXiGcAdapter.parseSemesterName(SEMESTER_LIST_JSON, "别的学期"))
        assertEquals("", GuangXiGcAdapter.parseSemesterName("not json", "x"))
    }

    // ---------- 全链路（MockWebServer 重放真实报文顺序） ----------

    /**
     * 页面 → 规则 → 周历 → 学期名 → 课表 → 钟点，六步一循环；POST 必须带 CSRF 头与 JSON 体。
     * 钟点接口回空 data：与 2026-10 实测一致，作息应回落到本校内置值；
     * 学期元数据应随结果带回（18 周 / 2026-08-31 / 学期名）。
     */
    @Test
    fun `full fetch replays the cip flow`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setBody(PAGE_HTML))
                server.enqueue(MockResponse().setBody(RULE_JSON))
                server.enqueue(MockResponse().setBody(WEEK_LIST_JSON))
                server.enqueue(MockResponse().setBody(SEMESTER_LIST_JSON))
                server.enqueue(MockResponse().setBody(TIMETABLE_JSON))
                server.enqueue(MockResponse().setBody(RULE_TIME_EMPTY))

                val result = GuangXiGcAdapter.fetchSchedule(client, server.url("/login").toString(), totalWeeks = 20)

                assertEquals(5, result.courses.size)
                assertTrue(result.sectionTimes.contains(GuangXiGcAdapter.SECTION_TIMES.first()))
                val meta = result.meta!!
                assertEquals(18, meta.totalWeeks)
                assertEquals("2026-08-31", meta.startDate)
                assertEquals("2026-2027学年第一学期", meta.semester)

                // 第 1 个请求是课表页；之后五个 POST 都要带 CSRF 头
                assertEquals(6, server.requestCount)
                val pagePath = server.takeRequest().path.orEmpty()
                assertTrue(pagePath.contains("timetable/timetableView/studentTimetable"))
                val rule = server.takeRequest()
                assertEquals("97de3745-c400-4c52-9e4a-3ad630422c48", rule.getHeader("X-CSRF-TOKEN"))
                assertTrue(rule.body.readUtf8().contains("17804696533371735297414361881535"))
                val weekList = server.takeRequest()
                assertTrue(weekList.path.orEmpty().contains("getWeekList"))
                val semester = server.takeRequest()
                assertTrue(semester.path.orEmpty().contains("getSchoolBaseInfo"))
                val timetable = server.takeRequest()
                assertTrue(timetable.path.orEmpty().contains("getStudentTimetable"))
                assertTrue(timetable.body.readUtf8().contains("weekNo"))
                val times = server.takeRequest()
                assertTrue(times.path.orEmpty().contains("getTimetableRuleTime"))
            } finally {
                server.shutdown()
            }
        }

    /** 会话失效时课表页就是登录页，要抛 NeedsLogin 让用户回 WebView 重登 */
    @Test
    fun `login page means needs login`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                server.enqueue(MockResponse().setBody(LOGIN_PAGE_HTML))
                try {
                    GuangXiGcAdapter.fetchSchedule(client, server.url("/login").toString(), totalWeeks = 20)
                    throw AssertionError("expected NeedsLogin")
                } catch (ignored: JwException.NeedsLogin) {
                    // 预期路径
                }
            } finally {
                server.shutdown()
            }
        }

    /**
     * 钟点接口空过一次就记住（2026-10 实测本校恒空）：同进程第二次抓取
     * 只发 5 个请求，不再白发 getTimetableRuleTime。
     */
    @Test
    fun `empty rule time is remembered and skipped on next fetch`() =
        runBlocking {
            val server = MockWebServer()
            server.start()
            try {
                repeat(2) {
                    server.enqueue(MockResponse().setBody(PAGE_HTML))
                    server.enqueue(MockResponse().setBody(RULE_JSON))
                    server.enqueue(MockResponse().setBody(WEEK_LIST_JSON))
                    server.enqueue(MockResponse().setBody(SEMESTER_LIST_JSON))
                    server.enqueue(MockResponse().setBody(TIMETABLE_JSON))
                    server.enqueue(MockResponse().setBody(RULE_TIME_EMPTY))
                }
                val url = server.url("/login").toString()
                val first = GuangXiGcAdapter.fetchSchedule(client, url, totalWeeks = 20)
                val second = GuangXiGcAdapter.fetchSchedule(client, url, totalWeeks = 20)

                assertEquals(first.courses, second.courses)
                assertTrue(second.sectionTimes.contains(GuangXiGcAdapter.SECTION_TIMES.first()))
                // 第一次 6 个请求（含钟点），第二次 5 个
                assertEquals(11, server.requestCount)
                val paths = mutableListOf<String>()
                repeat(11) { paths += server.takeRequest().path.orEmpty() }
                assertEquals(1, paths.count { it.contains("getTimetableRuleTime") })
            } finally {
                server.shutdown()
            }
        }

    // ---------- fixtures（2026-10 实测报文摘录） ----------

    private companion object {
        val PAGE_HTML =
            """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8"/>
                <meta name="_csrf" content="97de3745-c400-4c52-9e4a-3ad630422c48"/>
                <meta name="_csrf_header" content="X-CSRF-TOKEN"/>
                <title>我的课表</title>
            </head>
            <body>
            <script>
                var defaultSemesterId = "17804696533371735297414361881535";
                var classNumber = "2";
                var campusId = "17223345130421788816810616956059";
            </script>
            </body>
            </html>
            """.trimIndent()

        val LOGIN_PAGE_HTML =
            """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8"/>
                <meta name="_csrf" content="7c9e5bdd-9033-4624-8349-5c4291e769c0"/>
                <title></title>
            </head>
            <body>
            <script>
                document.title = "系统登录-" + "广西工程职业学院智慧校园平台";
            </script>
            </body>
            </html>
            """.trimIndent()

        val RULE_JSON =
            """
            {"code":0,"data":{
              "timetableRuleId":"17840133420081735237854783680657",
              "semesterId":"17804696533371735297414361881535",
              "weekDays":5,"classNumber":2,
              "morningHour":0,"amHour":4,"noonHour":0,"pmHour":4,"nightHour":4,"eveningHour":0,
              "objectType":1}}
            """.trimIndent()

        val RULE_JSON_PARTIAL =
            """
            {"code":0,"data":{"weekDays":5,"classNumber":2,"amHour":4}}
            """.trimIndent()

        val TIMETABLE_JSON =
            """
            {"code":0,"data":{"studentTimetableList":{
              "morning":{"1_12":[],"1_34":[],"2_12":[]},
              "am":{
                "1_34":[{
                  "semesterId":"17804696533371735297414361881535","weekDay":1,
                  "timeType":"am","classIndex":4,
                  "courseId":"17610975092641735199150010580708","courseName":"高等数学",
                  "classroomNo":"2-601","classroomName":"2-601",
                  "mainTeacherName":"陆梅蓝",
                  "teachingSquadName":"2026级金属智能加工技术#2026级金属智能加工技术1班#高等数学",
                  "startEndWeek":"6,7,8,9,10,11,12,13,14,15,16,17,18",
                  "weekNos":"6,7,8,9,10,11,12,13,14,15,16,17,18"}],
                "2_12":[{
                  "weekDay":2,"timeType":"am","classIndex":2,
                  "courseName":"大学体育1","classroomNo":"操场13",
                  "mainTeacherName":"梁耀福",
                  "startEndWeek":"6,7,8,9,10,11,12,13,14,15,16,17,18",
                  "weekNos":"6,7,8,9,10,11,12,13,14,15,16,17,18"}]},
              "pm":{
                "1_12":[{
                  "weekDay":1,"timeType":"pm","classIndex":2,
                  "courseName":"金属材料热处理及加工应用","classroomNo":"1-607",
                  "mainTeacherName":"陆章金",
                  "startEndWeek":"6,7,8,9,10,11,12,13,14,15,16,17,18",
                  "weekNos":"6,7,8,9,10,11,12,13,14,15,16,17,18"}],
                "4_34":[{
                  "weekDay":4,"timeType":"pm","classIndex":4,
                  "courseName":"国家安全教育","classroomNo":"2-106",
                  "mainTeacherName":"罗艳色",
                  "startEndWeek":"7,8,9,10,11,12,13,14",
                  "weekNos":null}]},
              "night":{
                "1_12":[{
                  "weekDay":1,"timeType":"night","classIndex":2,
                  "courseName":"晚自习","classroomNo":"",
                  "mainTeacherName":"",
                  "startEndWeek":null,"weekNos":null}]}}}}
            """.trimIndent()

        val RULE_TIME_JSON =
            """
            {"code":0,"data":{"17223345130421788816810616956059":{
              "am":{
                "classIndex1":["08:00","08:45"],"classIndex2":["08:55","09:40"],
                "classIndex3":["10:00","10:45"],"classIndex4":["10:55","11:40"]},
              "pm":{
                "classIndex1":["14:30","15:15"],"classIndex2":["15:25","16:10"],
                "classIndex3":["16:20","17:05"],"classIndex4":["17:15","18:00"]},
              "night":{
                "classIndex1":["19:00","19:45"],"classIndex2":["19:55","20:40"],
                "classIndex3":["20:50","21:35"],"classIndex4":["21:45","22:30"]}}}}
            """.trimIndent()

        val RULE_TIME_EMPTY = """{"code":0,"data":{}}"""

        val WEEK_LIST_JSON =
            """
            {"code":0,"data":[
              {"weekId":"w1","weekNo":1,"startDate":"2026-08-31","endDate":"2026-09-06","remarks":null},
              {"weekId":"w2","weekNo":2,"startDate":"2026-09-07","endDate":"2026-09-13","remarks":null},
              {"weekId":"w17","weekNo":17,"startDate":"2026-12-21","endDate":"2026-12-27","remarks":null},
              {"weekId":"w18","weekNo":18,"startDate":"2026-12-28","endDate":"2027-01-03","remarks":null}]}
            """.trimIndent()

        val WEEK_LIST_EMPTY = """{"code":0,"data":[]}"""

        /** 成绩页 fixture：非登录态 + meta csrf（结构照抄实测 score_view.html） */
        val SCORE_PAGE_HTML =
            """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8"/>
                <meta name="_csrf" content="score-csrf-token"/>
                <title>我的成绩</title>
            </head>
            <body>
            <div id="app"></div>
            </body>
            </html>
            """.trimIndent()

        /** 成绩 JSON fixture：条目字段照抄页面 v2.js 模板（courseName/sumScore/examStatus） */
        val SCORE_JSON =
            """
            {"code":0,"data":[
              {"semesterId":"17804696533371735297414361881535","scoreList":[
                {"courseName":"高等数学","teacherName":"陆梅蓝","sumScore":89.5,
                 "displayType":1,"examStatus":0,"scoreDetail":{},"retakes":{},"rebuilds":{}},
                {"courseName":"大学英语","teacherName":"罗艳色","sumScore":"优秀",
                 "displayType":2,"examStatus":0,"scoreDetail":{},"retakes":{},"rebuilds":{}},
                {"courseName":"大学物理","teacherName":"陆章金","sumScore":null,
                 "displayType":1,"examStatus":1,"scoreDetail":{},"retakes":{},"rebuilds":{}}]}]}
            """.trimIndent()

        val SEMESTER_LIST_JSON =
            """
            {"code":0,"data":{"semesterList":[
              {"semesterId":"17616125694251788336827988858181","semesterName":"2025-2026学年第二学期",
               "startDate":"2026-03-08T16:00:00.000+00:00","endDate":"2026-07-09T16:00:00.000+00:00",
               "schoolYear":"2025","semesterIndex":2},
              {"semesterId":"17804696533371735297414361881535","semesterName":"2026-2027学年第一学期",
               "startDate":"2026-08-30T16:00:00.000+00:00","endDate":"2027-01-02T16:00:00.000+00:00",
               "schoolYear":"2026","semesterIndex":1}]}}
            """.trimIndent()
    }
}
