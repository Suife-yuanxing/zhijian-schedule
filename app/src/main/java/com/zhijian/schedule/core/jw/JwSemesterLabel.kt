package com.zhijian.schedule.core.jw

/**
 * 学年 + 学期码 → 学期名（如 `2026-2027学年第一学期`）。
 *
 * ## 为什么单独成对象
 * 正方、青果、URP 等厂家的课表页都带「学年 / 学期」下拉，但编码不是一套：
 * 正方用 `3 / 12`，强智 jsxsd 用 `1 / 3` 与 `2 / 12`，还有写 `1 / 2` 的部署。
 * 各适配器各写一份 switch 必然漂移，收敛到这里，改一处全生效。
 *
 * ## 只做「有据可查」的推导
 * 不猜学期、不按当前月份推断（那是 [ZhengFangNewAdapter.termCandidates] 在找不到
 * 页面选中项时的兜底，属于另一个问题）。这里只把页面上**已经写着**的学年学期码
 * 翻译成人话，认不出就返回空串，让调用方保留本地值。
 *
 * 纯逻辑、不碰 Android API（项目约定，见 CONTRIBUTING 第 3 节）。
 */
object JwSemesterLabel {
    /** `2026-2027` / `2026` / `2026-2027-1` 里取前两段学年 */
    private val ACADEMIC_YEAR = Regex("""^(\d{4})(?:\s*-\s*(\d{4}))?""")

    /**
     * @param academicYear 学年，接受 `2026-2027` 或裸 `2026`（后者补成 2026-2027）
     * @param termCode 学期码；认不出返回空串
     * @return 形如 `2026-2027学年第一学期`；任一段认不出返回空串（不覆盖本地值）
     */
    fun of(
        academicYear: String,
        termCode: String,
    ): String {
        val year = yearOf(academicYear)
        val term = termNameOf(termCode)
        if (year.isEmpty() || term.isEmpty()) return ""
        return year + "学年" + term
    }

    /** 学年归一化成 `2026-2027`；只有起始年时补成完整学年 */
    internal fun yearOf(raw: String): String {
        val m = ACADEMIC_YEAR.find(raw.trim()) ?: return ""
        val start = m.groupValues[1].toIntOrNull() ?: return ""
        val end = m.groupValues[2].toIntOrNull() ?: (start + 1)
        // 学年跨度非法（写成 2026-2025 之类）时按起始年推，别把错数据传下去
        val safeEnd = if (end == start + 1) end else start + 1
        return start.toString() + "-" + safeEnd
    }

    /**
     * 学期码 → 第一学期 / 第二学期。
     *
     * 两套编码都要认，否则换一所学校学期名就同步不过来：
     * - 正方：`3` = 第一学期，`12` = 第二学期；
     * - 强智 jsxsd：`1` / `3` = 第一学期，`2` / `12` = 第二学期。
     */
    internal fun termNameOf(raw: String): String =
        when (raw.trim().toIntOrNull()) {
            TERM_FIRST_PLAIN, TERM_FIRST_ALT -> "第一学期"
            TERM_SECOND_PLAIN, TERM_SECOND_ALT -> "第二学期"
            else -> ""
        }

    /** 强智 jsxsd 的第一学期码 */
    private const val TERM_FIRST_PLAIN = 1

    /** 正方 / 强智都用的第一学期码 */
    private const val TERM_FIRST_ALT = 3

    /** 强智 jsxsd 的第二学期码 */
    private const val TERM_SECOND_PLAIN = 2

    /** 正方 / 强智都用的第二学期码 */
    private const val TERM_SECOND_ALT = 12
}
