package com.zhijian.schedule.navigation

import androidx.navigation.NavType
import androidx.navigation.navArgument
import com.zhijian.schedule.feature.profile.ProfileEntryKeys

/**
 * 每个底部 tab 一个独立嵌套图。
 * 不能把二级页与三个 tab 平铺在同一个图里：popUpTo(start){saveState}+restoreState
 * 会把「被弹出页面」的状态挂在起始目的地键下，之后恢复课表 tab 时可能把
 * 「我的 / 今日」的页面原样恢复出来（点课表跳到我的）。嵌套图按 tab 隔离存取。
 */
object Routes {
    const val TODAY_TAB = "today_tab"
    const val SCHEDULE_TAB = "schedule_tab"
    const val PROFILE_TAB = "profile_tab"

    const val TODAY = "today"
    const val SCHEDULE = "schedule"
    const val PROFILE = "profile"

    const val SCHEDULE_IMPORTER = "schedule_importer"
    const val SCHEDULE_JW_IMPORT = "schedule_jw_import"
    const val SCHEDULE_EDITOR = "schedule_editor?courseId={courseId}&day={day}&section={section}"

    const val TODAY_EDITOR = "today_editor?courseId={courseId}&day={day}&section={section}"

    const val PROFILE_IMPORTER = "profile_importer"
    const val PROFILE_JW_IMPORT = "profile_jw_import"
    const val PROFILE_SETTINGS = "profile_settings"
    const val PROFILE_GRADES = "profile_grades?entryKey={entryKey}"
    const val PROFILE_EXAMS = "profile_exams"
    const val PROFILE_JW_SCRIPTS = "profile_jw_scripts"

    fun profileGrades(entryKey: String = ProfileEntryKeys.GRADES): String = "profile_grades?entryKey=$entryKey"

    fun scheduleEditor(
        courseId: Long? = null,
        day: Int? = null,
        section: Int? = null,
    ): String = "schedule_editor?courseId=${courseId ?: -1L}&day=${day ?: -1}&section=${section ?: -1}"

    fun todayEditor(
        courseId: Long? = null,
        day: Int? = null,
        section: Int? = null,
    ): String = "today_editor?courseId=${courseId ?: -1L}&day=${day ?: -1}&section=${section ?: -1}"
}

/** 课程编辑页参数：今日 / 课表两个入口共用（缺省 -1 表示未指定） */
internal val editorArgs =
    listOf(
        navArgument("courseId") {
            type = NavType.LongType
            defaultValue = -1L
        },
        navArgument("day") {
            type = NavType.IntType
            defaultValue = -1
        },
        navArgument("section") {
            type = NavType.IntType
            defaultValue = -1
        },
    )

/** 成绩页入口参数：入口卡 key 决定分段级联的落点 */
internal val gradesArgs =
    listOf(
        navArgument("entryKey") {
            type = NavType.StringType
            defaultValue = ProfileEntryKeys.GRADES
        },
    )
