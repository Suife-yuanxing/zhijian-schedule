package com.zhijian.schedule.core.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.zhijian.schedule.core.theme.GridStyles
import com.zhijian.schedule.core.theme.ThemeIds
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

data class AppSettings(
    val activeTableId: Long = 0L,
    val themeId: String = ThemeIds.PAPER,
    /** 网格语言：editorial / block，与主题皮肤正交 */
    val gridStyle: String = GridStyles.EDITORIAL,
    val darkMode: Int = 0,
    val showWeekend: Boolean = false,
    val reminderEnabled: Boolean = true,
    val reminderMinutes: Int = 10,
    val autoSyncEnabled: Boolean = false,
    val jwUrl: String = "",
    val jwSchool: String = "",
    /** 上次成功同步时教务地址的主机名；用于判断「是不是换了学校/校区」 */
    val jwSyncedHost: String = "",
    val lastSyncTime: Long = 0L,
    val calendarSyncEnabled: Boolean = false,
    val capsuleEnabled: Boolean = false,
    /** 临近状态切换时用系统闹钟兜底（状态栏会短暂出现闹钟图标）；默认开启保证准时 */
    val capsuleAlarmFallbackEnabled: Boolean = true,
    val gradesFetchedAt: Long = 0L,
    val holidayFetchedAt: Long = 0L,
    /** 课程脚本远程仓库地址（index.json）；空 = 未配置 */
    val jwScriptIndexUrl: String = "",
    /** 「镜像加速」开关；默认开启 —— GitHub 直连在国内经常不可用，镜像作为自动退路 */
    val mirrorsEnabled: Boolean = true,
    /** 教务登录页自动填充已保存的账号密码；默认关闭，由用户在高级设置里显式打开 */
    val jwAutoFillEnabled: Boolean = false,
)

/**
 * 全应用的偏好读写门面：一个 key 一个 setter，没有逻辑、只有 dataStore.edit 一行。
 *
 * detekt 的 TooManyFunctions 在 20 个函数处触发。这里的取舍是**保持扁平**：
 * 把 setter 拆成 ThemeSettings / JwSettings / SyncSettings 三个子门面，会多出三层
 * 依赖注入与三处「到底该找哪个门面」的检索成本，而它们共享同一个 DataStore 实例——
 * 扁平结构反而让「所有 key 都在一个文件里」这件事一直成立。
 */
