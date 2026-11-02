package com.zhijian.schedule.feature.settings

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zhijian.schedule.core.calendar.CalendarSyncManager
import com.zhijian.schedule.core.capsule.CapsuleCoordinator
import com.zhijian.schedule.core.capsule.CapsuleNotifier
import com.zhijian.schedule.core.capsule.CapsuleReliability
import com.zhijian.schedule.core.capsule.LiveCapsuleService
import com.zhijian.schedule.core.data.BackupManager
import com.zhijian.schedule.core.data.db.SectionTimeEntity
import com.zhijian.schedule.core.data.db.TimetableEntity
import com.zhijian.schedule.core.data.prefs.AppSettings
import com.zhijian.schedule.core.data.prefs.SettingsStore
import com.zhijian.schedule.core.jw.credential.JwCredentialStore
import com.zhijian.schedule.core.jw.credential.JwCredentialSummary
import com.zhijian.schedule.core.notifications.AlarmScheduler
import com.zhijian.schedule.core.repository.ScheduleRepository
import com.zhijian.schedule.core.repository.SemesterStartDateMemory
import com.zhijian.schedule.core.ui.island.IslandHintTone
import com.zhijian.schedule.core.ui.island.IslandNotice
import com.zhijian.schedule.core.util.TimeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val timetable: TimetableEntity? = null,
    val sections: List<SectionTimeEntity> = emptyList(),
    val exactAlarmOk: Boolean = true,
    val calendarAvailable: Boolean = false,
    val calendarPermissionGranted: Boolean = false,
    /** 本次会话里被拒绝过；用于把「去授权」换成「去设置」 */
    val calendarPermissionDenied: Boolean = false,
    val calendarPermissionPermanentlyDenied: Boolean = false,
    /** 系统是否放行实时活动（流体云）；null = 系统不支持查询 */
    val capsulePromoteAllowed: Boolean? = null,
    /** 是否已允许后台运行（忽略电池优化）；未允许时临近切换会用系统闹钟兜底 */
    val batteryOptimizationIgnored: Boolean = true,
    val busy: Boolean = false,
    val message: String? = null,
    /** 已保存的教务账号摘要；null = 没存过或密钥失效读不出来。**永远不含密码** */
    val jwCredential: JwCredentialSummary? = null,
)

/**
 * 设置页的单一状态持有者：课表信息、作息、提醒、胶囊、日历同步、教务凭据、备份都在这一页。
 *
 * 构造参数 9 个，超过 detekt 的 8。这里不拆的理由：这些依赖全是 @Singleton 的领域服务，
 * 拆成多个 ViewModel 后设置页要同时观察 3 个 state，而它们共享同一份 AppSettings 流——
 * 分开反而要各自订阅、各自处理「另一个 VM 改了设置」的同步问题。
 */
