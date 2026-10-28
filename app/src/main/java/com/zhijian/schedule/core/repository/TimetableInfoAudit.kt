package com.zhijian.schedule.core.repository

/**
 * 抓取后的「课表信息核对」提示。
 *
 * 课表信息（学期 / 开学日期 / 总周数）**不跟随**教务抓取改写：它决定周次如何映射到
 * 具体日期，写坏了整学期都会错，而用户很难发现。代价是换学校、换学期后旧值会安静地
 * 留在这儿，所以抓完主动提示一句，让用户自己去高级设置核对。
 *
 * 只报**有据可查**的两件事，不猜：
 * - 学期为空：换学校后最常见的遗留状态；
 * - 教务地址与上次成功同步时不同：换学校 / 换校区的确定证据。
 *
 * 特意不判「总周数设小了」——[com.zhijian.schedule.core.util.WeekMask] 会把超出总周数的
 * 周次直接丢掉，解析出的位掩码长度恒等于总周数，从草稿这里拿不到越界证据。
 * 要检测这件事得让解析器把原始周次单独带出来，属于另一件事。
 *
 * 纯逻辑、不碰 Android API（项目约定，见 CONTRIBUTING 第 3 节）。
 */
object TimetableInfoAudit {
    private const val SEMESTER_MISSING = "课表信息未填写学期，建议在高级设置里核对"
    private const val SCHOOL_CHANGED = "教务地址与上次同步时不同，建议核对学期与开学日期"

    /**
     * @param semester 当前课表的学期字符串
     * @param schoolChanged 本次抓取的教务地址与上次成功同步时是否不同
     * @return 需要用户核对的提示；无需处理时返回 null（调用方不显示）
     */
    fun audit(
        semester: String,
        schoolChanged: Boolean,
    ): String? {
        val missing = semester.isBlank()
        return when {
            missing && schoolChanged -> SEMESTER_MISSING + "；" + SCHOOL_CHANGED
            missing -> SEMESTER_MISSING
            schoolChanged -> SCHOOL_CHANGED
            else -> null
        }
    }

    /**
     * 取教务地址里的主机名，作为「是不是同一所学校」的判定依据。
     *
     * 不用整条 URL：课表页的 query 里带着学期、类型参数，学期一换就变，
     * 拿整条比会把同校的正常同步误报成换学校。
     */
    fun hostOf(url: String): String {
        val scheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://([^/?#]+)")
        val authority = scheme.find(url.trim())?.groupValues?.get(1) ?: return ""
        return authority.substringAfter('@').substringBefore(':').lowercase()
    }
}