@Singleton
@Suppress("TooManyFunctions")
class SettingsStore
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) {
        private object Keys {
            val ACTIVE_TABLE = longPreferencesKey("active_table_id")
            val THEME = stringPreferencesKey("theme_id")
            val GRID_STYLE = stringPreferencesKey("grid_style")
            val DARK_MODE = intPreferencesKey("dark_mode")
            val SHOW_WEEKEND = booleanPreferencesKey("show_weekend")
            val REMINDER_ENABLED = booleanPreferencesKey("reminder_enabled")
            val REMINDER_MINUTES = intPreferencesKey("reminder_minutes")
            val AUTO_SYNC = booleanPreferencesKey("auto_sync_enabled")
            val JW_URL = stringPreferencesKey("jw_url")
            val JW_SCHOOL = stringPreferencesKey("jw_school")
            val JW_SYNCED_HOST = stringPreferencesKey("jw_synced_host")
            val LAST_SYNC = longPreferencesKey("last_sync_time")
            val CALENDAR_SYNC = booleanPreferencesKey("calendar_sync_enabled")
            val CAPSULE = booleanPreferencesKey("capsule_enabled")
            val CAPSULE_ALARM_FALLBACK = booleanPreferencesKey("capsule_alarm_fallback")
            val GRADES_FETCHED = longPreferencesKey("grades_fetched_at")
            val HOLIDAY_FETCHED = longPreferencesKey("holiday_fetched_at")
            val JW_SCRIPT_INDEX = stringPreferencesKey("jw_script_index_url")
            val MIRRORS = booleanPreferencesKey("update_mirrors_enabled")
            val CALENDAR_FINGERPRINT = stringPreferencesKey("calendar_sync_fingerprint")
            val SETTINGS_SCHEMA = intPreferencesKey("settings_schema")
            val JW_AUTOFILL = booleanPreferencesKey("jw_autofill_enabled")
        }

        val settings: Flow<AppSettings> =
            dataStore.data.map { p ->
                AppSettings(
                    activeTableId = p[Keys.ACTIVE_TABLE] ?: 0L,
                    themeId = p[Keys.THEME] ?: ThemeIds.PAPER,
                    gridStyle = p[Keys.GRID_STYLE] ?: GridStyles.EDITORIAL,
                    darkMode = p[Keys.DARK_MODE] ?: 0,
                    showWeekend = p[Keys.SHOW_WEEKEND] ?: false,
                    reminderEnabled = p[Keys.REMINDER_ENABLED] ?: true,
                    reminderMinutes = p[Keys.REMINDER_MINUTES] ?: 10,
                    autoSyncEnabled = p[Keys.AUTO_SYNC] ?: false,
                    jwUrl = p[Keys.JW_URL] ?: "",
                    jwSchool = p[Keys.JW_SCHOOL] ?: "",
                    jwSyncedHost = p[Keys.JW_SYNCED_HOST] ?: "",
                    lastSyncTime = p[Keys.LAST_SYNC] ?: 0L,
                    calendarSyncEnabled = p[Keys.CALENDAR_SYNC] ?: false,
                    capsuleEnabled = p[Keys.CAPSULE] ?: false,
                    capsuleAlarmFallbackEnabled = p[Keys.CAPSULE_ALARM_FALLBACK] ?: true,
                    gradesFetchedAt = p[Keys.GRADES_FETCHED] ?: 0L,
                    holidayFetchedAt = p[Keys.HOLIDAY_FETCHED] ?: 0L,
                    jwScriptIndexUrl = p[Keys.JW_SCRIPT_INDEX] ?: "",
                    mirrorsEnabled = p[Keys.MIRRORS] ?: true,
                    jwAutoFillEnabled = p[Keys.JW_AUTOFILL] ?: false,
                )
            }

        suspend fun setActiveTable(id: Long) = dataStore.edit { it[Keys.ACTIVE_TABLE] = id }

        suspend fun setTheme(id: String) = dataStore.edit { it[Keys.THEME] = id }

        suspend fun setGridStyle(id: String) = dataStore.edit { it[Keys.GRID_STYLE] = id }

        suspend fun setDarkMode(mode: Int) = dataStore.edit { it[Keys.DARK_MODE] = mode }

        suspend fun setShowWeekend(show: Boolean) = dataStore.edit { it[Keys.SHOW_WEEKEND] = show }

        suspend fun setReminder(
            enabled: Boolean,
            minutes: Int,
        ) = dataStore.edit {
            it[Keys.REMINDER_ENABLED] = enabled
            it[Keys.REMINDER_MINUTES] = minutes
        }

        suspend fun setAutoSync(enabled: Boolean) = dataStore.edit { it[Keys.AUTO_SYNC] = enabled }

        suspend fun setJw(
            url: String,
            school: String,
        ) = dataStore.edit {
            it[Keys.JW_URL] = url
            it[Keys.JW_SCHOOL] = school
        }

        suspend fun setCalendarSync(enabled: Boolean) = dataStore.edit { it[Keys.CALENDAR_SYNC] = enabled }

        suspend fun setCapsule(enabled: Boolean) = dataStore.edit { it[Keys.CAPSULE] = enabled }

        suspend fun setCapsuleAlarmFallback(enabled: Boolean) =
            dataStore.edit {
                it[Keys.CAPSULE_ALARM_FALLBACK] = enabled
            }

        suspend fun setGradesFetchedAt(time: Long) = dataStore.edit { it[Keys.GRADES_FETCHED] = time }

        suspend fun setHolidayFetchedAt(time: Long) = dataStore.edit { it[Keys.HOLIDAY_FETCHED] = time }

        suspend fun setMirrorsEnabled(enabled: Boolean) = dataStore.edit { it[Keys.MIRRORS] = enabled }

        /** 教务账号密码自动填充开关（凭据本体另有 Keystore 加密存储，见 JwCredentialStore） */
        suspend fun setJwAutoFillEnabled(enabled: Boolean) = dataStore.edit { it[Keys.JW_AUTOFILL] = enabled }

        suspend fun setJwScriptIndexUrl(url: String) = dataStore.edit { it[Keys.JW_SCRIPT_INDEX] = url.trim() }

        /**
         * v1.0.6 及以前的镜像开关默认关闭，升级用户在直连不可用时完全无法下载更新。
         * schema 2 起强制打开一次（用户之后仍可在检查更新窗口关闭）；同时保留对用户
         * 后续选择的尊重，不再重复覆盖。
         */
        suspend fun migrateIfNeeded() {
            dataStore.edit { prefs ->
                val schema = prefs[Keys.SETTINGS_SCHEMA] ?: 1
                if (schema < 2) {
                    prefs[Keys.MIRRORS] = true
                    prefs[Keys.SETTINGS_SCHEMA] = 2
                }
            }
        }

        /** 已写入系统日历的课表内容指纹（内部状态，不参与 AppSettings） */
        suspend fun calendarFingerprint(): String? = dataStore.data.map { it[Keys.CALENDAR_FINGERPRINT] }.first()

        suspend fun setCalendarFingerprint(value: String) = dataStore.edit { it[Keys.CALENDAR_FINGERPRINT] = value }

        /**
         * 记录一次成功同步：时间 + 教务主机名。
         *
         * 主机名单独存是为了判断「这次是不是换了学校」——课表页 URL 带学期参数，
         * 拿整条比会把同校的正常同步误报成换学校。放在类尾部实现，避免把上面的
         * 存量方法整体推下去、让 ktlint baseline 的行号集体漂移。
         */
        suspend fun setSyncMark(
            time: Long,
            host: String,
        ) = dataStore.edit {
            it[Keys.LAST_SYNC] = time
            it[Keys.JW_SYNCED_HOST] = host
        }
    }