@HiltViewModel
@Suppress("LongParameterList")
class SettingsViewModel
    @Inject
    constructor(
        private val repository: ScheduleRepository,
        private val settingsStore: SettingsStore,
        private val backupManager: BackupManager,
        private val calendarSyncManager: CalendarSyncManager,
        private val alarmScheduler: AlarmScheduler,
        private val capsuleCoordinator: CapsuleCoordinator,
        private val credentialStore: JwCredentialStore,
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        var uiState by mutableStateOf(SettingsUiState())
            private set

        /** 用户从权限设置页返回后，若已授权就补上这次开启 */
        private var pendingCalendarEnable = false

        /**
         * 设置页的浮岛提示（与本 App 别处的浮岛同一条通道，见 IslandNoticeHost）。
         *
         * 与 [SettingsUiState.message] 分工：那条是**常驻**在页面顶部的一行结果说明
         * （「课表信息已保存」），说的是刚刚做完的事；这条会自己收起，说的是**还差什么**
         * ——它每次进设置页都该出现，直到用户真的填了为止。
         */
        var notice by mutableStateOf<IslandNotice?>(null)
            private set

        /** 与 [notice] 分开，是为了让退场动画有内容可演（同 IslandNoticeHost 的契约） */
        var noticeVisible by mutableStateOf(false)
            private set

        /** 当前挂着的那条「还缺开学日期」的文案；null = 没有这条待办 */
        private var startDatePrompt: String? = null

        init {
            uiState =
                uiState.copy(
                    calendarPermissionGranted = calendarSyncManager.hasPermission(),
                    calendarAvailable = calendarSyncManager.hasCalendarProvider(),
                )
            viewModelScope.launch {
                credentialStore.refresh()
                credentialStore.summary.collect { summary -> uiState = uiState.copy(jwCredential = summary) }
            }
            viewModelScope.launch {
                combine(
                    repository.settings,
                    repository.activeTimetable,
                    repository.activeSections,
                ) { settings, timetable, sections -> Triple(settings, timetable, sections) }
                    .collect { (settings, timetable, sections) ->
                        // 开学日期提示跟着课表一起算：换学期（timetable.semester 变了）
                        // 就得重新问一次，而学期名只在这条流里出现
                        refreshStartDateNotice(settings, timetable)
                        uiState =
                            uiState.copy(
                                settings = settings,
                                timetable = timetable,
                                sections = sections,
                                exactAlarmOk = alarmScheduler.canScheduleExact(),
                                // 不在主数据流里同步查询 Provider：系统日历查询偶发阻塞时，
                                // 会把课表/作息等首屏数据一起卡在初始态。可用性由 init 与权限回调单独刷新。
                                calendarPermissionGranted = calendarSyncManager.hasPermission(),
                                capsulePromoteAllowed = CapsuleNotifier.canPromote(context),
                                batteryOptimizationIgnored = CapsuleReliability.isBatteryOptimizationIgnored(context),
                            )
                    }
            }
        }

        /**
         * 开学日期缺来源时，弹一条浮岛提醒（走与本 App 别处同一条通道）。
         *
         * ## 判据为什么是「这个学期不在记忆里」
         * 课表实体上的 startDate **永远有值**——首次建表时写的是当天那一周的周一。
         * 从它身上分不出「这是教务给的 / 用户填的」还是「这是建表那天猜的」。
         * 所以权威性单独记一张表（[SemesterStartDateMemory]）：教务给了就记、
         * 用户填了就记，**没记过 = 没有来源**。这条判据不需要认识任何一家教务。
         *
         * ## 为什么只在文案变化时重弹
         * 这条流会被节假日刷新、跨零点、改任意设置反复触发。每次都取新时间戳
         * 重弹一遍，用户在设置页待多久就被打扰多久。文案没变就不动它。
         */
        private fun refreshStartDateNotice(
            settings: AppSettings,
            timetable: TimetableEntity?,
        ) {
            val text =
                timetable?.let {
                    SemesterStartDateMemory.prompt(it.semester, settings.semesterStartDates)
                }
            // 与「当前挂着哪条待办」比，而不是与 notice.message 比：
            // 同一条通道上还会出现别的话（「课表信息已保存」），拿它比会把那些话吞掉
            if (text == startDatePrompt) return
            startDatePrompt = text
            if (text == null) {
                // 待办消失了：只在正挂着这条待办时才收起，别把别的提示顺手关掉
                if (notice?.tone == IslandHintTone.Pending) noticeVisible = false
                return
            }
            notice = IslandNotice(tone = IslandHintTone.Pending, message = text, at = System.currentTimeMillis())
            noticeVisible = true
        }

        /** 浮岛提示的进度条走完：收起它（数据留着，退场动画才有内容可演） */
        fun dismissNotice() {
            noticeVisible = false
        }

        private suspend fun calendarAvailable(): Boolean =
            withContext(Dispatchers.IO) {
                // 未授权时查询 Provider 必然 SecurityException，不能据此判定「没有日历」，
                // 否则开关被禁用，权限弹窗永远弹不出来
                if (calendarSyncManager.hasPermission()) {
                    calendarSyncManager.resolveAuthority() != null
                } else {
                    calendarSyncManager.hasCalendarProvider()
                }
            }

        /** 从系统设置返回后刷新权限、流体云放行状态，并补上待开启的日历同步 */
        fun refreshRuntimeState() {
            viewModelScope.launch {
                val granted = calendarSyncManager.hasPermission()
                // 先把会挂起的查询做完再读取 uiState：Provider 探测期间 combine 可能已写入
                // 课表/作息，若先拷贝旧状态、挂起回来再赋值，会把首屏数据回滚成空初始态
                val available = calendarAvailable()
                val promoteAllowed = CapsuleNotifier.canPromote(context)
                val batteryIgnored = CapsuleReliability.isBatteryOptimizationIgnored(context)
                val exactAlarmOk = alarmScheduler.canScheduleExact()
                uiState =
                    uiState.copy(
                        capsulePromoteAllowed = promoteAllowed,
                        batteryOptimizationIgnored = batteryIgnored,
                        exactAlarmOk = exactAlarmOk,
                        calendarPermissionGranted = granted,
                        calendarAvailable = available,
                        calendarPermissionPermanentlyDenied =
                            if (granted) false else uiState.calendarPermissionPermanentlyDenied,
                    )
                if (!granted) {
                    // 同步开关还开着但权限被收回：重新授权后自动补刷
                    if (uiState.settings.calendarSyncEnabled) pendingCalendarEnable = true
                    return@launch
                }
                when {
                    pendingCalendarEnable -> {
                        pendingCalendarEnable = false
                        setCalendarSyncInternal(true)
                    }
                    uiState.settings.calendarSyncEnabled -> syncCalendarIfNeeded()
                }
            }
        }

        /** 权限被收回/恢复后开关状态可能和系统不一致，回前台时按指纹补刷一次 */
        private suspend fun syncCalendarIfNeeded() {
            val timetable = repository.activeTimetable.first() ?: return
            val courses = repository.activeCourses.first()
            val sections = repository.activeSections.first()
            calendarSyncManager.syncIfNeeded(courses, timetable, sections)
        }

        fun refreshAlarmPermission() {
            uiState = uiState.copy(exactAlarmOk = alarmScheduler.canScheduleExact())
        }

        // ---------- 课表信息 ----------

        fun saveTimetable(
            name: String,
            semester: String,
            startDate: String,
            totalWeeks: Int,
        ) {
            val timetable = uiState.timetable ?: return
            if (TimeUtils.parseDate(startDate) == null) {
                uiState = uiState.copy(message = "开学日期格式应为 yyyy-MM-dd")
                return
            }
            viewModelScope.launch {
                val merged = semester.trim().ifBlank { timetable.semester }
                repository.updateTimetable(
                    timetable.copy(
                        name = name.trim().ifBlank { timetable.name },
                        semester = merged,
                        startDate = startDate.trim(),
                        totalWeeks = totalWeeks.coerceIn(1, 30),
                    ),
                )
                // 用户亲手填的开学日期按学期记下来：下学期（学期名变了）才会再问一次。
                // 这是「填一次、按学期复用」里的那一半——不记的话，下次教务抓不到
                // 日期时仍然只能沿用建表当天，等于白填。
                settingsStore.rememberSemesterStartDate(merged, startDate.trim())
                uiState = uiState.copy(message = "课表信息已保存")
                postNotice(IslandHintTone.Done, "开学日期已记住 · 「" + merged + "」以后自动复用")
            }
        }

        /**
         * 设置页浮岛提示的唯一入口（与本 App 别处同一条通道）。
         *
         * 令牌取系统时钟而不是由调用方传：它既是这条提示的身份，也是进度条的重置令牌
         * （见 [IslandNotice.at]）。让调用方参与只会引入一个不可信的输入。
         */
        private fun postNotice(
            tone: IslandHintTone,
            message: String,
        ) {
            notice = IslandNotice(tone = tone, message = message, at = System.currentTimeMillis())
            noticeVisible = true
        }

        fun saveSectionTime(
            section: SectionTimeEntity,
            start: String,
            end: String,
        ) {
            if (TimeUtils.parseTime(start) == null || TimeUtils.parseTime(end) == null) {
                uiState = uiState.copy(message = "时间格式应为 HH:mm")
                return
            }
            viewModelScope.launch {
                repository.updateSectionTime(section.copy(startTime = start.trim(), endTime = end.trim()))
                uiState = uiState.copy(message = "作息时间已更新")
            }
        }

        // ---------- 备份 ----------

        fun exportBackup(uri: Uri) {
            viewModelScope.launch {
                uiState = uiState.copy(busy = true)
                val result =
                    runCatching {
                        val json = backupManager.exportJson()
                        withContext(Dispatchers.IO) {
                            context.contentResolver.openOutputStream(uri)?.use { out ->
                                out.write(json.toByteArray(Charsets.UTF_8))
                            } ?: throw IllegalStateException("无法写入文件")
                        }
                        "备份已导出"
                    }
                uiState = uiState.copy(busy = false, message = result.getOrElse { "导出失败：${it.message}" })
            }
        }

        fun importBackup(uri: Uri) {
            viewModelScope.launch {
                uiState = uiState.copy(busy = true)
                val result =
                    runCatching {
                        val content =
                            withContext(Dispatchers.IO) {
                                context.contentResolver
                                    .openInputStream(
                                        uri,
                                    )?.use { it.readBytes().toString(Charsets.UTF_8) }
                                    ?: throw IllegalStateException("无法读取文件")
                            }
                        backupManager.restoreJson(content).getOrThrow()
                    }
                uiState = uiState.copy(busy = false, message = result.getOrElse { "导入失败：${it.message}" })
            }
        }

        // ---------- 日历 ----------

        fun onCalendarPermissionResult(
            granted: Boolean,
            permanentlyDenied: Boolean,
        ) {
            if (granted) {
                pendingCalendarEnable = false
                uiState =
                    uiState.copy(
                        calendarPermissionGranted = true,
                        calendarPermissionDenied = false,
                        calendarPermissionPermanentlyDenied = false,
                    )
                viewModelScope.launch { setCalendarSyncInternal(true) }
            } else {
                uiState =
                    uiState.copy(
                        calendarPermissionGranted = false,
                        calendarPermissionDenied = true,
                        calendarPermissionPermanentlyDenied = permanentlyDenied,
                        message =
                            if (permanentlyDenied) {
                                "日历权限已被拒绝，请到系统设置中开启"
                            } else {
                                "未授予日历权限，无法写入系统日历"
                            },
                    )
            }
        }

        /** 用户点了「去设置」：授权成功后回到前台自动开启同步 */
        fun onCalendarPermissionSettingsOpened() {
            pendingCalendarEnable = true
        }

        fun setCalendarSync(enabled: Boolean) {
            viewModelScope.launch { setCalendarSyncInternal(enabled) }
        }

        private suspend fun setCalendarSyncInternal(enabled: Boolean) {
            if (enabled && !calendarSyncManager.hasPermission()) {
                uiState = uiState.copy(message = "请先授予日历读写权限")
                return
            }
            uiState = uiState.copy(busy = true)
            val timetable = repository.activeTimetable.first()
            val courses = repository.activeCourses.first()
            val sections = repository.activeSections.first()
            val result =
                if (enabled) {
                    if (timetable == null) {
                        Result.failure(IllegalStateException("没有可用课表"))
                    } else {
                        calendarSyncManager.syncAll(courses, timetable, sections)
                    }
                } else {
                    calendarSyncManager.clearAll()
                }
            settingsStore.setCalendarSync(enabled && result.isSuccess)
            val message =
                result.fold(
                    onSuccess = { n -> if (enabled) "已写入 $n 个课程事件" else "已清除日历中的课程" },
                    onFailure = { "日历同步失败：${it.message}" },
                )
            val available = calendarAvailable()
            uiState =
                uiState.copy(
                    busy = false,
                    message = message,
                    calendarPermissionGranted = calendarSyncManager.hasPermission(),
                    calendarAvailable = available,
                )
        }

        // ---------- 实时胶囊 ----------

        fun setCapsule(enabled: Boolean) {
            viewModelScope.launch {
                settingsStore.setCapsule(enabled)
                if (enabled) {
                    LiveCapsuleService.start(context)
                    // 开启胶囊时顺手申请后台运行：否则 ColorOS 会冻结进程，状态切换只能靠闹钟兜底
                    if (!CapsuleReliability.isBatteryOptimizationIgnored(context)) {
                        CapsuleReliability.requestIgnoreBatteryOptimization(context)
                    }
                } else {
                    LiveCapsuleService.stop(context)
                    capsuleCoordinator.cancelTransition()
                }
                uiState =
                    uiState.copy(
                        capsulePromoteAllowed = CapsuleNotifier.canPromote(context),
                        batteryOptimizationIgnored = CapsuleReliability.isBatteryOptimizationIgnored(context),
                        exactAlarmOk = alarmScheduler.canScheduleExact(),
                    )
            }
        }

        fun setCapsuleAlarmFallback(enabled: Boolean) {
            viewModelScope.launch { settingsStore.setCapsuleAlarmFallback(enabled) }
        }

        // ---------- 教务账号密码 ----------

        /**
         * 自动填充开关。
         *
         * 关掉时不动已保存的凭据（那是另一件事，得用户单独「清除」）：
         * 「这次不想自动填」和「把密码删掉」是两个不同的意愿，不能合成一个开关。
         */
        fun setJwAutoFill(enabled: Boolean) {
            viewModelScope.launch {
                settingsStore.setJwAutoFillEnabled(enabled)
                uiState =
                    uiState.copy(
                        message = if (enabled) "下次打开教务登录页会自动填入账号密码" else "已关闭自动填充，凭据仍保留",
                    )
            }
        }

        fun clearJwCredential() {
            viewModelScope.launch {
                credentialStore.clear()
                uiState = uiState.copy(message = "已清除保存的教务账号密码")
            }
        }
    }
