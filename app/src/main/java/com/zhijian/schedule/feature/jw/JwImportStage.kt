package com.zhijian.schedule.feature.jw

/**
 * 教务导入的三个阶段：① 登录 → ② 进课表页 → ③ 抓取。
 *
 * 为什么把「阶段」从文案里抽出来：改造前这一段是三行纯文字（① 登录 → ② 学生课表 → ③ 抓取课表
 * + 一整行状态提示），既占高度又没有「我现在走到第几步」的信息。抽出 [JwStage] 之后，
 * 界面可以同时用三种视觉通道表达同一条信息：进度点的填充、标签的字重、状态行的颜色，
 * 而文字量不增加。
 *
 * 判定口径**逐字沿用**改造前的四档（登录页 / 统一认证页 / 已进入 / 其它），
 * 只是把它拆成「阶段 + 步骤下标」两个纯值，方便单测钉住。
 */
internal enum class JwStage(
    /** 对应三个步骤里的哪一个（0/1/2），进度点按它点亮 */
    val stepIndex: Int,
) {
    /** 停在教务自己的登录页 */
    LOGIN(0),

    /** 停在统一身份认证（sso.jsp 自动跳转而来）——仍属于「登录」这一步 */
    SSO(0),

    /** 已登录、但还没进课表页 */
    TIMETABLE(1),

    /** 已经进入教务主框架 / 课表页，下一步就是抓取 */
    FETCH(2),
}

/**
 * 当前落脚页 → 阶段。
 *
 * 优先级必须与改造前一致：先判「已进入教务系统」，再判统一认证，最后才是登录页——
 * 因为强智的 xsMain 外壳页 URL 里也带 login 字样，顺序反了会把已登录判成登录页。
 */
internal fun jwStageOf(
    onLoginPage: Boolean,
    onAuthserverPage: Boolean,
    onLoggedInPage: Boolean,
): JwStage =
    when {
        onLoggedInPage -> JwStage.FETCH
        onAuthserverPage -> JwStage.SSO
        onLoginPage -> JwStage.LOGIN
        else -> JwStage.TIMETABLE
    }

/** 三步骤在界面上的固定叫法：两字一顿，与进度点等宽，不会被长校名撑成三种宽度 */
internal val JwStepLabels: List<String> = listOf("登录", "课表", "抓取")

/**
 * 状态行的两段文案：**先说要做什么，再说在什么条件下**。
 *
 * 改造前是一整句平铺（「请用学号 / 密码登录；校外网络请先连校园 WiFi，或经 WebVPN 访问」），
 * 分号两侧的权重完全一样，用户得先读完二十多个字才知道第一步做什么。拆成两行之后，
 * 主句用正文色 + 中等字重，条件句用达标的次要色 + 小一号字：同一块高度里出现层次，
 * 信息一个字都没少。四档文案与改造前逐字对应。
 */
internal data class JwStatusLine(
    val primary: String,
    val secondary: String = "",
)

internal fun jwStatusText(
    stage: JwStage,
    timetablePage: String,
): JwStatusLine =
    when (stage) {
        JwStage.FETCH -> JwStatusLine("已进入教务系统", "点右下「抓取课表」即可导入")
        JwStage.SSO -> JwStatusLine("请在「统一身份认证」页登录", "用学号 + 密码，密码可能与教务不同")
        JwStage.LOGIN ->
            JwStatusLine(
                "请用学号 / 密码登录",
                "校外网络请先连校园 WiFi，或经 WebVPN 访问",
            )
        JwStage.TIMETABLE ->
            JwStatusLine(
                "登录后打开「" + timetablePage + "」页面",
                "再点右下「抓取课表」",
            )
    }
